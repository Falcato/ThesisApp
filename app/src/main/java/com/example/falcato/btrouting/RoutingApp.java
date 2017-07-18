package com.example.falcato.btrouting;

import android.app.Application;
import android.util.Log;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

public class RoutingApp extends Application {

    private static final String TAG = "RoutingApp";

    private boolean hasNet;
    // Table with routing hops
    public Map<String, Integer> routeTable = new HashMap<>();
    // Table with MACs corresponding to message ID's
    public Map<Integer, String> rspTable = new HashMap<>();

    public boolean getHasNet () {
        Log.i(TAG, "getHasNet()");
        return hasNet;
    }

    public void setHasNet (boolean hasNet) {
        Log.i(TAG, "setHasNet() " + hasNet);
        this.hasNet = hasNet;
    }

    public void updateRouteTable (String msg) {
        Log.i(TAG, "updateRouteTable()");
        String dest = msg.split(";")[1];
        int hops = Integer.parseInt(msg.split(";")[2]);

        /*// If already contains current node
        if (routeTable.containsKey(dest)){
            // If new advertise is better than previous
            if (hops <= routeTable.get(dest))
                routeTable.put(dest, hops);
            Log.i(TAG, "Updated table: " + routeTable.toString());
            // If table doesn't contain node
        }else{
            // Insert new node and hops
            routeTable.put(dest, hops);
            Log.i(TAG, "Inserted table: " + routeTable.toString());
        }*/
        routeTable.put(dest, hops);
    }

    public void clearRouteTable () {
        Log.i(TAG, "clearRouteTable");
        routeTable.clear();
    }

    public int getMinHop () {
        Log.i(TAG, "getMinHop()");
        int minHop;
        try {
            minHop = Collections.min(routeTable.values());
        }catch (NoSuchElementException e){
            Log.e(TAG, e.toString());
            minHop = 16;
        }

        Log.i(TAG, "Minimal nr of hops is: " + minHop);
        return minHop;
    }

    public String getNextHop () {
        Log.i(TAG, "getNextHop()");
        int minHop = getMinHop();
        if (minHop == 16)
            return null;
        else
            return getKeyFromValue(routeTable, minHop);
    }

    private String getKeyFromValue (Map<String, Integer> hm, Integer value) {
        Log.i(TAG, "getKeyFromValue()");
        for (String key : hm.keySet()) {
            if (hm.get(key).equals(value)) {
                return key;
            }
        }
        return null;
    }

    public void updateRspTable (int msgID, String MAC) {
        Log.i(TAG, "updateRspTable()");
        rspTable.put(msgID, MAC);
        Log.i(TAG, "Updated message table: " + rspTable.toString());
    }

    public String getRspHop (int msgID) {
        Log.i(TAG, "getRspHop()");
        if (rspTable.containsKey(msgID))
            return rspTable.get(msgID);
        else
            return null;
    }
}