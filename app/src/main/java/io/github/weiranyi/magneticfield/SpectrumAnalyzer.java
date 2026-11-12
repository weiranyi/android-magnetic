package io.github.weiranyi.magneticfield;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

/**
 * 频谱分析器，负责基于 FFT 的频谱峰值检测和采样率估算。
 * 纯计算逻辑，不依赖 Android 组件。
 */
public class SpectrumAnalyzer {

    static final int HISTORY_SIZE = 8;

    static final FastFourierTransformer FFT_TRANSFORMER =
            new FastFourierTransformer(DftNormalization.STANDARD);

    /**
     * 根据环形缓冲区快照计算频谱峰值。
     *
     * @param magnitudeSamples  磁场强度样本快照
     * @param timestampSamples  时间戳样本快照
     * @param snapshotIndex     当前写入索引
     * @param snapshotSize      缓冲区大小
     * @return 前 HISTORY_SIZE 个峰值，无峰值时返回 null
     */
    public static SpectrumPeak[] calculateSpectrumPeaks(
            float[] magnitudeSamples, long[] timestampSamples,
            int snapshotIndex, int snapshotSize) {

        if (snapshotSize < 2) {
            return null;
        }

        float[] ordered = new float[snapshotSize];
        long oldestTimestamp = 0L;
        long newestTimestamp = 0L;
        float mean = 0f;

        for (int i = 0; i < snapshotSize; i++) {
            int sourceIndex = (snapshotIndex + i) % snapshotSize;
            ordered[i] = magnitudeSamples[sourceIndex];
            mean += ordered[i];
            if (i == 0) {
                oldestTimestamp = timestampSamples[sourceIndex];
            } else if (i == snapshotSize - 1) {
                newestTimestamp = timestampSamples[sourceIndex];
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

        // FFT 输入长度必须是 2 的幂（Apache Commons Math 要求），不足时用 0 补齐
        int fftSize = 1;
        while (fftSize < snapshotSize) {
            fftSize <<= 1;
        }
        double[] real = new double[fftSize];
        double windowDenom = snapshotSize - 1;
        if (windowDenom < 1d) windowDenom = 1d;
        for (int i = 0; i < snapshotSize; i++) {
            double window = 0.5d - 0.5d * Math.cos((2d * Math.PI * i) / windowDenom);
            real[i] = (ordered[i] - mean) * window;
        }
        // 剩余的 [snapshotSize, fftSize) 保持 0，不需要显式赋值

        Complex[] spectrum = FFT_TRANSFORMER.transform(real, TransformType.FORWARD);

        int half = fftSize / 2;
        // 根据原始采样率换算频谱频率分辨率：sampleRate / fftSize
        // 通过把频谱能量按比例缩放，保持峰值搜索频率与原始 snapshotSize 一致
        final double sampleRateForBin = (double) sampleRate / (double) fftSize;
        float[] energies = new float[half];
        for (int bin = 0; bin < half; bin++) {
            double mag = spectrum[bin].abs();
            energies[bin] = (float) (mag * mag / snapshotSize);
        }

        for (int bin = 2; bin < half - 1; bin++) {
            if (energies[bin] > energies[bin - 1] && energies[bin] > energies[bin + 1]) {
                float frequency = (float) (bin * sampleRateForBin);
                insertPeak(peaks, new SpectrumPeak(frequency, energies[bin]));
            }
        }

        return peaks;
    }

    /**
     * 根据两个传感器时间戳和样本间隔数估算实际采样率。
     */
    public static float calculateSampleRate(long oldestTimestamp, long newestTimestamp, int intervals) {
        long durationNs = newestTimestamp - oldestTimestamp;
        if (durationNs <= 0L || intervals <= 0) {
            return 0f;
        }
        return intervals * 1_000_000_000f / durationNs;
    }

    private static void insertPeak(SpectrumPeak[] peaks, SpectrumPeak candidate) {
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

    /**
     * 频谱峰值数据类。
     */
    public static final class SpectrumPeak {
        public final float frequency;
        public final float energy;

        SpectrumPeak(float frequency, float energy) {
            this.frequency = frequency;
            this.energy = energy;
        }
    }
}