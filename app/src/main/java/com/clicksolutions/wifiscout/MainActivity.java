package com.clicksolutions.wifiscout;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.drawerlayout.widget.DrawerLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final int  PERM_LOCATION     = 100;
    private static final int  PERM_ACTIVITY     = 101;
    private static final int  PERM_NOTIF        = 102;
    private static final int  DEFAULT_NO_MOVE   = 15;   // seconds without steps before asking
    private static final int  DEFAULT_THRESHOLD = 20;
    private static final String PREF_THRESHOLD  = "red_threshold";
    private static final String PREF_NO_MOVE    = "no_move_timeout";

    private WifiHeatmapView heatmapView;
    private Button          btnStartStop, btnSave, btnShare, btnMark, btnMenu, btnExportCsv;
    private TextView        tvSsid, tvSignalStrength, tvSignalQuality;
    private TextView        tvPointCount, tvStatus, tvVersion, tvDrawerVersion, tvThresholdValue, tvNoMoveValue;
    private SeekBar         seekThreshold, seekNoMove;
    private RadioGroup      rgMapMode, rgRoamStyle;
    private DrawerLayout    drawerLayout;

    private WifiScanService scanService;
    private boolean         serviceBound  = false;
    private StepNavigator   stepNavigator;
    private boolean         isScanning    = false;
    private int             stepCount     = 0;
    private int             lastStepAtScan = -1;
    private int             noMoveCount   = 0;
    private String          currentSsid   = "";
    private String          currentBssid  = "";
    private String          lastBssid     = "";
    private int             redThreshold  = DEFAULT_THRESHOLD;
    private int             noMoveTimeout = DEFAULT_NO_MOVE;
    private long            stickyStatusUntil = 0;  // keep roaming message visible
    private boolean         noMoveDialogShowing = false;
    private AlertDialog     noMoveDialog;
    private android.widget.ProgressBar scanSpinner;

    // Every distinct physical AP unit seen this scan, in order: index 0 = "AP 1" (router).
    // Keyed by the first 5 MAC octets so both radios (2.4/5 GHz) of one unit share a name.
    private final List<String> seenUnits = new ArrayList<>();

    /** One same-SSID AP heard by a background scan, tied to where we stood. */
    private static class NeighborSighting {
        final float x, y; final String bssid; final int rssi, freqMhz;
        NeighborSighting(float x, float y, String bssid, int rssi, int freqMhz) {
            this.x = x; this.y = y; this.bssid = bssid; this.rssi = rssi; this.freqMhz = freqMhz;
        }
    }
    private final List<NeighborSighting> sightings = new ArrayList<>();
    private int    lastFreqMhz = -1;
    private String diagnostics = "";   // filled at scan stop, shown in the share report

    private final StringBuilder scanLog = new StringBuilder();

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            scanService = ((WifiScanService.LocalBinder) b).getService();
            serviceBound = true;
            scanService.setScanCallback((rssi, level, ssid, freq, link, rtt) ->
                    runOnUiThread(() -> onNewScanResult(rssi, level, ssid, freq, link, rtt)));
            scanService.setNeighborCallback(results ->
                    runOnUiThread(() -> onNeighborResults(results)));
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            serviceBound = false; scanService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        loadPrefs();
        initViews();
        stepNavigator = new StepNavigator(this, (x, y) -> runOnUiThread(() -> {
            stepCount++;
            noMoveCount = 0;
            setStatus("");
            heatmapView.setHeadingDeg(stepNavigator.getAzimuthDeg());
            heatmapView.updatePosition(x, y);
            log("Step #" + stepCount + "  X=" + (int)x + "  Y=" + (int)y
                    + "  heading=" + stepNavigator.getAzimuthDeg() + "deg");
        }));
        requestLocationPermission();
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        stopScanning(true);
        // stopService is always allowed; startService from a destroyed activity
        // throws IllegalStateException when the app is already in the background
        stopService(new Intent(this, WifiScanService.class));
    }

    @Override protected void onStart() {
        super.onStart();
        bindService(new Intent(this, WifiScanService.class),
                serviceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override protected void onStop() {
        super.onStop();
        if (noMoveDialog != null && noMoveDialog.isShowing()) noMoveDialog.dismiss();
        noMoveDialogShowing = false;
        if (isScanning) stopScanning(true);
        if (serviceBound) {
            if (scanService != null) {
                scanService.setScanCallback(null);
                scanService.setNeighborCallback(null);
            }
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }

    @Override public void onBackPressed() {
        if (drawerLayout.isOpen()) { drawerLayout.close(); return; }
        new AlertDialog.Builder(this)
                .setTitle("Exit WiFi Scout")
                .setMessage("Are you sure you want to exit?")
                .setPositiveButton("Exit", (d, w) -> finish())
                .setNegativeButton("Cancel", null).show();
    }

    // ── Prefs ────────────────────────────────────────────────────

    private void loadPrefs() {
        SharedPreferences sp = getSharedPreferences("wifi_scout_settings", MODE_PRIVATE);
        redThreshold  = sp.getInt(PREF_THRESHOLD, DEFAULT_THRESHOLD);
        noMoveTimeout = sp.getInt(PREF_NO_MOVE, DEFAULT_NO_MOVE);
    }

    private void savePrefs() {
        getSharedPreferences("wifi_scout_settings", MODE_PRIVATE)
                .edit().putInt(PREF_THRESHOLD, redThreshold)
                .putInt(PREF_NO_MOVE, noMoveTimeout).apply();
    }

    // ── Init ─────────────────────────────────────────────────────

    private void initViews() {
        drawerLayout     = findViewById(R.id.drawerLayout);
        heatmapView      = findViewById(R.id.heatmapView);
        btnStartStop     = findViewById(R.id.btnStartStop);
        btnSave          = findViewById(R.id.btnSave);
        btnShare         = findViewById(R.id.btnShare);
        btnMark          = findViewById(R.id.btnMark);
        btnMenu          = findViewById(R.id.btnMenu);
        tvSsid           = findViewById(R.id.tvSsid);
        tvSignalStrength = findViewById(R.id.tvSignalStrength);
        tvSignalQuality  = findViewById(R.id.tvSignalQuality);
        tvPointCount     = findViewById(R.id.tvPointCount);
        tvStatus         = findViewById(R.id.tvStatus);
        tvVersion        = findViewById(R.id.tvVersion);
        tvDrawerVersion  = findViewById(R.id.tvDrawerVersion);
        tvThresholdValue = findViewById(R.id.tvThresholdValue);
        scanSpinner      = findViewById(R.id.scanSpinner);
        seekThreshold    = findViewById(R.id.seekThreshold);
        rgMapMode        = findViewById(R.id.rgMapMode);
        rgRoamStyle      = findViewById(R.id.rgRoamStyle);

        String ver = "Click Solutions v" + BuildConfig.VERSION_NAME;
        tvVersion.setText(ver); tvDrawerVersion.setText(ver);

        btnMenu.setOnClickListener(v -> drawerLayout.open());

        rgMapMode.setOnCheckedChangeListener((g, id) -> {
            if      (id == R.id.rbAutoFit)    heatmapView.setMapMode(WifiHeatmapView.MapMode.AUTO_FIT);
            else if (id == R.id.rbAutoCenter) heatmapView.setMapMode(WifiHeatmapView.MapMode.AUTO_CENTER);
            else                              heatmapView.setMapMode(WifiHeatmapView.MapMode.FREE_SCROLL);
        });

        rgRoamStyle.setOnCheckedChangeListener((g, id) -> {
            if      (id == R.id.rbFlashRing)  heatmapView.setRoamStyle(WifiHeatmapView.RoamStyle.FLASH_RING);
            else if (id == R.id.rbLightning)  heatmapView.setRoamStyle(WifiHeatmapView.RoamStyle.LIGHTNING);
            else if (id == R.id.rbBanner)     heatmapView.setRoamStyle(WifiHeatmapView.RoamStyle.BANNER);
            else                              heatmapView.setRoamStyle(WifiHeatmapView.RoamStyle.COLOR_SPLIT);
        });

        seekThreshold.setProgress(redThreshold);
        tvThresholdValue.setText(redThreshold + "%");
        seekThreshold.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                redThreshold = p; tvThresholdValue.setText(p + "%"); savePrefs();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        tvNoMoveValue = findViewById(R.id.tvNoMoveValue);
        seekNoMove    = findViewById(R.id.seekNoMove);
        seekNoMove.setProgress(noMoveTimeout);
        tvNoMoveValue.setText(noMoveTimeout + "s");
        seekNoMove.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean u) {
                noMoveTimeout = p; tvNoMoveValue.setText(p + "s"); savePrefs();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        findViewById(R.id.btnLicense).setOnClickListener(v -> {
            drawerLayout.close();
            new AlertDialog.Builder(this)
                    .setTitle("License")
                    .setMessage("WiFi Scout v" + BuildConfig.VERSION_NAME + "\nClick Solutions Pro\n\nLicense coming soon.")
                    .setPositiveButton("OK", null).show();
        });

        btnStartStop.setOnClickListener(v -> toggleScanning());

        findViewById(R.id.btnClear).setOnClickListener(v -> {
            heatmapView.clearTrail();
            stepCount = 0; noMoveCount = 0; scanLog.setLength(0);
            updatePointCount(); setStatus("");
            btnSave.setEnabled(false); btnShare.setEnabled(false);
        });

        btnSave.setEnabled(false);
        btnSave.setOnClickListener(v -> saveToGallery());

        btnShare.setEnabled(false);
        btnShare.setOnClickListener(v -> shareImage());

        // Export CSV button — find it if it exists in layout
        View csvBtn = findViewById(R.id.btnExportCsv);
        if (csvBtn != null) csvBtn.setOnClickListener(v -> exportCsv());

        btnMark.setEnabled(false);
        btnMark.setOnClickListener(v -> showMarkDialog());

        // Long-press Mark = Undo last marker
        btnMark.setOnLongClickListener(v -> {
            heatmapView.removeLastMarker();
            Toast.makeText(this, "Last marker removed", Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    // ── WiFi check ────────────────────────────────────────────────

    private boolean isWifiConnected() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (!wm.isWifiEnabled()) return false;
            WifiInfo info = wm.getConnectionInfo();
            return info != null && info.getNetworkId() != -1;
        } catch (Exception e) { return false; }
    }

    // ── Scanning ─────────────────────────────────────────────────

    private void toggleScanning() {
        if (!hasLocationPermission()) { requestLocationPermission(); return; }
        if (!hasActivityPermission()) { requestActivityPermission(); return; }
        if (isScanning) {
            new AlertDialog.Builder(this)
                    .setTitle("Stop scanning?")
                    .setMessage("Did you scan the whole house?")
                    .setPositiveButton("Yes, stop", (d, w) -> stopScanning(false))
                    .setNegativeButton("No, continue", null)
                    .show();
            return;
        }

        if (!isWifiConnected()) {
            new AlertDialog.Builder(this)
                    .setTitle("No WiFi Connection")
                    .setMessage("Please connect to a WiFi network before scanning.")
                    .setPositiveButton("OK", null).show();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("Before you start")
                .setMessage("Stand approximately 1 meter from your Router or Extender, then tap START to begin scanning.")
                .setPositiveButton("START", (d, w) -> startScanning())
                .setNegativeButton("Cancel", null).show();
    }

    private void startScanning() {
        isScanning = true; stepCount = 0; noMoveCount = 0;
        lastStepAtScan = -1; currentSsid = ""; currentBssid = ""; lastBssid = "";
        seenUnits.clear(); sightings.clear(); diagnostics = ""; lastFreqMhz = -1;
        scanLog.setLength(0);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnStartStop.setText("STOP");
        btnStartStop.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.stop_red));
        btnSave.setEnabled(false); btnShare.setEnabled(false); btnMark.setEnabled(true);
        if (scanSpinner != null) scanSpinner.setVisibility(View.VISIBLE);
        noMoveDialogShowing = false;
        heatmapView.clearTrail(); updatePointCount(); setStatus("");
        heatmapView.setScanning(true);
        Intent si = new Intent(this, WifiScanService.class);
        si.setAction(WifiScanService.ACTION_START);
        ContextCompat.startForegroundService(this, si);
        if (serviceBound && scanService != null) scanService.startScanning();
        heatmapView.post(() -> {
            heatmapView.resetOrigin();
            stepNavigator.start(heatmapView.getWorldOriginX(), heatmapView.getWorldOriginY());
        });
        log("Scan started");
    }

    private void stopScanning(boolean silent) {
        if (!isScanning) return;
        isScanning = false;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        btnStartStop.setText("START");
        btnStartStop.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.start_green));
        btnMark.setEnabled(false);
        if (scanSpinner != null) scanSpinner.setVisibility(View.GONE);
        stickyStatusUntil = 0; setStatus("");   // clear leftover "keep walking" banner
        heatmapView.setScanning(false);         // triggers weak-cluster + placement computation
        runEndOfScanDiagnostics();
        stepNavigator.stop();
        // Shut the service down completely so its notification disappears immediately
        if (serviceBound && scanService != null) scanService.shutdown();
        log("Scan stopped  total=" + heatmapView.getPointCount() + "  steps=" + stepCount);
        if (heatmapView.getPointCount() > 0) {
            btnShare.setEnabled(true);
            saveToGallery();  // auto-save — no Save button anymore
        }
        if (!silent) showStopReport();
    }

    private void showStopReport() {
        if (isFinishing() || isDestroyed()) return; // dialog on a dead activity crashes
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_stop_report, null);
        List<ScanPoint> pts = heatmapView.getPoints();
        int total=pts.size(), green=0, yellow=0, red=0;
        for (ScanPoint p : pts) {
            if (p.rssi>=-60) green++; else if (p.rssi>=-70) yellow++; else red++;
        }
        int pG=total>0?Math.round(green*100f/total):0;
        int pY=total>0?Math.round(yellow*100f/total):0;
        int pR=total>0?Math.round(red*100f/total):0;
        ((TextView)view.findViewById(R.id.tvTotal)).setText(String.valueOf(total));
        ((TextView)view.findViewById(R.id.tvSteps)).setText(String.valueOf(stepCount));
        ((TextView)view.findViewById(R.id.tvGreen)).setText(green+"  ("+pG+"%)");
        ((TextView)view.findViewById(R.id.tvYellow)).setText(yellow+"  ("+pY+"%)");
        ((TextView)view.findViewById(R.id.tvRed)).setText(red+"  ("+pR+"%)");
        View alertBox = view.findViewById(R.id.alertBox);
        if (total>0 && pR>=redThreshold) {
            alertBox.setVisibility(View.VISIBLE);
            ((TextView)view.findViewById(R.id.tvAlert)).setText(
                    pR+"% of readings show weak signal — consider repositioning your extender.");
        }
        AlertDialog d = new AlertDialog.Builder(this).setView(view).setCancelable(false).create();
        if (d.getWindow()!=null) d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        view.findViewById(R.id.btnClose).setOnClickListener(v -> d.dismiss());
        d.show();
    }

    private void onNewScanResult(int rssi, int signalLevel, String ssid,
                                 int freqMhz, int linkMbps, int rttMs) {
        if (!isScanning) return;
        heatmapView.setHeadingDeg(stepNavigator.getAzimuthDeg());
        lastFreqMhz = freqMhz;
        String bssid = getBssid();
        if (currentSsid.isEmpty() && !ssid.equals("<unknown ssid>")) {
            currentSsid = ssid; currentBssid = bssid; lastBssid = bssid;
            if (!bssid.equals("N/A") && !bssid.equals("<none>")) apName(bssid); // router = AP 1
        }
        if (!lastBssid.isEmpty() && !bssid.equals(lastBssid)
                && !bssid.equals("N/A") && !bssid.equals("<none>")) {
            onRoamingDetected(lastBssid, bssid, freqMhz);
            lastBssid = bssid;
        }
        String name = ssid.isEmpty()||ssid.equals("<unknown ssid>") ? "Not connected" : ssid;
        tvSsid.setText("Network: " + name);
        tvSignalStrength.setText(rssi + " dBm" + (freqMhz >= 4900 ? " (5G)" : freqMhz > 0 ? " (2.4G)" : ""));
        tvSignalQuality.setText(qualityLabel(signalLevel));
        checkNoMove();
        if (stepCount > 0) {
            heatmapView.addPoint(signalLevel, rssi, ssid, freqMhz, linkMbps, rttMs,
                    isInterferenceSuspected(rssi, freqMhz, linkMbps, rttMs));
            log("WiFi  rssi="+rssi+"  freq="+freqMhz+"  link="+linkMbps+"Mbps  rtt="+rttMs
                    +"ms  point#="+heatmapView.getPointCount()+"  step#="+stepCount);
        }
        updatePointCount();
    }

    /**
     * Stage A — interference heuristic: strong signal but poor performance.
     * Never a verdict on a single metric; RSSI must be good AND at least one
     * performance metric must be clearly bad.
     */
    private boolean isInterferenceSuspected(int rssi, int freqMhz, int linkMbps, int rttMs) {
        if (rssi < -60) return false;                       // weak signal explains itself
        boolean lowLink = linkMbps > 0 && linkMbps < (freqMhz >= 4900 ? 60 : 25);
        boolean highRtt = rttMs >= 120;
        return lowLink || highRtt;
    }

    /** Stage B — remember every same-SSID AP audible from the current position. */
    private void onNeighborResults(List<android.net.wifi.ScanResult> results) {
        if (!isScanning) return;
        float x = heatmapView.getCurrentWorldX(), y = heatmapView.getCurrentWorldY();
        for (android.net.wifi.ScanResult r : results) {
            if (sightings.size() >= 600) break;
            sightings.add(new NeighborSighting(x, y, r.BSSID, r.level, r.frequency));
        }
        log("BG-SCAN  heard " + results.size() + " same-SSID AP(s) here");
    }

    /** Physical-unit key: first 5 MAC octets — both radios of one unit share it. */
    private String unitKey(String bssid) {
        String b = bssid.toLowerCase(Locale.US);
        return b.length() >= 14 ? b.substring(0, 14) : b;
    }

    private boolean sameUnit(String b1, String b2) { return unitKey(b1).equals(unitKey(b2)); }

    /** Friendly sequential name per physical AP: "AP 1" = router, "AP 2" = first extender... */
    private String apName(String bssid) {
        String key = unitKey(bssid);
        int idx = seenUnits.indexOf(key);
        if (idx < 0) { seenUnits.add(key); idx = seenUnits.size() - 1; }
        return "AP " + (idx + 1);
    }

    /**
     * BSSID changed. Two very different cases:
     *  - same physical unit, other radio  → band steering (2.4↔5 GHz), NOT an extender
     *  - different unit                   → real hand-off between router/extenders
     */
    private void onRoamingDetected(String oldBssid, String newBssid, int newFreqMhz) {
        apName(oldBssid);              // make sure the origin AP is registered first
        boolean bandSwitch = sameUnit(oldBssid, newBssid);
        String band = newFreqMhz >= 4900 ? "5GHz" : "2.4GHz";
        if (bandSwitch) {
            log("BAND CHANGE  " + oldBssid + " -> " + newBssid + "  now " + band);
            heatmapView.markRoaming(band);
            stickyStatusUntil = System.currentTimeMillis() + 4000;
            setStatus("⇄ Band change → " + band + " (same router)");
            return;   // no vibration — not a real extender hand-off
        }
        String apTag = apName(newBssid);
        log("ROAMING  old=" + oldBssid + "  new=" + newBssid + "  = " + apTag);
        heatmapView.markRoaming(apTag);
        // Vibrate so the user feels the hand-off while walking
        try {
            android.os.Vibrator vib = (android.os.Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vib != null && vib.hasVibrator())
                vib.vibrate(android.os.VibrationEffect.createOneShot(250,
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {}
        // Sticky banner for 4 seconds (step updates won't wipe it)
        stickyStatusUntil = System.currentTimeMillis() + 4000;
        setStatus("⇄ Roaming → " + apTag + "  (" + shortBssid(newBssid) + ", " + band + ")");
        Toast.makeText(this, "Roaming → " + apTag, Toast.LENGTH_SHORT).show();
    }

    private String shortBssid(String b) {
        return b.length() >= 5 ? b.substring(b.length() - 5) : b;
    }

    // ── End-of-scan diagnostics (stages B + D) ────────────────────

    private void runEndOfScanDiagnostics() {
        StringBuilder diag = new StringBuilder();

        // Interference zones (stage A summary)
        int interferencePts = 0;
        List<ScanPoint> pts = heatmapView.getPoints();
        for (ScanPoint p : pts) if (p.interference) interferencePts++;
        if (interferencePts >= 3) {
            diag.append("Interference SUSPECTED at ").append(interferencePts)
                .append(" points: good signal but poor link speed/latency.\n")
                .append("Possible causes: noise source (speaker/microwave/BT), channel congestion.\n");
        }

        // Stage B — smart extender verdict for the weak zone
        if (heatmapView.hasSuggestion()) {
            float wx = heatmapView.getWeakZoneX(), wy = heatmapView.getWeakZoneY();
            NeighborSighting best = null;
            for (NeighborSighting s : sightings) {
                float dx = s.x - wx, dy = s.y - wy;
                if (dx*dx + dy*dy > 250f*250f) continue;   // heard near the weak zone
                if (s.rssi < -62) continue;                // and clearly strong
                if (best == null || s.rssi > best.rssi) best = s;
            }
            if (best != null) {
                // Connection there was weak while another same-SSID radio was strong
                // → the AP exists, the phone just refused to roam (sticky client)
                String tag = apName(best.bssid);
                heatmapView.setSuggestionNote("⚠ " + tag + " is strong here — phone didn't roam");
                diag.append("Weak zone verdict: ").append(tag).append(" (")
                    .append(shortBssid(best.bssid)).append(", ").append(best.rssi)
                    .append(" dBm) is audible there — NOT a coverage hole.\n")
                    .append("Fix roaming (802.11k/v/r, AP placement), don't add hardware.\n");
            } else if (!sightings.isEmpty()) {
                heatmapView.setSuggestionNote("Place extender here");
                diag.append("Weak zone verdict: no other same-SSID AP audible — ")
                    .append("a real coverage hole. Extender recommended at the marked spot.\n");
            } else {
                // no background scan data — keep the default cautious label
                heatmapView.setSuggestionNote("Extender here?");
            }
        }

        // Stage D — signal drop vs expected path loss (reference = start at ~1 m)
        String obstruction = analyzeObstruction(pts);
        if (!obstruction.isEmpty()) diag.append(obstruction);

        // Neighbor inventory
        if (!sightings.isEmpty()) {
            java.util.Map<String, Integer> bestPerUnit = new java.util.LinkedHashMap<>();
            for (NeighborSighting s : sightings) {
                String key = unitKey(s.bssid);
                Integer cur = bestPerUnit.get(key);
                if (cur == null || s.rssi > cur) bestPerUnit.put(key, s.rssi);
            }
            diag.append("Same-SSID units heard during walk: ").append(bestPerUnit.size()).append("\n");
        }

        diagnostics = diag.toString();
        if (interferencePts >= 3)
            Toast.makeText(this, "Interference suspected — see purple zones", Toast.LENGTH_LONG).show();
    }

    /**
     * Stage D: compare measured RSSI against an indoor path-loss model using the
     * first readings (taken ~1 m from the router) as reference. Only points before
     * the first hand-off count — distances to the router are meaningless afterwards.
     */
    private String analyzeObstruction(List<ScanPoint> pts) {
        if (pts.size() < 8) return "";
        List<Integer> roamIdx = heatmapView.getRoamingIndices();
        int limit = roamIdx.isEmpty() ? pts.size() : roamIdx.get(0);
        if (limit < 8) return "";
        float ref = (pts.get(0).rssi + pts.get(1).rssi + pts.get(2).rssi) / 3f;
        float ox = heatmapView.getWorldOriginX(), oy = heatmapView.getWorldOriginY();
        List<Integer> flagged = new ArrayList<>();
        for (int i = 3; i < limit; i++) {
            ScanPoint p = pts.get(i);
            float dm = (float) Math.hypot(p.x - ox, p.y - oy) / 71f;  // world units → meters
            if (dm < 3f) continue;                                    // model unreliable up close
            // indoor model: n≈2.8; flag only a drastic gap (22 dB) to avoid false alarms
            float expected = ref - 28f * (float) Math.log10(dm);
            if (p.rssi < expected - 22f) flagged.add(i);
        }
        if (flagged.size() < 3) return "";
        heatmapView.setObstructionIndices(flagged);
        return "Signal drops MUCH faster than expected near the router ("
                + flagged.size() + " points) — check: router inside a cabinet, "
                + "thick wall/metal, or an interference source on the way.\n";
    }

    private void checkNoMove() {
        if (noMoveDialogShowing) { lastStepAtScan = stepCount; return; }
        if (stepCount == lastStepAtScan) {
            noMoveCount++;
            if (noMoveCount >= noMoveTimeout) { showNoMoveDialog(); return; }
            int warnAt = Math.max(3, noMoveTimeout - 7);  // warn ~7s before asking
            if (noMoveCount >= warnAt)
                setStatus("No movement detected — keep walking! (" + (noMoveTimeout-noMoveCount) + ")");
        } else { noMoveCount = 0; setStatus(""); }
        lastStepAtScan = stepCount;
    }

    /** Instead of auto-stopping: ask. Obstacles at home can take a few seconds to pass. */
    private void showNoMoveDialog() {
        if (isFinishing() || isDestroyed()) return;
        noMoveDialogShowing = true;
        setStatus("Scan paused — waiting for your answer");
        noMoveDialog = new AlertDialog.Builder(this)
                .setTitle("No movement detected")
                .setMessage("No steps for a while. Keep scanning or stop and save the map?")
                .setCancelable(false)
                .setPositiveButton("Keep scanning", (d, w) -> {
                    noMoveDialogShowing = false;
                    noMoveCount = 0; setStatus("");
                })
                .setNegativeButton("Stop & save", (d, w) -> {
                    noMoveDialogShowing = false;
                    stopScanning(false);
                })
                .show();
    }

    // ── Mark Location ─────────────────────────────────────────────

    private void showMarkDialog() {
        View dv = LayoutInflater.from(this).inflate(R.layout.dialog_mark_location, null);
        AlertDialog d = new AlertDialog.Builder(this).setView(dv).create();
        if (d.getWindow()!=null) d.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        EditText et = dv.findViewById(R.id.etCustomLabel);
        int[] ids={R.id.btnRouter,R.id.btnExtender,R.id.btnKitchen,R.id.btnLivingRoom,
                R.id.btnBedroom,R.id.btnBathroom,R.id.btnHallway,R.id.btnOffice,R.id.btnStairs};
        MapMarker.Type[] types={MapMarker.Type.ROUTER,MapMarker.Type.EXTENDER,MapMarker.Type.KITCHEN,
                MapMarker.Type.LIVING_ROOM,MapMarker.Type.BEDROOM,MapMarker.Type.BATHROOM,
                MapMarker.Type.HALLWAY,MapMarker.Type.OFFICE,MapMarker.Type.STAIRS};
        for (int i=0;i<ids.length;i++) {
            final MapMarker.Type t=types[i];
            dv.findViewById(ids[i]).setOnClickListener(v->{ addMarker(t,null); d.dismiss(); });
        }
        dv.findViewById(R.id.btnAddCustom).setOnClickListener(v->{
            String txt=et.getText().toString().trim();
            if(!txt.isEmpty()){addMarker(MapMarker.Type.CUSTOM,txt);d.dismiss();}
            else et.setError("Enter a label");
        });
        d.show();
    }

    private void addMarker(MapMarker.Type type, String custom) {
        String label=(custom!=null&&!custom.isEmpty())?custom:MapMarker.defaultLabel(type);
        MapMarker m=new MapMarker(heatmapView.getCurrentWorldX(),heatmapView.getCurrentWorldY(),type,label);
        heatmapView.addMarker(m);
        log("Marker added: "+label+" at "+(int)m.worldX+","+(int)m.worldY);
        Toast.makeText(this, label+" marked  (long-press Mark to undo)", Toast.LENGTH_SHORT).show();
    }

    // ── Save to Gallery ───────────────────────────────────────────

    private void saveToGallery() {
        Bitmap bmp = heatmapView.exportBitmap(buildWatermark());
        if (bmp==null) { Toast.makeText(this,"Nothing to save.",Toast.LENGTH_SHORT).show(); return; }
        SimpleDateFormat sdf=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.getDefault());
        String fn="WifiScout_"+sdf.format(new Date())+".png";
        try {
            OutputStream out;
            if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) {
                ContentValues cv=new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME,fn);
                cv.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/WiFiScout");
                Uri uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,cv);
                if (uri==null) { Toast.makeText(this,"Save failed: storage unavailable.",Toast.LENGTH_SHORT).show(); return; }
                out=getContentResolver().openOutputStream(uri);
            } else {
                File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"WiFiScout");
                dir.mkdirs(); out=new FileOutputStream(new File(dir,fn));
            }
            bmp.compress(Bitmap.CompressFormat.PNG,95,out); out.close();
            Toast.makeText(this,"Saved to Gallery/WiFiScout",Toast.LENGTH_SHORT).show();
        } catch(Exception e){Toast.makeText(this,"Save failed: "+e.getMessage(),Toast.LENGTH_SHORT).show();}
    }

    // ── Share image only ─────────────────────────────────────────

    private void shareImage() {
        try {
            Bitmap bmp = heatmapView.exportBitmap(buildWatermark());
            if (bmp==null){Toast.makeText(this,"Nothing to share.",Toast.LENGTH_SHORT).show();return;}
            File dir=new File(getCacheDir(),"shares"); dir.mkdirs();
            File imgFile=new File(dir,"wifiscout_scan.png");
            FileOutputStream fo=new FileOutputStream(imgFile);
            bmp.compress(Bitmap.CompressFormat.PNG,95,fo); fo.close();
            Uri imgUri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",imgFile);
            Intent share=new Intent(Intent.ACTION_SEND);
            share.setType("image/png");
            share.putExtra(Intent.EXTRA_STREAM,imgUri);
            share.putExtra(Intent.EXTRA_TEXT,buildShareText());
            share.putExtra(Intent.EXTRA_SUBJECT,"WiFi Scout - "+currentSsid);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share,"Share WiFi Map"));
        } catch(Exception e){Toast.makeText(this,"Share failed: "+e.getMessage(),Toast.LENGTH_SHORT).show();}
    }

    // ── Export CSV to Downloads ────────────────────────────────────

    private void exportCsv() {
        List<ScanPoint> pts = heatmapView.getPoints();
        if (pts.isEmpty()) { Toast.makeText(this,"No data to export.",Toast.LENGTH_SHORT).show(); return; }
        // roam events by point index → tag of the AP that took over
        List<Integer> roamIdx = heatmapView.getRoamingIndices();
        List<String>  roamLbl = heatmapView.getRoamingLabels();
        StringBuilder csv=new StringBuilder(
                "index,x,y,rssi,quality,ssid,freq_mhz,link_mbps,rtt_ms,interference,roamed_to_ap\n");
        for(int i=0;i<pts.size();i++){
            ScanPoint p=pts.get(i);
            String roam="";
            for(int r=0;r<roamIdx.size();r++)
                if(roamIdx.get(r)==i){ roam=roamLbl.get(r)!=null?roamLbl.get(r):"roam"; break; }
            csv.append(i+1).append(",").append(p.x).append(",").append(p.y)
                    .append(",").append(p.rssi).append(",")
                    .append(qualityLabel(p.signalLevel)).append(",")
                    .append(p.ssid).append(",")
                    .append(p.freqMhz).append(",")
                    .append(p.linkMbps).append(",")
                    .append(p.rttMs).append(",")
                    .append(p.interference?"YES":"").append(",")
                    .append(roam).append("\n");
        }
        SimpleDateFormat sdf=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.getDefault());
        String fn="WifiScout_"+sdf.format(new Date())+".csv";
        try {
            if (Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q) {
                ContentValues cv=new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME,fn);
                cv.put(MediaStore.Downloads.MIME_TYPE,"text/csv");
                cv.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/WiFiScout");
                Uri uri=getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,cv);
                if (uri==null) { Toast.makeText(this,"CSV export failed: storage unavailable.",Toast.LENGTH_SHORT).show(); return; }
                OutputStream out=getContentResolver().openOutputStream(uri);
                out.write(csv.toString().getBytes("UTF-8")); out.close();
            } else {
                File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),"WiFiScout");
                dir.mkdirs(); File f=new File(dir,fn);
                FileOutputStream out=new FileOutputStream(f);
                out.write(csv.toString().getBytes("UTF-8")); out.close();
            }
            Toast.makeText(this,"CSV saved to Downloads/WiFiScout",Toast.LENGTH_SHORT).show();
        } catch(Exception e){Toast.makeText(this,"CSV export failed: "+e.getMessage(),Toast.LENGTH_SHORT).show();}
    }

    // ── Helpers ──────────────────────────────────────────────────

    private String buildWatermark() {
        SimpleDateFormat sdf=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault());
        return sdf.format(new Date())+"  |  "+currentSsid+"  |  "
                +Build.MANUFACTURER+" "+Build.MODEL+"  |  Android "+Build.VERSION.RELEASE;
    }

    private String buildShareText() {
        SimpleDateFormat sdf=new SimpleDateFormat("dd/MM/yyyy HH:mm",Locale.getDefault());
        List<ScanPoint> pts=heatmapView.getPoints();
        int total=pts.size(),green=0,yellow=0,red=0;
        for(ScanPoint p:pts){if(p.rssi>=-60)green++;else if(p.rssi>=-70)yellow++;else red++;}
        // dedicated roaming section — hand-offs between router/extenders
        List<Integer> roamIdx = heatmapView.getRoamingIndices();
        List<String>  roamLbl = heatmapView.getRoamingLabels();
        StringBuilder roams = new StringBuilder();
        roams.append("Roaming events: ").append(roamIdx.size()).append("\n");
        for (int r = 0; r < roamIdx.size(); r++) {
            String tag = (r < roamLbl.size() && roamLbl.get(r) != null) ? roamLbl.get(r) : "?";
            roams.append("  #").append(r+1)
                 .append("  at point ").append(roamIdx.get(r)+1)
                 .append("  -> ").append(tag).append("\n");
        }
        return "WiFi Scout Scan Report\n======================\n"
                +"Date:    "+sdf.format(new Date())+"\n"
                +"Network: "+currentSsid+"\n"
                +"BSSID:   "+currentBssid+"\n"
                +"Device:  "+Build.MANUFACTURER+" "+Build.MODEL+"\n"
                +"Android: "+Build.VERSION.RELEASE+"\n"
                +"App:     WiFi Scout v"+BuildConfig.VERSION_NAME+"\n"
                +"Points:  "+total+"  Good:"+green+"  Fair:"+yellow+"  Weak:"+red+"\n"
                +"Steps:   "+stepCount+"\n"
                +"\n--- Roaming ---\n"+roams
                +(diagnostics.isEmpty()?"":"\n--- Diagnostics ---\n"+diagnostics)
                +"\n--- Scan Log ---\n"+scanLog
                +"\nGenerated by WiFi Scout - Click Solutions Pro";
    }

    private void setStatus(String msg){
        if(msg.isEmpty()){
            if(System.currentTimeMillis() < stickyStatusUntil) return; // keep roaming banner
            tvStatus.setVisibility(View.GONE);
        }
        else{tvStatus.setText(msg);tvStatus.setVisibility(View.VISIBLE);}
    }
    private void updatePointCount(){
        tvPointCount.setText("Points: "+heatmapView.getPointCount()+"  Steps: "+stepCount);
    }
    private void log(String msg){
        SimpleDateFormat sdf=new SimpleDateFormat("HH:mm:ss",Locale.getDefault());
        scanLog.append("[").append(sdf.format(new Date())).append("] ").append(msg).append("\n");
    }
    private String qualityLabel(int level){
        if(level>=80) return "Excellent"; if(level>=60) return "Good";
        if(level>=40) return "Fair"; if(level>=20) return "Weak"; return "Poor";
    }
    private String getBssid(){
        try{WifiManager wm=(WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo i=wm.getConnectionInfo(); return i!=null?i.getBSSID():"N/A";}
        catch(Exception e){return "N/A";}
    }

    private boolean hasLocationPermission(){
        return ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED;
    }
    private boolean hasActivityPermission(){
        return ContextCompat.checkSelfPermission(this,Manifest.permission.ACTIVITY_RECOGNITION)==PackageManager.PERMISSION_GRANTED;
    }
    private void requestLocationPermission(){
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION},PERM_LOCATION);
    }
    private void requestActivityPermission(){
        ActivityCompat.requestPermissions(this,new String[]{Manifest.permission.ACTIVITY_RECOGNITION},PERM_ACTIVITY);
    }
    /** Android 13+: without this the foreground-service notification is invisible. */
    private void requestNotificationPermissionIfNeeded(){
        if(Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS)
                   !=PackageManager.PERMISSION_GRANTED)
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},PERM_NOTIF);
    }
    @Override public void onRequestPermissionsResult(int req,@NonNull String[] p,@NonNull int[] r){
        super.onRequestPermissionsResult(req,p,r);
        boolean ok=r.length>0&&r[0]==PackageManager.PERMISSION_GRANTED;
        if(req==PERM_LOCATION&&ok) requestActivityPermission();
        if(req==PERM_ACTIVITY){
            if(!ok) Toast.makeText(this,"Physical Activity permission required.",Toast.LENGTH_LONG).show();
            requestNotificationPermissionIfNeeded(); // optional — scanning works either way
        }
    }
}