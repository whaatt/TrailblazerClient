package com.research.siemens.trailblazer;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class MainActivity extends Activity implements SensorEventListener {
    private SensorManager mSensorManager;
    private Sensor mAccelerometer;

    private int iterations = 0;

    private double dt = 0;
    private long newStamp = 0;
    private long lastStamp = System.currentTimeMillis();

    //initial accelerations
    private double accX = 0;
    private double accY = 0;
    private double accZ = 0;

    //initial velocities
    private double velX = 0;
    private double velY = 0;
    private double velZ = 0;

    //initial positions
    private double locX = 0;
    private double locY = 0;
    private double locZ = 0;

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        mSensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        mAccelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_FASTEST);
    }

    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        //ignore this at the moment
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        TextView status = (TextView)findViewById(R.id.status);

        double[] gravity = new double[3];
        final double alpha = 0.9;

        gravity[0] = alpha * gravity[0] + (1 - alpha) * event.values[0];
        gravity[1] = alpha * gravity[1] + (1 - alpha) * event.values[1];
        gravity[2] = alpha * gravity[2] + (1 - alpha) * event.values[2];

        newStamp = System.currentTimeMillis();
        dt = (float) (newStamp - lastStamp) / (float) 1000;
        lastStamp = newStamp;

        accX = event.values[0] - gravity[0];
        accY = event.values[1] - gravity[1];
        accZ = event.values[2] - gravity[2];

        //accX = event.values[0];
        //accY = event.values[1];
        //accZ = event.values[2];

        if (iterations > 10){
            velX = velX + accX * dt;
            velY = velY + accY * dt;
            velZ = velZ + accZ * dt;

            locX = locX + velX * dt;
            locY = locY + velY * dt;
            locZ = locZ + velZ * dt;
        }

        iterations++;

        status.setText("Acc: (" + Double.toString(accX) + ", " + Double.toString(accY) + ", " + Double.toString(dt) + ")\n"
            + "Vel: (" + Double.toString(velX) + ", " + Double.toString(velY) + ", " + Long.toString(newStamp) + ")\n"
            +"Loc: (" + Double.toString(locX) + ", " + Double.toString(locY) + ", " + Long.toString(lastStamp) + ")");
    }

    /**
     * Called when the exit button is clicked.
     */
    public void exitButton(View v) {
        finish();
    }
}
