package app.bedsiderotate;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class RuleActivity extends BedsideActivity {
    private static final String[] MODE_LABELS = {
            "交还系统",
            "跟随躺姿",
            "固定横屏"
    };

    private final List<LauncherApp> launcherApps = new ArrayList<>();

    private Palette palette;
    private boolean syncingSwitch;
    private boolean syncingGameSwitch;
    private Switch enabledSwitch;
    private Switch gamePauseSwitch;
    private TextView summaryTitle;
    private TextView summaryDetail;
    private TextView permissionState;
    private TextView gamePermissionState;
    private TextView appCount;
    private Button permissionButton;
    private Button gamePermissionButton;
    private LinearLayout gamePermissionRow;
    private LinearLayout appList;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) {
            getActionBar().hide();
        }

        palette = new Palette(isNightMode());
        configureWindow();
        loadLauncherApps();
        buildContent();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshState();
    }

    private void buildContent() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(palette.background);
        scrollView.setVerticalScrollBarEnabled(false);

        int horizontalPadding = dp(getResources().getConfiguration().smallestScreenWidthDp >= 600
                ? 30
                : 18);
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(horizontalPadding, dp(20), horizontalPadding, dp(34));
        shell.setBackgroundColor(palette.background);

        shell.addView(createHeader(), matchWrap());
        shell.addView(createMasterCard(), matchWrap());
        shell.addView(space(16));
        shell.addView(createAppsCard(), matchWrap());
        shell.addView(space(14));

        TextView footer = text(
                "前台识别在本机判断窗口所属应用、类型和范围，并结合桌面导航状态区分最近任务和主屏幕。游戏上方打开小窗或进入最近任务时继续暂停，回到主屏幕后恢复阅读方向。服务不截图、不录屏，也不读取小窗文字。识别未连接时，若已设置方向例外会暂时交还系统，其余情况继续跟随躺姿。",
                11.5f,
                palette.faint,
                Typeface.NORMAL
        );
        footer.setLineSpacing(dp(2), 1f);
        shell.addView(footer, matchWrap());

        scrollView.addView(shell, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        setContentView(scrollView);
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, 0, 0, dp(18));

        Button back = createButton("返回");
        back.setOnClickListener(view -> finish());
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(dp(72), dp(40));
        backParams.setMarginEnd(dp(12));
        header.addView(back, backParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("分应用方向", 27f, palette.ink, Typeface.BOLD);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        TextView subtitle = text("按正在使用的应用决定是否跟随躺姿", 12.5f,
                palette.muted, Typeface.NORMAL);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(title);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        return header;
    }

    private View createMasterCard() {
        LinearLayout card = createCard();

        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView title = text("启用分应用规则", 18f, palette.ink, Typeface.BOLD);
        TextView explanation = text("关闭时普通应用按全局躺姿跟随。开启后未单独配置的应用仍按全局躺姿跟随，右侧可保存交还系统或固定横屏例外。",
                12f, palette.muted, Typeface.NORMAL);
        explanation.setPadding(0, dp(3), dp(12), 0);
        copy.addView(title);
        copy.addView(explanation);
        switchRow.addView(copy, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        enabledSwitch = new Switch(this);
        enabledSwitch.setShowText(false);
        enabledSwitch.setContentDescription("启用分应用规则");
        switchRow.addView(enabledSwitch);
        card.addView(switchRow, matchWrap());

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.VERTICAL);
        summary.setPadding(dp(14), dp(13), dp(14), dp(13));
        summary.setBackground(rounded(palette.inset, dp(12), 0, Color.TRANSPARENT));
        summaryTitle = text("", 15f, palette.ink, Typeface.BOLD);
        summaryDetail = text("", 12f, palette.muted, Typeface.NORMAL);
        summaryDetail.setPadding(0, dp(3), 0, 0);
        summaryDetail.setLineSpacing(dp(2), 1f);
        summary.addView(summaryTitle);
        summary.addView(summaryDetail);
        LinearLayout.LayoutParams summaryParams = matchWrap();
        summaryParams.topMargin = dp(15);
        card.addView(summary, summaryParams);

        View permissionRule = new View(this);
        permissionRule.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        ruleParams.topMargin = dp(16);
        card.addView(permissionRule, ruleParams);

        LinearLayout permissionRow = new LinearLayout(this);
        permissionRow.setOrientation(LinearLayout.HORIZONTAL);
        permissionRow.setGravity(Gravity.CENTER_VERTICAL);
        permissionRow.setPadding(0, dp(12), 0, 0);

        LinearLayout permissionCopy = new LinearLayout(this);
        permissionCopy.setOrientation(LinearLayout.VERTICAL);
        TextView permissionTitle = text("前台识别权限", 13f, palette.ink, Typeface.BOLD);
        permissionState = text("", 11.5f, palette.muted, Typeface.NORMAL);
        permissionState.setPadding(0, dp(2), dp(10), 0);
        permissionState.setLineSpacing(dp(1), 1f);
        permissionCopy.addView(permissionTitle);
        permissionCopy.addView(permissionState);
        permissionRow.addView(permissionCopy, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        permissionButton = addPermissionButton(permissionRow);
        card.addView(permissionRow, matchWrap());

        View gameRule = new View(this);
        gameRule.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams gameRuleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1)
        );
        gameRuleParams.topMargin = dp(16);
        card.addView(gameRule, gameRuleParams);

        LinearLayout gameSwitchRow = new LinearLayout(this);
        gameSwitchRow.setOrientation(LinearLayout.HORIZONTAL);
        gameSwitchRow.setGravity(Gravity.CENTER_VERTICAL);
        gameSwitchRow.setPadding(0, dp(14), 0, 0);

        LinearLayout gameCopy = new LinearLayout(this);
        gameCopy.setOrientation(LinearLayout.VERTICAL);
        TextView gameTitle = text("游戏时暂停", 16f, palette.ink, Typeface.BOLD);
        TextView gameExplanation = text(
                "识别到游戏后暂停躺姿接管，离开游戏后自动恢复。默认开启，独立于上方分应用规则总开关。",
                12f,
                palette.muted,
                Typeface.NORMAL
        );
        gameExplanation.setPadding(0, dp(3), dp(12), 0);
        gameExplanation.setLineSpacing(dp(1), 1f);
        gameCopy.addView(gameTitle);
        gameCopy.addView(gameExplanation);
        gameSwitchRow.addView(gameCopy, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        gamePauseSwitch = new Switch(this);
        gamePauseSwitch.setShowText(false);
        gamePauseSwitch.setContentDescription("游戏时暂停");
        gameSwitchRow.addView(gamePauseSwitch);
        card.addView(gameSwitchRow, matchWrap());

        gamePermissionRow = new LinearLayout(this);
        gamePermissionRow.setOrientation(LinearLayout.HORIZONTAL);
        gamePermissionRow.setGravity(Gravity.CENTER_VERTICAL);
        gamePermissionRow.setPadding(dp(12), dp(10), dp(12), dp(10));
        gamePermissionRow.setBackground(rounded(
                palette.warmSoft,
                dp(12),
                0,
                Color.TRANSPARENT
        ));
        gamePermissionState = text("", 11.5f, palette.warm, Typeface.NORMAL);
        gamePermissionState.setLineSpacing(dp(1), 1f);
        gamePermissionRow.addView(gamePermissionState, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        gamePermissionButton = addPermissionButton(gamePermissionRow);
        LinearLayout.LayoutParams gamePermissionParams = matchWrap();
        gamePermissionParams.topMargin = dp(12);
        card.addView(gamePermissionRow, gamePermissionParams);

        enabledSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (syncingSwitch) {
                    return;
                }
                AppRules.setEnabled(RuleActivity.this, checked);
                refreshState();
                AppRules.notifyRotationService(RuleActivity.this);
            }
        });

        gamePauseSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingGameSwitch) {
                return;
            }
            AppRules.setGamePauseEnabled(RuleActivity.this, checked);
            refreshState();
            AppRules.notifyRotationService(RuleActivity.this);
        });
        return card;
    }

    private View createAppsCard() {
        LinearLayout card = createCard();

        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = sectionTitle("应用方向");
        heading.addView(title, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));
        appCount = text("", 11.5f, palette.muted, Typeface.BOLD);
        heading.addView(appCount);
        card.addView(heading, matchWrap());

        TextView caption = text(
                "右侧选择每个应用的方向。未单独配置的普通应用默认按全局躺姿跟随。前台身份未明确时，已保存交还系统或固定横屏例外会保持交还系统方向。系统未识别的游戏可在应用名下方手动标记。",
                12f,
                palette.muted,
                Typeface.NORMAL
        );
        caption.setPadding(0, dp(5), 0, dp(6));
        caption.setLineSpacing(dp(1), 1f);
        card.addView(caption, matchWrap());

        appList = new LinearLayout(this);
        appList.setOrientation(LinearLayout.VERTICAL);
        populateAppList();
        card.addView(appList, matchWrap());
        return card;
    }

    private void populateAppList() {
        appList.removeAllViews();
        if (launcherApps.isEmpty()) {
            appCount.setText("0 个");
            addEmptyNotice("没有发现带桌面入口的应用。请确认清单已声明 MAIN/LAUNCHER 查询。");
            return;
        }

        int configurableCount = 0;
        for (LauncherApp app : launcherApps) {
            if (!getPackageName().equals(app.packageName)) {
                configurableCount++;
            }
        }
        appCount.setText(String.format(Locale.CHINA, "%d 个可配置", configurableCount));

        for (int index = 0; index < launcherApps.size(); index++) {
            if (index > 0) {
                View divider = new View(this);
                divider.setBackgroundColor(palette.line);
                LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1)
                );
                dividerParams.setMarginStart(dp(50));
                appList.addView(divider, dividerParams);
            }
            appList.addView(createAppRow(launcherApps.get(index)), matchWrap());
        }

        if (configurableCount == 0) {
            addEmptyNotice("没有其他可配置应用。懒人翻身自身始终跟随躺姿，以便在主界面调整参数。");
        }
    }

    private View createAppRow(LauncherApp app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        boolean ownApp = getPackageName().equals(app.packageName);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(app.icon);
        icon.setContentDescription(null);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        iconParams.setMarginEnd(dp(12));
        row.addView(icon, iconParams);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView name = text(app.label, 14f, palette.ink, Typeface.BOLD);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView packageName = text(app.packageName, 10.5f, palette.faint, Typeface.NORMAL);
        packageName.setSingleLine(true);
        packageName.setEllipsize(TextUtils.TruncateAt.END);
        packageName.setPadding(0, dp(2), dp(8), 0);
        copy.addView(name);
        copy.addView(packageName);

        if (!ownApp) {
            boolean systemGame = AppRules.isSystemGame(this, app.packageName);
            Switch gameSwitch = new Switch(this);
            gameSwitch.setText(systemGame ? "系统识别为游戏" : "标为游戏");
            gameSwitch.setTextSize(11f);
            gameSwitch.setTextColor(palette.muted);
            gameSwitch.setChecked(AppRules.isGamePackage(this, app.packageName));
            gameSwitch.setEnabled(!systemGame);
            gameSwitch.setContentDescription(app.label + "游戏时暂停");
            if (!systemGame) {
                gameSwitch.setOnCheckedChangeListener((button, checked) -> {
                    AppRules.setGamePackage(RuleActivity.this, app.packageName, checked);
                    refreshState();
                    AppRules.notifyRotationService(RuleActivity.this);
                });
            }
            copy.addView(gameSwitch);
        }

        row.addView(copy, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        ));

        if (ownApp) {
            TextView fixed = text("始终跟随躺姿", 11f, palette.accentText, Typeface.BOLD);
            fixed.setGravity(Gravity.CENTER);
            fixed.setPadding(dp(10), dp(7), dp(10), dp(7));
            fixed.setBackground(rounded(
                    palette.accentSoft,
                    dp(18),
                    0,
                    Color.TRANSPARENT
            ));
            row.addView(fixed);
        } else {
            Spinner mode = new Spinner(this, Spinner.MODE_DROPDOWN);
            int spinnerWidth = modeSpinnerWidth(mode);
            mode.setAdapter(new ModeAdapter());
            mode.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            mode.setMinimumHeight(dp(42));
            mode.setBackgroundTintList(ColorStateList.valueOf(palette.ink));
            mode.setDropDownWidth(spinnerWidth);
            mode.setPopupBackgroundDrawable(rounded(
                    palette.surface,
                    dp(11),
                    dp(1),
                    palette.line
            ));
            mode.setContentDescription(app.label + "方向模式");
            int initialMode = AppRules.modeForPackage(this, app.packageName);
            mode.setSelection(initialMode, false);
            mode.setOnItemSelectedListener(new ModeSelectionListener(app.packageName, initialMode));
            row.addView(mode, new LinearLayout.LayoutParams(
                    spinnerWidth,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        }
        return row;
    }

    private void refreshState() {
        boolean enabled = AppRules.isEnabled(this);
        boolean gamePauseEnabled = AppRules.isGamePauseEnabled(this);
        boolean permissionGranted = isAccessibilityServiceEnabled();
        boolean connected = AppRules.isDetectorConnected();
        String foregroundPackage = AppRules.foregroundPackage();
        boolean foregroundIsGame = connected
                && foregroundPackage != null
                && !getPackageName().equals(foregroundPackage)
                && AppRules.isGamePackage(this, foregroundPackage);
        boolean foregroundGamePaused = gamePauseEnabled && foregroundIsGame;
        boolean recognitionNeeded = enabled || gamePauseEnabled;
        boolean hasDirectionExceptions = AppRules.hasDirectionExceptions(this);

        syncingSwitch = true;
        enabledSwitch.setChecked(enabled);
        syncingSwitch = false;

        syncingGameSwitch = true;
        gamePauseSwitch.setChecked(gamePauseEnabled);
        syncingGameSwitch = false;

        int summaryBackground = palette.inset;
        int summaryTitleColor = palette.ink;
        if (recognitionNeeded && (!permissionGranted || !connected)) {
            summaryBackground = palette.warmSoft;
            summaryTitleColor = palette.warm;
        } else if (foregroundGamePaused || (enabled && foregroundPackage != null)) {
            summaryBackground = palette.accentSoft;
            summaryTitleColor = palette.accentText;
        }
        ((View) summaryTitle.getParent()).setBackground(
                rounded(summaryBackground, dp(12), 0, Color.TRANSPARENT)
        );
        summaryTitle.setTextColor(summaryTitleColor);

        if (gamePauseEnabled && !permissionGranted) {
            summaryTitle.setText("未授权游戏识别");
            summaryDetail.setText("授权后进入游戏自动暂停，离开游戏自动恢复。");
        } else if (gamePauseEnabled && !connected) {
            summaryTitle.setText("等待游戏识别服务连接");
            summaryDetail.setText("连接建立前不会暂停，离开游戏后的恢复规则保持不变。");
        } else if (foregroundGamePaused) {
            summaryTitle.setText(String.format(
                    Locale.CHINA,
                    "%s · 游戏暂停",
                    appLabel(foregroundPackage)
            ));
            summaryDetail.setText("已识别为游戏，暂停躺姿接管；离开游戏后自动恢复。");
        } else if (!enabled) {
            summaryTitle.setText("分应用规则已关闭");
            summaryDetail.setText(gamePauseEnabled
                    ? "普通应用跟随躺姿；游戏时暂停仍按独立开关生效。"
                    : "懒人翻身运行时所有应用跟随躺姿，分应用选择暂不生效。");
        } else if (!permissionGranted) {
            summaryTitle.setText("未授权前台识别");
            summaryDetail.setText(hasDirectionExceptions
                    ? "已保存方向例外，但无法识别前台身份，外部应用保持交还系统方向。懒人翻身控制台可自行识别。"
                    : "未保存方向例外，外部应用直接使用全局躺姿跟随。懒人翻身控制台可自行识别，授权后可识别方向例外和游戏。");
        } else if (!connected) {
            summaryTitle.setText("等待识别服务连接");
            summaryDetail.setText(hasDirectionExceptions
                    ? "识别服务未连接，已保存方向例外，外部应用保持交还系统方向。懒人翻身控制台可自行识别。"
                    : "识别服务未连接。未保存方向例外，外部应用直接使用全局躺姿跟随，懒人翻身控制台可自行识别。");
        } else if (foregroundPackage == null) {
            summaryTitle.setText("等待明确的前台应用");
            summaryDetail.setText(hasDirectionExceptions
                    ? "前台身份尚不明确，已保存方向例外，外部应用保持交还系统方向。懒人翻身控制台可自行识别。"
                    : "前台身份尚不明确。未保存方向例外，外部应用直接使用全局躺姿跟随，懒人翻身控制台可自行识别。");
        } else if (getPackageName().equals(foregroundPackage)) {
            summaryTitle.setText("懒人翻身自身 · 跟随躺姿");
            summaryDetail.setText("打开懒人翻身时始终跟随躺姿，便于调整阈值和控制参数。");
        } else {
            int mode = AppRules.mode(this);
            summaryTitle.setText(String.format(
                    Locale.CHINA,
                    "%s · %s",
                    appLabel(foregroundPackage),
                    modeLabel(mode)
            ));
            if (mode == AppRules.PASS) {
                summaryDetail.setText("该应用交还系统方向，不接管自动旋转。");
            } else if (mode == AppRules.LANDSCAPE) {
                summaryDetail.setText("该应用固定为横屏，离开后按新前台应用重新判断。");
            } else if (mode == AppRules.GAME_PAUSED) {
                summaryDetail.setText("该应用被识别为游戏，暂停躺姿接管；离开游戏后自动恢复。");
            } else {
                summaryDetail.setText("该应用跟随躺姿方向，离开后按新前台应用重新判断。");
            }
        }

        gamePermissionRow.setVisibility(gamePauseEnabled ? View.VISIBLE : View.GONE);
        int gamePermissionBackground = palette.accentSoft;
        int gamePermissionColor = palette.success;
        if (!permissionGranted || !connected) {
            gamePermissionBackground = palette.warmSoft;
            gamePermissionColor = palette.warm;
        }
        gamePermissionRow.setBackground(rounded(
                gamePermissionBackground,
                dp(12),
                0,
                Color.TRANSPARENT
        ));
        gamePermissionState.setTextColor(gamePermissionColor);
        if (!permissionGranted) {
            gamePermissionState.setText(
                    "未授权前台识别。授权后进入游戏时暂停，离开游戏后自动恢复。"
            );
        } else if (!connected) {
            gamePermissionState.setText(
                    "已授权，等待识别服务连接。进入游戏时暂停，离开后自动恢复。"
            );
        } else {
            gamePermissionState.setText("游戏识别已就绪。进入游戏时暂停，离开游戏后自动恢复。");
        }
        gamePermissionButton.setText(permissionGranted ? "管理授权" : "去授权");

        if (permissionGranted) {
            if (enabled && gamePauseEnabled) {
                permissionState.setText("已授权，可识别前台应用和游戏。");
            } else if (enabled) {
                permissionState.setText("已授权，可识别明确的前台应用。");
            } else if (gamePauseEnabled) {
                permissionState.setText("已授权，可识别游戏并在离开后自动恢复。");
            } else {
                permissionState.setText("已授权。当前所有应用均使用全局躺姿跟随。");
            }
            permissionState.setTextColor(palette.success);
        } else {
            if (enabled && gamePauseEnabled) {
                permissionState.setText(hasDirectionExceptions
                        ? "未授权。已保存方向例外暂保持系统方向，游戏时暂停也无法生效。"
                        : "未授权。未保存方向例外，外部应用仍使用全局躺姿跟随，游戏时暂停无法生效。");
            } else if (gamePauseEnabled) {
                permissionState.setText("未授权，游戏时暂停暂时无法生效。");
            } else if (enabled) {
                permissionState.setText(hasDirectionExceptions
                        ? "未授权。已保存方向例外暂保持系统方向。"
                        : "未授权。未保存方向例外，外部应用使用全局躺姿跟随。");
            } else {
                permissionState.setText("未授权。全局跟随不需要前台识别，启用方向例外或游戏暂停时可再授权。");
            }
            permissionState.setTextColor(recognitionNeeded ? palette.warm : palette.muted);
        }
        permissionButton.setText(permissionGranted ? "管理授权" : "去授权");
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager manager =
                (AccessibilityManager) getSystemService(ACCESSIBILITY_SERVICE);
        if (manager == null || !manager.isEnabled()) {
            return false;
        }

        ComponentName target = new ComponentName(this, AppRuleService.class);
        List<AccessibilityServiceInfo> services =
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        for (AccessibilityServiceInfo service : services) {
            ResolveInfo resolveInfo = service.getResolveInfo();
            ServiceInfo serviceInfo = resolveInfo == null ? null : resolveInfo.serviceInfo;
            if (serviceInfo == null) {
                continue;
            }
            ComponentName enabled = new ComponentName(
                    serviceInfo.packageName,
                    serviceInfo.name
            );
            if (target.equals(enabled)) {
                return true;
            }
        }
        return false;
    }

    private void loadLauncherApps() {
        PackageManager packageManager = getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> activities = packageManager.queryIntentActivities(launcher, 0);
        Set<String> seenPackages = new HashSet<>();

        for (ResolveInfo activity : activities) {
            if (activity.activityInfo == null
                    || !activity.activityInfo.enabled
                    || !seenPackages.add(activity.activityInfo.packageName)) {
                continue;
            }
            CharSequence loadedLabel = activity.loadLabel(packageManager);
            String label = loadedLabel == null
                    ? activity.activityInfo.packageName
                    : loadedLabel.toString();
            launcherApps.add(new LauncherApp(
                    activity.activityInfo.packageName,
                    label,
                    activity.loadIcon(packageManager)
            ));
        }

        final Collator collator = Collator.getInstance(Locale.CHINA);
        Collections.sort(launcherApps, new Comparator<LauncherApp>() {
            @Override
            public int compare(LauncherApp left, LauncherApp right) {
                int byLabel = collator.compare(left.label, right.label);
                return byLabel != 0 ? byLabel : left.packageName.compareTo(right.packageName);
            }
        });
    }

    private String appLabel(String packageName) {
        for (LauncherApp app : launcherApps) {
            if (app.packageName.equals(packageName)) {
                return app.label;
            }
        }
        return packageName;
    }

    private String modeLabel(int mode) {
        if (mode == AppRules.GAME_PAUSED) {
            return "游戏暂停";
        }
        if (mode == AppRules.FOLLOW || mode == AppRules.LANDSCAPE) {
            return MODE_LABELS[mode];
        }
        return MODE_LABELS[AppRules.PASS];
    }

    private void addEmptyNotice(String message) {
        TextView notice = text(message, 12f, palette.muted, Typeface.NORMAL);
        notice.setLineSpacing(dp(2), 1f);
        notice.setPadding(dp(12), dp(12), dp(12), dp(12));
        notice.setBackground(rounded(palette.inset, dp(10), 0, Color.TRANSPARENT));
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        appList.addView(notice, params);
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException exception) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (ActivityNotFoundException ignored) {
                Toast.makeText(this, "无法打开系统无障碍设置。", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackground(rounded(palette.surface, dp(18), dp(1), palette.line));
        card.setElevation(dp(isNightMode() ? 0 : 1));
        return card;
    }

    private TextView sectionTitle(String value) {
        TextView view = text(value, 18f, palette.ink, Typeface.BOLD);
        view.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return view;
    }

    private TextView text(String value, float size, int color, int style) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create("sans-serif", style));
        view.setIncludeFontPadding(false);
        return view;
    }

    private int modeSpinnerWidth(Spinner mode) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        paint.setTextSize(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                14f,
                getResources().getDisplayMetrics()
        ));
        float labelWidth = 0;
        for (String label : MODE_LABELS) {
            labelWidth = Math.max(labelWidth, paint.measureText(label));
        }
        int nativePadding = mode.getPaddingLeft() + mode.getPaddingRight();
        return Math.max(dp(136), (int) Math.ceil(labelWidth) + nativePadding + dp(8));
    }

    private Button addPermissionButton(LinearLayout row) {
        Button button = createButton("去授权");
        button.setSingleLine();
        button.setMinWidth(dp(104));
        button.setMinHeight(dp(40));
        button.setOnClickListener(view -> openAccessibilitySettings());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(dp(10));
        params.setMarginEnd(dp(10));
        row.addView(button, params);
        return button;
    }

    private Button createButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(13f);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));

        int pressedColor = palette.accentSoft;
        StateListDrawable states = new StateListDrawable();
        states.addState(
                new int[]{android.R.attr.state_pressed},
                rounded(pressedColor, dp(12), dp(1), palette.line)
        );
        states.addState(
                new int[]{},
                rounded(Color.TRANSPARENT, dp(12), dp(1), palette.line)
        );
        button.setBackground(states);
        button.setTextColor(palette.accentText);
        return button;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(1), dp(heightDp)));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private GradientDrawable rounded(int color, float radius, int strokeWidth, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        if (strokeWidth > 0) {
            drawable.setStroke(strokeWidth, strokeColor);
        }
        return drawable;
    }

    private boolean isNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(palette.background);
        window.setNavigationBarColor(palette.surface);

        int visibility = window.getDecorView().getSystemUiVisibility();
        if (isNightMode()) {
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            visibility &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        } else {
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            visibility |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        window.getDecorView().setSystemUiVisibility(visibility);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class ModeAdapter extends ArrayAdapter<String> {
        ModeAdapter() {
            super(RuleActivity.this, android.R.layout.simple_spinner_item, MODE_LABELS);
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getView(position, convertView, parent);
            styleModeItem(view, false);
            return view;
        }

        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            TextView view = (TextView) super.getDropDownView(position, convertView, parent);
            styleModeItem(view, true);
            return view;
        }

        private void styleModeItem(TextView view, boolean dropdown) {
            view.setTextColor(palette.ink);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            view.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            view.setIncludeFontPadding(false);
            view.setSingleLine(true);
            view.setEllipsize(null);
            view.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);

            if (dropdown) {
                view.setPadding(dp(14), dp(10), dp(14), dp(10));
                view.setMinimumHeight(dp(48));
                ViewGroup.LayoutParams params = view.getLayoutParams();
                if (params != null) {
                    params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                    view.setLayoutParams(params);
                }
            } else {
                view.setPaddingRelative(dp(4), dp(6), 0, dp(6));
            }
        }
    }

    private final class ModeSelectionListener implements AdapterView.OnItemSelectedListener {
        private final String packageName;
        private int savedMode;

        ModeSelectionListener(String packageName, int savedMode) {
            this.packageName = packageName;
            this.savedMode = savedMode;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
            if (position == savedMode) {
                return;
            }
            savedMode = position;
            AppRules.setModeForPackage(RuleActivity.this, packageName, position);
            refreshState();
            AppRules.notifyRotationService(RuleActivity.this);
        }

        @Override
        public void onNothingSelected(AdapterView<?> parent) {
        }
    }

    private static final class LauncherApp {
        final String packageName;
        final String label;
        final Drawable icon;

        LauncherApp(String packageName, String label, Drawable icon) {
            this.packageName = packageName;
            this.label = label;
            this.icon = icon;
        }
    }

    private static final class Palette {
        final int background;
        final int surface;
        final int inset;
        final int ink;
        final int muted;
        final int faint;
        final int line;
        final int accentSoft;
        final int accentText;
        final int warm;
        final int warmSoft;
        final int success;

        Palette(boolean night) {
            if (night) {
                background = 0xFF07111F;
                surface = 0xFF0E1A2B;
                inset = 0xFF132238;
                ink = 0xFFF5F8FC;
                muted = 0xFFA8B7CB;
                faint = 0xFF7E90A8;
                line = 0xFF263850;
                accentSoft = 0xFF152B4B;
                accentText = 0xFF8CB9FF;
                warm = 0xFFF0B25F;
                warmSoft = 0xFF2A2114;
                success = 0xFF66D7C6;
            } else {
                background = 0xFFF3F6FA;
                surface = 0xFFFFFFFF;
                inset = 0xFFF7F9FC;
                ink = 0xFF0F172A;
                muted = 0xFF475569;
                faint = 0xFF718096;
                line = 0xFFD7E0EC;
                accentSoft = 0xFFE8EEFF;
                accentText = 0xFF1E40AF;
                warm = 0xFFB45309;
                warmSoft = 0xFFFFF2D8;
                success = 0xFF0F766E;
            }
        }
    }
}
