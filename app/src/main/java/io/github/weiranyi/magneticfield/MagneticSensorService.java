package io.github.weiranyi.magneticfield;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

public class MagneticSensorService extends Service implements SensorEventListener2 {
    public interface SampleListener {
        void onMagneticSample(float x, float y, float z, float magnitude, long timestampNs);
        void onChartSample(float x, float y, float z, float magnitude, long timestampNs);
        void onSensorAccuracyChanged(int accuracy);
        void onSensorAvailabilityChanged(boolean available);
        void onRecordingStateChanged(boolean recording, boolean saving, boolean fileListChanged);
    }

    // ==================== 常量 ====================
    private static final long BACKGROUND_AUTO_STOP_DELAY_MS = 3 * 60 * 1000L;
    private static final long BACKGROUND_AUTO_STOP_FALLBACK_GRACE_MS = 2_000L;
    private static final long BACKGROUND_WAKE_LOCK_TIMEOUT_MS =
            BACKGROUND_AUTO_STOP_DELAY_MS + BACKGROUND_AUTO_STOP_FALLBACK_GRACE_MS + 1_000L;
    private static final long RECORDING_WAKE_LOCK_TIMEOUT_MS = 12 * 60 * 60 * 1000L;
    private static final long CHART_SAMPLE_INTERVAL_MS = 250L;
    private static final long CHART_GAP_CLEAR_THRESHOLD_NS = 5_000_000_000L;  // 5秒无采样视为中断，清空旧缓冲区
    private static final long CHART_WINDOW_NS = 20_000_000_000L;  // 图表显示窗口大小（20秒）
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L;
    private static final int DEFAULT_SAMPLE_RATE_HZ = 0;
    private static final int CHART_HISTORY_SIZE = 14400;
    private static final int AXES_HISTORY_SIZE = 64;
    private static final int MAX_RECORDING_ENTRIES = 1_000_000;
    private static final String CHART_CACHE_FILE = "chart_cache.dat";
    private static final long CHART_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000L;

    // ==================== 静态共享状态 ====================
    private static final CopyOnWriteArrayList<SampleListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Object CHART_LOCK = new Object();
    private static final Object AXES_LOCK = new Object();
    private static final Object RECORDING_LOCK = new Object();
    private static final float[] chartXSamples = new float[CHART_HISTORY_SIZE];
    private static final float[] chartYSamples = new float[CHART_HISTORY_SIZE];
    private static final float[] chartZSamples = new float[CHART_HISTORY_SIZE];
    private static final float[] chartTotalSamples = new float[CHART_HISTORY_SIZE];
    private static final long[] chartTimestampsNs = new long[CHART_HISTORY_SIZE];
    private static final float[] axesXSamples = new float[AXES_HISTORY_SIZE];
    private static final float[] axesYSamples = new float[AXES_HISTORY_SIZE];
    private static final float[] axesZSamples = new float[AXES_HISTORY_SIZE];
    private static final float[] axesTotalSamples = new float[AXES_HISTORY_SIZE];
    private static final long[] axesTimestampsNs = new long[AXES_HISTORY_SIZE];
    private static final java.util.ArrayList<RecordingSample> recordingData = new java.util.ArrayList<>();
    private static volatile MagneticSensorService currentService;
    private static volatile boolean running;
    private static volatile boolean appInBackground;
    private static volatile long backgroundEnteredElapsedMs;
    private static volatile boolean sensorAvailable = true;
    private static volatile boolean hasLastSample;
    private static volatile boolean recording;
    private static volatile boolean recordingSaving;
    private static volatile boolean recordingLimitReached;
    private static volatile long recordingStartedWallTimeMs;
    private static volatile long recordingStartedElapsedNs;
    private static volatile int targetSampleRateHz = DEFAULT_SAMPLE_RATE_HZ;
    private static volatile int lastAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    private static volatile float lastX;
    private static volatile float lastY;
    private static volatile float lastZ;
    private static volatile float lastMagnitude;
    private static volatile long lastTimestampNs;

    // ==================== 实例字段 ====================
    private volatile SensorManager sensorManager;
    private volatile Sensor magneticSensor;
    private volatile boolean sensorRegistered;
    private int chartSampleIndex;
    private int chartSampleCount;
    private int axesSampleIndex;
    private int axesSampleCount;
    private volatile long lastNotificationUpdateMs;
    private long chartOriginTimestampNs;
    private volatile long lastChartSampleTimestampNs;
    private Handler mainHandler;
    private Handler sensorHandler;
    private HandlerThread sensorThread;
    private ExecutorService saveExecutor;
    private PowerManager.WakeLock recordingWakeLock;
    private PowerManager.WakeLock backgroundSamplingWakeLock;
    private boolean shuttingDownAfterBackgroundTimeout;

    private final Runnable backgroundAutoStopRunnable = () -> {
        if (appInBackground && !hasActiveRecordingWork()) {
            shutdownAfterBackgroundTimeout();
        }
    };
    private final Runnable liveNotificationRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running || mainHandler == null) {
                return;
            }
            if (shutdownIfBackgroundTimeoutElapsed()) {
                return;
            }
            keepSensorAndNotificationAlive();
            long intervalMs = recording ? 500L : NOTIFICATION_UPDATE_INTERVAL_MS;
            mainHandler.postDelayed(this, intervalMs);
        }
    };

    // ==================== Service 生命周期 ====================

    public static boolean start(Context context) {
        Intent intent = new Intent(context, MagneticSensorService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            return true;
        } catch (RuntimeException e) {
            android.util.Log.e("MagneticSensorService", "Failed to start foreground service", e);
            return false;
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, MagneticSensorService.class));
    }

    public static boolean isRunning() {
        return running;
    }

    public static void setAppInBackground(boolean inBackground) {
        long nowMs = SystemClock.elapsedRealtime();
        if (inBackground) {
            if (!appInBackground || backgroundEnteredElapsedMs <= 0L) {
                backgroundEnteredElapsedMs = nowMs;
            }
        } else {
            backgroundEnteredElapsedMs = 0L;
        }
        appInBackground = inBackground;
        MagneticSensorService service = currentService;
        if (service != null) {
            service.updateBackgroundAutoStop();
        }
    }

    // ==================== 传感器状态查询 ====================

    public static boolean isSensorAvailable() {
        return sensorAvailable;
    }

    public static int getTargetSampleRateHz() {
        return targetSampleRateHz;
    }

    public static float getActualSampleRateHz() {
        return SensorRateController.actualSampleRateHz;
    }

    public static void setTargetSampleRateHz(int sampleRateHz) {
        targetSampleRateHz = sampleRateHz;
        MagneticSensorService service = currentService;
        if (service != null) {
            service.applyTargetSampleRate();
        }
    }

    // ==================== Listener 管理 ====================

    public static void addListener(SampleListener listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.addIfAbsent(listener);
        final boolean avail = sensorAvailable;
        final int acc = lastAccuracy;
        final boolean rec = recording;
        final boolean recSaving = recordingSaving;
        final long ts = lastTimestampNs;
        final float x = lastX;
        final float y = lastY;
        final float z = lastZ;
        final float mag = lastMagnitude;
        listener.onSensorAvailabilityChanged(avail);
        listener.onSensorAccuracyChanged(acc);
        listener.onRecordingStateChanged(rec, recSaving, false);
        if (ts > 0L) {
            listener.onMagneticSample(x, y, z, mag, ts);
        }
    }

    public static void removeListener(SampleListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

    public static void refreshNotification() {
        updateCurrentForegroundNotification();
    }

    // ==================== Service 回调 ====================

    @Override
    public void onCreate() {
        super.onCreate();
        currentService = this;
        mainHandler = new Handler(Looper.getMainLooper());
        sensorThread = new HandlerThread("MagneticSensorThread");
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());
        saveExecutor = Executors.newSingleThreadExecutor();
        running = true;
        NotificationHelper.ensureNotificationChannel(this);
        startForeground(NotificationHelper.NOTIFICATION_ID, buildNotification(0f));
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD, true);
            if (magneticSensor == null) {
                magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            }
        }
        sensorAvailable = magneticSensor != null;
        notifyAvailability(sensorAvailable);
        loadChartCache();
        registerMagneticSensor();
        if (isRecording()) {
            acquireRecordingWakeLock();
        }
        updateBackgroundAutoStop();
        startLiveNotificationLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && NotificationHelper.ACTION_STOP_RECORDING.equals(intent.getAction())) {
            stopRecordingFromNotification();
            return START_STICKY;
        }
        registerMagneticSensor();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (hasActiveRecordingWork()) {
            registerMagneticSensor();
            updateForegroundNotification();
        } else {
            shutdownAfterBackgroundTimeout();
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            stopForeground(true);
        } catch (RuntimeException e) {
            android.util.Log.w("MagneticSensorService", "Failed to stop foreground", e);
        }
        NotificationHelper.removeForegroundNotification(this);
        unregisterMagneticSensor();
        saveChartCache();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(backgroundAutoStopRunnable);
            mainHandler.removeCallbacks(liveNotificationRunnable);
        }
        sensorAvailable = false;
        notifyAvailability(false);
        if (currentService == this) {
            currentService = null;
        }
        if (saveExecutor != null) {
            saveExecutor.shutdown();
            try {
                if (!saveExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    saveExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                saveExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        releaseBackgroundSamplingWakeLock();
        releaseRecordingWakeLock();
        if (sensorThread != null) {
            sensorThread.quitSafely();
            sensorThread = null;
            sensorHandler = null;
        }
        hasLastSample = false;
        lastTimestampNs = 0L;
        lastX = 0f;
        lastY = 0f;
        lastZ = 0f;
        lastMagnitude = 0f;
        lastAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
        SensorRateController.resetActualSampleRate();
        super.onDestroy();
    }

    // ==================== SensorEventListener2 ====================

    @Override
    public void onSensorChanged(SensorEvent event) {
        // 取消注册后传感器线程的回调可能延迟到达，忽略避免更新已停止的状态
        if (!sensorRegistered) {
            return;
        }
        if (event == null || event.values == null || event.values.length < 3) {
            return;
        }
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        if (!SensorRateController.shouldAcceptSampleForTargetRate(event.timestamp, targetSampleRateHz)) {
            return;
        }
        SensorRateController.updateActualSampleRate(event.timestamp);
        // 使用 SystemClock.elapsedRealtimeNanos() 保证与录制参考时间同一时钟基准
        long nowNs = SystemClock.elapsedRealtimeNanos();
        lastX = x;
        lastY = y;
        lastZ = z;
        lastMagnitude = magnitude;
        lastTimestampNs = nowNs;
        if (!sensorAvailable) {
            sensorAvailable = true;
            notifyAvailability(true);
        }
        hasLastSample = true;
        addAxesSample(x, y, z, magnitude, nowNs);
        addChartSampleIfDue(x, y, z, magnitude, nowNs);
        addRecordingSample(x, y, z, magnitude, nowNs);
        for (SampleListener listener : LISTENERS) {
            listener.onMagneticSample(x, y, z, magnitude, nowNs);
        }
        long nowMs = SystemClock.elapsedRealtime();
        updateLiveNotificationIfDue(nowMs, false);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        lastAccuracy = accuracy;
        for (SampleListener listener : LISTENERS) {
            listener.onSensorAccuracyChanged(accuracy);
        }
    }

    @Override
    public void onFlushCompleted(Sensor sensor) {
        updateLiveNotificationIfDue(SystemClock.elapsedRealtime(), true);
    }

    // ==================== 传感器注册 ====================

    private void registerMagneticSensor() {
        if (!sensorRegistered && sensorManager != null && magneticSensor != null) {
            int samplingPeriodUs = SensorRateController.getSamplingPeriodUs(targetSampleRateHz, recording);
            int batchReportLatencyUs = SensorRateController.getBatchReportLatencyUs(targetSampleRateHz, recording);
            boolean registered;
            if (sensorHandler != null) {
                registered = sensorManager.registerListener(this, magneticSensor,
                        samplingPeriodUs,
                        batchReportLatencyUs, sensorHandler);
            } else {
                registered = sensorManager.registerListener(this, magneticSensor,
                        samplingPeriodUs,
                        batchReportLatencyUs);
            }
            sensorRegistered = registered;
            if (registered) {
                if (!sensorAvailable) {
                    sensorAvailable = true;
                    notifyAvailability(true);
                }
            } else {
                sensorAvailable = false;
                notifyAvailability(false);
            }
        }
    }

    private void applyTargetSampleRate() {
        if (sensorRegistered) {
            unregisterMagneticSensor();
        }
        SensorRateController.resetActualSampleRate();
        SensorRateController.resetTargetRateLimiter(targetSampleRateHz);
        registerMagneticSensor();
    }

    private void reapplySamplingForRecording(boolean isStartingRecording) {
        if (!running) {
            return;
        }
        if (sensorRegistered) {
            unregisterMagneticSensor();
        }
        SensorRateController.resetActualSampleRate();
        SensorRateController.resetTargetRateLimiter(targetSampleRateHz);
        registerMagneticSensor();
        if (isStartingRecording) {
            flushBatchedSensorEvents();
        }
    }

    private void unregisterMagneticSensor() {
        if (sensorRegistered && sensorManager != null) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
    }

    private void flushBatchedSensorEvents() {
        if (!sensorRegistered || sensorManager == null || magneticSensor == null) {
            return;
        }
        sensorManager.flush(this);
    }

    // ==================== WakeLock 管理 ====================

    private void acquireRecordingWakeLock() {
        if (recordingWakeLock != null && recordingWakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        recordingWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MagneticField:RecordingWakeLock");
        recordingWakeLock.setReferenceCounted(false);
        recordingWakeLock.acquire(RECORDING_WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseRecordingWakeLock() {
        if (recordingWakeLock == null) {
            return;
        }
        if (recordingWakeLock.isHeld()) {
            try {
                recordingWakeLock.release();
            } catch (RuntimeException e) {
                android.util.Log.w("MagneticSensorService", "Failed to release wake lock", e);
            }
        }
        recordingWakeLock = null;
    }

    private void acquireBackgroundSamplingWakeLock() {
        if (backgroundSamplingWakeLock != null && backgroundSamplingWakeLock.isHeld()) {
            return;
        }
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager == null) {
            return;
        }
        backgroundSamplingWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MagneticField:BackgroundSamplingWakeLock");
        backgroundSamplingWakeLock.setReferenceCounted(false);
        backgroundSamplingWakeLock.acquire(BACKGROUND_WAKE_LOCK_TIMEOUT_MS);
    }

    private void releaseBackgroundSamplingWakeLock() {
        if (backgroundSamplingWakeLock == null) {
            return;
        }
        if (backgroundSamplingWakeLock.isHeld()) {
            try {
                backgroundSamplingWakeLock.release();
            } catch (RuntimeException e) {
                android.util.Log.w("MagneticSensorService",
                        "Failed to release background wake lock", e);
            }
        }
        backgroundSamplingWakeLock = null;
    }

    // ==================== 通知管理 ====================

    private Notification buildNotification(float magnitude) {
        final boolean isRecording;
        final boolean isSaving;
        synchronized (RECORDING_LOCK) {
            isRecording = recording;
            isSaving = recordingSaving;
        }
        return NotificationHelper.buildNotification(this, magnitude,
                isRecording, isSaving, hasLastSample, lastTimestampNs, recordingStartedWallTimeMs);
    }

    private void updateForegroundNotification() {
        final boolean isRecording;
        final boolean isSaving;
        synchronized (RECORDING_LOCK) {
            isRecording = recording;
            isSaving = recordingSaving;
        }
        NotificationHelper.updateForegroundNotification(this, lastMagnitude,
                isRecording, isSaving, hasLastSample, lastTimestampNs, recordingStartedWallTimeMs);
    }

    private static void updateCurrentForegroundNotification() {
        MagneticSensorService service = currentService;
        if (service != null) {
            service.updateForegroundNotification();
        }
    }

    private static void acquireCurrentRecordingWakeLock() {
        MagneticSensorService service = currentService;
        if (service != null) {
            service.acquireRecordingWakeLock();
        }
    }

    private static void releaseCurrentRecordingWakeLock() {
        MagneticSensorService service = currentService;
        if (service != null) {
            service.releaseRecordingWakeLock();
        }
    }

    private void startLiveNotificationLoop() {
        if (mainHandler == null) {
            return;
        }
        mainHandler.removeCallbacks(liveNotificationRunnable);
        mainHandler.post(liveNotificationRunnable);
    }

    private void keepSensorAndNotificationAlive() {
        flushBatchedSensorEvents();
        updateLiveNotificationIfDue(SystemClock.elapsedRealtime(), false);
    }

    private void updateLiveNotificationIfDue(long nowMs, boolean force) {
        if (!force && nowMs - lastNotificationUpdateMs < NOTIFICATION_UPDATE_INTERVAL_MS) {
            return;
        }
        lastNotificationUpdateMs = nowMs;
        final float magnitude = lastMagnitude;
        if (mainHandler != null) {
            mainHandler.post(() -> {
                NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
                if (manager != null) {
                    Notification notification = buildNotification(magnitude);
                    try {
                        manager.notify(NotificationHelper.NOTIFICATION_ID, notification);
                    } catch (SecurityException e) {
                        android.util.Log.w("MagneticSensorService", "Notification permission denied", e);
                    }
                }
            });
        }
    }

    private void notifyAvailability(boolean available) {
        for (SampleListener listener : LISTENERS) {
            listener.onSensorAvailabilityChanged(available);
        }
    }

    // ==================== 后台自动停止 ====================

    private void shutdownAfterBackgroundTimeout() {
        if (!appInBackground
                || MainActivity.hasForegroundActivity()
                || shuttingDownAfterBackgroundTimeout
                || hasActiveRecordingWork()) {
            return;
        }
        shuttingDownAfterBackgroundTimeout = true;
        releaseBackgroundSamplingWakeLock();
        MainActivity.finishTaskFromBackgroundTimeout();
        MainActivity.removeAppTasks(getApplicationContext());
        NotificationHelper.cancelAllServiceNotifications(this);
        stopSelf();
    }

    private void updateBackgroundAutoStop() {
        if (mainHandler == null) {
            return;
        }
        mainHandler.removeCallbacks(backgroundAutoStopRunnable);
        if (appInBackground && !hasActiveRecordingWork()) {
            long delayMs = getRemainingBackgroundAutoStopMs();
            if (delayMs <= 0L) {
                shutdownAfterBackgroundTimeout();
                return;
            }
            acquireBackgroundSamplingWakeLock();
            mainHandler.postDelayed(backgroundAutoStopRunnable, delayMs);
        } else {
            releaseBackgroundSamplingWakeLock();
        }
    }

    private boolean shutdownIfBackgroundTimeoutElapsed() {
        if (!appInBackground || hasActiveRecordingWork() || getRemainingBackgroundAutoStopMs() > 0L) {
            return false;
        }
        shutdownAfterBackgroundTimeout();
        return true;
    }

    private static long getRemainingBackgroundAutoStopMs() {
        if (backgroundEnteredElapsedMs <= 0L) {
            return BACKGROUND_AUTO_STOP_DELAY_MS;
        }
        long elapsedMs = SystemClock.elapsedRealtime() - backgroundEnteredElapsedMs;
        return Math.max(0L, BACKGROUND_AUTO_STOP_DELAY_MS - elapsedMs);
    }

    // ==================== 图表/轴采样 ====================

    public static ChartSnapshot getChartSnapshot() {
        synchronized (CHART_LOCK) {
            return new ChartSnapshot(
                    chartXSamples.clone(),
                    chartYSamples.clone(),
                    chartZSamples.clone(),
                    chartTotalSamples.clone(),
                    chartTimestampsNs.clone(),
                    currentService == null ? 0 : currentService.chartSampleIndex,
                    currentService == null ? 0 : currentService.chartSampleCount,
                    currentService == null ? 0L : currentService.chartOriginTimestampNs);
        }
    }

    public static AxesSnapshot getAxesSnapshot() {
        synchronized (AXES_LOCK) {
            MagneticSensorService service = currentService;
            return new AxesSnapshot(
                    axesXSamples.clone(),
                    axesYSamples.clone(),
                    axesZSamples.clone(),
                    axesTotalSamples.clone(),
                    axesTimestampsNs.clone(),
                    service == null ? 0 : service.axesSampleIndex,
                    service == null ? 0 : service.axesSampleCount);
        }
    }

    public static void clearChartSamples() {
        synchronized (CHART_LOCK) {
            Arrays.fill(chartXSamples, 0f);
            Arrays.fill(chartYSamples, 0f);
            Arrays.fill(chartZSamples, 0f);
            Arrays.fill(chartTotalSamples, 0f);
            Arrays.fill(chartTimestampsNs, 0L);
            MagneticSensorService service = currentService;
            if (service != null) {
                service.chartSampleIndex = 0;
                service.chartSampleCount = 0;
                service.chartOriginTimestampNs = 0L;
                service.lastChartSampleTimestampNs = 0L;
            }
        }
    }

    public static void clearAxesSamples() {
        synchronized (AXES_LOCK) {
            Arrays.fill(axesXSamples, 0f);
            Arrays.fill(axesYSamples, 0f);
            Arrays.fill(axesZSamples, 0f);
            Arrays.fill(axesTotalSamples, 0f);
            Arrays.fill(axesTimestampsNs, 0L);
            MagneticSensorService service = currentService;
            if (service != null) {
                service.axesSampleIndex = 0;
                service.axesSampleCount = 0;
            }
        }
    }

    private void addAxesSample(float x, float y, float z, float magnitude, long timestampNs) {
        synchronized (AXES_LOCK) {
            axesXSamples[axesSampleIndex] = x;
            axesYSamples[axesSampleIndex] = y;
            axesZSamples[axesSampleIndex] = z;
            axesTotalSamples[axesSampleIndex] = magnitude;
            axesTimestampsNs[axesSampleIndex] = timestampNs;
            axesSampleIndex = (axesSampleIndex + 1) % AXES_HISTORY_SIZE;
            if (axesSampleCount < AXES_HISTORY_SIZE) {
                axesSampleCount++;
            }
        }
    }

    private void addChartSampleIfDue(float x, float y, float z, float magnitude, long timestampNs) {
        if (timestampNs <= 0L) {
            return;
        }
        if (lastChartSampleTimestampNs > 0L
                && timestampNs - lastChartSampleTimestampNs < CHART_SAMPLE_INTERVAL_MS * 1_000_000L) {
            return;
        }
        // 长时间采样中断（如后台返回/息屏恢复），清空旧缓冲区，避免时间戳跳变导致折线断开
        if (lastChartSampleTimestampNs > 0L
                && timestampNs - lastChartSampleTimestampNs > CHART_GAP_CLEAR_THRESHOLD_NS) {
            clearChartSamples();
        }
        addChartSampleInternal(x, y, z, magnitude, timestampNs, true);
    }

    private void addChartSampleInternal(float x, float y, float z, float magnitude,
                                        long timestampNs, boolean notifyListeners) {
        lastChartSampleTimestampNs = timestampNs;
        synchronized (CHART_LOCK) {
            if (chartSampleCount == 0 || chartOriginTimestampNs == 0L) {
                chartOriginTimestampNs = timestampNs;
            }
            chartXSamples[chartSampleIndex] = x;
            chartYSamples[chartSampleIndex] = y;
            chartZSamples[chartSampleIndex] = z;
            chartTotalSamples[chartSampleIndex] = magnitude;
            chartTimestampsNs[chartSampleIndex] = timestampNs;
            chartSampleIndex = (chartSampleIndex + 1) % CHART_HISTORY_SIZE;
            if (chartSampleCount < CHART_HISTORY_SIZE) {
                chartSampleCount++;
            }
        }
        if (notifyListeners) {
            for (SampleListener listener : LISTENERS) {
                listener.onChartSample(x, y, z, magnitude, timestampNs);
            }
        }
    }

    private File getChartCacheFile() {
        return new File(getCacheDir(), CHART_CACHE_FILE);
    }

    private void saveChartCache() {
        File file = getChartCacheFile();
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeInt(1);
            int count;
            int startIndex;
            synchronized (CHART_LOCK) {
                count = chartSampleCount;
                startIndex = chartSampleCount < CHART_HISTORY_SIZE
                        ? 0
                        : chartSampleIndex;
            }
            out.writeInt(count);
            for (int i = 0; i < count; i++) {
                int idx = (startIndex + i) % CHART_HISTORY_SIZE;
                synchronized (CHART_LOCK) {
                    out.writeFloat(chartXSamples[idx]);
                    out.writeFloat(chartYSamples[idx]);
                    out.writeFloat(chartZSamples[idx]);
                    out.writeFloat(chartTotalSamples[idx]);
                    out.writeLong(chartTimestampsNs[idx]);
                }
            }
            synchronized (CHART_LOCK) {
                out.writeLong(chartOriginTimestampNs);
                out.writeLong(lastChartSampleTimestampNs);
            }
        } catch (Exception e) {
            android.util.Log.w("MagneticSensorService", "Failed to save chart cache", e);
            file.delete();
        }
    }

    private void loadChartCache() {
        File file = getChartCacheFile();
        if (!file.exists() || !file.isFile()) {
            return;
        }
        long ageMs = System.currentTimeMillis() - file.lastModified();
        if (ageMs > CHART_CACHE_MAX_AGE_MS) {
            file.delete();
            return;
        }
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(new FileInputStream(file)))) {
            int version = in.readInt();
            if (version != 1) {
                file.delete();
                return;
            }
            int count = in.readInt();
            if (count < 0 || count > CHART_HISTORY_SIZE) {
                file.delete();
                return;
            }

            java.util.ArrayList<ChartCacheEntry> entries = new java.util.ArrayList<>();
            for (int i = 0; i < count; i++) {
                float x = in.readFloat();
                float y = in.readFloat();
                float z = in.readFloat();
                float total = in.readFloat();
                long timestampNs = in.readLong();
                entries.add(new ChartCacheEntry(x, y, z, total, timestampNs));
            }
            long originTimestampNs = in.readLong();
            in.readLong();

            if (entries.isEmpty()) {
                file.delete();
                return;
            }

            long newestTimestampNs = entries.get(entries.size() - 1).timestampNs;
            long nowNs = SystemClock.elapsedRealtimeNanos();

            if (nowNs - newestTimestampNs > CHART_GAP_CLEAR_THRESHOLD_NS) {
                file.delete();
                return;
            }

            long windowStartNs = newestTimestampNs - CHART_WINDOW_NS;

            clearChartSamples();
            long actualOriginNs = 0L;
            for (ChartCacheEntry entry : entries) {
                if (entry.timestampNs >= windowStartNs) {
                    if (actualOriginNs == 0L) {
                        actualOriginNs = entry.timestampNs;
                    }
                    addChartSampleInternal(entry.x, entry.y, entry.z, entry.total, entry.timestampNs, false);
                }
            }

            synchronized (CHART_LOCK) {
                chartOriginTimestampNs = actualOriginNs;
            }

            file.delete();
        } catch (Exception e) {
            android.util.Log.w("MagneticSensorService", "Failed to load chart cache", e);
            file.delete();
        }
    }

    private static final class ChartCacheEntry {
        final float x, y, z, total;
        final long timestampNs;

        ChartCacheEntry(float x, float y, float z, float total, long timestampNs) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.total = total;
            this.timestampNs = timestampNs;
        }
    }

    // ==================== 录制管理 ====================

    public static void startRecording() {
        MagneticSensorService service = currentService;
        synchronized (RECORDING_LOCK) {
            recordingData.clear();
            recording = true;
            recordingSaving = false;
            recordingLimitReached = false;
            // 同一时刻同时捕获墙钟与开机时钟，保证后续时间转换的单调性
            long nowMs = System.currentTimeMillis();
            long nowElapsedNs = SystemClock.elapsedRealtimeNanos();
            recordingStartedWallTimeMs = nowMs;
            recordingStartedElapsedNs = nowElapsedNs;
        }
        if (service != null) {
            service.reapplySamplingForRecording(true);
        }
        updateCurrentForegroundNotification();
        acquireCurrentRecordingWakeLock();
        notifyRecordingStateChanged(false);
    }

    public static java.util.ArrayList<RecordingSample> stopRecordingAndDrain() {
        MagneticSensorService service = currentService;
        java.util.ArrayList<RecordingSample> snapshot;
        synchronized (RECORDING_LOCK) {
            recording = false;
            recordingLimitReached = false;
            snapshot = new java.util.ArrayList<>(recordingData);
            recordingData.clear();
            recordingSaving = !snapshot.isEmpty();
            if (snapshot.isEmpty()) {
                recordingStartedWallTimeMs = 0L;
                recordingStartedElapsedNs = 0L;
            }
        }
        if (service != null) {
            service.reapplySamplingForRecording(false);
        }
        updateCurrentForegroundNotification();
        if (snapshot.isEmpty()) {
            releaseCurrentRecordingWakeLock();
        }
        notifyRecordingStateChanged(false);
        return snapshot;
    }

    public static void finishRecordingNotification(Context context, String fileName, boolean success) {
        boolean stillRecording;
        synchronized (RECORDING_LOCK) {
            stillRecording = recording;
            recordingSaving = false;
            if (!stillRecording) {
                recordingStartedWallTimeMs = 0L;
                recordingStartedElapsedNs = 0L;
            }
        }
        updateCurrentForegroundNotification();
        if (!stillRecording) {
            releaseCurrentRecordingWakeLock();
            // 录制结束后重新调度后台自动停止，保证3分钟倒计时生效
            MagneticSensorService service = currentService;
            if (service != null) {
                service.updateBackgroundAutoStop();
            }
        }
        notifyRecordingStateChanged(success);
        Context appContext = context == null ? null : context.getApplicationContext();
        MagneticSensorService service = currentService;
        if (appContext == null && service != null) {
            appContext = service.getApplicationContext();
        }
        if (appContext != null) {
            NotificationHelper.postRecordingFinishedNotification(appContext, fileName, success);
        }
    }

    public static boolean isRecording() {
        return recording;
    }

    public static boolean isRecordingSaving() {
        return recordingSaving;
    }

    public static long getRecordingStartedWallTimeMs() {
        return recordingStartedWallTimeMs;
    }

    public static long getRecordingStartedElapsedNs() {
        return recordingStartedElapsedNs;
    }

    /**
     * 原子读取录制参考时间，保证 wallTime 和 elapsedNs 来自同一录制会话。
     */
    public static long[] getRecordingReferenceTimes() {
        synchronized (RECORDING_LOCK) {
            return new long[]{recordingStartedWallTimeMs, recordingStartedElapsedNs};
        }
    }

    public static boolean hasPendingRecordingData() {
        synchronized (RECORDING_LOCK) {
            return !recordingData.isEmpty();
        }
    }

    private static boolean hasActiveRecordingWork() {
        synchronized (RECORDING_LOCK) {
            return recording || recordingSaving || !recordingData.isEmpty();
        }
    }

    public static boolean consumeRecordingLimitReached() {
        synchronized (RECORDING_LOCK) {
            boolean reached = recordingLimitReached;
            recordingLimitReached = false;
            return reached;
        }
    }

    private static void notifyRecordingStateChanged(boolean fileListChanged) {
        final boolean currentRecording;
        final boolean currentSaving;
        synchronized (RECORDING_LOCK) {
            currentRecording = recording;
            currentSaving = recordingSaving;
        }
        for (SampleListener listener : LISTENERS) {
            listener.onRecordingStateChanged(currentRecording, currentSaving, fileListChanged);
        }
    }

    private void addRecordingSample(float x, float y, float z, float magnitude, long timestampNs) {
        synchronized (RECORDING_LOCK) {
            if (!recording) {
                return;
            }
            recordingData.add(new RecordingSample(timestampNs, x, y, z, magnitude));
            if (recordingData.size() >= MAX_RECORDING_ENTRIES) {
                recording = false;
                recordingSaving = true;
                recordingLimitReached = true;
                if (mainHandler != null) {
                    mainHandler.post(() -> saveRecordingAndThen(null));
                }
            }
        }
    }

    private void saveRecordingAndThen(Runnable afterSave) {
        // 在同步块内一次性捕获参考时间，保证原子性和一致性
        final long refWallTimeMs;
        final long refElapsedNs;
        synchronized (RECORDING_LOCK) {
            refWallTimeMs = recordingStartedWallTimeMs;
            refElapsedNs = recordingStartedElapsedNs;
        }
        java.util.ArrayList<RecordingSample> data = stopRecordingAndDrain();
        if (data.isEmpty() || saveExecutor == null) {
            if (afterSave != null) {
                afterSave.run();
            }
            return;
        }
        final File dir = RecordingFileManager.getRecordDir(getExternalFilesDir(null), getFilesDir());
        final String headerTime = getString(R.string.excel_header_time);
        final String headerX = getString(R.string.excel_header_x);
        final String headerY = getString(R.string.excel_header_y);
        final String headerZ = getString(R.string.excel_header_z);
        final String headerTotal = getString(R.string.excel_header_total);
        final String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        final String fileName = "Magnetic_" + timestamp + ".csv";
        try {
            saveExecutor.execute(() -> {
                boolean saved = RecordingFileManager.saveToCsv(data, dir, fileName,
                        refWallTimeMs, refElapsedNs,
                        headerTime, headerX, headerY, headerZ, headerTotal);
                finishRecordingNotification(getApplicationContext(), fileName, saved);
                if (afterSave != null && mainHandler != null) {
                    mainHandler.post(afterSave);
                }
            });
        } catch (RejectedExecutionException e) {
            boolean saved = RecordingFileManager.saveToCsv(data, dir, fileName,
                    refWallTimeMs, refElapsedNs,
                    headerTime, headerX, headerY, headerZ, headerTotal);
            finishRecordingNotification(getApplicationContext(), fileName, saved);
            if (afterSave != null) {
                afterSave.run();
            }
        }
    }

    private void stopRecordingFromNotification() {
        if (!isRecording() && !hasPendingRecordingData()) {
            updateForegroundNotification();
            return;
        }
        saveRecordingAndThen(() -> {
            if (appInBackground) {
                stopSelf();
            }
        });
    }

    // ==================== 内部数据类 ====================

    public static final class ChartSnapshot {
        public final float[] xSamples;
        public final float[] ySamples;
        public final float[] zSamples;
        public final float[] totalSamples;
        public final long[] timestampsNs;
        public final int sampleIndex;
        public final int sampleCount;
        public final long originTimestampNs;

        private ChartSnapshot(float[] xSamples, float[] ySamples, float[] zSamples,
                              float[] totalSamples, long[] timestampsNs,
                              int sampleIndex, int sampleCount, long originTimestampNs) {
            this.xSamples = xSamples;
            this.ySamples = ySamples;
            this.zSamples = zSamples;
            this.totalSamples = totalSamples;
            this.timestampsNs = timestampsNs;
            this.sampleIndex = sampleIndex;
            this.sampleCount = sampleCount;
            this.originTimestampNs = originTimestampNs;
        }
    }

    public static final class AxesSnapshot {
        public final float[] xSamples;
        public final float[] ySamples;
        public final float[] zSamples;
        public final float[] totalSamples;
        public final long[] timestampsNs;
        public final int sampleIndex;
        public final int sampleCount;

        private AxesSnapshot(float[] xSamples, float[] ySamples, float[] zSamples,
                             float[] totalSamples, long[] timestampsNs,
                             int sampleIndex, int sampleCount) {
            this.xSamples = xSamples;
            this.ySamples = ySamples;
            this.zSamples = zSamples;
            this.totalSamples = totalSamples;
            this.timestampsNs = timestampsNs;
            this.sampleIndex = sampleIndex;
            this.sampleCount = sampleCount;
        }
    }

    public static final class RecordingSample {
        public final long timestampNs;
        public final float x;
        public final float y;
        public final float z;
        public final float total;

        private RecordingSample(long timestampNs, float x, float y, float z, float total) {
            this.timestampNs = timestampNs;
            this.x = x;
            this.y = y;
            this.z = z;
            this.total = total;
        }
    }
}