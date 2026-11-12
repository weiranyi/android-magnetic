package io.github.weiranyi.magneticfield;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 录制文件管理器，负责：
 * 1. CSV 文件写入
 * 2. 录制目录管理
 * 3. 传感器时间戳格式化
 */
public class RecordingFileManager {

    static final String RECORD_DIR = "magnetic_records";

    private static final ThreadLocal<SimpleDateFormat> CSV_TIME_FORMAT = new ThreadLocal<SimpleDateFormat>() {
        @Override
        protected SimpleDateFormat initialValue() {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        }
    };

    /**
     * 将录制数据写入 CSV 文件。
     *
     * @param refWallTimeMs 录制开始时的 System.currentTimeMillis()（毫秒）
     * @param refElapsedNs  录制开始时的 SystemClock.elapsedRealtimeNanos()（纳秒）
     *                      这两个值构成固定参考，保证 CSV 中所有样本时间戳单调递增
     * @return true 表示写入成功
     */
    static boolean saveToCsv(List<MagneticSensorService.RecordingSample> data, File dir, String fileName,
                              long refWallTimeMs, long refElapsedNs,
                              String headerTime, String headerX, String headerY,
                              String headerZ, String headerTotal) {
        File file = new File(dir, fileName);
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write('\uFEFF');
            writer.write(headerTime + "," + headerX + "," + headerY + "," + headerZ + "," + headerTotal);
            writer.newLine();
            for (MagneticSensorService.RecordingSample sample : data) {
                writer.write(String.format(Locale.US, "'%s',%.3f,%.3f,%.3f,%.3f",
                        formatNanos(sample.timestampNs, refWallTimeMs, refElapsedNs),
                        sample.x, sample.y, sample.z, sample.total));
                writer.newLine();
            }
            return true;
        } catch (Exception e) {
            if (file.exists() && !file.delete()) {
                android.util.Log.w("RecordingFileManager", "Failed to delete incomplete CSV: " + file);
            }
            return false;
        }
    }

    /**
     * 获取录制文件存储目录。
     */
    static File getRecordDir(File externalFilesDir, File internalFilesDir) {
        File base = externalFilesDir != null ? externalFilesDir : internalFilesDir;
        if (base == null) {
            throw new IllegalStateException("No writable storage directory available");
        }
        File dir = new File(base, RECORD_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            android.util.Log.w("RecordingFileManager", "Failed to create record directory: " + dir);
        }
        return dir;
    }

    /**
     * 将传感器时间戳（elapsedRealtimeNanos）格式化为可读的 CSV 时间字符串。
     *
     * 使用录制开始时捕获的固定参考时间（refWallTimeMs, refElapsedNs），保证：
     *   output_ns = refWallTimeMs * 1_000_000 + (timestampNs - refElapsedNs)
     * 由于 timestampNs 单调递增，输出也单调递增，不受系统时钟校时影响。
     */
    static String formatNanos(long timestampNs, long refWallTimeMs, long refElapsedNs) {
        long deltaNs = timestampNs - refElapsedNs;
        long totalNs = refWallTimeMs * 1_000_000L + deltaNs;
        long totalMs = totalNs / 1_000_000L;
        String timePart = CSV_TIME_FORMAT.get().format(new Date(totalMs));
        long fractionNs = Math.floorMod(totalNs, 1_000_000_000L);
        int millis = (int) (fractionNs / 1_000_000L);
        int nanos = (int) (fractionNs % 1_000_000L);
        return String.format(Locale.US, "%s.%03d.%06d", timePart, millis, nanos);
    }
}
