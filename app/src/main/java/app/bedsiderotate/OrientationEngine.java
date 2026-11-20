package app.bedsiderotate;

/**
 * Android-free conversion from gravity and rotation vectors to Surface rotation.
 *
 * <p>Gravity uses Android device coordinates. The returned rotation is always
 * Surface.ROTATION_0, ROTATION_90, ROTATION_180, or ROTATION_270, including for
 * devices whose natural orientation is landscape. Positive gravity X therefore
 * maps to rotation 1.</p>
 *
 * <p>Quaternion composition uses Hamilton multiplication. Adjacent wrist spin is
 * measured as qPrevious^-1 * qCurrent, so a local wrist sample is qPrevious * qZ.
 * The threshold is the wrist angle, in degrees, that triggers one 90-degree screen
 * step in bed mode. Its supported range is 46 through 85 degrees, with 55 as the
 * reference default. Both timeNs arguments are monotonic nanosecond readings. An
 * older sample from the same sensor is ignored. Gravity drives desk/bed state while
 * adjacent quaternion samples drive wrist spin.</p>
 */
public final class OrientationEngine {
    private static final double RAD_TO_DEG = 180.0 / Math.PI;
    private static final double DEG_TO_RAD = Math.PI / 180.0;

    private static final double FLAT_TILT_NORMALIZED = Math.sin(8.0 * DEG_TO_RAD);
    private static final double DESK_HYSTERESIS_DEG = 10.0;
    private static final double TRIGGER_EPSILON_DEG = 1.0e-6;
    private static final double MANUAL_RELEASE_DEG = 12.0;
    // A reversal must retrace nearly half a cell before it can undo the preceding step.
    private static final double MIN_REVERSAL_AFTER_STEP_DEG = 45.0;
    private static final int MIN_THRESHOLD_DEG = 46;
    private static final int MAX_THRESHOLD_DEG = 85;

    private static final double BED_ENTER_Z_NORMALIZED = -0.20;
    private static final double BED_ENTER_CANCEL_Z_NORMALIZED = -0.10;
    // A slightly upward-facing screen is common when reading on either side.
    private static final double DESK_FACE_UP_Z_NORMALIZED = Math.cos(30.0 * DEG_TO_RAD);
    private static final long BED_ENTER_HOLD_NS = 150_000_000L;
    private static final long FACE_UP_HOLD_NS = 1_500_000_000L;

    private int rotation;
    private double thresholdDegrees;
    private boolean inBed;
    private boolean autoExitBedWhenFlat = true;

    private boolean haveGravity;
    private long lastGravityTimeNs;
    private double gravityX;
    private double gravityY;
    private double gravityZ;
    private double gravityNorm;
    private float gu;
    private float gv;
    private float gz;
    private float alpha;
    private float radius;

    private boolean bedEntryCandidate;
    private long bedEntryStartNs;
    private boolean deskExitCandidate;
    private long deskExitStartNs;

    private boolean manualDeskHold;
    private boolean haveManualGravity;
    private double manualGravityX;
    private double manualGravityY;
    private double manualGravityZ;

    private boolean haveQuaternion;
    private long lastQuaternionTimeNs;
    private double previousQuaternionW;
    private double previousQuaternionX;
    private double previousQuaternionY;
    private double previousQuaternionZ;

    private double pendingSpinDegrees;
    private double spinDegrees;
    private int lastStepDirection;
    private double reversalExtremeSpinDegrees;

    public OrientationEngine(int initialRotation, int threshold) {
        this.rotation = requireSurfaceRotation(initialRotation);
        setThreshold(threshold);
    }

    public void setThreshold(int threshold) {
        if (threshold < MIN_THRESHOLD_DEG || threshold > MAX_THRESHOLD_DEG) {
            throw new IllegalArgumentException("threshold must be in [46, 85] degrees");
        }
        thresholdDegrees = threshold;
        applyTriggeredSteps();
    }

    public void setAutoExitBedWhenFlat(boolean enabled) {
        autoExitBedWhenFlat = enabled;
        if (!enabled) {
            deskExitCandidate = false;
        }
    }

    /**
     * Clears motion history and waits for a fresh first sample after screen reopen.
     * The logical rotation and desk/bed mode are retained. The first quaternion
     * after this call establishes the next adjacent-sample reference only.
     */
    public void resetMotion() {
        haveGravity = false;
        lastGravityTimeNs = Long.MIN_VALUE;
        gravityX = 0.0;
        gravityY = 0.0;
        gravityZ = 0.0;
        gravityNorm = 0.0;
        gu = 0.0f;
        gv = 0.0f;
        gz = 0.0f;
        alpha = 0.0f;
        radius = 0.0f;

        bedEntryCandidate = false;
        deskExitCandidate = false;
        if (!inBed) {
            haveManualGravity = false;
        } else {
            manualDeskHold = false;
            haveManualGravity = false;
        }

        haveQuaternion = false;
        lastQuaternionTimeNs = Long.MIN_VALUE;
        pendingSpinDegrees = 0.0;
        spinDegrees = 0.0;
        lastStepDirection = 0;
        reversalExtremeSpinDegrees = 0.0;
    }

    public void gravity(float x, float y, float z, long timeNs) {
        requireFinite(x, y, z);
        if (haveGravity && timeNs < lastGravityTimeNs) {
            return;
        }

        haveGravity = true;
        lastGravityTimeNs = timeNs;
        gravityX = x;
        gravityY = y;
        gravityZ = z;
        gravityNorm = Math.sqrt(gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ);
        updateEyeAxes();
        updateBedState(timeNs);

        if (!inBed && manualDeskHold && keepManualDeskRotationUntilPostureChanges()) {
            return;
        }
        if (!inBed && !bedEntryCandidate) {
            updateDeskRotation();
        }
    }

    public void quaternion(float w, float x, float y, float z, long timeNs) {
        requireFinite(w, x, y, z);
        double length = Math.sqrt(w * w + x * x + y * y + z * z);
        if (length < 1.0e-6 || (haveQuaternion && timeNs < lastQuaternionTimeNs)) {
            return;
        }

        double currentW = w / length;
        double currentX = x / length;
        double currentY = y / length;
        double currentZ = z / length;

        if (!haveQuaternion) {
            storePreviousQuaternion(currentW, currentX, currentY, currentZ);
            lastQuaternionTimeNs = timeNs;
            return;
        }

        if (dotPrevious(currentW, currentX, currentY, currentZ) < 0.0) {
            currentW = -currentW;
            currentX = -currentX;
            currentY = -currentY;
            currentZ = -currentZ;
        }

        double[] relative = relativeQuaternion(
                previousQuaternionW,
                previousQuaternionX,
                previousQuaternionY,
                previousQuaternionZ,
                currentW,
                currentX,
                currentY,
                currentZ);
        storePreviousQuaternion(currentW, currentX, currentY, currentZ);
        lastQuaternionTimeNs = timeNs;
        if (inBed) {
            double deltaDegrees = wristDeltaDegrees(relative);
            pendingSpinDegrees += deltaDegrees;
            spinDegrees += deltaDegrees;
            updateReversalExtreme();
            applyTriggeredSteps();
        }
    }

    /**
     * Binds the supplied device display rotation and enters bed mode immediately.
     * This is the manual path for side-lying poses where the screen-normal gravity
     * is too close to zero for a reliable automatic desk/bed decision.
     */
    public void calibrate(int rotation) {
        this.rotation = requireSurfaceRotation(rotation);
        inBed = true;
        manualDeskHold = false;
        resetMotion();
    }

    /**
     * Restores a managed quadrant after an external interruption. Bed/desk mode
     * is retained, and the first gravity sample becomes the desk hold reference.
     */
    public void restoreManagedRotation(int rotation) {
        this.rotation = requireSurfaceRotation(rotation);
        resetMotion();
        updateEyeAxes();
        holdDeskRotationAtCurrentGravity();
    }

    /**
     * Steps rotation forward manually, refreshes the logical axes, and holds this
     * direction until gravity changes by at least 12 degrees. The hold covers fixed
     * emulator orientations and a manual step before the first gravity sample. In
     * bed mode the remaining wrist movement is also rebased.
     */
    public void manualStep() {
        rotation = normalizeRotation(rotation + 1);
        pendingSpinDegrees = 0.0;
        spinDegrees = 0.0;
        lastStepDirection = 0;
        reversalExtremeSpinDegrees = 0.0;
        updateEyeAxes();

        holdDeskRotationAtCurrentGravity();
    }

    public int rotation() {
        return rotation;
    }

    public boolean inBed() {
        return inBed;
    }

    public float alpha() {
        return alpha;
    }

    public float radius() {
        return radius;
    }

    public float gu() {
        return gu;
    }

    public float gv() {
        return gv;
    }

    public float gz() {
        return gz;
    }

    /** Signed accumulated wrist angle in degrees since the latest bed binding. */
    public float spin() {
        return (float) spinDegrees;
    }

    private void updateBedState(long timeNs) {
        if (gravityNorm < 1.0e-6) {
            return;
        }
        double normalizedZ = gravityZ / gravityNorm;

        if (!inBed) {
            if (normalizedZ <= BED_ENTER_Z_NORMALIZED) {
                if (!bedEntryCandidate) {
                    bedEntryCandidate = true;
                    bedEntryStartNs = timeNs;
                }
                if (timeNs - bedEntryStartNs >= BED_ENTER_HOLD_NS) {
                    enterBed();
                }
            } else if (normalizedZ >= BED_ENTER_CANCEL_Z_NORMALIZED) {
                bedEntryCandidate = false;
            }
            return;
        }

        if (!autoExitBedWhenFlat || normalizedZ < DESK_FACE_UP_Z_NORMALIZED) {
            deskExitCandidate = false;
        } else if (!deskExitCandidate) {
            deskExitCandidate = true;
            deskExitStartNs = timeNs;
        } else if (timeNs - deskExitStartNs >= FACE_UP_HOLD_NS) {
            leaveBed();
        }
    }

    private void enterBed() {
        inBed = true;
        bedEntryCandidate = false;
        deskExitCandidate = false;
        manualDeskHold = false;
        haveManualGravity = false;
        pendingSpinDegrees = 0.0;
        spinDegrees = 0.0;
        lastStepDirection = 0;
        reversalExtremeSpinDegrees = 0.0;
    }

    private void leaveBed() {
        inBed = false;
        bedEntryCandidate = false;
        deskExitCandidate = false;
        manualDeskHold = false;
        haveManualGravity = false;
        pendingSpinDegrees = 0.0;
        spinDegrees = 0.0;
        lastStepDirection = 0;
        reversalExtremeSpinDegrees = 0.0;
    }

    private boolean keepManualDeskRotationUntilPostureChanges() {
        if (gravityNorm < 1.0e-6) {
            return true;
        }
        if (!haveManualGravity) {
            captureManualGravity();
            return true;
        }

        double dot = (gravityX * manualGravityX
                + gravityY * manualGravityY
                + gravityZ * manualGravityZ) / gravityNorm;
        double angle = Math.acos(Math.max(-1.0, Math.min(1.0, dot))) * RAD_TO_DEG;
        if (angle < MANUAL_RELEASE_DEG) {
            return true;
        }

        manualDeskHold = false;
        haveManualGravity = false;
        return false;
    }

    private void holdDeskRotationAtCurrentGravity() {
        manualDeskHold = !inBed;
        haveManualGravity = false;
        if (manualDeskHold) {
            captureManualGravity();
        }
    }

    private void captureManualGravity() {
        if (gravityNorm < 1.0e-6) {
            haveManualGravity = false;
            return;
        }
        manualGravityX = gravityX / gravityNorm;
        manualGravityY = gravityY / gravityNorm;
        manualGravityZ = gravityZ / gravityNorm;
        haveManualGravity = true;
    }

    private void updateDeskRotation() {
        if (!haveGravity || gravityNorm < 1.0e-6) {
            return;
        }

        double horizontal = Math.hypot(gravityX, gravityY);
        // Flat poses have no reliable atan2 quadrant, so retain the current rotation.
        if (horizontal / gravityNorm < FLAT_TILT_NORMALIZED) {
            return;
        }

        double angleDegrees = Math.atan2(gravityX, gravityY) * RAD_TO_DEG;
        double currentCenter = rotation * 90.0;
        double delta = normalizeSignedDegrees(angleDegrees - currentCenter);
        if (Math.abs(delta) <= 45.0 + DESK_HYSTERESIS_DEG) {
            return;
        }

        long quadrant = Math.round(angleDegrees / 90.0);
        int nextRotation = (int) (((quadrant % 4L) + 4L) % 4L);
        if (nextRotation != rotation) {
            rotation = nextRotation;
            updateEyeAxes();
        }
    }

    private void applyTriggeredSteps() {
        boolean changed = false;
        while (pendingSpinDegrees >= thresholdDegrees - TRIGGER_EPSILON_DEG
                && reversalAllowsStep(1)) {
            reversalExtremeSpinDegrees = spinDegrees;
            rotation = normalizeRotation(rotation + 1);
            pendingSpinDegrees -= 90.0;
            lastStepDirection = 1;
            changed = true;
        }
        while (pendingSpinDegrees <= -thresholdDegrees + TRIGGER_EPSILON_DEG
                && reversalAllowsStep(-1)) {
            reversalExtremeSpinDegrees = spinDegrees;
            rotation = normalizeRotation(rotation - 1);
            pendingSpinDegrees += 90.0;
            lastStepDirection = -1;
            changed = true;
        }
        if (changed) {
            updateEyeAxes();
        }
    }

    private void updateReversalExtreme() {
        if (lastStepDirection > 0) {
            reversalExtremeSpinDegrees = Math.max(reversalExtremeSpinDegrees, spinDegrees);
        } else if (lastStepDirection < 0) {
            reversalExtremeSpinDegrees = Math.min(reversalExtremeSpinDegrees, spinDegrees);
        }
    }

    private boolean reversalAllowsStep(int direction) {
        if (lastStepDirection == 0 || lastStepDirection == direction) {
            return true;
        }
        double reversalDegrees = direction > 0
                ? spinDegrees - reversalExtremeSpinDegrees
                : reversalExtremeSpinDegrees - spinDegrees;
        return reversalDegrees >= MIN_REVERSAL_AFTER_STEP_DEG - TRIGGER_EPSILON_DEG;
    }

    private void updateEyeAxes() {
        switch (rotation) {
            case 1:
                gu = (float) -gravityY;
                gv = (float) gravityX;
                break;
            case 2:
                gu = (float) -gravityX;
                gv = (float) -gravityY;
                break;
            case 3:
                gu = (float) gravityY;
                gv = (float) -gravityX;
                break;
            default:
                gu = (float) gravityX;
                gv = (float) gravityY;
                break;
        }
        gz = (float) gravityZ;
        radius = (float) Math.sqrt((double) gu * gu + (double) gz * gz);
        alpha = (float) (Math.atan2(gu, -gz) * RAD_TO_DEG);
    }

    private void storePreviousQuaternion(double w, double x, double y, double z) {
        haveQuaternion = true;
        previousQuaternionW = w;
        previousQuaternionX = x;
        previousQuaternionY = y;
        previousQuaternionZ = z;
    }

    private double dotPrevious(double w, double x, double y, double z) {
        return previousQuaternionW * w
                + previousQuaternionX * x
                + previousQuaternionY * y
                + previousQuaternionZ * z;
    }

    private static double[] relativeQuaternion(
            double referenceW,
            double referenceX,
            double referenceY,
            double referenceZ,
            double currentW,
            double currentX,
            double currentY,
            double currentZ) {
        double inverseW = referenceW;
        double inverseX = -referenceX;
        double inverseY = -referenceY;
        double inverseZ = -referenceZ;
        return new double[] {
                inverseW * currentW - inverseX * currentX - inverseY * currentY - inverseZ * currentZ,
                inverseW * currentX + inverseX * currentW + inverseY * currentZ - inverseZ * currentY,
                inverseW * currentY - inverseX * currentZ + inverseY * currentW + inverseZ * currentX,
                inverseW * currentZ + inverseX * currentY - inverseY * currentX + inverseZ * currentW
        };
    }

    private static double wristDeltaDegrees(double[] quaternion) {
        double w = quaternion[0];
        double z = quaternion[3];
        // The vector part is the motion axis scaled by sin(angle/2). A tilted
        // body roll has a Z component even when the wrist stays still.
        if (Math.abs(z) <= Math.hypot(quaternion[1], quaternion[2])) {
            return 0.0;
        }
        double angle = 2.0 * Math.atan2(z, w);
        return normalizeSignedDegrees(angle * RAD_TO_DEG);
    }

    private static double normalizeSignedDegrees(double degrees) {
        double normalized = degrees % 360.0;
        if (normalized > 180.0) {
            normalized -= 360.0;
        } else if (normalized <= -180.0) {
            normalized += 360.0;
        }
        return normalized;
    }

    private static int requireSurfaceRotation(int rotation) {
        if (rotation < 0 || rotation > 3) {
            throw new IllegalArgumentException("rotation must be one of 0, 1, 2, 3");
        }
        return rotation;
    }

    private static int normalizeRotation(int rotation) {
        int normalized = rotation % 4;
        return normalized < 0 ? normalized + 4 : normalized;
    }

    private static void requireFinite(float w, float x, float y) {
        if (!Float.isFinite(w) || !Float.isFinite(x) || !Float.isFinite(y)) {
            throw new IllegalArgumentException("sensor values must be finite");
        }
    }

    private static void requireFinite(float w, float x, float y, float z) {
        if (!Float.isFinite(w) || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("sensor values must be finite");
        }
    }
}
