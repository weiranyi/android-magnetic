package io.github.weiranyi.magneticfield;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 前台通知辅助类，负责：
 * 1. 通知渠道创建
 * 2. 前台通知构建（传感器监控/录制中/保存中）
 * 3. 录制完成通知
 * 4. 通知安全发送
 */
public class NotificationHelper {

    static final String CHANNEL_ID = "magnetic_sensor_live";
    static final String ACTION_STOP_RECORDING = "io.github.weiranyi.magneticfield.action.STOP_RECORDING";
    static final int NOTIFICATION_ID = 1001;
    static final int COMPLETE_NOTIFICATION_ID = 1002;
    static final long COMPLETE_NOTIFICATION_TIMEOUT_MS = 4_000L;
    static final int LIVE_NOTIFICATION_COLOR = 0xFFE53935;
    static final int COMPLETE_NOTIFICATION_COLOR = 0xFF00C853;
    private static final String EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing";
    private static final String EXTRA_SHORT_CRITICAL_TEXT = "android.shortCriticalText";

    private static final ThreadLocal<SimpleDateFormat> CSV_TIME_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("HH:mm:ss", Locale.US);
        }
    };

    /**
     * 确保通知渠道已创建（Android O+）。
     */
    static void ensureNotificationChannel(Context context) {
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

    /**
     * 构建前台通知。
     *
     * @param context                  Service 上下文
     * @param magnitude                当前磁场强度
     * @param isRecording              是否录制中
     * @param isSaving                 是否保存中
     * @param hasLastSample            是否有最新样本
     * @param recordingStartedWallTimeMs 录制开始时间
     */
    static Notification buildNotification(Context context, float magnitude,
                                           boolean isRecording, boolean isSaving,
                                           boolean hasLastSample, long lastTimestampNs,
                                           long recordingStartedWallTimeMs) {
        String title;
        String text;
        int icon;
        int priority;
        if (isRecording) {
            title = context.getString(R.string.recording_notification_title);
            text = String.format(Locale.US, context.getString(R.string.recording_notification_text), magnitude);
            icon = R.drawable.ic_notification_recording;
            priority = NotificationCompat.PRIORITY_DEFAULT;
        } else if (isSaving) {
            title = context.getString(R.string.recording_saving_notification_title);
            text = context.getString(R.string.recording_saving_notification_text);
            icon = R.drawable.ic_notification_recording;
            priority = NotificationCompat.PRIORITY_DEFAULT;
        } else {
            title = context.getString(R.string.sensor_service_notification_title);
            text = lastTimestampNs > 0L
                    ? String.format(Locale.US, context.getString(R.string.sensor_service_notification_text), magnitude)
                            + "  " + CSV_TIME_FORMAT.get().format(new Date())
                    : context.getString(R.string.collecting);
            icon = R.drawable.ic_target;
            priority = NotificationCompat.PRIORITY_DEFAULT;
        }
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(buildLaunchPendingIntent(context, 0))
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
                            context.getString(R.string.btn_pause),
                            buildStopRecordingPendingIntent(context))
                            .build())
                    .addExtras(buildPromotedExtras(context.getString(R.string.recording_live_short)));
        } else if (isSaving) {
            builder.setShowWhen(false)
                    .setProgress(0, 0, true)
                    .addExtras(buildPromotedExtras(context.getString(R.string.recording_live_short)));
        } else {
            builder.setShowWhen(false);
        }
        return builder.build();
    }

    /**
     * 更新前台通知。
     */
    static void updateForegroundNotification(Context context, float magnitude,
                                              boolean isRecording, boolean isSaving,
                                              boolean hasLastSample, long lastTimestampNs,
                                              long recordingStartedWallTimeMs) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            Notification notification = buildNotification(context, magnitude,
                    isRecording, isSaving, hasLastSample, lastTimestampNs, recordingStartedWallTimeMs);
            notifySafely(manager, NOTIFICATION_ID, notification);
        }
    }

    /**
     * 取消前台通知。
     */
    static void removeForegroundNotification(Context context) {
        // stopForeground 由 Service 自己调用，这里只取消通知
        cancelNotification(context, NOTIFICATION_ID);
    }

    /**
     * 取消所有 Service 通知。
     */
    static void cancelAllServiceNotifications(Context context) {
        cancelNotification(context, NOTIFICATION_ID);
        cancelNotification(context, COMPLETE_NOTIFICATION_ID);
    }

    /**
     * 取消指定通知。
     */
    static void cancelNotification(Context context, int notificationId) {
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(notificationId);
        }
    }

    /**
     * 发送录制完成通知。
     */
    static void postRecordingFinishedNotification(Context context, String fileName, boolean success) {
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

    private static void notifySafely(NotificationManager manager, int id, Notification notification) {
        try {
            manager.notify(id, notification);
        } catch (SecurityException e) {
            android.util.Log.w("NotificationHelper", "Notification permission denied", e);
        }
    }
}