package app.bedsiderotate;

import android.accessibilityservice.AccessibilityService;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class AppRuleService extends AccessibilityService {
    private String homePackage;
    private String homeClass;
    private final Set<String> launchers = new HashSet<>();
    private boolean overview;
    private boolean homeRequested;
    private boolean connected;
    private int ownerWindow = -1;
    private int inspectedWindow = -1;
    private String inspectedPackage;
    private int launcherWindow = -1;
    private String launcherWindowPackage;
    private enum LauncherPage { ACTIVITY, HOME, OVERVIEW }
    private LauncherPage launcherPage;

    private final BroadcastReceiver navigation = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String reason = intent.getStringExtra("reason");
            if ("recentapps".equals(reason)) {
                launcherWindow = -1;
                overview = true;
                homeRequested = false;
            } else if ("homekey".equals(reason)) {
                launcherWindow = -1;
                overview = false;
                homeRequested = true;
                resolveHome();
                inspectWindows(null);
            }
        }
    };

    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        if (!connected) {
            IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
            registerReceiver(navigation, filter, Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? Context.RECEIVER_EXPORTED : 0);
        }
        connected = true;
        resolveHome();
        AppRules.setDetectorConnected(true);
        inspectWindows(null);
        AppRules.notifyForegroundChanged();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            rememberLauncherPage(event);
            inspectWindows(event);
        } else if (event.getEventType() == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            inspectWindows(null);
        }
    }

    private void inspectWindows(AccessibilityEvent event) {
        List<AccessibilityWindowInfo> windows = getWindows();
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                        && (window.isFocused() || window.isActive())) {
                    inspectApplication(window, windows, event);
                    return;
                }
            }
        } finally {
            for (AccessibilityWindowInfo window : windows) window.recycle();
        }
    }

    private void inspectApplication(AccessibilityWindowInfo window,
                                    List<AccessibilityWindowInfo> windows, AccessibilityEvent event) {
        boolean matchingEvent = event != null && event.getWindowId() == window.getId()
                && event.getPackageName() != null;
        String pkg = matchingEvent ? event.getPackageName().toString() : packageForWindow(window);
        if (pkg == null || "com.android.systemui".equals(pkg)) return;
        if (!pkg.equals(inspectedPackage)) {
            resolveHome();
            if (matchingEvent) rememberLauncherPage(event);
        }
        inspectedWindow = window.getId();
        inspectedPackage = pkg;

        String owner = AppRules.foregroundPackage();
        if (!pkg.equals(owner) && AppRules.isGamePauseEnabled(this)
                && retainUnderlyingGame(window, windows, owner)) return;
        if (launchers.contains(pkg)) {
            boolean identified = window.getId() == launcherWindow && pkg.equals(launcherWindowPackage);
            if (identified && launcherPage == LauncherPage.OVERVIEW) {
                overview = true;
                homeRequested = false;
                return;
            }
            if (pkg.equals(homePackage)) {
                if (identified && launcherPage == LauncherPage.HOME) {
                    overview = false;
                    homeRequested = true;
                }
                if (!homeRequested && (overview || !identified)) return;
            } else if (!identified) {
                return;
            }
        }
        if (!pkg.equals(homePackage) && (!pkg.equals(owner) || matchingEvent && isActivityClass(event))) {
            overview = false;
            homeRequested = false;
            if (!pkg.equals(owner) && !pkg.equals(launcherWindowPackage)) launcherWindow = -1;
        }
        selectApp(pkg, window.getId());
    }

    private void selectApp(String pkg, int windowId) {
        ownerWindow = windowId;
        if (!pkg.equals(AppRules.foregroundPackage())) {
            AppRules.setForegroundPackage(pkg);
            AppRules.notifyForegroundChanged();
        }
    }

    private String packageForWindow(AccessibilityWindowInfo window) {
        if (inspectedWindow == window.getId()) return inspectedPackage;
        AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? window.getRoot(0) : window.getRoot();
        if (root == null) return null;
        try {
            CharSequence name = root.getPackageName();
            return name == null ? null : name.toString();
        } finally {
            root.recycle();
        }
    }

    private boolean retainUnderlyingGame(AccessibilityWindowInfo candidate,
                                         List<AccessibilityWindowInfo> windows, String owner) {
        Rect bounds = new Rect();
        candidate.getBoundsInScreen(bounds);
        Point usable = new Point();
        getSystemService(WindowManager.class).getDefaultDisplay().getSize(usable);
        boolean narrow = bounds.width() < usable.x;
        boolean shortWindow = bounds.height() < usable.y;
        boolean floating = candidate.isInPictureInPictureMode() || (narrow && shortWindow);
        if (!floating && !narrow && !shortWindow) return false;
        if (candidate.isInPictureInPictureMode() && owner != null && AppRules.isGamePackage(this, owner)) return true;
        for (AccessibilityWindowInfo other : windows) {
            if (other.getType() != AccessibilityWindowInfo.TYPE_APPLICATION
                    || other.getLayer() >= candidate.getLayer()) continue;
            if (other.getId() == ownerWindow && owner != null && AppRules.isGamePackage(this, owner)) return true;
            other.getBoundsInScreen(bounds);
            if (!floating || bounds.width() < usable.x || bounds.height() < usable.y) continue;
            String pkg = packageForWindow(other);
            if (pkg != null && AppRules.isGamePackage(this, pkg)) {
                selectApp(pkg, other.getId());
                return true;
            }
        }
        return false;
    }

    private void resolveHome() {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        PackageManager pm = getPackageManager();
        ResolveInfo match = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        launchers.clear();
        for (ResolveInfo launcher : pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY)) {
            launchers.add(launcher.activityInfo.packageName);
        }
        ActivityInfo activity = match == null ? null : match.activityInfo;
        homePackage = activity == null ? null : activity.packageName;
        homeClass = activity == null ? "" : activity.targetActivity != null ? activity.targetActivity : activity.name;
    }

    private static boolean isActivityClass(AccessibilityEvent event) {
        String cls = String.valueOf(event.getClassName());
        return !cls.startsWith("android.") && !cls.startsWith("androidx.");
    }

    // An Activity can announce its page before Android adds its window to getWindows().
    private void rememberLauncherPage(AccessibilityEvent event) {
        String pkg = String.valueOf(event.getPackageName());
        if (!launchers.contains(pkg) || !isActivityClass(event)) return;
        if (isOverviewClass(event)) {
            launcherPage = LauncherPage.OVERVIEW;
        } else if (pkg.equals(homePackage)) {
            if (!homeClass.equals(String.valueOf(event.getClassName()))) return;
            launcherPage = isOverviewEvent(event) ? LauncherPage.OVERVIEW
                    : isHomeAnnouncement(event) ? LauncherPage.HOME : LauncherPage.ACTIVITY;
        } else {
            launcherPage = LauncherPage.ACTIVITY;
        }
        launcherWindow = event.getWindowId();
        launcherWindowPackage = pkg;
    }

    private static boolean isOverviewClass(AccessibilityEvent event) {
        String cls = String.valueOf(event.getClassName());
        String simple = cls.substring(cls.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return simple.startsWith("recents") || simple.startsWith("overview");
    }

    private static boolean isOverviewEvent(AccessibilityEvent event) {
        for (CharSequence label : event.getText()) {
            String title = label.toString().trim().toLowerCase(Locale.ROOT);
            if (title.equals("最近用过的应用") || title.equals("最近使用的应用") || title.startsWith("最近任务")
                    || title.startsWith("最近任務") || title.equals("多任务") || title.equals("多工處理")
                    || title.equals("recents") || title.equals("recent apps") || title.equals("overview")) return true;
        }
        return false;
    }

    private static boolean isHomeAnnouncement(AccessibilityEvent event) {
        for (CharSequence label : event.getText()) {
            String title = label.toString().trim().toLowerCase(Locale.ROOT);
            if (title.startsWith("主屏幕") || title.startsWith("主螢幕") || title.startsWith("主畫面")
                    || title.equals("桌面") || title.startsWith("home screen")) return true;
        }
        return false;
    }

    @Override public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override public void onInterrupt() { }

    private void disconnect() {
        if (!connected) return;
        connected = false;
        unregisterReceiver(navigation);
        inspectedWindow = ownerWindow = launcherWindow = -1;
        inspectedPackage = null;
        overview = homeRequested = false;
        AppRules.setDetectorConnected(false);
        AppRules.notifyForegroundChanged();
    }
}
