package ro.cobrabm.fexdroid;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Shaking the device (on a handheld such as the AYN Odin 3 the device is the controller) becomes
 * "shake" in the pad file: the runtime then reports a DualShock 4 shaken by hand, which is how
 * The Last of Us Remastered fixes a flickering flashlight (runtime_pad_bionic.c, play_shake).
 * A shake is several strong jolts in quick succession, so a single bump or tilting the device
 * while playing does not count. Settings: "motion_shake" (on/off), "motion_sensitivity".
 */
final class MotionShake implements SensorEventListener, SharedPreferences.OnSharedPreferenceChangeListener {
    /** Jolts needed within WINDOW_MS for a shake; the shake lasts HOLD_MS after the last jolt. */
    private static final int JOLTS=3;
    private static final long WINDOW_MS=600, HOLD_MS=450, JOLT_GAP_MS=60;

    private final SensorManager sensors;
    private final SharedPreferences prefs;
    private final PadBridge pad;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final long[] jolts=new long[JOLTS];
    private int joltCount;
    private long lastJolt;
    private boolean linear, listening, resumed, shaking;
    private float threshold;
    private final float[] gravity=new float[3];
    private boolean gravityReady;
    private final Runnable stop=() -> setShaking(false);

    MotionShake(Context context, PadBridge pad) {
        this.sensors=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        this.prefs=PortSettings.prefs(context);
        this.pad=pad;
        prefs.registerOnSharedPreferenceChangeListener(this);
        readSettings();
    }

    /** m/s^2 of acceleration, gravity removed, that counts as a jolt. */
    static float threshold(String sensitivity) {
        switch (sensitivity==null?"medium":sensitivity) {
            case "high": return 9f;
            case "low": return 18f;
            default: return 13f;
        }
    }

    static boolean available(Context context) {
        SensorManager s=(SensorManager)context.getSystemService(Context.SENSOR_SERVICE);
        return s!=null && (s.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)!=null ||
                           s.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)!=null);
    }

    void resume() { resumed=true; update(); }
    void pause() { resumed=false; update(); }
    void close() {
        resumed=false; update();
        prefs.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override public void onSharedPreferenceChanged(SharedPreferences p, String key) {
        if ("motion_shake".equals(key) || "motion_sensitivity".equals(key) || "shake_button".equals(key)) {
            readSettings(); update();
        }
    }

    private void readSettings() {
        threshold=threshold(prefs.getString("motion_sensitivity","medium"));
        pad.setShakeButton(prefs.getBoolean("shake_button",true));
    }

    private void update() {
        final boolean want=resumed && prefs.getBoolean("motion_shake",true) && sensors!=null;
        if (want==listening) return;
        if (want) {
            Sensor sensor=sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            linear=sensor!=null;
            if (sensor==null) sensor=sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (sensor==null) return;
            gravityReady=false; joltCount=0;
            listening=sensors.registerListener(this,sensor,SensorManager.SENSOR_DELAY_GAME,handler);
        } else {
            sensors.unregisterListener(this);
            listening=false;
            handler.removeCallbacks(stop);
            setShaking(false);
        }
    }

    @Override public void onSensorChanged(SensorEvent e) {
        float x=e.values[0], y=e.values[1], z=e.values[2];
        if (!linear) {
            // Plain accelerometer: a low-pass estimate of gravity is subtracted.
            if (!gravityReady) { gravity[0]=x; gravity[1]=y; gravity[2]=z; gravityReady=true; return; }
            final float a=0.8f;
            gravity[0]=a*gravity[0]+(1-a)*x; gravity[1]=a*gravity[1]+(1-a)*y; gravity[2]=a*gravity[2]+(1-a)*z;
            x-=gravity[0]; y-=gravity[1]; z-=gravity[2];
        }
        if (x*x+y*y+z*z<threshold*threshold) return;
        final long now=e.timestamp/1000000L;
        if (now-lastJolt<JOLT_GAP_MS) return; // one jolt, several samples
        lastJolt=now;
        jolts[joltCount%JOLTS]=now;
        ++joltCount;
        final long oldest=jolts[joltCount%JOLTS];
        if (shaking || (joltCount>=JOLTS && now-oldest<=WINDOW_MS)) {
            setShaking(true);
            handler.removeCallbacks(stop);
            handler.postDelayed(stop,HOLD_MS);
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void setShaking(boolean value) {
        if (shaking==value) return;
        shaking=value;
        pad.setSensorShake(value);
    }
}
