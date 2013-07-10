package com.research.siemens.trailblazer;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import de.uvwxy.footpath.core.StepDetection;
import de.uvwxy.footpath.core.StepTrigger;
import de.uvwxy.footpath.gui.Calibrator;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;

public class MainActivity extends Activity implements StepTrigger {

    public static final String CALIBRATION = "TrailblazerSettings"; // the name of our sharedPreferences file
    public static final String SESSIONS = "sessions.txt"; // where we store loc data
    public static final String SERVER = "http://www.skalon.com/trailblazer/test.php"; // server address

    float peak;		// threshold for step detection
    float alpha;    // value for low pass filter
    int stepTimeoutM;   // distance in ms between each step
    float stride;   // stride length

    boolean started = false; // check if started
    boolean stepped = false; // in a given trial, see if a step or reading has been taken
    double initHead = -1;    // note down initial heading

    double locX = 0;
    double locY = 0;
    double locZ = 0;

    StepDetection stepDetection; // global step detector
    JSONArray sessionData = new JSONArray(); // store session data

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

    protected void onPause(){
        super.onPause();
        if (started) {
            //java quirk
            try { debrief(); }
            catch (Exception ignored) {}
        }
    }

    /**
     * Make a toast to screen!
     */

    public void makeToast(String message) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show();
    }

    /**
     * Notes on data cache/post cycle (steps 2-4 dispatched from debrief):
     * 1. store readings in JSONObject sessionData (trigger)
     * 2. onPause or onStop, append sessionData to session.txt (writeJSONFile)
     * 3. attempt to send readings to server (uploadJSONFile)
     * 4a. if success: blank session.txt, toast (blank, showToast)
     * 4b. if failure: toast (showToast)
     */

    /**
     * Called to get things going.
     */

    public void brief() {
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

    /**
     * Called to get things stopped.
     */

    public void debrief() {
        stepDetection.unload();
        stepDetection = null;
        started = false;

        TextView status = (TextView) findViewById(R.id.status);
        status.setText("No errors detected.");

        Button sButton = (Button) findViewById(R.id.run);
        sButton.setText("Start");

        if (stepped) {
            writeJSONFile(SESSIONS, sessionData);
            new uploadJSONFile().execute(SESSIONS);
            stepped = false; // trial is over
        }
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

    public void startButton(View v){
        if (!started){
            brief();
        }

        else{
            debrief();
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

        JSONObject step = new JSONObject();
        stepped = true; // step has been taken

        try {
            step.put("time", nowMS);
            step.put("type", "relative");
            step.put("x", locX);
            step.put("y", locY);
        }

        catch (JSONException e){
            //just needed so Java/IDEA won't complain
        }

        sessionData.put(step); //add step to data object
        TextView status = (TextView) findViewById(R.id.status);
        status.setText("Heading: " + Double.toString(compDir) + "\nTime: " + Long.toString(nowMS)
            + "\n\nX: " + Double.toString(locX) + "\nY: " + Double.toString(locY));
    }

    @Override
    public void dataHookAcc(long now_ms, double x, double y, double z) {
        //default body
    }

    @Override
    public void dataHookComp(long now_ms, double x, double y, double z) {
        //default body
    }

    @Override
    public void timedDataHook(long now_ms, double[] acc, double[] comp) {
        //default body
    }

    /**
     * Methods that handle file I/O.
     */

    public boolean writeJSONFile(String filename, JSONArray jData) {
        FileOutputStream outputStream;
        JSONArray origJSON;
        String output;

        try {
            outputStream = openFileOutput(filename, Context.MODE_PRIVATE);
            String original = readJSONFile(filename);

            if (!original.equals("IOException")){
                origJSON = new JSONArray(original);
                origJSON.put(jData);
            }

            else{
                origJSON = new JSONArray();
                origJSON.put(jData);
            }

            output = origJSON.toString();
            byte[] outputBytes = output.getBytes();

            outputStream.write(outputBytes);
            outputStream.close();
            return true;
        }

        catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public String readJSONFile(String filename) {
        String contents = "";

        try {
            FileInputStream fIn = openFileInput(filename) ;
            InputStreamReader isr = new InputStreamReader(fIn);
            BufferedReader bReader = new BufferedReader(isr);

            String readString = bReader.readLine();
            while (readString != null) {
                contents = contents + readString ;
                readString = bReader.readLine();
            }

            isr.close();
        }

        catch (IOException ioe) {
            ioe.printStackTrace();
            return "IOException";
        }

        if (contents.equals("")){
            return "IOException"; //for consistency's sake with errors
        }

        return contents;
    }

    //this will run on an asynchronous thread for performance
    public class uploadJSONFile extends AsyncTask<String, Void, String> {
        //uploads to server, return value gives disposition
        protected String doInBackground(String... filename){
            //instantiates httpclient to make request
            DefaultHttpClient httpclient = new DefaultHttpClient();

            //instantiate StringEntity
            StringEntity se;

            //url with the post data
            HttpPost httppost = new HttpPost(SERVER);

            //convert parameters into JSON object
            String postData = readJSONFile(filename[0]);

            if (postData.equals("IOException")) {
                return "";
            }

            //passes the results to a string builder/entity
            try { se = new StringEntity(postData); }
            catch (UnsupportedEncodingException e) { return ""; }

            //sets the post request as the resulting string
            httppost.setEntity(se);

            //sets a request header so the page receiving the request
            //will know what to do with it
            httppost.setHeader("Accept", "application/json");
            httppost.setHeader("Content-type", "application/json");

            //Handles what is returned from the page
            //Catch catches situations with no internet

            try {
                HttpResponse response = httpclient.execute(httppost);
                int responseCode = response.getStatusLine().getStatusCode();

                switch(responseCode) {
                    case 200: //everything went fine
                        HttpEntity entity = response.getEntity();
                        return EntityUtils.toString(entity);

                    default:
                        return "";
                }
            }

            catch (Exception e) {
                return "";
            }
        }

        protected void onPostExecute(String out) {
            if (!out.equals("")) {
                blank(SESSIONS);
                makeToast("Data successfully uploaded.");
                makeToast("Thanks for contributing.");
            }

            else {
                makeToast("Data upload unsuccessful.");
                makeToast("I'll try again later.");
            }
        }
    }

    //blanks a file
    public boolean blank(String filename){
        FileOutputStream outputStream;

        try {
            outputStream = openFileOutput(filename, Context.MODE_PRIVATE);
            outputStream.write("".getBytes());
            outputStream.close();
            return true;
        }

        catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}
