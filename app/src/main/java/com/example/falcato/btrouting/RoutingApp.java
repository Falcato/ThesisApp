package com.example.falcato.btrouting;

import android.app.Application;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class RoutingApp extends Application {

    private static final String TAG = "RoutingApp";
    private boolean hasNet;
    public Map<String, Integer> routeTable = new HashMap<>();

    public boolean getHasNet() { return hasNet; }

    public void setHasNet(boolean hasNet) {
        this.hasNet = hasNet;
    }

    public void updateRouteTable (String msg) {
        String dest = msg.split(";")[1];
        int hops = Integer.parseInt(msg.split(";")[2]);

        // If already contains current node
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
        }
    }

    public int getMinHop() {
        int minHop = Collections.min(routeTable.values());
        Log.i(TAG, "Minimal nr of hops is: " + minHop);
        return minHop;
    }

}
