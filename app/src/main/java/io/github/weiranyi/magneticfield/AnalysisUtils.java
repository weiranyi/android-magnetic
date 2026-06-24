package io.github.weiranyi.magneticfield;

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
            if (magCol < 0) magCol = Math.max(0, header.length - 1);

            double firstTime = Double.NaN;
            while ((line = reader.readLine()) != null) {
                String[] parts = splitCsv(line);
                if (parts.length <= Math.max(timeCol, magCol)) {
                    continue;
                }
                try {
                    double t = parseTime(parts[timeCol]);
                    float m = Float.parseFloat(parts[magCol].trim());
                    if (Double.isNaN(firstTime)) {
                        firstTime = t;
                    }
                    times.add((float) (t - firstTime));
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
     * 支持 yyyy-MM-dd HH:mm:ss.mmm.nnnnnn（日期+时间+毫秒+纳秒6位）
     * 以及旧格式 HH:mm:ss.mmm.uuu.nnn，或纯数字。
     */
    private static double parseTime(String s) {
        s = s.trim().replace("'", "");
        try {
            // 新格式：yyyy-MM-dd HH:mm:ss.mmm.nnnnnn
            // 先按空格分割日期和时间部分
            String datePart = null;
            String timePart = s;
            if (s.contains(" ") && s.contains("-")) {
                int firstSpace = s.indexOf(' ');
                datePart = s.substring(0, firstSpace).trim();
                timePart = s.substring(firstSpace + 1).trim();
            }

            // 解析日期部分（可选）
            long daySeconds = 0L;
            if (datePart != null && datePart.contains("-")) {
                String[] dateParts = datePart.split("-");
                if (dateParts.length >= 3) {
                    int y = Integer.parseInt(dateParts[0].trim());
                    int mo = Integer.parseInt(dateParts[1].trim());
                    int d = Integer.parseInt(dateParts[2].trim());
                    // 将日期转为自某基准日起的天数 * 86400
                    // 使用简化公式计算 yyyy-MM-dd 的儒略日
                    long a = (14L - mo) / 12L;
                    long y2 = y + 4800L - a;
                    long m2 = mo + 12L * a - 3L;
                    long jdn = d + (153L * m2 + 2L) / 5L + 365L * y2 + y2 / 4L - y2 / 100L + y2 / 400L - 32045L;
                    daySeconds = jdn * 86400L;
                }
            }

            // 解析时间部分 HH:mm:ss.mmm[.nnnnnn] 或 HH:mm:ss.mmm.uuu.nnn
            if (timePart.contains(":")) {
                String[] parts = timePart.split(":");
                if (parts.length >= 3) {
                    int h = Integer.parseInt(parts[0].trim());
                    int m = Integer.parseInt(parts[1].trim());
                    String secPart = parts[2].trim();
                    if (secPart.isEmpty()) {
                        return daySeconds + h * 3600.0 + m * 60.0;
                    }
                    String[] secParts = secPart.split("\\.");
                    if (secParts.length == 0 || secParts[0].trim().isEmpty()) {
                        return daySeconds + h * 3600.0 + m * 60.0;
                    }
                    int sec = Integer.parseInt(secParts[0].trim());
                    double fraction = 0.0;
                    if (secParts.length > 1 && !secParts[1].trim().isEmpty()) {
                        fraction += Integer.parseInt(secParts[1].trim()) / 1000.0;
                    }
                    if (secParts.length > 2 && !secParts[2].trim().isEmpty()) {
                        String fracStr = secParts[2].trim();
                        if (fracStr.length() >= 6) {
                            // 新格式：6位亚毫秒纳秒 (0-999999)，需除以 1e9
                            fraction += Integer.parseInt(fracStr) / 1_000_000_000.0;
                        } else {
                            // 旧格式：3位微秒 (0-999)，需除以 1e6
                            fraction += Integer.parseInt(fracStr) / 1_000_000.0;
                        }
                    }
                    if (secParts.length > 3 && !secParts[3].trim().isEmpty()) {
                        // 旧格式的纳秒部分
                        fraction += Integer.parseInt(secParts[3].trim()) / 1_000_000_000.0;
                    }
                    return daySeconds + h * 3600.0 + m * 60.0 + sec + fraction;
                }
            }
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static AnalysisResult analyze(float[] times, float[] magnitudes) {
        int n = magnitudes.length;

        // 按时间排序：确保 times 严格递增，避免绘图时线条"回头"
        final float[] origTimes = times;
        Integer[] indices = new Integer[n];
        for (int i = 0; i < n; i++) indices[i] = i;
        java.util.Arrays.sort(indices, (a, b) -> {
            float diff = origTimes[a] - origTimes[b];
            if (diff != 0) return diff > 0 ? 1 : -1;
            return 0;
        });
        float[] sortedTimes = new float[n];
        float[] sortedMags = new float[n];
        for (int i = 0; i < n; i++) {
            sortedTimes[i] = origTimes[indices[i]];
            sortedMags[i] = magnitudes[indices[i]];
        }
        // 去重：相同时间戳只保留第一个
        int write = 0;
        for (int read = 0; read < n; read++) {
            if (write == 0 || sortedTimes[read] > sortedTimes[write - 1]) {
                sortedTimes[write] = sortedTimes[read];
                sortedMags[write] = sortedMags[read];
                write++;
            }
        }
        if (write < n) {
            times = java.util.Arrays.copyOf(sortedTimes, write);
            magnitudes = java.util.Arrays.copyOf(sortedMags, write);
            n = write;
        } else {
            times = sortedTimes;
            magnitudes = sortedMags;
        }
        // 去重后数据点不足，无法分析
        if (n < 2) {
            throw new IllegalArgumentException("有效数据点不足（去重后）");
        }

        // 采样间隔与采样率：确保 fs 始终为正
        float dt = meanDiff(times);
        if (dt <= 0) {
            float totalSpan = times[n - 1] - times[0];
            if (totalSpan > 0) {
                dt = totalSpan / Math.max(1, n - 1);
            } else {
                // 时间信息不可用，退回到按样本序号计算（1样本=1秒为下限）
                dt = 1.0f;
            }
        }
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

        // 50% 能量集中（25%-75% 四分位距），避免 DC 峰主导导致范围过宽
        double totalE = sum(welch.psd);
        double cum25 = totalE * 0.25;
        double cum75 = totalE * 0.75;
        int loIdx = searchSortedCumulative(welch.psd, cum25);
        int hiIdx = searchSortedCumulative(welch.psd, cum75);
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
        if (amplitudes == null || amplitudes.length < 3) {
            return new java.util.ArrayList<>();
        }
        if (start < 1) start = 1;
        if (start >= amplitudes.length - 1) start = amplitudes.length - 2;
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
        if (arr == null || arr.length == 0) {
            throw new IllegalArgumentException("max() requires non-empty array");
        }
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
