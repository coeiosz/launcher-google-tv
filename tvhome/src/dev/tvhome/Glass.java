package dev.tvhome;

import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;

import java.util.ArrayList;
import java.util.List;

/**
 * "Liquid glass" surfaces (pills, row panel, menu). Nothing is captured from the screen: what lies
 * behind every surface is the wash, a known 64x36 bitmap already blurred, so an AGSL shader
 * samples it directly at the surface's on-screen position. Only the curved rim samples it (bent
 * inward, red and blue bent by different amounts); the flat interior is a plain white veil over
 * whatever is really behind, so most pixels cost no texture reads at all.
 *
 * The shader's idea of "behind" must follow the surface as it moves and the wash as it crossfades,
 * so once per frame (pre-draw, only while the tree is drawing anyway) each surface reads its
 * on-screen transform and the wash views' state, and re-records itself only if something changed.
 *
 * API 33+ (RuntimeShader). Below that, or if the shader fails to compile, enabled() is false and
 * the callers keep their flat translucent look. Main thread only.
 */
final class Glass implements ViewTreeObserver.OnPreDrawListener {
    private static final String TAG = "tvhome";

    // Surface-local px -> wash view px is xform.xy + p * xform.zw (the surfaces only translate
    // and scale). Behind a surface lies wash * baseK, veiled by baseVeil (the menu's own glass).
    private static final String AGSL =
            "uniform shader washA;\n" // front wash (the one fading in)
            + "uniform shader washB;\n" // back wash
            + "uniform float mixA;\n" // front wash's weight: its alpha during the crossfade
            + "uniform float4 xform;\n"
            + "uniform float4 rect;\n" // surface: left, top, width, height (px)
            + "uniform float radius;\n"
            + "uniform float bevel;\n" // width of the curved rim (px)
            + "uniform float bendPx;\n" // inward bend at the very edge (px)
            + "uniform float aberr;\n" // red/blue bend this much less/more than green
            + "uniform float baseK;\n"
            + "uniform float baseVeil;\n"
            + "uniform float frost;\n" // white veil at the top, half of it at the bottom
            + "uniform float lift;\n" // 0 clear .. 1 lit (focused) glass
            + "uniform float opaque;\n" // 1: interior drawn from the wash too (over tiles)
            + "uniform float shine;\n" // specular strength
            + "uniform float hair;\n" // highlight line width (px)
            + "\n"
            + "float3 behind(float2 p) {\n"
            + "  float2 q = xform.xy + p * xform.zw;\n"
            + "  float3 c = washA.eval(q).rgb;\n"
            + "  if (mixA < 0.999) { c = mix(washB.eval(q).rgb, c, mixA); }\n"
            + "  return mix(c * baseK, float3(1.0), baseVeil);\n"
            + "}\n"
            + "\n"
            + "float edgeLine(float depth) {\n"
            + "  float x = clamp(1.0 - depth / hair, 0.0, 1.0);\n"
            + "  return x * x;\n"
            + "}\n"
            + "\n"
            + "half4 main(float2 p) {\n"
            + "  float2 hs = rect.zw * 0.5;\n"
            + "  float2 lp = p - rect.xy - hs;\n"
            + "  float2 q = abs(lp) - hs + radius;\n"
            + "  float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - radius;\n"
            + "  float cov = clamp(0.5 - d, 0.0, 1.0);\n"
            + "  if (cov <= 0.0) { return half4(0.0); }\n"
            + "  float depth = -d;\n"
            + "  float y = clamp((p.y - rect.y) / rect.w, 0.0, 1.0);\n"
            + "  float veil = frost * (1.0 - 0.5 * y);\n"
            // Lit glass glows from the middle; its rim stays clearer, so it reads as thickness.
            + "  float lit = lift * (0.75 + 0.25 * clamp(depth / bevel, 0.0, 1.0));\n"
            + "  float a = 1.0 - (1.0 - veil) * (1.0 - 0.86 * lit);\n"
            + "  if (depth >= bevel) {\n"
            + "    if (opaque < 0.5) { return half4(a, a, a, a); }\n"
            + "    return half4(mix(behind(p), float3(1.0), a), 1.0);\n"
            + "  }\n"
            + "  float2 n = (q.x > 0.0 && q.y > 0.0) ? normalize(q) : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
            + "  n *= sign(lp);\n"
            + "  float e = 1.0 - depth / bevel;\n" // 0 where the bevel starts, 1 at the edge
            + "  float bend = e * e;\n"
            + "  float2 off = n * (bendPx * bend);\n"
            + "  float3 c = float3(behind(p - off * (1.0 - aberr)).r, behind(p - off).g,\n"
            + "      behind(p - off * (1.0 + aberr)).b);\n"
            + "  c = mix(c, float3(1.0), a);\n"
            // Key light from above, a little to the left; a weaker bounce on the opposite rim.
            + "  float facing = dot(n, float2(-0.287, -0.958));\n"
            + "  float key = pow(max(facing, 0.0), 1.5);\n"
            + "  float bounce = pow(max(-facing, 0.0), 1.5);\n"
            + "  float glint = 0.12 + 0.88 * key + 0.35 * bounce;\n"
            // The highlight line, with red and blue a fraction of a pixel apart: a faint fringe.
            + "  float f = hair * 0.3;\n"
            + "  float3 line = float3(edgeLine(depth + f), edgeLine(depth), edgeLine(depth - f));\n"
            + "  float shade = bend * 0.16 * (1.0 - 0.7 * key) * (1.0 - 0.6 * lift);\n"
            + "  float sheen = bend * key * 0.1;\n"
            + "  c = c * (1.0 - shade) + (line * glint + sheen) * shine;\n"
            + "  return half4(clamp(c, 0.0, 1.0) * cov, cov);\n"
            + "}\n";

    /** How a surface looks; lengths in dp. */
    static final class Style {
        float radius, bevel, refract, aberr = 0.4f, frost, baseK = 1f, baseVeil, shine, hair = 1.2f;
        boolean opaque, hideIdle;
        // Flat look for a software canvas (never expected on the TV, but must not crash).
        int fallback = 0x26FFFFFF;

        Style radius(float v) { radius = v; return this; }
        Style bevel(float v) { bevel = v; return this; }
        Style refract(float v) { refract = v; return this; }
        Style frost(float v) { frost = v; return this; }
        Style shine(float v) { shine = v; return this; }
        Style base(float k, float veil) { baseK = k; baseVeil = veil; return this; }
        Style opaque() { opaque = true; return this; }
        Style hideIdle() { hideIdle = true; return this; }
        Style fallback(int argb) { fallback = argb; return this; }
    }

    private final ImageView washFront, washBack;
    private final float density;
    private final boolean enabled;
    private final List<Surface> surfaces = new ArrayList<>();
    private final Matrix washInv = new Matrix(), tmp = new Matrix();
    private Bitmap bmpA, bmpB;
    private BitmapShader shA, shB, black;
    private int washW, washH;

    Glass(ImageView washFront, ImageView washBack, float density) {
        this.washFront = washFront;
        this.washBack = washBack;
        this.density = density;
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                new RuntimeShader(AGSL);
                // Before any wash is shown the background is black: sample a 1x1 black bitmap.
                black = new BitmapShader(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888),
                        Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
                ok = true;
            } catch (RuntimeException e) {
                Log.w(TAG, "glass shader unavailable; flat surfaces", e);
            }
        }
        enabled = ok;
        if (enabled) washFront.getViewTreeObserver().addOnPreDrawListener(this);
    }

    /** False below API 33 or if the shader failed: callers keep the flat look. */
    boolean enabled() { return enabled; }

    /** A glass drawable drawn in host's own coordinates (its background, or a layer of it). */
    Surface surface(View host, Style s) {
        Surface g = new Surface(host, s, density);
        if (enabled) surfaces.add(g);
        return g;
    }

    @Override public boolean onPreDraw() {
        if (washFront.getWidth() == 0) return true;
        try {
            sync();
        } catch (RuntimeException e) {
            // Never take the home screen down over a visual: the surfaces keep their last state.
            Log.w(TAG, "glass sync failed", e);
        }
        return true;
    }

    private void sync() {
        Bitmap fa = bitmapOf(washFront);
        boolean backOn = washBack.getVisibility() == View.VISIBLE;
        Bitmap fb = backOn ? bitmapOf(washBack) : null;
        float mix = fa == null ? 0f : backOn ? washFront.getAlpha() : 1f;

        boolean resized = washFront.getWidth() != washW || washFront.getHeight() != washH;
        washW = washFront.getWidth();
        washH = washFront.getHeight();
        if (resized) bmpA = bmpB = null;
        // Front and back swap bitmaps on every change of app: reuse the shader that already exists.
        BitmapShader na = fa == bmpA ? shA : fa == bmpB ? shB : washShader(fa);
        BitmapShader nb = fb == bmpA ? shA : fb == bmpB ? shB : washShader(fb);
        bmpA = fa;
        bmpB = fb;
        shA = na;
        shB = nb;

        tmp.reset();
        washFront.transformMatrixToGlobal(tmp);
        if (!tmp.invert(washInv)) return;
        for (Surface s : surfaces) s.sync(washInv, na != null ? na : black, nb != null ? nb : black, mix);
    }

    private BitmapShader washShader(Bitmap b) {
        if (b == null) return null;
        // Hardware bitmaps are fine here: only ever drawn by the GPU (Surface checks the canvas).
        BitmapShader s = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (Build.VERSION.SDK_INT >= 33) s.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        // The wash view stretches the bitmap over itself (FIT_XY).
        Matrix m = new Matrix();
        m.setScale(washW / (float) b.getWidth(), washH / (float) b.getHeight());
        s.setLocalMatrix(m);
        return s;
    }

    private static Bitmap bitmapOf(ImageView v) {
        Drawable d = v.getDrawable();
        return d instanceof BitmapDrawable ? ((BitmapDrawable) d).getBitmap() : null;
    }

    /** One glass surface. lift (0..1) is the focused, lit state. */
    static final class Surface extends Drawable {
        private final View host;
        private final Style s;
        private final float density;
        private final RuntimeShader shader;
        private final Paint paint = new Paint(), flat = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Matrix m = new Matrix();
        private final float[] v = new float[9];
        private final float[] last = {Float.NaN, 0, 0, 0, 0};
        private final RectF r = new RectF();
        private final ValueAnimator liftAnim = ValueAnimator.ofFloat(0f, 1f);
        private Shader childA, childB;
        private float lift;
        private boolean synced;

        Surface(View host, Style s, float density) {
            this.host = host;
            this.s = s;
            this.density = density;
            RuntimeShader sh = null;
            if (Build.VERSION.SDK_INT >= 33) {
                try {
                    sh = new RuntimeShader(AGSL);
                    sh.setFloatUniform("radius", s.radius * density);
                    sh.setFloatUniform("bevel", s.bevel * density);
                    sh.setFloatUniform("bendPx", s.refract * density);
                    sh.setFloatUniform("aberr", s.aberr);
                    sh.setFloatUniform("baseK", s.baseK);
                    sh.setFloatUniform("baseVeil", s.baseVeil);
                    sh.setFloatUniform("frost", s.frost);
                    sh.setFloatUniform("lift", 0f);
                    sh.setFloatUniform("opaque", s.opaque ? 1f : 0f);
                    sh.setFloatUniform("shine", s.shine);
                    sh.setFloatUniform("hair", s.hair * density);
                    sh.setFloatUniform("mixA", 1f);
                    sh.setFloatUniform("xform", 0f, 0f, 1f, 1f);
                    sh.setFloatUniform("rect", 0f, 0f, 1f, 1f);
                    paint.setShader(sh);
                } catch (RuntimeException e) {
                    Log.w(TAG, "glass surface unavailable", e);
                    sh = null;
                }
            }
            shader = sh;
            liftAnim.setInterpolator(new DecelerateInterpolator(2f));
            liftAnim.addUpdateListener(a -> setLift((float) a.getAnimatedValue()));
        }

        /** Animates to lit (1) or clear (0); ms 0 sets it at once. */
        void lift(boolean on, long ms) {
            float to = on ? 1f : 0f;
            liftAnim.cancel();
            if (ms <= 0) {
                setLift(to);
                return;
            }
            liftAnim.setFloatValues(lift, to);
            liftAnim.setDuration(ms);
            liftAnim.start();
        }

        private void setLift(float l) {
            if (l == lift) return;
            lift = l;
            if (shader != null) shader.setFloatUniform("lift", l);
            invalidateSelf();
        }

        /** Points the shader at what is behind the surface now; re-records only on a change. */
        void sync(Matrix washInv, Shader a, Shader b, float mix) {
            if (shader == null || !host.isAttachedToWindow() || host.getWidth() == 0) return;
            m.reset();
            host.transformMatrixToGlobal(m);
            m.postConcat(washInv);
            m.getValues(v);
            float tx = v[Matrix.MTRANS_X], ty = v[Matrix.MTRANS_Y], sx = v[Matrix.MSCALE_X], sy = v[Matrix.MSCALE_Y];
            boolean changed = !synced || a != childA || b != childB || mix != last[0]
                    || tx != last[1] || ty != last[2] || sx != last[3] || sy != last[4];
            if (!changed) return;
            if (a != childA) shader.setInputShader("washA", a);
            if (b != childB) shader.setInputShader("washB", b);
            childA = a;
            childB = b;
            shader.setFloatUniform("mixA", mix);
            shader.setFloatUniform("xform", tx, ty, sx, sy);
            last[0] = mix;
            last[1] = tx;
            last[2] = ty;
            last[3] = sx;
            last[4] = sy;
            synced = true;
            invalidateSelf();
        }

        @Override protected void onBoundsChange(Rect b) {
            if (shader != null) shader.setFloatUniform("rect", b.left, b.top, Math.max(1, b.width()), Math.max(1, b.height()));
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            if (b.isEmpty() || (s.hideIdle && lift <= 0f)) return;
            // Hardware bitmaps can't be read by a software canvas (and nothing is synced yet
            // before the first pre-draw): flat fill then.
            if (shader != null && synced && c.isHardwareAccelerated()) {
                c.drawRect(b, paint);
                return;
            }
            r.set(b);
            float rad = s.radius * density;
            if (!s.hideIdle) {
                flat.setColor(s.fallback);
                c.drawRoundRect(r, rad, rad, flat);
            }
            if (lift > 0f) {
                flat.setColor(0xF2FFFFFF);
                flat.setAlpha(Math.round(0xF2 * lift));
                c.drawRoundRect(r, rad, rad, flat);
            }
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @SuppressWarnings("deprecation") // abstract in Drawable, so it must still be implemented
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
