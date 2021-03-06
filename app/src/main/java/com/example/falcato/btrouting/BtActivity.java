package com.example.falcato.btrouting;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class BtActivity extends Activity {

    // Debugging
    private static final String TAG = "BluetoothActivity";
    private static final boolean D = true;

    Button goButton;
    EditText mEdit;
    WebView mWebview;

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;
    public static final int FILE_READ = 6;
    public static final int FILE_WRITE = 7;

    // Key names received from the BluetoothService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    // Intent request codes
    private static final int REQUEST_ENABLE_BT = 3;

    // Name of the connected device
    private String mConnectedDeviceName = null;
    // List of peer devices
    private ArrayList<BluetoothDevice> peers = null;
    // String buffer for outgoing messages
    private StringBuffer mOutStringBuffer;
    // Local Bluetooth adapter
    private BluetoothAdapter mBluetoothAdapter = null;
    // Member object for the chat services
    private BluetoothService mService = null;
    // Check if peer discovery is finished
    private boolean discoveryFinished = false;
    // Save the message ID
    private int msgID = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if(D) Log.e(TAG, "+++ ON CREATE +++");

        // Set up the window layout
        setContentView(R.layout.activity_bt);

        // Get local Bluetooth adapter
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Initialize peers array
        peers = new ArrayList<>();

        // If the adapter is null, then Bluetooth is not supported
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

    }

    @Override
    public void onStart() {
        super.onStart();
        if(D) Log.e(TAG, "++ ON START ++");

        // If BT is not on, request that it be enabled.
        if (!mBluetoothAdapter.isEnabled()) {
            Log.e(TAG, "mBluetoothAdapter not enabled");
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, REQUEST_ENABLE_BT);

        } else {
            Log.e(TAG, "mBluetoothAdapter enabled");
        }

        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        this.registerReceiver(mReceiver, filter);

        // Register for broadcasts when discovery has finished
        filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        this.registerReceiver(mReceiver, filter);

        mWebview = (WebView) findViewById(R.id.webView);
        mWebview.getSettings().setJavaScriptEnabled(true);
        mWebview.getSettings().setAllowFileAccess(true);
        mWebview.getSettings().setDomStorageEnabled(true);
        mWebview.getSettings().setAllowContentAccess(true);
        mWebview.getSettings().setAllowFileAccessFromFileURLs(true);
        mWebview.getSettings().setJavaScriptCanOpenWindowsAutomatically(true);

        // Make this device discoverable
        ensureDiscoverable();

        // update route table and start discovery
        // If device has net
        if(((RoutingApp)getApplicationContext()).getHasNet())
            ((RoutingApp)getApplicationContext()).updateRouteTable
                ("ADV;" + getOwnMAC() + ";0");
        // Otherwise infinite number of hops
        else
            ((RoutingApp)getApplicationContext()).updateRouteTable
                    ("ADV;" + getOwnMAC() + ";16");

        // Remove comments
        doDiscovery();
        setupBluetoothService();

        goButton = (Button) findViewById(R.id.buttonGo);
        mEdit = (EditText)findViewById(R.id.editText);
        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Test
                //getPage(mEdit.getText().toString());

                // Remove comments
                sendRequest(true, -1, mEdit.getText().toString());
            }
        });

        // Repeat discovery process every 5 min
        Timer timer = new Timer();
        timer.scheduleAtFixedRate(new Discovery(), 300000, 300000);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mService != null) mService.stop();
        if(D) Log.e(TAG, "--- ON DESTROY ---");
        unregisterReceiver(mReceiver);
    }

    private String getOwnMAC () {
        if (Build.VERSION.SDK_INT > 22)
            return android.provider.Settings.Secure.getString(this.getContentResolver(),
                "bluetooth_address");
        else
            return mBluetoothAdapter.getAddress();
    }

    private void ensureDiscoverable() {
        if(D) Log.i(TAG, "ensure discoverable");
        if (mBluetoothAdapter.getScanMode() !=
                BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent discoverableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 0);
            startActivity(discoverableIntent);
        }
    }

    private void doDiscovery() {
        if (D) Log.i(TAG, "doDiscovery()");
        discoveryFinished = false;

        // If we're already discovering, stop it
        if (mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
        }

        mBluetoothAdapter.startDiscovery();
        Toast.makeText(getApplicationContext(), "Starting discovery. Please wait...",
                Toast.LENGTH_SHORT).show();
    }

    private void setupBluetoothService() {
        Log.i(TAG, "setupBluetoothService()");
        // Initialize the BluetoothChatService to perform bluetooth connections
        mService = new BluetoothService(this, mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");
    }

    private void advertisePeers() {
        Log.i(TAG, "advertisePeers()");
        for (BluetoothDevice peer : peers){
            Log.i(TAG, "Peer class: " + peer.getBluetoothClass().getDeviceClass());
            // Check if peer is a cell phone
            if (peer.getBluetoothClass().getDeviceClass() == 524){
                //Debug
                //if(!peer.getName().contains(";Moto")) {
                // Check if peer is using app
                if (peer.getName().contains(";")) {
                    Log.i(TAG, "Will advertise to: " + peer.getName());
                    mService.connect(peer);

                    // Wait until connection is done
                    while (mService.getState() != BluetoothService.STATE_CONNECTED) {
                        if (mService.getState() == BluetoothService.STATE_LISTEN) {
                            Log.e(TAG, "Failed to connect, listening");
                            //mService.start();
                            break;
                        }
                    }

                    // Device is connected, advertise
                    int nrHops = ((RoutingApp) getApplicationContext()).getMinHop() + 1;
                    sendMessage("ADV;" + getOwnMAC() + ";" + nrHops);

                    // Wait until connection is finished
                    long initTime = System.currentTimeMillis();
                    while (mService.getState() != BluetoothService.STATE_LISTEN) {
                        if (System.currentTimeMillis() - initTime > 5000) {
                            break;
                        }
                    }
                }
                //Debug
                //}
            }
        }
        mService.start();
    }

    private void sendRequest(boolean owner, int msgID, String message) {
        Log.i(TAG, "sendRequest()");

        // Get the address of the next hop
        BluetoothDevice nextHop = mBluetoothAdapter.getRemoteDevice(
                ((RoutingApp)getApplicationContext()).getNextHop());
        // In case there is no next hop
        if (nextHop.getAddress() == null){
            Log.i(TAG, "There is no next hop");
            sendFail(msgID);
            return;
        }

        if (nextHop.equals(getOwnMAC())){
            mWebview.getSettings().setCacheMode( WebSettings.LOAD_CACHE_ELSE_NETWORK );
            mWebview.setVisibility(View.VISIBLE);
            mWebview.getSettings().setJavaScriptEnabled(true);
            // #1 try to send a re-request
            mWebview.setWebViewClient(new WebViewClient());
            mWebview.setWebChromeClient(new WebChromeClient());
            mWebview.loadUrl(message);
            return;
        }

        Log.i(TAG, "Will send request to: " + nextHop.getAddress());
        mService.connect(nextHop);

        // Wait until connection is done
        for (int aux = 0; mService.getState() != BluetoothService.STATE_CONNECTED; aux ++){
            if (mService.getState() == BluetoothService.STATE_LISTEN){
                Log.e(TAG, "Failed to connect, listening");
                mService.start();
                // Failed to connect send fail notification
                sendFail(msgID);
                return;
            }
        }

        // If device is the one sending the request
        if (owner) {
            // Device is connected, send request
            Random rn = new Random();
            int newMsgID = rn.nextInt();
            // Message -> RQT ; Message ID ; Own MAC ; Data
            sendMessage("RQT;" + newMsgID + ";" + getOwnMAC() + ";" + message);
            // Update the response table with own MAC to know when the response is received
            ((RoutingApp)getApplicationContext()).updateRspTable(newMsgID, getOwnMAC());
        // If device is forwarding the request
        }else{
            // Message -> RQT ; Message ID ; Own MAC ; Data
            sendMessage("RQT;" + msgID + ";" + getOwnMAC() + ";" + message);
        }
    }

    private void sendResponse(int msgID) {
        Log.i(TAG, "sendResponse(int msgID) " + msgID);
        // Retrieve the requester's MAC
        String nextHopMAC = ((RoutingApp)getApplicationContext()).getRspHop(msgID);
        // If message ID exists in the response table
        if (nextHopMAC != null){
            // Get the address of the next hop
            BluetoothDevice nextHopDevice = mBluetoothAdapter.getRemoteDevice(nextHopMAC);
            Log.i(TAG, "Will send response to: " + nextHopDevice.getAddress());
            mService.connect(nextHopDevice);

            // Wait until connection is done
            for (int aux = 0; mService.getState() != BluetoothService.STATE_CONNECTED; aux ++){
                if (mService.getState() == BluetoothService.STATE_LISTEN){
                    Log.e(TAG, "Failed to connect, listening");
                    mService.start();
                    return;
                }
            }
            // Send the response message
            sendMessage("RSP;" + msgID);
        }else{
            Log.i(TAG, "MAC for requested Message ID not found");
        }
    }

    private void sendFail(int msgID) {
        Log.i(TAG, "sendFail(int msgID) " + msgID);
        // Retrieve the requester's MAC
        String nextHopMAC = ((RoutingApp)getApplicationContext()).getRspHop(msgID);
        // If message ID exists in the response table
        if (nextHopMAC != null){
            // Get the address of the next hop
            BluetoothDevice nextHopDevice = mBluetoothAdapter.getRemoteDevice(nextHopMAC);
            Log.i(TAG, "Will send fail notification to: " + nextHopDevice.getAddress());
            mService.connect(nextHopDevice);

            // Wait until connection is done
            for (int aux = 0; mService.getState() != BluetoothService.STATE_CONNECTED; aux ++){
                if (mService.getState() == BluetoothService.STATE_LISTEN){
                    Log.e(TAG, "Failed to connect, listening");
                    mService.start();
                    return;
                }
            }
            // Send the response message
            sendMessage("FAIL;" + msgID);
        }else{
            Log.i(TAG, "MAC for requested Message ID not found");
        }
    }

    private void sendMessage(String message) {
        // Check that we're actually connected before trying anything
        if (mService.getState() != BluetoothService.STATE_CONNECTED) {
            Log.e(TAG, "Not connected, can't send message");
            return;
        }

        // Check that there's actually something to send
        if (message.length() > 0) {
            // Get the message bytes and tell the BluetoothChatService to write
            byte[] send = message.getBytes();
            mService.write(send);

            // Reset out string buffer to zero and clear the edit text field
            mOutStringBuffer.setLength(0);
        }
    }

    private void sendFile() {
        Log.i(TAG, "sendFile()");

        File file = new File(getFilesDir() + "file.mht");
        int size = (int) file.length();
        byte[] bytes = new byte[size];

        try {
            BufferedInputStream buf = new BufferedInputStream(new FileInputStream(file));
            buf.read(bytes, 0, bytes.length);
            buf.close();
        } catch (IOException e) {
            e.printStackTrace();
        }

        mService.writeFile(bytes);

        Log.i(TAG, "sent file with " + bytes.length + " bytes in: " + getFilesDir() + "file.mht");
    }

    private void analyzeMessage(String message) {

        // Advertising message
        if (message.contains("ADV")){
            // Check if new shortest path was found
            if (Integer.parseInt(message.split(";")[2]) <
                    ((RoutingApp)getApplicationContext()).getMinHop()){
                Log.i(TAG, "New best path will advertise");
                // If so, update table, initiate discovery and advertise new path
                ((RoutingApp)getApplicationContext()).updateRouteTable(message);
                discoveryFinished = false;
                doDiscovery();
            }else{
                Log.i(TAG, "New path is not the best will not advertise");
                // Otherwise update table and continue listening
                ((RoutingApp)getApplicationContext()).updateRouteTable(message);
            }

        // Request message
        }else if (message.contains("RQT")){
            // Save the message ID
            msgID = Integer.parseInt(message.split(";")[1]);
            // Update the response table
            ((RoutingApp)getApplicationContext()).updateRspTable(
                    Integer.parseInt(message.split(";")[1]), message.split(";")[2]);

            // If device is not connected to the Internet
            if (!((RoutingApp)getApplicationContext()).getHasNet()){
                // Forward the request
                sendRequest(false, Integer.parseInt(message.split(";")[1]), message.split(";")[3]);
            // If it is connected, fetch the web page and send the response
            }else{
                getPage(message.split(";")[3], Integer.parseInt(message.split(";")[1]));
            }

        // Response message
        }else if (message.contains("RSP")){

            // Save message ID to know the file name
            msgID = Integer.parseInt(message.split(";")[1]);

            // If device is the destination
            if (((RoutingApp)getApplicationContext()).getRspHop(Integer.parseInt(
                    message.split(";")[1])).equals(getOwnMAC())){
                // Request was successfully sent and response was received
                Log.i(TAG, "Received my response.");

            // Otherwise forward response to destination
            }else{
                // Forward the response
                Log.i(TAG, "Not the final destination will forward response.");
            }
        }else if (message.contains("FAIL")){
            // Save message ID to know the file name
            msgID = Integer.parseInt(message.split(";")[1]);

            // If device is the destination
            if (((RoutingApp)getApplicationContext()).getRspHop(Integer.parseInt(
                    message.split(";")[1])).equals(getOwnMAC())){
                // Request was successfully sent but response failed
                Toast.makeText(getApplicationContext(), "Unfortunately there was a problem along " +
                                "the path. Please try again later.",
                        Toast.LENGTH_SHORT).show();

                // Otherwise forward response to destination
            }else{
                // Forward the failed request
                Log.i(TAG, "Not the final destination will forward fail notification.");
                sendFail(msgID);
            }
        }
    }

    private void getPage(String url, final int msgID){
        Log.i(TAG, "getPage(String url, final int messageID)");
        //mWebview.setVisibility(View.VISIBLE);
        mWebview.getSettings().setJavaScriptEnabled(true);
        mWebview.setWebViewClient(new WebViewClient() {
              public void onPageFinished(WebView view, String url) {
                  // Fix to load all pages and not send 0 bytes

                  File file = new File(getFilesDir() + "file.mht");

                  try {
                      file.createNewFile();
                  } catch (IOException e) {
                      e.printStackTrace();
                  }

                  view.saveWebArchive(getFilesDir() + "file.mht");
                  //mWebview.setVisibility(View.INVISIBLE);

                  waitForWebPage dloader = new waitForWebPage();
                  dloader.execute();
              }
        });
        mWebview.loadUrl("https://" + url);
    }

    @Override
    public void onBackPressed(){
        // If a page is being displayed, let the user enter a new URL
        if (mWebview.getVisibility() == View.VISIBLE) {
            mWebview.setVisibility(View.INVISIBLE);
        // Otherwise act normally and go back
        }else{
            super.onBackPressed();
        }
    }

    /* --- Logic to display the file --- */

    private void loadPage(){
        Log.i(TAG, "loading page...");

        mWebview.getSettings().setCacheMode( WebSettings.LOAD_CACHE_ELSE_NETWORK );
        mWebview.setVisibility(View.VISIBLE);
        mWebview.getSettings().setJavaScriptEnabled(true);
        // #1 try to send a re-request
        mWebview.setWebViewClient(new WebViewClient(){
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Log.i(TAG, "No Internet connection, sending a re-request of: " +
                            request.getUrl().toString().split("://")[1]);
                    view.loadUrl("about:blank");
                    sendRequest(true, -1, request.getUrl().toString().split("://")[1]);
                }
            }
        });
        mWebview.setWebChromeClient(new WebChromeClient());

        if (Build.VERSION.SDK_INT < 22) {
            loadArchive();
        } else {
            mWebview.loadUrl("file:///" + getFilesDir() + "file.mht");
        }

        // Debug
        File file = new File(getFilesDir() + "file.mht");
        Log.i(TAG, "Loaded page in: file:///" + getFilesDir() + "file.mht with " +
                file.length());
    }

    private void loadArchive(){
        String rawData = null;
        try {
            rawData = getStringFromFile(getFilesDir() + "file.mht");
        } catch (Exception e) {
            e.printStackTrace();
        }
        mWebview.loadDataWithBaseURL(null, rawData, "application/x-webarchive-xml", "UTF-8", null);
    }

    public String getStringFromFile (String filePath) throws Exception {
        File fl = new File(filePath);
        FileInputStream fin = new FileInputStream(fl);
        String ret = convertStreamToString(fin);
        //Make sure you close all streams.
        fin.close();
        return ret;
    }

    public  String convertStreamToString(InputStream is) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(is));
        StringBuilder sb = new StringBuilder();
        String line = null;
        while ((line = reader.readLine()) != null) {
            sb.append(line).append("\n");
        }
        reader.close();
        return sb.toString();
    }

    /* --- End of logic --- */

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();

        // When discovery finds a device
        if (BluetoothDevice.ACTION_FOUND.equals(action)) {
            Log.i(TAG, "Found a device.");
            // Get the BluetoothDevice object from the Intent
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            // If new peer not counted already add it to peer list
            if (!peers.contains(device)) {
                peers.add(device);
            }
            // When discovery is finished, change the Activity title
        } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
            if(!discoveryFinished) {
                TextView peerText = (TextView) findViewById(R.id.textViewPeers);
                Log.i(TAG, "Discovery finished.");
                // Stop the discovery
                mBluetoothAdapter.cancelDiscovery();
                discoveryFinished = true;
                Toast.makeText(getApplicationContext(), "Discovery finished",
                        Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Peers found: " + peers.toString());
                peerText.setText("Peers found: " + peers.toString());
                advertisePeers();
            }
        }
        }
    };

    // The Handler that gets information back from the BluetoothChatService
    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_STATE_CHANGE:
                    if(D) Log.i(TAG, "MESSAGE_STATE_CHANGE: " + msg.arg1);
                    switch (msg.arg1) {
                        case BluetoothService.STATE_CONNECTED:
                            Log.e(TAG, "Status: connected");
                            break;
                        case BluetoothService.STATE_CONNECTING:
                            Log.e(TAG, "Status: connecting");
                            break;
                        case BluetoothService.STATE_LISTEN:
                            Log.e(TAG, "Status: listen");
                            break;
                        case BluetoothService.STATE_NONE:
                            Log.e(TAG, "Status: none");
                            break;
                    }
                    break;
                case MESSAGE_WRITE:
                    byte[] writeBuf = (byte[]) msg.obj;
                    // construct a string from the buffer
                    String writeMessage = new String(writeBuf);
                    // handle sent message
                    Log.i(TAG, "Sent a new message: " + writeMessage);

                    // If a response was sent follow with the corresponding file
                    if (writeMessage.contains("RSP;")){
                        sendFile();
                    }
                    break;
                case MESSAGE_READ:
                    byte[] readBuf = (byte[]) msg.obj;
                    // construct a string from the valid bytes in the buffer
                    String readMessage = new String(readBuf, 0, msg.arg1);
                    // handle received message
                    TextView recvText = (TextView) findViewById(R.id.textViewReceived);
                    Log.i(TAG, "Received a new message: " + readMessage);
                    recvText.setText(recvText.getText() + "\n" + readMessage);

                    // Restart the Bluetooth Service
                    if (!readMessage.contains("RSP;")) {
                        mService.start();
                    }else {
                        //mService.fileReady = true;
                    }
                    analyzeMessage(readMessage);
                    break;
                case MESSAGE_DEVICE_NAME:
                    // save the connected device's name
                    mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                    Log.i(TAG, "Connected to " + mConnectedDeviceName);
                    break;
                case MESSAGE_TOAST:
                    break;

                case FILE_READ:
                    Log.i(TAG, "Received a new file");

                    byte[] readFileBuf = (byte[]) msg.obj;

                    //Debug
                    //String readFileMessage = new String(readFileBuf, 0, msg.arg1);
                    //Log.i(TAG, readFileMessage);

                    try {

                        File file = new File(getFilesDir() + "file.mht");
                        if (!file.exists()) {
                            file.createNewFile();
                        }

                        FileOutputStream stream = new FileOutputStream(
                                getFilesDir() + "file.mht", false);
                        stream.write(readFileBuf);
                        stream.flush();
                        stream.close();

                        Log.i(TAG, "Saved the file in: " + getFilesDir() + "file.mht with " +
                        file.length());
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Restart the Bluetooth Service
                    mService.start();
                    mService.fileReady = false;

                    // If I am the destination
                    if (((RoutingApp)getApplicationContext()).getRspHop(msgID).equals(getOwnMAC())) {
                        // Display the page
                        loadPage();
                    }else{
                        sendResponse(msgID);
                    }

                    break;

                case FILE_WRITE:
                    byte[] writeFileBuf = (byte[]) msg.obj;
                    try {

                        File file = new File(getFilesDir() + "file.mht");
                        if (!file.exists()) {
                            file.createNewFile();
                        }

                        FileOutputStream stream = new FileOutputStream(
                                getFilesDir() + "file.mht", false);
                        stream.write(writeFileBuf);
                        stream.flush();
                        stream.close();

                        Log.i(TAG, "Saved the file in: " + getFilesDir() + "file.mht");
                    } catch (FileNotFoundException e) {
                        e.printStackTrace();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    // Display the page
                    loadPage();
                    break;
            }
        }
    };

    private class waitForWebPage extends AsyncTask<Void, Void, Void>{
        File file = new File(getFilesDir() + "file.mht");
        @Override
        protected Void doInBackground(Void... params) {
            Log.i(TAG, "Downloading webpage...");
            while(!(file.length() > 0)){
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void result){
            Log.i(TAG, "saved web archive in: " + getFilesDir() + "file.mht with " +
                    file.length() + " bytes");
            sendResponse(msgID);
        }
    }

    private class Discovery extends TimerTask{
        @Override
        public void run() {
            // Clear previous routing table entries
            ((RoutingApp)getApplicationContext()).clearRouteTable();

            // Make device discoverable
            ensureDiscoverable();

            // Update route table and start discovery
            // If device has net
            if(((RoutingApp)getApplicationContext()).getHasNet())
                ((RoutingApp)getApplicationContext()).updateRouteTable
                        ("ADV;" + getOwnMAC() + ";0");

            // Otherwise infinite number of hops
            else
                ((RoutingApp)getApplicationContext()).updateRouteTable
                        ("ADV;" + getOwnMAC() + ";16");

            // Remove comments
            doDiscovery();
        }
    }
}
