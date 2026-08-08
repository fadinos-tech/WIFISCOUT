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

    // Diagnostics (v1.1): -1 / false = unknown
    public final int     freqMhz;      // connection frequency (2412..2484 = 2.4GHz, 5000+ = 5GHz)
    public final int     linkMbps;     // negotiated link speed
    public final int     rttMs;        // latency to the gateway
    public final boolean interference; // good signal but poor performance at this spot

    public ScanPoint(int x, int y, int color, int signalLevel, int rssi, String ssid) {
        this(x, y, color, signalLevel, rssi, ssid, -1, -1, -1, false);
    }

    public ScanPoint(int x, int y, int color, int signalLevel, int rssi, String ssid,
                     int freqMhz, int linkMbps, int rttMs, boolean interference) {
        this.x = x;
        this.y = y;
        this.color = color;
        this.signalLevel = signalLevel;
        this.rssi = rssi;
        this.ssid = ssid;
        this.timestamp = System.currentTimeMillis();
        this.freqMhz = freqMhz;
        this.linkMbps = linkMbps;
        this.rttMs = rttMs;
        this.interference = interference;
    }

    public boolean is5GHz() { return freqMhz >= 4900; }

    public String getQualityLabel() {
        if (signalLevel >= 80) return "מעולה";
        if (signalLevel >= 60) return "טוב";
        if (signalLevel >= 40) return "בינוני";
        if (signalLevel >= 20) return "חלש";
        return "גרוע";
    }
}
