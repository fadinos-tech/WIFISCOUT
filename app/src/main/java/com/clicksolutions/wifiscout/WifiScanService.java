package com.clicksolutions.wifiscout;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.DhcpInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that keeps WiFi scanning alive
 * even when the app is in the background.
 *
 * v1.1: also reports frequency, link speed and gateway RTT per sample,
 * and runs a throttle-friendly background scan (~every 30 s) that reports
 * every same-SSID access point audible nearby.
 */
public class WifiScanService extends Service {

    public static final String CHANNEL_ID = "WifiScoutChannel";
    public static final String ACTION_START = "com.clicksolutions.wifiscout.START";
    public static final String ACTION_STOP  = "com.clicksolutions.wifiscout.STOP";
    private static final int NOTIFICATION_ID = 1;
    private static final int SCAN_INTERVAL_MS = 1000;
    // Android throttles to 4 scans / 2 min — 35 s spacing stays safely under it
    private static final int BG_SCAN_EVERY_TICKS = 35;
    private static final long FRESH_RESULT_US = 60_000_000L; // accept results younger than 60 s

    // Callback interface so Activity receives scan results
    public interface ScanCallback {
        void onScanResult(int rssi, int signalLevel, String ssid,
                          int freqMhz, int linkMbps, int rttMs);
    }

    /** Same-SSID neighbor APs heard during a background scan. */
    public interface NeighborCallback {
        void onNeighborResults(List<ScanResult> sameSsid);
    }

    private final IBinder binder = new LocalBinder();
    private WifiManager wifiManager;
    private Handler handler;
    private Runnable scanRunnable;
    private ScanCallback scanCallback;
    private NeighborCallback neighborCallback;
    private boolean scanning = false;
    private int tickCount = 0;

    private ExecutorService netExecutor;
    private volatile int     latestRttMs  = -1;
    private volatile boolean rttInFlight  = false;

    private final BroadcastReceiver scanResultsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { deliverNeighborResults(); }
    };

    public class LocalBinder extends Binder {
        public WifiScanService getService() { return WifiScanService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        netExecutor = Executors.newSingleThreadExecutor();
        ContextCompat.registerReceiver(this, scanResultsReceiver,
                new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }
        try {
            // Android 14+ throws SecurityException for a location-type foreground
            // service if location permission was revoked meanwhile
            startForeground(NOTIFICATION_ID, buildNotification("סורק WiFi..."));
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startScanning();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setScanCallback(ScanCallback cb)         { this.scanCallback = cb; }
    public void setNeighborCallback(NeighborCallback cb) { this.neighborCallback = cb; }

    public void startScanning() {
        if (scanning) return;
        scanning = true;
        tickCount = 0;
        latestRttMs = -1;
        scanRunnable = new Runnable() {
            @Override public void run() {
                if (!scanning) return;
                doScan();
                handler.postDelayed(this, SCAN_INTERVAL_MS);
            }
        };
        handler.post(scanRunnable);
    }

    public void stopScanning() {
        scanning = false;
        if (scanRunnable != null) handler.removeCallbacks(scanRunnable);
    }

    /** Fully stop: scanning + foreground notification + service. Safe via binder from any state. */
    public void shutdown() {
        stopScanning();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private void doScan() {
        if (wifiManager == null) return;
        WifiInfo info;
        try {
            info = wifiManager.getConnectionInfo();
        } catch (SecurityException e) { return; }  // location permission revoked mid-scan
        if (info == null) return;
        int rssi  = info.getRssi();
        int level = WifiManager.calculateSignalLevel(rssi, 100);
        String ssid = info.getSSID().replace("\"", "");
        int freq = info.getFrequency();
        int link = info.getLinkSpeed();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int rx = info.getRxLinkSpeedMbps();
            if (rx > 0 && (link <= 0 || rx < link)) link = rx;  // rx rate is the honest one
        }

        measureGatewayRtt();  // async; result used on the next ticks

        tickCount++;
        if (tickCount % BG_SCAN_EVERY_TICKS == 5) requestBackgroundScan();

        if (scanCallback != null) {
            scanCallback.onScanResult(rssi, level, ssid, freq, link, latestRttMs);
        }
        updateNotification("📡 " + ssid + "  |  " + rssi + " dBm");
    }

    // ── Gateway latency (TCP connect time — routers rarely answer ICMP from apps) ──

    private void measureGatewayRtt() {
        if (rttInFlight) return;
        rttInFlight = true;
        netExecutor.execute(() -> {
            int rtt = -1;
            try {
                DhcpInfo dhcp = wifiManager.getDhcpInfo();
                if (dhcp != null && dhcp.gateway != 0) {
                    String gw = String.format(Locale.US, "%d.%d.%d.%d",
                            dhcp.gateway & 0xff, (dhcp.gateway >> 8) & 0xff,
                            (dhcp.gateway >> 16) & 0xff, (dhcp.gateway >> 24) & 0xff);
                    rtt = tcpConnectMs(gw, 80, 400);
                    if (rtt < 0) rtt = tcpConnectMs(gw, 443, 400);
                    if (rtt < 0) rtt = tcpConnectMs(gw, 53, 400);
                }
            } catch (Exception ignored) {}
            latestRttMs = rtt;
            rttInFlight = false;
        });
    }

    private int tcpConnectMs(String host, int port, int timeoutMs) {
        long t0 = SystemClock.elapsedRealtime();
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return (int) (SystemClock.elapsedRealtime() - t0);
        } catch (Exception e) { return -1; }
    }

    // ── Background neighbor scan ─────────────────────────────────

    private void requestBackgroundScan() {
        try { wifiManager.startScan(); } catch (Exception ignored) {}
    }

    private void deliverNeighborResults() {
        if (neighborCallback == null || !scanning) return;
        List<ScanResult> fresh = new ArrayList<>();
        try {
            String ssid = "";
            WifiInfo info = wifiManager.getConnectionInfo();
            if (info != null) ssid = info.getSSID().replace("\"", "");
            if (ssid.isEmpty() || ssid.equals("<unknown ssid>")) return;
            long nowUs = SystemClock.elapsedRealtime() * 1000L;
            for (ScanResult r : wifiManager.getScanResults()) {
                if (r.SSID == null || !r.SSID.equals(ssid)) continue;
                if (nowUs - r.timestamp > FRESH_RESULT_US) continue;  // stale cache entry
                fresh.add(r);
            }
        } catch (SecurityException e) { return; }
        if (!fresh.isEmpty()) neighborCallback.onNeighborResults(fresh);
    }

    // ── Notification ─────────────────────────────────────────────

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "WiFi Scout סריקה",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("סריקת WiFi רצה ברקע");
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr != null) mgr.createNotificationChannel(ch);
    }

    private Notification buildNotification(String text) {
        Intent openApp = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_IMMUTABLE);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle("WiFi Scout")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager mgr = getSystemService(NotificationManager.class);
        if (mgr != null) mgr.notify(NOTIFICATION_ID, buildNotification(text));
    }

    @Override
    public void onDestroy() {
        stopScanning();
        try { unregisterReceiver(scanResultsReceiver); } catch (Exception ignored) {}
        if (netExecutor != null) netExecutor.shutdownNow();
        super.onDestroy();
    }
}
