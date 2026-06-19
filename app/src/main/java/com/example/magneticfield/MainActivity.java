package com.example.magneticfield;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.Dialog;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
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
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.animation.ValueAnimator;
import android.animation.ObjectAnimator;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.cardview.widget.CardView;
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
    private static final int AXES_CHART_HISTORY_SIZE = 14400; // 1 hour at 250 ms/sample
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
    private ImageView sampleRateHintIcon;
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
    // 数值 rolling 动画相关
    private float lastFieldValue = Float.NaN;
    private ValueAnimator fieldValueAnimator = null;
    private ValueAnimator xAxisAnimator = null;
    private ValueAnimator yAxisAnimator = null;
    private ValueAnimator zAxisAnimator = null;
    // 状态灯涟漪动画
    private ValueAnimator dotRippleAnimator = null;
    // 文件列表动画
    private boolean fileListAnimating = false;
    // Tab 切换动画
    private boolean tabSwitchAnimating = false;
    private View statusDot;
    private LinearLayout historyCard;
    private TextView btnCalibrate;
    private TextView btnRecord;
    private ValueAnimator recordPulseAnimator = null;
    private ValueAnimator recordBreathAnimator = null;
    private ObjectAnimator recordScaleAnimator = null;
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
        sampleRateHintIcon = findViewById(R.id.sampleRateHintIcon);
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
        sampleRateHintIcon.setOnClickListener(v -> showSampleRateDialog());

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
            sampleRateHintIcon.setEnabled(false);
            btnRecord.setAlpha(0.5f);
            btnReset.setAlpha(0.5f);
            sampleRateText.setAlpha(0.5f);
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
        // 停止录制按钮动画，防止内存泄漏
        stopRecordButtonAnimation();
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
            // 场强数值 rolling 动画
            animateFieldValue(ciChangText, fMagnitude);
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
        final CharSequence[] labels = new CharSequence[SAMPLE_RATE_OPTIONS_HZ.length];
        int checkedIndex = -1;
        int currentRate = MagneticSensorService.getTargetSampleRateHz();
        for (int i = 0; i < SAMPLE_RATE_OPTIONS_HZ.length; i++) {
            int rate = SAMPLE_RATE_OPTIONS_HZ[i];
            labels[i] = getString(SAMPLE_RATE_OPTION_LABELS[i]);
            if (rate == currentRate) {
                checkedIndex = i;
            }
        }
        final int primaryColor = getColorCompat(R.color.text_primary);
        confirmDialog = new android.app.AlertDialog.Builder(this, R.style.GlassAlertDialog)
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
        applyGlassDialogBackground(confirmDialog);
        // 确保列表项文字颜色
        final android.widget.ListView lv = confirmDialog.getListView();
        if (lv != null) {
            tintListViewItems(lv, primaryColor);
            lv.addOnLayoutChangeListener(new android.view.View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(android.view.View v, int left, int top, int right, int bottom,
                                           int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    tintListViewItems(lv, primaryColor);
                }
            });
        }
    }

    private static void tintListViewItems(android.widget.ListView listView, int textColor) {
        for (int i = 0, n = listView.getChildCount(); i < n; i++) {
            android.view.View child = listView.getChildAt(i);
            if (child instanceof android.widget.TextView) {
                ((android.widget.TextView) child).setTextColor(textColor);
            } else if (child instanceof android.view.ViewGroup) {
                android.view.ViewGroup vg = (android.view.ViewGroup) child;
                for (int j = 0, m = vg.getChildCount(); j < m; j++) {
                    android.view.View inner = vg.getChildAt(j);
                    if (inner instanceof android.widget.TextView) {
                        ((android.widget.TextView) inner).setTextColor(textColor);
                    }
                }
            }
        }
    }

    private void applyGlassDialogBackground(android.app.Dialog dialog) {
        if (dialog == null) {
            return;
        }
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(R.drawable.dialog_background);
        }
        final int primaryColor = getColorCompat(R.color.text_primary);
        if (dialog instanceof android.app.AlertDialog) {
            android.app.AlertDialog ad = (android.app.AlertDialog) dialog;
            android.widget.ListView listView = ad.getListView();
            if (listView != null) {
                listView.setBackgroundColor(android.graphics.Color.TRANSPARENT);
                listView.setDivider(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
                listView.setDividerHeight(0);
                listView.setSelector(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            }
            // 设置按钮和标题文字颜色
            android.widget.Button pos = ad.getButton(android.app.Dialog.BUTTON_POSITIVE);
            android.widget.Button neg = ad.getButton(android.app.Dialog.BUTTON_NEGATIVE);
            android.widget.Button neu = ad.getButton(android.app.Dialog.BUTTON_NEUTRAL);
            if (pos != null) pos.setTextColor(primaryColor);
            if (neg != null) neg.setTextColor(primaryColor);
            if (neu != null) neu.setTextColor(primaryColor);
            // 标题
            android.widget.TextView titleTv = ad.findViewById(android.R.id.title);
            if (titleTv != null) titleTv.setTextColor(primaryColor);
            // 消息文本
            android.widget.TextView messageTv = ad.findViewById(android.R.id.message);
            if (messageTv != null) messageTv.setTextColor(primaryColor);
        }
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
        // 停止之前的动画
        stopRecordButtonAnimation();
        if (isRecording || MagneticSensorService.isRecording()) {
            // 录制中：启动脉冲动画
            btnRecord.setEnabled(true);
            btnRecord.setAlpha(1f);
            btnRecord.setText(R.string.btn_pause);
            btnRecord.setBackgroundResource(R.drawable.record_button_pause_bg);
            startRecordPulseAnimation();
        } else if (isRecordingSaving || MagneticSensorService.isRecordingSaving()) {
            // 保存中：停止动画，显示禁用状态
            btnRecord.setEnabled(false);
            btnRecord.setAlpha(0.6f);
            btnRecord.setText(R.string.recording_saving_notification_title);
            btnRecord.setBackgroundResource(R.drawable.record_button_pause_bg);
        } else {
            // 未录制：启动呼吸动画
            btnRecord.setEnabled(true);
            btnRecord.setAlpha(1f);
            btnRecord.setText(R.string.btn_record);
            btnRecord.setBackgroundResource(R.drawable.record_button_bg);
            startRecordBreathAnimation();
        }
    }

    private void startRecordPulseAnimation() {
        // 红色脉冲扩散动画（录制中）
        recordPulseAnimator = ValueAnimator.ofFloat(1.0f, 1.15f);
        recordPulseAnimator.setDuration(1000);
        recordPulseAnimator.setRepeatMode(ValueAnimator.RESTART);
        recordPulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        recordPulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        recordPulseAnimator.addUpdateListener(animation -> {
            float scale = (Float) animation.getAnimatedValue();
            btnRecord.setScaleX(scale);
            btnRecord.setScaleY(scale);
        });
        recordPulseAnimator.start();
    }

    private void startRecordBreathAnimation() {
        // 呼吸动画（未录制时）
        recordBreathAnimator = ValueAnimator.ofFloat(1.0f, 1.03f);
        recordBreathAnimator.setDuration(2000);
        recordBreathAnimator.setRepeatMode(ValueAnimator.REVERSE);
        recordBreathAnimator.setRepeatCount(ValueAnimator.INFINITE);
        recordBreathAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        recordBreathAnimator.addUpdateListener(animation -> {
            float scale = (Float) animation.getAnimatedValue();
            btnRecord.setScaleX(scale);
            btnRecord.setScaleY(scale);
        });
        recordBreathAnimator.start();
    }

    private void stopRecordButtonAnimation() {
        if (recordPulseAnimator != null) {
            recordPulseAnimator.cancel();
            recordPulseAnimator = null;
        }
        if (recordBreathAnimator != null) {
            recordBreathAnimator.cancel();
            recordBreathAnimator = null;
        }
        // 恢复原始大小
        if (btnRecord != null) {
            btnRecord.setScaleX(1.0f);
            btnRecord.setScaleY(1.0f);
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

    




    // ── 动画辅助方法 ──

    /**
     * ③ 数值 rolling 动画：平滑过渡到新值
     */
    private void animateFieldValue(TextView target, float newValue) {
        if (Float.isNaN(newValue)) return;
        if (fieldValueAnimator != null) {
            fieldValueAnimator.cancel();
        }
        float startValue = Float.isNaN(lastFieldValue) ? newValue : lastFieldValue;
        lastFieldValue = newValue;
        fieldValueAnimator = ValueAnimator.ofFloat(startValue, newValue);
        fieldValueAnimator.setDuration(250);
        fieldValueAnimator.setInterpolator(new DecelerateInterpolator(1.5f));
        fieldValueAnimator.addUpdateListener(animation -> {
            float v = (Float) animation.getAnimatedValue();
            target.setText(String.format(Locale.US, getString(R.string.field_value_format), v));
        });
        fieldValueAnimator.start();
    }

    /**
     * 轴数值 rolling 动画
     */
    private void animateAxisValue(TextView target, float newValue, ValueAnimator animator) {
        if (animator != null) {
            animator.cancel();
        }
        String currentText = target.getText().toString();
        float startValue;
        try {
            startValue = Float.parseFloat(currentText);
        } catch (Exception e) {
            startValue = newValue;
        }
        ValueAnimator va = ValueAnimator.ofFloat(startValue, newValue);
        va.setDuration(250);
        va.setInterpolator(new DecelerateInterpolator(1.5f));
        va.addUpdateListener(animation -> {
            float v = (Float) animation.getAnimatedValue();
            target.setText(String.format(Locale.US, getString(R.string.axis_value_format), v));
        });
        va.start();
        // 保存引用到对应字段
        if (target == xAxisText) xAxisAnimator = va;
        else if (target == yAxisText) yAxisAnimator = va;
        else if (target == zAxisText) zAxisAnimator = va;
    }

    /**
     * ④ 状态灯涟漪动画
     */
    private void animateDotRipple(int newColor) {
        if (statusDot == null) return;
        // 先取消之前的涟漪动画
        if (dotRippleAnimator != null) {
            dotRippleAnimator.cancel();
        }
        // 缩放弹跳动画（轻微放大再恢复，模拟涟漪）
        statusDot.setScaleX(1.0f);
        statusDot.setScaleY(1.0f);
        ValueAnimator ripple = ValueAnimator.ofFloat(1.0f, 1.35f, 1.0f);
        ripple.setDuration(400);
        ripple.setInterpolator(new AccelerateDecelerateInterpolator());
        ripple.addUpdateListener(animation -> {
            float scale = (Float) animation.getAnimatedValue();
            statusDot.setScaleX(scale);
            statusDot.setScaleY(scale);
        });
        ripple.start();
        dotRippleAnimator = ripple;

        // 同时带一个背景圆形扩散的假涟漪（用额外的 View 实现）
        if (statusDot.getParent() instanceof FrameLayout) {
            FrameLayout parent = (FrameLayout) statusDot.getParent();
            // 由于 statusDot 现在是 View（不是 FrameLayout），需要用代码创建涟漪圈
            android.view.animation.Animation alphaAnim =
                    new android.view.animation.AlphaAnimation(0.5f, 0f);
            alphaAnim.setDuration(500);
            statusDot.startAnimation(alphaAnim);
        }
    }

    /**
     * 给按钮添加按压反馈（短暂缩小后恢复）
     */
    private View.OnClickListener withPressEffect(View.OnClickListener listener) {
        return v -> {
            v.animate()
                    .scaleX(0.92f)
                    .scaleY(0.92f)
                    .setDuration(80)
                    .setInterpolator(new DecelerateInterpolator())
                    .withEndAction(() -> v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(120)
                            .setInterpolator(new DecelerateInterpolator())
                            .start())
                    .start();
            if (listener != null) listener.onClick(v);
        };
    }

    /**
     * ⑤ 文件列表条目入场动画（滑入 + 淡入）
     */
    private void animateFileListItem(View itemView, int position) {
        itemView.setAlpha(0f);
        itemView.setTranslationX(dp(40));
        itemView.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(300)
                .setStartDelay(position * 60L)
                .setInterpolator(new DecelerateInterpolator(1.5f))
                .start();
    }

    /**
     * 文件列表条目删除动画（滑出 + 淡出）
     */
    private void animateFileListItemExit(View itemView, Runnable onEnd) {
        itemView.animate()
                .alpha(0f)
                .translationX(dp(80))
                .setDuration(250)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(onEnd)
                .start();
    }

    /**
     * ⑦ Tab 切换淡入淡出动画
     */
    private void animateTabSwitch(View outPage, View inPage) {
        if (tabSwitchAnimating) return;
        tabSwitchAnimating = true;
        outPage.animate()
                .alpha(0f)
                .setDuration(120)
                .withEndAction(() -> {
                    outPage.setVisibility(View.GONE);
                    inPage.setAlpha(0f);
                    inPage.setVisibility(View.VISIBLE);
                    inPage.animate()
                            .alpha(1f)
                            .setDuration(120)
                            .withEndAction(() -> tabSwitchAnimating = false)
                            .start();
                })
                .start();
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

        for (int i = 0; i < files.length; i++) {
            View row = createFileRow(files[i]);
            fileListContainer.addView(row);
            // 文件列表条目入场动画（滑入 + 淡入）
            animateFileListItem(row, i);
        }
    }

    private View createFileRow(File file) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12));
        row.setBackgroundResource(R.drawable.file_item_bg);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(6);
        row.setLayoutParams(rowParams);

        
        TextView fileNameText = new TextView(this);
        fileNameText.setText(file.getName());
        fileNameText.setTextColor(getColorCompat(R.color.text_primary));
        fileNameText.setTextSize(14);
        row.addView(fileNameText);

        
        SimpleDateFormat sdf = FILE_TIME_FORMAT.get();
        TextView timeText = new TextView(this);
        timeText.setText(sdf.format(new Date(file.lastModified())));
        timeText.setTextColor(getColorCompat(R.color.text_secondary));
        timeText.setTextSize(11);
        timeText.setPadding(0, dp(4), 0, 0);
        row.addView(timeText);

        
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        actionRow.setPadding(0, dp(10), 0, 0);
        actionRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        
        TextView btnOpen = new TextView(this);
        btnOpen.setText(R.string.btn_open);
        btnOpen.setTextColor(0xFFFFFFFF);
        btnOpen.setTextSize(13);
        btnOpen.setGravity(Gravity.CENTER);
        btnOpen.setBackgroundResource(R.drawable.action_button_bg);
        btnOpen.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(
                0,
                dp(36));
        openParams.weight = 1;
        openParams.setMarginEnd(dp(8));
        btnOpen.setLayoutParams(openParams);
        btnOpen.setOnClickListener(withPressEffect(v -> openFile(file)));
        actionRow.addView(btnOpen);


        TextView btnShare = new TextView(this);
        btnShare.setText(R.string.btn_share);
        btnShare.setTextColor(0xFFFFFFFF);
        btnShare.setTextSize(13);
        btnShare.setGravity(Gravity.CENTER);
        btnShare.setBackgroundResource(R.drawable.action_button_bg);
        btnShare.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams shareParams = new LinearLayout.LayoutParams(
                0,
                dp(36));
        shareParams.weight = 1;
        shareParams.setMarginEnd(dp(8));
        btnShare.setLayoutParams(shareParams);
        btnShare.setOnClickListener(withPressEffect(v -> shareFile(file)));
        actionRow.addView(btnShare);


        TextView btnAnalyze = new TextView(this);
        btnAnalyze.setText(R.string.btn_analyze);
        btnAnalyze.setTextColor(0xFFFFFFFF);
        btnAnalyze.setTextSize(13);
        btnAnalyze.setGravity(Gravity.CENTER);
        btnAnalyze.setBackgroundResource(R.drawable.analyze_button_bg);
        btnAnalyze.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams analyzeParams = new LinearLayout.LayoutParams(
                0,
                dp(36));
        analyzeParams.weight = 1;
        analyzeParams.setMarginEnd(dp(8));
        btnAnalyze.setLayoutParams(analyzeParams);
        btnAnalyze.setOnClickListener(withPressEffect(v -> analyzeFile(file)));
        actionRow.addView(btnAnalyze);

        TextView btnDelete = new TextView(this);
        btnDelete.setText(R.string.btn_delete);
        btnDelete.setTextColor(0xFFFFFFFF);
        btnDelete.setTextSize(13);
        btnDelete.setGravity(Gravity.CENTER);
        btnDelete.setBackgroundResource(R.drawable.delete_button_bg);
        btnDelete.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(
                0,
                dp(36));
        deleteParams.weight = 1;
        btnDelete.setLayoutParams(deleteParams);
        btnDelete.setOnClickListener(withPressEffect(v -> deleteFile(file)));
        actionRow.addView(btnDelete);

        row.addView(actionRow);
        return row;
    }

    private void analyzeFile(File file) {
        if (isFinishing() || isDestroyed()) return;
        android.app.AlertDialog progressDialog = new android.app.AlertDialog.Builder(this, R.style.GlassAlertDialog)
                .setTitle(R.string.analyze_title)
                .setMessage(R.string.analyze_loading)
                .setCancelable(false)
                .create();
        progressDialog.show();
        applyGlassDialogBackground(progressDialog);

        Runnable analyzeTask = () -> {
            try {
                AnalysisUtils.AnalysisResult result = AnalysisUtils.analyzeFile(file);
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.dismiss();
                        showAnalysisDialog(result, file);
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Analyze failed", e);
                final String errMsg = e.getMessage() != null ? e.getMessage() : getString(R.string.analyze_error);
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        progressDialog.dismiss();
                        Toast.makeText(this, getString(R.string.analyze_error) + ": " + errMsg, Toast.LENGTH_LONG).show();
                    }
                });
            }
        };
        try {
            saveExecutor.execute(analyzeTask);
        } catch (RejectedExecutionException e) {
            new Thread(analyzeTask, "MagneticAnalyzeFallback").start();
        }
    }

    private void showAnalysisDialog(AnalysisUtils.AnalysisResult result, File file) {
        if (isFinishing() || isDestroyed()) return;

        // ── 根视图：整体白色背景，垂直线性布局 ──
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));
        root.setBackgroundColor(getColorCompat(R.color.dialog_bg));

        // ── 顶部导航栏 ──
        LinearLayout navbar = new LinearLayout(this);
        navbar.setOrientation(LinearLayout.HORIZONTAL);
        navbar.setGravity(Gravity.CENTER_VERTICAL);
        navbar.setBackgroundColor(getColorCompat(R.color.dialog_bg));
        // 顶部留出状态栏安全距离（约 24dp），防止标题被挖孔屏遮挡
        int statusBarInset = getStatusBarHeight();
        navbar.setPadding(dp(12), dp(10) + statusBarInset, dp(16), dp(10));
        LinearLayout.LayoutParams navParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(52) + statusBarInset);
        navbar.setLayoutParams(navParams);

        // 返回按钮（圆形背景）
        Dialog[] dialogHolder = new Dialog[1];
        FrameLayout backBtn = new FrameLayout(this);
        backBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        GradientDrawable backCircle = new GradientDrawable();
        backCircle.setShape(GradientDrawable.OVAL);
        backCircle.setColor(getColorCompat(R.color.card_bg));
        backBtn.setBackground(backCircle);
        backBtn.setOnClickListener(v -> {
            if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        });
        // 返回箭头 "‹" 文字代替
        TextView backArrow = new TextView(this);
        backArrow.setText("‹");
        backArrow.setTextColor(getColorCompat(R.color.text_primary));
        backArrow.setTextSize(22);
        backArrow.setGravity(Gravity.CENTER);
        backArrow.setPadding(0, 0, dp(2), dp(2));
        FrameLayout.LayoutParams arrowLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        backArrow.setLayoutParams(arrowLp);
        backBtn.addView(backArrow);
        navbar.addView(backBtn);

        // 标题居中
        TextView navTitle = new TextView(this);
        navTitle.setText(R.string.analyze_title);
        navTitle.setTextColor(getColorCompat(R.color.text_primary));
        navTitle.setTextSize(15);
        navTitle.setTypeface(null, Typeface.NORMAL);
        navTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        navTitle.setLayoutParams(titleLp);
        navbar.addView(navTitle);

        // 右侧：PDF 导出分享按钮（与返回按钮等宽，保持标题居中）
        FrameLayout exportBtn = new FrameLayout(this);
        exportBtn.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        GradientDrawable exportCircle = new GradientDrawable();
        exportCircle.setShape(GradientDrawable.OVAL);
        exportCircle.setColor(getColorCompat(R.color.stat_card_bg));
        exportBtn.setBackground(exportCircle);
        // 用文字图标 "↑" 表示导出/分享
        TextView exportIcon = new TextView(this);
        exportIcon.setText("⇧");
        exportIcon.setTextColor(getColorCompat(R.color.primary));
        exportIcon.setTextSize(17);
        exportIcon.setGravity(Gravity.CENTER);
        exportIcon.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        exportBtn.addView(exportIcon);
        // 点击事件在 dialog 创建后通过 final 引用设置（见下方）
        navbar.addView(exportBtn);

        // 导航栏底部分割线
        View navDivider = new View(this);
        navDivider.setBackgroundColor(getColorCompat(R.color.divider));
        navDivider.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1));

        root.addView(navbar);
        root.addView(navDivider);

        // ── 可滚动内容区 ──
        ScrollView scrollView = new ScrollView(this);
        scrollView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0, 1f));
        scrollView.setBackgroundColor(getColorCompat(R.color.dialog_bg));

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(32));
        content.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── 文件信息卡片 ──
        CardView fileCard = new CardView(this);
        fileCard.setRadius(dp(14));
        fileCard.setCardElevation(0);
        fileCard.setCardBackgroundColor(getColorCompat(R.color.card_bg));
        GradientDrawable fileBorder = new GradientDrawable();
        fileBorder.setColor(getColorCompat(R.color.card_bg));
        fileBorder.setStroke(1, getColorCompat(R.color.card_border));
        fileBorder.setCornerRadius(dp(14));
        fileCard.setBackground(fileBorder);
        LinearLayout.LayoutParams fileCardLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        fileCardLp.bottomMargin = dp(12);
        fileCard.setLayoutParams(fileCardLp);

        LinearLayout fileCardInner = new LinearLayout(this);
        fileCardInner.setOrientation(LinearLayout.VERTICAL);
        fileCardInner.setPadding(dp(14), dp(14), dp(14), dp(14));

        TextView fileNameView = new TextView(this);
        fileNameView.setText(file.getName());
        fileNameView.setTextColor(getColorCompat(R.color.text_primary));
        fileNameView.setTextSize(13);
        fileNameView.setTypeface(null, Typeface.BOLD);
        fileCardInner.addView(fileNameView);

        TextView fileMetaView = new TextView(this);
        fileMetaView.setText(String.format(Locale.US,
                getString(R.string.analyze_file_info),
                result.n, result.duration, result.mean, result.std));
        fileMetaView.setTextColor(getColorCompat(R.color.text_secondary));
        fileMetaView.setTextSize(11);
        fileMetaView.setPadding(0, dp(4), 0, 0);
        fileCardInner.addView(fileMetaView);

        fileCard.addView(fileCardInner);
        content.addView(fileCard);

        // ── 2×2 指标卡片网格 ──
        GridLayout metricGrid = new GridLayout(this);
        metricGrid.setColumnCount(2);
        metricGrid.setRowCount(2);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        gridLp.bottomMargin = dp(12);
        metricGrid.setLayoutParams(gridLp);
        metricGrid.setUseDefaultMargins(false);

        String[][] metricData = {
                {getString(R.string.analyze_freq_range), result.freqRange},
                {getString(R.string.analyze_sample_rate), String.format(Locale.US, "%.0f Hz", result.fs)},
                {getString(R.string.analyze_energy_focus), result.energyRange},
                {getString(R.string.analyze_nyquist), String.format(Locale.US, "%.1f Hz", result.nyquist)}
        };
        for (int i = 0; i < 4; i++) {
            GridLayout.Spec rowSpec = GridLayout.spec(i / 2, GridLayout.FILL, 1f);
            GridLayout.Spec colSpec = GridLayout.spec(i % 2, GridLayout.FILL, 1f);
            GridLayout.LayoutParams cellLp = new GridLayout.LayoutParams(rowSpec, colSpec);
            cellLp.width = 0;
            int leftMargin = (i % 2 == 0) ? 0 : dp(5);
            int rightMargin = (i % 2 == 0) ? dp(5) : 0;
            int topMargin = (i / 2 == 0) ? 0 : dp(10);
            cellLp.setMargins(leftMargin, topMargin, rightMargin, 0);

            CardView mc = new CardView(this);
            mc.setRadius(dp(12));
            mc.setCardElevation(0);
            mc.setCardBackgroundColor(getColorCompat(R.color.sample_rate_bg));
            mc.setLayoutParams(cellLp);

            LinearLayout mcInner = new LinearLayout(this);
            mcInner.setOrientation(LinearLayout.VERTICAL);
            mcInner.setPadding(dp(14), dp(12), dp(14), dp(12));

            TextView mcLabel = new TextView(this);
            mcLabel.setText(metricData[i][0]);
            mcLabel.setTextColor(getColorCompat(R.color.text_secondary));
            mcLabel.setTextSize(11);
            mcInner.addView(mcLabel);

            TextView mcValue = new TextView(this);
            mcValue.setText(metricData[i][1]);
            mcValue.setTextColor(getColorCompat(R.color.primary));
            // 较长文本用小字号
            mcValue.setTextSize(metricData[i][1].length() > 8 ? 14 : 18);
            mcValue.setTypeface(null, Typeface.NORMAL);
            mcValue.setPadding(0, dp(5), 0, 0);
            mcInner.addView(mcValue);

            mc.addView(mcInner);
            metricGrid.addView(mc);
        }
        content.addView(metricGrid);

        // ── 统计摘要行（均值 / 标准差 / 频谱类型）──
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams statsRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        statsRowLp.bottomMargin = dp(12);
        statsRow.setLayoutParams(statsRowLp);

        String[][] statsData = {
                {getString(R.string.analyze_mean), String.format(Locale.US, "%.1f μT", result.mean)},
                {getString(R.string.analyze_std), String.format(Locale.US, "%.1f μT", result.std)},
                {getString(R.string.analyze_spectrum_type), AnalysisUtils.getSpectrumTypeLabel(this, result.spectrumType)}
        };
        for (int i = 0; i < 3; i++) {
            CardView sc = new CardView(this);
            sc.setRadius(dp(12));
            sc.setCardElevation(0);
            sc.setCardBackgroundColor(getColorCompat(R.color.card_bg));
            LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) scLp.leftMargin = dp(8);
            sc.setLayoutParams(scLp);

            LinearLayout scInner = new LinearLayout(this);
            scInner.setOrientation(LinearLayout.VERTICAL);
            scInner.setPadding(dp(10), dp(12), dp(10), dp(12));
            scInner.setGravity(Gravity.CENTER_HORIZONTAL);

            TextView scLabel = new TextView(this);
            scLabel.setText(statsData[i][0]);
            scLabel.setTextColor(getColorCompat(R.color.text_secondary));
            scLabel.setTextSize(11);
            scLabel.setGravity(Gravity.CENTER);
            scInner.addView(scLabel);

            TextView scValue = new TextView(this);
            scValue.setText(statsData[i][1]);
            // 频谱类型用蓝色，其他用黑色
            scValue.setTextColor(i == 2 ? getColorCompat(R.color.primary) : getColorCompat(R.color.text_primary));
            scValue.setTextSize(i == 2 ? 12 : 15);
            scValue.setTypeface(null, i == 2 ? Typeface.NORMAL : Typeface.BOLD);
            scValue.setGravity(Gravity.CENTER);
            scValue.setPadding(0, dp(3), 0, 0);
            scInner.addView(scValue);

            sc.addView(scInner);
            statsRow.addView(sc);
        }
        content.addView(statsRow);

        // ── 三张图表卡片 ──
        content.addView(createChartCard(result.timeX, result.timeY, result.timeCount,
                getString(R.string.analyze_time_waveform), "Time (s)", "uT", false));
        content.addView(createChartCard(result.fftX, result.fftY, result.fftCount,
                getString(R.string.analyze_fft), "Frequency (Hz)", "Amplitude (uT)", true, 0f, 15f,
                0f, Float.NaN));
        content.addView(createChartCard(result.welchX, result.welchY, result.welchCount,
                getString(R.string.analyze_welch), "Frequency (Hz)", "PSD (uT\u00b2/Hz)", true, 0f, 15f,
                0f, Float.NaN));

        // ── 峰值频率卡片 ──
        if (!result.peaks.isEmpty()) {
            CardView peaksCard = new CardView(this);
            peaksCard.setRadius(dp(14));
            peaksCard.setCardElevation(0);
            peaksCard.setCardBackgroundColor(getColorCompat(R.color.card_bg));
            LinearLayout.LayoutParams peaksCardLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            peaksCardLp.topMargin = dp(12);
            peaksCard.setLayoutParams(peaksCardLp);

            LinearLayout peaksInner = new LinearLayout(this);
            peaksInner.setOrientation(LinearLayout.VERTICAL);
            peaksInner.setPadding(dp(16), dp(14), dp(16), dp(6));

            TextView peaksTitle = new TextView(this);
            peaksTitle.setText(getString(R.string.analyze_main_freqs));
            peaksTitle.setTextColor(getColorCompat(R.color.text_primary));
            peaksTitle.setTextSize(13);
            peaksTitle.setTypeface(null, Typeface.BOLD);
            peaksTitle.setPadding(0, 0, 0, dp(10));
            peaksInner.addView(peaksTitle);

            int peakCount = Math.min(result.peaks.size(), 3);
            float maxAmp = result.peaks.get(0).amplitude;
            for (int i = 0; i < peakCount; i++) {
                AnalysisUtils.Peak p = result.peaks.get(i);

                LinearLayout peakRow = new LinearLayout(this);
                peakRow.setOrientation(LinearLayout.HORIZONTAL);
                peakRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams peakRowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                peakRowLp.bottomMargin = dp(10);
                peakRow.setLayoutParams(peakRowLp);

                // 排名圆圈
                FrameLayout rankCircle = new FrameLayout(this);
                GradientDrawable rankBg = new GradientDrawable();
                rankBg.setShape(GradientDrawable.OVAL);
                rankBg.setColor(getColorCompat(R.color.stat_card_bg));
                rankCircle.setBackground(rankBg);
                rankCircle.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
                TextView rankNum = new TextView(this);
                rankNum.setText(String.valueOf(i + 1));
                rankNum.setTextColor(getColorCompat(R.color.primary));
                rankNum.setTextSize(11);
                rankNum.setTypeface(null, Typeface.BOLD);
                rankNum.setGravity(Gravity.CENTER);
                rankNum.setLayoutParams(new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT));
                rankCircle.addView(rankNum);
                peakRow.addView(rankCircle);

                // 频率值
                TextView freqVal = new TextView(this);
                freqVal.setText(String.format(Locale.US, "%.2f Hz", p.frequency));
                freqVal.setTextColor(getColorCompat(R.color.text_primary));
                freqVal.setTextSize(14);
                freqVal.setTypeface(null, Typeface.BOLD);
                LinearLayout.LayoutParams freqLp = new LinearLayout.LayoutParams(dp(80), LinearLayout.LayoutParams.WRAP_CONTENT);
                freqLp.leftMargin = dp(10);
                freqVal.setLayoutParams(freqLp);
                peakRow.addView(freqVal);

                // 比例条
                FrameLayout barWrap = new FrameLayout(this);
                barWrap.setLayoutParams(new LinearLayout.LayoutParams(0, dp(4), 1f));
                GradientDrawable barBg = new GradientDrawable();
                barBg.setColor(getColorCompat(R.color.card_bg));
                barBg.setCornerRadius(dp(2));
                barWrap.setBackground(barBg);

                View bar = new View(this);
                float barRatio = maxAmp > 0 ? p.amplitude / maxAmp : 0f;
                GradientDrawable barFg = new GradientDrawable();
                barFg.setColor(getColorCompat(R.color.primary));
                barFg.setCornerRadius(dp(2));
                bar.setBackground(barFg);
                FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                        0, FrameLayout.LayoutParams.MATCH_PARENT);
                bar.setLayoutParams(barLp);
                barWrap.addView(bar);
                // 用 post 延迟设宽度百分比
                final View finalBar = bar;
                final float finalRatio = barRatio;
                barWrap.post(() -> {
                    int totalW = barWrap.getWidth();
                    FrameLayout.LayoutParams lp2 = (FrameLayout.LayoutParams) finalBar.getLayoutParams();
                    lp2.width = (int) (totalW * finalRatio);
                    finalBar.setLayoutParams(lp2);
                });
                peakRow.addView(barWrap);

                // 幅度值
                TextView ampVal = new TextView(this);
                ampVal.setText(String.format(Locale.US, "%.1f μT", p.amplitude));
                ampVal.setTextColor(getColorCompat(R.color.text_secondary));
                ampVal.setTextSize(13);
                ampVal.setGravity(Gravity.END);
                LinearLayout.LayoutParams ampLp = new LinearLayout.LayoutParams(dp(60), LinearLayout.LayoutParams.WRAP_CONTENT);
                ampLp.leftMargin = dp(8);
                ampVal.setLayoutParams(ampLp);
                peakRow.addView(ampVal);

                peaksInner.addView(peakRow);
            }
            peaksCard.addView(peaksInner);
            content.addView(peaksCard);
        }

        scrollView.addView(content);
        root.addView(scrollView);

        // ── 分析弹窗卡片依次入场动画 ──
        java.util.List<View> analysisAnimViews = new java.util.ArrayList<>();
        for (int i = 0; i < content.getChildCount(); i++) {
            analysisAnimViews.add(content.getChildAt(i));
        }
        for (View v : analysisAnimViews) {
            v.setAlpha(0f);
            v.setTranslationY(dp(20));
        }
        for (int i = 0; i < analysisAnimViews.size(); i++) {
            final View target = analysisAnimViews.get(i);
            final long delay = i * 80L;
            mainHandler.postDelayed(() -> {
                if (target.getParent() == null) return;
                target.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .setDuration(350)
                        .setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f))
                        .start();
            }, delay);
        }

        // 使用 Dialog 替代 AlertDialog，在 show() 之前设置 Window 属性，避免动画跳动
        Dialog dialog = new Dialog(this, android.R.style.Theme_Material_Light_NoActionBar);
        dialog.setContentView(root);
        dialogHolder[0] = dialog;

        // 在 dialog 创建后绑定导出按钮事件（dialog 此时已 effectively final）
        exportBtn.setOnClickListener(v -> exportPdfAndShare(result, file, dialog));

        android.view.Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            // 关键：在 show() 之前设置 LayoutParams，避免动画跳动
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.width = android.view.WindowManager.LayoutParams.MATCH_PARENT;
            params.height = android.view.WindowManager.LayoutParams.MATCH_PARENT;
            params.gravity = Gravity.TOP;  // 从顶部开始布局，避免左移
            // 使用自定义淡入上移动画，替代系统默认动画
            params.windowAnimations = R.style.DialogAnimation;
            window.setAttributes(params);
            // 设置状态栏颜色与导航栏一致
            window.setStatusBarColor(getColorCompat(R.color.dialog_bg));
        }
        dialog.show();
    }

    /**
     * 后台生成 PDF 并通过系统分享对话框发送。
     * 点击分析弹窗顶部导航栏右侧的导出按钮时触发。
     */
    private void exportPdfAndShare(AnalysisUtils.AnalysisResult result, File srcFile, Dialog dialog) {
        if (isFinishing() || isDestroyed()) return;
        Toast.makeText(this, R.string.analyze_pdf_generating, Toast.LENGTH_SHORT).show();
        Runnable task = () -> {
            try {
                File pdfFile = AnalysisPdfExporter.export(this, result, srcFile);
                mainHandler.post(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    try {
                        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                                this, getPackageName() + ".fileprovider", pdfFile);
                        Intent intent = new Intent(Intent.ACTION_SEND);
                        intent.setType("application/pdf");
                        intent.putExtra(Intent.EXTRA_STREAM, uri);
                        intent.putExtra(Intent.EXTRA_SUBJECT,
                                getString(R.string.analyze_pdf_share_title));
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(intent,
                                getString(R.string.analyze_pdf_share_title)));
                    } catch (Exception e) {
                        Log.e(TAG, "Share PDF failed", e);
                        Toast.makeText(this, R.string.analyze_pdf_failed, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Generate PDF failed", e);
                mainHandler.post(() -> {
                    if (!isFinishing() && !isDestroyed()) {
                        Toast.makeText(this, R.string.analyze_pdf_failed, Toast.LENGTH_LONG).show();
                    }
                });
            }
        };
        try {
            saveExecutor.execute(task);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            new Thread(task, "PdfExportFallback").start();
        }
    }

    private CardView createStatCard(String label, String value) {
        CardView card = new CardView(this);
        card.setRadius(dp(12));
        card.setCardElevation(0);
        card.setCardBackgroundColor(getColorCompat(R.color.stat_card_bg));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(110), dp(80));
        params.setMargins(dp(6), 0, dp(6), 0);
        card.setLayoutParams(params);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        inner.setPadding(dp(12), dp(10), dp(12), dp(10));
        inner.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT));

        TextView labelView = new TextView(this);
        labelView.setText(label);
        labelView.setTextColor(getColorCompat(R.color.text_secondary));
        labelView.setTextSize(11);
        inner.addView(labelView);

        TextView valueView = new TextView(this);
        valueView.setText(value);
        valueView.setTextColor(getColorCompat(R.color.stat_card_text));
        valueView.setTextSize(16);
        valueView.setTypeface(null, Typeface.BOLD);
        valueView.setPadding(0, dp(6), 0, 0);
        inner.addView(valueView);

        card.addView(inner);
        return card;
    }

    private CardView createChartCard(float[] x, float[] y, int count,
                                     String title, String xLabel, String yLabel, boolean fill) {
        return createChartCard(x, y, count, title, xLabel, yLabel, fill, Float.NaN, Float.NaN);
    }

    private CardView createChartCard(float[] x, float[] y, int count,
                                     String title, String xLabel, String yLabel,
                                     boolean fill, float fixedXMin, float fixedXMax) {
        return createChartCard(x, y, count, title, xLabel, yLabel, fill,
                fixedXMin, fixedXMax, Float.NaN, Float.NaN);
    }

    private CardView createChartCard(float[] x, float[] y, int count,
                                     String title, String xLabel, String yLabel,
                                     boolean fill, float fixedXMin, float fixedXMax,
                                     float fixedYMin, float fixedYMax) {
        CardView card = new CardView(this);
        card.setRadius(dp(16));
        card.setCardElevation(0);
        card.setCardBackgroundColor(getColorCompat(R.color.stat_card_bg));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        card.setLayoutParams(params);

        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setPadding(dp(12), dp(12), dp(12), dp(12));
        inner.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        AnalysisChartView chart = new AnalysisChartView(this);
        chart.setTitle(title);
        chart.setAxisLabels(xLabel, yLabel);
        chart.setFillArea(fill);
        if (!Float.isNaN(fixedXMin) && !Float.isNaN(fixedXMax)) {
            chart.setFixedXRange(fixedXMin, fixedXMax);
        }
        if (!Float.isNaN(fixedYMin) || !Float.isNaN(fixedYMax)) {
            // 至少一个有效就设置，另一个用 NaN 保持自动
            chart.setFixedYRange(
                    Float.isNaN(fixedYMin) ? Float.NaN : fixedYMin,
                    Float.isNaN(fixedYMax) ? Float.NaN : fixedYMax
            );
        }
        chart.setData(x, y, count);
        chart.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(200)));
        inner.addView(chart);

        card.addView(inner);
        return card;
    }

    private void deleteFile(File file) {
        
        if (isFinishing() || isDestroyed()) return;
        confirmDialog = new android.app.AlertDialog.Builder(this, R.style.GlassAlertDialog)
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
                .create();
        confirmDialog.show();
        applyGlassDialogBackground(confirmDialog);
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
        if (spectrumPage.getVisibility() == View.VISIBLE) return;
        animateTabSwitch(axesPage, spectrumPage);
        tabSpectrum.setBackgroundResource(R.drawable.segment_active);
        tabAxes.setBackground(null);
        tabSpectrum.setTextColor(getColorCompat(R.color.segment_active_text));
        tabAxes.setTextColor(getColorCompat(R.color.text_secondary));
    }

    private void showAxesPage() {
        if (axesPage.getVisibility() == View.VISIBLE) return;
        animateTabSwitch(spectrumPage, axesPage);
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
        // 轴数值 rolling 动画
        animateAxisValue(xAxisText, x, xAxisAnimator);
        animateAxisValue(yAxisText, y, yAxisAnimator);
        animateAxisValue(zAxisText, z, zAxisAnimator);
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
                    ? String.format(Locale.US, "%.6f", peak.energy)
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
        // 状态灯切换时触发涟漪动画
        animateDotRipple(color);
    }

    private int getColorCompat(int resId) {
        return ContextCompat.getColor(this, resId);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /**
     * 获取状态栏高度（px）。
     * 用于在 Dialog 中预留安全区域，避免标题被挖孔屏遮挡。
     */
    private int getStatusBarHeight() {
        int resourceId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resourceId > 0) {
            return getResources().getDimensionPixelSize(resourceId);
        }
        // fallback：按 24dp 估算（适配大部分手机）
        return dp(24);
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
