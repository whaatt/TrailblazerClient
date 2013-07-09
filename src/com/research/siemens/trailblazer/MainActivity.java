package com.research.siemens.trailblazer;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import de.uvwxy.footpath.core.StepDetection;
import de.uvwxy.footpath.core.StepTrigger;
import de.uvwxy.footpath.gui.Calibrator;

public class MainActivity extends Activity implements StepTrigger {

    public static final String CALIBRATION = "TrailblazerSettings"; //the name of our sharedPreferences file

    float peak;		// threshold for step detection
    float alpha;    // value for low pass filter
    int stepTimeoutM;   // distance in ms between each step
    float stride;   // stride length

    boolean started = false; // check if started
    double initHead = -1;    // note down initial heading

    double locX = 0;
    double locY = 0;
    double locZ = 0;

    StepDetection stepDetection; // global step detector

    /**
     * Activity life cycle.
     */

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);
    }

    protected void onResume() {
        super.onResume();
    }

    protected void onPause() {
        super.onPause();
    }

    /**
     * Called when the exit button is clicked.
     */

    public void exitButton(View v) {
        finish();
    }

    /**
     * Called when the calibrate button is clicked.
     */

    public void calibrateButton(View v) {
        Intent intent = new Intent(this, Calibrator.class);
        startActivity(intent);
    }

    /**
     * Called when the start button is clicked.
     */

    public void startButton(View v) {
        if (!started){
            //load settings and create step detector
            loadSettings();
            stepDetection = new StepDetection(this, this, alpha, peak, stepTimeoutM);
            stepDetection.load();

            TextView status = (TextView) findViewById(R.id.status);
            status.setText("Waiting for movement.");

            Button sButton = (Button) findViewById(R.id.run);
            sButton.setText("Stop");
            started = true;

            //reset initial position data
            initHead = -1;    // note down initial heading

            locX = 0;
            locY = 0;
            locZ = 0;
        }

        else{
            stepDetection.unload();
            stepDetection = null;
            started = false;

            TextView status = (TextView) findViewById(R.id.status);
            status.setText("No errors detected.");

            Button sButton = (Button) findViewById(R.id.run);
            sButton.setText("Start");
        }
    }

    /**
     * Load settings from sharedPreferences.
     */

    private void loadSettings(){
        //0.4, 2, 333, 0.5 are good all-purpose values for calibration
        alpha = getSharedPreferences(CALIBRATION, 0).getFloat("a", 0.4f);
        peak = getSharedPreferences(CALIBRATION, 0).getFloat("peak", 1.2f);
        stepTimeoutM = getSharedPreferences(CALIBRATION, 0).getInt("timeout", 333);
        stride = getSharedPreferences(CALIBRATION,0).getFloat("stride", 0.5f);
    }

    /**
     * Methods that implement StepTrigger from FootPath.
     */

    @Override
    public void trigger(long nowMS, double compDir) {
        //must be declared final to use in anon class
        final long nowMSF = nowMS;
        final double compDirF = compDir;

        runOnUiThread(new Runnable() {
            public void run() {
                onStep(nowMSF, compDirF);
            }
        });
    }

    //Actually handle trigger steps.
    public void onStep(long nowMS, double compDir){
        if (initHead == -1){
            initHead = compDir;
        }

        double heading = Math.toRadians(compDir - initHead); //heading is relative to original
        locX += stride * Math.sin(heading);
        locY += stride * Math.cos(heading);

        TextView status = (TextView) findViewById(R.id.status);
        status.setText("Heading: " + Double.toString(compDir) + "\nTime: " + Long.toString(nowMS)
            + "\n\nX: " + Double.toString(locX) + "\nY: " + Double.toString(locY));
    }

    @Override
    public void dataHookAcc(long now_ms, double x, double y, double z) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void dataHookComp(long now_ms, double x, double y, double z) {
        //To change body of implemented methods use File | Settings | File Templates.
    }

    @Override
    public void timedDataHook(long now_ms, double[] acc, double[] comp) {
        //To change body of implemented methods use File | Settings | File Templates.
    }
}
