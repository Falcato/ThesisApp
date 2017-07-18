package com.example.falcato.btrouting;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

public class InitActivity extends Activity {

    private static final String TAG = "InitActivity";
    Button goButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_init);
    }

    @Override
    protected void onStart (){
        super.onStart();

        // Check if device has an Internet connection
        new NetworkCheck().execute();

        if (Build.VERSION.SDK_INT > 22)
            this.requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION}, 1);

        goButton = (Button) findViewById(R.id.button);
        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent ( InitActivity.this, BtActivity.class );
                startActivity(intent);
            }
        });
    }

    private class NetworkCheck extends AsyncTask<Void, Void, Boolean> {

        @Override
        protected Boolean doInBackground(Void... params) {
            Log.i(TAG, "hasActiveInternetConnection()");
            try {
                HttpURLConnection urlc = (HttpURLConnection) (
                        new URL("http://clients3.google.com/generate_204").openConnection());
                urlc.setRequestProperty("User-Agent", "Test");
                urlc.setRequestProperty("Connection", "close");
                urlc.setConnectTimeout(1500);
                urlc.connect();
                return (urlc.getResponseCode() == 204 && urlc.getContentLength() == 0);
            } catch (IOException e) {
                Log.e(TAG, "Error checking internet connection", e);
            }
            return false;
        }

        @Override
        protected void onPostExecute(Boolean result) {
            ((RoutingApp) getApplicationContext()).setHasNet(result);
        }
    }
}
