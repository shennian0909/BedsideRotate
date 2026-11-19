package app.bedsiderotate;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.view.accessibility.AccessibilityEvent;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class AppRuleService extends AccessibilityService {
    private static final String SYSTEM_UI_PACKAGE = "com.android.systemui";

    private final Set<String> inputMethodPackages = new HashSet<>();
    private boolean detectorActive;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        refreshInputMethodPackages();
        AppRules.setDetectorConnected(true);
        detectorActive = true;
        apply();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }

        CharSequence packageName = event.getPackageName();
        if (packageName == null || packageName.length() == 0) {
            return;
        }

        String foregroundPackage = packageName.toString();
        refreshInputMethodPackages();
        if (SYSTEM_UI_PACKAGE.equals(foregroundPackage)
                || inputMethodPackages.contains(foregroundPackage)
                || foregroundPackage.equals(AppRules.foregroundPackage())) {
            return;
        }

        AppRules.setForegroundPackage(foregroundPackage);
        apply();
    }

    @Override
    public boolean onUnbind(Intent intent) {
        disconnect();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        disconnect();
        super.onDestroy();
    }

    @Override
    public void onInterrupt() {
    }

    private void apply() {
        AppRules.notifyRotationService(this);
    }

    private void disconnect() {
        if (!detectorActive) {
            return;
        }
        detectorActive = false;
        AppRules.setDetectorConnected(false);
        apply();
    }

    private void refreshInputMethodPackages() {
        inputMethodPackages.clear();
        InputMethodManager inputMethodManager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (inputMethodManager == null) {
            return;
        }

        List<InputMethodInfo> inputMethods = inputMethodManager.getEnabledInputMethodList();
        for (InputMethodInfo inputMethod : inputMethods) {
            inputMethodPackages.add(inputMethod.getPackageName());
        }
    }
}
