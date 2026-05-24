package com.example.magneticfield;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener2;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
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

    private static final String CHANNEL_ID = "magnetic_sensor_live";
    private static final String ACTION_STOP_RECORDING =
            "com.example.magneticfield.action.STOP_RECORDING";
    private static final int NOTIFICATION_ID = 1001;
    private static final int COMPLETE_NOTIFICATION_ID = 1002;
    private static final String RECORD_DIR = "magnetic_records";
    private static final long BACKGROUND_AUTO_STOP_DELAY_MS = 3 * 60 * 1000L;
    private static final long BACKGROUND_AUTO_STOP_FALLBACK_GRACE_MS = 2_000L;
    private static final long BACKGROUND_WAKE_LOCK_TIMEOUT_MS =
            BACKGROUND_AUTO_STOP_DELAY_MS + BACKGROUND_AUTO_STOP_FALLBACK_GRACE_MS + 1_000L;
    
    
    private static final long RECORDING_WAKE_LOCK_TIMEOUT_MS = 60 * 60 * 1000L;
    private static final long COMPLETE_NOTIFICATION_TIMEOUT_MS = 4_000L;
    private static final long CHART_SAMPLE_INTERVAL_MS = 250L;
    private static final long NOTIFICATION_UPDATE_INTERVAL_MS = 1_000L;
    private static final int SENSOR_BATCH_REPORT_LATENCY_US = 250_000;
    public static final int SAMPLE_RATE_FASTEST = -1;
    private static final int DEFAULT_SAMPLE_RATE_HZ = 0;
    private static final int ACTUAL_SAMPLE_RATE_WINDOW_SIZE = 32;
    private static final int LIVE_NOTIFICATION_COLOR = 0xFFE53935;
    private static final int COMPLETE_NOTIFICATION_COLOR = 0xFF00C853;
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING =
            "android.requestPromotedOngoing";
    private static final String EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText";
    private static final int CHART_HISTORY_SIZE = 120;
    private static final int AXES_HISTORY_SIZE = 64;
    private static final int MAX_RECORDING_ENTRIES = 1_000_000;
    private static final CopyOnWriteArrayList<SampleListener> LISTENERS = new CopyOnWriteArrayList<>();
    private static final Object CHART_LOCK = new Object();
    private static final Object AXES_LOCK = new Object();
    private static final Object RECORDING_LOCK = new Object();
    private static final Object ACTUAL_SAMPLE_RATE_LOCK = new Object();
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
    private static volatile int targetSampleRateHz = DEFAULT_SAMPLE_RATE_HZ;
    private static volatile int lastAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH;
    private static volatile float lastX;
    private static volatile float lastY;
    private static volatile float lastZ;
    private static volatile float lastMagnitude;
    private static volatile long lastTimestampNs;
    private static volatile float actualSampleRateHz;
    private static final long[] actualSampleRateTimestampsNs =
            new long[ACTUAL_SAMPLE_RATE_WINDOW_SIZE];
    private static int actualSampleRateIndex;
    private static int actualSampleRateCount;
    private static final ThreadLocal<SimpleDateFormat> CSV_TIME_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US);
        }
    };

    private SensorManager sensorManager;
    private Sensor magneticSensor;
    private boolean sensorRegistered;
    private int chartSampleIndex;
    private int chartSampleCount;
    private int axesSampleIndex;
    private int axesSampleCount;
    
    private volatile long lastNotificationUpdateMs;
    private long chartOriginTimestampNs;
    private volatile long lastChartSampleTimestampNs;
    private long targetRateOriginTimestampNs;
    private long targetRateAcceptedSampleCount;
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
            mainHandler.postDelayed(this, NOTIFICATION_UPDATE_INTERVAL_MS);
        }
    };

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

    public static void refreshNotification() {
        updateCurrentForegroundNotification();
    }

    public static boolean isSensorAvailable() {
        return sensorAvailable;
    }

    public static int getTargetSampleRateHz() {
        return targetSampleRateHz;
    }

    public static float getActualSampleRateHz() {
        return actualSampleRateHz;
    }

    public static void setTargetSampleRateHz(int sampleRateHz) {
        targetSampleRateHz = sampleRateHz;
        MagneticSensorService service = currentService;
        if (service != null) {
            service.applyTargetSampleRate();
        }
    }

    public static void addListener(SampleListener listener) {
        if (listener == null) {
            return;
        }
        LISTENERS.addIfAbsent(listener);
        listener.onSensorAvailabilityChanged(sensorAvailable);
        listener.onSensorAccuracyChanged(lastAccuracy);
        listener.onRecordingStateChanged(recording, recordingSaving, false);
        if (lastTimestampNs > 0L) {
            listener.onMagneticSample(lastX, lastY, lastZ, lastMagnitude, lastTimestampNs);
        }
    }

    public static void removeListener(SampleListener listener) {
        if (listener != null) {
            LISTENERS.remove(listener);
        }
    }

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
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(0f));
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD, true);
            if (magneticSensor == null) {
                magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
            }
        }
        sensorAvailable = magneticSensor != null;
        notifyAvailability(sensorAvailable);
        registerMagneticSensor();
        if (isRecording()) {
            acquireRecordingWakeLock();
        }
        updateBackgroundAutoStop();
        startLiveNotificationLoop();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP_RECORDING.equals(intent.getAction())) {
            stopRecordingFromNotification();
            return START_NOT_STICKY;
        }
        registerMagneticSensor();
        return START_NOT_STICKY;
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
        removeForegroundNotification();
        unregisterMagneticSensor();
        if (mainHandler != null) {
            mainHandler.removeCallbacks(backgroundAutoStopRunnable);
            mainHandler.removeCallbacks(liveNotificationRunnable);
        }
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
        resetActualSampleRate();
        running = false;
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event == null || event.values == null || event.values.length < 3) {
            return;
        }
        float x = event.values[0];
        float y = event.values[1];
        float z = event.values[2];
        float magnitude = (float) Math.sqrt(x * x + y * y + z * z);
        updateActualSampleRate(event.timestamp);
        if (!shouldAcceptSampleForTargetRate(event.timestamp)) {
            return;
        }
        lastX = x;
        lastY = y;
        lastZ = z;
        lastMagnitude = magnitude;
        lastTimestampNs = event.timestamp;
        if (!sensorAvailable) {
            sensorAvailable = true;
            notifyAvailability(true);
        }
        hasLastSample = true;
        addAxesSample(x, y, z, magnitude, event.timestamp);
        addChartSampleIfDue(x, y, z, magnitude, event.timestamp);
        addRecordingSample(x, y, z, magnitude, event.timestamp);
        for (SampleListener listener : LISTENERS) {
            listener.onMagneticSample(x, y, z, magnitude, event.timestamp);
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

    private void registerMagneticSensor() {
        if (!sensorRegistered && sensorManager != null && magneticSensor != null) {
            int samplingPeriodUs = getSamplingPeriodUs();
            boolean registered;
            if (sensorHandler != null) {
                registered = sensorManager.registerListener(this, magneticSensor,
                        samplingPeriodUs,
                        SENSOR_BATCH_REPORT_LATENCY_US, sensorHandler);
            } else {
                registered = sensorManager.registerListener(this, magneticSensor,
                        samplingPeriodUs,
                        SENSOR_BATCH_REPORT_LATENCY_US);
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

    private int getSamplingPeriodUs() {
        int rateHz = targetSampleRateHz;
        if (rateHz == SAMPLE_RATE_FASTEST) {
            return SensorManager.SENSOR_DELAY_FASTEST;
        }
        if (rateHz <= 0) {
            return SensorManager.SENSOR_DELAY_NORMAL;
        }
        if (rateHz <= 5) {
            return SensorManager.SENSOR_DELAY_NORMAL;
        }
        if (rateHz <= 15) {
            return SensorManager.SENSOR_DELAY_UI;
        }
        if (rateHz <= 50) {
            return SensorManager.SENSOR_DELAY_GAME;
        }
        return Math.max(1, 1_000_000 / rateHz);
    }

    private void applyTargetSampleRate() {
        if (sensorRegistered) {
            unregisterMagneticSensor();
        }
        resetActualSampleRate();
        resetTargetRateLimiter();
        registerMagneticSensor();
    }

    private void updateActualSampleRate(long timestampNs) {
        if (timestampNs <= 0L) {
            return;
        }
        synchronized (ACTUAL_SAMPLE_RATE_LOCK) {
            actualSampleRateTimestampsNs[actualSampleRateIndex] = timestampNs;
            actualSampleRateIndex = (actualSampleRateIndex + 1) % ACTUAL_SAMPLE_RATE_WINDOW_SIZE;
            if (actualSampleRateCount < ACTUAL_SAMPLE_RATE_WINDOW_SIZE) {
                actualSampleRateCount++;
            }
            if (actualSampleRateCount < 2) {
                actualSampleRateHz = 0f;
                return;
            }
            int oldestIndex = actualSampleRateCount < ACTUAL_SAMPLE_RATE_WINDOW_SIZE
                    ? 0
                    : actualSampleRateIndex;
            int newestIndex = (actualSampleRateIndex - 1 + ACTUAL_SAMPLE_RATE_WINDOW_SIZE)
                    % ACTUAL_SAMPLE_RATE_WINDOW_SIZE;
            long durationNs = actualSampleRateTimestampsNs[newestIndex]
                    - actualSampleRateTimestampsNs[oldestIndex];
            int intervals = actualSampleRateCount - 1;
            actualSampleRateHz = durationNs > 0L
                    ? intervals * 1_000_000_000f / durationNs
                    : 0f;
        }
    }

    private static void resetActualSampleRate() {
        synchronized (ACTUAL_SAMPLE_RATE_LOCK) {
            Arrays.fill(actualSampleRateTimestampsNs, 0L);
            actualSampleRateIndex = 0;
            actualSampleRateCount = 0;
            actualSampleRateHz = 0f;
        }
    }

    private boolean shouldAcceptSampleForTargetRate(long timestampNs) {
        int rateHz = targetSampleRateHz;
        if (rateHz <= 0 || timestampNs <= 0L) {
            return true;
        }
        if (targetRateOriginTimestampNs <= 0L || timestampNs < targetRateOriginTimestampNs) {
            targetRateOriginTimestampNs = timestampNs;
            targetRateAcceptedSampleCount = 1L;
            return true;
        }
        long elapsedNs = timestampNs - targetRateOriginTimestampNs;
        if (elapsedNs * (long) rateHz >= targetRateAcceptedSampleCount * 1_000_000_000L) {
            targetRateAcceptedSampleCount++;
            return true;
        }
        return false;
    }

    private void resetTargetRateLimiter() {
        targetRateOriginTimestampNs = 0L;
        targetRateAcceptedSampleCount = 0L;
    }

    private void unregisterMagneticSensor() {
        if (sensorRegistered && sensorManager != null) {
            sensorManager.unregisterListener(this);
            sensorRegistered = false;
        }
    }

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

    private void flushBatchedSensorEvents() {
        if (!sensorRegistered || sensorManager == null || magneticSensor == null) {
            return;
        }
        sensorManager.flush(this);
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
                    notifySafely(manager, NOTIFICATION_ID, buildNotification(magnitude));
                }
            });
        }
    }

    
    
    
    
    
    
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
        cancelAllServiceNotifications();
        stopSelf();
    }

    private void removeForegroundNotification() {
        try {
            stopForeground(true);
        } catch (RuntimeException e) {
            android.util.Log.w("MagneticSensorService", "Failed to stop foreground", e);
        }
        cancelNotification(NOTIFICATION_ID);
    }

    private void cancelAllServiceNotifications() {
        removeForegroundNotification();
        cancelNotification(COMPLETE_NOTIFICATION_ID);
    }

    private void cancelNotification(int notificationId) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(notificationId);
        }
    }

    private void notifyAvailability(boolean available) {
        for (SampleListener listener : LISTENERS) {
            listener.onSensorAvailabilityChanged(available);
        }
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

    public static void startRecording() {
        synchronized (RECORDING_LOCK) {
            recordingData.clear();
            recording = true;
            recordingSaving = false;
            recordingLimitReached = false;
            recordingStartedWallTimeMs = System.currentTimeMillis();
        }
        updateCurrentForegroundNotification();
        acquireCurrentRecordingWakeLock();
        notifyRecordingStateChanged(false);
    }

    public static java.util.ArrayList<RecordingSample> stopRecordingAndDrain() {
        java.util.ArrayList<RecordingSample> snapshot;
        synchronized (RECORDING_LOCK) {
            recording = false;
            recordingLimitReached = false;
            snapshot = new java.util.ArrayList<>(recordingData);
            recordingData.clear();
            recordingSaving = !snapshot.isEmpty();
            if (snapshot.isEmpty()) {
                recordingStartedWallTimeMs = 0L;
            }
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
            }
        }
        updateCurrentForegroundNotification();
        if (!stillRecording) {
            releaseCurrentRecordingWakeLock();
        }
        notifyRecordingStateChanged(success);
        Context appContext = context == null ? null : context.getApplicationContext();
        MagneticSensorService service = currentService;
        if (appContext == null && service != null) {
            appContext = service.getApplicationContext();
        }
        if (appContext != null) {
            postRecordingFinishedNotification(appContext, fileName, success);
        }
    }

    public static boolean isRecording() {
        return recording;
    }

    public static boolean isRecordingSaving() {
        return recordingSaving;
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
        boolean currentRecording = recording;
        boolean currentSaving = recordingSaving;
        for (SampleListener listener : LISTENERS) {
            listener.onRecordingStateChanged(currentRecording, currentSaving, fileListChanged);
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

    private void addRecordingSample(float x, float y, float z, float magnitude, long timestampNs) {
        synchronized (RECORDING_LOCK) {
            if (!recording) {
                return;
            }
            if (recordingData.size() >= MAX_RECORDING_ENTRIES) {
                recording = false;
                recordingLimitReached = true;
                return;
            }
            recordingData.add(new RecordingSample(timestampNs, x, y, z, magnitude));
        }
    }

    private void saveRecordingAndThen(Runnable afterSave) {
        java.util.ArrayList<RecordingSample> data = stopRecordingAndDrain();
        if (data.isEmpty() || saveExecutor == null) {
            if (afterSave != null) {
                afterSave.run();
            }
            return;
        }
        final File dir = getRecordDir();
        final String headerTime = getString(R.string.excel_header_time);
        final String headerX = getString(R.string.excel_header_x);
        final String headerY = getString(R.string.excel_header_y);
        final String headerZ = getString(R.string.excel_header_z);
        final String headerTotal = getString(R.string.excel_header_total);
        final String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        final String fileName = "Magnetic_" + timestamp + ".csv";
        try {
            saveExecutor.execute(() -> {
                boolean saved = saveToCsv(data, dir, fileName,
                        headerTime, headerX, headerY, headerZ, headerTotal);
                finishRecordingNotification(getApplicationContext(), fileName, saved);
                if (afterSave != null && mainHandler != null) {
                    mainHandler.post(afterSave);
                }
            });
        } catch (RejectedExecutionException e) {
            boolean saved = saveToCsv(data, dir, fileName,
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

    private boolean saveToCsv(java.util.List<RecordingSample> data, File dir, String fileName,
                              String headerTime, String headerX, String headerY,
                              String headerZ, String headerTotal) {
        File file = new File(dir, fileName);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write('\uFEFF');
            writer.write(headerTime + "," + headerX + "," + headerY + "," + headerZ + "," + headerTotal);
            writer.newLine();
            for (RecordingSample sample : data) {
                writer.write(String.format(Locale.US, "'%s',%.3f,%.3f,%.3f,%.3f",
                        formatNanos(sample.timestampNs), sample.x, sample.y, sample.z, sample.total));
                writer.newLine();
            }
            return true;
        } catch (Exception e) {
            if (file.exists() && !file.delete()) {
                android.util.Log.w("MagneticSensorService", "Failed to delete incomplete CSV: " + file);
            }
            return false;
        }
    }

    private File getRecordDir() {
        File external = getExternalFilesDir(null);
        if (external == null) {
            external = getFilesDir();
        }
        File dir = new File(external, RECORD_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("MagneticSensorService", "Failed to create record directory: " + dir);
        }
        return dir;
    }

    private String formatNanos(long timestampNs) {
        long bootNanos = SystemClock.elapsedRealtimeNanos();
        long nowNs = System.currentTimeMillis() * 1_000_000L;
        long estimatedNs = nowNs - (bootNanos - timestampNs);
        long estimatedMs = estimatedNs / 1_000_000L;
        String timePart = CSV_TIME_FORMAT.get().format(new Date(estimatedMs));
        long fractionNs = Math.floorMod(estimatedNs, 1_000_000_000L);
        int millis = (int) (fractionNs / 1_000_000L);
        int micros = (int) ((fractionNs / 1_000L) % 1_000L);
        int nanos = (int) (fractionNs % 1_000L);
        return String.format(Locale.US, "%s.%03d.%03d.%03d", timePart, millis, micros, nanos);
    }

    private void addChartSampleIfDue(float x, float y, float z, float magnitude, long timestampNs) {
        if (timestampNs <= 0L) {
            return;
        }
        if (lastChartSampleTimestampNs > 0L
                && timestampNs - lastChartSampleTimestampNs < CHART_SAMPLE_INTERVAL_MS * 1_000_000L) {
            return;
        }
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
        for (SampleListener listener : LISTENERS) {
            listener.onChartSample(x, y, z, magnitude, timestampNs);
        }
    }

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

    private void createNotificationChannel() {
        ensureNotificationChannel(this);
    }

    private Notification buildNotification(float magnitude) {
        boolean isRecording = recording;
        boolean isSaving = recordingSaving;
        String title;
        String text;
        int icon;
        int priority;
        if (isRecording) {
            title = getString(R.string.recording_notification_title);
            text = String.format(Locale.US, getString(R.string.recording_notification_text), magnitude);
            icon = R.drawable.ic_notification_recording;
            priority = NotificationCompat.PRIORITY_DEFAULT;
        } else if (isSaving) {
            title = getString(R.string.recording_saving_notification_title);
            text = getString(R.string.recording_saving_notification_text);
            icon = R.drawable.ic_notification_recording;
            priority = NotificationCompat.PRIORITY_DEFAULT;
        } else {
            title = getString(R.string.sensor_service_notification_title);
            
            
            text = lastTimestampNs > 0L
                    ? String.format(Locale.US, getString(R.string.sensor_service_notification_text), magnitude)
                            + "  " + CSV_TIME_FORMAT.get().format(new Date())
                    : getString(R.string.collecting);
            icon = R.drawable.ic_target;
            priority = NotificationCompat.PRIORITY_DEFAULT;
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(buildLaunchPendingIntent(this, 0))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setColor(LIVE_NOTIFICATION_COLOR)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(priority)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        if (isRecording) {
            long startMs = recordingStartedWallTimeMs > 0L
                    ? recordingStartedWallTimeMs
                    : System.currentTimeMillis();
            builder.setWhen(startMs)
                    .setShowWhen(true)
                    .setUsesChronometer(true)
                    .addAction(new NotificationCompat.Action.Builder(
                            R.drawable.ic_notification_stop_button,
                            getString(R.string.btn_pause),
                            buildStopRecordingPendingIntent(this))
                            .build())
                    .addExtras(buildPromotedExtras(getString(R.string.recording_live_short)));
        } else if (isSaving) {
            builder.setShowWhen(false)
                    .setProgress(0, 0, true)
                    .addExtras(buildPromotedExtras(getString(R.string.recording_live_short)));
        } else {
            builder.setShowWhen(false);
        }
        return builder.build();
    }

    private void updateForegroundNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null) {
            notifySafely(manager, NOTIFICATION_ID, buildNotification(lastMagnitude));
        }
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

    private static void ensureNotificationChannel(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.sensor_service_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static PendingIntent buildLaunchPendingIntent(Context context, int requestCode) {
        Intent launchIntent = new Intent(context, MainActivity.class);
        launchIntent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(
                context,
                requestCode,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static PendingIntent buildStopRecordingPendingIntent(Context context) {
        Intent stopIntent = new Intent(context, MagneticSensorService.class);
        stopIntent.setAction(ACTION_STOP_RECORDING);
        return PendingIntent.getService(
                context,
                2,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static Bundle buildPromotedExtras(CharSequence shortText) {
        Bundle extras = new Bundle();
        extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true);
        extras.putCharSequence(EXTRA_SHORT_CRITICAL_TEXT, shortText);
        return extras;
    }

    private static void postRecordingFinishedNotification(Context context, String fileName,
                                                          boolean success) {
        ensureNotificationChannel(context);
        NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        String title = context.getString(success
                ? R.string.recording_complete_notification_title
                : R.string.recording_failed_notification_title);
        String text = success && fileName != null
                ? String.format(Locale.US,
                context.getString(R.string.recording_complete_notification_text), fileName)
                : context.getString(R.string.recording_failed_notification_text);
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(success ? R.drawable.ic_notification_done : R.drawable.ic_target)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(buildLaunchPendingIntent(context, 1))
                .setOngoing(true)
                .setTimeoutAfter(COMPLETE_NOTIFICATION_TIMEOUT_MS)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setColor(success ? COMPLETE_NOTIFICATION_COLOR : LIVE_NOTIFICATION_COLOR)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .addExtras(buildPromotedExtras(context.getString(R.string.recording_done_short)))
                .build();
        notifySafely(manager, COMPLETE_NOTIFICATION_ID, notification);
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> manager.cancel(COMPLETE_NOTIFICATION_ID),
                COMPLETE_NOTIFICATION_TIMEOUT_MS);
    }

    private static void notifySafely(NotificationManager manager, int id, Notification notification) {
        try {
            manager.notify(id, notification);
        } catch (SecurityException e) {
            android.util.Log.w("MagneticSensorService", "Notification permission denied", e);
        }
    }
}
