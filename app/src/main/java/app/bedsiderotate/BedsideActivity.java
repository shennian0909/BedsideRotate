package app.bedsiderotate;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

/** Local pages can identify themselves even when external app detection is unavailable. */
public abstract class BedsideActivity extends Activity {
    static final String PREFERENCES = "bedside";
    static final String PREF_HIDE_FROM_RECENTS = "hide_from_recents";
    static final String PREF_TILE_ENABLED = "tile_enabled";
    static final String PREF_TILE_ADDED = "tile_added";

    private static BedsideActivity foreground;

    static boolean isForeground() {
        return foreground != null;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    static boolean isTileEnabled(Context context) {
        return preferences(context).getBoolean(PREF_TILE_ENABLED, true);
    }

    static boolean isTileAdded(Context context) {
        return preferences(context).getBoolean(PREF_TILE_ADDED, false);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        applyRecentsVisibility();
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = this;
        AppRules.notifyRotationService(this);
    }

    @Override
    protected void onPause() {
        if (foreground == this) {
            foreground = null;
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // A configuration restart hands the same display request to the new Activity.
        if (!isChangingConfigurations() && foreground == null) {
            AppRules.notifyRotationService(this);
        }
    }

    void applyRecentsVisibility() {
        boolean exclude = preferences(this).getBoolean(PREF_HIDE_FROM_RECENTS, false);
        ActivityManager activityManager =
                (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        int taskId = getTaskId();
        for (ActivityManager.AppTask task : activityManager.getAppTasks()) {
            if (task.getTaskInfo().id == taskId) {
                task.setExcludeFromRecents(exclude);
                return;
            }
        }
    }
}
