package io.github.weiranyi.magneticfield;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

public class AxesChartView extends View {
    private static final long CHART_WINDOW_NS = 20_000_000_000L;
    private static final long CHART_TICK_NS = 5_000_000_000L;
    private static final long CHART_GAP_THRESHOLD_NS = 10_000_000_000L;  // 10秒，避免短暂切后台就断线
    private static final float MIN_CHART_LIMIT = 40f;
    private static final float CHART_LIMIT_STEP = 20f;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint xPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint yPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint zPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    
    private final Paint totalPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path reusablePath = new Path();
    
    private final int gridColor;
    private final int textColor;
    private final int xColor;
    private final int yColor;
    private final int zColor;
    private final int totalColor;
    
    
    private float[] xSamples = new float[0];
    private float[] ySamples = new float[0];
    private float[] zSamples = new float[0];
    private float[] totalSamples = new float[0];
    private long[] timestampsNs = new long[0];
    private long firstSampleTimestampNs = 0L;
    private int sampleIndex;
    private int sampleCount;
    private float chartLimit = 40f;

    public AxesChartView(Context context) {
        this(context, null);
    }

    public AxesChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        
        gridColor = ContextCompat.getColor(context, R.color.chart_grid);
        textColor = ContextCompat.getColor(context, R.color.text_secondary);
        xColor = ContextCompat.getColor(context, R.color.axis_x);
        yColor = ContextCompat.getColor(context, R.color.axis_y);
        zColor = ContextCompat.getColor(context, R.color.axis_z);
        totalColor = ContextCompat.getColor(context, R.color.text_primary);
        gridPaint.setStrokeWidth(dp(1));
        textPaint.setTextSize(dp(12));
        xPaint.setStyle(Paint.Style.STROKE);
        yPaint.setStyle(Paint.Style.STROKE);
        zPaint.setStyle(Paint.Style.STROKE);
        totalPaint.setStyle(Paint.Style.STROKE);
        xPaint.setStrokeWidth(dp(2));
        yPaint.setStrokeWidth(dp(2));
        zPaint.setStrokeWidth(dp(2));
        totalPaint.setStrokeWidth(dp(2));
    }

    public void setSamples(float[] x, float[] y, float[] z, float[] total,
                           long[] timestamps, int index, int count) {
        setSamples(x, y, z, total, timestamps, index, count, 0L);
    }

    public void setSamples(float[] x, float[] y, float[] z, float[] total,
                           long[] timestamps, int index, int count,
                           long originTimestampNs) {
        
        xSamples = x.clone();
        ySamples = y.clone();
        zSamples = z.clone();
        totalSamples = total.clone();
        timestampsNs = timestamps.clone();
        sampleIndex = index;
        sampleCount = count;
        if (count == 0) {
            firstSampleTimestampNs = 0L;
        } else if (originTimestampNs > 0L) {
            firstSampleTimestampNs = originTimestampNs;
        } else if (firstSampleTimestampNs == 0L) {
            firstSampleTimestampNs = getTimestampAtVisibleIndex(0);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = getPaddingLeft() + dp(42);
        float top = getPaddingTop() + dp(10);
        float right = getWidth() - getPaddingRight() - dp(6);
        float bottom = getHeight() - getPaddingBottom() - dp(30);
        if (right <= left || bottom <= top) {
            return;
        }

        gridPaint.setColor(gridColor);
        textPaint.setColor(textColor);
        xPaint.setColor(xColor);
        yPaint.setColor(yColor);
        zPaint.setColor(zColor);
        totalPaint.setColor(totalColor);

        chartLimit = calculateChartLimit();
        drawGrid(canvas, left, top, right, bottom);
        drawTimeAxis(canvas, left, right, bottom);
        if (sampleCount < 2) {
            return;
        }
        drawSeries(canvas, totalSamples, left, top, right, bottom, totalPaint);
        drawSeries(canvas, xSamples, left, top, right, bottom, xPaint);
        drawSeries(canvas, ySamples, left, top, right, bottom, yPaint);
        drawSeries(canvas, zSamples, left, top, right, bottom, zPaint);
    }

    private void drawGrid(Canvas canvas, float left, float top, float right, float bottom) {
        float half = chartLimit / 2f;
        float[] labels = {chartLimit, half, 0f, -half, -chartLimit};
        for (float label : labels) {
            float y = mapValue(label, top, bottom);
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(formatLabel(label), getPaddingLeft() + dp(16), y + dp(4), textPaint);
        }
        canvas.drawText("\u00B5T", getPaddingLeft(), (top + bottom) / 2f + dp(4), textPaint);
    }

    private void drawTimeAxis(Canvas canvas, float left, float right, float bottom) {
        canvas.drawLine(left, bottom, right, bottom, gridPaint);

        if (sampleCount < 2 || timestampsNs.length == 0) {
            canvas.drawText("0", left, bottom + dp(18), textPaint);
            return;
        }

        Paint.Align oldAlign = textPaint.getTextAlign();
        textPaint.setTextAlign(Paint.Align.CENTER);

        long newestNs = sampleCount < 2 ? firstSampleTimestampNs : getTimestampAtVisibleIndex(sampleCount - 1);
        long viewportStartNs = getViewportStartNs(newestNs);
        int labelCount = (int) (CHART_WINDOW_NS / CHART_TICK_NS) + 1;
        for (int i = 0; i < labelCount; i++) {
            long labelNs = viewportStartNs + CHART_TICK_NS * i;
            long labelMs = Math.max(0L, (labelNs - firstSampleTimestampNs) / 1_000_000L);
            float fraction = (CHART_TICK_NS * i) / (float) CHART_WINDOW_NS;
            float x = left + (right - left) * fraction;
            if (i == 0) {
                textPaint.setTextAlign(Paint.Align.LEFT);
            } else if (i == labelCount - 1) {
                textPaint.setTextAlign(Paint.Align.RIGHT);
            } else {
                textPaint.setTextAlign(Paint.Align.CENTER);
            }
            canvas.drawLine(x, bottom, x, bottom + dp(4), gridPaint);
            canvas.drawText(formatTimeLabel(labelMs), x, bottom + dp(18), textPaint);
        }
        textPaint.setTextAlign(oldAlign);
    }

    private void drawSeries(Canvas canvas, float[] samples, float left, float top, float right, float bottom, Paint paint) {
        
        reusablePath.reset();
        if (samples.length == 0) {
            return;
        }
        long newestNs = getTimestampAtVisibleIndex(sampleCount - 1);
        long viewportStartNs = getViewportStartNs(newestNs);
        long viewportEndNs = viewportStartNs + CHART_WINDOW_NS;
        boolean hasPoint = false;
        long prevTimestampNs = 0L;
        for (int i = 0; i < sampleCount; i++) {
            int source = (sampleIndex - sampleCount + i + samples.length) % samples.length;
            long timestampNs = timestampsNs[source];
            if (timestampNs < viewportStartNs || timestampNs > viewportEndNs) {
                continue;
            }
            float x = left + (right - left) * (timestampNs - viewportStartNs) / (float) CHART_WINDOW_NS;
            float y = mapValue(samples[source], top, bottom);
            if (!hasPoint) {
                reusablePath.moveTo(x, y);
                hasPoint = true;
            } else {
                // 相邻点时间间隔超过阈值时断开路径，避免后台返回时出现跳变连线
                if (timestampNs - prevTimestampNs > CHART_GAP_THRESHOLD_NS) {
                    reusablePath.moveTo(x, y);
                } else {
                    reusablePath.lineTo(x, y);
                }
            }
            prevTimestampNs = timestampNs;
        }
        if (hasPoint) {
            canvas.drawPath(reusablePath, paint);
        }
    }

    private float mapValue(float value, float top, float bottom) {
        float clamped = Math.max(-chartLimit, Math.min(chartLimit, value));
        return top + (chartLimit - clamped) * (bottom - top) / (chartLimit * 2f);
    }

    private float calculateChartLimit() {
        float maxAbs = MIN_CHART_LIMIT;
        if (sampleCount == 0 || timestampsNs.length == 0) {
            return MIN_CHART_LIMIT;
        }
        long newestNs = getTimestampAtVisibleIndex(sampleCount - 1);
        long viewportStartNs = getViewportStartNs(newestNs);
        long viewportEndNs = viewportStartNs + CHART_WINDOW_NS;
        for (int i = 0; i < sampleCount; i++) {
            int source = (sampleIndex - sampleCount + i + xSamples.length) % xSamples.length;
            long timestampNs = timestampsNs[source];
            if (timestampNs < viewportStartNs || timestampNs > viewportEndNs) {
                continue;
            }
            maxAbs = Math.max(maxAbs, Math.abs(xSamples[source]));
            maxAbs = Math.max(maxAbs, Math.abs(ySamples[source]));
            maxAbs = Math.max(maxAbs, Math.abs(zSamples[source]));
            maxAbs = Math.max(maxAbs, Math.abs(totalSamples[source]));
        }
        maxAbs = Math.max(MIN_CHART_LIMIT, maxAbs);
        return (float) Math.ceil(maxAbs / CHART_LIMIT_STEP) * CHART_LIMIT_STEP;
    }

    private String formatLabel(float value) {
        return Math.abs(value - Math.round(value)) < 0.01f
                ? String.valueOf(Math.round(value))
                : String.format(java.util.Locale.US, "%.1f", value);
    }

    private long getTimestampAtVisibleIndex(int visibleIndex) {
        if (timestampsNs.length == 0) {
            return 0L;
        }
        int source = (sampleIndex - sampleCount + visibleIndex + timestampsNs.length) % timestampsNs.length;
        return timestampsNs[source];
    }

    private String formatTimeLabel(long valueMs) {
        if (valueMs == 0L) {
            return "0";
        }
        if (valueMs < 1000L) {
            return valueMs + "ms";
        }
        return String.format(java.util.Locale.US, "%.1fs", valueMs / 1000f);
    }

    private long getViewportStartNs(long newestNs) {
        if (firstSampleTimestampNs == 0L || newestNs <= firstSampleTimestampNs + CHART_WINDOW_NS) {
            return firstSampleTimestampNs;
        }
        return newestNs - CHART_WINDOW_NS;
    }

    private float dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
