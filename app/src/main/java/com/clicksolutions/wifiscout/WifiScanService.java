package com.clicksolutions.wifiscout;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import androidx.core.app.NotificationCompat;

/**
 * Foreground service that keeps WiFi scanning alive
 * even when the app is in the background.
 */
public class WifiScanService extends Service {

    public static final String CHANNEL_ID = "WifiScoutChannel";
    public static final String ACTION_START = "com.clicksolutions.wifiscout.START";
    public static final String ACTION_STOP  = "com.clicksolutions.wifiscout.STOP";
    private static final int NOTIFICATION_ID = 1;
    private static final int SCAN_INTERVAL_MS = 1000;

    // Callback interface so Activity receives scan results
    public interface ScanCallback {
        void onScanResult(int rssi, int signalLevel, String ssid);
    }

    private final IBinder binder = new LocalBinder();
    private WifiManager wifiManager;
    private Handler handler;
    private Runnable scanRunnable;
    private ScanCallback scanCallback;
    private boolean scanning = false;

    public class LocalBinder extends Binder {
        public WifiScanService getService() { return WifiScanService.this; }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification("סורק WiFi..."));
        startScanning();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    public void setScanCallback(ScanCallback cb) { this.scanCallback = cb; }

    public void startScanning() {
        if (scanning) return;
        scanning = true;
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

    private void doScan() {
        if (wifiManager == null) return;
        WifiInfo info = wifiManager.getConnectionInfo();
        if (info == null) return;
        int rssi  = info.getRssi();
        int level = WifiManager.calculateSignalLevel(rssi, 100);
        String ssid = info.getSSID().replace("\"", "");
        if (scanCallback != null) {
            scanCallback.onScanResult(rssi, level, ssid);
        }
        updateNotification("📡 " + ssid + "  |  " + rssi + " dBm");
    }

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
        super.onDestroy();
    }
}
