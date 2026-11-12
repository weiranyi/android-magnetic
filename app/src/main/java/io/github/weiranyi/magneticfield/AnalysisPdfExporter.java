package io.github.weiranyi.magneticfield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Android 原生 PDF 报告生成器。
 * 布局参考 fenxi.py 的 generate_pdf()，使用 android.graphics.pdf.PdfDocument + Canvas 绘制。
 *
 * 页面：A4 纵向（595×842 pt）
 */
public class AnalysisPdfExporter {

    // A4 尺寸（Android PdfDocument 单位是 pt，与 Canvas pixel 1:1）
    private static final int PAGE_WIDTH_PX  = 794;
    private static final int PAGE_HEIGHT_PX = 1123;
    private static final int MARGIN = 48;

    // 颜色
    private static final int COLOR_BG       = Color.WHITE;
    private static final int COLOR_HEADER   = Color.parseColor("#1A1A2E");
    private static final int COLOR_ACCENT   = Color.parseColor("#2563EB");
    private static final int COLOR_TEXT     = Color.parseColor("#1A1A1A");
    private static final int COLOR_SUBTEXT  = Color.parseColor("#666666");
    private static final int COLOR_GRID     = Color.parseColor("#E0E0E0");
    private static final int COLOR_CARD_BDR = Color.parseColor("#E8E8E8");
    private static final int COLOR_LINE     = Color.parseColor("#2287F2");
    // BUG1修复：半透明蓝使用 Color.argb，不能用 parseColor("#AARRGGBB") 的 8 位写法
    private static final int COLOR_FILL     = Color.argb(0x1A, 0x22, 0x87, 0xF2);

    // BUG3修复：查找并加载系统中文字体，避免 PDF 中文乱码
    private static Typeface sCjkTypeface;

    private static Typeface getCjkTypeface() {
        if (sCjkTypeface != null) return sCjkTypeface;
        String[] candidates = {
                "/system/fonts/NotoSansCJK-Regular.ttc",
                "/system/fonts/DroidSansFallback.ttf",
                "/system/fonts/NotoSansSC-Regular.otf",
                "/system/fonts/SourceHanSansCN-Regular.otf",
        };
        for (String path : candidates) {
            try {
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    sCjkTypeface = Typeface.createFromFile(f);
                    return sCjkTypeface;
                }
            } catch (Exception ignored) {}
        }
        // 找不到就用系统默认（可能乱码，但不会崩溃）
        sCjkTypeface = Typeface.DEFAULT;
        return sCjkTypeface;
    }

    /**
     * 生成 PDF 并返回文件。
     */
    public static File export(Context context, AnalysisUtils.AnalysisResult result, File srcFile)
            throws Exception {

        // BUG5修复：导出前清空旧的 PDF 缓存文件
        File cacheDir = context.getCacheDir();
        if (cacheDir == null) {
            cacheDir = context.getFilesDir();
        }
        File outDir = new File(cacheDir, "pdf_export");
        //noinspection ResultOfMethodCallIgnored
        outDir.mkdirs();
        File[] oldFiles = outDir.listFiles((d, name) -> name.endsWith(".pdf"));
        if (oldFiles != null) {
            for (File old : oldFiles) {
                //noinspection ResultOfMethodCallIgnored
                old.delete();
            }
        }

        // BUG6修复：文件名改为纯 ASCII，避免部分 ContentResolver 路径编码问题
        String dateStr = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File pdfFile = new File(outDir, "magnetic_analysis_" + dateStr + ".pdf");

        PdfDocument document = new PdfDocument();
        // BUG2修复：用 try-finally 保证 document 一定被关闭
        try {
            PdfDocument.PageInfo pageInfo = new PdfDocument.PageInfo.Builder(
                    PAGE_WIDTH_PX, PAGE_HEIGHT_PX, 1).create();
            PdfDocument.Page page = document.startPage(pageInfo);
            Canvas canvas = page.getCanvas();
            drawPage(canvas, result, srcFile, context);
            document.finishPage(page);

            try (FileOutputStream fos = new FileOutputStream(pdfFile)) {
                document.writeTo(fos);
            }
        } finally {
            document.close();
        }

        return pdfFile;
    }

    // ═══════════════════════════════════════════════════════════════
    //  页面绘制
    // ═══════════════════════════════════════════════════════════════

    private static void drawPage(Canvas canvas, AnalysisUtils.AnalysisResult result, File srcFile, Context context) {
        int w = PAGE_WIDTH_PX;
        int cw = w - MARGIN * 2;
        int y;

        // 白色背景
        Paint bg = new Paint();
        bg.setColor(COLOR_BG);
        canvas.drawRect(0, 0, w, PAGE_HEIGHT_PX, bg);

        // 顶部标题栏
        Paint headerBg = new Paint();
        headerBg.setColor(COLOR_HEADER);
        canvas.drawRect(0, 0, w, 70, headerBg);

        Paint titlePaint = boldPaint(22, Color.WHITE);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(context.getString(R.string.pdf_report_title), w / 2f, 40, titlePaint);

        // 副标题（根据系统语言本地化）
        Paint subtitlePaint = normalPaint(12, Color.parseColor("#AAAAAA"));
        subtitlePaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(context.getString(R.string.pdf_report_subtitle), w / 2f, 58, subtitlePaint);

        Paint subPaint = normalPaint(10, Color.parseColor("#AAAAAA"));
        subPaint.setTextAlign(Paint.Align.RIGHT);
        String dateStr = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
        canvas.drawText(context.getString(R.string.pdf_generated, dateStr), w - MARGIN, 68, subPaint);

        y = 86;

        // 文件信息卡片
        y = drawInfoCard(canvas, result, srcFile.getName(), MARGIN, y, cw, context) + 12;

        // 指标网格
        y = drawMetricGrid(canvas, result, MARGIN, y, cw, context) + 12;

        // 主频列表
        if (!result.peaks.isEmpty()) {
            y = drawPeaksSection(canvas, result, MARGIN, y, cw, context) + 12;
        }

        // 三张图表
        int remainY = PAGE_HEIGHT_PX - MARGIN - y;
        int chartH = Math.max(120, Math.min(175, (remainY - 24) / 3 - 12));
        y = drawChart(canvas,
                context.getString(R.string.pdf_chart_time_waveform),
                context.getString(R.string.pdf_axis_time),
                context.getString(R.string.pdf_axis_uT),
                result.timeX, result.timeY, result.timeCount,
                MARGIN, y, cw, chartH, false) + 10;
        y = drawChart(canvas,
                context.getString(R.string.pdf_chart_fft),
                context.getString(R.string.pdf_axis_frequency),
                context.getString(R.string.pdf_axis_amplitude),
                result.fftX, result.fftY, result.fftCount,
                MARGIN, y, cw, chartH, true) + 10;
        drawChart(canvas,
                context.getString(R.string.pdf_chart_welch),
                context.getString(R.string.pdf_axis_frequency),
                context.getString(R.string.pdf_axis_psd),
                result.welchX, result.welchY, result.welchCount,
                MARGIN, y, cw, chartH, true);
    }

    // ═══════════════════════════════════════════════════════════════
    //  各区块绘制
    // ═══════════════════════════════════════════════════════════════

    private static int drawInfoCard(Canvas canvas, AnalysisUtils.AnalysisResult r,
                                     String fileName, int x, int y, int w, Context context) {
        int cardH = 64;
        drawCard(canvas, x, y, w, cardH, 8);

        Paint namePaint = boldPaint(12, COLOR_TEXT);
        // 文件名截断：太长则省略
        String displayName = fileName.length() > 70 ? fileName.substring(0, 67) + "..." : fileName;
        canvas.drawText(displayName, x + 14, y + 20, namePaint);

        String spectrumLabel = AnalysisUtils.getSpectrumTypeLabel(context, r.spectrumType);
        String info = context.getString(R.string.pdf_file_info,
                r.n, r.duration, r.mean, r.std, spectrumLabel);
        Paint infoPaint = normalPaint(9, COLOR_SUBTEXT);
        canvas.drawText(info, x + 14, y + 45, infoPaint);
        return y + cardH;
    }

    private static int drawMetricGrid(Canvas canvas, AnalysisUtils.AnalysisResult r,
                                       int x, int y, int w, Context context) {
        String[][] metrics = {
                {context.getString(R.string.pdf_freq_range),   r.freqRange},
                {context.getString(R.string.pdf_sample_rate),  String.format(Locale.US, "%.0f Hz", r.fs)},
                {context.getString(R.string.pdf_energy_focus), r.energyRange},
                {context.getString(R.string.pdf_nyquist),      String.format(Locale.US, "%.1f Hz", r.nyquist)},
        };
        int cols = 2;
        int gap = 8;
        int cellW = (w - gap) / cols;
        int cellH = 60;
        for (int i = 0; i < 4; i++) {
            int col = i % cols;
            int row = i / cols;
            int cx = x + col * (cellW + gap);
            int cy = y + row * (cellH + gap);
            drawCard(canvas, cx, cy, cellW, cellH, 8);

            Paint labelPaint = normalPaint(8, COLOR_SUBTEXT);
            canvas.drawText(metrics[i][0], cx + 12, cy + 18, labelPaint);
            Paint valuePaint = boldPaint(15, COLOR_ACCENT);
            canvas.drawText(metrics[i][1], cx + 12, cy + 44, valuePaint);
        }
        return y + 2 * (cellH + gap) - gap;
    }

    private static int drawPeaksSection(Canvas canvas, AnalysisUtils.AnalysisResult r,
                                         int x, int y, int w, Context context) {
        int count = Math.min(r.peaks.size(), 3);
        int sectionH = 28 + count * 28 + 8;
        drawCard(canvas, x, y, w, sectionH, 8);

        Paint titlePaint = boldPaint(11, COLOR_TEXT);
        canvas.drawText(context.getString(R.string.pdf_main_frequencies), x + 14, y + 20, titlePaint);

        float maxAmp = r.peaks.get(0).amplitude;
        for (int i = 0; i < count; i++) {
            AnalysisUtils.Peak p = r.peaks.get(i);
            int ry = y + 28 + i * 28;

            // 序号圆
            Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            circlePaint.setColor(Color.parseColor("#E8F1FB"));
            canvas.drawCircle(x + 26, ry + 10, 11, circlePaint);
            Paint numPaint = boldPaint(9, COLOR_ACCENT);
            numPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(String.valueOf(i + 1), x + 26, ry + 14, numPaint);

            Paint freqPaint = boldPaint(13, COLOR_TEXT);
            canvas.drawText(String.format(Locale.US, "%.2f Hz", p.frequency), x + 46, ry + 14, freqPaint);

            int barX = x + 140;
            int barY = ry + 7;
            int barW = w - 140 - 80;
            int barH = 7;
            Paint barBg = new Paint(Paint.ANTI_ALIAS_FLAG);
            barBg.setColor(Color.parseColor("#F0F0F0"));
            canvas.drawRoundRect(new RectF(barX, barY, barX + barW, barY + barH), 3, 3, barBg);
            float ratio = maxAmp > 0 ? p.amplitude / maxAmp : 0f;
            Paint barFg = new Paint(Paint.ANTI_ALIAS_FLAG);
            barFg.setColor(COLOR_ACCENT);
            if (ratio > 0) {
                canvas.drawRoundRect(new RectF(barX, barY, barX + barW * ratio, barY + barH), 3, 3, barFg);
            }

            Paint ampPaint = normalPaint(10, COLOR_SUBTEXT);
            ampPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(String.format(Locale.US, "%.3f uT", p.amplitude), x + w - 12, ry + 14, ampPaint);
        }
        return y + sectionH;
    }

    private static int drawChart(Canvas canvas,
                                  String title, String xLabel, String yLabel,
                                  float[] xData, float[] yData, int count,
                                  int x, int y, int w, int h, boolean fill) {
        int cardH = h + 38;
        drawCard(canvas, x, y, w, cardH, 8);

        Paint titlePaint = boldPaint(10, COLOR_TEXT);
        canvas.drawText(title, x + 12, y + 15, titlePaint);

        if (count < 2) return y + cardH;

        int ml = 58, mr = 14, mt = 22, mb = 28;
        float left   = x + ml;
        float top    = y + mt;
        float right  = x + w - mr;
        float bottom = y + mt + h;

        // 数据范围
        float yMin = yData[0], yMax = yData[0];
        float xMin = xData[0], xMax = xData[0];
        for (int i = 1; i < count; i++) {
            if (yData[i] < yMin) yMin = yData[i];
            if (yData[i] > yMax) yMax = yData[i];
            if (xData[i] < xMin) xMin = xData[i];
            if (xData[i] > xMax) xMax = xData[i];
        }
        // 截断 DC 峰
        if (yMax > 0 && yMin >= 0 && yMax / Math.max(Math.abs(yMin), 1e-10f) > 20f) {
            float dcPeak = yMax;
            boolean past = false;
            float postMax = 0f;
            for (int i = 0; i < count; i++) {
                if (!past && i > 3 && yData[i] < dcPeak * 0.5f) past = true;
                if (past && yData[i] > postMax) postMax = yData[i];
            }
            if (postMax > 0) {
                yMax = postMax * 2.5f;
                yMin = 0f;
            }
        }
        float yPad = (yMax - yMin) * 0.06f;
        yMin -= yPad;
        yMax += yPad;
        if (yMax <= yMin) yMax = yMin + 1f;
        float xRange = xMax - xMin;
        float yRange = yMax - yMin;
        if (xRange <= 0) xRange = 1f;
        if (yRange <= 0) yRange = 1f;

        // 网格
        Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(COLOR_GRID);
        gridPaint.setStrokeWidth(0.8f);
        int yTicks = 4;
        for (int i = 0; i < yTicks; i++) {
            float frac = i / (float) (yTicks - 1);
            float gy = bottom - frac * (bottom - top);
            canvas.drawLine(left, gy, right, gy, gridPaint);
            float val = yMin + frac * yRange;
            Paint tickPaint = normalPaint(7, COLOR_SUBTEXT);
            tickPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(formatAxisVal(val), left - 4, gy + 3, tickPaint);
        }
        canvas.drawLine(left, bottom, right, bottom, gridPaint);
        int xTicks = 5;
        for (int i = 0; i < xTicks; i++) {
            float frac = i / (float) (xTicks - 1);
            float gx = left + frac * (right - left);
            float val = xMin + frac * xRange;
            Paint tickPaint = normalPaint(7, COLOR_SUBTEXT);
            if (i == 0) tickPaint.setTextAlign(Paint.Align.LEFT);
            else if (i == xTicks - 1) tickPaint.setTextAlign(Paint.Align.RIGHT);
            else tickPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(formatAxisVal(val), gx, bottom + 14, tickPaint);
        }
        Paint xUnitPaint = normalPaint(7, COLOR_SUBTEXT);
        xUnitPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(xLabel, right, bottom + 24, xUnitPaint);

        // Y 轴单位（旋转）
        canvas.save();
        float yCenterY = top + (bottom - top) / 2f;
        float unitX = x + 10;
        canvas.rotate(-90, unitX, yCenterY);
        Paint yUnitPaint = normalPaint(7, COLOR_SUBTEXT);
        yUnitPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(yLabel, unitX, yCenterY + 3, yUnitPaint);
        canvas.restore();

        // 折线
        Path linePath = new Path();
        boolean first = true;
        boolean prevClipped = false;
        for (int i = 0; i < count; i++) {
            float px = left + (xData[i] - xMin) / xRange * (right - left);
            float py = bottom - (yData[i] - yMin) / yRange * (bottom - top);
            boolean clippedTop    = py < top;
            boolean clippedBottom = py > bottom;
            if (clippedTop)    py = top;
            if (clippedBottom) py = bottom;
            if (first) {
                linePath.moveTo(px, py);
                first = false;
            } else if (prevClipped) {
                // 上一个点被裁剪（顶部或底部），当前点无论是否正常都断开路径，避免在边界处画多余线段
                linePath.moveTo(px, py);
            } else {
                linePath.lineTo(px, py);
            }
            prevClipped = clippedTop || clippedBottom;
        }

        if (fill) {
            Path fillPath = new Path(linePath);
            float lastX = left + (xData[count - 1] - xMin) / xRange * (right - left);
            float firstX = left + (xData[0] - xMin) / xRange * (right - left);
            fillPath.lineTo(lastX, bottom);
            fillPath.lineTo(firstX, bottom);
            fillPath.close();
            Paint fillPaintObj = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaintObj.setColor(COLOR_FILL);
            fillPaintObj.setStyle(Paint.Style.FILL);
            canvas.drawPath(fillPath, fillPaintObj);
        }

        Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        linePaint.setColor(COLOR_LINE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(1.6f);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        canvas.drawPath(linePath, linePaint);

        return y + cardH;
    }

    // ═══════════════════════════════════════════════════════════════
    //  通用辅助
    // ═══════════════════════════════════════════════════════════════

    private static void drawCard(Canvas canvas, int x, int y, int w, int h, int r) {
        RectF rect = new RectF(x, y, x + w, y + h);
        Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
        shadow.setColor(Color.argb(18, 0, 0, 0));
        canvas.drawRoundRect(new RectF(x + 1, y + 1, x + w + 1, y + h + 1), r, r, shadow);
        Paint cardBg = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBg.setColor(COLOR_BG);
        canvas.drawRoundRect(rect, r, r, cardBg);
        Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        border.setColor(COLOR_CARD_BDR);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(0.8f);
        canvas.drawRoundRect(rect, r, r, border);
    }

    private static Paint normalPaint(int sp, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        p.setTextSize(sp);
        p.setTypeface(getCjkTypeface());  // 使用 CJK 字体，防止 PDF 中文方块
        return p;
    }

    private static Paint boldPaint(int sp, int color) {
        Paint p = normalPaint(sp, color);
        p.setFakeBoldText(true);  // CJK 字体无原生粗体，用 fake bold 代替
        return p;
    }

    private static String formatAxisVal(float val) {
        float abs = Math.abs(val);
        if (abs == 0f) return "0";
        if (abs >= 1000f) return String.format(Locale.US, "%.0f", val);
        if (abs >= 100f)  return String.format(Locale.US, "%.1f", val);
        if (abs >= 10f)   return String.format(Locale.US, "%.1f", val);
        if (abs >= 1f)    return String.format(Locale.US, "%.2f", val);
        if (abs >= 0.01f) return String.format(Locale.US, "%.2f", val);
        return String.format(Locale.US, "%.1e", val);
    }
}
