package io.github.weiranyi.magneticfield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * 通用折线图控件，用于展示分析结果（时域波形、FFT、Welch PSD）。
 * v2: 修复 Y 轴标签堆叠 & FFT/Welch 曲线断开问题
 * v3: 支持夜间模式（颜色从 colors.xml 读取）
 */
public class AnalysisChartView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unitPaint = new Paint(Paint.ANTI_ALIAS_FLAG);  // 专用单位文字画笔
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private final Path fillPath = new Path();

    private float[] xValues = new float[0];
    private float[] yValues = new float[0];
    private int pointCount;

    private String chartTitle = "";
    private String xAxisLabel = "";
    private String yAxisLabel = "";
    private float fixedXMin = Float.NaN;
    private float fixedXMax = Float.NaN;
    private float fixedYMin = Float.NaN;   // 可指定Y轴下限
    private float fixedYMax = Float.NaN;   // 可指定Y轴上限
    private boolean fillArea = false;
    private int lineColor = 0xFF2287F2;  // fallback，构造函数会覆盖

    private float xMin;
    private float xMax;
    private float yMin;  // 实际绘制用的边界
    private float yMax;

    // 图表边距（dp）
    private final float MARGIN_LEFT_DP = 50f;
    private final float MARGIN_RIGHT_DP = 12f;
    private final float MARGIN_TOP_DP = 34f;
    private final float MARGIN_BOTTOM_DP = 36f;

    public AnalysisChartView(Context context) {
        this(context, null);
    }

    public AnalysisChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        int gridColor  = ContextCompat.getColor(context, R.color.chart_grid);
        int textColor  = ContextCompat.getColor(context, R.color.text_secondary);
        int unitColor  = ContextCompat.getColor(context, R.color.text_secondary);
        int titleColor = ContextCompat.getColor(context, R.color.text_primary);
        lineColor = ContextCompat.getColor(context, R.color.primary);

        gridPaint.setColor(gridColor);
        gridPaint.setStrokeWidth(dp(0.8f));

        textPaint.setColor(textColor);
        textPaint.setTextSize(dp(9));
        textPaint.setAntiAlias(true);

        unitPaint.setColor(unitColor);
        unitPaint.setTextSize(dp(8));
        unitPaint.setAntiAlias(true);

        titlePaint.setColor(titleColor);
        titlePaint.setTextSize(dp(13));
        titlePaint.setAntiAlias(true);
        titlePaint.setFakeBoldText(true);

        linePaint.setColor(lineColor);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(1.6f));
        linePaint.setAntiAlias(true);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);

        fillPaint.setColor(lineColor & 0x18FFFFFF);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setAntiAlias(true);
    }

    public void setData(float[] x, float[] y, int count) {
        this.xValues = x != null ? x.clone() : new float[0];
        this.yValues = y != null ? y.clone() : new float[0];
        this.pointCount = Math.min(count, Math.min(this.xValues.length, this.yValues.length));
        computeBounds();
        invalidate();
    }

    public void setTitle(String title) {
        this.chartTitle = title;
        invalidate();
    }

    public void setAxisLabels(String xLabel, String yLabel) {
        this.xAxisLabel = xLabel;
        this.yAxisLabel = yLabel;
        invalidate();
    }

    public void setFixedXRange(float min, float max) {
        this.fixedXMin = min;
        this.fixedXMax = max;
        computeBounds();
        invalidate();
    }

    /**
     * 设置 Y 轴固定范围。
     * 用于 FFT/Welch 图表防止极值导致曲线"断开"不可见。
     */
    public void setFixedYRange(float min, float max) {
        this.fixedYMin = min;
        this.fixedYMax = max;
        computeBounds();
        invalidate();
    }

    public void setFillArea(boolean fill) {
        this.fillArea = fill;
        invalidate();
    }

    public void setLineColor(int color) {
        this.lineColor = color;
        linePaint.setColor(color);
        fillPaint.setColor(color & 0x18FFFFFF);
        invalidate();
    }

    /**
     * 计算数据边界。
     * 关键改进：
     * - 支持通过 fixedYMin/fixedYMax 截断 Y 轴
     * - 当 Y 轴跨度极大时（FFT/Welch），自动将 Y 范围截断到合理区间，
     *   让低频极值之外的数据也能清晰显示为连续曲线
     */
    private void computeBounds() {
        if (pointCount == 0) {
            xMin = yMin = 0f;
            xMax = yMax = 1f;
            return;
        }
        // X 轴
        xMin = Float.isNaN(fixedXMin) ? xValues[0] : fixedXMin;
        xMax = Float.isNaN(fixedXMax) ? xValues[0] : fixedXMax;
        if (Float.isNaN(fixedXMin) || Float.isNaN(fixedXMax)) {
            for (int i = 0; i < pointCount; i++) {
                if (Float.isNaN(fixedXMin)) xMin = Math.min(xMin, xValues[i]);
                if (Float.isNaN(fixedXMax)) xMax = Math.max(xMax, xValues[i]);
            }
        }

        // Y 轴原始数据范围
        float dataYMin = yValues[0];
        float dataYMax = yValues[0];
        for (int i = 1; i < pointCount; i++) {
            dataYMin = Math.min(dataYMin, yValues[i]);
            dataYMax = Math.max(dataYMax, yValues[i]);
        }

        // 应用固定范围（如果有）
        yMin = Float.isNaN(fixedYMin) ? dataYMin : fixedYMin;
        yMax = Float.isNaN(fixedYMax) ? dataYMax : fixedYMax;

        // 如果没设置固定范围但数据跨度极大（>20倍），自动截断 Y 轴
        if (Float.isNaN(fixedYMin) && Float.isNaN(fixedYMax)
                && dataYMax > 0 && dataYMax / Math.max(Math.abs(dataYMin), 1e-10f) > 20f) {
            boolean pastPeak = false;
            float postPeakMax = 0f;
            for (int i = 0; i < pointCount; i++) {
                if (!pastPeak && i > 3 && yValues[i] < dataYMax * 0.5f) {
                    pastPeak = true;
                }
                if (pastPeak) {
                    postPeakMax = Math.max(postPeakMax, yValues[i]);
                }
            }
            if (postPeakMax > 0) {
                yMax = postPeakMax * 2.5f;
                yMin = 0f;
            } else {
                yMax = dataYMax * 0.15f;
                yMin = 0f;
            }
        }

        if (yMax <= yMin) {
            yMax = yMin + 1f;
        }
        float yPadding = (yMax - yMin) * 0.06f;
        yMin -= yPadding;
        yMax += yPadding;
        if (yMin == yMax) {
            yMax = yMin + 1f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left   = getPaddingLeft()   + dp(MARGIN_LEFT_DP);
        float top    = getPaddingTop()    + dp(MARGIN_TOP_DP);
        float right  = getWidth() - getPaddingRight() - dp(MARGIN_RIGHT_DP);
        float bottom = getHeight() - getPaddingBottom() - dp(MARGIN_BOTTOM_DP);
        if (right <= left || bottom <= top) {
            return;
        }

        drawTitle(canvas);
        drawGridAndLabels(canvas, left, top, right, bottom);
        if (pointCount < 2) {
            return;
        }
        drawSeries(canvas, left, top, right, bottom);
    }

    private void drawTitle(Canvas canvas) {
        if (chartTitle == null || chartTitle.isEmpty()) {
            return;
        }
        float x = getPaddingLeft() + dp(MARGIN_LEFT_DP);
        float y = getPaddingTop() + dp(18);
        Paint.Align oldAlign = titlePaint.getTextAlign();
        titlePaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(chartTitle, x, y, titlePaint);
        titlePaint.setTextAlign(oldAlign);
    }

    private void drawGridAndLabels(Canvas canvas, float left, float top, float right, float bottom) {
        Paint.Align oldAlign = textPaint.getTextAlign();

        // ── Y轴单位：竖排在数字左侧居中 ──
        if (yAxisLabel != null && !yAxisLabel.isEmpty()) {
            canvas.save();
            float centerY = top + (bottom - top) / 2f;
            float unitX = left - dp(36);
            canvas.rotate(-90, unitX, centerY);
            unitPaint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(yAxisLabel, unitX, centerY + dp(3), unitPaint);
            canvas.restore();
        }

        // ── 横向网格线 + Y 轴刻度数值 ──
        int yTicks = 4;
        float labelX = left - dp(5);
        for (int i = 0; i < yTicks; i++) {
            float fraction = i / (float) (yTicks - 1);
            float y = bottom - fraction * (bottom - top);
            canvas.drawLine(left, y, right, y, gridPaint);
            float value = yMin + fraction * (yMax - yMin);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(formatValue(value), labelX, y + dp(3), textPaint);
        }

        // 底部 X 轴线
        canvas.drawLine(left, bottom, right, bottom, gridPaint);
        int xTicks = 5;
        for (int i = 0; i < xTicks; i++) {
            float fraction = i / (float) (xTicks - 1);
            float x = left + fraction * (right - left);
            canvas.drawLine(x, bottom, x, bottom + dp(3), gridPaint);
            float value = xMin + fraction * (xMax - xMin);
            if (i == 0) {
                textPaint.setTextAlign(Paint.Align.LEFT);
            } else if (i == xTicks - 1) {
                textPaint.setTextAlign(Paint.Align.RIGHT);
            } else {
                textPaint.setTextAlign(Paint.Align.CENTER);
            }
            canvas.drawText(formatValue(value), x, bottom + dp(14), textPaint);
        }

        // X 轴单位：右下角
        if (xAxisLabel != null && !xAxisLabel.isEmpty()) {
            textPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(xAxisLabel, right, bottom + dp(24), textPaint);
        }

        textPaint.setTextAlign(oldAlign);
    }

    private void drawSeries(Canvas canvas, float left, float top, float right, float bottom) {
        linePath.reset();
        boolean first = true;
        boolean prevClipped = false;
        // 按 xValues 排序绘制：确保线条从左到右，不会"回头"
        Integer[] order = new Integer[pointCount];
        for (int i = 0; i < pointCount; i++) order[i] = i;
        boolean needSort = false;
        for (int i = 1; i < pointCount; i++) {
            if (xValues[i] < xValues[i - 1]) { needSort = true; break; }
        }
        if (needSort) {
            java.util.Arrays.sort(order, (a, b) -> {
                float d = xValues[a] - xValues[b];
                if (d != 0) return d > 0 ? 1 : -1;
                return 0;
            });
        }
        for (int k = 0; k < pointCount; k++) {
            int i = needSort ? order[k] : k;
            float x = mapX(xValues[i], left, right);
            float y = mapY(yValues[i], top, bottom);
            boolean clippedTop    = (y < top);
            boolean clippedBottom = (y > bottom);
            if (clippedTop)    y = top;
            if (clippedBottom) y = bottom;
            if (first) {
                linePath.moveTo(x, y);
                first = false;
            } else if (prevClipped) {
                // 上一个点被裁剪（顶部或底部），当前点无论是否正常都断开路径，避免在边界处画多余线段
                linePath.moveTo(x, y);
            } else {
                linePath.lineTo(x, y);
            }
            prevClipped = clippedTop || clippedBottom;
        }
        canvas.drawPath(linePath, linePaint);

        if (fillArea) {
            fillPath.reset();
            fillPath.addPath(linePath);
            float lastX  = mapX(xValues[pointCount - 1], left, right);
            float firstX = mapX(xValues[0], left, right);
            fillPath.lineTo(lastX, bottom);
            fillPath.lineTo(firstX, bottom);
            fillPath.close();
            canvas.drawPath(fillPath, fillPaint);
        }
    }

    private float mapX(float value, float left, float right) {
        float range = xMax - xMin;
        if (range <= 0) {
            return left;
        }
        return left + (value - xMin) / range * (right - left);
    }

    private float mapY(float value, float top, float bottom) {
        float range = yMax - yMin;
        if (range <= 0) {
            return bottom;
        }
        return bottom - (value - yMin) / range * (bottom - top);
    }

    private String formatValue(float value) {
        float abs = Math.abs(value);
        if (abs == 0f) {
            return "0";
        }
        if (abs >= 1000f) {
            return String.format(Locale.US, "%.0f", value);
        }
        if (abs >= 100f) {
            return String.format(Locale.US, "%.1f", value);
        }
        if (abs >= 10f) {
            return String.format(Locale.US, "%.1f", value);
        }
        if (abs >= 1f) {
            return String.format(Locale.US, "%.2f", value);
        }
        if (abs >= 0.01f) {
            return String.format(Locale.US, "%.2f", value);
        }
        if (abs >= 0.001f) {
            return String.format(Locale.US, "%.3f", value);
        }
        return String.format(Locale.US, "%.1e", value);
    }

    private float dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
