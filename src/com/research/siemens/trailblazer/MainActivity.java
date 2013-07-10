package com.research.siemens.trailblazer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.PowerManager;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
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
    public static final int GPS_FREQ = 6000; //GPS update frequency in milliseconds

    // location manager for managing GPS location updates
    LocationManager locationManager;

    float peak;		// threshold for step detection
    float alpha;    // value for low pass filter
    int stepTimeoutM;   // distance in ms between each step
    float stride;   // stride length

    boolean started = false; // check if started
    boolean stepped = false; // in a given trial, see if a step or reading has been taken
    boolean dataPause = false; // pause data collection during labeling
    boolean appIsPausing = false; // check if onPause has been called

    double initHead = -1;    // note down initial heading
    double lastHead = -1;    // save most recent heading

    double locX = 0;
    double locY = 0;
    double locZ = 0;

    double latitude = -1;
    double longitude = -1;
    float accuracy = -1;

    //turn debug output on or off
    int devModeClicks = 0;
    boolean devMode = false;

    StepDetection stepDetection; // global step detector
    JSONArray sessionData = new JSONArray(); // store session data

    // power manager instantiations
    PowerManager pm;
    PowerManager.WakeLock wl;

    //create a copy of the app context
    Context thisCopy;

    /**
     * Activity life cycle.
     */

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        //keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        //instantiate location manager after onCreate() is called, like you should
        locationManager = (LocationManager) this.getSystemService(Context.LOCATION_SERVICE);

        //copy context to this
        thisCopy = this;
    }

    protected void onResume() {
        super.onResume();
        appIsPausing = false;
    }

    protected void onPause(){
        super.onPause();
        appIsPausing = true;

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

    public void makeToast(String message, boolean flag) {
        Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
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
     * Notes on JSON event format (these get packaged together and sent to server):
     * 1. steps look like {'type' : 'relative', 'x' : 123, 'y' : 123, 'time' : 12312, 'heading' : 180}
     * 2. GPS locations are like {'type' : 'absolute', 'latitude' : 31.41412, 'longitude' : 56.12331,
     *                          'heading' : 167, 'time' : 12311, 'accuracy' : 12}
     * 3. labels look like {'type' : 'label', 'content' : 'Room 201', 'time' : 12415}
     */

    /**
     * Called to get things going.
     */

    public void brief() {
        //load settings and create step detector
        loadSettings();
        stepDetection = new StepDetection(this, this, alpha, peak, stepTimeoutM);
        stepDetection.load();

        //enable and disable label and calibrate buttons, respectively
        Button calibrate = (Button) findViewById(R.id.tools);
        calibrate.setText("Label");

        //make sure screen turn-offs don't impact step detection
        //pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        //wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TrailOn");
        //wl.acquire(); //actually start the wake-lock

        //listen to location updates, from network and GPS, but network is pretty inaccurate
        //locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0, 0, locationListener);
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, GPS_FREQ, 0, locationListener);

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            gpsAlert();
        }

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

        latitude = -1;
        longitude = -1;
        accuracy = -1;
    }

    /**
     * Called to get things stopped.
     */

    public void debrief() {
        stepDetection.unload();
        stepDetection = null;
        started = false;

        //enable and disable label and calibrate buttons, respectively
        Button label = (Button) findViewById(R.id.tools);
        label.setText("Calibrate");

        //cancel wake-lock
        //wl.release();

        TextView status = (TextView) findViewById(R.id.status);
        status.setText("No errors detected.");

        //un-register location listener
        locationManager.removeUpdates(locationListener);

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
     * Called when keys are pressed.
     */

    /*@Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        switch(keyCode) {
            case KeyEvent.KEYCODE_MENU:
                devMode();
                return true;
        }

        return super.onKeyDown(keyCode, e);
    }*/

    /**
     * Called when the menu button is clicked.
     * Enables development mode after nine clicks.
     */

    public void devMode(View v) {
        if (devModeClicks == 9) {
            if (devMode) {
                makeToast("Disabling dev mode.");
                devMode = false;
                devModeClicks = 0;
            }

            else {
                makeToast("Enabling dev mode.");
                devMode = true;
                devModeClicks = 0;
            }
        }

        else {
            devModeClicks++;
        }
    }

    /**
     * Called when the calibrate button is clicked.
     */

    public void toolsButton(View v) {
        if (!started) {
            Intent intent = new Intent(this, Calibrator.class);
            startActivity(intent);
        }

        else {
            labelAlert();
        }
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
        if (dataPause){
            return;
        }

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
            step.put("heading", compDir);
            step.put("x", locX);
            step.put("y", locY);
        }

        catch (JSONException e){
            //just needed so Java/IDEA won't complain
        }

        sessionData.put(step); //add step to data object
        TextView status = (TextView) findViewById(R.id.status);
        status.setText("Heading: " + tr(Double.toString(compDir), 3) + "\nX-Axis: " + tr(Double.toString(locX), 5)
                + "\nY-Axis: " + tr(Double.toString(locY), 5) + "\n\nLat: " + tr(Double.toString(latitude), 7)
                + "\nLon: " + tr(Double.toString(longitude), 7) + "\nAccuracy: " + Float.toString(accuracy));
    }

    @Override
    public void dataHookAcc(long now_ms, double x, double y, double z) {
        //default body
    }

    @Override
    public void dataHookComp(long now_ms, double x, double y, double z) {
        lastHead = x; //save compass azimuth reading to last heading
    }

    @Override
    public void timedDataHook(long now_ms, double[] acc, double[] comp) {
        //default body
    }

    /**
     * Subclass for listening to GPS location updates.
     */

    // Define a listener that responds to location updates
    LocationListener locationListener = new LocationListener() {
        // Called when a new location is found by the network location provider.
        public void onLocationChanged(Location location) {
            accuracy = location.getAccuracy();
            latitude = location.getLatitude();
            longitude = location.getLongitude();

            JSONObject loc = new JSONObject();

            try {
                loc.put("time", location.getTime());
                loc.put("type", "absolute");
                loc.put("heading", lastHead);
                loc.put("latitude", latitude);
                loc.put("longitude", longitude);
                loc.put("accuracy", accuracy);
            }

            catch (JSONException e){
                //just needed so Java/IDEA won't complain
            }

            sessionData.put(loc); //add step to data object
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {}
        public void onProviderEnabled(String provider) {}
        public void onProviderDisabled(String provider) {}
    };

    /**
     * Dialog boxes.
     */

    private void gpsAlert() {
        if (dataPause){
            return;
        }

        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage("Your GPS seems to be disabled; do you want to enable it? " +
                "Doing so will add context and accuracy to your submitted data.")
                .setCancelable(false)

                .setPositiveButton("Yes", new DialogInterface.OnClickListener() {
                    public void onClick(final DialogInterface dialog, final int id) {
                        startActivity(new Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                    }
                })

                .setNegativeButton("No", new DialogInterface.OnClickListener() {
                    public void onClick(final DialogInterface dialog, final int id) {
                        dialog.cancel();
                    }
                });

        final AlertDialog alert = builder.create();
        alert.show();
    }

    private void labelAlert() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);

        //pause data collection
        dataPause = true;

        //editable input box
        final EditText input = new EditText(this);

        builder.setMessage("Please use the following box to set a short but descriptive name" +
                " for your current location, such as Library, Cafeteria, or Room 201.").setCancelable(false)

                .setPositiveButton("Confirm", new DialogInterface.OnClickListener() {
                    public void onClick(final DialogInterface dialog, final int id) {
                        String content = input.getText().toString();
                        JSONObject label = new JSONObject();

                        try {
                            label.put("time", System.currentTimeMillis());
                            label.put("type", "label");
                            label.put("content", content);
                        }

                        catch (JSONException e){
                            //just needed so Java/IDEA won't complain
                        }

                        sessionData.put(label); //add step to data object
                        makeToast("Label added!");
                        dataPause = false;
                    }
                })

                .setNegativeButton("Oops", new DialogInterface.OnClickListener() {
                    public void onClick(final DialogInterface dialog, final int id) {
                        dialog.cancel();
                        dataPause = false;
                    }
                });

        final AlertDialog alert = builder.create();
        alert.setView(input, 25, 0, 25, 25);
        alert.show();
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

                if (devMode && !appIsPausing){
                    new AlertDialog.Builder(thisCopy)
                        .setMessage(out)
                        .setPositiveButton("Cool", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int which) {}
                        }).create().show();
                }
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

    /**
     * Various tools.
     */

    //truncate string to l chars
    public String tr(String s, int l){
        if (s.charAt(0) == '-') { l++; }
        s = s.substring(0, Math.min(s.length(), l));

        if (s.charAt(s.length() - 1) == '.') {
            return s.substring(0, s.length() - 1);
        }

        else {
            return s;
        }
    }
}
