package com.clicksolutions.wifiscout;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
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

    private static final float WORLD_SIZE   = 3000f;
    private static final float WORLD_ORIGIN = WORLD_SIZE / 2f;

    // ── Heat field (IDW grid) ────────────────────────────────────
    private static final float CELL        = 12f;                       // world units per cell
    private static final int   GRID_N      = (int) (WORLD_SIZE / CELL); // 250 x 250
    private static final float HEAT_RADIUS = 110f;   // influence radius of one sample (~2 steps)
    private static final float W_MIN       = 0.02f;  // below this weight the cell is "not covered"
    private static final float W_FULL      = 0.35f;  // weight at which the cell reaches full opacity
    private static final int   HEAT_ALPHA  = 170;    // max opacity of the heat layer

    private static final float FIT_PAD     = 140f;   // world padding for auto-fit
    private static final float MIN_MOVE_PX = 15f;
    private static final float WEAK_RSSI   = -72f;   // "weak" threshold for extender suggestion

    private final float[] sumW  = new float[GRID_N * GRID_N];
    private final float[] sumWV = new float[GRID_N * GRID_N];
    private final int[]   gridPixels = new int[GRID_N * GRID_N];
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
    private final Paint crossPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint miniPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint miniDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path  walkPath     = new Path();

    private float worldX = WORLD_ORIGIN;
    private float worldY = WORLD_ORIGIN;
    private boolean started  = false;
    private boolean scanning = false;  // hide crosshair after stop

    // Extender suggestion (weakest cluster centroid), NaN = none
    private float suggestX = Float.NaN;
    private float suggestY = Float.NaN;

    private float panX = 0f, panY = 0f, zoom = 1.0f;

    private MapMode   mapMode   = MapMode.AUTO_FIT;
    private RoamStyle roamStyle = RoamStyle.FLASH_RING;

    private ValueAnimator pulseAnimator;
    private float         pulseValue = 0f;

    private GestureDetector      gestureDetector;
    private ScaleGestureDetector scaleDetector;

    public WifiHeatmapView(Context c) { super(c); init(); }
    public WifiHeatmapView(Context c, AttributeSet a) { super(c, a); init(); }
    public WifiHeatmapView(Context c, AttributeSet a, int d) { super(c, a, d); init(); }

    private void init() {
        heatBitmap = Bitmap.createBitmap(GRID_N, GRID_N, Bitmap.Config.ARGB_8888);

        pathPaint.setStyle(Paint.Style.STROKE);
        pathPaint.setStrokeCap(Paint.Cap.ROUND);
        pathPaint.setStrokeJoin(Paint.Join.ROUND);
        pathPaint.setColor(Color.argb(90, 255, 255, 255));

        dotPaint.setStyle(Paint.Style.FILL);
        dotRimPaint.setStyle(Paint.Style.STROKE);
        dotRimPaint.setStrokeWidth(1.5f);
        dotRimPaint.setColor(Color.argb(150, 6, 8, 16));

        gridPaint.setColor(Color.argb(10, 255, 255, 255));
        gridPaint.setStrokeWidth(1f);
        gridPaint.setStyle(Paint.Style.STROKE);

        crossPaint.setColor(Color.WHITE);
        crossPaint.setStrokeWidth(2.5f);
        crossPaint.setStyle(Paint.Style.STROKE);
        crossPaint.setAlpha(230);

        hintPaint.setColor(Color.argb(60, 255, 255, 255));
        hintPaint.setTextSize(36f);
        hintPaint.setTextAlign(Paint.Align.CENTER);
        hintPaint.setAntiAlias(true);

        miniPaint.setColor(Color.argb(190, 15, 18, 28));
        miniPaint.setStyle(Paint.Style.FILL);

        miniDotPaint.setAntiAlias(true);

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
                    @Override public void onLongPress(MotionEvent e) { handleTouch(e.getX(), e.getY()); }
                });
        scaleDetector = new ScaleGestureDetector(getContext(),
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector d) {
                        if (mapMode == MapMode.FREE_SCROLL) {
                            zoom = Math.max(0.2f, Math.min(4f, zoom * d.getScaleFactor()));
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

    public void setScanning(boolean s) {
        scanning = s;
        if (s) clearSuggestion(); else computeExtenderSuggestion();
        invalidate();
    }

    public void updatePosition(float wx, float wy) {
        worldX = wx; worldY = wy; started = true; applyMode(); invalidate();
    }

    public void addPoint(int signalLevel, int rssi, String ssid) {
        if (!started) return;
        // Skip duplicate position — no movement
        if (!Float.isNaN(lastDrawnX)) {
            float dx = worldX - lastDrawnX;
            float dy = worldY - lastDrawnY;
            if (Math.sqrt(dx*dx + dy*dy) < MIN_MOVE_PX) return;
        }
        lastDrawnX = worldX; lastDrawnY = worldY;
        points.add(new ScanPoint((int) worldX, (int) worldY,
                colorForRssi(rssi, false), signalLevel, rssi, ssid));
        addHeatSample(worldX, worldY, rssi);
        applyMode(); invalidate();
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

    public void resetOrigin() {
        worldX = WORLD_ORIGIN; worldY = WORLD_ORIGIN;
        started = false; scanning = true;
        lastDrawnX = Float.NaN; lastDrawnY = Float.NaN;
        clearSuggestion();
        centerOnCurrent(); invalidate();
    }

    // ── Heat field ───────────────────────────────────────────────

    private void clearHeatField() {
        java.util.Arrays.fill(sumW, 0f);
        java.util.Arrays.fill(sumWV, 0f);
        java.util.Arrays.fill(gridPixels, 0);
        heatBitmap.eraseColor(Color.TRANSPARENT);
    }

    /** Splat one RSSI sample into the grid with a smooth compact kernel. */
    private void addHeatSample(float wx, float wy, int rssi) {
        int cellR = (int) Math.ceil(HEAT_RADIUS / CELL);
        int cx = (int) (wx / CELL), cy = (int) (wy / CELL);
        int x0 = Math.max(0, cx - cellR), x1 = Math.min(GRID_N - 1, cx + cellR);
        int y0 = Math.max(0, cy - cellR), y1 = Math.min(GRID_N - 1, cy + cellR);
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
                gridPixels[idx] = cellColor(idx);
            }
        }
        heatBitmap.setPixels(gridPixels, y0 * GRID_N + x0, GRID_N,
                x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private int cellColor(int idx) {
        float w = sumW[idx];
        if (w < W_MIN) return 0;
        float rssi = sumWV[idx] / w;
        int alpha = (int) (HEAT_ALPHA * Math.min(1f, w / W_FULL));
        return setAlpha(heatColorForRssi(rssi), alpha);
    }

    private void drawHeatField(Canvas canvas) {
        heatMatrix.reset();
        heatMatrix.postScale(CELL * zoom, CELL * zoom);
        heatMatrix.postTranslate(panX, panY);
        canvas.drawBitmap(heatBitmap, heatMatrix, heatPaint);
    }

    // ── Extender suggestion ──────────────────────────────────────

    private void clearSuggestion() { suggestX = Float.NaN; suggestY = Float.NaN; }

    /**
     * Finds the centroid of the largest cluster of weak readings.
     * That is where an extender (or a better-placed one) is needed.
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
        suggestX = sx / n; suggestY = sy / n;
    }

    private void drawSuggestion(Canvas canvas) {
        if (Float.isNaN(suggestX)) return;
        float sx = toSX(suggestX), sy = toSY(suggestY);
        float r = 34f;
        Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2.5f);
        ring.setColor(Color.argb(230, 255, 171, 64));
        ring.setPathEffect(new DashPathEffect(new float[]{10, 6}, 0));
        canvas.drawCircle(sx, sy, r, ring);

        Paint ico = new Paint(Paint.ANTI_ALIAS_FLAG);
        ico.setColor(Color.argb(240, 255, 171, 64));
        ico.setTextAlign(Paint.Align.CENTER);
        ico.setTextSize(26f);
        ico.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("+", sx, sy + 9f, ico);

        String label = "Extender here";
        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setTextSize(13f); lbl.setTextAlign(Paint.Align.CENTER);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = lbl.measureText(label);
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(220, 40, 26, 6));
        float ly = sy + r + 6;
        canvas.drawRoundRect(new RectF(sx-tw/2-8, ly, sx+tw/2+8, ly+21), 5, 5, bg);
        lbl.setColor(Color.argb(240, 255, 200, 120));
        canvas.drawText(label, sx, ly + 15, lbl);
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
        zoom=Math.max(0.05f,Math.min(sX,sY));
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
            canvas.drawText("Tap START and walk around",
                    getWidth()/2f, getHeight()/2f, hintPaint);
        } else {
            drawHeatField(canvas);
            drawWalkPath(canvas);
            drawSampleDots(canvas);
            drawRoamingMarkers(canvas);
            drawMarkers(canvas);
            drawSuggestion(canvas);
            if (scanning) drawCurrentPosition(canvas);
            if (mapMode == MapMode.FREE_SCROLL) drawMiniMap(canvas);
        }
    }

    /** Thin translucent polyline showing where the user walked. */
    private void drawWalkPath(Canvas canvas) {
        if (points.size() < 2) return;
        walkPath.rewind();
        walkPath.moveTo(toSX(points.get(0).x), toSY(points.get(0).y));
        for (int i = 1; i < points.size(); i++)
            walkPath.lineTo(toSX(points.get(i).x), toSY(points.get(i).y));
        pathPaint.setStrokeWidth(3f);
        canvas.drawPath(walkPath, pathPaint);
    }

    /** Small colored dots at each measurement — screen-sized, never smeared. */
    private void drawSampleDots(Canvas canvas) {
        float r = Math.max(3.5f, Math.min(7f, 5f * zoom));
        for (int i = 0; i < points.size(); i++) {
            ScanPoint p = points.get(i);
            float sx = toSX(p.x), sy = toSY(p.y);
            dotPaint.setColor(colorForRssi(p.rssi, isColorSplitAfterRoam(i)));
            canvas.drawCircle(sx, sy, r, dotPaint);
            canvas.drawCircle(sx, sy, r, dotRimPaint);
        }
    }

    private void drawCurrentPosition(Canvas canvas) {
        if (!started) return;
        float sx=toSX(worldX), sy=toSY(worldY), s=16;
        canvas.drawLine(sx-s,sy,sx+s,sy,crossPaint);
        canvas.drawLine(sx,sy-s,sx,sy+s,crossPaint);
        Paint r=new Paint(Paint.ANTI_ALIAS_FLAG);
        r.setStyle(Paint.Style.STROKE); r.setStrokeWidth(2f);
        r.setColor(Color.argb(180,255,255,255));
        canvas.drawCircle(sx,sy,14,r);
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

    /** Small "⇄ …tag" chip under a roam marker — identifies which AP took over. */
    private void drawRoamLabel(Canvas canvas, float sx, float sy, String label) {
        String text = "⇄ " + label;
        Paint lbl = new Paint(Paint.ANTI_ALIAS_FLAG);
        lbl.setTextSize(11f); lbl.setTextAlign(Paint.Align.CENTER);
        lbl.setTypeface(Typeface.DEFAULT_BOLD);
        float tw = lbl.measureText(text), ly = sy + 34;
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setColor(Color.argb(215, 20, 30, 50));
        canvas.drawRoundRect(new RectF(sx-tw/2-6, ly, sx+tw/2+6, ly+18), 5, 5, bg);
        lbl.setColor(Color.argb(235, 255, 220, 130));
        canvas.drawText(text, sx, ly + 13, lbl);
    }

    private void drawFlashRing(Canvas canvas, float sx, float sy) {
        float p=pulseValue;
        Paint r=new Paint(Paint.ANTI_ALIAS_FLAG);
        r.setStyle(Paint.Style.STROKE);
        r.setStrokeWidth(2f+p*2f);
        r.setColor(Color.argb((int)(120+p*135),255,255,255));
        canvas.drawCircle(sx,sy,26+p*16,r);
        r.setColor(Color.argb((int)(150+p*105),255,200,50));
        r.setStrokeWidth(1.5f);
        canvas.drawCircle(sx,sy,14+p*5,r);
    }

    private void drawLightning(Canvas canvas, float sx, float sy) {
        Paint t=new Paint(Paint.ANTI_ALIAS_FLAG);
        t.setTextAlign(Paint.Align.CENTER); t.setTextSize(22f); t.setColor(0xFFFFD600);
        canvas.drawText("⚡",sx,sy-28,t);
        Paint g=new Paint(Paint.ANTI_ALIAS_FLAG); g.setStyle(Paint.Style.FILL);
        g.setColor(Color.argb(30,255,214,0)); canvas.drawCircle(sx,sy,22,g);
        g.setStyle(Paint.Style.STROKE); g.setColor(Color.argb(120,255,214,0));
        g.setStrokeWidth(1.5f); canvas.drawCircle(sx,sy,22,g);
    }

    private void drawBanner(Canvas canvas, float sx, float sy) {
        Paint dp=new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setStyle(Paint.Style.STROKE); dp.setStrokeWidth(1.5f);
        dp.setColor(Color.argb(100,255,255,255));
        dp.setPathEffect(new DashPathEffect(new float[]{8,5},0));
        canvas.drawLine(sx,0,sx,getHeight(),dp);
        Paint bg=new Paint(Paint.ANTI_ALIAS_FLAG); bg.setColor(Color.argb(210,20,30,50));
        canvas.drawRoundRect(new RectF(sx-38,10,sx+38,30),5,5,bg);
        Paint lt=new Paint(Paint.ANTI_ALIAS_FLAG); lt.setColor(Color.argb(230,255,255,255));
        lt.setTextSize(11f); lt.setTextAlign(Paint.Align.CENTER);
        lt.setTypeface(Typeface.DEFAULT_BOLD);
        canvas.drawText("ROAMING",sx,24,lt);
    }

    private void drawColorSplitMark(Canvas canvas, float sx, float sy) {
        Paint cp=new Paint(Paint.ANTI_ALIAS_FLAG); cp.setStyle(Paint.Style.FILL);
        cp.setColor(Color.argb(40,140,80,255)); canvas.drawCircle(sx,sy,28,cp);
        cp.setStyle(Paint.Style.STROKE); cp.setColor(Color.argb(160,160,100,255));
        cp.setStrokeWidth(2f); canvas.drawCircle(sx,sy,28,cp);
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
        float r=Math.max(16f,20f*zoom);
        ico.setTextSize(r*0.9f); lbl.setTextSize(Math.max(11f,12f*zoom));
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
        float mW=110,mH=80,mX=getWidth()-mW-10,mY=10;
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
        drawWalkPath(c);
        drawSampleDots(c);
        drawRoamingMarkers(c);
        drawMarkers(c);
        drawSuggestion(c);
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
