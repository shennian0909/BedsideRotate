package app.bedsiderotate;

import android.content.ContentResolver;
import android.hardware.SensorEvent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

final class MotionTrace {
    private static final Handler clock = new Handler(Looper.getMainLooper());
    private static final Runnable stop = MotionTrace::finish;
    private static StringBuilder rows;
    private static long nextSettingsSampleNs;
    private static int systemAuto;
    private static int systemRotation;
    static int samples;
    static boolean recording;

    static void begin() {
        rows = new StringBuilder("time_ns,sensor_type,v0,v1,v2,v3,in_bed,rotation,gu,gv,gz,"
                + "alpha_deg,r_uz,spin_deg,requested_rotation,display_rotation,rule_mode,"
                + "system_auto_rotate,system_user_rotation\n");
        samples = 0;
        nextSettingsSampleNs = 0;
        recording = true;
        clock.removeCallbacks(stop);
        clock.postDelayed(stop, 30000);
    }

    // The service and the activity both run on the application main looper.
    static void sample(SensorEvent event, OrientationEngine engine,
                       int requestedRotation, int displayRotation, int ruleMode, ContentResolver resolver) {
        if (event.timestamp >= nextSettingsSampleNs) {
            systemAuto = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, -1);
            systemRotation = Settings.System.getInt(resolver, Settings.System.USER_ROTATION, -1);
            nextSettingsSampleNs = event.timestamp + 250_000_000L;
        }
        float[] raw = event.values;
        rows.append(event.timestamp).append(',').append(event.sensor.getType()).append(',')
                .append(raw[0]).append(',').append(raw[1]).append(',').append(raw[2]).append(',');
        if (raw.length >= 4) rows.append(raw[3]);
        rows.append(',').append(engine.inBed() ? 1 : 0).append(',').append(engine.rotation())
                .append(',').append(engine.gu()).append(',').append(engine.gv())
                .append(',').append(engine.gz()).append(',').append(engine.alpha())
                .append(',').append(engine.radius()).append(',').append(engine.spin())
                .append(',').append(requestedRotation).append(',').append(displayRotation)
                .append(',').append(ruleMode).append(',').append(systemAuto)
                .append(',').append(systemRotation).append('\n');
        samples++;
    }

    static void finish() {
        recording = false;
        clock.removeCallbacks(stop);
    }

    static String csv() {
        return rows.toString();
    }
}
