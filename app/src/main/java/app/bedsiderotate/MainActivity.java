package app.bedsiderotate;

import android.Manifest;
import android.app.AlertDialog;
import android.app.StatusBarManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.StateListDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends BedsideActivity {
    private static final int REQUEST_POST_NOTIFICATIONS = 2301;
    private static final String APP_DISPLAY_NAME = "懒人翻身";
    private static final int MIN_THRESHOLD = 46;
    private static final int MAX_THRESHOLD = 85;
    private static final String PREF_THRESHOLD = "threshold";
    private static final String PREF_OVERLAY = "overlay";
    private static final String PREF_PILL = "pill";

    private SharedPreferences preferences;
    private Palette palette;
    private boolean nightMode;
    private boolean syncingControls;
    private boolean statusReceiverRegistered;
    private boolean pendingOverlayAuthorization;
    private boolean pendingPillAuthorization;
    private boolean hasStatus;
    private boolean resumed;
    private Boolean shownRunning;
    private int threshold;
    private boolean overlayEnabled;
    private boolean pillEnabled;
    private boolean tileEnabled;
    private boolean tileAdded;

    private PoseGaugeView gaugeView;
    private TextView serviceBadge;
    private TextView angleValue;
    private TextView modeValue;
    private TextView directionValue;
    private TextView serviceDetail;
    private TextView restoreHint;
    private TextView thresholdValue;
    private TextView writeState;
    private TextView notificationState;
    private TextView overlayState;
    private Button startButton;
    private Button manualStepButton;
    private Button calibrateButton;
    private Button writeSettingsButton;
    private Button notificationButton;
    private Button overlayPermissionButton;
    private Switch overlaySwitch;
    private Switch pillSwitch;
    private Switch hideFromRecentsSwitch;
    private Switch tileSwitch;
    private LinearLayout addTileRow;
    private TextView addTileState;
    private Button addTileButton;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !RotationService.STATUS.equals(intent.getAction())) {
                return;
            }

            hasStatus = true;
            String detail = intent.getStringExtra("detail");
            int rotation = intent.getIntExtra("rotation", 0);
            boolean bed = intent.getBooleanExtra("bed", false);
            float alpha = intent.getFloatExtra("alpha", 0f);
            int activityOrientation = intent.getIntExtra(
                    RotationService.EXTRA_ACTIVITY_ORIENTATION,
                    ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            );
            applyActivityOrientation(activityOrientation);
            renderStatus(detail, rotation, bed, alpha);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) {
            getActionBar().hide();
        }

        nightMode = isNightMode();
        palette = new Palette(nightMode);
        preferences = getSharedPreferences("bedside", MODE_PRIVATE);
        threshold = Math.max(MIN_THRESHOLD, Math.min(MAX_THRESHOLD,
                preferences.getInt(PREF_THRESHOLD, 55)));
        preferences.edit().putInt(PREF_THRESHOLD, threshold).apply();
        overlayEnabled = preferences.getBoolean(PREF_OVERLAY, false);
        pillEnabled = preferences.getBoolean(PREF_PILL, false);
        tileEnabled = isTileEnabled(this);
        tileAdded = isTileAdded(this);
        resumed = false;

        configureWindow();
        buildConsole();
        registerStatusReceiver();

        hasStatus = RotationService.lastStatus != null;
        String initialDetail = RotationService.lastStatus;
        if (initialDetail == null) {
            initialDetail = defaultServiceDetail();
        }
        serviceDetail.setText(initialDetail);
        updateServicePresentation();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        finishPendingVisualAuthorization();
        tileAdded = isTileAdded(this);
        refreshPermissionControls();

        if (!RotationService.running) {
            releaseActivityOrientation();
            boolean restored = RotationService.restorePending(this);
            restoreHint.setVisibility(restored ? View.GONE : View.VISIBLE);
        } else {
            restoreHint.setVisibility(View.GONE);
        }

        updateServicePresentation();
    }

    @Override
    protected void onPause() {
        resumed = false;
        super.onPause();
    }

    @Override
    protected void onStop() {
        resumed = false;
        if (!isChangingConfigurations()) {
            releaseActivityOrientation();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (statusReceiverRegistered) {
            unregisterReceiver(statusReceiver);
            statusReceiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_POST_NOTIFICATIONS) {
            return;
        }

        refreshPermissionControls();
        if (!notificationsAllowed()) {
            Toast.makeText(this, "通知未授权，懒人翻身仍可继续使用。", Toast.LENGTH_SHORT).show();
        }
    }

    private void buildConsole() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(palette.background);
        scrollView.setVerticalScrollBarEnabled(false);

        boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
        int horizontalPadding = dp(tablet ? 32 : 18);

        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setPadding(horizontalPadding, dp(26), horizontalPadding, dp(36));
        shell.setBackgroundColor(palette.background);

        shell.addView(createHeader(), matchWrap());
        shell.addView(createRule());

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(tablet ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        body.setGravity(Gravity.TOP);

        LinearLayout primaryColumn = new LinearLayout(this);
        primaryColumn.setOrientation(LinearLayout.VERTICAL);
        primaryColumn.addView(createControlsCard(), matchWrap());
        primaryColumn.addView(space(16));
        primaryColumn.addView(createGaugeCard(), matchWrap());

        LinearLayout settingsColumn = new LinearLayout(this);
        settingsColumn.setOrientation(LinearLayout.VERTICAL);
        settingsColumn.addView(createSensitivityCard(), matchWrap());
        settingsColumn.addView(space(16));
        settingsColumn.addView(createCapabilitiesCard(), matchWrap());

        LinearLayout.LayoutParams primaryParams = tablet
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.08f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams settingsParams = tablet
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.92f)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (tablet) {
            primaryParams.setMarginEnd(dp(16));
        } else {
            primaryParams.bottomMargin = dp(16);
        }

        body.addView(primaryColumn, primaryParams);
        body.addView(settingsColumn, settingsParams);
        shell.addView(body, matchWrap());

        scrollView.addView(shell, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scrollView);
    }

    private View createHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(2), 0, dp(14));

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_rotation);
        icon.setContentDescription(APP_DISPLAY_NAME + "图标");
        icon.setPadding(dp(9), dp(9), dp(9), dp(9));
        icon.setBackground(rounded(palette.accentSoft, dp(13), 0, Color.TRANSPARENT));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        iconParams.setMarginEnd(dp(12));
        header.addView(icon, iconParams);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(APP_DISPLAY_NAME, 28f, palette.ink, Typeface.BOLD);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLineSpacing(0f, 0.95f);
        titles.addView(title);
        header.addView(titles, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        serviceBadge = text("待启动", 12f, palette.muted, Typeface.BOLD);
        serviceBadge.setGravity(Gravity.CENTER);
        serviceBadge.setPadding(dp(12), dp(7), dp(12), dp(7));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        badgeParams.setMarginStart(dp(8));
        header.addView(serviceBadge, badgeParams);
        return header;
    }

    private View createGaugeCard() {
        LinearLayout card = createCard();
        card.addView(sectionTitle("实时姿态"), matchWrap());

        gaugeView = new PoseGaugeView(this);
        gaugeView.setColors(palette.track, palette.accent, palette.warm,
                palette.ink, palette.muted);
        LinearLayout.LayoutParams gaugeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(218));
        gaugeParams.topMargin = dp(10);
        card.addView(gaugeView, gaugeParams);

        LinearLayout metrics = new LinearLayout(this);
        metrics.setOrientation(LinearLayout.HORIZONTAL);
        metrics.setGravity(Gravity.TOP);
        LinearLayout angleMetric = createMetric("估计翻身角");
        angleValue = (TextView) angleMetric.getChildAt(1);
        angleValue.setText("—");
        LinearLayout modeMetric = createMetric("模式");
        modeValue = (TextView) modeMetric.getChildAt(1);
        modeValue.setText("—");
        LinearLayout directionMetric = createMetric("方向");
        directionValue = (TextView) directionMetric.getChildAt(1);
        directionValue.setText("—");

        metrics.addView(angleMetric, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        metrics.addView(verticalRule());
        metrics.addView(modeMetric, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        metrics.addView(verticalRule());
        metrics.addView(directionMetric, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams metricsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metricsParams.topMargin = dp(4);
        card.addView(metrics, metricsParams);

        serviceDetail = text(defaultServiceDetail(), 13f, palette.ink, Typeface.NORMAL);
        serviceDetail.setLineSpacing(dp(2), 1f);
        serviceDetail.setPadding(dp(14), dp(12), dp(14), dp(12));
        serviceDetail.setBackground(rounded(palette.inset, dp(12), dp(1), palette.line));
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = dp(14);
        card.addView(serviceDetail, detailParams);
        return card;
    }

    private View createControlsCard() {
        LinearLayout card = createCard();
        card.addView(sectionTitle("控制"), matchWrap());

        PermissionRow writeRow = createPermissionRow("修改系统设置", "授权",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        openWriteSettings();
                    }
                });
        writeState = writeRow.state;
        writeSettingsButton = writeRow.button;
        card.addView(writeRow.root, matchWrap());

        startButton = createButton("启动" + APP_DISPLAY_NAME, ButtonStyle.PRIMARY);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                handleStartStop();
            }
        });
        LinearLayout.LayoutParams startParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        startParams.topMargin = dp(8);
        card.addView(startButton, startParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        manualStepButton = createButton("手动 +90°", ButtonStyle.SECONDARY);
        manualStepButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runConnectedAction(RotationService.STEP);
            }
        });
        calibrateButton = createButton("绑定当前显示", ButtonStyle.SECONDARY);
        calibrateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                runConnectedAction(RotationService.CALIBRATE);
            }
        });

        LinearLayout.LayoutParams firstAction = new LinearLayout.LayoutParams(
                0, dp(50), 1f);
        firstAction.setMarginEnd(dp(5));
        LinearLayout.LayoutParams secondAction = new LinearLayout.LayoutParams(
                0, dp(50), 1f);
        secondAction.setMarginStart(dp(5));
        actions.addView(manualStepButton, firstAction);
        actions.addView(calibrateButton, secondAction);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(10);
        card.addView(actions, actionParams);

        PermissionRow traceRow = createPermissionRow("录制姿态数据", "打开",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        startActivity(new Intent(MainActivity.this, TraceActivity.class));
                    }
                });
        traceRow.state.setText("真机校准 · 记录传感器与姿态原始值");
        traceRow.state.setTextColor(palette.muted);
        LinearLayout.LayoutParams traceParams = matchWrap();
        traceParams.topMargin = dp(8);
        card.addView(traceRow.root, traceParams);

        restoreHint = text("遗留方向未能恢复。请重新授予“修改系统设置”，返回后会再次尝试。",
                12f, palette.warm, Typeface.NORMAL);
        restoreHint.setPadding(dp(12), dp(10), dp(12), dp(10));
        restoreHint.setBackground(rounded(palette.warmSoft, dp(10), 0, Color.TRANSPARENT));
        restoreHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(12);
        card.addView(restoreHint, hintParams);

        String flatExitLabel = "平放后自动回到桌面模式";
        LinearLayout flatExitRow = createSwitchRow(flatExitLabel,
                "卧床时，屏幕朝上放平约 1.5 秒后退出。关闭此项可在放下平板后保持卧床模式。");
        Switch flatExitSwitch = (Switch) flatExitRow.getChildAt(1);
        flatExitSwitch.setContentDescription(flatExitLabel);
        flatExitSwitch.setChecked(preferences.getBoolean(RotationService.PREF_AUTO_EXIT_BED_WHEN_FLAT, true));
        flatExitSwitch.setOnCheckedChangeListener((button, checked) -> {
            preferences.edit().putBoolean(RotationService.PREF_AUTO_EXIT_BED_WHEN_FLAT, checked).apply();
            notifyServiceOptions();
        });
        card.addView(flatExitRow, matchWrap());

        TextView note = text("已侧卧时，点「绑定当前显示」进入卧床模式。",
                11f, palette.faint, Typeface.NORMAL);
        note.setLineSpacing(dp(1), 1f);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        noteParams.topMargin = dp(12);
        card.addView(note, noteParams);
        return card;
    }

    private View createSensitivityCard() {
        LinearLayout card = createCard();
        LinearLayout heading = new LinearLayout(this);
        heading.setOrientation(LinearLayout.HORIZONTAL);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(sectionTitle("转腕灵敏度"), new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        thresholdValue = text(formatThreshold(threshold), 19f, palette.accentText,
                Typeface.BOLD);
        thresholdValue.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        heading.addView(thresholdValue);
        card.addView(heading, matchWrap());

        TextView explanation = text("绕屏幕法线的主动转腕角可调 46°–85°。默认 55°，调整后立即保存。",
                12f, palette.muted, Typeface.NORMAL);
        explanation.setPadding(0, dp(5), 0, dp(8));
        card.addView(explanation, matchWrap());

        SeekBar seekBar = new SeekBar(this);
        seekBar.setMin(MIN_THRESHOLD);
        seekBar.setMax(MAX_THRESHOLD);
        seekBar.setProgress(threshold);
        seekBar.setSplitTrack(false);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(palette.accent));
        seekBar.setContentDescription("主动转腕阈值");
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                threshold = progress;
                thresholdValue.setText(formatThreshold(threshold));
                if (fromUser) {
                    preferences.edit().putInt(PREF_THRESHOLD, threshold).apply();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                notifyServiceOptions();
            }
        });
        card.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44)));

        LinearLayout scale = new LinearLayout(this);
        scale.setOrientation(LinearLayout.HORIZONTAL);
        TextView low = text("46° 更灵敏", 10f, palette.faint, Typeface.NORMAL);
        TextView high = text("85° 更稳定", 10f, palette.faint, Typeface.NORMAL);
        high.setGravity(Gravity.END);
        scale.addView(low, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        scale.addView(high, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(scale, matchWrap());
        return card;
    }

    private View createCapabilitiesCard() {
        LinearLayout card = createCard();
        card.addView(sectionTitle("授权与可选能力"), matchWrap());

        TextView intro = text("方向覆盖、边缘转屏按钮和通知均为可选，不开启也能维持基础方向控制。",
                12f, palette.muted, Typeface.NORMAL);
        intro.setPadding(0, dp(5), 0, dp(12));
        card.addView(intro, matchWrap());

        LinearLayout overlayRow = createSwitchRow("方向覆盖",
                "尝试让当前应用采用指定方向");
        overlaySwitch = (Switch) overlayRow.getChildAt(1);
        overlaySwitch.setChecked(overlayEnabled);
        overlaySwitch.setContentDescription("方向覆盖");
        overlaySwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (syncingControls) {
                    return;
                }
                handleOverlayToggle(checked);
            }
        });
        card.addView(overlayRow, matchWrap());

        LinearLayout pillRow = createSwitchRow("边缘转屏按钮",
                "点一下，尝试让当前应用方向前进90°");
        pillSwitch = (Switch) pillRow.getChildAt(1);
        pillSwitch.setChecked(pillEnabled);
        pillSwitch.setContentDescription("边缘转屏按钮");
        pillSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                if (syncingControls) {
                    return;
                }
                handlePillToggle(checked);
            }
        });
        LinearLayout.LayoutParams pillParams = matchWrap();
        pillParams.topMargin = dp(2);
        card.addView(pillRow, pillParams);

        card.addView(insetRule(), matchOneWithTopMargin(14));
        LinearLayout hideFromRecentsRow = createSwitchRow("在最近任务中隐藏",
                "隐藏后仍可从桌面或应用的磁贴、通知入口打开");
        hideFromRecentsSwitch = (Switch) hideFromRecentsRow.getChildAt(1);
        hideFromRecentsSwitch.setChecked(preferences.getBoolean(
                PREF_HIDE_FROM_RECENTS, false));
        hideFromRecentsSwitch.setContentDescription("在最近任务中隐藏");
        hideFromRecentsSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingControls) {
                return;
            }
            preferences.edit().putBoolean(PREF_HIDE_FROM_RECENTS, checked).apply();
            applyRecentsVisibility();
        });
        card.addView(hideFromRecentsRow, matchWrap());

        LinearLayout tileRow = createSwitchRow("快捷设置磁贴",
                "关闭后已添加的磁贴不再启动或停止服务");
        tileSwitch = (Switch) tileRow.getChildAt(1);
        tileSwitch.setChecked(tileEnabled);
        tileSwitch.setContentDescription("快捷设置磁贴");
        tileSwitch.setOnCheckedChangeListener((button, checked) -> {
            if (syncingControls) {
                return;
            }
            handleTileToggle(checked);
        });
        LinearLayout.LayoutParams tileParams = matchWrap();
        tileParams.topMargin = dp(2);
        card.addView(tileRow, tileParams);

        PermissionRow addTilePermissionRow = createPermissionRow(
                "添加快捷设置磁贴", "添加", new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        requestAddTile();
                    }
                });
        addTileRow = addTilePermissionRow.root;
        addTileState = addTilePermissionRow.state;
        addTileButton = addTilePermissionRow.button;
        LinearLayout.LayoutParams addTileParams = matchWrap();
        addTileParams.topMargin = dp(2);
        card.addView(addTileRow, addTileParams);

        card.addView(insetRule(), matchOneWithTopMargin(14));
        PermissionRow rulesRow = createPermissionRow("游戏暂停与分应用规则", "管理",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        startActivity(new Intent(MainActivity.this, RuleActivity.class));
                    }
                });
        rulesRow.state.setText("识别到游戏时暂停懒人翻身，离开后自动恢复");
        rulesRow.state.setTextColor(palette.muted);
        card.addView(rulesRow.root, matchWrap());

        card.addView(insetRule(), matchOneWithTopMargin(10));
        TextView systemHeading = text("系统权限", 13f, palette.ink, Typeface.BOLD);
        LinearLayout.LayoutParams systemHeadingParams = matchWrap();
        systemHeadingParams.topMargin = dp(10);
        systemHeadingParams.bottomMargin = dp(4);
        card.addView(systemHeading, systemHeadingParams);

        card.addView(insetRule(), matchOne());
        PermissionRow notificationRow = createPermissionRow("通知权限", "允许",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        requestNotificationPermission();
                    }
                });
        notificationState = notificationRow.state;
        notificationButton = notificationRow.button;
        card.addView(notificationRow.root, matchWrap());

        card.addView(insetRule(), matchOne());
        PermissionRow overlayPermissionRow = createPermissionRow("悬浮层权限", "设置",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        openOverlaySettings();
                    }
                });
        overlayState = overlayPermissionRow.state;
        overlayPermissionButton = overlayPermissionRow.button;
        card.addView(overlayPermissionRow.root, matchWrap());

        card.addView(insetRule(), matchOneWithTopMargin(10));
        PermissionRow aboutRow = createPermissionRow("关于", "打开",
                new View.OnClickListener() {
                    @Override
                    public void onClick(View view) {
                        startActivity(new Intent(MainActivity.this, AboutActivity.class));
                    }
                });
        aboutRow.state.setText("GitHub 项目与开源许可证");
        aboutRow.state.setTextColor(palette.muted);
        card.addView(aboutRow.root, matchWrap());

        refreshPermissionControls();
        return card;
    }

    private LinearLayout createMetric(String label) {
        LinearLayout metric = new LinearLayout(this);
        metric.setOrientation(LinearLayout.VERTICAL);
        metric.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView labelView = text(label, 10f, palette.faint, Typeface.NORMAL);
        labelView.setLetterSpacing(0.04f);
        TextView valueView = text("—", 17f, palette.ink, Typeface.BOLD);
        valueView.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        valueView.setGravity(Gravity.CENTER);
        valueView.setPadding(0, dp(3), 0, 0);
        metric.addView(labelView);
        metric.addView(valueView);
        return metric;
    }

    private LinearLayout createSwitchRow(String title, String description) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(8), 0, dp(8));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 14f, palette.ink, Typeface.BOLD);
        TextView descriptionView = text(description, 11f, palette.muted, Typeface.NORMAL);
        descriptionView.setPadding(0, dp(2), dp(10), 0);
        copy.addView(titleView);
        copy.addView(descriptionView);
        row.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setShowText(false);
        toggle.setMinWidth(dp(48));
        int[][] tintStates = new int[][]{
                {android.R.attr.state_enabled, android.R.attr.state_checked},
                {android.R.attr.state_enabled},
                {}
        };
        toggle.setThumbTintList(new android.content.res.ColorStateList(tintStates,
                new int[]{palette.accent, palette.muted, palette.faint}));
        toggle.setTrackTintList(new android.content.res.ColorStateList(tintStates,
                new int[]{palette.accentSoft, palette.track, palette.track}));
        row.addView(toggle);
        return row;
    }

    private PermissionRow createPermissionRow(String title, String actionText,
                                              View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(7), 0, dp(7));

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = text(title, 13f, palette.ink, Typeface.BOLD);
        TextView stateView = text("检查中", 11f, palette.muted, Typeface.NORMAL);
        stateView.setPadding(0, dp(1), dp(8), 0);
        copy.addView(titleView);
        copy.addView(stateView);
        row.addView(copy, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button button = createButton(actionText, ButtonStyle.GHOST);
        button.setOnClickListener(listener);
        row.addView(button, new LinearLayout.LayoutParams(dp(78), dp(40)));
        return new PermissionRow(row, stateView, button);
    }

    private void handleStartStop() {
        if (RotationService.running) {
            sendServiceAction(RotationService.STOP);
            return;
        }
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "先授予“修改系统设置”权限。", Toast.LENGTH_SHORT).show();
            openWriteSettings();
            return;
        }
        sendServiceAction(RotationService.START);
    }

    private void runConnectedAction(String action) {
        if (!RotationService.running) {
            Toast.makeText(this, "请先启动懒人翻身服务。", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "修改系统设置权限已失效，请重新授权。", Toast.LENGTH_SHORT).show();
            restoreHint.setVisibility(View.VISIBLE);
            return;
        }
        sendServiceAction(action);
    }

    private void handleOverlayToggle(boolean checked) {
        if (checked && !Settings.canDrawOverlays(this)) {
            pendingOverlayAuthorization = true;
            syncOverlaySwitch(false);
            Toast.makeText(this, "请在系统页面允许懒人翻身显示在其他应用上层。",
                    Toast.LENGTH_SHORT).show();
            openOverlaySettings();
            return;
        }

        overlayEnabled = checked;
        preferences.edit().putBoolean(PREF_OVERLAY, overlayEnabled).apply();
        notifyServiceOptions();
    }

    private void handlePillToggle(boolean checked) {
        if (checked && !Settings.canDrawOverlays(this)) {
            pendingPillAuthorization = true;
            syncPillControl();
            Toast.makeText(this, "请在系统页面允许懒人翻身显示在其他应用上层。",
                    Toast.LENGTH_SHORT).show();
            openOverlaySettings();
            return;
        }

        pillEnabled = checked;
        preferences.edit().putBoolean(PREF_PILL, pillEnabled).apply();
        notifyServiceOptions();
    }

    private void finishPendingVisualAuthorization() {
        if (!pendingOverlayAuthorization && !pendingPillAuthorization) {
            return;
        }

        boolean enablingOverlay = pendingOverlayAuthorization;
        boolean enablingPill = pendingPillAuthorization;
        boolean canOverlay = Settings.canDrawOverlays(this);
        if (enablingOverlay) {
            overlayEnabled = canOverlay;
            preferences.edit().putBoolean(PREF_OVERLAY, overlayEnabled).apply();
        }
        if (enablingPill) {
            pillEnabled = canOverlay;
            preferences.edit().putBoolean(PREF_PILL, pillEnabled).apply();
        }
        pendingOverlayAuthorization = false;
        pendingPillAuthorization = false;

        if (canOverlay) {
            String message = enablingOverlay && enablingPill
                    ? "方向覆盖与边缘转屏按钮已启用。"
                    : (enablingOverlay ? "方向覆盖已启用。" : "边缘转屏按钮已启用。");
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            notifyServiceOptions();
        } else {
            Toast.makeText(this, "未授予悬浮层权限，可选显示保持关闭。",
                    Toast.LENGTH_SHORT).show();
        }
        syncOverlaySwitch(overlayEnabled);
        syncPillControl();
    }

    private void refreshPermissionControls() {
        if (writeState == null) {
            return;
        }

        boolean canWrite = Settings.System.canWrite(this);
        writeState.setText(canWrite ? "已允许修改显示方向" : "尚未授权，启动前需要开启");
        writeState.setTextColor(canWrite ? palette.success : palette.warm);
        writeSettingsButton.setText(canWrite ? "管理" : "授权");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean notificationAllowed = notificationsAllowed();
            notificationState.setText(notificationAllowed ? "已允许运行通知" : "可选，不影响基础方向控制");
            notificationState.setTextColor(notificationAllowed ? palette.success : palette.muted);
            notificationButton.setText(notificationAllowed ? "已允许" : "允许");
            notificationButton.setEnabled(!notificationAllowed);
        } else {
            notificationState.setText("当前系统无需额外通知授权");
            notificationState.setTextColor(palette.success);
            notificationButton.setText("无需设置");
            notificationButton.setEnabled(false);
        }

        boolean canOverlay = Settings.canDrawOverlays(this);
        overlayState.setText(canOverlay
                ? "已允许方向覆盖与边缘转屏按钮"
                : "可选，方向覆盖与边缘转屏按钮共用此权限");
        overlayState.setTextColor(canOverlay ? palette.success : palette.muted);
        overlayPermissionButton.setText(canOverlay ? "管理" : "设置");

        syncOverlaySwitch(overlayEnabled);
        syncPillControl();
        syncFeatureSwitches();
        refreshAddTileEntry();
    }

    private void updateServicePresentation() {
        boolean running = RotationService.running;
        if (shownRunning != null && shownRunning == running) {
            return;
        }
        shownRunning = running;

        serviceBadge.setText(running ? "运行中" : "待启动");
        serviceBadge.setTextColor(running ? palette.success : palette.muted);
        serviceBadge.setBackground(rounded(running ? palette.successSoft : palette.inset,
                dp(20), running ? 0 : dp(1), running ? Color.TRANSPARENT : palette.line));

        startButton.setText(running ? "停止" + APP_DISPLAY_NAME : "启动" + APP_DISPLAY_NAME);
        styleButton(startButton, running ? ButtonStyle.WARM : ButtonStyle.PRIMARY);
        manualStepButton.setEnabled(running);
        calibrateButton.setEnabled(running);
        manualStepButton.setAlpha(running ? 1f : 0.45f);
        calibrateButton.setAlpha(running ? 1f : 0.45f);

        if (running) {
            restoreHint.setVisibility(View.GONE);
        }
        if (!hasStatus && !running) {
            serviceDetail.setText(defaultServiceDetail());
        }

        overlaySwitch.setEnabled(true);
        pillSwitch.setEnabled(true);
    }

    private void renderStatus(String detail, int rotation, boolean bed, float alpha) {
        gaugeView.setStatus(rotation, bed, alpha);
        angleValue.setText(bed ? formatDegrees(alpha) : "—");
        modeValue.setText(bed ? "卧床" : "桌面");

        String direction;
        if (bed) {
            direction = Math.abs(alpha) < 3f
                    ? "居中"
                    : (alpha > 0f ? "右倾" : "左倾");
        } else {
            int normalizedRotation = ((rotation % 4) + 4) % 4;
            direction = String.format(Locale.CHINA, "自然 %d°", normalizedRotation * 90);
        }
        directionValue.setText(direction);

        if (detail == null || detail.trim().isEmpty()) {
            serviceDetail.setText(defaultServiceDetail());
        } else {
            serviceDetail.setText(detail);
        }
        updateServicePresentation();
    }

    private void applyActivityOrientation(int orientation) {
        if (!RotationService.running
                || orientation == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            releaseActivityOrientation();
            return;
        }
        if (!resumed || getRequestedOrientation() == orientation) {
            return;
        }
        setRequestedOrientation(orientation);
    }

    private void releaseActivityOrientation() {
        if (getRequestedOrientation() == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) {
            return;
        }
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void sendServiceAction(String action) {
        Intent intent = new Intent(this, RotationService.class);
        intent.setAction(action);
        if (RotationService.START.equals(action)) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
    }

    private void notifyServiceOptions() {
        if (RotationService.running) {
            sendServiceAction(RotationService.OPTIONS);
        }
    }

    private void handleTileToggle(boolean checked) {
        tileEnabled = checked;
        preferences.edit().putBoolean(PREF_TILE_ENABLED, checked).apply();
        TileService.requestListeningState(this, new ComponentName(this, BedTile.class));
        refreshAddTileEntry();
        if (checked) {
            requestAddTile();
        }
    }

    private void requestAddTile() {
        if (!tileEnabled) {
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            showManualTileInstructions();
            return;
        }

        StatusBarManager statusBarManager = getSystemService(StatusBarManager.class);
        if (statusBarManager == null) {
            showManualTileInstructions();
            return;
        }
        statusBarManager.requestAddTileService(
                new ComponentName(this, BedTile.class),
                APP_DISPLAY_NAME,
                Icon.createWithResource(this, R.drawable.ic_rotation),
                getMainExecutor(),
                result -> {
                    boolean added = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED
                            || result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED;
                    if (added) {
                        preferences.edit().putBoolean(PREF_TILE_ADDED, true).apply();
                        tileAdded = true;
                        Toast.makeText(this, "快捷设置磁贴已就绪。",
                                Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "未添加磁贴，可从快捷设置编辑页手动添加。",
                                Toast.LENGTH_SHORT).show();
                    }
                    refreshAddTileEntry();
                });
    }

    private void showManualTileInstructions() {
        new AlertDialog.Builder(this)
                .setTitle("添加“懒人翻身”磁贴")
                .setMessage("下拉打开快捷设置，点编辑按钮，在可用磁贴中找到“懒人翻身”，"
                        + "再拖到快捷设置区域。")
                .setPositiveButton("知道了", null)
                .show();
    }

    private void refreshAddTileEntry() {
        if (addTileRow == null) {
            return;
        }

        boolean show = tileEnabled && !tileAdded;
        addTileRow.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addTileState.setText("尚未添加，点右侧邀请系统添加");
            addTileButton.setText("添加");
        } else {
            addTileState.setText("尚未添加，点右侧查看手动添加方法");
            addTileButton.setText("说明");
        }
    }

    private void openWriteSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:" + getPackageName()));
        launchSettings(intent, Settings.ACTION_SETTINGS);
    }

    private void openOverlaySettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        launchSettings(intent, Settings.ACTION_SETTINGS);
    }

    private void launchSettings(Intent preferred, String fallbackAction) {
        try {
            startActivity(preferred);
        } catch (ActivityNotFoundException exception) {
            startActivity(new Intent(fallbackAction));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || notificationsAllowed()) {
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                REQUEST_POST_NOTIFICATIONS);
    }

    private boolean notificationsAllowed() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void registerStatusReceiver() {
        IntentFilter filter = new IntentFilter(RotationService.STATUS);
        int receiverFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Context.RECEIVER_NOT_EXPORTED
                : 0;
        registerReceiver(statusReceiver, filter, receiverFlags);
        statusReceiverRegistered = true;
    }

    private void syncOverlaySwitch(boolean checked) {
        syncingControls = true;
        overlaySwitch.setChecked(checked);
        syncingControls = false;
    }

    private void syncPillControl() {
        syncingControls = true;
        pillSwitch.setChecked(pillEnabled);
        pillSwitch.setEnabled(true);
        syncingControls = false;
    }

    private void syncFeatureSwitches() {
        syncingControls = true;
        hideFromRecentsSwitch.setChecked(preferences.getBoolean(
                PREF_HIDE_FROM_RECENTS, false));
        tileSwitch.setChecked(tileEnabled);
        syncingControls = false;
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

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(20), dp(20), dp(20), dp(20));
        card.setBackground(rounded(palette.surface, dp(18), dp(1), palette.line));
        card.setElevation(dp(nightMode ? 0 : 1));
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

    private Button createButton(String label, ButtonStyle style) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(14f);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setStateListAnimator(null);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        styleButton(button, style);
        return button;
    }

    private void styleButton(Button button, ButtonStyle style) {
        int normalColor;
        int pressedColor;
        int textColor;
        int strokeColor = Color.TRANSPARENT;
        int strokeWidth = 0;

        switch (style) {
            case PRIMARY:
                normalColor = palette.accent;
                pressedColor = palette.accentPressed;
                textColor = palette.onAccent;
                break;
            case WARM:
                normalColor = palette.warm;
                pressedColor = palette.warmPressed;
                textColor = palette.onWarm;
                break;
            case SECONDARY:
                normalColor = palette.inset;
                pressedColor = palette.pressed;
                textColor = palette.ink;
                strokeColor = palette.line;
                strokeWidth = dp(1);
                break;
            case GHOST:
            default:
                normalColor = Color.TRANSPARENT;
                pressedColor = palette.accentSoft;
                textColor = palette.accentText;
                strokeColor = palette.line;
                strokeWidth = dp(1);
                break;
        }

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed},
                rounded(pressedColor, dp(12), strokeWidth, strokeColor));
        states.addState(new int[]{android.R.attr.state_enabled},
                rounded(normalColor, dp(12), strokeWidth, strokeColor));
        states.addState(new int[]{}, rounded(palette.inset, dp(12), strokeWidth, palette.line));
        button.setBackground(states);
        button.setTextColor(textColor);
    }

    private View createRule() {
        View view = new View(this);
        view.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        params.bottomMargin = dp(14);
        view.setLayoutParams(params);
        return view;
    }

    private View insetRule() {
        View view = new View(this);
        view.setBackgroundColor(palette.line);
        view.setLayoutParams(matchOne());
        return view;
    }

    private View verticalRule() {
        View view = new View(this);
        view.setBackgroundColor(palette.line);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(1), dp(34));
        params.topMargin = dp(2);
        view.setLayoutParams(params);
        return view;
    }

    private View space(int heightDp) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(1), dp(heightDp)));
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchOne() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
    }

    private LinearLayout.LayoutParams matchOneWithTopMargin(int topMarginDp) {
        LinearLayout.LayoutParams params = matchOne();
        params.topMargin = dp(topMarginDp);
        return params;
    }

    private String formatThreshold(int value) {
        return String.format(Locale.CHINA, "%d°", value);
    }

    private String formatDegrees(float degrees) {
        int rounded = Math.round(degrees);
        return rounded > 0
                ? String.format(Locale.CHINA, "+%d°", rounded)
                : String.format(Locale.CHINA, "%d°", rounded);
    }

    private String defaultServiceDetail() {
        return RotationService.running
                ? "服务运行中，等待姿态广播。"
                : "服务未启动。授予修改系统设置后即可开始。";
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
        if (nightMode) {
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

    private static final class PermissionRow {
        final LinearLayout root;
        final TextView state;
        final Button button;

        PermissionRow(LinearLayout root, TextView state, Button button) {
            this.root = root;
            this.state = state;
            this.button = button;
        }
    }

    private enum ButtonStyle {
        PRIMARY,
        SECONDARY,
        WARM,
        GHOST
    }

    private static final class Palette {
        final int background;
        final int surface;
        final int inset;
        final int pressed;
        final int ink;
        final int muted;
        final int faint;
        final int line;
        final int track;
        final int accent;
        final int accentPressed;
        final int accentSoft;
        final int accentText;
        final int onAccent;
        final int warm;
        final int warmPressed;
        final int warmSoft;
        final int onWarm;
        final int success;
        final int successSoft;

        Palette(boolean night) {
            if (night) {
                background = 0xFF07111F;
                surface = 0xFF0E1A2B;
                inset = 0xFF132238;
                pressed = 0xFF1A2C46;
                ink = 0xFFF5F8FC;
                muted = 0xFFA8B7CB;
                faint = 0xFF7E90A8;
                line = 0xFF263850;
                track = 0xFF2B405D;
                accent = 0xFF4F8DFF;
                accentPressed = 0xFF3977E8;
                accentSoft = 0xFF152B4B;
                accentText = 0xFF8CB9FF;
                onAccent = 0xFF07111F;
                warm = 0xFFF0B25F;
                warmPressed = 0xFFD99B48;
                warmSoft = 0xFF2A2114;
                onWarm = 0xFF1A1207;
                success = 0xFF66D7C6;
                successSoft = 0xFF102D2B;
            } else {
                background = 0xFFF3F6FA;
                surface = 0xFFFFFFFF;
                inset = 0xFFF7F9FC;
                pressed = 0xFFEAF0F8;
                ink = 0xFF0F172A;
                muted = 0xFF475569;
                faint = 0xFF718096;
                line = 0xFFD7E0EC;
                track = 0xFFE2E8F0;
                accent = 0xFF1E40AF;
                accentPressed = 0xFF19378F;
                accentSoft = 0xFFE8EEFF;
                accentText = 0xFF1E40AF;
                onAccent = 0xFFFFFFFF;
                warm = 0xFFB45309;
                warmPressed = 0xFF934207;
                warmSoft = 0xFFFFF2D8;
                onWarm = 0xFFFFFFFF;
                success = 0xFF0F766E;
                successSoft = 0xFFE2F5F1;
            }
        }
    }
}
