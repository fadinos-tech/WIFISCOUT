package com.clicksolutions.wifiscout;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class ScanStorage {

    private static final String PREFS_NAME = "wifi_scout_scans";
    private static final String KEY_SCANS  = "scans";
    public static final int     MAX_SCANS  = 5;

    private final SharedPreferences prefs;

    public ScanStorage(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void save(ScanRecord record) {
        try {
            List<ScanRecord> all = load();
            all.add(0, record);
            if (all.size() > MAX_SCANS) all = all.subList(0, MAX_SCANS);

            JSONArray arr = new JSONArray();
            for (ScanRecord r : all) arr.put(toJson(r));
            prefs.edit().putString(KEY_SCANS, arr.toString()).apply();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public List<ScanRecord> load() {
        List<ScanRecord> result = new ArrayList<>();
        try {
            String json = prefs.getString(KEY_SCANS, "[]");
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                result.add(fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return result;
    }

    public int count() { return load().size(); }

    private JSONObject toJson(ScanRecord r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id",             r.id);
        o.put("timestamp",      r.timestamp);
        o.put("ssid",           r.ssid);
        o.put("bssid",          r.bssid);
        o.put("deviceModel",    r.deviceModel);
        o.put("androidVersion", r.androidVersion);
        JSONArray pts = new JSONArray();
        for (ScanPoint p : r.points) {
            JSONObject pt = new JSONObject();
            pt.put("x",     p.x);
            pt.put("y",     p.y);
            pt.put("rssi",  p.rssi);
            pt.put("level", p.signalLevel);
            pt.put("color", p.color);
            pts.put(pt);
        }
        o.put("points", pts);
        return o;
    }

    private ScanRecord fromJson(JSONObject o) throws Exception {
        ScanRecord r = new ScanRecord(
                o.getString("ssid"),
                o.getString("bssid"),
                o.getString("deviceModel"),
                o.getString("androidVersion"));
        r.id        = o.getString("id");
        r.timestamp = o.getLong("timestamp");
        JSONArray pts = o.getJSONArray("points");
        for (int i = 0; i < pts.length(); i++) {
            JSONObject pt = pts.getJSONObject(i);
            r.points.add(new ScanPoint(
                    pt.getInt("x"), pt.getInt("y"),
                    pt.getInt("color"), pt.getInt("level"),
                    pt.getInt("rssi"), r.ssid));
        }
        return r;
    }
}