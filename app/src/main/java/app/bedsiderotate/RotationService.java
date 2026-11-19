package app.bedsiderotate;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.drawable.Icon;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.view.Surface;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RotationService extends Service implements SensorEventListener {
    private static final String ACTION_PREFIX = "app.bedsiderotate.action.";

    public static final String START = ACTION_PREFIX + "START";
    public static final String STOP = ACTION_PREFIX + "STOP";
    public static final String STEP = ACTION_PREFIX + "STEP";
    public static final String CALIBRATE = ACTION_PREFIX + "CALIBRATE";
    public static final String OPTIONS = ACTION_PREFIX + "OPTIONS";
    public static final String STATUS = ACTION_PREFIX + "STATUS";

    public static final String EXTRA_DETAIL = "detail";
    public static final String EXTRA_ROTATION = "rotation";
    public static final String EXTRA_DISPLAY_ROTATION = "display_rotation";
    public static final String EXTRA_ACTIVITY_ORIENTATION = "activity_orientation";
    public static final String EXTRA_BED = "bed";
    public static final String EXTRA_ALPHA = "alpha";

    public static volatile boolean running;
    public static volatile String lastStatus = "服务未运行";

    private static final String PREFS_NAME = "bedside";
    private static final String PREF_THRESHOLD = "threshold";
    static final String PREF_AUTO_EXIT_BED_WHEN_FLAT = "auto_exit_bed_when_flat";
    private static final String PREF_OVERLAY = "overlay";
    private static final String PREF_PILL = "pill";
    private static final String PREF_RESTORE_PENDING = "restore_pending";
    private static final String PREF_ORIGINAL_AUTO_ROTATE = "original_auto_rotate";
    private static final String PREF_ORIGINAL_USER_ROTATION = "original_user_rotation";
    private static final String PREF_ACTIVE_ROTATION = "active_rotation";

    private static final String CHANNEL_ID = "bedside_rotation";
    private static final int NOTIFICATION_ID = 0x0BD5;
    private static final long STATUS_INTERVAL_MS = 250L;
    private static final int SENSOR_PERIOD_US = 20_000;
    private static final float LOW_PASS_WEIGHT = 0.82f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float[] lowPassGravity = new float[3];
    private final float[] rotationQuaternion = new float[4];

    private SharedPreferences preferences;
    private NotificationManager notificationManager;
    private SensorManager sensorManager;
    private PowerManager powerManager;
    private OrientationEngine engine;
    private BedsideOverlayController overlayController;

    private boolean foregroundStarted;
    private boolean controlStarted;
    private boolean screenReceiverRegistered;
    private boolean sensorsRegistered;
    private boolean statusUpdatesStarted;
    private boolean lowPassInitialized;
    private boolean gravityFallback;
    private boolean rotationFallback;
    private String sensorIssue = "";
    private String settingsIssue = "";
    private int lastAppliedRotation = -1;
    private long mismatchSinceMs;
    private boolean retriedDisplay;
    private int lastRotation = Surface.ROTATION_0;
    private boolean lastBed;
    private float lastAlpha;
    private int appRuleMode = AppRules.FOLLOW;

    private final Runnable statusRunnable = new Runnable() {
        @Override
        public void run() {
            publishStatus();
            if (running && !isGamePaused() && powerManager.isInteractive()) {
                mainHandler.postDelayed(this, STATUS_INTERVAL_MS);
            } else {
                statusUpdatesStarted = false;
            }
        }
    };

    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                unregisterSensors();
                stopStatusUpdates();
                publishStatus();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (isGamePaused()) {
                    publishStatus();
                    return;
                }
                registerSensors();
                startStatusUpdates();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? START : intent.getAction();
        if (STOP.equals(action)) {
            stopBedside();
            return START_NOT_STICKY;
        }

        if (!running) {
            if (!START.equals(action)) {
                stopSelf(startId);
                return START_NOT_STICKY;
            }
            startBedside();
        } else if (START.equals(action)) {
            refreshPreferences();
        }

        if (engine == null) {
            return START_STICKY;
        }

        if (STEP.equals(action)) {
            if (!isGamePaused()) {
                engine.manualStep();
                applyEngineRotation(true);
            }
        } else if (CALIBRATE.equals(action)) {
            if (!isGamePaused()) {
                int rotation = intent != null && intent.hasExtra(EXTRA_ROTATION)
                        ? normalizeRotation(intent.getIntExtra(EXTRA_ROTATION, engine.rotation()))
                        : currentDisplayRotation();
                engine.calibrate(rotation);
                applyEngineRotation(true);
            }
        } else if (OPTIONS.equals(action)) {
            refreshPreferences();
        }

        publishStatus();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        boolean wasRunning = running;
        unregisterSensors();
        unregisterScreenReceiver();
        stopStatusUpdates();

        if (controlStarted || preferences.getBoolean(PREF_RESTORE_PENDING, false)) {
            restoreOwnedSettings();
        }

        if (overlayController != null) {
            overlayController.removeAll();
        }
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }

        running = false;
        MotionTrace.finish();
        if (wasRunning) {
            requestTileListeningState();
            sendStoppedStatus();
        }
        super.onDestroy();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (engine == null) {
            return;
        }

        int type = event.sensor.getType();
        if (type == Sensor.TYPE_GRAVITY) {
            sendGravity(event);
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            updateLowPassGravity(event);
            engine.gravity(lowPassGravity[0], lowPassGravity[1], lowPassGravity[2], event.timestamp);
            applyEngineRotation(false);
        } else if (type == Sensor.TYPE_GAME_ROTATION_VECTOR || type == Sensor.TYPE_ROTATION_VECTOR) {
            SensorManager.getQuaternionFromVector(rotationQuaternion, event.values);
            engine.quaternion(
                    rotationQuaternion[0],
                    rotationQuaternion[1],
                    rotationQuaternion[2],
                    rotationQuaternion[3],
                    event.timestamp
            );
            applyEngineRotation(false);
        }
        if (MotionTrace.recording) {
            int request = isManagedMode() && controlStarted ? requestedRotation() : -1;
            MotionTrace.sample(event, engine, request, currentDisplayRotation(), appRuleMode,
                    getContentResolver());
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    public static boolean restorePending(Context context) {
        if (running) {
            return true;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.getBoolean(PREF_RESTORE_PENDING, false)) {
            return true;
        }

        if (!Settings.System.canWrite(context)) {
            lastStatus = "有待恢复的系统方向设置，但缺少修改系统设置权限";
            return false;
        }

        boolean restored = restoreStoredSettings(context, prefs);
        if (restored) {
            clearPendingRestore(prefs);
            lastStatus = "已恢复上次异常退出前的系统方向设置";
        } else {
            lastStatus = "恢复系统方向设置失败，请检查修改系统设置权限";
        }
        return restored;
    }

    private void startBedside() {
        int threshold = readThreshold();
        int initialRotation = initialRotation();
        engine = new OrientationEngine(initialRotation, threshold);
        overlayController = new BedsideOverlayController(this, new Runnable() {
            @Override
            public void run() {
                manualStep();
            }
        });

        startForeground(NOTIFICATION_ID, buildNotification("正在启动床头方向接管"));
        foregroundStarted = true;
        running = true;

        registerScreenReceiver();
        refreshPreferences();
        requestTileListeningState();

        if (!isGamePaused() && powerManager.isInteractive()) {
            registerSensors();
            startStatusUpdates();
        }
    }

    private void manualStep() {
        if (engine == null || isGamePaused()) {
            return;
        }
        engine.manualStep();
        applyEngineRotation(true);
        publishStatus();
    }

    private void refreshPreferences() {
        boolean wasGamePaused = isGamePaused();
        refreshAppRuleMode();
        if (engine != null) {
            engine.setThreshold(readThreshold());
            engine.setAutoExitBedWhenFlat(preferences.getBoolean(PREF_AUTO_EXIT_BED_WHEN_FLAT, true));
        }

        if (isGamePaused()) {
            updateNotification();
            return;
        }

        if (isManagedMode()) {
            beginSystemControl();
        }
        if (engine != null) {
            applyEngineRotation(false);
        }
        refreshOverlayOptions();
        if (wasGamePaused && powerManager.isInteractive()) {
            registerSensors();
            startStatusUpdates();
        }
        updateNotification();
    }

    private void beginSystemControl() {
        if (!isManagedMode()) {
            return;
        }
        if (controlStarted) {
            return;
        }
        if (!Settings.System.canWrite(this)) {
            settingsIssue = "缺少修改系统设置权限";
            return;
        }

        if (!preferences.getBoolean(PREF_RESTORE_PENDING, false)) {
            int autoRotate = Settings.System.getInt(
                    getContentResolver(),
                    Settings.System.ACCELEROMETER_ROTATION,
                    1
            );
            int userRotation = Settings.System.getInt(
                    getContentResolver(),
                    Settings.System.USER_ROTATION,
                    engine.rotation()
            );
            boolean saved = preferences.edit()
                    .putBoolean(PREF_RESTORE_PENDING, true)
                    .putInt(PREF_ORIGINAL_AUTO_ROTATE, autoRotate)
                    .putInt(PREF_ORIGINAL_USER_ROTATION, userRotation)
                    .putInt(PREF_ACTIVE_ROTATION, normalizeRotation(engine.rotation()))
                    .commit();
            if (!saved) {
                settingsIssue = "无法保存原系统方向值";
                return;
            }
        }

        boolean autoRotateCleared = Settings.System.putInt(
                getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION,
                0
        );
        controlStarted = true;
        lastAppliedRotation = -1;
        if (autoRotateCleared) {
            settingsIssue = "";
        } else {
            settingsIssue = "系统自动旋转设置写入失败";
        }
    }

    private void applyEngineRotation(boolean force) {
        if (engine == null) {
            return;
        }

        int requestedRotation = requestedRotation();
        int rotation = normalizeRotation(requestedRotation);
        boolean changed = rotation != lastAppliedRotation;
        if (changed || force) {
            mismatchSinceMs = 0;
            retriedDisplay = false;
        }
        lastRotation = rotation;
        lastBed = engine.inBed();
        lastAlpha = engine.alpha();

        if (isManagedMode() && controlStarted && (changed || force)) {
            boolean written = Settings.System.putInt(
                    getContentResolver(),
                    Settings.System.USER_ROTATION,
                    rotation
            );
            if (!written) {
                settingsIssue = "系统方向值写入失败";
            }
            preferences.edit().putInt(PREF_ACTIVE_ROTATION, rotation).apply();
        }

        if (overlayController != null && (changed || force)) {
            overlayController.updateRotation(rotation);
        }
        lastAppliedRotation = rotation;

        if (changed || force) {
            updateNotification();
        }
    }

    private void refreshOverlayOptions() {
        if (overlayController == null) {
            return;
        }
        boolean managed = isManagedMode();
        overlayController.applyOptions(
                managed && preferences.getBoolean(PREF_OVERLAY, false),
                managed && preferences.getBoolean(PREF_PILL, false),
                requestedRotation()
        );
    }

    private void refreshAppRuleMode() {
        int nextMode = AppRules.mode(this);
        if (nextMode == appRuleMode) {
            return;
        }

        appRuleMode = nextMode;
        if (nextMode == AppRules.GAME_PAUSED) {
            pauseForGame();
        } else if (nextMode == AppRules.PASS) {
            releaseSystemControl();
        }
    }

    private void pauseForGame() {
        releaseSystemControl();
        if (overlayController != null) {
            overlayController.removeAll();
        }
        unregisterSensors();
        stopStatusUpdates();
        MotionTrace.finish();
    }

    private void releaseSystemControl() {
        if (controlStarted || preferences.getBoolean(PREF_RESTORE_PENDING, false)) {
            restoreOwnedSettings();
        }
        if (!preferences.getBoolean(PREF_RESTORE_PENDING, false)) {
            controlStarted = false;
        }
        lastAppliedRotation = -1;
    }

    private int requestedRotation() {
        if (engine == null) {
            return lastRotation;
        }
        if (appRuleMode == AppRules.LANDSCAPE && overlayController != null) {
            return overlayController.defaultLandscapeRotation();
        }
        return normalizeRotation(engine.rotation());
    }

    private int initialRotation() {
        if (preferences.getBoolean(PREF_RESTORE_PENDING, false)
                && preferences.contains(PREF_ACTIVE_ROTATION)) {
            return normalizeRotation(preferences.getInt(PREF_ACTIVE_ROTATION, Surface.ROTATION_0));
        }
        return currentDisplayRotation();
    }

    private int currentDisplayRotation() {
        WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        return normalizeRotation(windowManager.getDefaultDisplay().getRotation());
    }

    private String reconcileDisplay(int actual) {
        if (actual == lastRotation || !isManagedMode() || !controlStarted
                || !powerManager.isInteractive() || !Settings.System.canWrite(this)) {
            mismatchSinceMs = 0;
            return "";
        }
        long now = SystemClock.uptimeMillis();
        if (mismatchSinceMs == 0) {
            mismatchSinceMs = now;
        }
        if (now - mismatchSinceMs < 500) {
            return "";
        }
        if (!retriedDisplay) {
            retriedDisplay = true;
            if (Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) != 0) {
                Settings.System.putInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 0);
            }
            if (Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION, -1) != lastRotation) {
                Settings.System.putInt(getContentResolver(), Settings.System.USER_ROTATION, lastRotation);
            }
            overlayController.retryOrientationAnchor(lastRotation);
            return "已重新请求方向，等待屏幕转动";
        }
        int auto = Settings.System.getInt(getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
        int locked = Settings.System.getInt(getContentResolver(), Settings.System.USER_ROTATION, -1);
        String system = auto != 0 ? "自动旋转" : (locked < 0 ? "未知" : locked * 90 + "°");
        return "实际显示仍与请求不同，系统方向设置为" + system;
    }

    private void registerScreenReceiver() {
        if (screenReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(screenReceiver, filter);
        }
        screenReceiverRegistered = true;
    }

    private void unregisterScreenReceiver() {
        if (!screenReceiverRegistered) {
            return;
        }
        unregisterReceiver(screenReceiver);
        screenReceiverRegistered = false;
    }

    private void registerSensors() {
        if (!running || isGamePaused() || !powerManager.isInteractive()) {
            return;
        }
        if (sensorsRegistered) {
            unregisterSensors();
        }

        Sensor gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
        gravityFallback = false;
        if (gravitySensor == null) {
            gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            gravityFallback = gravitySensor != null;
        }

        Sensor orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR);
        rotationFallback = false;
        if (orientationSensor == null) {
            orientationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
            rotationFallback = orientationSensor != null;
        }

        boolean gravityRegistered = gravitySensor != null
                && sensorManager.registerListener(this, gravitySensor, SENSOR_PERIOD_US);
        boolean rotationRegistered = orientationSensor != null
                && sensorManager.registerListener(this, orientationSensor, SENSOR_PERIOD_US);
        sensorsRegistered = gravityRegistered || rotationRegistered;
        lowPassInitialized = false;

        List<String> issues = new ArrayList<>();
        if (gravitySensor == null) {
            issues.add("无重力或加速度传感器");
        } else if (!gravityRegistered) {
            issues.add("重力传感器注册失败");
        }
        if (orientationSensor == null) {
            issues.add("无转动传感器，可手动旋转");
        } else if (!rotationRegistered) {
            issues.add("转动传感器注册失败，可手动旋转");
        }
        sensorIssue = joinIssues(issues);
    }

    private void unregisterSensors() {
        if (sensorManager != null) {
            sensorManager.unregisterListener(this);
        }
        if (engine != null) {
            engine.resetMotion();
        }
        sensorsRegistered = false;
        lowPassInitialized = false;
    }

    private void sendGravity(SensorEvent event) {
        engine.gravity(
                event.values[0],
                event.values[1],
                event.values[2],
                event.timestamp
        );
        applyEngineRotation(false);
    }

    private void updateLowPassGravity(SensorEvent event) {
        if (!lowPassInitialized) {
            lowPassGravity[0] = event.values[0];
            lowPassGravity[1] = event.values[1];
            lowPassGravity[2] = event.values[2];
            lowPassInitialized = true;
            return;
        }
        for (int i = 0; i < 3; i++) {
            lowPassGravity[i] = LOW_PASS_WEIGHT * lowPassGravity[i]
                    + (1f - LOW_PASS_WEIGHT) * event.values[i];
        }
    }

    private void startStatusUpdates() {
        if (statusUpdatesStarted
                || !running
                || isGamePaused()
                || !powerManager.isInteractive()) {
            return;
        }
        mainHandler.removeCallbacks(statusRunnable);
        statusUpdatesStarted = true;
        mainHandler.post(statusRunnable);
    }

    private void stopStatusUpdates() {
        mainHandler.removeCallbacks(statusRunnable);
        statusUpdatesStarted = false;
    }

    private void publishStatus() {
        if (engine == null) {
            return;
        }

        lastRotation = requestedRotation();
        lastBed = engine.inBed();
        lastAlpha = engine.alpha();
        int displayRotation = currentDisplayRotation();

        List<String> issues = new ArrayList<>();
        String mismatch = reconcileDisplay(displayRotation);
        if (!mismatch.isEmpty()) {
            issues.add(mismatch);
        }
        if (!Settings.System.canWrite(this)) {
            issues.add("缺少修改系统设置权限");
        } else if (!settingsIssue.isEmpty()) {
            issues.add(settingsIssue);
        }
        if (overlayController != null && !overlayController.issues().isEmpty()) {
            issues.add(overlayController.issues());
        }
        if (!sensorIssue.isEmpty()) {
            issues.add(sensorIssue);
        }

        String state = isGamePaused()
                ? "游戏暂停"
                : (!powerManager.isInteractive()
                ? "息屏待机"
                : (sensorsRegistered ? "传感器监听中" : "手动模式"));
        String detail = String.format(
                Locale.CHINA,
                "%s · 规则=%s · 请求=%s · 实际显示=%s · %s\n传感器 G=%s R=%s\n诊断 gu=%.2f gv=%.2f gz=%.2f α=%.1f° R=%.2f spin=%.1f°%s",
                state,
                appRuleLabel(),
                requestedRotationLabel(),
                displayRotation * 90 + "°",
                lastBed ? "床上" : "离床",
                gravityLabel(),
                rotationLabel(),
                engine.gu(),
                engine.gv(),
                engine.gz(),
                lastAlpha,
                engine.radius(),
                engine.spin(),
                issues.isEmpty() ? "" : "\n问题：" + joinIssues(issues)
        );
        sendStatus(detail, lastRotation, displayRotation, lastBed, lastAlpha);
    }

    private void sendStoppedStatus() {
        String detail = settingsIssue.isEmpty()
                ? "服务已停止，原系统方向设置已恢复"
                : "服务已停止，但未完成系统设置恢复：" + settingsIssue;
        sendStatus(detail, lastRotation, currentDisplayRotation(), lastBed, lastAlpha);
    }

    private void sendStatus(String detail, int rotation, int displayRotation,
                            boolean bed, float alpha) {
        lastStatus = detail;
        Intent status = new Intent(STATUS);
        status.setPackage(getPackageName());
        status.putExtra(EXTRA_DETAIL, detail);
        status.putExtra(EXTRA_ROTATION, rotation);
        status.putExtra(EXTRA_DISPLAY_ROTATION, displayRotation);
        int activityOrientation = running && isManagedMode() && controlStarted
                && Settings.System.canWrite(this)
                ? overlayController.screenOrientationFor(rotation)
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        status.putExtra(EXTRA_ACTIVITY_ORIENTATION, activityOrientation);
        status.putExtra(EXTRA_BED, bed);
        status.putExtra(EXTRA_ALPHA, alpha);
        sendBroadcast(status);
    }

    private String gravityLabel() {
        if (gravityFallback) {
            return "加速度低通";
        }
        return sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) == null ? "不可用" : "TYPE_GRAVITY";
    }

    private String rotationLabel() {
        if (sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) != null) {
            return "GAME_ROTATION_VECTOR";
        }
        if (rotationFallback) {
            return "ROTATION_VECTOR降级";
        }
        return "不可用";
    }

    private void stopBedside() {
        unregisterSensors();
        unregisterScreenReceiver();
        stopStatusUpdates();

        if (overlayController != null) {
            overlayController.removeAll();
        }
        if (controlStarted || preferences.getBoolean(PREF_RESTORE_PENDING, false)) {
            restoreOwnedSettings();
        }

        running = false;
        requestTileListeningState();
        if (foregroundStarted) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foregroundStarted = false;
        }
        sendStoppedStatus();
        stopSelf();
    }

    private boolean restoreOwnedSettings() {
        if (!Settings.System.canWrite(this)) {
            settingsIssue = "缺少修改系统设置权限";
            return false;
        }
        boolean restored = restoreStoredSettings(this, preferences);
        if (restored) {
            clearPendingRestore(preferences);
            controlStarted = false;
            settingsIssue = "";
        } else {
            settingsIssue = "恢复原系统方向设置失败";
        }
        return restored;
    }

    private static boolean restoreStoredSettings(Context context, SharedPreferences prefs) {
        int autoRotate = prefs.getInt(PREF_ORIGINAL_AUTO_ROTATE, 1);
        int userRotation = prefs.getInt(PREF_ORIGINAL_USER_ROTATION, Surface.ROTATION_0);
        boolean userWritten = Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.USER_ROTATION,
                userRotation
        );
        boolean autoWritten = Settings.System.putInt(
                context.getContentResolver(),
                Settings.System.ACCELEROMETER_ROTATION,
                autoRotate
        );
        return userWritten && autoWritten;
    }

    private static void clearPendingRestore(SharedPreferences preferences) {
        preferences.edit()
                .remove(PREF_RESTORE_PENDING)
                .remove(PREF_ORIGINAL_AUTO_ROTATE)
                .remove(PREF_ORIGINAL_USER_ROTATION)
                .remove(PREF_ACTIVE_ROTATION)
                .apply();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "床头方向控制",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("传感器方向接管与系统设置恢复状态");
        channel.enableVibration(false);
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String fallbackText) {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        PendingIntent stopPendingIntent = servicePendingIntent(STOP, 2);
        PendingIntent stepPendingIntent = servicePendingIntent(STEP, 3);
        Icon actionIcon = Icon.createWithResource(this, R.drawable.ic_rotation);

        String text = engine == null ? fallbackText : notificationSummary();
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_rotation)
                .setContentTitle(isGamePaused() ? "游戏中 · 懒人翻身已暂停" : "床头方向接管")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(openPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .addAction(new Notification.Action.Builder(actionIcon, "停止", stopPendingIntent).build());
        if (!isGamePaused()) {
            builder.addAction(new Notification.Action.Builder(
                    actionIcon,
                    "手动转90°",
                    stepPendingIntent
            ).build());
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE);
        }
        return builder.build();
    }

    private PendingIntent servicePendingIntent(String action, int requestCode) {
        Intent intent = new Intent(this, RotationService.class).setAction(action);
        return PendingIntent.getService(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }

    private String notificationSummary() {
        if (isGamePaused()) {
            return "方向由游戏和系统决定，离开游戏后懒人翻身自动恢复";
        }
        if (!Settings.System.canWrite(this)) {
            return "需要修改系统设置权限才能接管方向";
        }
        return String.format(
                Locale.CHINA,
                "规则=%s · 请求=%s · %s · %s",
                appRuleLabel(),
                requestedRotationLabel(),
                engine.inBed() ? "床上锁定" : "离床",
                sensorsRegistered ? "传感器监听中" : "手动模式"
        );
    }

    private String appRuleLabel() {
        if (appRuleMode == AppRules.PASS) {
            if (!AppRules.isDetectorConnected()) {
                return "前台识别未连接，暂交还系统";
            }
            if (AppRules.foregroundPackage() == null) {
                return "等待识别前台应用，暂交还系统";
            }
            return "该应用已设为交还系统";
        }
        if (appRuleMode == AppRules.GAME_PAUSED) {
            return "游戏暂停";
        }
        if (appRuleMode == AppRules.LANDSCAPE) {
            return "分应用锁定横屏";
        }
        return "跟随";
    }

    private String requestedRotationLabel() {
        if (appRuleMode == AppRules.PASS) {
            return "未接管";
        }
        if (appRuleMode == AppRules.GAME_PAUSED) {
            return lastRotation * 90 + "°（暂停）";
        }
        return lastRotation * 90 + "°";
    }

    private boolean isManagedMode() {
        return appRuleMode == AppRules.FOLLOW || appRuleMode == AppRules.LANDSCAPE;
    }

    private boolean isGamePaused() {
        return appRuleMode == AppRules.GAME_PAUSED;
    }

    private void updateNotification() {
        if (!foregroundStarted) {
            return;
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification("床头方向接管运行中"));
    }

    private void requestTileListeningState() {
        ComponentName tile = new ComponentName(getPackageName(), "app.bedsiderotate.BedTile");
        TileService.requestListeningState(this, tile);
    }

    private static int normalizeRotation(int rotation) {
        return ((rotation % 4) + 4) % 4;
    }

    private int readThreshold() {
        int threshold = preferences.getInt(PREF_THRESHOLD, 55);
        return Math.max(46, Math.min(85, threshold));
    }

    private static String joinIssues(List<String> issues) {
        StringBuilder result = new StringBuilder();
        for (String issue : issues) {
            if (issue == null || issue.isEmpty()) {
                continue;
            }
            if (result.length() > 0) {
                result.append("；");
            }
            result.append(issue);
        }
        return result.toString();
    }
}
