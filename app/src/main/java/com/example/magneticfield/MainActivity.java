package com.example.magneticfield;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity implements MagneticSensorService.SampleListener {
    private static final int HISTORY_SIZE = 8;
    private static final int DEFAULT_SPECTRUM_SIZE = 1024;
    private static final int AUTO_SAMPLE_RATE_ESTIMATE_COUNT = 8;
    private static final int AXES_HISTORY_SIZE = 64;
    private static final int AXES_CHART_HISTORY_SIZE = 120;
    private static final int[] SAMPLE_RATE_OPTIONS_HZ = {
            5,
            15,
            50,
            MagneticSensorService.SAMPLE_RATE_FASTEST
    };
    private static final int[] SAMPLE_RATE_OPTION_LABELS = {
            R.string.sample_rate_option_slowest,
            R.string.sample_rate_option_medium,
            R.string.sample_rate_option_fast,
            R.string.sample_rate_option_fastest
    };
    private static final long UI_UPDATE_INTERVAL_MS = 250L;
    
    
    private static final int REQUEST_NOTIFICATION_PERMISSION = 100;
    private static final String RECORD_DIR = "magnetic_records";
    private static final String TAG = "MainActivity";
    
    
    private static volatile WeakReference<MainActivity> latestActivityRef = new WeakReference<>(null);

    private SensorManager sensorManager;
    private Sensor magneticSensor;
    private TextView ciChangText;
    private TextView statusText;
    private TextView sampleRateText;
    private TextView sampleRateHintText;
    private TextView updateText;
    private TextView tabSpectrum;
    private TextView tabAxes;
    private LinearLayout spectrumPage;
    private LinearLayout axesPage;
    private TextView xAxisText;
    private TextView yAxisText;
    private TextView zAxisText;
    private TextView axesTotalText;
    private TextView axesMaxText;
    private TextView axesMinText;
    private TextView axesAvgText;
    private AxesChartView axesChartView;
    private View statusDot;
    private LinearLayout historyCard;
    private TextView btnCalibrate;
    private TextView btnRecord;
    private TextView btnReset;
    private LinearLayout fileListContainer;
    private Dialog calibrationDialog;
    
    private android.app.AlertDialog confirmDialog;
    private VideoView calibrationVideoView;
    private ImageView playPauseOverlay;
    private Handler overlayHandler;
    private int lastDotColor;
    private boolean hasLastDotColor;
    private TextView[] spectrumFreqTexts;
    private TextView[] spectrumEnergyTexts;
    private float[] magnitudeSamples = new float[DEFAULT_SPECTRUM_SIZE];
    private long[] timestampSamples = new long[DEFAULT_SPECTRUM_SIZE];
    private final float[] xSamples = new float[AXES_HISTORY_SIZE];
    private final float[] ySamples = new float[AXES_HISTORY_SIZE];
    private final float[] zSamples = new float[AXES_HISTORY_SIZE];
    private final float[] totalSamples = new float[AXES_HISTORY_SIZE];
    private final long[] axesTimestampsNs = new long[AXES_HISTORY_SIZE];
    private final float[] chartXSamples = new float[AXES_CHART_HISTORY_SIZE];
    private final float[] chartYSamples = new float[AXES_CHART_HISTORY_SIZE];
    private final float[] chartZSamples = new float[AXES_CHART_HISTORY_SIZE];
    private final float[] chartTotalSamples = new float[AXES_CHART_HISTORY_SIZE];
    private final long[] chartTimestampsNs = new long[AXES_CHART_HISTORY_SIZE];
    private final Object spectrumSamplesLock = new Object();
    private final Object axesSamplesLock = new Object();
    private volatile int sampleIndex;
    private volatile int sampleCount;
    private volatile int spectrumSize = DEFAULT_SPECTRUM_SIZE;
    private volatile int axesSampleIndex;
    private volatile int axesSampleCount;
    private int chartSampleIndex;
    private int chartSampleCount;
    private volatile long lastUiUpdateMs;
    private volatile boolean sensorRegistered;
    private boolean inBackground;
    private boolean activityResumed;
    
    
    private volatile boolean uiPaused;
    
    
    
    
    private volatile boolean sensorReliable = true;
    private boolean finishingFromBackgroundTimeout;

    
    private static final FastFourierTransformer FFT_TRANSFORMER =
            new FastFourierTransformer(DftNormalization.STANDARD);

    
    private static final ThreadLocal<SimpleDateFormat> UI_TIME_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm", Locale.getDefault());
        }
    };
    private static final ThreadLocal<SimpleDateFormat> FILE_TIME_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
        }
    };
    
    private static final ThreadLocal<SimpleDateFormat> CSV_TIME_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US);
        }
    };

    
    private volatile boolean isRecording = false;
    private volatile boolean isRecordingSaving = false;
    private volatile boolean recordingStopPending = false;
    private boolean pendingStartRecordingAfterNotificationPermission;
    private final AtomicInteger recordingSaveTaskCount = new AtomicInteger();
    private ExecutorService saveExecutor;
    private Handler mainHandler;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        latestActivityRef = new WeakReference<>(this);
        if (savedInstanceState == null) {
            MagneticSensorService.setTargetSampleRateHz(0);
        }
        setContentView(R.layout.activity_main);

        saveExecutor = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        ciChangText = findViewById(R.id.fieldValueText);
        statusText = findViewById(R.id.statusText);
        sampleRateText = findViewById(R.id.sampleRateText);
        sampleRateHintText = findViewById(R.id.sampleRateHintText);
        updateText = findViewById(R.id.updateText);
        tabSpectrum = findViewById(R.id.tabSpectrum);
        tabAxes = findViewById(R.id.tabAxes);
        spectrumPage = findViewById(R.id.spectrumPage);
        axesPage = findViewById(R.id.axesPage);
        xAxisText = findViewById(R.id.xAxisText);
        yAxisText = findViewById(R.id.yAxisText);
        zAxisText = findViewById(R.id.zAxisText);
        axesTotalText = findViewById(R.id.axesTotalText);
        axesMaxText = findViewById(R.id.axesMaxText);
        axesMinText = findViewById(R.id.axesMinText);
        axesAvgText = findViewById(R.id.axesAvgText);
        axesChartView = findViewById(R.id.axesChartView);
        statusDot = findViewById(R.id.statusDot);
        historyCard = findViewById(R.id.historyCard);
        btnCalibrate = findViewById(R.id.btnCalibrate);
        btnRecord = findViewById(R.id.btnRecord);
        btnReset = findViewById(R.id.btnReset);
        fileListContainer = findViewById(R.id.fileListContainer);

        btnCalibrate.setOnClickListener(v -> showCalibrationDialog());
        tabSpectrum.setOnClickListener(v -> showSpectrumPage());
        tabAxes.setOnClickListener(v -> showAxesPage());
        btnRecord.setOnClickListener(v -> toggleRecording());
        btnReset.setOnClickListener(v -> resetStats());
        sampleRateText.setOnClickListener(v -> showSampleRateDialog());
        sampleRateHintText.setOnClickListener(v -> showSampleRateDialog());

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensorManager != null) {
            magneticSensor = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        }

        if (magneticSensor == null) {
            ciChangText.setText(R.string.dash);
            statusText.setText(R.string.no_sensor);
            sampleRateText.setText(R.string.sample_rate_placeholder);
            seedAxes();
            setDotColor(getColorCompat(R.color.danger));
            seedEmptyRows();
            btnRecord.setEnabled(false);
            btnReset.setEnabled(false);
            sampleRateText.setEnabled(false);
            sampleRateHintText.setEnabled(false);
            btnRecord.setAlpha(0.5f);
            btnReset.setAlpha(0.5f);
            sampleRateText.setAlpha(0.5f);
            sampleRateHintText.setAlpha(0.5f);
        }

        
        getRecordDir();
        refreshFileList();
        requestNotificationPermissionIfNeededForLiveStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        latestActivityRef = new WeakReference<>(this);
        activityResumed = true;
        uiPaused = false;          
        markAppInForeground();
        registerMagneticSensor();
        syncRecordingStateFromService();
        syncAxesSamplesFromService();
        syncAxesChartFromService();
        refreshFileList();
    }

    private void registerMagneticSensor() {
        
        
        
        if (sensorRegistered && !MagneticSensorService.isRunning()) {
            sensorRegistered = false;
        }
        if (!sensorRegistered && magneticSensor != null) {
            if (!MagneticSensorService.start(getApplicationContext())) {
                ciChangText.setText(R.string.dash);
                statusText.setText(R.string.sensor_service_start_failed);
                sampleRateText.setText(R.string.sample_rate_placeholder);
                setDotColor(getColorCompat(R.color.danger));
                return;
            }
            sensorRegistered = true;
            MagneticSensorService.addListener(this);
        }
    }

    private void removeMagneticSensorListener() {
        if (sensorRegistered) {
            MagneticSensorService.removeListener(this);
            sensorRegistered = false;
        }
    }

    private void stopMagneticSensorService() {
        removeMagneticSensorListener();
        MagneticSensorService.stop(getApplicationContext());
    }

    static void finishTaskFromBackgroundTimeout() {
        MainActivity activity = latestActivityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        
        
        
        
        
        
        if (activity.isFinishing() || activity.isDestroyed() || activity.hasActiveRecording()) {
            return;
        }
        activity.finishForBackgroundTimeout();
    }

    static void removeAppTasks(Context context) {
        if (context == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }
        ActivityManager activityManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager == null) {
            return;
        }
        try {
            for (ActivityManager.AppTask task : activityManager.getAppTasks()) {
                task.finishAndRemoveTask();
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Failed to remove app tasks", e);
        }
    }

    private void finishForBackgroundTimeout() {
        finishingFromBackgroundTimeout = true;
        finishAndRemoveTask();
    }

    private boolean hasActiveRecording() {
        return isRecording || recordingStopPending
                || isRecordingSaving
                || MagneticSensorService.isRecording()
                || MagneticSensorService.isRecordingSaving()
                || MagneticSensorService.hasPendingRecordingData();
    }

    private void syncRecordingStateFromService() {
        if (MagneticSensorService.consumeRecordingLimitReached()) {
            isRecording = false;
            recordingStopPending = true;
            Toast.makeText(getApplicationContext(), R.string.recording_limit_reached, Toast.LENGTH_LONG).show();
            stopRecordingAndSave();
            return;
        }
        isRecording = MagneticSensorService.isRecording();
        isRecordingSaving = MagneticSensorService.isRecordingSaving();
        recordingStopPending = false;
        updateRecordButtonState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityResumed = false;
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && activityResumed) {
            markAppInForeground();
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            markAppInBackgroundForAutoClose();
        }
    }

    private void markAppInBackgroundForAutoClose() {
        inBackground = true;
        
        
        
        MagneticSensorService.setAppInBackground(true);
    }

    private void markAppInForeground() {
        inBackground = false;
        
        
        MagneticSensorService.setAppInBackground(false);
    }

    @Override
    protected void onStop() {
        super.onStop();
        markAppInBackgroundForAutoClose();
        if (inBackground) {
            
            
            
            
            uiPaused = true;
        }
    }

    static boolean hasForegroundActivity() {
        MainActivity activity = latestActivityRef.get();
        return activity != null
                && !activity.isFinishing()
                && !activity.isDestroyed()
                && activity.activityResumed
                && !activity.inBackground;
    }

    
    

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (latestActivityRef.get() == this) {
            latestActivityRef = new WeakReference<>(null);
        }
        
        
        if (isFinishing()) {
            if (finishingFromBackgroundTimeout || hasActiveRecording()) {
                removeMagneticSensorListener();
            } else {
                stopMagneticSensorService();
            }
        } else {
            removeMagneticSensorListener();
        }
        if (overlayHandler != null) {
            overlayHandler.removeCallbacksAndMessages(null);
            overlayHandler = null;
        }
        if (calibrationDialog != null && calibrationDialog.isShowing()) {
            calibrationDialog.dismiss();
        }
        
        if (confirmDialog != null && confirmDialog.isShowing()) {
            confirmDialog.dismiss();
            confirmDialog = null;
        }
        if (saveExecutor != null) {
            
            saveExecutor.shutdown();
            try {
                if (!saveExecutor.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    if (hasRecordingSaveInProgress()) {
                        Log.w(TAG, "CSV save still running; allowing executor to finish");
                    } else {
                        saveExecutor.shutdownNow();
                        if (!saveExecutor.awaitTermination(1, java.util.concurrent.TimeUnit.SECONDS)) {
                            Log.w(TAG, "saveExecutor did not terminate gracefully");
                        }
                    }
                }
            } catch (InterruptedException e) {
                if (!hasRecordingSaveInProgress()) {
                    saveExecutor.shutdownNow();
                }
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void onMagneticSample(float x, float y, float z, float magnitude, long timestampNs) {
        if (!shouldAcceptSensorCallbacks()) {
            return;
        }
        addSpectrumSample(magnitude, timestampNs);
        addAxesSample(x, y, z, magnitude, timestampNs);
        if (MagneticSensorService.consumeRecordingLimitReached()) {
            isRecording = false;
            recordingStopPending = true;
            mainHandler.post(() -> {
                Toast.makeText(getApplicationContext(), R.string.recording_limit_reached, Toast.LENGTH_LONG).show();
                stopRecordingAndSave();
            });
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastUiUpdateMs < UI_UPDATE_INTERVAL_MS) {
            return;
        }
        lastUiUpdateMs = now;

        final float fx = x;
        final float fy = y;
        final float fz = z;
        final float fMagnitude = magnitude;
        final long currentTime = System.currentTimeMillis();

        runOnUiThread(() -> {
            if (!shouldAcceptSensorCallbacks()) {
                return;
            }
            ciChangText.setText(String.format(Locale.US, getString(R.string.field_value_format), fMagnitude));
            updateStatus(fMagnitude);
            updateSampleRateText();
            updateAxesText(fx, fy, fz, fMagnitude);
            updateText.setText(getString(R.string.last_update,
                    UI_TIME_FORMAT.get().format(new Date(currentTime))));
            renderSpectrum();
        });
    }

    @Override
    public void onChartSample(float x, float y, float z, float magnitude, long timestampNs) {
        if (!shouldAcceptSensorCallbacks()) {
            return;
        }
        
        
        
        runOnUiThread(() -> {
            if (!shouldAcceptSensorCallbacks()) {
                return;
            }
            addAxesChartSample(x, y, z, magnitude, timestampNs);
            if (!uiPaused && axesChartView != null) {
                axesChartView.setSamples(chartXSamples, chartYSamples, chartZSamples, chartTotalSamples,
                        chartTimestampsNs, chartSampleIndex, chartSampleCount);
            }
        });
    }

    @Override
    public void onSensorAccuracyChanged(int accuracy) {
        sensorReliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE;
    }

    @Override
    public void onSensorAvailabilityChanged(boolean available) {
        if (!shouldAcceptSensorCallbacks()) {
            return;
        }
        if (!available) {
            runOnUiThread(() -> {
                if (!shouldAcceptSensorCallbacks()) {
                    return;
                }
                ciChangText.setText(R.string.dash);
                statusText.setText(R.string.no_sensor);
                sampleRateText.setText(R.string.sample_rate_placeholder);
                setDotColor(getColorCompat(R.color.danger));
            });
        }
    }

    @Override
    public void onRecordingStateChanged(boolean recording, boolean saving, boolean fileListChanged) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            isRecording = recording;
            isRecordingSaving = saving;
            recordingStopPending = false;
            updateRecordButtonState();
            if (fileListChanged) {
                refreshFileList();
            }
        });
    }

    private boolean shouldAcceptSensorCallbacks() {
        return sensorRegistered && !isFinishing() && !isDestroyed();
    }

    private void showSampleRateDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (confirmDialog != null && confirmDialog.isShowing()) {
            return;
        }
        CharSequence[] labels = new CharSequence[SAMPLE_RATE_OPTIONS_HZ.length];
        int checkedIndex = -1;
        int currentRate = MagneticSensorService.getTargetSampleRateHz();
        for (int i = 0; i < SAMPLE_RATE_OPTIONS_HZ.length; i++) {
            int rate = SAMPLE_RATE_OPTIONS_HZ[i];
            labels[i] = getString(SAMPLE_RATE_OPTION_LABELS[i]);
            if (rate == currentRate) {
                checkedIndex = i;
            }
        }
        confirmDialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.sample_rate_dialog_title)
                .setSingleChoiceItems(labels, checkedIndex, (dialog, which) -> {
                    int selectedRate = SAMPLE_RATE_OPTIONS_HZ[which];
                    MagneticSensorService.setTargetSampleRateHz(selectedRate);
                    resetSpectrumSamples(getSpectrumSizeForRate(getEstimatedSampleRateHz(selectedRate)));
                    seedEmptyRows();
                    Toast.makeText(this,
                            String.format(Locale.US, getString(R.string.sample_rate_set_toast),
                                    labels[which]),
                            Toast.LENGTH_SHORT).show();
                    dialog.dismiss();
                })
                .create();
        confirmDialog.setOnDismissListener(dialog -> confirmDialog = null);
        confirmDialog.show();
    }

    

    
    private volatile boolean togglePending = false;
    private static final long DEBOUNCE_DELAY_MS = 300L;

    private void toggleRecording() {
        if (togglePending) return;
        togglePending = true;
        
        mainHandler.postDelayed(() -> togglePending = false, DEBOUNCE_DELAY_MS);
        if (hasActiveRecording()) {
            if (isRecordingSaving || MagneticSensorService.isRecordingSaving()) {
                return;
            }
            stopRecordingAndSave();
        } else if (!requestNotificationPermissionIfNeeded(true)) {
            startRecording();
        }
    }

    private void requestNotificationPermissionIfNeededForLiveStatus() {
        requestNotificationPermissionIfNeeded(false);
    }

    private boolean requestNotificationPermissionIfNeeded(boolean startRecordingAfterGrant) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        pendingStartRecordingAfterNotificationPermission = startRecordingAfterGrant;
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_NOTIFICATION_PERMISSION);
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_NOTIFICATION_PERMISSION) {
            return;
        }
        boolean shouldStart = pendingStartRecordingAfterNotificationPermission;
        pendingStartRecordingAfterNotificationPermission = false;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (!granted) {
            Toast.makeText(this, R.string.recording_notification_permission_hint,
                    Toast.LENGTH_LONG).show();
        } else {
            MagneticSensorService.refreshNotification();
        }
        if (shouldStart && !hasActiveRecording()) {
            startRecording();
        }
    }

    private void startRecording() {
        if (!MagneticSensorService.isRunning() && !MagneticSensorService.start(getApplicationContext())) {
            Toast.makeText(this, R.string.sensor_service_start_failed, Toast.LENGTH_SHORT).show();
            return;
        }
        if (isRecordingSaving || MagneticSensorService.isRecordingSaving()) {
            updateRecordButtonState();
            return;
        }
        isRecording = true;
        isRecordingSaving = false;
        recordingStopPending = false;
        MagneticSensorService.startRecording();
        updateRecordButtonState();
        Toast.makeText(this, R.string.recording, Toast.LENGTH_SHORT).show();
    }

    private void stopRecordingAndSave() {
        stopRecordingAndSave(null);
    }

    private void stopRecordingAndSave(Runnable afterSave) {
        final List<MagneticSensorService.RecordingSample> dataToSave;
        if (!hasActiveRecording()) {
            if (afterSave != null) {
                afterSave.run();
            }
            return;
        }
        ArrayList<MagneticSensorService.RecordingSample> serviceData =
                MagneticSensorService.stopRecordingAndDrain();
        isRecording = false;
        isRecordingSaving = !serviceData.isEmpty();
        recordingStopPending = false;
        if (serviceData.isEmpty()) {
            dataToSave = null;
        } else {
            dataToSave = serviceData;
        }

        updateRecordButtonState();

        if (dataToSave == null) {
            if (!isFinishing() && !isDestroyed()) {
                Toast.makeText(this, R.string.no_data_recorded, Toast.LENGTH_SHORT).show();
            }
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
        final String exportSuccess = getString(R.string.export_success);
        final String savePrefix = getString(R.string.save_failed_prefix);
        final String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(new Date());
        final String fileName = "Magnetic_" + timestamp + ".csv";
        final Context appContext = getApplicationContext();

        recordingSaveTaskCount.incrementAndGet();
        Runnable saveTask = () -> {
            String error = null;
            try {
                error = saveToExcel(dataToSave, dir, fileName,
                        headerTime, headerX, headerY, headerZ, headerTotal);
            } finally {
                recordingSaveTaskCount.decrementAndGet();
            }
            final String saveError = error;
            mainHandler.post(() -> {
                MagneticSensorService.finishRecordingNotification(appContext, fileName,
                        saveError == null);
                if (saveError == null) {
                    Toast.makeText(appContext,
                            String.format(exportSuccess, fileName), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(appContext, savePrefix + saveError, Toast.LENGTH_LONG).show();
                }
                if (!isFinishing() && !isDestroyed()) {
                    refreshFileList();
                }
                if (afterSave != null) {
                    afterSave.run();
                }
            });
        };
        try {
            saveExecutor.execute(saveTask);
        } catch (RejectedExecutionException e) {
            new Thread(saveTask, "MagneticCsvSaveFallback").start();
        }
    }

    private boolean hasRecordingSaveInProgress() {
        return recordingSaveTaskCount.get() > 0;
    }

    private void updateRecordButtonState() {
        if (btnRecord == null) {
            return;
        }
        if (isRecording || MagneticSensorService.isRecording()) {
            btnRecord.setEnabled(true);
            btnRecord.setAlpha(1f);
            btnRecord.setText(R.string.btn_pause);
            btnRecord.setBackgroundResource(R.drawable.record_button_pause_bg);
        } else if (isRecordingSaving || MagneticSensorService.isRecordingSaving()) {
            btnRecord.setEnabled(false);
            btnRecord.setAlpha(0.6f);
            btnRecord.setText(R.string.recording_saving_notification_title);
            btnRecord.setBackgroundResource(R.drawable.record_button_pause_bg);
        } else {
            btnRecord.setEnabled(true);
            btnRecord.setAlpha(1f);
            btnRecord.setText(R.string.btn_record);
            btnRecord.setBackgroundResource(R.drawable.record_button_bg);
        }
    }

    private String saveToExcel(List<MagneticSensorService.RecordingSample> data, File dir, String fileName,
                               String headerTime, String headerX, String headerY,
                               String headerZ, String headerTotal) {
        File file = new File(dir, fileName);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {

            
            writer.write('\uFEFF');

            
            writer.write(headerTime + "," + headerX + "," + headerY + "," + headerZ + "," + headerTotal);
            writer.newLine();

            
            
            for (MagneticSensorService.RecordingSample entry : data) {
                writer.write(String.format(Locale.US, "'%s',%.3f,%.3f,%.3f,%.3f",
                        formatNanos(entry.timestampNs),
                        entry.x, entry.y, entry.z, entry.total));
                writer.newLine();
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to save CSV", e);
            if (file.exists() && !file.delete()) {
                Log.w(TAG, "Failed to delete incomplete CSV: " + file);
            }
            return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        }
    }

    




    private String formatNanos(long timestampNs) {
        
        long bootNanos = SystemClock.elapsedRealtimeNanos();
        long nowNs = System.currentTimeMillis() * 1_000_000L;
        
        long estimatedNs = nowNs - (bootNanos - timestampNs);
        long estimatedMs = estimatedNs / 1_000_000L;
        SimpleDateFormat sdf = CSV_TIME_FORMAT.get();
        String timePart = sdf.format(new Date(estimatedMs));
        long fractionNs = Math.floorMod(estimatedNs, 1_000_000_000L);
        int millis = (int) (fractionNs / 1_000_000L);
        int micros = (int) ((fractionNs / 1_000L) % 1_000L);
        int nanos = (int) (fractionNs % 1_000L);
        return String.format(Locale.US, "%s.%03d.%03d.%03d", timePart, millis, micros, nanos);
    }

    

    private void refreshFileList() {
        final File dir = getRecordDir();
        Runnable loadTask = () -> {
            File[] files = dir.listFiles((d, name) -> name.endsWith(".csv"));
            if (files != null && files.length > 1) {
                Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            }
            final File[] sorted = files;
            mainHandler.post(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    populateFileList(sorted);
                }
            });
        };
        try {
            saveExecutor.execute(loadTask);
        } catch (RejectedExecutionException e) {
            if (!isFinishing() && !isDestroyed()) {
                new Thread(loadTask, "MagneticFileListFallback").start();
            }
        }
    }

    private void populateFileList(File[] files) {
        fileListContainer.removeAllViews();

        if (files == null || files.length == 0) {
            TextView emptyText = new TextView(this);
            emptyText.setText(R.string.no_records);
            emptyText.setTextColor(getColorCompat(R.color.text_secondary));
            emptyText.setTextSize(14);
            emptyText.setGravity(Gravity.CENTER);
            emptyText.setPadding(0, dp(8), 0, dp(8));
            fileListContainer.addView(emptyText);
            return;
        }

        for (File file : files) {
            fileListContainer.addView(createFileRow(file));
        }
    }

    private View createFileRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(R.drawable.file_item_bg);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(6);
        row.setLayoutParams(rowParams);

        
        LinearLayout infoCol = new LinearLayout(this);
        infoCol.setOrientation(LinearLayout.VERTICAL);
        infoCol.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView fileNameText = new TextView(this);
        fileNameText.setText(file.getName());
        fileNameText.setTextColor(getColorCompat(R.color.text_primary));
        fileNameText.setTextSize(14);
        infoCol.addView(fileNameText);

        
        SimpleDateFormat sdf = FILE_TIME_FORMAT.get();
        TextView timeText = new TextView(this);
        timeText.setText(sdf.format(new Date(file.lastModified())));
        timeText.setTextColor(getColorCompat(R.color.text_secondary));
        timeText.setTextSize(11);
        timeText.setPadding(0, dp(4), 0, 0);
        infoCol.addView(timeText);

        row.addView(infoCol);

        
        TextView btnOpen = new TextView(this);
        btnOpen.setText(R.string.btn_open);
        btnOpen.setTextColor(0xFFFFFFFF);
        btnOpen.setTextSize(13);
        btnOpen.setGravity(Gravity.CENTER);
        btnOpen.setBackgroundResource(R.drawable.action_button_bg);
        btnOpen.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        openParams.setMarginEnd(dp(8));
        btnOpen.setLayoutParams(openParams);
        btnOpen.setOnClickListener(v -> openFile(file));
        row.addView(btnOpen);

        
        TextView btnShare = new TextView(this);
        btnShare.setText(R.string.btn_share);
        btnShare.setTextColor(0xFFFFFFFF);
        btnShare.setTextSize(13);
        btnShare.setGravity(Gravity.CENTER);
        btnShare.setBackgroundResource(R.drawable.action_button_bg);
        btnShare.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        shareParams.setMarginEnd(dp(8));
        btnShare.setLayoutParams(shareParams);
        btnShare.setOnClickListener(v -> shareFile(file));
        row.addView(btnShare);

        
        TextView btnDelete = new TextView(this);
        btnDelete.setText(R.string.btn_delete);
        btnDelete.setTextColor(0xFFFFFFFF);
        btnDelete.setTextSize(13);
        btnDelete.setGravity(Gravity.CENTER);
        btnDelete.setBackgroundResource(R.drawable.delete_button_bg);
        btnDelete.setPadding(dp(14), dp(7), dp(14), dp(7));
        btnDelete.setOnClickListener(v -> deleteFile(file));
        row.addView(btnDelete);

        return row;
    }

    private void deleteFile(File file) {
        
        if (isFinishing() || isDestroyed()) return;
        confirmDialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.btn_delete)
                .setMessage(getString(R.string.delete_confirm, file.getName()))
                .setPositiveButton(R.string.btn_delete, (dialog, which) -> {
                    if (file.delete()) {
                        refreshFileList();
                        Toast.makeText(MainActivity.this, R.string.delete_success, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(MainActivity.this, R.string.delete_failed, Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        confirmDialog.setOnDismissListener(dialog -> confirmDialog = null);
    }

    private void openFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "text/csv");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.btn_open)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.no_app_to_open, Toast.LENGTH_SHORT).show();
        }
    }

    private void shareFile(File file) {
        try {
            Uri uri = FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/csv");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.btn_share)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.share_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private File getRecordDir() {
        File external = getExternalFilesDir(null);
        if (external == null) {
            
            external = getFilesDir();
        }
        File dir = new File(external, RECORD_DIR);
        if (!dir.exists()) {
            if (!dir.mkdirs()) {
                Log.w(TAG, "Failed to create record directory: " + dir);
            }
        }
        return dir;
    }

    

    private void showSpectrumPage() {
        spectrumPage.setVisibility(View.VISIBLE);
        axesPage.setVisibility(View.GONE);
        tabSpectrum.setBackgroundResource(R.drawable.segment_active);
        tabAxes.setBackground(null);
        tabSpectrum.setTextColor(getColorCompat(R.color.segment_active_text));
        tabAxes.setTextColor(getColorCompat(R.color.text_secondary));
    }

    private void showAxesPage() {
        spectrumPage.setVisibility(View.GONE);
        axesPage.setVisibility(View.VISIBLE);
        tabSpectrum.setBackground(null);
        tabAxes.setBackgroundResource(R.drawable.segment_active);
        tabSpectrum.setTextColor(getColorCompat(R.color.text_secondary));
        tabAxes.setTextColor(getColorCompat(R.color.segment_active_text));
        refreshFileList();
    }

    // B-23 fix: removed the !sensorReliable guard. Many Android sensors briefly
    // report SENSOR_STATUS_UNRELIABLE after the app returns from background or
    // after sensor re-registration, even though they continue to produce valid
    // data. The magnitude being inside the 25-65 uT window is itself proof that
    // the sensor is working — showing "unstable" for a perfectly normal 43 uT
    // reading is misleading.
    private void updateStatus(float magnitude) {
        if (magnitude < 25f || magnitude > 65f) {
            statusText.setText(R.string.unstable);
            setDotColor(getColorCompat(R.color.warning));
        } else {
            statusText.setText(R.string.stable);
            setDotColor(getColorCompat(R.color.stable));
        }
    }

    private void addSpectrumSample(float magnitude, long timestampNs) {
        synchronized (spectrumSamplesLock) {
            maybeAdjustAutoSpectrumSizeLocked();
            magnitudeSamples[sampleIndex] = magnitude;
            timestampSamples[sampleIndex] = timestampNs;
            sampleIndex = (sampleIndex + 1) % spectrumSize;
            if (sampleCount < spectrumSize) {
                sampleCount++;
            }
        }
    }

    private void maybeAdjustAutoSpectrumSizeLocked() {
        if (MagneticSensorService.getTargetSampleRateHz() > 0
                || sampleCount < AUTO_SAMPLE_RATE_ESTIMATE_COUNT) {
            return;
        }
        float sampleRate = getCurrentSampleRateHzLocked();
        int desiredSize = getSpectrumSizeForRate(sampleRate);
        if (desiredSize != spectrumSize) {
            resetSpectrumSamplesLocked(desiredSize);
        }
    }

    private int getSpectrumSizeForRate(float sampleRateHz) {
        int roundedRateHz = Math.max(1, Math.round(sampleRateHz));
        if (roundedRateHz <= 1) {
            return 32;
        }
        if (roundedRateHz <= 5) {
            return 128;
        }
        if (roundedRateHz <= 10) {
            return 256;
        }
        if (roundedRateHz <= 25) {
            return 512;
        }
        return 1024;
    }

    private float getEstimatedSampleRateHz(int sampleRateSetting) {
        if (sampleRateSetting == MagneticSensorService.SAMPLE_RATE_FASTEST) {
            return Math.max(50f, MagneticSensorService.getActualSampleRateHz());
        }
        return sampleRateSetting;
    }

    private void resetSpectrumSamples(int newSpectrumSize) {
        synchronized (spectrumSamplesLock) {
            resetSpectrumSamplesLocked(newSpectrumSize);
        }
    }

    private void resetSpectrumSamplesLocked(int newSpectrumSize) {
        spectrumSize = newSpectrumSize;
        magnitudeSamples = new float[newSpectrumSize];
        timestampSamples = new long[newSpectrumSize];
        sampleIndex = 0;
        sampleCount = 0;
    }

    private void addAxesSample(float x, float y, float z, float magnitude, long timestampNs) {
        synchronized (axesSamplesLock) {
            xSamples[axesSampleIndex] = x;
            ySamples[axesSampleIndex] = y;
            zSamples[axesSampleIndex] = z;
            totalSamples[axesSampleIndex] = magnitude;
            axesTimestampsNs[axesSampleIndex] = timestampNs;
            axesSampleIndex = (axesSampleIndex + 1) % AXES_HISTORY_SIZE;
            if (axesSampleCount < AXES_HISTORY_SIZE) {
                axesSampleCount++;
            }
        }
    }

    private void addAxesChartSample(float x, float y, float z, float magnitude, long timestampNs) {
        chartXSamples[chartSampleIndex] = x;
        chartYSamples[chartSampleIndex] = y;
        chartZSamples[chartSampleIndex] = z;
        chartTotalSamples[chartSampleIndex] = magnitude;
        chartTimestampsNs[chartSampleIndex] = timestampNs;
        chartSampleIndex = (chartSampleIndex + 1) % AXES_CHART_HISTORY_SIZE;
        if (chartSampleCount < AXES_CHART_HISTORY_SIZE) {
            chartSampleCount++;
        }
    }

    private void resetAxesChart() {
        MagneticSensorService.clearChartSamples();
        Arrays.fill(chartXSamples, 0);
        Arrays.fill(chartYSamples, 0);
        Arrays.fill(chartZSamples, 0);
        Arrays.fill(chartTotalSamples, 0);
        Arrays.fill(chartTimestampsNs, 0L);
        chartSampleIndex = 0;
        chartSampleCount = 0;
        if (axesChartView != null) {
            axesChartView.setSamples(chartXSamples, chartYSamples, chartZSamples, chartTotalSamples,
                    chartTimestampsNs, 0, 0);
        }
    }

    private void syncAxesChartFromService() {
        MagneticSensorService.ChartSnapshot snapshot = MagneticSensorService.getChartSnapshot();
        System.arraycopy(snapshot.xSamples, 0, chartXSamples, 0, AXES_CHART_HISTORY_SIZE);
        System.arraycopy(snapshot.ySamples, 0, chartYSamples, 0, AXES_CHART_HISTORY_SIZE);
        System.arraycopy(snapshot.zSamples, 0, chartZSamples, 0, AXES_CHART_HISTORY_SIZE);
        System.arraycopy(snapshot.totalSamples, 0, chartTotalSamples, 0, AXES_CHART_HISTORY_SIZE);
        System.arraycopy(snapshot.timestampsNs, 0, chartTimestampsNs, 0, AXES_CHART_HISTORY_SIZE);
        chartSampleIndex = snapshot.sampleIndex;
        chartSampleCount = snapshot.sampleCount;
        if (axesChartView != null) {
            axesChartView.setSamples(chartXSamples, chartYSamples, chartZSamples, chartTotalSamples,
                    chartTimestampsNs, chartSampleIndex, chartSampleCount, snapshot.originTimestampNs);
        }
    }

    private void updateAxesText(float x, float y, float z, float magnitude) {
        xAxisText.setText(String.format(Locale.US, getString(R.string.axis_value_format), x));
        yAxisText.setText(String.format(Locale.US, getString(R.string.axis_value_format), y));
        zAxisText.setText(String.format(Locale.US, getString(R.string.axis_value_format), z));
        axesTotalText.setText(String.format(Locale.US, getString(R.string.axis_value_format), magnitude));

        
        final int snapshotAxesCount;
        final float[] snapshotTotal = new float[AXES_HISTORY_SIZE];
        synchronized (axesSamplesLock) {
            snapshotAxesCount = axesSampleCount;
            System.arraycopy(totalSamples, 0, snapshotTotal, 0, AXES_HISTORY_SIZE);
        }
        if (snapshotAxesCount == 0) {
            axesMaxText.setText(R.string.max_placeholder);
            axesMinText.setText(R.string.min_placeholder);
            axesAvgText.setText(R.string.avg_placeholder);
        } else {
            float max = -Float.MAX_VALUE;
            float min = Float.MAX_VALUE;
            double sum = 0d;
            for (int i = 0; i < snapshotAxesCount; i++) {
                float v = snapshotTotal[i];
                if (v > max) max = v;
                if (v < min) min = v;
                sum += v;
            }
            axesMaxText.setText(String.format(Locale.US, getString(R.string.max_format), max));
            axesMinText.setText(String.format(Locale.US, getString(R.string.min_format), min));
            axesAvgText.setText(String.format(Locale.US, getString(R.string.avg_format),
                    (float) (sum / snapshotAxesCount)));
        }

        axesChartView.setSamples(chartXSamples, chartYSamples, chartZSamples, chartTotalSamples,
                chartTimestampsNs, chartSampleIndex, chartSampleCount);
    }

    private void resetStats() {
        MagneticSensorService.clearAxesSamples();
        synchronized (axesSamplesLock) {
            Arrays.fill(xSamples, 0);
            Arrays.fill(ySamples, 0);
            Arrays.fill(zSamples, 0);
            Arrays.fill(totalSamples, 0);
            Arrays.fill(axesTimestampsNs, 0L);
            axesSampleIndex = 0;
            axesSampleCount = 0;
        }
        seedAxes();
        resetAxesChart();
    }

    private void syncAxesSamplesFromService() {
        MagneticSensorService.AxesSnapshot snapshot = MagneticSensorService.getAxesSnapshot();
        synchronized (axesSamplesLock) {
            System.arraycopy(snapshot.xSamples, 0, xSamples, 0, AXES_HISTORY_SIZE);
            System.arraycopy(snapshot.ySamples, 0, ySamples, 0, AXES_HISTORY_SIZE);
            System.arraycopy(snapshot.zSamples, 0, zSamples, 0, AXES_HISTORY_SIZE);
            System.arraycopy(snapshot.totalSamples, 0, totalSamples, 0, AXES_HISTORY_SIZE);
            System.arraycopy(snapshot.timestampsNs, 0, axesTimestampsNs, 0, AXES_HISTORY_SIZE);
            axesSampleIndex = snapshot.sampleIndex;
            axesSampleCount = snapshot.sampleCount;
        }
        if (snapshot.sampleCount > 0) {
            int latest = (snapshot.sampleIndex - 1 + AXES_HISTORY_SIZE) % AXES_HISTORY_SIZE;
            updateAxesText(snapshot.xSamples[latest], snapshot.ySamples[latest],
                    snapshot.zSamples[latest], snapshot.totalSamples[latest]);
        }
    }

    private void seedAxes() {
        xAxisText.setText(R.string.axis_placeholder);
        yAxisText.setText(R.string.axis_placeholder);
        zAxisText.setText(R.string.axis_placeholder);
        axesTotalText.setText(R.string.total_placeholder);
        axesMaxText.setText(R.string.max_placeholder);
        axesMinText.setText(R.string.min_placeholder);
        axesAvgText.setText(R.string.avg_placeholder);
    }

    private void updateSampleRateText() {
        float sampleRate = MagneticSensorService.getActualSampleRateHz();
        if (sampleRate <= 0f) {
            sampleRateText.setText(R.string.sample_rate_placeholder);
            return;
        }
        sampleRateText.setText(String.format(Locale.US, getString(R.string.sample_rate_format), sampleRate));
    }

    private float getCurrentSampleRateHz() {
        synchronized (spectrumSamplesLock) {
            return getCurrentSampleRateHzLocked();
        }
    }

    private float getCurrentSampleRateHzLocked() {
        if (sampleCount < 2) {
            return 0f;
        }
        int oldestIndex = sampleCount < spectrumSize ? 0 : sampleIndex;
        int newestIndex = (sampleIndex - 1 + spectrumSize) % spectrumSize;
        int intervals = sampleCount < spectrumSize ? sampleCount - 1 : spectrumSize - 1;
        return calculateSampleRate(timestampSamples[oldestIndex], timestampSamples[newestIndex], intervals);
    }

    private void renderSpectrum() {
        ensureSpectrumRows();

        final int currentSampleCount;
        final int currentSpectrumSize;
        synchronized (spectrumSamplesLock) {
            currentSampleCount = sampleCount;
            currentSpectrumSize = spectrumSize;
        }
        if (currentSampleCount < currentSpectrumSize) {
            renderPlaceholderRows(getString(R.string.collecting),
                    currentSampleCount + "/" + currentSpectrumSize);
            return;
        }

        SpectrumPeak[] peaks = calculateSpectrumPeaks();
        if (peaks == null) {
            renderPlaceholderRows(getString(R.string.sample_rate_placeholder), getString(R.string.dash));
            return;
        }

        for (int i = 0; i < HISTORY_SIZE; i++) {
            SpectrumPeak peak = peaks[i];
            String frequencyText = peak.frequency > 0f
                    ? String.format(Locale.US, getString(R.string.frequency_format), peak.frequency)
                    : getString(R.string.sample_rate_placeholder);
            String energyText = peak.frequency > 0f
                    ? String.valueOf(Math.round(peak.energy))
                    : getString(R.string.dash);
            spectrumFreqTexts[i].setText(frequencyText);
            spectrumEnergyTexts[i].setText(energyText);
        }
    }

    private void seedEmptyRows() {
        renderPlaceholderRows(getString(R.string.sample_rate_placeholder), getString(R.string.dash));
    }

    private void renderPlaceholderRows(String left, String right) {
        ensureSpectrumRows();
        for (int i = 0; i < HISTORY_SIZE; i++) {
            spectrumFreqTexts[i].setText(left);
            spectrumEnergyTexts[i].setText(right);
        }
    }

    private void ensureSpectrumRows() {
        if (spectrumFreqTexts != null) return;
        historyCard.removeAllViews();
        addSpectrumHeader();
        spectrumFreqTexts = new TextView[HISTORY_SIZE];
        spectrumEnergyTexts = new TextView[HISTORY_SIZE];
        for (int i = 0; i < HISTORY_SIZE; i++) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(11), 0, dp(11));
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
            spectrumFreqTexts[i] = createRowText("", Gravity.START);
            spectrumEnergyTexts[i] = createRowText("", Gravity.END);
            row.addView(spectrumFreqTexts[i]);
            row.addView(spectrumEnergyTexts[i]);
            historyCard.addView(row);
            if (i < HISTORY_SIZE - 1) {
                historyCard.addView(createDivider());
            }
        }
    }

    private void addSpectrumHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(8));
        header.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView frequencyText = createHeaderText(getString(R.string.header_frequency), Gravity.START);
        TextView energyText = createHeaderText(getString(R.string.header_energy), Gravity.END);
        header.addView(frequencyText);
        header.addView(energyText);
        historyCard.addView(header);
        historyCard.addView(createDivider());
    }

    private TextView createHeaderText(String text, int gravity) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColorCompat(R.color.text_secondary));
        view.setTextSize(13);
        view.setGravity(gravity);
        view.setAllCaps(true);
        view.setLetterSpacing(0f);
        view.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return view;
    }

    private SpectrumPeak[] calculateSpectrumPeaks() {
        
        final int snapshotIndex;
        final int snapshotSize;
        final float[] snapshotSamples;
        final long[] snapshotTimestamps;
        synchronized (spectrumSamplesLock) {
            snapshotSize = spectrumSize;
            snapshotIndex = sampleIndex;
            snapshotSamples = magnitudeSamples.clone();
            snapshotTimestamps = timestampSamples.clone();
        }

        float[] ordered = new float[snapshotSize];
        long oldestTimestamp = 0L;
        long newestTimestamp = 0L;
        float mean = 0f;

        for (int i = 0; i < snapshotSize; i++) {
            int sourceIndex = (snapshotIndex + i) % snapshotSize;
            ordered[i] = snapshotSamples[sourceIndex];
            mean += ordered[i];
            if (i == 0) {
                oldestTimestamp = snapshotTimestamps[sourceIndex];
            } else if (i == snapshotSize - 1) {
                newestTimestamp = snapshotTimestamps[sourceIndex];
            }
        }

        mean /= snapshotSize;
        float sampleRate = calculateSampleRate(oldestTimestamp, newestTimestamp, snapshotSize - 1);
        if (sampleRate <= 0f) {
            return null;
        }

        SpectrumPeak[] peaks = new SpectrumPeak[HISTORY_SIZE];
        for (int i = 0; i < HISTORY_SIZE; i++) {
            peaks[i] = new SpectrumPeak(0f, 0f);
        }

        double[] real = new double[snapshotSize];
        for (int i = 0; i < snapshotSize; i++) {
            double window = 0.5d - 0.5d * Math.cos((2d * Math.PI * i) / (snapshotSize - 1));
            real[i] = (ordered[i] - mean) * window;
        }

        
        Complex[] spectrum = FFT_TRANSFORMER.transform(real, TransformType.FORWARD);

        int half = snapshotSize / 2;
        float[] energies = new float[half];
        for (int bin = 0; bin < half; bin++) {
            double mag = spectrum[bin].abs();
            energies[bin] = (float) (mag * mag / snapshotSize);
        }
        
        for (int bin = 2; bin < half - 1; bin++) {
            if (energies[bin] > energies[bin - 1] && energies[bin] > energies[bin + 1]) {
                float frequency = bin * sampleRate / snapshotSize;
                insertPeak(peaks, new SpectrumPeak(frequency, energies[bin]));
            }
        }

        return peaks;
    }

    private float calculateSampleRate(long oldestTimestamp, long newestTimestamp, int intervals) {
        long durationNs = newestTimestamp - oldestTimestamp;
        if (durationNs <= 0L || intervals <= 0) {
            return 0f;
        }
        return intervals * 1_000_000_000f / durationNs;
    }

    private void insertPeak(SpectrumPeak[] peaks, SpectrumPeak candidate) {
        for (int i = 0; i < peaks.length; i++) {
            if (candidate.energy > peaks[i].energy) {
                for (int j = peaks.length - 1; j > i; j--) {
                    peaks[j] = peaks[j - 1];
                }
                peaks[i] = candidate;
                return;
            }
        }
    }

    private TextView createRowText(String text, int gravity) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColorCompat(R.color.text_primary));
        view.setTextSize(18);
        view.setGravity(gravity);
        view.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return view;
    }

    private View createDivider() {
        View divider = new View(this);
        divider.setBackgroundColor(getColorCompat(R.color.divider));
        divider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
        return divider;
    }

    private void setDotColor(int color) {
        if (hasLastDotColor && lastDotColor == color) return;
        lastDotColor = color;
        hasLastDotColor = true;
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        statusDot.setBackground(drawable);
    }

    private int getColorCompat(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    

    private void showCalibrationDialog() {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (calibrationDialog != null && calibrationDialog.isShowing()) {
            return;
        }
        calibrationDialog = new Dialog(this, android.R.style.Theme_Translucent_NoTitleBar);
        calibrationDialog.setContentView(R.layout.dialog_calibration);
        calibrationDialog.setCancelable(true);

        calibrationVideoView = calibrationDialog.findViewById(R.id.calibrationVideoView);
        playPauseOverlay = calibrationDialog.findViewById(R.id.playPauseOverlay);
        ImageButton btnClose = calibrationDialog.findViewById(R.id.btnClose);

        Uri videoUri = Uri.parse("android.resource://" + getPackageName() + "/" + R.raw.calibration_video);
        calibrationVideoView.setVideoURI(videoUri);

        calibrationVideoView.setOnPreparedListener(mp -> {
            
            final VideoView vv = calibrationVideoView;
            if (vv == null) return;
            mp.setLooping(true);
            vv.start();
        });

        View clickOverlay = calibrationDialog.findViewById(R.id.clickOverlay);
        clickOverlay.setOnClickListener(v -> togglePlayPause());

        btnClose.setOnClickListener(v -> dismissCalibrationDialog());

        calibrationDialog.setOnDismissListener(dialog -> {
            if (calibrationVideoView != null) {
                calibrationVideoView.setOnPreparedListener(null);
                calibrationVideoView.stopPlayback();
            }
            if (overlayHandler != null) {
                overlayHandler.removeCallbacksAndMessages(null);
            }
            calibrationDialog = null;
            calibrationVideoView = null;
            playPauseOverlay = null;
        });

        calibrationDialog.show();
    }

    private void togglePlayPause() {
        if (calibrationVideoView == null || playPauseOverlay == null) return;
        if (calibrationVideoView.isPlaying()) {
            calibrationVideoView.pause();
            playPauseOverlay.setImageResource(android.R.drawable.ic_media_play);
            showOverlayTemporarily();
        } else {
            calibrationVideoView.start();
            playPauseOverlay.setImageResource(android.R.drawable.ic_media_pause);
            showOverlayTemporarily();
        }
    }

    private void showOverlayTemporarily() {
        if (playPauseOverlay == null) return;
        playPauseOverlay.setVisibility(View.VISIBLE);
        playPauseOverlay.setAlpha(0.85f);
        if (overlayHandler == null) {
            overlayHandler = new Handler(Looper.getMainLooper());
        }
        overlayHandler.removeCallbacksAndMessages(null);
        
        final ImageView overlay = playPauseOverlay;
        overlayHandler.postDelayed(() -> {
            if (overlay != null && overlay.getVisibility() == View.VISIBLE) {
                overlay.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .withEndAction(() -> overlay.setVisibility(View.GONE))
                        .start();
            }
        }, 800);
    }

    private void dismissCalibrationDialog() {
        if (calibrationDialog != null) {
            calibrationDialog.dismiss();
        }
    }

    

    private static final class SpectrumPeak {
        final float frequency;
        final float energy;

        SpectrumPeak(float frequency, float energy) {
            this.frequency = frequency;
            this.energy = energy;
        }
    }

}
