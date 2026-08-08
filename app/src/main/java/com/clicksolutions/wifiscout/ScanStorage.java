package com.clicksolutions.wifiscout;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Persists the last MAX_SCANS scan sessions as JSON in SharedPreferences.
 * (License tiers may raise the limit later.)
 */
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
            while (all.size() > MAX_SCANS) all.remove(all.size() - 1);
            persist(all);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void delete(String id) {
        try {
            List<ScanRecord> all = load();
            for (int i = all.size() - 1; i >= 0; i--)
                if (all.get(i).id.equals(id)) all.remove(i);
            persist(all);
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

    private void persist(List<ScanRecord> all) throws Exception {
        JSONArray arr = new JSONArray();
        for (ScanRecord r : all) arr.put(toJson(r));
        prefs.edit().putString(KEY_SCANS, arr.toString()).apply();
    }

    private JSONObject toJson(ScanRecord r) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id",             r.id);
        o.put("name",           r.name);
        o.put("timestamp",      r.timestamp);
        o.put("ssid",           r.ssid);
        o.put("bssid",          r.bssid);
        o.put("deviceModel",    r.deviceModel);
        o.put("androidVersion", r.androidVersion);
        o.put("diagnostics",    r.diagnostics);
        o.put("steps",          r.steps);

        JSONArray pts = new JSONArray();
        for (ScanPoint p : r.points) {
            JSONObject pt = new JSONObject();
            pt.put("x",     p.x);
            pt.put("y",     p.y);
            pt.put("rssi",  p.rssi);
            pt.put("level", p.signalLevel);
            pt.put("color", p.color);
            pt.put("freq",  p.freqMhz);
            pt.put("link",  p.linkMbps);
            pt.put("rtt",   p.rttMs);
            pt.put("intf",  p.interference);
            pt.put("ts",    p.timestamp);
            pts.put(pt);
        }
        o.put("points", pts);

        JSONArray ri = new JSONArray();
        for (int idx : r.roamIndices) ri.put(idx);
        o.put("roamIndices", ri);
        JSONArray rl = new JSONArray();
        for (String s : r.roamLabels) rl.put(s == null ? "" : s);
        o.put("roamLabels", rl);

        JSONArray mk = new JSONArray();
        for (MapMarker m : r.markers) {
            JSONObject mo = new JSONObject();
            mo.put("x", m.worldX); mo.put("y", m.worldY);
            mo.put("type", m.type.name()); mo.put("label", m.label);
            mk.put(mo);
        }
        o.put("markers", mk);
        return o;
    }

    private ScanRecord fromJson(JSONObject o) throws Exception {
        ScanRecord r = new ScanRecord(
                o.optString("name", "Scan"),
                o.getString("ssid"),
                o.getString("bssid"),
                o.getString("deviceModel"),
                o.getString("androidVersion"));
        r.id          = o.getString("id");
        r.timestamp   = o.getLong("timestamp");
        r.diagnostics = o.optString("diagnostics", "");
        r.steps       = o.optInt("steps", 0);

        JSONArray pts = o.getJSONArray("points");
        for (int i = 0; i < pts.length(); i++) {
            JSONObject pt = pts.getJSONObject(i);
            r.points.add(new ScanPoint(
                    pt.getInt("x"), pt.getInt("y"),
                    pt.getInt("color"), pt.getInt("level"),
                    pt.getInt("rssi"), r.ssid,
                    pt.optInt("freq", -1), pt.optInt("link", -1),
                    pt.optInt("rtt", -1), pt.optBoolean("intf", false),
                    pt.optLong("ts", r.timestamp)));
        }
        JSONArray ri = o.optJSONArray("roamIndices");
        if (ri != null) for (int i = 0; i < ri.length(); i++) r.roamIndices.add(ri.getInt(i));
        JSONArray rl = o.optJSONArray("roamLabels");
        if (rl != null) for (int i = 0; i < rl.length(); i++) {
            String s = rl.getString(i);
            r.roamLabels.add(s.isEmpty() ? null : s);
        }
        JSONArray mk = o.optJSONArray("markers");
        if (mk != null) for (int i = 0; i < mk.length(); i++) {
            JSONObject mo = mk.getJSONObject(i);
            MapMarker.Type t;
            try { t = MapMarker.Type.valueOf(mo.getString("type")); }
            catch (Exception e) { t = MapMarker.Type.CUSTOM; }
            r.markers.add(new MapMarker((float) mo.getDouble("x"),
                    (float) mo.getDouble("y"), t, mo.getString("label")));
        }
        return r;
    }
}
