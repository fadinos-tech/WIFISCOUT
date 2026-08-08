package com.clicksolutions.wifiscout;

/**
 * Represents a single WiFi measurement point on the heatmap.
 */
public class ScanPoint {
    public final int x;
    public final int y;
    public final int color;
    public final int signalLevel; // 0–100
    public final int rssi;        // dBm
    public final String ssid;
    public final long timestamp;

    public ScanPoint(int x, int y, int color, int signalLevel, int rssi, String ssid) {
        this.x = x;
        this.y = y;
        this.color = color;
        this.signalLevel = signalLevel;
        this.rssi = rssi;
        this.ssid = ssid;
        this.timestamp = System.currentTimeMillis();
    }

    public String getQualityLabel() {
        if (signalLevel >= 80) return "מעולה";
        if (signalLevel >= 60) return "טוב";
        if (signalLevel >= 40) return "בינוני";
        if (signalLevel >= 20) return "חלש";
        return "גרוע";
    }
}
