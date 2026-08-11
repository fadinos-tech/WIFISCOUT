package com.clicksolutions.wifiscout;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;

import java.util.HashMap;
import java.util.Map;

/**
 * Trial + licensing:
 *  - TRIAL_SCANS free scans, counted per device (ANDROID_ID) in Firestore
 *    with a local SharedPreferences mirror (offline / anti-reset: the
 *    higher of the two counts wins).
 *  - A license code (issued after PayPal purchase) unlocks the app for
 *    LICENSE_YEARS. Codes live in the "licenses" collection; each code
 *    is single-use and gets stamped with the claiming device.
 *
 * Firestore layout:
 *   trials/{deviceId}   { scansUsed, licensed, licenseExpiry, updatedAt }
 *   licenses/{CODE}     { used:false }  →  { used:true, deviceId, claimedAt }
 */
public class LicenseManager {

    public static final int  TRIAL_SCANS   = 3;
    public static final int  LICENSE_YEARS = 5;
    public static final String PURCHASE_URL = "https://clicksolutionspro.com/wifiscout";

    public interface Listener { void onLicenseStateChanged(); }
    public interface RedeemCallback { void onResult(boolean ok, String message); }

    private static final String PREFS = "wifi_scout_license";

    private final SharedPreferences prefs;
    private final String deviceId;
    private final FirebaseFirestore db;
    private Listener listener;

    private int     scansUsed;
    private boolean licensed;
    private long    licenseExpiry;

    @SuppressLint("HardwareIds")
    public LicenseManager(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        deviceId = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ANDROID_ID);
        db = FirebaseFirestore.getInstance();
        scansUsed     = prefs.getInt("scans_used", 0);
        licensed      = prefs.getBoolean("licensed", false);
        licenseExpiry = prefs.getLong("license_expiry", 0);
        syncFromRemote();
    }

    public void setListener(Listener l) { listener = l; }

    // ── State ────────────────────────────────────────────────────

    public boolean isLicensed() {
        return licensed && licenseExpiry > System.currentTimeMillis();
    }

    public int getScansUsed()     { return Math.min(scansUsed, TRIAL_SCANS); }
    public int getTrialRemaining(){ return Math.max(0, TRIAL_SCANS - scansUsed); }
    public boolean canScan()      { return isLicensed() || scansUsed < TRIAL_SCANS; }

    /** Call when a scan actually starts — burns one trial scan (unless licensed). */
    public void recordScanStart() {
        if (isLicensed()) return;
        scansUsed++;
        prefs.edit().putInt("scans_used", scansUsed).apply();
        Map<String, Object> doc = new HashMap<>();
        doc.put("scansUsed", scansUsed);
        doc.put("updatedAt", FieldValue.serverTimestamp());
        trialDoc().set(doc, com.google.firebase.firestore.SetOptions.merge());
        notifyChanged();
    }

    // ── Remote sync ──────────────────────────────────────────────

    private DocumentReference trialDoc() {
        return db.collection("trials").document(deviceId);
    }

    private void syncFromRemote() {
        FirebaseAuth auth = FirebaseAuth.getInstance();
        Runnable fetch = () -> trialDoc().get().addOnSuccessListener(snap -> {
            if (snap.exists()) {
                Long remoteUsed = snap.getLong("scansUsed");
                Boolean remoteLic = snap.getBoolean("licensed");
                Long remoteExp = snap.getLong("licenseExpiry");
                // remote wins for license; the HIGHER count wins for trials
                if (remoteUsed != null && remoteUsed > scansUsed) scansUsed = remoteUsed.intValue();
                if (remoteLic != null) licensed = remoteLic;
                if (remoteExp != null) licenseExpiry = remoteExp;
                persistLocal();
            } else if (scansUsed > 0) {
                recordScanStartMirror();   // device known locally but not remotely
            }
            notifyChanged();
        });
        if (auth.getCurrentUser() != null) fetch.run();
        else auth.signInAnonymously()
                .addOnSuccessListener(r -> fetch.run())
                .addOnFailureListener(e -> notifyChanged()); // offline — local state rules
    }

    private void recordScanStartMirror() {
        Map<String, Object> doc = new HashMap<>();
        doc.put("scansUsed", scansUsed);
        doc.put("updatedAt", FieldValue.serverTimestamp());
        trialDoc().set(doc, com.google.firebase.firestore.SetOptions.merge());
    }

    private void persistLocal() {
        prefs.edit().putInt("scans_used", scansUsed)
                .putBoolean("licensed", licensed)
                .putLong("license_expiry", licenseExpiry).apply();
    }

    private void notifyChanged() {
        if (listener != null) listener.onLicenseStateChanged();
    }

    // ── Code redemption ──────────────────────────────────────────

    /** Claims a single-use license code and activates a LICENSE_YEARS license. */
    public void redeemCode(String rawCode, RedeemCallback cb) {
        String code = rawCode.trim().toUpperCase();
        if (code.isEmpty()) { cb.onResult(false, "Enter a code."); return; }
        // code format: 5 digits + one uppercase letter, e.g. 48213K
        if (!code.matches("\\d{5}[A-Z]")) {
            cb.onResult(false, "Invalid code format — expected 5 digits followed by a letter (e.g. 48213K).");
            return;
        }
        if (FirebaseAuth.getInstance().getCurrentUser() == null) {
            // auth may not have completed yet — try again, then redeem
            FirebaseAuth.getInstance().signInAnonymously()
                    .addOnSuccessListener(r -> doRedeem(code, cb))
                    .addOnFailureListener(e -> cb.onResult(false,
                            e instanceof com.google.firebase.auth.FirebaseAuthException
                              ? "Server sign-in refused — Anonymous authentication is not enabled on the server (contact support)."
                              : "Can't reach the license server (" + e.getClass().getSimpleName()
                                + "). Check your internet connection and try again."));
            return;
        }
        doRedeem(code, cb);
    }

    private void doRedeem(String code, RedeemCallback cb) {
        DocumentReference codeRef = db.collection("licenses").document(code);
        db.runTransaction(tx -> {
            com.google.firebase.firestore.DocumentSnapshot snap = tx.get(codeRef);
            if (!snap.exists())
                throw new IllegalStateException("Code not found — check the spelling.");
            Boolean used = snap.getBoolean("used");
            String usedBy = snap.getString("deviceId");
            if (Boolean.TRUE.equals(used) && !deviceId.equals(usedBy))
                throw new IllegalStateException("This code was already used on another device.");
            long expiry = System.currentTimeMillis()
                    + LICENSE_YEARS * 365L * 24 * 60 * 60 * 1000;
            Map<String, Object> codeUpd = new HashMap<>();
            codeUpd.put("used", true);
            codeUpd.put("deviceId", deviceId);
            codeUpd.put("expiry", expiry);
            codeUpd.put("claimedAt", FieldValue.serverTimestamp());
            tx.set(codeRef, codeUpd, com.google.firebase.firestore.SetOptions.merge());
            Map<String, Object> trialUpd = new HashMap<>();
            trialUpd.put("licensed", true);
            trialUpd.put("licenseExpiry", expiry);
            trialUpd.put("licenseCode", code);
            trialUpd.put("updatedAt", FieldValue.serverTimestamp());
            tx.set(trialDoc(), trialUpd, com.google.firebase.firestore.SetOptions.merge());
            return expiry;
        }).addOnSuccessListener(expiry -> {
            licensed = true;
            licenseExpiry = expiry;
            persistLocal();
            notifyChanged();
            cb.onResult(true, "License activated — valid for " + LICENSE_YEARS + " years. Thank you!");
        }).addOnFailureListener(e -> cb.onResult(false, describeFailure(e)));
    }

    /** Human-readable reason instead of a generic "check internet". */
    private String describeFailure(Exception e) {
        Throwable t = e.getCause() != null ? e.getCause() : e;
        if (t instanceof IllegalStateException) return t.getMessage();
        // exact Firestore status when available — makes support debugging trivial
        if (t instanceof com.google.firebase.firestore.FirebaseFirestoreException) {
            com.google.firebase.firestore.FirebaseFirestoreException fe =
                    (com.google.firebase.firestore.FirebaseFirestoreException) t;
            switch (fe.getCode()) {
                case PERMISSION_DENIED:
                    return "License server rejected the request (PERMISSION_DENIED — security rules). Contact support.";
                case UNAVAILABLE:
                    return "No connection to the license server — check your internet and try again.";
                case UNAUTHENTICATED:
                    return "Not signed in to the license server (UNAUTHENTICATED — is Anonymous auth enabled?).";
                case NOT_FOUND:
                    return "License database not found (NOT_FOUND) — contact support.";
                default:
                    return "Verification failed (" + fe.getCode().name() + ") — try again or contact support.";
            }
        }
        String s = String.valueOf(t.getMessage()).toUpperCase();
        if (s.contains("NETWORK") || s.contains("TIMEOUT") || s.contains("UNAVAILABLE"))
            return "No connection to the license server — check your internet and try again.";
        return "Verification failed: " + t.getClass().getSimpleName() + " — try again or contact support.";
    }

    // ── Version gate ─────────────────────────────────────────────

    public interface VersionCallback {
        /** required = must update to keep using; storeUrl = where to update. */
        void onUpdateNeeded(boolean required, String latestName, String storeUrl);
    }

    /**
     * Reads config/app {latestVersionCode, minVersionCode, latestVersionName,
     * storeUrl} and fires the callback when this build is outdated.
     */
    public void checkVersion(int currentVersionCode, VersionCallback cb) {
        db.collection("config").document("app").get()
            .addOnSuccessListener(snap -> {
                if (!snap.exists()) return;
                Long latest = snap.getLong("latestVersionCode");
                Long min    = snap.getLong("minVersionCode");
                String name = snap.getString("latestVersionName");
                String url  = snap.getString("storeUrl");
                if (url == null || url.isEmpty()) url = PURCHASE_URL;
                if (min != null && currentVersionCode < min)
                    cb.onUpdateNeeded(true, name != null ? name : String.valueOf(latest), url);
                else if (latest != null && currentVersionCode < latest)
                    cb.onUpdateNeeded(false, name != null ? name : String.valueOf(latest), url);
            });
    }
}
