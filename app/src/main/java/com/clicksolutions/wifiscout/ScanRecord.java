package com.clicksolutions.wifiscout;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One complete saved scan session: points, roaming events, markers and
 * the end-of-scan diagnostics text.
 */
public class ScanRecord {

    public String id = UUID.randomUUID().toString();
    public String name;
    public long   timestamp = System.currentTimeMillis();

    public String ssid;
    public String bssid;
    public String deviceModel;
    public String androidVersion;
    public String diagnostics = "";
    public int    steps = 0;

    public final List<ScanPoint> points      = new ArrayList<>();
    public final List<Integer>   roamIndices = new ArrayList<>();
    public final List<String>    roamLabels  = new ArrayList<>();
    public final List<MapMarker> markers     = new ArrayList<>();

    public ScanRecord(String name, String ssid, String bssid,
                      String deviceModel, String androidVersion) {
        this.name = name;
        this.ssid = ssid;
        this.bssid = bssid;
        this.deviceModel = deviceModel;
        this.androidVersion = androidVersion;
    }
}
