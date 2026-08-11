package com.clicksolutions.wifiscout;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * WiFiMan-style heatmap:
 * the signal field is interpolated (IDW) into a low-res world grid,
 * rendered as one smooth bitmap, with a thin walk path and small
 * sample dots on top — instead of wide overlapping ribbons.
 */
public class WifiHeatmapView extends android.view.View {

    public enum MapMode   { AUTO_FIT, AUTO_CENTER, FREE_SCROLL }
    public enum RoamStyle { FLASH_RING, LIGHTNING, BANNER, COLOR_SPLIT }
    public enum MapLayer  { SIGNAL, SPEED }   // color by dBm or by link Mbps

    // ±85 m from the start point — big houses with yards stay inside
    private static final float WORLD_SIZE   = 12000f;
    private static final float WORLD_ORIGIN = WORLD_SIZE / 2f;

    // ── Heat field (IDW grid) ────────────────────────────────────
    private static final float CELL        = 16f;                       // world units per cell
    private static final int   GRID_N      = (int) (WORLD_SIZE / CELL); // 750 x 750
    private static final float HEAT_RADIUS = 85f;    // influence radius of one sample (~1.5 steps)
    private static final float W_MIN       = 0.02f;  // below this weight the cell is "not covered"
    private static final float W_FULL      = 0.35f;  // weight at which the cell reaches full opacity
    private static final float W_CAP       = 2.5f;   // dwelling in place must not fatten the smear
    private static final float W_VIS       = 0.004f; // render threshold — soft fading outer edge
    private static final float W_LOW       = W_FULL * 0.6f; // below this, enclosed cells get blended fill
    private static final float SPLAT_SPACING = 20f;  // spread each sample evenly along the walked segment
    private static final int   HEAT_ALPHA  = 160;    // max opacity of the heat layer
    private static final float MAX_FIT_ZOOM = 1.15f; // don't zoom a single point to full screen

    private static final float FIT_PAD     = 140f;   // world padding for auto-fit
    private static final float MIN_MOVE_PX = 15f;
    private static final float WEAK_RSSI   = -72f;   // "weak" threshold for extender suggestion
    private static final float CONTOUR_RSSI = -70f;  // weak-zone contour line level
    private static final float UNITS_PER_METER = 71f; // one step = 50 units ≈ 0.7 m
    private static final float CELL_AREA_M2 = (CELL / UNITS_PER_METER) * (CELL / UNITS_PER_METER);
    // ignore weak islands smaller than ~1.5 m² — measurement noise, not a dead zone
    private static final int   MIN_CONTOUR_CELLS = Math.max(8, (int) (1.5f / CELL_AREA_M2));

    private final float[] sumW  = new float[GRID_N * GRID_N];
    private final float[] sumWV = new float[GRID_N * GRID_N];
    private final float[] sumWSp  = new float[GRID_N * GRID_N];  // link-speed weights
    private final float[] sumWVSp = new float[GRID_N * GRID_N];  // link-speed weighted values
    private final int[]   gridPixels = new int[GRID_N * GRID_N];
    private MapLayer mapLayer = MapLayer.SIGNAL;
    private final RectF layerChipRect = new RectF();  // dBm|Mbps toggle hit area
    private Bitmap heatBitmap;
    private final Matrix heatMatrix = new Matrix();
    private final Paint  heatPaint  = new Paint(Paint.FILTER_BITMAP_FLAG);

    private final List<ScanPoint> points  = new ArrayList<>();
    private final List<MapMarker> markers = new ArrayList<>();
    private final List<Integer>   roamingIndices = new ArrayList<>();
    private final List<String>    roamingLabels  = new ArrayList<>();  // new-AP tag per roam

    // Last drawn position — to skip duplicate points
    private float lastDrawnX = Float.NaN;
    private float lastDrawnY = Float.NaN;

    private final Paint pathPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint     = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotRimPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hintPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint miniPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint miniDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  walkPath     = new Path();

    private float worldX = WORLD_ORIGIN;
    private float worldY = WORLD_ORIGIN;
    private boolean started  = false;
    private boolean scanning = false;  // hide crosshair after stop

    // Extender suggestion: weak-cluster centroid + recommended placement, NaN = none
    private float weakX    = Float.NaN;   // center of the weak zone
    private float weakY    = Float.NaN;
    private float suggestX = Float.NaN;   // recommended extender spot (last good point)
    private float suggestY = Float.NaN;
    private String suggestionNote = "Extender here?";

    // Points where the signal drops far faster than the path-loss model predicts
    private final List<Integer> obstructionIndices = new ArrayList<>();

    // Heading of the phone (degrees, 0 = up/north on the map), NaN = unknown
    private float headingDeg = Float.NaN;

    // Weak-zone contour (marching squares), cached in world coords
    private final Path contourPath   = new Path();
    private final Path contourScreen = new Path();
    private final Matrix contourMatrix = new Matrix();
    private final Paint contourPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean contourDirty = false;

    // Pulse animation for the newest sample dot
    private float lastDotScale = 1f;
    private ValueAnimator dotAnimator;

    // Distinct rim color per AP segment (between roam events)
    private static final int[] AP_RIM_COLORS = {
            Color.argb(220, 255, 255, 255),  // AP 0 — white
            Color.argb(220, 64, 196, 255),   // AP 1 — cyan
            Color.argb(220, 255, 128, 171),  // AP 2 — pink
            Color.argb(220, 255, 215, 64),   // AP 3 — amber
            Color.argb(220, 179, 136, 255)   // AP 4+ — purple
    };

    private float panX = 0f, panY = 0f, zoom = 1.0f;

    private MapMode   mapMode   = MapMode.AUTO_FIT;
    private RoamStyle roamStyle = RoamStyle.FLASH_RING;

    private ValueAnimator pulseAnimator;
    private float         pulseValue = 0f;

    private GestureDetector      gestureDetector;
    private ScaleGestureDetector scaleDetector;

    // Display density — all overlay text/UI sizes are in dp so they are
    // readable on every screen (raw px looked microscopic on xxhdpi phones)
    private float dp = 1f;

    /** Fired when the user taps the empty map ("Tap to start") before a scan. */
    public interface OnStartTapListener { void onStartTap(); }
    private OnStartTapListener startTapListener;
    public void setOnStartTapListener(OnStartTapListener l) { startTapListener = l; }

    /** Fired (rate-limited) when the walker hits the edge of the scan world. */
    public interface OnBoundaryListener { void onBoundaryReached(); }
    private OnBoundaryListener boundaryListener;
    private long lastBoundaryWarn = 0;
    public void setOnBoundaryListener(OnBoundaryListener l) { boundaryListener = l; }

    public WifiHeatmapView(Context c) { super(c); init(); }
    public WifiHeatmapView(Context c, AttributeSet a) { super(c, a); init(); }
    public WifiHeatmapView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        dp = getResources().getDisplayMetrics().density;
        heatBitmap = Bitmap.createBitmap(GRID_N, GRID_N, Bitmap.Config.ARGB_8888);

        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeCap(Paint.Cap.ROUND);
        pathPaint.setStrokeJoin(Paint.Join.ROUND);
        pathPaint.setColor(Color.argb(90, 255, 255, 255));

        dotPaint.setStyle(Paint.Style.FILL);
        dotRimPaint.setStyle(Paint.Style.STROKE);
        dotRimPaint.setStrokeWidth(1.2f * dp);
        dotRimPaint.setColor(Color.argb(150, 6, 8, 16));

        gridPaint.setColor(Color.argb(10, 255, 255, 255));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        hintPaint.setColor(Color.argb(60, 255, 255, 255));
        hintPaint.setTextSize(15f * dp);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setAntiAlias(true);

        miniPaint.setColor(Color.argb(190, 15, 18, 28));
        miniPaint.setStyle(Paint.Style.FILL);

        miniDotPaint.setAntiAlias(true);

        contourPaint.setStyle(Paint.Style.STROKE);
        contourPaint.setStrokeWidth(1.8f * dp);
        contourPaint.setColor(Color.argb(200, 255, 110, 60));
        contourPaint.setPathEffect(new DashPathEffect(new float[]{8 * dp, 5 * dp}, 0));

        setupGestures();
    }

    private void setupGestures() {
        gestureDetector = new GestureDetector(getContext(),
                new GestureDetector.SimpleOnGestureListener() {
                    @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                        if (mapMode == MapMode.FREE_SCROLL) { panX -= dx; panY -= dy; invalidate(); }
                        return true;
                    }
                    @Override public boolean onDoubleTap(MotionEvent e) { centerOnCurrent(); return true; }
                    @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                        // empty map before a scan → tapping it starts scanning
                        if (!started && points.isEmpty()) {
                            if (startTapListener != null) startTapListener.onStartTap();
                            return true;
                        }
                        if (layerChipRect.contains(e.getX(), e.getY())) {
                            setMapLayer(mapLayer == MapLayer.SIGNAL
                                    ? MapLayer.SPEED : MapLayer.SIGNAL);
                            return true;
                        }
                        if (handleRoamTap(e.getX(), e.getY())) return true;
                        if (handlePointTap(e.getX(), e.getY())) return true;
                        return handleAreaTap(e.getX(), e.getY());
                    }
                    @Override public void onLongPress(MotionEvent e) { handleTouch(e.getX(), e.getY()); }
                });
        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        if (mapMode == MapMode.FREE_SCROLL) {
                            float newZoom = Math.max(0.2f, Math.min(4f, zoom * d.getScaleFactor()));
                            // zoom around the pinch focus so the map stays under the fingers
                            float fx = d.getFocusX(), fy = d.getFocusY();
                            float k = newZoom / zoom;
                            panX = fx - (fx - panX) * k;
                            panY = fy - (fy - panY) * k;
                            zoom = newZoom;
                            invalidate();
                        }
                        return true;
                    }
                });
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        scaleDetector.onTouchEvent(e); gestureDetector.onTouchEvent(e); return true;
    }

    // ── Public API ───────────────────────────────────────────────

    public void setMapMode(MapMode m)   { mapMode = m; applyMode(); invalidate(); }
    public void setRoamStyle(RoamStyle s) {
        roamStyle = s;
        if (s == RoamStyle.FLASH_RING) startPulse(); else stopPulse();
        invalidate();
    }
    public MapMode   getMapMode()   { return mapMode; }
    public RoamStyle getRoamStyle() { return roamStyle; }

    // Area estimates (approximate — based on walked coverage, not the real floor plan)
    private float coveredAreaM2 = 0f;
    private float weakAreaM2 = 0f;
    public float getCoveredAreaM2() { return coveredAreaM2; }
    public float getWeakAreaM2()    { return weakAreaM2; }

    private void computeAreas() {
        int covered = 0, weak = 0;
        for (int idx = 0; idx < sumW.length; idx++) {
            if (sumW[idx] < W_MIN) continue;
            covered++;
            if (sumWV[idx] / sumW[idx] <= CONTOUR_RSSI) weak++;
        }
        coveredAreaM2 = covered * CELL_AREA_M2;
        weakAreaM2    = weak * CELL_AREA_M2;
    }

    public void setScanning(boolean s) {
        scanning = s;
        if (s) clearSuggestion();
        else { computeExtenderSuggestion(); computeAreas(); fillCoverageGaps(); }
        invalidate();
    }

    public void updatePosition(float wx, float wy) {
        // never let the position leave the world grid
        worldX = Math.max(50f, Math.min(WORLD_SIZE - 50f, wx));
        worldY = Math.max(50f, Math.min(WORLD_SIZE - 50f, wy));
        // warn the user instead of silently piling points on the border
        if ((worldX != wx || worldY != wy)
                && System.currentTimeMillis() - lastBoundaryWarn > 5000) {
            lastBoundaryWarn = System.currentTimeMillis();
            if (boundaryListener != null) boundaryListener.onBoundaryReached();
        }
        started = true; applyMode(); invalidate();
    }

    public void setHeadingDeg(float deg) { headingDeg = deg; if (scanning) invalidate(); }

    public void addPoint(int signalLevel, int rssi, String ssid) {
        addPoint(signalLevel, rssi, ssid, -1, -1, -1, false);
    }

    public void addPoint(int signalLevel, int rssi, String ssid,
                         int freqMhz, int linkMbps, int rttMs, boolean interference) {
        if (!started) return;
        // Skip duplicate position — no movement
        if (!Float.isNaN(lastDrawnX)) {
            float dx = worldX - lastDrawnX;
            float dy = worldY - lastDrawnY;
            if (Math.sqrt(dx*dx + dy*dy) < MIN_MOVE_PX) return;
        }
        float prevX = lastDrawnX, prevY = lastDrawnY;
        int prevRssi = points.isEmpty() ? rssi : points.get(points.size() - 1).rssi;
        lastDrawnX = worldX; lastDrawnY = worldY;
        points.add(new ScanPoint((int) worldX, (int) worldY,
                colorForRssi(rssi, false), signalLevel, rssi, ssid,
                freqMhz, linkMbps, rttMs, interference));
        int prevLink = points.size() < 2 ? linkMbps : points.get(points.size() - 2).linkMbps;
        if (Float.isNaN(prevX)) addHeatSample(worldX, worldY, rssi, linkMbps);
        else splatSegment(prevX, prevY, prevRssi, prevLink, worldX, worldY, rssi, linkMbps);
        pulseNewDot();
        applyMode(); invalidate();
    }

    /**
     * Spreads a sample's energy evenly along the segment walked since the last
     * one — fast walking gives the same smooth ribbon as slow walking instead
     * of a chain of separate beads.
     */
    private void splatSegment(float x0, float y0, int rssi0, int link0,
                              float x1, float y1, int rssi1, int link1) {
        float d = (float) Math.hypot(x1 - x0, y1 - y0);
        int k = Math.max(1, Math.round(d / SPLAT_SPACING));
        boolean lerpLink = link0 > 0 && link1 > 0;
        for (int j = 1; j <= k; j++) {
            float t = j / (float) k;
            int link = lerpLink ? Math.round(link0 + (link1 - link0) * t) : link1;
            addHeatSample(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t,
                    Math.round(rssi0 + (rssi1 - rssi0) * t), link);
        }
    }

    private void pulseNewDot() {
        if (dotAnimator != null) dotAnimator.cancel();
        dotAnimator = ValueAnimator.ofFloat(1.9f, 1f);
        dotAnimator.setDuration(400);
        dotAnimator.addUpdateListener(a -> { lastDotScale = (float) a.getAnimatedValue(); invalidate(); });
        dotAnimator.start();
    }

    public void markRoaming() { markRoaming(null); }

    public void markRoaming(String apLabel) {
        if (!points.isEmpty()) {
            roamingIndices.add(points.size() - 1);
            roamingLabels.add(apLabel);
            if (roamStyle == RoamStyle.FLASH_RING) startPulse();
            invalidate();
        }
    }

    public void removeLastMarker() {
        if (!markers.isEmpty()) { markers.remove(markers.size()-1); invalidate(); }
    }

    public void addMarker(MapMarker m) { markers.add(m); invalidate(); }
    public List<MapMarker> getMarkers() { return new ArrayList<>(markers); }
    public List<ScanPoint> getPoints()  { return new ArrayList<>(points);  }
    public List<Integer>   getRoamingIndices() { return new ArrayList<>(roamingIndices); }
    public List<String>    getRoamingLabels()  { return new ArrayList<>(roamingLabels);  }
    public int   getPointCount()        { return points.size(); }
    public float getWorldOriginX()      { return WORLD_ORIGIN; }
    public float getWorldOriginY()      { return WORLD_ORIGIN; }
    public float getCurrentWorldX()     { return worldX; }
    public float getCurrentWorldY()     { return worldY; }

    public void clearTrail() {
        points.clear(); markers.clear(); roamingIndices.clear(); roamingLabels.clear();
        clearHeatField(); clearSuggestion();
        worldX = WORLD_ORIGIN; worldY = WORLD_ORIGIN;
        started = false; scanning = false;
        lastDrawnX = Float.NaN; lastDrawnY = Float.NaN;
        panX = 0; panY = 0; zoom = 1f;
        stopPulse(); invalidate();
    }

    /** Restores a saved scan onto the map: heat field, path, roams, markers, suggestion. */
    public void loadScan(List<ScanPoint> pts, List<Integer> roamIdx,
                         List<String> roamLbls, List<MapMarker> mks) {
        clearTrail();
        points.addAll(pts);
        roamingIndices.addAll(roamIdx);
        roamingLabels.addAll(roamLbls);
        markers.addAll(mks);
        replaySplats();
        if (!points.isEmpty()) {
            ScanPoint last = points.get(points.size() - 1);
            worldX = last.x; worldY = last.y;
        }
        started = true; scanning = false;
        computeExtenderSuggestion();
        computeAreas();
        fillCoverageGaps();
        applyMode(); invalidate();
    }

    /**
     * Loop closure: the user confirmed the walk ended where it started.
     * The end-to-origin gap is pure accumulated drift — distribute the
     * correction linearly along the path, then rebuild everything.
     */
    public void applyLoopClosure() {
        int n = points.size();
        if (n < 8) return;
        ScanPoint last = points.get(n - 1);
        float ex = last.x - WORLD_ORIGIN, ey = last.y - WORLD_ORIGIN;
        if (Math.hypot(ex, ey) < 30) { return; }   // already closed — nothing to fix
        List<ScanPoint> old = new ArrayList<>(points);
        points.clear();
        for (int i = 0; i < n; i++) {
            ScanPoint p = old.get(i);
            float f = (i + 1) / (float) n;         // drift grows with walked distance
            int nx = Math.round(p.x - ex * f), ny = Math.round(p.y - ey * f);
            nx = (int) Math.max(50, Math.min(WORLD_SIZE - 50, nx));
            ny = (int) Math.max(50, Math.min(WORLD_SIZE - 50, ny));
            points.add(new ScanPoint(nx, ny, p.color, p.signalLevel, p.rssi, p.ssid,
                    p.freqMhz, p.linkMbps, p.rttMs, p.interference, p.timestamp));
        }
        // markers move with the correction of their nearest path point
        for (MapMarker m : markers) {
            int best = 0; float bd = Float.MAX_VALUE;
            for (int i = 0; i < n; i++) {
                float dx = old.get(i).x - m.worldX, dy = old.get(i).y - m.worldY;
                float d = dx*dx + dy*dy;
                if (d < bd) { bd = d; best = i; }
            }
            float f = (best + 1) / (float) n;
            m.worldX -= ex * f; m.worldY -= ey * f;
        }
        clearHeatField();
        replaySplats();
        worldX = points.get(n - 1).x; worldY = points.get(n - 1).y;
        computeExtenderSuggestion();
        computeAreas();
        fillCoverageGaps();
        applyMode(); invalidate();
    }

    public void resetOrigin() {
        worldX = WORLD_ORIGIN; worldY = WORLD_ORIGIN;
        started = false; scanning = true;
        lastDrawnX = Float.NaN; lastDrawnY = Float.NaN;
        clearSuggestion();
        centerOnCurrent(); invalidate();
    }

    // ── Heat field ───────────────────────────────────────────────

    /** Rebuilds the whole heat field from the point list with segment splatting. */
    private void replaySplats() {
        for (int i = 0; i < points.size(); i++) {
            ScanPoint p = points.get(i);
            if (i == 0) addHeatSample(p.x, p.y, p.rssi, p.linkMbps);
            else {
                ScanPoint q = points.get(i - 1);
                splatSegment(q.x, q.y, q.rssi, q.linkMbps, p.x, p.y, p.rssi, p.linkMbps);
            }
        }
    }

    private void clearHeatField() {
        java.util.Arrays.fill(sumW, 0f);
        java.util.Arrays.fill(sumWV, 0f);
        java.util.Arrays.fill(sumWSp, 0f);
        java.util.Arrays.fill(sumWVSp, 0f);
        java.util.Arrays.fill(gridPixels, 0);
        heatBitmap.eraseColor(Color.TRANSPARENT);
        contourPath.rewind();
        contourDirty = false;
    }

    /** Splat one sample (RSSI + optional link speed) into the grid with a smooth compact kernel. */
    private void addHeatSample(float wx, float wy, int rssi, int linkMbps) {
        int cellR = (int) Math.ceil(HEAT_RADIUS / CELL);
        int cx = (int) (wx / CELL), cy = (int) (wy / CELL);
        int x0 = Math.max(0, cx - cellR), x1 = Math.min(GRID_N - 1, cx + cellR);
        int y0 = Math.max(0, cy - cellR), y1 = Math.min(GRID_N - 1, cy + cellR);
        if (x0 > x1 || y0 > y1) return;  // point outside the grid — setPixels would crash
        float r2 = HEAT_RADIUS * HEAT_RADIUS;
        for (int gy = y0; gy <= y1; gy++) {
            float dy = (gy + 0.5f) * CELL - wy;
            for (int gx = x0; gx <= x1; gx++) {
                float dx = (gx + 0.5f) * CELL - wx;
                float d2 = dx*dx + dy*dy;
                if (d2 > r2) continue;
                float t = 1f - d2 / r2;
                float w = t * t;            // smooth falloff, compact support
                int idx = gy * GRID_N + gx;
                sumW[idx]  += w;
                sumWV[idx] += w * rssi;
                if (sumW[idx] > W_CAP) {    // keep the running average, stop the growth
                    float s = W_CAP / sumW[idx];
                    sumW[idx] *= s; sumWV[idx] *= s;
                }
                if (linkMbps > 0) {
                    sumWSp[idx]  += w;
                    sumWVSp[idx] += w * linkMbps;
                    if (sumWSp[idx] > W_CAP) {
                        float s = W_CAP / sumWSp[idx];
                        sumWSp[idx] *= s; sumWVSp[idx] *= s;
                    }
                }
                gridPixels[idx] = cellColor(idx);
            }
        }
        heatBitmap.setPixels(gridPixels, y0 * GRID_N + x0, GRID_N,
                x0, y0, x1 - x0 + 1, y1 - y0 + 1);
        contourDirty = true;
    }

    /**
     * End-of-scan pass: enclosed black gaps between walked areas get filled
     * with color interpolated from ALL samples (IDW 1/d²). Cells open to the
     * outside stay dark — we never invent coverage beyond the walked area.
     * Slightly lower alpha marks the fill as estimated, not measured.
     */
    private void fillCoverageGaps() {
        if (points.size() < 10) return;
        // The walk path is continuous even when the heat beads have gaps —
        // rasterize it as a barrier so the interior can't "leak" outside.
        barrier = new boolean[GRID_N * GRID_N];
        for (int i = 1; i < points.size(); i++) {
            ScanPoint a = points.get(i - 1), b = points.get(i);
            float d = (float) Math.hypot(b.x - a.x, b.y - a.y);
            int k = Math.max(1, (int) (d / (CELL / 2f)));
            for (int j = 0; j <= k; j++) {
                float t = j / (float) k;
                int gx = (int) ((a.x + (b.x - a.x) * t) / CELL);
                int gy = (int) ((a.y + (b.y - a.y) * t) / CELL);
                if (gx >= 0 && gx < GRID_N && gy >= 0 && gy < GRID_N)
                    barrier[gy * GRID_N + gx] = true;
            }
        }
        boolean[] exterior = new boolean[GRID_N * GRID_N];
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        for (int i = 0; i < GRID_N; i++) {
            pushUncovered(stack, exterior, i);                          // top row
            pushUncovered(stack, exterior, (GRID_N-1)*GRID_N + i);      // bottom row
            pushUncovered(stack, exterior, i * GRID_N);                 // left col
            pushUncovered(stack, exterior, i * GRID_N + GRID_N - 1);    // right col
        }
        while (!stack.isEmpty()) {
            int idx = stack.pop();
            int gx = idx % GRID_N, gy = idx / GRID_N;
            if (gx > 0)          pushUncovered(stack, exterior, idx - 1);
            if (gx < GRID_N - 1) pushUncovered(stack, exterior, idx + 1);
            if (gy > 0)          pushUncovered(stack, exterior, idx - GRID_N);
            if (gy < GRID_N - 1) pushUncovered(stack, exterior, idx + GRID_N);
        }
        int filled = 0;
        for (int gy = 0; gy < GRID_N; gy++) {
            for (int gx = 0; gx < GRID_N; gx++) {
                int idx = gy * GRID_N + gx;
                if (sumW[idx] >= W_LOW || exterior[idx]) continue;   // solid or outside
                // outer fringe (touches the exterior) is left to the soft edge fade
                if ((gx > 0          && exterior[idx - 1])
                 || (gx < GRID_N - 1 && exterior[idx + 1])
                 || (gy > 0          && exterior[idx - GRID_N])
                 || (gy < GRID_N - 1 && exterior[idx + GRID_N])) continue;

                float est = interpolateAt((gx + 0.5f) * CELL, (gy + 0.5f) * CELL);
                if (Float.isNaN(est)) continue;
                float value = est;
                if (sumW[idx] >= W_MIN) {
                    // ghost-island fix: weakly-measured enclosed cell — blend
                    // its faint measurement with the interpolation
                    float t = sumW[idx] / W_LOW;
                    float measured = (mapLayer == MapLayer.SPEED && sumWSp[idx] >= W_MIN)
                            ? sumWVSp[idx] / sumWSp[idx]
                            : (mapLayer == MapLayer.SIGNAL ? sumWV[idx] / sumW[idx] : est);
                    value = measured * t + est * (1f - t);
                }
                int col = mapLayer == MapLayer.SPEED
                        ? speedColorForMbps(value) : heatColorForRssi(value);
                gridPixels[idx] = setAlpha(col, HEAT_ALPHA - 40);
                filled++;
            }
        }
        if (filled > 0)
            heatBitmap.setPixels(gridPixels, 0, GRID_N, 0, 0, GRID_N, GRID_N);
        barrier = null;
    }

    /** IDW (1/d²) estimate of the active layer's value at a world position; NaN if no data. */
    private float interpolateAt(float wx, float wy) {
        double sw = 0, swv = 0;
        for (ScanPoint p : points) {
            float v = mapLayer == MapLayer.SPEED ? p.linkMbps : p.rssi;
            if (mapLayer == MapLayer.SPEED && p.linkMbps <= 0) continue;
            float dx = p.x - wx, dy = p.y - wy;
            double w = 1.0 / (dx*dx + dy*dy + 1.0);
            sw += w; swv += w * v;
        }
        return sw > 0 ? (float) (swv / sw) : Float.NaN;
    }

    private boolean[] barrier;   // valid only inside fillCoverageGaps

    private void pushUncovered(java.util.ArrayDeque<Integer> stack, boolean[] visited, int idx) {
        if (!visited[idx] && sumW[idx] < W_MIN && (barrier == null || !barrier[idx])) {
            visited[idx] = true; stack.push(idx);
        }
    }

    /**
     * Marching squares over the IDW grid: builds the dashed contour line that
     * outlines every zone weaker than CONTOUR_RSSI. Cached until data changes.
     */
    private void rebuildContour() {
        contourDirty = false;
        contourPath.rewind();
        final float T = CONTOUR_RSSI;
        // weak mask filtered by connected-component size: tiny islands are noise
        boolean[] keep = buildFilteredWeakMask(T);
        for (int gy = 0; gy < GRID_N - 1; gy++) {
            int row = gy * GRID_N;
            for (int gx = 0; gx < GRID_N - 1; gx++) {
                int i00 = row + gx, i10 = i00 + 1, i01 = i00 + GRID_N, i11 = i01 + 1;
                if (sumW[i00] < W_MIN || sumW[i10] < W_MIN
                        || sumW[i01] < W_MIN || sumW[i11] < W_MIN) continue;
                float v00 = sumWV[i00]/sumW[i00], v10 = sumWV[i10]/sumW[i10];
                float v01 = sumWV[i01]/sumW[i01], v11 = sumWV[i11]/sumW[i11];
                int c = (keep[i00] ? 1 : 0) | (keep[i10] ? 2 : 0)
                      | (keep[i11] ? 4 : 0) | (keep[i01] ? 8 : 0);
                if (c == 0 || c == 15) continue;
                float x0 = (gx + 0.5f) * CELL, y0c = (gy + 0.5f) * CELL;
                float x1 = x0 + CELL, y1c = y0c + CELL;
                // interpolated crossing on each square edge
                float tx = x0 + CELL * frac(v00, v10, T);   // top edge
                float bx = x0 + CELL * frac(v01, v11, T);   // bottom edge
                float ly = y0c + CELL * frac(v00, v01, T);  // left edge
                float ry = y0c + CELL * frac(v10, v11, T);  // right edge
                switch (c) {
                    case 1:  case 14: seg(x0, ly, tx, y0c);  break;
                    case 2:  case 13: seg(tx, y0c, x1, ry);  break;
                    case 4:  case 11: seg(x1, ry, bx, y1c);  break;
                    case 8:  case 7:  seg(bx, y1c, x0, ly);  break;
                    case 3:  case 12: seg(x0, ly, x1, ry);   break;
                    case 6:  case 9:  seg(tx, y0c, bx, y1c); break;
                    case 5:  seg(x0, ly, tx, y0c); seg(x1, ry, bx, y1c); break;
                    case 10: seg(tx, y0c, x1, ry); seg(bx, y1c, x0, ly); break;
                }
            }
        }
    }

    /** Weak (covered, below T) cells, with connected regions under MIN_CONTOUR_CELLS removed. */
    private boolean[] buildFilteredWeakMask(float T) {
        boolean[] weak = new boolean[GRID_N * GRID_N];
        for (int idx = 0; idx < weak.length; idx++)
            weak[idx] = sumW[idx] >= W_MIN && sumWV[idx] / sumW[idx] < T;
        boolean[] visited = new boolean[weak.length];
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        java.util.List<Integer> component = new ArrayList<>();
        for (int start = 0; start < weak.length; start++) {
            if (!weak[start] || visited[start]) continue;
            component.clear();
            visited[start] = true; stack.push(start);
            while (!stack.isEmpty()) {
                int idx = stack.pop();
                component.add(idx);
                int gx = idx % GRID_N, gy = idx / GRID_N;
                if (gx > 0          && weak[idx-1]      && !visited[idx-1])      { visited[idx-1]=true;      stack.push(idx-1); }
                if (gx < GRID_N-1   && weak[idx+1]      && !visited[idx+1])      { visited[idx+1]=true;      stack.push(idx+1); }
                if (gy > 0          && weak[idx-GRID_N] && !visited[idx-GRID_N]) { visited[idx-GRID_N]=true; stack.push(idx-GRID_N); }
                if (gy < GRID_N-1   && weak[idx+GRID_N] && !visited[idx+GRID_N]) { visited[idx+GRID_N]=true; stack.push(idx+GRID_N); }
            }
            if (component.size() < MIN_CONTOUR_CELLS)
                for (int idx : component) weak[idx] = false;
        }
        return weak;
    }

    private static float frac(float a, float b, float t) {
        return (Math.abs(b - a) < 1e-4f) ? 0.5f : Math.max(0f, Math.min(1f, (t - a) / (b - a)));
    }

    private void seg(float ax, float ay, float bx, float by) {
        contourPath.moveTo(ax, ay); contourPath.lineTo(bx, by);
    }

    private void drawContour(Canvas canvas) {
        if (contourDirty) rebuildContour();
        if (contourPath.isEmpty()) return;
        contourMatrix.reset();
        contourMatrix.postScale(zoom, zoom);
        contourMatrix.postTranslate(panX, panY);
        contourPath.transform(contourMatrix, contourScreen);
        canvas.drawPath(contourScreen, contourPaint);
    }

    private int cellColor(int idx) {
        float w = sumW[idx];
        if (w < W_VIS) return 0;
        // soft outer edge: gentle power curve instead of a hard cutoff (no scallops)
        int alpha = (int) (HEAT_ALPHA * Math.pow(Math.min(1f, w / W_FULL), 0.75));
        if (mapLayer == MapLayer.SPEED) {
            float ws = sumWSp[idx];
            if (ws < W_MIN)   // covered, but no link-speed data here → neutral gray
                return setAlpha(Color.rgb(120, 120, 132), Math.min(alpha, 90));
            return setAlpha(speedColorForMbps(sumWVSp[idx] / ws), alpha);
        }
        return setAlpha(heatColorForRssi(sumWV[idx] / w), alpha);
    }

    private void drawHeatField(Canvas canvas) {
        heatMatrix.reset();
        heatMatrix.postScale(CELL * zoom, CELL * zoom);
        heatMatrix.postTranslate(panX, panY);
        canvas.drawBitmap(heatBitmap, heatMatrix, heatPaint);
    }

    // ── Extender suggestion ──────────────────────────────────────

    private void clearSuggestion() {
        suggestX = Float.NaN; suggestY = Float.NaN;
        weakX = Float.NaN;    weakY = Float.NaN;
        suggestionNote = "Extender here?";
        obstructionIndices.clear();
    }

    public boolean hasSuggestion()  { return !Float.isNaN(weakX); }
    public float getWeakZoneX()     { return weakX; }
    public float getWeakZoneY()     { return weakY; }
    public void setSuggestionNote(String note) { suggestionNote = note; invalidate(); }
    public void setObstructionIndices(List<Integer> idx) {
        obstructionIndices.clear(); obstructionIndices.addAll(idx); invalidate();
    }

    /**
     * Finds the centroid of the largest cluster of weak readings (the coverage
     * hole), then recommends placing the extender at the LAST GOOD point on the
     * walk toward it — an extender inside the dead zone would only repeat a bad
     * signal.
     */
    private void computeExtenderSuggestion() {
        clearSuggestion();
        List<ScanPoint> weak = new ArrayList<>();
        for (ScanPoint p : points) if (p.rssi <= WEAK_RSSI) weak.add(p);
        if (weak.size() < 3) return;

        float clusterR2 = 160f * 160f;
        int bestCount = 0; ScanPoint bestSeed = null;
        for (ScanPoint seed : weak) {
            int count = 0;
            for (ScanPoint p : weak) {
                float dx = p.x - seed.x, dy = p.y - seed.y;
                if (dx*dx + dy*dy <= clusterR2) count++;
            }
            if (count > bestCount) { bestCount = count; bestSeed = seed; }
        }
        if (bestSeed == null || bestCount < 3) return;

        float sx = 0, sy = 0; int n = 0;
        for (ScanPoint p : weak) {
            float dx = p.x - bestSeed.x, dy = p.y - bestSeed.y;
            if (dx*dx + dy*dy <= clusterR2) { sx += p.x; sy += p.y; n++; }
        }
        weakX = sx / n; weakY = sy / n;

        // Stage C: placement = the still-good point (≥ -60 dBm) closest to the hole
        ScanPoint bestGood = null; float bestD2 = Float.MAX_VALUE;
        for (ScanPoint p : points) {
            if (p.rssi < -60) continue;
            float dx = p.x - weakX, dy = p.y - weakY;
            float d2 = dx*dx + dy*dy;
            if (d2 < bestD2) { bestD2 = d2; bestGood = p; }
        }
        if (bestGood != null) { suggestX = bestGood.x; suggestY = bestGood.y; }
        else                  { suggestX = weakX;      suggestY = weakY;      }
    }

    private void drawSuggestion(Canvas canvas) {
        if (Float.isNaN(suggestX)) return;
        float sx = toSX(suggestX), sy = toSY(suggestY);

        // dashed link from the recommended spot to the hole it should cover
        if (!Float.isNaN(weakX) && (weakX != suggestX || weakY != suggestY)) {
            float hx = toSX(weakX), hy = toSY(weakY);
            Paint link = new Paint(Paint.ANTI_ALIAS_FLAG);
            link.setStyle(Paint.Style.STROKE);
            link.setStrokeWidth(1.8f * dp);
            link.setColor(Color.argb(150, 255, 171, 64));
            link.setPathEffect(new DashPathEffect(new float[]{6*dp, 6*dp}, 0));
            canvas.drawLine(sx, sy, hx, hy, link);
            // small dashed ring marking the weak-zone center
            Paint hole = new Paint(Paint.ANTI_ALIAS_FLAG);
            hole.setStyle(Paint.Style.STROKE);
            hole.setStrokeWidth(1.8f * dp);
            hole.setColor(Color.argb(190, 255, 82, 82));
            hole.setPathEffect(new DashPathEffect(new float[]{5*dp, 4*dp}, 0));
            canvas.drawCircle(hx, hy, 16f * dp, hole);
        }

        float r = 26f * dp;
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * dp);
        ring.setColor(Color.argb(230, 255, 171, 64));
        ring.setPathEffect(new DashPathEffect(new float[]{8*dp, 5*dp}, 0));
        canvas.drawCircle(sx, sy, r, ring);

        Paint ico = new Paint(Paint.ANTI_ALIAS_FLAG);
        ico.setColor(Color.argb(240, 255, 171, 64));
        ico.setTextAlign(Paint.Align.CENTER);
        ico.setTextSize(20f * dp);
        ico.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("+", sx, sy + 7f * dp, ico);

        String label = suggestionNote;
        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setTextSize(12f * dp); lbl.setTextAlign(Paint.Align.CENTER);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = lbl.measureText(label);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(230, 40, 26, 6));
        float lx = clampChipX(canvas, sx, tw/2 + 8*dp);
        float ly = clampChipY(canvas, sy + r + 5*dp, 20*dp);
        canvas.drawRoundRect(new RectF(lx-tw/2-8*dp, ly, lx+tw/2+8*dp, ly+20*dp), 5*dp, 5*dp, bg);
        lbl.setColor(Color.argb(245, 255, 200, 120));
        canvas.drawText(label, lx, ly + 14.5f*dp, lbl);

        // approximate size of the weak area, next to the hole it describes
        if (!Float.isNaN(weakX) && weakAreaM2 >= 1.5f) {
            String area = "~" + Math.round(weakAreaM2) + " m² weak";
            Paint at = new Paint(Paint.ANTI_ALIAS_FLAG);
            at.setTextSize(11f * dp); at.setTextAlign(Paint.Align.CENTER);
            at.setTypeface(Typeface.DEFAULT_BOLD);
            float atw = at.measureText(area);
            float ax = clampChipX(canvas, toSX(weakX), atw/2 + 7*dp);
            float ay = clampChipY(canvas, toSY(weakY) + 22*dp, 18*dp);
            Paint abg = new Paint(Paint.ANTI_ALIAS_FLAG);
            abg.setColor(Color.argb(215, 50, 12, 12));
            canvas.drawRoundRect(new RectF(ax-atw/2-7*dp, ay, ax+atw/2+7*dp, ay+18*dp), 5*dp, 5*dp, abg);
            at.setColor(Color.argb(245, 255, 150, 150));
            canvas.drawText(area, ax, ay + 13f*dp, at);
        }
    }

    /** True when so many points are flagged that hatching would hide the map. */
    public boolean isInterferenceWidespread() {
        if (points.isEmpty()) return false;
        int n = 0;
        for (ScanPoint p : points) if (p.interference) n++;
        return n * 100 > points.size() * 40;
    }

    /**
     * Stage A overlay — one unified purple hatched region (no overlap darkening).
     * When >40% of the scan is flagged the problem is global, not local — skip
     * the hatch entirely (a report banner covers it instead).
     */
    private void drawInterference(Canvas canvas) {
        if (isInterferenceWidespread()) return;
        float cx = 0, cy = 0; int n = 0;
        float topY = Float.MAX_VALUE;
        float r = Math.max(16f * dp, HEAT_RADIUS * 0.55f * zoom);
        Path region = new Path();
        for (ScanPoint p : points) {
            if (!p.interference) continue;
            float sx = toSX(p.x), sy = toSY(p.y);
            cx += sx; cy += sy; n++;
            topY = Math.min(topY, sy);
            region.addCircle(sx, sy, r, Path.Direction.CW);
        }
        if (n == 0) return;
        // union fill: one path = one alpha, however much the circles overlap
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setColor(Color.argb(48, 186, 104, 200));
        canvas.drawPath(region, fill);
        Paint stripe = new Paint(Paint.ANTI_ALIAS_FLAG);
        stripe.setColor(Color.argb(110, 186, 104, 200));
        stripe.setStrokeWidth(2f * dp);
        RectF b = new RectF();
        region.computeBounds(b, true);
        canvas.save();
        canvas.clipPath(region);
        for (float x = b.left - b.height(); x <= b.right; x += 10f * dp)
            canvas.drawLine(x, b.bottom, x + b.height(), b.top, stripe);
        canvas.restore();

        if (n >= 3) {
            String label = "⚠ Interference suspected";
            Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
            lbl.setTextSize(13f * dp); lbl.setTextAlign(Paint.Align.CENTER);
            lbl.setTypeface(Typeface.DEFAULT_BOLD);
            // chip sits ABOVE the whole marked area, clamped inside the canvas
            float tw = lbl.measureText(label);
            float lx = clampChipX(canvas, cx / n, tw/2 + 9*dp);
            float ly = clampChipY(canvas, topY - r - 28*dp, 22*dp);
            RectF chip = new RectF(lx-tw/2-9*dp, ly, lx+tw/2+9*dp, ly+22*dp);
            Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
            bg.setColor(Color.argb(235, 45, 20, 50));
            canvas.drawRoundRect(chip, 6*dp, 6*dp, bg);
            Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
            edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(1.5f * dp);
            edge.setColor(Color.argb(200, 210, 140, 255));
            canvas.drawRoundRect(chip, 6*dp, 6*dp, edge);
            lbl.setColor(Color.argb(250, 230, 180, 255));
            canvas.drawText(label, lx, ly + 16*dp, lbl);
        }
    }

    // Keep floating chips fully inside the canvas (labels were clipped at edges)
    private float clampChipX(Canvas c, float x, float halfWidth) {
        return Math.max(halfWidth + 4*dp, Math.min(c.getWidth() - halfWidth - 4*dp, x));
    }
    private float clampChipY(Canvas c, float y, float height) {
        return Math.max(4*dp, Math.min(c.getHeight() - height - 4*dp, y));
    }

    /** Stage D badges — orange "!" on points where signal fell far below the model. */
    private void drawObstructionBadges(Canvas canvas) {
        if (obstructionIndices.isEmpty()) return;
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.argb(235, 255, 145, 0));
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.WHITE); txt.setTextAlign(Paint.Align.CENTER);
        txt.setTextSize(10f * dp); txt.setTypeface(Typeface.DEFAULT_BOLD);
        for (int i : obstructionIndices) {
            if (i >= points.size()) continue;
            ScanPoint p = points.get(i);
            float sx = toSX(p.x), sy = toSY(p.y) - 12f * dp;
            canvas.drawCircle(sx, sy, 7f * dp, dot);
            canvas.drawText("!", sx, sy + 3.5f * dp, txt);
        }
    }

    // ── Pulse animation ──────────────────────────────────────────

    private void startPulse() {
        if (pulseAnimator != null && pulseAnimator.isRunning()) return;
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(a -> { pulseValue = (float) a.getAnimatedValue(); invalidate(); });
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) { pulseAnimator.cancel(); pulseAnimator = null; }
        pulseValue = 0f;
    }

    // ── Map modes ─────────────────────────────────────────────────

    private void applyMode() {
        if (getWidth() == 0) return;
        switch (mapMode) {
            case AUTO_FIT:    applyAutoFit();    break;
            case AUTO_CENTER: applyAutoCenter(); break;
            case FREE_SCROLL: break;
        }
    }

    private void applyAutoFit() {
        if (points.isEmpty() && !started) return;
        float minX=worldX,maxX=worldX,minY=worldY,maxY=worldY;
        for (ScanPoint p : points) {
            minX=Math.min(minX,p.x); maxX=Math.max(maxX,p.x);
            minY=Math.min(minY,p.y); maxY=Math.max(maxY,p.y);
        }
        float rX=maxX-minX+FIT_PAD*2, rY=maxY-minY+FIT_PAD*2;
        float sX=(rX>10)?getWidth()/rX:1f, sY=(rY>10)?getHeight()/rY:1f;
        zoom=Math.max(0.05f,Math.min(Math.min(sX,sY),MAX_FIT_ZOOM));
        panX=getWidth()/2f-((minX+maxX)/2f)*zoom;
        panY=getHeight()/2f-((minY+maxY)/2f)*zoom;
    }

    private void applyAutoCenter() {
        if (!started) return;
        float sx=toSX(worldX), sy=toSY(worldY), t=0.8f;
        boolean out=sx<getWidth()*(1-t)||sx>getWidth()*t||sy<getHeight()*(1-t)||sy>getHeight()*t;
        if (out) centerOnCurrent();
        if (points.size()>1) {
            float minX=worldX,maxX=worldX,minY=worldY,maxY=worldY;
            for(ScanPoint p:points){minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
            float nz=Math.min(getWidth()/(maxX-minX+FIT_PAD*3),getHeight()/(maxY-minY+FIT_PAD*3));
            if(nz<zoom) zoom=Math.max(0.05f,nz);
        }
    }

    private void centerOnCurrent() {
        if(getWidth()==0) return;
        panX=getWidth()/2f-worldX*zoom; panY=getHeight()/2f-worldY*zoom;
    }

    private float toSX(float wx) { return wx*zoom+panX; }
    private float toSY(float wy) { return wy*zoom+panY; }
    private float toWX(float sx) { return (sx-panX)/zoom; }
    private float toWY(float sy) { return (sy-panY)/zoom; }

    // ── Draw ─────────────────────────────────────────────────────

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Dark background
        canvas.drawColor(Color.parseColor("#060810"));
        drawGrid(canvas);

        if (!started && points.isEmpty()) {
            canvas.drawText("Tap here to START scanning",
                    getWidth()/2f, getHeight()/2f, hintPaint);
        } else {
            drawHeatField(canvas);
            drawContour(canvas);
            drawWalkPath(canvas);
            drawSampleDots(canvas);
            drawObstructionBadges(canvas);
            drawStartMarker(canvas);
            drawRoamingMarkers(canvas);
            drawMarkers(canvas);
            drawInterference(canvas);   // always on top — must never hide under the graph
            drawSuggestion(canvas);
            if (scanning) drawCurrentPosition(canvas);
            if (mapMode == MapMode.FREE_SCROLL) drawMiniMap(canvas);
            drawSignalLegend(canvas, 12f*dp, getHeight() - 30f*dp, 130f*dp);
            drawScaleBar(canvas, getWidth() - 16f*dp, getHeight() - 16f*dp);
            drawLayerChip(canvas);
        }
    }

    /** Smooth translucent walk path — quadratic curves through segment midpoints. */
    private void drawWalkPath(Canvas canvas) {
        if (points.size() < 2) return;
        walkPath.rewind();
        float px = toSX(points.get(0).x), py = toSY(points.get(0).y);
        walkPath.moveTo(px, py);
        for (int i = 1; i < points.size() - 1; i++) {
            float cx = toSX(points.get(i).x),   cy = toSY(points.get(i).y);
            float nx = toSX(points.get(i+1).x), ny = toSY(points.get(i+1).y);
            walkPath.quadTo(cx, cy, (cx + nx) / 2f, (cy + ny) / 2f);
        }
        ScanPoint last = points.get(points.size() - 1);
        walkPath.lineTo(toSX(last.x), toSY(last.y));
        pathPaint.setStrokeWidth(2.2f * dp);
        canvas.drawPath(walkPath, pathPaint);
    }

    /** Index of the AP (roam segment) that served point i — 0 before the first roam. */
    private int apIndexForPoint(int i) {
        int n = 0;
        for (int ri : roamingIndices) if (i > ri) n++;
        return n;
    }

    /** Small colored dots at each measurement; rim color identifies the serving AP.
     *  Kept small and subtle — the heat field is the star, not the dots. */
    private void drawSampleDots(Canvas canvas) {
        float r = 3f * dp;
        for (int i = 0; i < points.size(); i++) {
            ScanPoint p = points.get(i);
            float sx = toSX(p.x), sy = toSY(p.y);
            float pr = (i == points.size() - 1) ? r * lastDotScale : r;
            dotPaint.setColor(colorForRssi(p.rssi, isColorSplitAfterRoam(i)));
            canvas.drawCircle(sx, sy, pr, dotPaint);
            dotRimPaint.setColor(AP_RIM_COLORS[Math.min(apIndexForPoint(i), AP_RIM_COLORS.length - 1)]);
            canvas.drawCircle(sx, sy, pr, dotRimPaint);
        }
    }

    /** Fixed marker at the scan origin — the user starts 1 m from the router. */
    private void drawStartMarker(Canvas canvas) {
        float sx = toSX(WORLD_ORIGIN), sy = toSY(WORLD_ORIGIN);
        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setColor(Color.argb(60, 21, 101, 192));
        canvas.drawCircle(sx, sy, 20f * dp, halo);
        Paint body = new Paint(Paint.ANTI_ALIAS_FLAG);
        body.setColor(Color.rgb(21, 101, 192));
        canvas.drawCircle(sx, sy, 13f * dp, body);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(2f * dp);
        ring.setColor(Color.WHITE);
        canvas.drawCircle(sx, sy, 13f * dp, ring);
        Paint ico = new Paint(Paint.ANTI_ALIAS_FLAG);
        ico.setColor(Color.WHITE); ico.setTextAlign(Paint.Align.CENTER);
        ico.setTextSize(13f * dp); ico.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("R", sx, sy + 4.5f * dp, ico);

        String label = "Router / Start";
        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setTextSize(11f * dp); lbl.setTextAlign(Paint.Align.CENTER);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = lbl.measureText(label);
        float lx = clampChipX(canvas, sx, tw/2 + 6*dp);
        float ly = clampChipY(canvas, sy + 18f * dp, 17*dp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(220, 8, 25, 50));
        canvas.drawRoundRect(new RectF(lx-tw/2-6*dp, ly, lx+tw/2+6*dp, ly+17*dp), 5*dp, 5*dp, bg);
        lbl.setColor(Color.argb(240, 160, 205, 255));
        canvas.drawText(label, lx, ly + 12.5f * dp, lbl);
    }

    /** Google-Maps-style position puck: heading cone + blue dot with white ring. */
    private void drawCurrentPosition(Canvas canvas) {
        if (!started) return;
        float sx = toSX(worldX), sy = toSY(worldY);

        if (!Float.isNaN(headingDeg)) {
            float coneR = 38f * dp;
            Paint cone = new Paint(Paint.ANTI_ALIAS_FLAG);
            cone.setShader(new RadialGradient(sx, sy, coneR,
                    Color.argb(110, 88, 166, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP));
            // canvas angles: 0° = east; heading 0° = up
            float start = headingDeg - 90f - 35f;
            canvas.drawArc(new RectF(sx-coneR, sy-coneR, sx+coneR, sy+coneR), start, 70f, true, cone);
        }

        Paint halo = new Paint(Paint.ANTI_ALIAS_FLAG);
        halo.setColor(Color.argb(50, 88, 166, 255));
        canvas.drawCircle(sx, sy, 15f * dp, halo);
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setColor(Color.WHITE);
        canvas.drawCircle(sx, sy, 9f * dp, ring);
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setColor(Color.rgb(66, 133, 244));
        canvas.drawCircle(sx, sy, 6.5f * dp, dot);
    }

    /** Gradient legend bar — dBm scale or Mbps scale, matching the active layer. */
    private void drawSignalLegend(Canvas canvas, float x, float y, float w) {
        if (points.isEmpty()) return;
        float h = 8f * dp;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(190, 10, 15, 25));
        canvas.drawRoundRect(new RectF(x-6*dp, y-16*dp, x+w+6*dp, y+h+6*dp), 6*dp, 6*dp, bg);

        boolean speed = mapLayer == MapLayer.SPEED;
        float[] stops = speed ? SPEED_STOPS : HEAT_STOPS;
        int[] colors  = speed ? SPEED_COLORS : HEAT_COLORS;
        // gradient built from the stops, weak (left) → strong (right)
        int n = stops.length;
        int[] cols = new int[n];
        float[] pos = new float[n];
        float lo = stops[n-1], hi = stops[0];
        for (int i = 0; i < n; i++) {
            cols[i] = colors[n-1-i];
            pos[i]  = (stops[n-1-i] - lo) / (hi - lo);
        }
        Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        bar.setShader(new LinearGradient(x, 0, x+w, 0, cols, pos, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(new RectF(x, y, x+w, y+h), 3*dp, 3*dp, bar);

        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.argb(220, 255, 255, 255));
        txt.setTextSize(10f * dp);
        canvas.drawText(speed ? "≤" + (int) lo + " Mbps" : (int) lo + " dBm", x, y - 4*dp, txt);
        txt.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(speed ? (int) hi + "+ Mbps" : (int) hi + " dBm", x + w, y - 4*dp, txt);
    }

    /** Small "dBm ⇄ Mbps" toggle chip, top-left of the map (screen only, not export). */
    private void drawLayerChip(Canvas canvas) {
        if (points.isEmpty()) { layerChipRect.setEmpty(); return; }
        String text = mapLayer == MapLayer.SIGNAL ? "dBm ⇄" : "Mbps ⇄";
        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setTextSize(12f * dp); lbl.setTextAlign(Paint.Align.CENTER);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = lbl.measureText(text);
        layerChipRect.set(10*dp, 10*dp, 10*dp + tw + 20*dp, 10*dp + 26*dp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(210, 15, 22, 36));
        canvas.drawRoundRect(layerChipRect, 13*dp, 13*dp, bg);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(1.3f * dp);
        edge.setColor(Color.argb(180, 88, 166, 255));
        canvas.drawRoundRect(layerChipRect, 13*dp, 13*dp, edge);
        lbl.setColor(Color.argb(240, 150, 200, 255));
        canvas.drawText(text, layerChipRect.centerX(), layerChipRect.centerY() + 4.3f*dp, lbl);
    }

    /** Metric scale bar (right-bottom), rounded to 1/2/5/10/20 m for the current zoom. */
    private void drawScaleBar(Canvas canvas, float right, float y) {
        if (points.isEmpty()) return;
        float[] steps = {0.5f, 1f, 2f, 5f, 10f, 20f, 50f};
        float meters = steps[steps.length-1];
        for (float s : steps) {
            if (s * UNITS_PER_METER * zoom >= 48f * dp) { meters = s; break; }
        }
        float px = meters * UNITS_PER_METER * zoom;
        if (px > getWidth() * 0.5f) return;
        float x0 = right - px;
        Paint ln = new Paint(Paint.ANTI_ALIAS_FLAG);
        ln.setColor(Color.argb(220, 255, 255, 255));
        ln.setStrokeWidth(1.8f * dp);
        canvas.drawLine(x0, y, right, y, ln);
        canvas.drawLine(x0, y-4*dp, x0, y+4*dp, ln);
        canvas.drawLine(right, y-4*dp, right, y+4*dp, ln);
        Paint txt = new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.argb(220, 255, 255, 255));
        txt.setTextSize(11f * dp); txt.setTextAlign(Paint.Align.CENTER);
        String lbl = (meters == Math.floor(meters))
                ? (int) meters + " m" : meters + " m";
        canvas.drawText(lbl, (x0 + right) / 2f, y - 7*dp, txt);
    }

    private void drawRoamingMarkers(Canvas canvas) {
        for (int i = 0; i < roamingIndices.size(); i++) {
            int ri = roamingIndices.get(i);
            if (ri>=points.size()) continue;
            ScanPoint p=points.get(ri);
            float sx=toSX(p.x), sy=toSY(p.y);
            switch (roamStyle) {
                case FLASH_RING:  drawFlashRing(canvas,sx,sy);  break;
                case LIGHTNING:   drawLightning(canvas,sx,sy);  break;
                case BANNER:      drawBanner(canvas,sx,sy);     break;
                case COLOR_SPLIT: drawColorSplitMark(canvas,sx,sy); break;
            }
            String label = i < roamingLabels.size() ? roamingLabels.get(i) : null;
            if (label != null) drawRoamLabel(canvas, sx, sy, label);
        }
    }

    /** Tap on a roam marker → popup with the hand-off details. */
    private boolean handleRoamTap(float tx, float ty) {
        for (int i = 0; i < roamingIndices.size(); i++) {
            int ri = roamingIndices.get(i);
            if (ri >= points.size()) continue;
            ScanPoint p = points.get(ri);
            float sx = toSX(p.x), sy = toSY(p.y);
            if (Math.hypot(tx - sx, ty - sy) > 40 * dp) continue;
            String label = i < roamingLabels.size() && roamingLabels.get(i) != null
                    ? roamingLabels.get(i) : "Hand-off";
            String band = p.freqMhz >= 4900 ? "5 GHz" : p.freqMhz > 0 ? "2.4 GHz" : "?";
            java.text.SimpleDateFormat sdf =
                    new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
            StringBuilder msg = new StringBuilder();
            msg.append("Point #").append(ri + 1)
               .append("   ").append(sdf.format(new java.util.Date(p.timestamp))).append("\n\n")
               .append("Signal:  ").append(p.rssi).append(" dBm (").append(band).append(")\n");
            if (p.linkMbps > 0) msg.append("Link:     ").append(p.linkMbps).append(" Mbps\n");
            if (p.rttMs   > 0) msg.append("Latency: ").append(p.rttMs).append(" ms\n");
            if (p.interference) msg.append("\n⚠ Interference suspected at this spot");
            new android.app.AlertDialog.Builder(getContext())
                    .setTitle("⇄ Hand-off → " + label)
                    .setMessage(msg.toString())
                    .setPositiveButton("OK", null)
                    .show();
            return true;
        }
        return false;
    }

    /** Tap on any sample dot → same detail popup the roam markers get. */
    private boolean handlePointTap(float tx, float ty) {
        int best = -1; double bd = 18 * dp;
        for (int i = 0; i < points.size(); i++) {
            ScanPoint p = points.get(i);
            double d = Math.hypot(tx - toSX(p.x), ty - toSY(p.y));
            if (d < bd) { bd = d; best = i; }
        }
        if (best < 0) return false;
        ScanPoint p = points.get(best);
        String band = p.freqMhz >= 4900 ? "5 GHz" : p.freqMhz > 0 ? "2.4 GHz" : "?";
        java.text.SimpleDateFormat sdf =
                new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        StringBuilder msg = new StringBuilder();
        msg.append("Time:     ").append(sdf.format(new java.util.Date(p.timestamp))).append("\n")
           .append("Signal:   ").append(p.rssi).append(" dBm (").append(band).append(")\n")
           .append("Quality:  ").append(p.getQualityLabel()).append("\n");
        if (p.linkMbps > 0) msg.append("Link:      ").append(p.linkMbps).append(" Mbps\n");
        if (p.rttMs   > 0) msg.append("Latency:  ").append(p.rttMs).append(" ms\n");
        msg.append("Served by: AP ").append(apIndexForPoint(best) + 1);
        if (p.interference) msg.append("\n\n⚠ Interference suspected at this spot");
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("Point #" + (best + 1))
                .setMessage(msg.toString())
                .setPositiveButton("OK", null)
                .show();
        return true;
    }

    /** Tap on a colored area without a dot → honest "estimated" popup. */
    private boolean handleAreaTap(float tx, float ty) {
        float wx = toWX(tx), wy = toWY(ty);
        int gx = (int) (wx / CELL), gy = (int) (wy / CELL);
        if (gx < 0 || gx >= GRID_N || gy < 0 || gy >= GRID_N) return false;
        int idx = gy * GRID_N + gx;
        if (gridPixels[idx] == 0) return false;   // tapped the dark background
        boolean measured = sumW[idx] >= W_MIN;
        String msg;
        if (mapLayer == MapLayer.SPEED) {
            float v = measured && sumWSp[idx] >= W_MIN
                    ? sumWVSp[idx] / sumWSp[idx] : interpolateAt(wx, wy);
            if (Float.isNaN(v)) return false;
            msg = (measured ? "Average here:  " : "Estimated (interpolated):  ")
                    + Math.round(v) + " Mbps";
        } else {
            float v = measured ? sumWV[idx] / sumW[idx] : interpolateAt(wx, wy);
            if (Float.isNaN(v)) return false;
            msg = (measured ? "Average here:  " : "Estimated (interpolated):  ")
                    + Math.round(v) + " dBm";
        }
        new android.app.AlertDialog.Builder(getContext())
                .setTitle(measured ? "Measured area" : "Interpolated area")
                .setMessage(msg + (measured ? "" : "\n\nNo direct measurement here — value is estimated from nearby samples."))
                .setPositiveButton("OK", null)
                .show();
        return true;
    }

    /** "⇄ …tag" chip under a roam marker — identifies which AP took over. */
    private void drawRoamLabel(Canvas canvas, float sx, float sy, String label) {
        String text = "⇄ " + label;
        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setTextSize(13f * dp); lbl.setTextAlign(Paint.Align.CENTER);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = lbl.measureText(text);
        float cxq = clampChipX(canvas, sx, tw/2 + 8*dp);
        float ly = clampChipY(canvas, sy + 34 * dp, 22*dp);
        RectF chip = new RectF(cxq-tw/2-8*dp, ly, cxq+tw/2+8*dp, ly+22*dp);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(235, 40, 30, 8));
        canvas.drawRoundRect(chip, 6*dp, 6*dp, bg);
        Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(1.5f * dp);
        edge.setColor(Color.argb(200, 255, 200, 80));
        canvas.drawRoundRect(chip, 6*dp, 6*dp, edge);
        lbl.setColor(Color.argb(252, 255, 220, 130));
        canvas.drawText(text, cxq, ly + 16 * dp, lbl);
    }

    private void drawFlashRing(Canvas canvas, float sx, float sy) {
        float p=pulseValue;
        Paint r=new Paint(Paint.ANTI_ALIAS_FLAG);
        // soft amber fill so the spot stands out on the heat layer
        r.setStyle(Paint.Style.FILL);
        r.setColor(Color.argb(40,255,200,50));
        canvas.drawCircle(sx,sy,(24+p*8)*dp,r);
        r.setStyle(Paint.Style.STROKE);
        r.setStrokeWidth((2.5f+p*2.5f)*dp);
        r.setColor(Color.argb((int)(140+p*115),255,255,255));
        canvas.drawCircle(sx,sy,(27+p*16)*dp,r);
        r.setColor(Color.argb((int)(170+p*85),255,200,50));
        r.setStrokeWidth(2f*dp);
        canvas.drawCircle(sx,sy,(16+p*6)*dp,r);
    }

    private void drawLightning(Canvas canvas, float sx, float sy) {
        Paint t=new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER); t.setTextSize(22f*dp); t.setColor(0xFFFFD600);
        canvas.drawText("⚡",sx,sy-26*dp,t);
        Paint g=new Paint(Paint.ANTI_ALIAS_FLAG); g.setStyle(Paint.Style.FILL);
        g.setColor(Color.argb(45,255,214,0)); canvas.drawCircle(sx,sy,23*dp,g);
        g.setStyle(Paint.Style.STROKE); g.setColor(Color.argb(170,255,214,0));
        g.setStrokeWidth(2f*dp); canvas.drawCircle(sx,sy,23*dp,g);
    }

    private void drawBanner(Canvas canvas, float sx, float sy) {
        Paint dash=new Paint(Paint.ANTI_ALIAS_FLAG);
        dash.setStyle(Paint.Style.STROKE); dash.setStrokeWidth(1.5f*dp);
        dash.setColor(Color.argb(100,255,255,255));
        dash.setPathEffect(new DashPathEffect(new float[]{8*dp,5*dp},0));
        canvas.drawLine(sx,0,sx,getHeight(),dash);
        Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG); bg.setColor(Color.argb(210,20,30,50));
        canvas.drawRoundRect(new RectF(sx-40*dp,8*dp,sx+40*dp,28*dp),5*dp,5*dp,bg);
        Paint lt=new Paint(Paint.ANTI_ALIAS_FLAG); lt.setColor(Color.argb(230,255,255,255));
        lt.setTextSize(11f*dp); lt.setTextAlign(Paint.Align.CENTER);
        lt.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("ROAMING",sx,22*dp,lt);
    }

    private void drawColorSplitMark(Canvas canvas, float sx, float sy) {
        Paint cp=new Paint(Paint.ANTI_ALIAS_FLAG); cp.setStyle(Paint.Style.FILL);
        cp.setColor(Color.argb(55,140,80,255)); canvas.drawCircle(sx,sy,26*dp,cp);
        cp.setStyle(Paint.Style.STROKE); cp.setColor(Color.argb(200,160,100,255));
        cp.setStrokeWidth(2.2f*dp); canvas.drawCircle(sx,sy,26*dp,cp);
    }

    private void drawMarkers(Canvas canvas) {
        Paint circ=new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint ring=new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint ico=new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint lbl=new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(1.5f);
        ring.setColor(Color.argb(160,255,255,255));
        ico.setColor(Color.WHITE); ico.setTextAlign(Paint.Align.CENTER);
        ico.setTypeface(Typeface.DEFAULT_BOLD);
        bg.setColor(Color.argb(210,10,15,25));
        lbl.setColor(Color.WHITE); lbl.setTextAlign(Paint.Align.CENTER);
        lbl.setAntiAlias(true);
        float r=Math.max(14f*dp,20f*zoom);
        ico.setTextSize(r*0.9f); lbl.setTextSize(Math.max(10f*dp,12f*zoom));
        for (MapMarker m : markers) {
            float sx=toSX(m.worldX), sy=toSY(m.worldY);
            circ.setColor(m.iconColor()); canvas.drawCircle(sx,sy,r,circ);
            canvas.drawCircle(sx,sy,r,ring);
            canvas.drawText(m.iconText(),sx,sy+r*0.35f,ico);
            String lb=m.label.length()>14?m.label.substring(0,14):m.label;
            float tw=lbl.measureText(lb), ly=sy+r+4;
            canvas.drawRoundRect(new RectF(sx-tw/2-6,ly,sx+tw/2+6,ly+lbl.getTextSize()+5),4,4,bg);
            canvas.drawText(lb,sx,ly+lbl.getTextSize(),lbl);
        }
    }

    private void drawGrid(Canvas canvas) {
        float step=Math.max(40f,80*zoom), offX=panX%step, offY=panY%step;
        for(float x=offX;x<getWidth();x+=step) canvas.drawLine(x,0,x,getHeight(),gridPaint);
        for(float y=offY;y<getHeight();y+=step) canvas.drawLine(0,y,getWidth(),y,gridPaint);
    }

    private void drawMiniMap(Canvas canvas) {
        if (points.size()<2) return;
        float mW=110*dp,mH=80*dp,mX=getWidth()-mW-10*dp,mY=10*dp;
        canvas.drawRoundRect(new RectF(mX,mY,mX+mW,mY+mH),8,8,miniPaint);
        float minX=WORLD_ORIGIN,maxX=WORLD_ORIGIN,minY=WORLD_ORIGIN,maxY=WORLD_ORIGIN;
        for(ScanPoint p:points){minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
        float rX=Math.max(maxX-minX,10),rY=Math.max(maxY-minY,10);
        float sc=Math.min((mW-10)/rX,(mH-10)/rY);
        for(int i=1;i<points.size();i++){
            ScanPoint a=points.get(i-1),b=points.get(i);
            float ax=mX+5+(a.x-minX)*sc,ay=mY+5+(a.y-minY)*sc;
            float bx=mX+5+(b.x-minX)*sc,by=mY+5+(b.y-minY)*sc;
            miniDotPaint.setColor(setAlpha(blendColors(a.color,b.color,0.5f),200));
            miniDotPaint.setStrokeWidth(3f); miniDotPaint.setStyle(Paint.Style.STROKE);
            miniDotPaint.setStrokeCap(Paint.Cap.ROUND);
            canvas.drawLine(ax,ay,bx,by,miniDotPaint);
        }
        float cx=mX+5+(worldX-minX)*sc, cy=mY+5+(worldY-minY)*sc;
        miniDotPaint.setStyle(Paint.Style.FILL); miniDotPaint.setColor(Color.WHITE);
        canvas.drawCircle(Math.max(mX+5,Math.min(mX+mW-5,cx)),
                Math.max(mY+5,Math.min(mY+mH-5,cy)),4,miniDotPaint);
        Paint brd=new Paint(Paint.ANTI_ALIAS_FLAG);
        brd.setStyle(Paint.Style.STROKE); brd.setStrokeWidth(1f);
        brd.setColor(Color.argb(80,255,255,255));
        canvas.drawRoundRect(new RectF(mX,mY,mX+mW,mY+mH),8,8,brd);
    }

    // ── Touch ────────────────────────────────────────────────────

    private void handleTouch(float tx, float ty) {
        float wx=toWX(tx),wy=toWY(ty);
        ScanPoint best=null; double minD=HEAT_RADIUS;
        for(ScanPoint p:points){
            double d=Math.sqrt(Math.pow(wx-p.x,2)+Math.pow(wy-p.y,2));
            if(d<minD){minD=d;best=p;}
        }
        if(best!=null)
            Toast.makeText(getContext(),best.getQualityLabel()+"  "+best.rssi+" dBm",Toast.LENGTH_SHORT).show();
    }

    // ── Export full bitmap with watermark + legend ────────────────

    public Bitmap exportBitmap(String watermarkText) {
        if (points.isEmpty()) return null;
        float minX=points.get(0).x,maxX=minX,minY=points.get(0).y,maxY=minY;
        for(ScanPoint p:points){minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minY=Math.min(minY,p.y);maxY=Math.max(maxY,p.y);}
        float pad=FIT_PAD*1.5f,cW=maxX-minX+pad*2,cH=maxY-minY+pad*2;
        float ez=Math.max(0.3f,Math.min(3f,Math.min(1400f/cW,1400f/cH)));
        int bW=Math.max(900,Math.min((int)(cW*ez),2400));
        int bH=Math.max(900,Math.min((int)(cH*ez+120),2400)); // +120 for watermark bar

        Bitmap bmp=Bitmap.createBitmap(bW,bH,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bmp);
        c.drawColor(Color.parseColor("#060810"));

        float oPX=panX,oPY=panY,oZ=zoom;
        panX=pad*ez-minX*ez; panY=pad*ez-minY*ez; zoom=ez;

        drawGrid(c);
        drawHeatField(c);
        drawContour(c);
        drawWalkPath(c);
        drawSampleDots(c);
        drawObstructionBadges(c);
        drawStartMarker(c);
        drawRoamingMarkers(c);
        drawMarkers(c);
        drawInterference(c);
        drawSuggestion(c);
        drawSignalLegend(c, 16f*dp, 42f*dp, 150f*dp);
        drawScaleBar(c, bW - 20f*dp, 42f*dp);
        drawExportLegend(c,bW,bH);
        drawWatermark(c,bW,bH,watermarkText);

        panX=oPX; panY=oPY; zoom=oZ;
        return bmp;
    }

    private void drawExportLegend(Canvas canvas, int bW, int bH) {
        if (markers.isEmpty()) return;
        float barY=bH-115f, x=16f;
        Paint bg=new Paint(); bg.setColor(Color.argb(180,10,15,25));
        canvas.drawRect(0,barY-4,bW,barY+80,bg);
        Paint dot=new Paint(Paint.ANTI_ALIAS_FLAG);
        Paint txt=new Paint(Paint.ANTI_ALIAS_FLAG);
        txt.setColor(Color.argb(220,255,255,255));
        txt.setTextSize(22f); txt.setAntiAlias(true);
        for (MapMarker m : markers) {
            dot.setColor(m.iconColor());
            canvas.drawCircle(x+10,barY+28,12,dot);
            Paint ic=new Paint(Paint.ANTI_ALIAS_FLAG);
            ic.setColor(Color.WHITE); ic.setTextSize(12f);
            ic.setTextAlign(Paint.Align.CENTER); ic.setTypeface(Typeface.DEFAULT_BOLD);
            canvas.drawText(m.iconText(),x+10,barY+33,ic);
            canvas.drawText(m.label,x+26,barY+33,txt);
            x += txt.measureText(m.label)+60;
            if (x > bW-120) { x=16; barY+=36; }
        }
    }

    private void drawWatermark(Canvas canvas, int bW, int bH, String text) {
        Paint bg=new Paint(); bg.setColor(Color.argb(200,8,12,20));
        canvas.drawRect(0,bH-36,bW,bH,bg);
        Paint wt=new Paint(Paint.ANTI_ALIAS_FLAG);
        wt.setColor(Color.argb(180,255,255,255));
        wt.setTextSize(18f); wt.setAntiAlias(true);
        canvas.drawText(text,16,bH-12,wt);
        Paint rt=new Paint(Paint.ANTI_ALIAS_FLAG);
        rt.setColor(Color.argb(120,255,255,255));
        rt.setTextSize(16f); rt.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("WiFi Scout - Click Solutions Pro",bW-16,bH-12,rt);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private boolean isColorSplitAfterRoam(int idx) {
        return roamStyle==RoamStyle.COLOR_SPLIT && !roamingIndices.isEmpty() && idx>roamingIndices.get(0);
    }

    // Continuous color scale stops (like WiFiMan): green → yellow → orange → red
    private static final float[] HEAT_STOPS = { -45f, -55f, -63f, -70f, -78f, -88f };
    private static final int[]   HEAT_COLORS = {
            Color.rgb(0, 230, 118),    // excellent
            Color.rgb(174, 234, 0),    // good
            Color.rgb(255, 214, 0),    // fair
            Color.rgb(255, 145, 0),    // weak
            Color.rgb(255, 61, 0),     // bad
            Color.rgb(183, 28, 28)     // very bad
    };

    // Speed layer scale: deep green (fast) → dark red (slow), continuous
    private static final float[] SPEED_STOPS = { 150f, 100f, 60f, 30f, 10f };
    private static final int[]   SPEED_COLORS = {
            Color.rgb(0, 200, 83),     // 150+ Mbps
            Color.rgb(120, 220, 40),   // ~100
            Color.rgb(255, 214, 0),    // ~60
            Color.rgb(255, 145, 0),    // ~30
            Color.rgb(198, 40, 40)     // ≤10
    };

    private int speedColorForMbps(float mbps) {
        if (mbps >= SPEED_STOPS[0]) return SPEED_COLORS[0];
        int last = SPEED_STOPS.length - 1;
        if (mbps <= SPEED_STOPS[last]) return SPEED_COLORS[last];
        for (int i = 1; i <= last; i++) {
            if (mbps > SPEED_STOPS[i]) {
                float t = (SPEED_STOPS[i-1] - mbps) / (SPEED_STOPS[i-1] - SPEED_STOPS[i]);
                return blendColors(SPEED_COLORS[i-1], SPEED_COLORS[i], t);
            }
        }
        return SPEED_COLORS[last];
    }

    public MapLayer getMapLayer() { return mapLayer; }

    /** Switch the coloring layer (dBm ↔ Mbps) and repaint the whole field. */
    public void setMapLayer(MapLayer layer) {
        if (layer == mapLayer) return;
        mapLayer = layer;
        for (int idx = 0; idx < gridPixels.length; idx++)
            gridPixels[idx] = sumW[idx] >= W_VIS ? cellColor(idx) : 0;
        heatBitmap.setPixels(gridPixels, 0, GRID_N, 0, 0, GRID_N, GRID_N);
        if (!scanning && !points.isEmpty()) fillCoverageGaps();
        invalidate();
    }

    /** Smooth interpolated color for the heat field — no visible banding. */
    private int heatColorForRssi(float rssi) {
        if (rssi >= HEAT_STOPS[0]) return HEAT_COLORS[0];
        int last = HEAT_STOPS.length - 1;
        if (rssi <= HEAT_STOPS[last]) return HEAT_COLORS[last];
        for (int i = 1; i <= last; i++) {
            if (rssi > HEAT_STOPS[i]) {
                float t = (HEAT_STOPS[i-1] - rssi) / (HEAT_STOPS[i-1] - HEAT_STOPS[i]);
                return blendColors(HEAT_COLORS[i-1], HEAT_COLORS[i], t);
            }
        }
        return HEAT_COLORS[last];
    }

    /** Discrete color for sample dots (blue family after roam in COLOR_SPLIT mode). */
    private int colorForRssi(int rssi, boolean blue) {
        if (blue) {
            if (rssi>=-45) return Color.rgb(40,160,255);
            if (rssi>=-55) return Color.rgb(70,110,255);
            if (rssi>=-65) return Color.rgb(110,70,240);
            if (rssi>=-75) return Color.rgb(140,40,200);
            return Color.rgb(120,0,160);
        }
        return heatColorForRssi(rssi);
    }

    private int blendColors(int c1,int c2,float r){
        float i=1-r;
        return Color.rgb((int)(Color.red(c1)*i+Color.red(c2)*r),
                (int)(Color.green(c1)*i+Color.green(c2)*r),
                (int)(Color.blue(c1)*i+Color.blue(c2)*r));
    }

    private int setAlpha(int c,int a){
        return Color.argb(a,Color.red(c),Color.green(c),Color.blue(c));
    }
}
