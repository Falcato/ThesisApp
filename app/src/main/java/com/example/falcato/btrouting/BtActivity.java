package com.example.falcato.btrouting;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;


public class BtActivity extends Activity {

    // Debugging
    private static final String TAG = "BluetoothActivity";
    private static final boolean D = true;

    // Global application variables
    public RoutingApp gv;

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
    private static final int REQUEST_CONNECT_DEVICE_SECURE = 1;
    private static final int REQUEST_CONNECT_DEVICE_INSECURE = 2;
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

         gv = ((RoutingApp)getApplicationContext());

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
        // Register for broadcasts when a device is discovered
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        this.registerReceiver(mReceiver, filter);

        // Register for broadcasts when discovery has finished
        filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        this.registerReceiver(mReceiver, filter);

        // Make this device discoverable
        ensureDiscoverable();
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
            // Setup the session
            doDiscovery();
            // Otherwise, setup the chat session
        } else {
            Log.e(TAG, "mBluetoothAdapter enabled");
            gv.updateRouteTable("ADV;" + mBluetoothAdapter.getAddress() + ";999");
            doDiscovery();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // Stop the Bluetooth chat services
        if (mService != null) mService.stop();
        if(D) Log.e(TAG, "--- ON DESTROY ---");
        unregisterReceiver(mReceiver);
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
    }

    private void setupBluetoothService() {
        Log.i(TAG, "setupBluetoothService()");
        // Initialize the BluetoothChatService to perform bluetooth connections
        mService = new BluetoothService(this, mHandler);

        // Initialize the buffer for outgoing messages
        mOutStringBuffer = new StringBuffer("");

        // Start advertising
        advertisePeers();

        // Restart the service listening for connections
        mService.start();
    }

    private void advertisePeers() {
        Log.i(TAG, "advertisePeers()");
        for (BluetoothDevice peer : peers){
            // Check if peer is using app
            if (peer.getName().contains(";")){
                Log.i(TAG, "Will advertise to: " + peer.getName());
                mService.connect(peer);

                // Wait until connection is done
                for (int aux = 0; mService.getState() != BluetoothService.STATE_CONNECTED; aux ++){
                    if (mService.getState() == BluetoothService.STATE_LISTEN){
                        Log.e(TAG, "Failed to connect, listening");
                        return;
                    }
                }

                // Device is connected, advertise
                sendMessage("ADV;" + mBluetoothAdapter.getAddress() + ";" + gv.getMinHop());
            }
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
        // Advertising message
        if (message.contains("ADV")){
            Log.i(TAG, "Advertising message");
            // Check if new shortest path was found
            if (Integer.parseInt(message.split(";")[2]) < gv.getMinHop()){
                // If so, update table, initiate discovery and advertise new path
                gv.updateRouteTable(message);
                discoveryFinished = false;
                doDiscovery();
            }else{
                // Otherwise update table and continue listening
                gv.updateRouteTable(message);
            }

        // Request message
        }else if (message.contains("RQT")){
            Log.i(TAG, "Request message");

        // Response message
        }else if (message.contains("RSP")){
            Log.i(TAG, "Response message");
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
                TextView peerText = (TextView) findViewById(R.id.textView);
                Log.i(TAG, "Discovery finished.");
                // Stop the discovery
                mBluetoothAdapter.cancelDiscovery();
                discoveryFinished = true;
                peerText.setText(peerText.getText() + peers.toString());
                setupBluetoothService();
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
                        Log.i(TAG, "Status: connected");
                        break;
                    case BluetoothService.STATE_CONNECTING:
                        Log.i(TAG, "Status: connecting");
                        break;
                    case BluetoothService.STATE_LISTEN:
                        Log.i(TAG, "Status: listening");
                    case BluetoothService.STATE_NONE:
                        Log.i(TAG, "Status: none");
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
                Log.i(TAG, "Received a new message: " + readMessage);
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
