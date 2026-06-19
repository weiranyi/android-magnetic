package com.example.magneticfield;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * 磁场 CSV 数据分析工具，参考 fenxi.py 的核心算法实现。
 */
public class AnalysisUtils {

    private static final FastFourierTransformer FFT_TRANSFORMER =
            new FastFourierTransformer(DftNormalization.STANDARD);

    public static class Peak {
        public final float frequency;
        public final float amplitude;

        public Peak(float frequency, float amplitude) {
            this.frequency = frequency;
            this.amplitude = amplitude;
        }
    }

    public static class AnalysisResult {
        public int n;
        public float duration;
        public float dtMs;
        public float fs;
        public float nyquist;
        public float mean;
        public float std;
        public String freqRange = "";
        public String energyRange = "";
        public final List<Peak> peaks = new ArrayList<>();
        public String spectrumType = "";

        // 图表数据（已采样）
        public float[] timeX;
        public float[] timeY;
        public int timeCount;
        public float[] fftX;
        public float[] fftY;
        public int fftCount;
        public float[] welchX;
        public float[] welchY;
        public int welchCount;
    }

    /**
     * 将 spectrumType 的 key 转换为当前语言的显示文本。
     */
    public static String getSpectrumTypeLabel(Context context, String typeKey) {
        if (context == null || typeKey == null) return "";
        switch (typeKey) {
            case "spectrum_ultra_low":
                return context.getString(R.string.spectrum_ultra_low);
            case "spectrum_low":
                return context.getString(R.string.spectrum_low);
            case "spectrum_low_dominant":
                return context.getString(R.string.spectrum_low_dominant);
            case "spectrum_broadband":
                return context.getString(R.string.spectrum_broadband);
            case "spectrum_unknown":
                return context.getString(R.string.spectrum_unknown);
            default:
                return typeKey;
        }
    }

    public static AnalysisResult analyzeFile(File file) throws Exception {
        CsvData data = readCsv(file);
        if (data.times.length < 10) {
            throw new Exception("有效数据点不足");
        }
        return analyze(data.times, data.magnitudes);
    }

    private static class CsvData {
        final float[] times;
        final float[] magnitudes;

        CsvData(float[] times, float[] magnitudes) {
            this.times = times;
            this.magnitudes = magnitudes;
        }
    }

    private static CsvData readCsv(File file) throws Exception {
        List<Float> times = new ArrayList<>();
        List<Float> mags = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null) {
                throw new Exception("空文件");
            }
            // 去除 BOM
            if (line.length() > 0 && line.charAt(0) == '\uFEFF') {
                line = line.substring(1);
            }
            String[] header = splitCsv(line);
            int timeCol = findColumn(header, "时间", "time", "timestamp");
            int magCol = findColumn(header, "总量", "total", "magnitude");
            if (timeCol < 0) timeCol = 0;
            if (magCol < 0) magCol = header.length - 1;

            float firstTime = Float.NaN;
            while ((line = reader.readLine()) != null) {
                String[] parts = splitCsv(line);
                if (parts.length <= Math.max(timeCol, magCol)) {
                    continue;
                }
                try {
                    float t = parseTime(parts[timeCol]);
                    float m = Float.parseFloat(parts[magCol].trim());
                    if (Float.isNaN(firstTime)) {
                        firstTime = t;
                    }
                    times.add(t - firstTime);
                    mags.add(m);
                } catch (Exception ignored) {
                }
            }
        }

        float[] tArr = new float[times.size()];
        float[] mArr = new float[mags.size()];
        for (int i = 0; i < times.size(); i++) {
            tArr[i] = times.get(i);
            mArr[i] = mags.get(i);
        }
        return new CsvData(tArr, mArr);
    }

    private static String[] splitCsv(String line) {
        List<String> result = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == ',' && !inQuote) {
                result.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString().trim());
        return result.toArray(new String[0]);
    }

    private static int findColumn(String[] header, String... keywords) {
        for (int i = 0; i < header.length; i++) {
            String h = header[i].toLowerCase(Locale.getDefault());
            for (String kw : keywords) {
                if (h.contains(kw.toLowerCase(Locale.getDefault()))) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * 解析时间字符串为秒数。
     * 支持 HH:MM:SS.mmm.mmm.mmm（CSV 中秒后的 .mmm 为毫秒/微秒/纳秒）或纯数字。
     */
    private static float parseTime(String s) {
        s = s.trim().replace("'", "");
        if (s.contains(":")) {
            String[] parts = s.split(":");
            if (parts.length >= 3) {
                int h = Integer.parseInt(parts[0]);
                int m = Integer.parseInt(parts[1]);
                // parts[2] 形如 "56.123.456.789"，分别对应秒、毫秒、微秒、纳秒
                String[] secParts = parts[2].split("\\.");
                int sec = Integer.parseInt(secParts[0]);
                double fraction = 0.0;
                if (secParts.length > 1) fraction += Integer.parseInt(secParts[1]) / 1000.0;
                if (secParts.length > 2) fraction += Integer.parseInt(secParts[2]) / 1_000_000.0;
                if (secParts.length > 3) fraction += Integer.parseInt(secParts[3]) / 1_000_000_000.0;
                return h * 3600f + m * 60f + sec + (float) fraction;
            }
        }
        return Float.parseFloat(s);
    }

    private static AnalysisResult analyze(float[] times, float[] magnitudes) {
        int n = magnitudes.length;

        // 采样间隔与采样率
        float dt = meanDiff(times);
        if (dt <= 0) dt = (times[n - 1] - times[0]) / Math.max(1, n - 1);
        float fs = 1.0f / dt;

        // 去均值
        double mean = 0;
        for (float v : magnitudes) mean += v;
        mean /= n;
        double[] detrended = new double[n];
        for (int i = 0; i < n; i++) detrended[i] = magnitudes[i] - mean;

        // Hanning 窗
        double[] window = new double[n];
        double winSum = 0;
        for (int i = 0; i < n; i++) {
            window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (n - 1));
            winSum += window[i];
        }
        double winGain = winSum / n;

        // FFT（长度补到 2 的幂）
        int fftN = nextPowerOfTwo(n);
        double[] real = new double[fftN];
        for (int i = 0; i < n; i++) {
            real[i] = detrended[i] * window[i];
        }
        Complex[] spectrum = FFT_TRANSFORMER.transform(real, TransformType.FORWARD);
        int half = fftN / 2;
        double[] amplitudes = new double[half];
        double df = fs / fftN;
        for (int i = 0; i < half; i++) {
            amplitudes[i] = spectrum[i].abs() / fftN * 2.0 / winGain;
        }

        // Welch PSD
        int nperseg = Math.min(1024, Math.max(64, n / 4));
        nperseg = nextPowerOfTwo(nperseg);
        if (nperseg > n) nperseg = nextPowerOfTwo(n / 2);
        int noverlap = nperseg / 2;
        WelchResult welch = welch(detrended, fs, nperseg, noverlap);

        // 有意义频率范围
        double noiseFloor = median(Arrays.copyOfRange(amplitudes, Math.min(half - 1, Math.max(1, (int) (5.0 / df))), half));
        double threshold = Math.max(noiseFloor * 3, max(amplitudes) * 0.02);
        int sigStart = Math.max(1, (int) (0.05 / df));
        int firstSig = -1, lastSig = -1;
        for (int i = sigStart; i < half; i++) {
            if (amplitudes[i] > threshold) {
                if (firstSig < 0) firstSig = i;
                lastSig = i;
            }
        }
        float freqLo, freqHi;
        if (firstSig >= 0) {
            freqLo = Math.max(0.05f, round((float) (firstSig * df), 1));
            freqHi = round((float) (lastSig * df), 1);
        } else {
            freqLo = 0.1f;
            freqHi = Math.min(fs / 2, 50f);
        }

        // 80% 能量集中
        double totalE = sum(welch.psd);
        double cum10 = totalE * 0.10;
        double cum90 = totalE * 0.90;
        int loIdx = searchSortedCumulative(welch.psd, cum10);
        int hiIdx = searchSortedCumulative(welch.psd, cum90);
        float eLo = round(welch.frequencies[Math.max(0, loIdx)], 2);
        float eHi = round(welch.frequencies[Math.min(welch.psd.length - 1, hiIdx)], 2);

        // 主频率峰值
        List<Peak> peaks = findPeaks(amplitudes, df, sigStart, threshold);

        // 频谱类型
        double lowE = 0;
        for (int i = 0; i < welch.frequencies.length; i++) {
            if (welch.frequencies[i] <= 1.0) lowE += welch.psd[i];
        }
        String stype;
        if (totalE > 0) {
            double ratio = lowE / totalE;
            if (ratio > 0.9) stype = "spectrum_ultra_low";
            else if (ratio > 0.7) stype = "spectrum_low";
            else if (ratio > 0.5) stype = "spectrum_low_dominant";
            else stype = "spectrum_broadband";
        } else {
            stype = "spectrum_unknown";
        }

        AnalysisResult result = new AnalysisResult();
        result.n = n;
        result.duration = round(times[n - 1], 2);
        result.dtMs = round(dt * 1000, 2);
        result.fs = round(fs, 0);
        result.nyquist = round(fs / 2, 1);
        result.mean = round((float) mean, 2);
        result.std = round(std(magnitudes, mean), 2);
        result.freqRange = String.format(Locale.US, "%.1fHz - %.1fHz", freqLo, freqHi);
        result.energyRange = String.format(Locale.US, "%.2fHz - %.2fHz", eLo, eHi);
        result.peaks.addAll(peaks);
        result.spectrumType = stype;

        // 图表数据采样
        result.timeX = sample(times, 300);
        result.timeY = sample(magnitudes, 300);
        result.timeCount = result.timeX.length;

        float[] fftF = new float[half];
        float[] fftA = new float[half];
        for (int i = 0; i < half; i++) {
            fftF[i] = (float) (i * df);
            fftA[i] = (float) amplitudes[i];
        }
        int fftLim = Math.max(1, searchSorted(fftF, 15f));
        result.fftX = sample(fftF, fftLim, 200);
        result.fftY = sample(fftA, fftLim, 200);
        result.fftCount = result.fftX.length;

        int welchLim = Math.max(1, searchSorted(welch.frequencies, 15f));
        result.welchX = sample(welch.frequencies, welchLim, 200);
        result.welchY = sample(welch.psd, welchLim, 200);
        result.welchCount = result.welchX.length;

        return result;
    }

    private static class WelchResult {
        final float[] frequencies;
        final float[] psd;

        WelchResult(float[] frequencies, float[] psd) {
            this.frequencies = frequencies;
            this.psd = psd;
        }
    }

    private static WelchResult welch(double[] signal, float fs, int nperseg, int noverlap) {
        int step = nperseg - noverlap;
        int n = signal.length;
        int segments = 0;
        double[] psdSum = new double[nperseg / 2 + 1];

        // Hanning 窗
        double[] win = new double[nperseg];
        double winSum = 0;
        for (int i = 0; i < nperseg; i++) {
            win[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (nperseg - 1));
            winSum += win[i] * win[i];
        }
        double scale = winSum * fs;

        for (int start = 0; start + nperseg <= n; start += step) {
            double[] seg = new double[nperseg];
            double segMean = 0;
            for (int i = 0; i < nperseg; i++) {
                segMean += signal[start + i];
            }
            segMean /= nperseg;
            for (int i = 0; i < nperseg; i++) {
                seg[i] = (signal[start + i] - segMean) * win[i];
            }
            Complex[] sp = FFT_TRANSFORMER.transform(seg, TransformType.FORWARD);
            int half = nperseg / 2 + 1;
            for (int i = 0; i < half; i++) {
                double mag = sp[i].abs();
                psdSum[i] += (mag * mag) / scale;
            }
            segments++;
        }

        if (segments == 0) {
            segments = 1;
        }
        int half = nperseg / 2 + 1;
        float[] freqs = new float[half];
        float[] psd = new float[half];
        for (int i = 0; i < half; i++) {
            freqs[i] = i * fs / nperseg;
            psd[i] = (float) (psdSum[i] / segments);
        }
        return new WelchResult(freqs, psd);
    }

    private static List<Peak> findPeaks(double[] amplitudes, double df, int start, double threshold) {
        List<Peak> peaks = new ArrayList<>();
        int minDistance = Math.max(3, (int) (0.1 / df));
        int i = start;
        while (i < amplitudes.length - 1) {
            if (amplitudes[i] > threshold && amplitudes[i] > amplitudes[i - 1] && amplitudes[i] > amplitudes[i + 1]) {
                peaks.add(new Peak((float) (i * df), (float) amplitudes[i]));
                i += minDistance;
            } else {
                i++;
            }
        }
        // 按幅度降序取前 5
        peaks.sort((a, b) -> Float.compare(b.amplitude, a.amplitude));
        return peaks.size() > 5 ? peaks.subList(0, 5) : peaks;
    }

    private static float meanDiff(float[] arr) {
        if (arr.length < 2) return 0;
        double sum = 0;
        for (int i = 1; i < arr.length; i++) {
            sum += arr[i] - arr[i - 1];
        }
        return (float) (sum / (arr.length - 1));
    }

    private static double median(double[] arr) {
        if (arr.length == 0) return 0;
        double[] copy = arr.clone();
        Arrays.sort(copy);
        int mid = copy.length / 2;
        if (copy.length % 2 == 0) {
            return (copy[mid - 1] + copy[mid]) / 2.0;
        }
        return copy[mid];
    }

    private static double max(double[] arr) {
        double m = arr[0];
        for (double v : arr) if (v > m) m = v;
        return m;
    }

    private static double sum(double[] arr) {
        double s = 0;
        for (double v : arr) s += v;
        return s;
    }

    private static double sum(float[] arr) {
        double s = 0;
        for (float v : arr) s += v;
        return s;
    }

    private static float std(float[] arr, double mean) {
        if (arr.length < 2) return 0;
        double s = 0;
        for (float v : arr) {
            double d = v - mean;
            s += d * d;
        }
        return (float) Math.sqrt(s / arr.length);
    }

    private static int searchSorted(float[] arr, float key) {
        int lo = 0, hi = arr.length;
        while (lo < hi) {
            int mid = (lo + hi) / 2;
            if (arr[mid] < key) lo = mid + 1;
            else hi = mid;
        }
        return lo;
    }

    private static int searchSortedCumulative(double[] arr, double key) {
        double sum = 0;
        for (int i = 0; i < arr.length; i++) {
            sum += arr[i];
            if (sum >= key) return i;
        }
        return arr.length - 1;
    }

    private static int searchSortedCumulative(float[] arr, double key) {
        double sum = 0;
        for (int i = 0; i < arr.length; i++) {
            sum += arr[i];
            if (sum >= key) return i;
        }
        return arr.length - 1;
    }

    private static float[] sample(float[] arr, int maxPts) {
        if (arr.length <= maxPts) return arr.clone();
        float[] out = new float[maxPts];
        for (int i = 0; i < maxPts; i++) {
            int idx = (int) Math.round(i * (arr.length - 1) / (double) (maxPts - 1));
            out[i] = arr[idx];
        }
        return out;
    }

    private static float[] sample(float[] arr, int limit, int maxPts) {
        if (limit <= 0) limit = arr.length;
        if (limit >= arr.length) return sample(arr, maxPts);
        float[] sub = new float[limit];
        System.arraycopy(arr, 0, sub, 0, limit);
        return sample(sub, maxPts);
    }

    private static int nextPowerOfTwo(int n) {
        int p = 1;
        while (p < n) p <<= 1;
        return p;
    }

    private static float round(float value, int decimals) {
        float factor = (float) Math.pow(10, decimals);
        return Math.round(value * factor) / factor;
    }
}
