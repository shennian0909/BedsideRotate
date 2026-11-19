package app.bedsiderotate;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

final class BedsideOverlayController {
    private static final int ANCHOR_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
    private static final int PILL_FLAGS = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    private final Context context;
    private final WindowManager windowManager;
    private final Runnable stepCallback;
    private final boolean naturalLandscape;
    private final boolean reverseDefaultRotation;
    private final boolean fixedOrientationDevice;

    private View orientationAnchor;
    private WindowManager.LayoutParams anchorParams;
    private TextView pillView;
    private String issues = "";

    BedsideOverlayController(Context context, Runnable stepCallback) {
        this.context = context;
        this.stepCallback = stepCallback;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.naturalLandscape = isNaturalLandscape(windowManager);
        this.reverseDefaultRotation = readReverseDefaultRotation(context);
        this.fixedOrientationDevice = isFixedOrientationDevice(context);
    }

    void applyOptions(boolean wantOverlay, boolean wantPill, int rotation) {
        boolean needsOverlayPermission = wantOverlay || wantPill;
        if (needsOverlayPermission && !Settings.canDrawOverlays(context)) {
            removeAll();
            issues = "缺少悬浮窗权限";
            return;
        }

        StringBuilder currentIssues = new StringBuilder();
        if (fixedOrientationDevice) {
            appendIssue(currentIssues, "设备固定方向，平台可能忽略方向请求");
        }
        if (wantOverlay) {
            String issue = attachOrientationAnchor(rotation);
            appendIssue(currentIssues, issue);
        } else {
            removeOrientationAnchor();
        }

        if (wantPill) {
            String issue = attachPill();
            appendIssue(currentIssues, issue);
        } else {
            removePill();
        }
        issues = currentIssues.toString();
    }

    void updateRotation(int rotation) {
        if (orientationAnchor == null || anchorParams == null) {
            return;
        }
        anchorParams.screenOrientation = screenOrientationFor(rotation);
        try {
            windowManager.updateViewLayout(orientationAnchor, anchorParams);
        } catch (RuntimeException error) {
            removeOrientationAnchor();
            issues = "方向覆盖层更新失败";
        }
    }

    void retryOrientationAnchor(int rotation) {
        if (orientationAnchor == null) {
            return;
        }
        removeOrientationAnchor();
        String issue = attachOrientationAnchor(rotation);
        if (!issue.isEmpty()) {
            issues = issue;
        }
    }

    String issues() {
        return issues;
    }

    int defaultLandscapeRotation() {
        if (naturalLandscape) {
            return Surface.ROTATION_0;
        }
        return reverseDefaultRotation ? Surface.ROTATION_270 : Surface.ROTATION_90;
    }

    void removeAll() {
        removeOrientationAnchor();
        removePill();
        issues = "";
    }

    private String attachOrientationAnchor(int rotation) {
        if (orientationAnchor == null) {
            orientationAnchor = new View(context);
            anchorParams = new WindowManager.LayoutParams(
                    0,
                    0,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    ANCHOR_FLAGS,
                    PixelFormat.TRANSLUCENT
            );
            anchorParams.gravity = Gravity.TOP | Gravity.START;
            anchorParams.screenOrientation = screenOrientationFor(rotation);
            try {
                windowManager.addView(orientationAnchor, anchorParams);
            } catch (RuntimeException error) {
                orientationAnchor = null;
                anchorParams = null;
                return "方向覆盖层创建失败";
            }
            return "";
        }

        anchorParams.screenOrientation = screenOrientationFor(rotation);
        try {
            windowManager.updateViewLayout(orientationAnchor, anchorParams);
            return "";
        } catch (RuntimeException error) {
            removeOrientationAnchor();
            return "方向覆盖层更新失败";
        }
    }

    private String attachPill() {
        if (pillView != null) {
            return "";
        }

        TextView pill = new TextView(context);
        pill.setText("↻\n90°");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(14f);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setGravity(Gravity.CENTER);
        pill.setContentDescription("手动旋转90度");
        pill.setAlpha(0.88f);
        pill.setElevation(dp(6));
        pill.setPadding(dp(6), dp(10), dp(6), dp(10));
        pill.setBackground(createPillBackground());
        pill.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                stepCallback.run();
            }
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(58),
                dp(108),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                PILL_FLAGS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        params.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        try {
            windowManager.addView(pill, params);
            pillView = pill;
            return "";
        } catch (RuntimeException error) {
            return "边缘手动胶囊创建失败";
        }
    }

    private GradientDrawable createPillBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xD91F2937);
        background.setCornerRadius(dp(28));
        background.setStroke(dp(1), 0x66FFFFFF);
        return background;
    }

    private void removeOrientationAnchor() {
        if (orientationAnchor == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(orientationAnchor);
        } catch (RuntimeException ignored) {
        }
        orientationAnchor = null;
        anchorParams = null;
    }

    private void removePill() {
        if (pillView == null) {
            return;
        }
        try {
            windowManager.removeViewImmediate(pillView);
        } catch (RuntimeException ignored) {
        }
        pillView = null;
    }

    int screenOrientationFor(int rotation) {
        int normalized = ((rotation % 4) + 4) % 4;
        if (naturalLandscape) {
            switch (normalized) {
                case Surface.ROTATION_0:
                    return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                case Surface.ROTATION_90:
                    return reverseDefaultRotation
                            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            : ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
                case Surface.ROTATION_180:
                    return ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
                case Surface.ROTATION_270:
                    return reverseDefaultRotation
                            ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
                            : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                default:
                    return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            }
        }

        switch (normalized) {
            case Surface.ROTATION_0:
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            case Surface.ROTATION_90:
                return reverseDefaultRotation
                        ? ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case Surface.ROTATION_180:
                return ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT;
            case Surface.ROTATION_270:
                return reverseDefaultRotation
                        ? ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
            default:
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    private static boolean isNaturalLandscape(WindowManager windowManager) {
        Display display = windowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        int rotation = display.getRotation();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            int width = size.x;
            size.x = size.y;
            size.y = width;
        }
        return size.x > size.y;
    }

    private static boolean readReverseDefaultRotation(Context context) {
        int resourceId = context.getResources().getIdentifier(
                "config_reverseDefaultRotation",
                "bool",
                "android"
        );
        return resourceId != 0 && context.getResources().getBoolean(resourceId);
    }

    private static boolean isFixedOrientationDevice(Context context) {
        PackageManager packageManager = context.getPackageManager();
        return packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
                || packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
                || packageManager.hasSystemFeature("android.hardware.type.pc");
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void appendIssue(StringBuilder target, String issue) {
        if (issue == null || issue.isEmpty()) {
            return;
        }
        if (target.length() > 0) {
            target.append("；");
        }
        target.append(issue);
    }
}
