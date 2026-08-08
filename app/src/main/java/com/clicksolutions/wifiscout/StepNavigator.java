package com.clicksolutions.wifiscout;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.util.Log;
import android.view.Surface;
import android.view.WindowManager;

/**
 * תנועה לפי Accelerometer בלבד — אמין על כל הטלפונים.
 * אלגוריתם:
 *  1. מנרמל את וקטור התאוצה (מסיר כבידה)
 *  2. מוצא פיקים חיוביים = צעד קדימה
 *  3. משתמש ב-Rotation Vector לכיוון
 */
public class StepNavigator implements SensorEventListener {

    private static final String TAG = "StepNavigator";

    public interface PositionCallback {
        void onPositionUpdate(float x, float y);
    }

    private static final float STEP_SIZE_PX  = 50f;

    // פרמטרי זיהוי צעד
    private static final float GRAVITY       = 9.81f;
    private static final float THRESHOLD     = 1.8f;  // סף תאוצה מעל כבידה (m/s²)
    private static final long  MIN_STEP_MS   = 300;   // מינימום זמן בין צעדים
    private static final float ALPHA_GRAVITY = 0.85f; // Low-pass לכבידה

    // Low-pass לכיוון
    private static final float ALPHA_AZIMUTH = 0.08f;

    // נעילת כיוון: סטייה קטנה מהצעד הקודם = ממשיכים ישר.
    // מבטל "קו עקום" בהליכה ישרה שנגרם מריצוד המצפן בתוך הבית.
    private static final float HEADING_LOCK_RAD = (float) Math.toRadians(20);

    private final SensorManager    sensorManager;
    private final PositionCallback callback;
    private final int              screenRotation;

    private float   posX, posY;
    private float   azimuthRad   = 0f;
    private float   smoothAzimuth = 0f;
    private float   lastStepAzimuth = Float.NaN;
    private boolean firstAzimuth  = true;
    private boolean running        = false;
    private String  sensorSource   = "none";

    // Accelerometer state
    private float   gravX = 0, gravY = 0, gravZ = GRAVITY;
    private float   lastLinearMag  = 0f;
    private boolean wasAboveThresh = false;
    private long    lastStepTime   = 0;

    public StepNavigator(Context context, PositionCallback callback) {
        this.sensorManager  = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        this.callback       = callback;
        WindowManager wm    = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.screenRotation = wm.getDefaultDisplay().getRotation();
    }

    public void start(float startX, float startY) {
        posX          = startX;
        posY          = startY;
        running       = false;
        firstAzimuth  = true;
        lastStepAzimuth = Float.NaN;
        wasAboveThresh = false;
        lastStepTime  = 0;
        gravX = 0; gravY = 0; gravZ = GRAVITY;

        // Rotation vector לכיוון
        Sensor rotation = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        if (rotation != null)
            sensorManager.registerListener(this, rotation, SensorManager.SENSOR_DELAY_GAME);

        // נסה Step Detector ראשון
        Sensor stepDet = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR);
        if (stepDet != null) {
            sensorManager.registerListener(this, stepDet, SensorManager.SENSOR_DELAY_GAME);
            sensorSource = "STEP_DETECTOR";
            Log.d(TAG, "Using STEP_DETECTOR");
        }

        // תמיד רשום גם Accelerometer כ-fallback
        Sensor accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            if (stepDet == null) sensorSource = "ACCELEROMETER";
            Log.d(TAG, "Accelerometer registered as fallback");
        }

        running = true;
    }

    public void stop() {
        running = false;
        sensorManager.unregisterListener(this);
    }

    public boolean hasStepSensor() {
        return sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null;
    }

    public String getSensorSource() { return sensorSource; }

    public int getAzimuthDeg() {
        return ((int) Math.toDegrees(smoothAzimuth) + 360) % 360;
    }

    // ── SensorEventListener ──────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running) return;

        switch (event.sensor.getType()) {

            case Sensor.TYPE_ROTATION_VECTOR:
                updateAzimuth(event.values);
                break;

            case Sensor.TYPE_STEP_DETECTOR:
                // Step Detector של סמסונג — אם מגיע, טוב
                onStep("SD");
                break;

            case Sensor.TYPE_ACCELEROMETER:
                detectStepFromAccel(event.values);
                break;
        }
    }

    private void updateAzimuth(float[] rotVec) {
        float[] rotMatrix   = new float[9];
        float[] remapped    = new float[9];
        float[] orientation = new float[3];

        SensorManager.getRotationMatrixFromVector(rotMatrix, rotVec);

        int axisX, axisY;
        switch (screenRotation) {
            case Surface.ROTATION_90:
                axisX = SensorManager.AXIS_Y;
                axisY = SensorManager.AXIS_MINUS_X;
                break;
            case Surface.ROTATION_270:
                axisX = SensorManager.AXIS_MINUS_Y;
                axisY = SensorManager.AXIS_X;
                break;
            default:
                axisX = SensorManager.AXIS_X;
                axisY = SensorManager.AXIS_Y;
                break;
        }
        SensorManager.remapCoordinateSystem(rotMatrix, axisX, axisY, remapped);
        SensorManager.getOrientation(remapped, orientation);
        float raw = orientation[0];

        if (firstAzimuth) {
            smoothAzimuth = raw;
            firstAzimuth  = false;
        } else {
            float diff = raw - smoothAzimuth;
            if (diff >  Math.PI) diff -= 2 * Math.PI;
            if (diff < -Math.PI) diff += 2 * Math.PI;
            smoothAzimuth += ALPHA_AZIMUTH * diff;
        }
        azimuthRad = smoothAzimuth;
    }

    /**
     * זיהוי צעד מהאקסלרומטר:
     * 1. Low-pass filter → מוציא רכיב הכבידה
     * 2. מחשב תאוצה ליניארית (בלי כבידה)
     * 3. מזהה עלייה מעל THRESHOLD ואז ירידה → צעד
     */
    private void detectStepFromAccel(float[] v) {
        // עדכן כבידה עם low-pass
        gravX = ALPHA_GRAVITY * gravX + (1 - ALPHA_GRAVITY) * v[0];
        gravY = ALPHA_GRAVITY * gravY + (1 - ALPHA_GRAVITY) * v[1];
        gravZ = ALPHA_GRAVITY * gravZ + (1 - ALPHA_GRAVITY) * v[2];

        // תאוצה ליניארית (High-pass)
        float linX = v[0] - gravX;
        float linY = v[1] - gravY;
        float linZ = v[2] - gravZ;
        float mag  = (float) Math.sqrt(linX*linX + linY*linY + linZ*linZ);

        // Peak detection
        if (!wasAboveThresh && mag > THRESHOLD) {
            wasAboveThresh = true;
        } else if (wasAboveThresh && mag < THRESHOLD * 0.5f) {
            wasAboveThresh = false;
            long now = System.currentTimeMillis();
            if (now - lastStepTime > MIN_STEP_MS) {
                lastStepTime = now;
                onStep("ACC");
            }
        }
        lastLinearMag = mag;
    }

    private void onStep(String source) {
        float az = azimuthRad;
        if (!Float.isNaN(lastStepAzimuth)) {
            float diff = az - lastStepAzimuth;
            if (diff >  Math.PI) diff -= 2 * Math.PI;
            if (diff < -Math.PI) diff += 2 * Math.PI;
            // small wobble → walk straight; real turn → follow it
            if (Math.abs(diff) < HEADING_LOCK_RAD) az = lastStepAzimuth;
        }
        lastStepAzimuth = az;
        posX += STEP_SIZE_PX * (float) Math.sin(az);
        posY -= STEP_SIZE_PX * (float) Math.cos(az);
        Log.d(TAG, source + " Step X=" + (int)posX + " Y=" + (int)posY
                + " az=" + getAzimuthDeg() + "°");
        if (callback != null) callback.onPositionUpdate(posX, posY);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}