package app.bedsiderotate;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

import java.util.Locale;

/** Lightweight attitude dial driven by the latest RotationService broadcast. */
public final class PoseGaugeView extends View {
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint needlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arcBounds = new RectF();

    private int trackColor;
    private int accentColor;
    private int warmColor;
    private int inkColor;
    private int mutedColor;
    private int rotation;
    private boolean bed;
    private boolean hasReading;
    private float alpha;
    private float gaugeAngle;

    public PoseGaugeView(Context context) {
        super(context);
        setFocusable(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        configurePaints();
        updateAccessibilityText();
    }

    public void setColors(int trackColor, int accentColor, int warmColor,
                          int inkColor, int mutedColor) {
        this.trackColor = trackColor;
        this.accentColor = accentColor;
        this.warmColor = warmColor;
        this.inkColor = inkColor;
        this.mutedColor = mutedColor;
        invalidate();
    }

    public void setStatus(int rotation, boolean bed, float alpha) {
        this.rotation = rotation;
        this.bed = bed;
        this.hasReading = true;
        this.alpha = alpha;
        this.gaugeAngle = bed ? clampGaugeAngle(alpha) : 0f;
        updateAccessibilityText();
        invalidate();
    }

    private void configurePaints() {
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeWidth(dp(12f));
        arcPaint.setStrokeCap(Paint.Cap.ROUND);

        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setStrokeCap(Paint.Cap.ROUND);

        needlePaint.setStyle(Paint.Style.STROKE);
        needlePaint.setStrokeWidth(dp(4f));
        needlePaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(sp(30f));
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));

        smallTextPaint.setTextAlign(Paint.Align.CENTER);
        smallTextPaint.setTextSize(sp(11f));
        smallTextPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float radius = Math.min(width * 0.39f, height * 0.61f);
        float centerX = width * 0.5f;
        float centerY = dp(24f) + radius;

        arcBounds.set(centerX - radius, centerY - radius,
                centerX + radius, centerY + radius);

        arcPaint.setColor(trackColor);
        canvas.drawArc(arcBounds, 180f, 180f, false, arcPaint);

        float needleDegrees = 270f + gaugeAngle;
        float sweep = gaugeAngle;
        if (bed && Math.abs(sweep) > 0.3f) {
            arcPaint.setColor(gaugeAngle < 0f ? warmColor : accentColor);
            canvas.drawArc(arcBounds, 270f, sweep, false, arcPaint);
        }

        drawTicks(canvas, centerX, centerY, radius);
        drawNeedle(canvas, centerX, centerY, radius, needleDegrees);
        drawReadout(canvas, centerX, centerY, radius);
    }

    private void drawTicks(Canvas canvas, float centerX, float centerY, float radius) {
        for (int index = 0; index <= 12; index++) {
            boolean major = index % 3 == 0;
            double radians = Math.toRadians(180d + index * 15d);
            float outerX = centerX + (float) Math.cos(radians) * (radius + dp(13f));
            float outerY = centerY + (float) Math.sin(radians) * (radius + dp(13f));
            float innerDistance = radius + dp(major ? 3f : 7f);
            float innerX = centerX + (float) Math.cos(radians) * innerDistance;
            float innerY = centerY + (float) Math.sin(radians) * innerDistance;

            tickPaint.setColor(major ? mutedColor : trackColor);
            tickPaint.setStrokeWidth(dp(major ? 2f : 1f));
            canvas.drawLine(innerX, innerY, outerX, outerY, tickPaint);
        }

        smallTextPaint.setColor(mutedColor);
        canvas.drawText("-90°", centerX - radius, centerY + dp(22f), smallTextPaint);
        canvas.drawText("0°", centerX, dp(16f), smallTextPaint);
        canvas.drawText("+90°", centerX + radius, centerY + dp(22f), smallTextPaint);
    }

    private void drawNeedle(Canvas canvas, float centerX, float centerY, float radius,
                            float needleDegrees) {
        if (!hasReading || !bed) {
            needlePaint.setStyle(Paint.Style.FILL);
            needlePaint.setColor(mutedColor);
            canvas.drawCircle(centerX, centerY, dp(5f), needlePaint);
            needlePaint.setStyle(Paint.Style.STROKE);
            return;
        }

        double radians = Math.toRadians(needleDegrees);
        float innerRadius = dp(18f);
        float outerRadius = radius - dp(22f);
        float startX = centerX + (float) Math.cos(radians) * innerRadius;
        float startY = centerY + (float) Math.sin(radians) * innerRadius;
        float endX = centerX + (float) Math.cos(radians) * outerRadius;
        float endY = centerY + (float) Math.sin(radians) * outerRadius;

        needlePaint.setColor(gaugeAngle < 0f ? warmColor : accentColor);
        canvas.drawLine(startX, startY, endX, endY, needlePaint);

        needlePaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(centerX, centerY, dp(5f), needlePaint);
        needlePaint.setStyle(Paint.Style.STROKE);
    }

    private void drawReadout(Canvas canvas, float centerX, float centerY, float radius) {
        if (!hasReading) {
            textPaint.setColor(mutedColor);
            canvas.drawText("—", centerX, centerY + dp(25f), textPaint);
            smallTextPaint.setColor(mutedColor);
            canvas.drawText("等待服务姿态", centerX,
                    centerY - radius * 0.28f, smallTextPaint);
            return;
        }

        textPaint.setColor(bed ? inkColor : mutedColor);
        canvas.drawText(bed ? formatDegrees(alpha) : "—", centerX, centerY + dp(25f), textPaint);

        smallTextPaint.setColor(bed ? warmColor : mutedColor);
        canvas.drawText(bed ? "卧床姿态" : "桌面态 · 床姿角仅在床态有效", centerX,
                centerY - radius * 0.28f, smallTextPaint);
    }

    private float clampGaugeAngle(float alpha) {
        return Math.max(-90f, Math.min(90f, alpha));
    }

    private void updateAccessibilityText() {
        if (!hasReading) {
            setContentDescription("姿态仪表，等待服务姿态");
            return;
        }

        if (!bed) {
            setContentDescription(String.format(Locale.CHINA,
                    "姿态仪表，桌面模式，翻身角不适用，自然方向%d度",
                    (((rotation % 4) + 4) % 4) * 90));
            return;
        }

        String direction = Math.abs(alpha) < 3f
                ? "居中" : (alpha > 0f ? "右倾" : "左倾");
        setContentDescription(String.format(Locale.CHINA,
                "姿态仪表，床姿角%s，卧床模式，%s，系统方向%d度",
                formatDegrees(alpha), direction, rotation * 90));
    }

    private String formatDegrees(float degrees) {
        int rounded = Math.round(degrees);
        if (rounded > 0) {
            return "+" + rounded + "°";
        }
        return rounded + "°";
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private float sp(float value) {
        return value * getResources().getDisplayMetrics().scaledDensity;
    }
}
