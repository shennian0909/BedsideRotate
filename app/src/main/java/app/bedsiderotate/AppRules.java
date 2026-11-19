package app.bedsiderotate;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.Map;

public final class AppRules {
    public static final int PASS = 0;
    public static final int FOLLOW = 1;
    public static final int LANDSCAPE = 2;
    public static final int GAME_PAUSED = 3;

    private static final String PREFS_NAME = "bedside";
    private static final String PREF_ENABLED = "app_rules_enabled";
    private static final String PREF_MODE_PREFIX = "app_rule_mode:";
    private static final String PREF_GAME_PAUSE_ENABLED = "game_pause_enabled";
    private static final String PREF_GAME_APP_PREFIX = "game_app:";

    private static volatile boolean detectorConnected;
    private static volatile String foregroundPackage;

    private AppRules() {
    }

    public static boolean isEnabled(Context context) {
        return preferences(context).getBoolean(PREF_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    public static int modeForPackage(Context context, String packageName) {
        int mode = preferences(context).getInt(modeKey(packageName), FOLLOW);
        return mode == PASS || mode == LANDSCAPE ? mode : FOLLOW;
    }

    public static void setModeForPackage(Context context, String packageName, int mode) {
        preferences(context).edit().putInt(modeKey(packageName), mode).apply();
    }

    static boolean hasDirectionExceptions(Context context) {
        for (Map.Entry<String, ?> entry : preferences(context).getAll().entrySet()) {
            if (entry.getKey().startsWith(PREF_MODE_PREFIX)
                    && (Integer.valueOf(PASS).equals(entry.getValue())
                    || Integer.valueOf(LANDSCAPE).equals(entry.getValue()))) {
                return true;
            }
        }
        return false;
    }

    public static boolean isGamePauseEnabled(Context context) {
        return preferences(context).getBoolean(PREF_GAME_PAUSE_ENABLED, true);
    }

    public static void setGamePauseEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(PREF_GAME_PAUSE_ENABLED, enabled).apply();
    }

    public static boolean isGamePackage(Context context, String packageName) {
        return isSystemGame(context, packageName)
                || preferences(context).getBoolean(gameKey(packageName), false);
    }

    public static boolean isSystemGame(Context context, String packageName) {
        try {
            ApplicationInfo appInfo = context.getPackageManager()
                    .getApplicationInfo(packageName, 0);
            return appInfo.category == ApplicationInfo.CATEGORY_GAME
                    || (appInfo.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }

    public static void setGamePackage(Context context, String packageName, boolean game) {
        SharedPreferences.Editor editor = preferences(context).edit();
        if (game) {
            editor.putBoolean(gameKey(packageName), true);
        } else {
            editor.remove(gameKey(packageName));
        }
        editor.apply();
    }

    public static int mode(Context context) {
        if (BedsideActivity.isForeground()) {
            return FOLLOW;
        }
        String currentPackage = foregroundPackage;
        if (isGamePauseEnabled(context)
                && detectorConnected
                && currentPackage != null
                && !context.getPackageName().equals(currentPackage)
                && isGamePackage(context, currentPackage)) {
            return GAME_PAUSED;
        }
        if (!isEnabled(context)) {
            return FOLLOW;
        }

        if (context.getPackageName().equals(currentPackage)) {
            return FOLLOW;
        }
        if (!detectorConnected || currentPackage == null) {
            return hasDirectionExceptions(context) ? PASS : FOLLOW;
        }
        return modeForPackage(context, currentPackage);
    }

    public static void notifyRotationService(Context context) {
        if (!RotationService.running) {
            return;
        }
        Intent intent = new Intent(context, RotationService.class)
                .setAction(RotationService.OPTIONS);
        context.startService(intent);
    }

    static void setDetectorConnected(boolean connected) {
        detectorConnected = connected;
        if (!connected) {
            foregroundPackage = null;
        }
    }

    static boolean isDetectorConnected() {
        return detectorConnected;
    }

    static String foregroundPackage() {
        return foregroundPackage;
    }

    static void setForegroundPackage(String packageName) {
        foregroundPackage = packageName;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static String modeKey(String packageName) {
        return PREF_MODE_PREFIX + packageName;
    }

    private static String gameKey(String packageName) {
        return PREF_GAME_APP_PREFIX + packageName;
    }
}
