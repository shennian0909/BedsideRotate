import app.bedsiderotate.OrientationEngine;

public final class EngineReplay {
    private static final float EPSILON = 0.05f;
    private static final long MILLIS = 1_000_000L;
    private static final long SAMPLE_STEP_NS = 20 * MILLIS;

    private EngineReplay() {
    }

    public static void main(String[] args) {
        deskProjectionAndHysteresis();
        manualStepPreservesFixedDeskPose();
        restoredManagedRotationHoldsFirstDeskGravity();
        restoredManagedRotationKeepsBedState();
        bedEntryRollAndWristQuadrants();
        wristHysteresisAndReverse();
        thresholdRangeAvoidsImmediateUndo();
        bedToDeskHysteresis();
        sideLyingKeepsBedRotation();
        sideLyingVideoRoundTrips();
        bedExitTimerResetsWithMotion();
        autoExitConfigurationControls();
        flatPickupReplayWithAutoExitDisabled();
        manualSideCalibrationAndReset();
        supineBodyRollAndWristRoundTrips();
        tiltedWristRoundTrips();
        System.out.println("EngineReplay: all scenarios passed");
    }

    private static void deskProjectionAndHysteresis() {
        OrientationEngine engine = new OrientationEngine(0, 55);

        engine.gravity(0.0f, 0.0f, 9.81f, 0L);
        check(engine.rotation() == 0, "flat table must keep rotation 0");

        engine.gravity(0.0f, 9.81f, 0.0f, 10 * MILLIS);
        check(engine.rotation() == 0, "upright portrait must stay at rotation 0");

        engine.gravity(9.81f, 0.0f, 0.0f, 20 * MILLIS);
        check(engine.rotation() == 1, "positive gx must map to rotation 1");

        OrientationEngine edge = new OrientationEngine(0, 55);
        edge.gravity(7.515f, 6.305f, 0.0f, 0L);
        check(edge.rotation() == 0, "desk rotation must suppress pre-boundary jitter");
        edge.gravity(8.131f, 5.487f, 0.0f, 10 * MILLIS);
        check(edge.rotation() == 1, "desk rotation must cross the hysteretic boundary");
        edge.gravity(7.515f, 6.305f, 0.0f, 20 * MILLIS);
        check(edge.rotation() == 1, "desk hysteresis must retain rotation after small reversal");
    }

    private static void bedEntryRollAndWristQuadrants() {
        OrientationEngine engine = new OrientationEngine(0, 55);
        float[] q = unitQuaternion(0.0);
        feed(engine, q, 0L);

        engine.gravity(0.0f, 4.905f, -8.495f, 0L);
        check(!engine.inBed(), "bed entry must start with a stable candidate");
        engine.gravity(0.0f, 4.905f, -8.495f, 150 * MILLIS);
        check(engine.inBed(), "supine face-down posture must enter bed mode");
        checkNear(engine.alpha(), 0.0f, "supine alpha");
        checkNear(engine.radius(), 8.495f, "supine cylinder radius");

        engine.gravity(8.495f, 4.905f, 0.0f, 200 * MILLIS);
        check(engine.rotation() == 0, "rolling from supine to side must lock rotation");
        checkNear(engine.alpha(), 90.0f, "positive body roll alpha");
        checkNear(engine.gu(), 8.495f, "positive body roll gu");
        checkNear(engine.radius(), 8.495f, "side-lying cylinder radius");

        engine.gravity(-8.495f, 4.905f, 0.0f, 210 * MILLIS);
        check(engine.rotation() == 0, "rolling to the opposite side must not rotate screen");
        checkNear(engine.alpha(), -90.0f, "negative body roll alpha");
        engine.gravity(8.495f, 4.905f, 0.0f, 220 * MILLIS);
        check(engine.rotation() == 0, "returning through supine must keep bed lock");

        q = rotate(q, 0.0f, 1.0f, 0.0f, 90.0f);
        feed(engine, q, 500 * MILLIS);
        check(engine.rotation() == 0, "body-roll quaternion must not become wrist spin");
        checkNear(engine.spin(), 0.0f, "body-roll quaternion spin leakage");

        q = rotate(q, 0.0f, 0.0f, 1.0f, 10.0f);
        check(q[1] > 0.06f, "q_y(+90) * q_z(+10) must have positive x");
        feed(engine, q, 1000 * MILLIS);
        checkNear(engine.spin(), 10.0f, "first wrist increment after body roll");
        long quaternionTime = 1000 * MILLIS;
        for (int i = 2; i <= 9; i++) {
            q = rotate(q, 0.0f, 0.0f, 1.0f, 10.0f);
            quaternionTime += 10 * MILLIS;
            feed(engine, q, quaternionTime);
        }
        check(engine.rotation() == 1,
                "first pure wrist 90 must produce rotation 1; got " + engine.rotation()
                        + " spin=" + engine.spin() + " bed=" + engine.inBed());

        for (int i = 0; i < 9; i++) {
            q = rotate(q, 0.0f, 0.0f, 1.0f, 10.0f);
            quaternionTime += 10 * MILLIS;
            feed(engine, q, quaternionTime);
        }
        check(engine.rotation() == 2, "two consecutive wrist 90s must produce rotation 2");
        checkNear(engine.spin(), 180.0f, "consecutive wrist spin");

    }

    private static void manualStepPreservesFixedDeskPose() {
        OrientationEngine engine = new OrientationEngine(0, 55);
        engine.gravity(0.0f, 9.81f, 0.0f, 0L);
        engine.manualStep();

        check(engine.rotation() == 1, "manual desk step must move forward once");
        checkNear(engine.gu(), -9.81f, "manual desk step must refresh gu");
        checkNear(engine.alpha(), -90.0f, "manual desk step must refresh alpha");

        engine.gravity(0.0f, 9.81f, 0.0f, 10 * MILLIS);
        engine.gravity(0.0f, 9.81f, 0.0f, 20 * MILLIS);
        check(engine.rotation() == 1, "fixed vertical gravity must retain the manual direction");

        engine.resetMotion();
        engine.gravity(0.0f, 9.81f, 0.0f, 30 * MILLIS);
        check(engine.rotation() == 1, "motion reset must retain the pending manual direction");

        engine.gravity(0.0f, -9.81f, 0.0f, 40 * MILLIS);
        check(engine.rotation() == 2, "a real posture change must release the manual hold");

        OrientationEngine beforeFirstGravity = new OrientationEngine(0, 55);
        beforeFirstGravity.manualStep();
        beforeFirstGravity.gravity(0.0f, 9.81f, 0.0f, 0L);
        check(beforeFirstGravity.rotation() == 1,
                "manual step before the first gravity sample must also hold");
    }

    private static void restoredManagedRotationHoldsFirstDeskGravity() {
        OrientationEngine engine = new OrientationEngine(0, 55);
        engine.gravity(0.0f, 9.81f, 0.0f, 0L);
        engine.restoreManagedRotation(2);

        engine.gravity(9.81f, 0.0f, 0.0f, 10 * MILLIS);
        check(engine.rotation() == 2,
                "restored desk rotation must hold the first gravity sample");

        engine.gravity(-9.81f, 0.0f, 0.0f, 20 * MILLIS);
        check(engine.rotation() == 3,
                "a real posture change must release the restored desk hold");
    }

    private static void restoredManagedRotationKeepsBedState() {
        OrientationEngine engine = new OrientationEngine(0, 55);
        engine.calibrate(1);
        engine.restoreManagedRotation(3);

        check(engine.inBed(), "restoring a managed rotation must retain bed mode");
        check(engine.rotation() == 3,
                "restoring a managed rotation must retain the supplied quadrant");

        engine.gravity(0.0f, 0.0f, 9.81f, 0L);
        check(engine.inBed(), "restored bed mode must await the flat-exit hold");
        check(engine.rotation() == 3,
                "bed gravity must not replace the restored quadrant immediately");
    }

    private static void wristHysteresisAndReverse() {
        OrientationEngine engine = new OrientationEngine(0, 55);
        engine.calibrate(2);
        float[] q = unitQuaternion(0.0);
        feed(engine, q, 0L);

        q = rotate(q, 0.0f, 0.0f, 1.0f, 50.0f);
        feed(engine, q, 10 * MILLIS);
        check(engine.rotation() == 2, "wrist movement below threshold must not step");

        q = rotate(q, 0.0f, 0.0f, 1.0f, 5.0f);
        feed(engine, q, 20 * MILLIS);
        check(engine.rotation() == 3, "threshold crossing must produce one positive step");

        q = rotate(q, 0.0f, 0.0f, 1.0f, 4.0f);
        feed(engine, q, 30 * MILLIS);
        q = rotate(q, 0.0f, 0.0f, 1.0f, -8.0f);
        feed(engine, q, 40 * MILLIS);
        q = rotate(q, 0.0f, 0.0f, 1.0f, 4.0f);
        feed(engine, q, 50 * MILLIS);
        check(engine.rotation() == 3, "wrist jitter around threshold must not repeat a step");

        q = rotate(q, 0.0f, 0.0f, 1.0f, -55.0f);
        feed(engine, q, 60 * MILLIS);
        check(engine.rotation() == 2, "reverse wrist motion must return one signed step");

        q = rotate(q, 0.0f, 0.0f, 1.0f, 100.0f);
        feed(engine, q, 70 * MILLIS);
        check(engine.rotation() == 3, "100-degree input must produce one positive step");
        q = rotate(q, 0.0f, 0.0f, 1.0f, 80.0f);
        feed(engine, q, 80 * MILLIS);
        check(engine.rotation() == 0, "the 10-degree residual must contribute to the next 90");

        q = rotate(q, 0.0f, 0.0f, 1.0f, -90.0f);
        feed(engine, q, 90 * MILLIS);
        check(engine.rotation() == 3, "negative wrist rotation must wrap to rotation 3");
    }

    private static void thresholdRangeAvoidsImmediateUndo() {
        for (int threshold = 46; threshold <= 85; threshold++) {
            OrientationEngine engine = new OrientationEngine(0, threshold);
            engine.calibrate(0);
            float[] origin = unitQuaternion(0.0);
            feed(engine, origin, 0L);
            float[] forward = rotate(origin, 0.0f, 0.0f, 1.0f, 90.0f);
            feed(engine, forward, 10 * MILLIS);
            check(engine.rotation() == 1,
                    threshold + "-degree threshold must allow a forward 90");
            feed(engine, rotate(forward, 0.0f, 0.0f, 1.0f, -90.0f), 20 * MILLIS);
            check(engine.rotation() == 0,
                    threshold + "-degree threshold must allow a reverse 90");

            float[] halfTurn = rotate(origin, 0.0f, 0.0f, 1.0f, 180.0f);
            feed(engine, halfTurn, 30 * MILLIS);
            check(engine.rotation() == 2,
                    threshold + "-degree threshold must allow a forward 180");
            feed(engine, origin, 40 * MILLIS);
            check(engine.rotation() == 0,
                    threshold + "-degree threshold must allow a reverse 180");

            float[] reverse = rotate(origin, 0.0f, 0.0f, 1.0f, -90.0f);
            feed(engine, reverse, 50 * MILLIS);
            check(engine.rotation() == 3,
                    threshold + "-degree threshold must allow a reverse-first 90");
            feed(engine, origin, 60 * MILLIS);
            check(engine.rotation() == 0,
                    threshold + "-degree threshold must allow a forward return from reverse");

            float[] reverseHalfTurn = rotate(origin, 0.0f, 0.0f, 1.0f, -180.0f);
            feed(engine, reverseHalfTurn, 70 * MILLIS);
            check(engine.rotation() == 2,
                    threshold + "-degree threshold must allow a reverse-first 180");
            feed(engine, origin, 80 * MILLIS);
            check(engine.rotation() == 0,
                    threshold + "-degree threshold must allow a forward return from reverse-first");
        }

        OrientationEngine minimum = new OrientationEngine(0, 46);
        minimum.calibrate(0);
        float[] minQ = unitQuaternion(0.0);
        feed(minimum, minQ, 0L);
        float[] minForward = rotate(minQ, 0.0f, 0.0f, 1.0f, 46.0f);
        feed(minimum, minForward, 10 * MILLIS);
        check(minimum.rotation() == 1, "minimum threshold must produce its first step");
        float[] minJitter = rotate(minForward, 0.0f, 0.0f, 1.0f, -44.9f);
        feed(minimum, minJitter, 20 * MILLIS);
        check(minimum.rotation() == 1,
                "minimum threshold must ignore a small post-step rebound");
        feed(minimum, rotate(minJitter, 0.0f, 0.0f, 1.0f, -0.2f), 30 * MILLIS);
        check(minimum.rotation() == 0,
                "minimum threshold must still accept an active post-step reversal");

        OrientationEngine defaultThreshold = new OrientationEngine(0, 55);
        defaultThreshold.calibrate(0);
        float[] defaultOrigin = unitQuaternion(0.0);
        feed(defaultThreshold, defaultOrigin, 0L);
        feed(defaultThreshold, rotate(defaultOrigin, 0.0f, 0.0f, 1.0f, 55.8f), 10 * MILLIS);
        feed(defaultThreshold, rotate(defaultOrigin, 0.0f, 0.0f, 1.0f, 64.7f), 20 * MILLIS);
        feed(defaultThreshold, rotate(defaultOrigin, 0.0f, 0.0f, 1.0f, 34.7f), 30 * MILLIS);
        feed(defaultThreshold, rotate(defaultOrigin, 0.0f, 0.0f, 1.0f, 25.6f), 40 * MILLIS);
        check(defaultThreshold.rotation() == 1,
                "default threshold must ignore the recorded post-step rebound");
        feed(defaultThreshold, defaultOrigin, 50 * MILLIS);
        check(defaultThreshold.rotation() == 0,
                "default threshold must allow the recorded turn to continue back to origin");

        OrientationEngine fastReturn = new OrientationEngine(0, 55);
        fastReturn.calibrate(0);
        feed(fastReturn, defaultOrigin, 0L);
        feed(fastReturn, rotate(defaultOrigin, 0.0f, 0.0f, 1.0f, 65.0f), 10 * MILLIS);
        feed(fastReturn, defaultOrigin, 20 * MILLIS);
        check(fastReturn.rotation() == 0,
                "default threshold must allow a fast 65-to-origin return");

        OrientationEngine maximum = new OrientationEngine(0, 85);
        maximum.calibrate(0);
        float[] maxQ = unitQuaternion(0.0);
        feed(maximum, maxQ, 0L);
        maxQ = rotate(maxQ, 0.0f, 0.0f, 1.0f, 84.0f);
        feed(maximum, maxQ, 10 * MILLIS);
        check(maximum.rotation() == 0, "maximum threshold must suppress sub-threshold movement");
        feed(maximum, rotate(maxQ, 0.0f, 0.0f, 1.0f, 2.0f), 20 * MILLIS);
        check(maximum.rotation() == 1, "maximum threshold must trigger above its boundary");
        float[] maxForward = rotate(maxQ, 0.0f, 0.0f, 1.0f, 85.0f);
        maximum = new OrientationEngine(0, 85);
        maximum.calibrate(0);
        feed(maximum, maxQ, 0L);
        feed(maximum, maxForward, 10 * MILLIS);
        float[] maxRebound = rotate(maxForward, 0.0f, 0.0f, 1.0f, -79.9f);
        feed(maximum, maxRebound, 20 * MILLIS);
        check(maximum.rotation() == 1,
                "maximum threshold must retain its existing post-step reversal boundary");
        feed(maximum, rotate(maxRebound, 0.0f, 0.0f, 1.0f, -0.2f), 30 * MILLIS);
        check(maximum.rotation() == 0,
                "maximum threshold must not gain extra reversal hysteresis");

        OrientationEngine immediateReturn = new OrientationEngine(0, 46);
        immediateReturn.calibrate(0);
        feed(immediateReturn, minQ, 0L);
        feed(immediateReturn, minForward, 10 * MILLIS);
        feed(immediateReturn, minQ, 20 * MILLIS);
        check(immediateReturn.rotation() == 0,
                "an immediate return to the original pose must remain active");

        OrientationEngine reset = new OrientationEngine(0, 55);
        reset.calibrate(0);
        feed(reset, defaultOrigin, 0L);
        feed(reset, rotate(defaultOrigin, 0.0f, 0.0f, 1.0f, 65.0f), 10 * MILLIS);
        check(reset.rotation() == 1, "reset replay must start after a positive step");
        reset.resetMotion();
        feed(reset, defaultOrigin, 20 * MILLIS);
        feed(reset, rotate(defaultOrigin, 0.0f, 0.0f, 1.0f, -55.0f), 30 * MILLIS);
        check(reset.rotation() == 0, "motion reset must clear post-step reversal history");
    }

    private static void bedToDeskHysteresis() {
        OrientationEngine engine = new OrientationEngine(0, 55);

        engine.gravity(0.0f, 0.0f, -9.81f, 0L);
        engine.gravity(0.0f, 0.0f, -9.81f, 149 * MILLIS);
        check(!engine.inBed(), "bed entry must honor its hold time");
        engine.gravity(0.0f, 0.0f, -9.81f, 150 * MILLIS);
        check(engine.inBed(), "bed entry must complete after its hold time");
        check(engine.rotation() == 0, "automatic bed entry must preserve desk rotation 0");

        float[] screenUpInsideThreshold = screenUpGravity(29.9);
        float[] screenUpOutsideThreshold = screenUpGravity(30.1);
        engine.gravity(
                screenUpInsideThreshold[0],
                screenUpInsideThreshold[1],
                screenUpInsideThreshold[2],
                300 * MILLIS);
        engine.gravity(
                screenUpInsideThreshold[0],
                screenUpInsideThreshold[1],
                screenUpInsideThreshold[2],
                1_799 * MILLIS);
        check(engine.inBed(), "screen-up entry must remain in bed before 1500 ms");

        engine.gravity(
                screenUpOutsideThreshold[0],
                screenUpOutsideThreshold[1],
                screenUpOutsideThreshold[2],
                1_800 * MILLIS);
        check(engine.inBed(), "a brief sample beyond 30 degrees must not leave bed");
        engine.gravity(
                screenUpInsideThreshold[0],
                screenUpInsideThreshold[1],
                screenUpInsideThreshold[2],
                1_900 * MILLIS);
        engine.gravity(
                screenUpInsideThreshold[0],
                screenUpInsideThreshold[1],
                screenUpInsideThreshold[2],
                3_399 * MILLIS);
        check(engine.inBed(), "screen-up timing must restart after a valid reset sample");
        engine.gravity(
                screenUpInsideThreshold[0],
                screenUpInsideThreshold[1],
                screenUpInsideThreshold[2],
                3_400 * MILLIS);
        check(!engine.inBed(), "screen-up posture must leave bed after 1500 continuous ms");
        check(engine.rotation() == 0, "returning to desk must preserve rotation 0");

        engine.gravity(9.81f, 0.0f, 0.0f, 3_500 * MILLIS);
        check(engine.rotation() == 1, "normal desk rotation must resume after leaving bed");
    }

    private static void sideLyingKeepsBedRotation() {
        replaySideLyingHold(false, 2, -1, 20.0f, 1);
        replaySideLyingHold(false, 3, 1, 45.0f, -1);
        replaySideLyingHold(true, 1, 1, 20.0f, -1);
        replaySideLyingHold(true, 0, -1, 45.0f, 1);
    }

    private static void sideLyingVideoRoundTrips() {
        for (int portrait = 0; portrait <= 1; portrait++) {
            for (int side : new int[] {-1, 1}) {
                for (int turn : new int[] {-1, 1}) {
                    replayVideo(portrait, side, turn, true);
                    replayVideo(portrait, side, turn, false);
                }
            }
        }
    }

    private static void replayVideo(int portrait, int side, int turn, boolean bind) {
        OrientationEngine engine = new OrientationEngine(portrait, 55);
        if (bind) {
            engine.calibrate(portrait);
        } else {
            engine.gravity(0, 0, -9.81f, 0);
            engine.gravity(0, 0, -9.81f, 150 * MILLIS);
        }
        double gx = side * 8.03, gy = 4.50, gz = 3.36;
        double g = Math.sqrt(gx * gx + gy * gy + gz * gz);
        double w = Math.sqrt((1 + gz / g) / 2);
        float[] start = {(float) w, (float) (gy / (2 * w * g)), (float) (-gx / (2 * w * g)), 0};
        int landscape = (portrait + turn + 4) % 4;

        // The device turns 90 degrees over one second, plays for a minute, then turns back.
        for (int frame = 0; frame <= 3100; frame++) {
            int turnFrame = Math.min(50, Math.min(frame, 3100 - frame));
            float angle = turn * turnFrame * 1.8f;
            double cos = Math.cos(Math.toRadians(angle)), sin = Math.sin(Math.toRadians(angle));
            long t = 200 * MILLIS + frame * SAMPLE_STEP_NS;
            engine.gravity((float) (gx * cos + gy * sin), (float) (gy * cos - gx * sin), (float) gz, t);
            feed(engine, rotate(start, 0, 0, 1, angle), t);
            check(engine.inBed(), "watching or turning back must preserve bed mode");
            if (frame >= 50 && frame <= 3050) {
                check(engine.rotation() == landscape, "video must retain the requested landscape direction");
            }
        }
        check(engine.rotation() == portrait, "turning the tablet back must request the original portrait");
        checkNear(engine.spin(), 0, "net wrist angle after the video round trip");
    }

    private static void bedExitTimerResetsWithMotion() {
        OrientationEngine engine = new OrientationEngine(2, 55);
        engine.gravity(0.0f, 0.0f, -9.81f, 0L);
        engine.gravity(0.0f, 0.0f, -9.81f, 150 * MILLIS);
        check(engine.inBed(), "motion-reset replay must start in bed");

        engine.gravity(0.0f, 0.0f, 9.81f, 300 * MILLIS);
        engine.gravity(0.0f, 0.0f, 9.81f, 1_799 * MILLIS);
        check(engine.inBed(), "desk-exit candidate must still be pending before reset");

        engine.resetMotion();
        check(engine.inBed() && engine.rotation() == 2,
                "motion reset must retain bed mode and direction");
        engine.gravity(0.0f, 0.0f, 9.81f, 1_800 * MILLIS);
        check(engine.inBed(), "screen-on sample must not complete a pre-reset timer");
        engine.gravity(0.0f, 0.0f, 9.81f, 3_299 * MILLIS);
        check(engine.inBed(), "post-reset screen-up hold must remain below 1500 ms");
        engine.gravity(0.0f, 0.0f, 9.81f, 3_300 * MILLIS);
        check(!engine.inBed(), "post-reset screen-up hold must complete after 1500 ms");
        check(engine.rotation() == 2, "leaving bed after motion reset must retain direction");
    }

    private static void autoExitConfigurationControls() {
        String context = "default auto exit";
        OrientationEngine repeatedEnable = new OrientationEngine(0, 55);
        enterSupineBed(repeatedEnable, 0, 0, context);
        float[] flat = unitQuaternion(0.0);
        long candidateStart = 1_000 * MILLIS;
        feedPose(repeatedEnable, flat, candidateStart);
        repeatedEnable.setAutoExitBedWhenFlat(true);
        feedPose(repeatedEnable, flat, candidateStart + 1_499 * MILLIS);
        check(repeatedEnable.inBed(), context + " must honor the 1500 ms hold");
        feedPose(repeatedEnable, flat, candidateStart + 1_500 * MILLIS);
        check(!repeatedEnable.inBed(), context + " must leave bed after 1500 ms");

        context = "disabled and re-enabled auto exit";
        OrientationEngine toggle = new OrientationEngine(2, 55);
        toggle.setAutoExitBedWhenFlat(false);
        enterSupineBed(toggle, 2, 0, context);
        check(toggle.inBed(), context + " disabling from desk must not block bed entry");
        candidateStart = 1_000 * MILLIS;
        feedPose(toggle, flat, candidateStart);
        toggle.setAutoExitBedWhenFlat(false);
        feedPose(toggle, flat, candidateStart + 3_000 * MILLIS);
        check(toggle.inBed() && toggle.rotation() == 2,
                context + " disabled exit must not restore desk while flat");

        toggle.setAutoExitBedWhenFlat(true);
        check(toggle.inBed(), context + " re-enabling must not exit without a new sample");
        long restart = 5_000 * MILLIS;
        feedPose(toggle, flat, restart);
        feedPose(toggle, flat, restart + 1_499 * MILLIS);
        check(toggle.inBed(), context + " first sample after re-enabling must start a new hold");
        feedPose(toggle, flat, restart + 1_500 * MILLIS);
        check(!toggle.inBed(), context + " re-enabled hold must complete after 1500 ms");

        context = "auto-exit candidate cancellation";
        OrientationEngine cancellation = new OrientationEngine(3, 55);
        enterSupineBed(cancellation, 3, 0, context);
        feedPose(cancellation, flat, 1_000 * MILLIS);
        float[] tilted = rotate(flat, 1.0f, 0.0f, 0.0f, 50.0f);
        feedPose(cancellation, tilted, 2_000 * MILLIS);
        check(cancellation.inBed(), context + " invalid sample must not leave bed alone");
        restart = 2_020 * MILLIS;
        feedPose(cancellation, flat, restart);
        feedPose(cancellation, flat, restart + 1_499 * MILLIS);
        check(cancellation.inBed(), context + " next valid sample must restart the hold");
        feedPose(cancellation, flat, restart + 1_500 * MILLIS);
        check(!cancellation.inBed(), context + " restarted hold must complete after 1500 ms");
        check(cancellation.rotation() == 3, context + " exiting must retain rotation 3");

        OrientationEngine interrupted = new OrientationEngine(0, 55);
        interrupted.calibrate(0);
        feedPose(interrupted, flat, 0);
        feedPose(interrupted, flat, 1_499 * MILLIS);
        interrupted.setAutoExitBedWhenFlat(false);
        interrupted.setAutoExitBedWhenFlat(true);
        feedPose(interrupted, flat, 1_500 * MILLIS);
        check(interrupted.inBed(), "toggling off must cancel a hold even between sensor events");
        feedPose(interrupted, flat, 3_000 * MILLIS);
        check(!interrupted.inBed(), "the new hold must last a full 1500 ms");

        OrientationEngine desk = new OrientationEngine(1, 55);
        feedPose(desk, flat, 0L);
        desk.setAutoExitBedWhenFlat(false);
        check(!desk.inBed(), "disabling auto exit at desk must not enter bed");
        feedPose(desk, rotate(flat, 0.0f, 1.0f, 0.0f, 60.0f), SAMPLE_STEP_NS);
        check(!desk.inBed(), "disabled auto exit must leave desk bed entry unchanged");
    }

    private static void flatPickupReplayWithAutoExitDisabled() {
        for (int initialRotation = 0; initialRotation < 4; initialRotation++) {
            for (int pickupSide : new int[] {-1, 1}) {
                for (int wristDirection : new int[] {-1, 1}) {
                    replayFlatPickupWithAutoExitDisabled(
                            initialRotation,
                            pickupSide,
                            wristDirection);
                }
            }
        }
    }

    private static void replayFlatPickupWithAutoExitDisabled(
            int initialRotation,
            int pickupSide,
            int wristDirection) {
        OrientationEngine engine = new OrientationEngine(initialRotation, 55);
        engine.setAutoExitBedWhenFlat(false);
        String context = "rotation " + initialRotation
                + ", " + (pickupSide < 0 ? "left" : "right") + " pickup"
                + ", wrist " + wristDirection;
        float[] supine = enterSupineBed(engine, initialRotation, 0, context);
        long timeNs = 200 * MILLIS;
        float[] q = supine;
        // 90 degrees reaches the first side, 180 faces up, 270 reaches the opposite side.
        for (int angle = 2; angle <= 180; angle += 2) {
            q = rotate(supine, 0, 1, 0, -pickupSide * angle);
            timeNs += SAMPLE_STEP_NS;
            feedPose(engine, q, timeNs);
            check(engine.inBed() && engine.rotation() == initialRotation,
                    context + " putting the tablet down must preserve the reading direction");
        }

        checkNear(deviceGravityFromWorldUp(q)[2], 9.81f, context + " hold must be face up");
        for (int sample = 0; sample < 150; sample++) {
            timeNs += SAMPLE_STEP_NS;
            feedPose(engine, q, timeNs);
            check(engine.inBed() && engine.rotation() == initialRotation,
                    context + " must stay in bed throughout the 3-second flat hold");
        }

        for (int angle = 182; angle <= 270; angle += 2) {
            q = rotate(supine, 0, 1, 0, -pickupSide * angle);
            timeNs += SAMPLE_STEP_NS;
            feedPose(engine, q, timeNs);
            check(engine.inBed() && engine.rotation() == initialRotation,
                    context + " side pickup must retain direction without recalibration");
        }
        checkNear(deviceGravityFromWorldUp(q)[2], 0, context + " pickup must finish on its side");

        replayWristRoundTrip(
                engine,
                q,
                context,
                wristDirection,
                90,
                timeNs + SAMPLE_STEP_NS,
                0.0f,
                0.0f,
                1.0f);
        check(engine.inBed(), context + " wrist round trip must remain in bed");
        check(engine.rotation() == initialRotation,
                context + " wrist return must restore the picked-up direction");
        checkNear(engine.spin(), 0.0f, context + " wrist round-trip spin");
    }

    private static void replaySideLyingHold(
            boolean manual,
            int initialRotation,
            int side,
            float tiltDegrees,
            int wristDirection) {
        OrientationEngine engine = new OrientationEngine(initialRotation, 55);
        String context = (manual ? "manual" : "automatic")
                + (side < 0 ? " left" : " right")
                + " side " + tiltDegrees + " degrees";

        if (manual) {
            engine.calibrate(initialRotation);
        } else {
            engine.gravity(0.0f, 0.0f, -9.81f, 0L);
            engine.gravity(0.0f, 0.0f, -9.81f, 150 * MILLIS);
        }
        check(engine.inBed(), context + " must enter bed mode");
        check(engine.rotation() == initialRotation, context + " must preserve direction on entry");

        float[] sideGravity = sideLyingGravity(side, tiltDegrees);
        engine.gravity(sideGravity[0], sideGravity[1], sideGravity[2], 200 * MILLIS);
        engine.gravity(sideGravity[0], sideGravity[1], sideGravity[2], 2_200 * MILLIS);
        engine.gravity(sideGravity[0], sideGravity[1], sideGravity[2], 4_200 * MILLIS);
        check(engine.inBed(), context + " must remain in bed after several seconds");
        check(engine.rotation() == initialRotation, context + " must not trigger desk rotation");

        float[] q = unitQuaternion(0.0);
        feed(engine, q, 4_300 * MILLIS);
        feed(engine, rotate(q, 0.0f, 0.0f, 1.0f, wristDirection * 90.0f), 4_400 * MILLIS);
        int expectedRotation = (initialRotation + wristDirection + 4) % 4;
        check(engine.rotation() == expectedRotation,
                context + " must still allow signed wrist rotation");

        engine.gravity(sideGravity[0], sideGravity[1], sideGravity[2], 5_500 * MILLIS);
        check(engine.inBed() && engine.rotation() == expectedRotation,
                context + " must retain the wrist-selected direction while in bed");
    }

    private static void manualSideCalibrationAndReset() {
        OrientationEngine engine = new OrientationEngine(1, 55);
        engine.calibrate(3);
        check(engine.inBed(), "manual side-lying calibration must enter bed mode");
        check(engine.rotation() == 3, "manual calibration must bind the supplied display rotation");

        engine.gravity(-8.5f, 4.9f, 0.0f, 0L);
        check(engine.inBed(), "manual side-lying mode must survive side-normal gravity");
        engine.manualStep();
        check(engine.rotation() == 0, "manual step must wrap through all four quadrants");

        engine.resetMotion();
        check(engine.rotation() == 0 && engine.inBed(), "motion reset must preserve logical state");
        checkNear(engine.spin(), 0.0f, "motion reset spin");
        float[] first = unitQuaternion(10.0);
        feed(engine, first, 100 * MILLIS);
        checkNear(engine.spin(), 0.0f, "first quaternion after reset must only establish a reference");
        feed(engine, rotate(first, 0.0f, 0.0f, 1.0f, 90.0f), 110 * MILLIS);
        check(engine.rotation() == 1, "wrist tracking must resume after motion reset");
    }

    private static void supineBodyRollAndWristRoundTrips() {
        for (int initialRotation : new int[] {0, 1}) {
            for (int betaDegrees : new int[] {-40, 40}) {
                for (int bodyDirection : new int[] {-1, 1}) {
                    for (int wristDirection : new int[] {-1, 1}) {
                        replaySupineBodyRollAndWrist(
                                initialRotation,
                                betaDegrees,
                                bodyDirection,
                                wristDirection,
                                90);
                        replaySupineBodyRollAndWrist(
                                initialRotation,
                                betaDegrees,
                                bodyDirection,
                                wristDirection,
                                180);
                    }
                }
            }
        }
    }

    private static void replaySupineBodyRollAndWrist(
            int initialRotation,
            int betaDegrees,
            int bodyDirection,
            int wristDirection,
            int wristDegrees) {
        OrientationEngine engine = new OrientationEngine(initialRotation, 55);
        String context = "initial rotation " + initialRotation
                + ", beta " + betaDegrees
                + ", body direction " + bodyDirection
                + ", wrist direction " + wristDirection
                + ", wrist " + wristDegrees + " degrees";
        float[] supine = enterSupineBed(engine, initialRotation, betaDegrees, context);

        double betaRadians = Math.toRadians(betaDegrees);
        float bodyAxisY = (float) Math.cos(betaRadians);
        float bodyAxisZ = (float) Math.sin(betaRadians);
        long timeNs = 200 * MILLIS;
        float[] rolled = supine;
        for (int angle = 2; angle <= 90; angle += 2) {
            rolled = rotate(
                    supine,
                    0.0f,
                    bodyAxisY,
                    bodyAxisZ,
                    bodyDirection * angle);
            feedPose(engine, rolled, timeNs);
            check(engine.inBed(), context + " body roll must remain in bed");
            check(engine.rotation() == initialRotation,
                    context + " body roll at " + angle + " degrees must not request rotation");
            timeNs += SAMPLE_STEP_NS;
        }

        replayWristRoundTrip(
                engine,
                rolled,
                context,
                wristDirection,
                wristDegrees,
                timeNs,
                0.0f,
                0.0f,
                1.0f);
    }

    private static void tiltedWristRoundTrips() {
        for (int initialRotation : new int[] {0, 1}) {
            for (int wristDirection : new int[] {-1, 1}) {
                replayTiltedWristRoundTrip(
                        initialRotation,
                        wristDirection,
                        20,
                        90);
            }
        }
    }

    private static void replayTiltedWristRoundTrip(
            int initialRotation,
            int wristDirection,
            int tiltDegrees,
            int wristDegrees) {
        OrientationEngine engine = new OrientationEngine(initialRotation, 55);
        String context = "initial rotation " + initialRotation
                + ", wrist direction " + wristDirection
                + ", wrist " + wristDegrees + " degrees"
                + ", wrist-axis tilt " + tiltDegrees + " degrees";
        float[] supine = enterSupineBed(engine, initialRotation, 0, context);
        double tiltRadians = Math.toRadians(tiltDegrees);
        replayWristRoundTrip(
                engine,
                supine,
                context,
                wristDirection,
                wristDegrees,
                200 * MILLIS,
                (float) Math.sin(tiltRadians),
                0.0f,
                (float) Math.cos(tiltRadians));
    }

    private static float[] enterSupineBed(
            OrientationEngine engine,
            int initialRotation,
            int betaDegrees,
            String context) {
        float[] supine = rotate(
                unitQuaternion(0.0),
                1.0f,
                0.0f,
                0.0f,
                180.0f - betaDegrees);
        feedPose(engine, supine, 0L);
        feedPose(engine, supine, 149 * MILLIS);
        check(!engine.inBed(), context + " must not enter bed before its hold time");
        feedPose(engine, supine, 150 * MILLIS);
        check(engine.inBed(), context + " static supine pose must enter bed mode");
        check(engine.rotation() == initialRotation,
                context + " bed entry must preserve the initial rotation");
        return supine;
    }

    private static void replayWristRoundTrip(
            OrientationEngine engine,
            float[] base,
            String context,
            int wristDirection,
            int wristDegrees,
            long timeNs,
            float axisX,
            float axisY,
            float axisZ) {
        int startingRotation = engine.rotation();
        for (int angle = 10; angle <= wristDegrees; angle += 10) {
            float[] wrist = rotate(
                    base,
                    axisX,
                    axisY,
                    axisZ,
                    wristDirection * angle);
            feedPose(engine, wrist, timeNs);
            check(engine.inBed(), context + " active wrist turn must remain in bed");
            timeNs += SAMPLE_STEP_NS;
        }
        int expectedRotation = (startingRotation
                + wristDirection * (wristDegrees / 90) + 4) % 4;
        check(engine.rotation() == expectedRotation,
                context + " active wrist turn must request "
                        + (wristDegrees / 90) + " step(s)");

        for (int angle = wristDegrees - 10; angle >= 0; angle -= 10) {
            float[] wrist = rotate(
                    base,
                    axisX,
                    axisY,
                    axisZ,
                    wristDirection * angle);
            feedPose(engine, wrist, timeNs);
            check(engine.inBed(), context + " wrist return must remain in bed");
            timeNs += SAMPLE_STEP_NS;
        }
        check(engine.rotation() == startingRotation,
                context + " wrist return must restore the initial rotation");
    }

    private static void feedPose(OrientationEngine engine, float[] q, long timeNs) {
        float[] gravity = deviceGravityFromWorldUp(q);
        engine.gravity(gravity[0], gravity[1], gravity[2], timeNs);
        feed(engine, q, timeNs);
    }

    private static float[] deviceGravityFromWorldUp(float[] q) {
        double w = q[0];
        double x = q[1];
        double y = q[2];
        double z = q[3];
        return new float[] {
                (float) (2.0 * 9.81 * (x * z - w * y)),
                (float) (2.0 * 9.81 * (y * z + w * x)),
                (float) (9.81 * (1.0 - 2.0 * (x * x + y * y)))
        };
    }

    private static void feed(OrientationEngine engine, float[] q, long timeNs) {
        engine.quaternion(q[0], q[1], q[2], q[3], timeNs);
    }

    private static float[] unitQuaternion(double degrees) {
        double half = Math.toRadians(degrees) / 2.0;
        return new float[] {(float) Math.cos(half), 0.0f, 0.0f, (float) Math.sin(half)};
    }

    private static float[] rotate(float[] q, float axisX, float axisY, float axisZ, float degrees) {
        double length = Math.sqrt(axisX * axisX + axisY * axisY + axisZ * axisZ);
        double x = axisX / length;
        double y = axisY / length;
        double z = axisZ / length;
        double half = degrees * Math.PI / 360.0;
        double sin = Math.sin(half);
        double qw = Math.cos(half);
        double qx = x * sin;
        double qy = y * sin;
        double qz = z * sin;

        double w = q[0] * qw - q[1] * qx - q[2] * qy - q[3] * qz;
        double nx = q[0] * qx + q[1] * qw + q[2] * qz - q[3] * qy;
        double ny = q[0] * qy - q[1] * qz + q[2] * qw + q[3] * qx;
        double nz = q[0] * qz + q[1] * qy - q[2] * qx + q[3] * qw;
        double norm = Math.sqrt(w * w + nx * nx + ny * ny + nz * nz);
        return new float[] {(float) (w / norm), (float) (nx / norm), (float) (ny / norm), (float) (nz / norm)};
    }

    private static float[] screenUpGravity(double degreesFromHorizontal) {
        double radians = Math.toRadians(degreesFromHorizontal);
        return new float[] {
                0.0f,
                (float) (9.81 * Math.sin(radians)),
                (float) (9.81 * Math.cos(radians))
        };
    }

    private static float[] sideLyingGravity(int side, double degreesFromVertical) {
        double radians = Math.toRadians(degreesFromVertical);
        return new float[] {
                (float) (side * 9.81 * Math.cos(radians)),
                0.0f,
                (float) (9.81 * Math.sin(radians))
        };
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static void checkNear(float actual, float expected, String message) {
        if (Math.abs(actual - expected) > EPSILON) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

}
