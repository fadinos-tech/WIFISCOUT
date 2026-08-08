package com.clicksolutions.wifiscout;

import java.util.List;
import java.util.ArrayList;

public class ScanRecord {
    public String id;
    public long   timestamp;
    public String ssid;
    public String bssid;
    public String deviceModel;
    public String androidVersion;
    public List<ScanPoint> points = new ArrayList<>();

    public ScanRecord(String ssid, String bssid, String deviceModel, String androidVersion) {
        this.id             = String.valueOf(System.currentTimeMillis());
        this.timestamp      = System.currentTimeMillis();
        this.ssid           = ssid;
        this.bssid          = bssid;
        this.deviceModel    = deviceModel;
        this.androidVersion = androidVersion;
    }
}
