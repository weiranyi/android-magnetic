package io.github.weiranyi.magneticfield;

import android.hardware.SensorManager;

import java.util.Arrays;

/**
 * 传感器采样率控制器，负责：
 * 1. 硬件采样周期/批处理延迟计算
 * 2. 软件时间闸限流（确保目标Hz稳定）
 * 3. 实际采样率统计
 */
public class SensorRateController {

    public static final int SAMPLE_RATE_FASTEST = -1;
    static final int ACTUAL_SAMPLE_RATE_WINDOW_SIZE = 64;

    static final Object ACTUAL_SAMPLE_RATE_LOCK = new Object();
    static final long[] actualSampleRateTimestampsNs =
            new long[ACTUAL_SAMPLE_RATE_WINDOW_SIZE];
    static int actualSampleRateIndex;
    static int actualSampleRateCount;
    static volatile float actualSampleRateHz;

    static volatile long targetRateMinIntervalNs;
    static volatile long targetRateLastAcceptedNs;

    /**
     * 根据目标采样率和当前录制状态计算硬件采样周期（微秒）。
     */
    static int getSamplingPeriodUs(int targetSampleRateHz, boolean recording) {
        if (recording || targetSampleRateHz == SAMPLE_RATE_FASTEST) {
            return SensorManager.SENSOR_DELAY_FASTEST;
        }
        if (targetSampleRateHz <= 0) {
            return SensorManager.SENSOR_DELAY_NORMAL;
        }
        return Math.max(1, 1_000_000 / targetSampleRateHz);
    }

    /**
     * 根据目标采样率计算批处理报告延迟（微秒）。
     */
    static int getBatchReportLatencyUs(int targetSampleRateHz, boolean recording) {
        if (recording || targetSampleRateHz == SAMPLE_RATE_FASTEST || targetSampleRateHz <= 0) {
            return 0;
        }
        int periodUs = Math.max(1, 1_000_000 / targetSampleRateHz);
        return Math.max(periodUs, Math.min(2 * periodUs, 20_000));
    }

    /**
     * 更新实际采样率滑动窗口统计。
     */
    static void updateActualSampleRate(long timestampNs) {
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

    /**
     * 重置实际采样率统计。
     */
    static void resetActualSampleRate() {
        synchronized (ACTUAL_SAMPLE_RATE_LOCK) {
            Arrays.fill(actualSampleRateTimestampsNs, 0L);
            actualSampleRateIndex = 0;
            actualSampleRateCount = 0;
            actualSampleRateHz = 0f;
        }
    }

    /**
     * 判断传感器事件是否应被接受（基于目标Hz的时间闸）。
     */
    static boolean shouldAcceptSampleForTargetRate(long timestampNs, int targetSampleRateHz) {
        if (targetSampleRateHz == SAMPLE_RATE_FASTEST || targetSampleRateHz <= 0 || timestampNs <= 0L) {
            return true;
        }
        long minIntervalNs = targetRateMinIntervalNs;
        if (minIntervalNs <= 0L) {
            minIntervalNs = 1_000_000_000L / (long) targetSampleRateHz;
            targetRateMinIntervalNs = minIntervalNs;
            targetRateLastAcceptedNs = timestampNs;
            return true;
        }
        long lastAccepted = targetRateLastAcceptedNs;
        if (lastAccepted <= 0L) {
            targetRateLastAcceptedNs = timestampNs;
            return true;
        }
        if (timestampNs - lastAccepted >= minIntervalNs) {
            long nextAccepted = lastAccepted + minIntervalNs;
            if (nextAccepted < timestampNs - minIntervalNs) {
                targetRateLastAcceptedNs = timestampNs;
            } else {
                targetRateLastAcceptedNs = nextAccepted;
            }
            return true;
        }
        return false;
    }

    /**
     * 重置目标采样率限流器状态。
     */
    static void resetTargetRateLimiter(int targetSampleRateHz) {
        if (targetSampleRateHz == SAMPLE_RATE_FASTEST || targetSampleRateHz <= 0) {
            targetRateMinIntervalNs = 0L;
        } else {
            targetRateMinIntervalNs = 1_000_000_000L / (long) targetSampleRateHz;
        }
        targetRateLastAcceptedNs = 0L;
    }
}