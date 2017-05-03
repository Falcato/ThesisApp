package com.example.falcato.btrouting;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Random;


public class BtActivity extends Activity {

    // Debugging
    private static final String TAG = "BluetoothActivity";
    private static final boolean D = true;

    Button goButton;
    EditText mEdit;

    // Message types sent from the BluetoothChatService Handler
    public static final int MESSAGE_STATE_CHANGE = 1;
    public static final int MESSAGE_READ = 2;
    public static final int MESSAGE_WRITE = 3;
    public static final int MESSAGE_DEVICE_NAME = 4;
    public static final int MESSAGE_TOAST = 5;

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

        // Make this device discoverable
        ensureDiscoverable();

        // update route table and start discovery
        ((RoutingApp)getApplicationContext()).updateRouteTable
                ("ADV;" + getOwnMAC() + ";16");
        doDiscovery();
        setupBluetoothService();

        goButton = (Button) findViewById(R.id.buttonGo);
        mEdit = (EditText)findViewById(R.id.editText);
        goButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sendRequest(true, -1, mEdit.getText().toString());
            }
        });
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

        // If we're already discovering, stop it
        if (mBluetoothAdapter.isDiscovering()) {
            mBluetoothAdapter.cancelDiscovery();
        }

        BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (!bluetoothAdapter.isEnabled())
            bluetoothAdapter.enable();

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
            // Check if peer is using app
            if (peer.getName().contains(";")) {
                Log.i(TAG, "Will advertise to: " + peer.getName());
                mService.connect(peer);

                // Wait until connection is done
                for (int aux = 0; mService.getState() != BluetoothService.STATE_CONNECTED; aux ++){
                    if (mService.getState() == BluetoothService.STATE_LISTEN){
                        Log.e(TAG, "Failed to connect, listening");
                        mService.start();
                        return;
                    }
                }

                // Device is connected, advertise
                sendMessage("ADV;" + getOwnMAC() + ";" +
                        "1");
                //((RoutingApp)getApplicationContext()).getMinHop() + 1);
            }
        }
    }

    private void sendRequest(boolean owner, int msgID, String message) {
        Log.i(TAG, "sendRequest()");

        // Get the address of the next hop
        BluetoothDevice nextHop = mBluetoothAdapter.getRemoteDevice(
                ((RoutingApp)getApplicationContext()).getNextHop());
        // In case there is no next hop
        if (nextHop.getAddress() == null){
            Log.i(TAG, "There is no next hop");
            Toast.makeText(getApplicationContext(), "Unable to reach the Internet.",
                    Toast.LENGTH_SHORT).show();
            return;
        }

        Log.i(TAG, "Will send request to: " + nextHop.getAddress());
        mService.connect(nextHop);

        // Wait until connection is done
        for (int aux = 0; mService.getState() != BluetoothService.STATE_CONNECTED; aux ++){
            if (mService.getState() == BluetoothService.STATE_LISTEN){
                Log.e(TAG, "Failed to connect, listening");
                mService.start();
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

    private void sendResponse(int msgID, String message) {
        Log.i(TAG, "sendResponse()");
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
            sendMessage("RSP;" + msgID + ";" + message);
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

    private void analyzeMessage(String message) {
        // Close connection
        mService.start();

        // Advertising message
        if (message.contains("ADV")){
            Log.i(TAG, "Advertising message");
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
            Log.i(TAG, "Request message");
            // Update the response table
            ((RoutingApp)getApplicationContext()).updateRspTable(
                    Integer.parseInt(message.split(";")[1]), message.split(";")[2]);
            // Perform check if device is connected to the Internet and send response or request
            // to next hop


            // Send the response
            sendResponse(Integer.parseInt(message.split(";")[1]), message.split(";")[3]);

        // Response message
        }else if (message.contains("RSP")){
            Log.i(TAG, "Response message");
            // If device is the destination
            if (((RoutingApp)getApplicationContext()).getRspHop(Integer.parseInt(
                    message.split(";")[1])).equals(getOwnMAC())){
                // Request was successfully sent and response was received
                Log.i(TAG, "Received what I asked for.");
                TextView peerText = (TextView) findViewById(R.id.textViewPeers);
                peerText.setText("Finally!");
            // Otherwise forward response to destination
            }else{
                sendResponse(Integer.parseInt(message.split(";")[1]), message.split(";")[2]);
            }
        }
    }

    // The BroadcastReceiver that listens for discovered devices and
    // changes the title when discovery is finished
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
                break;
            case MESSAGE_READ:
                byte[] readBuf = (byte[]) msg.obj;
                // construct a string from the valid bytes in the buffer
                String readMessage = new String(readBuf, 0, msg.arg1);
                // handle received message
                TextView recvText = (TextView) findViewById(R.id.textViewReceived);
                Log.i(TAG, "Received a new message: " + readMessage);
                recvText.setText(recvText.getText() + "\n" + readMessage);
                analyzeMessage(readMessage);
                break;
            case MESSAGE_DEVICE_NAME:
                // save the connected device's name
                mConnectedDeviceName = msg.getData().getString(DEVICE_NAME);
                Log.i(TAG, "Connected to " + mConnectedDeviceName);
                break;
            case MESSAGE_TOAST:
                Toast.makeText(getApplicationContext(), msg.getData().getString(TOAST),
                        Toast.LENGTH_SHORT).show();
                break;
        }
        }
    };
}
