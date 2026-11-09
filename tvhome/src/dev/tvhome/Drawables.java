package dev.tvhome;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.animation.DecelerateInterpolator;

/** Code-drawn decorations for the home screen, so the app needs no image assets. */
final class Drawables {
    private Drawables() { }

    /** Glass highlight for the focused tile: a soft top gloss plus one diagonal sweep on focus. */
    static final class Shine extends Drawable {
        private final Paint gloss = new Paint(), band = new Paint();
        private final Matrix shift = new Matrix();
        private final ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        private LinearGradient bandShader;
        private float pos = -1f;

        Shine() {
            anim.setDuration(750);
            anim.setInterpolator(new DecelerateInterpolator(2f));
            anim.addUpdateListener(a -> { pos = (float) a.getAnimatedValue(); invalidateSelf(); });
            anim.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator a) { pos = -1f; invalidateSelf(); }
            });
        }

        void sweep() {
            anim.cancel();
            anim.start();
        }

        @Override protected void onBoundsChange(Rect b) {
            int h = b.height(), bw = Math.round(h * 0.9f);
            gloss.setShader(new LinearGradient(0, 0, 0, h * 0.55f, 0x2EFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
            bandShader = new LinearGradient(0, 0, bw, bw, new int[] {0x00FFFFFF, 0x55FFFFFF, 0x00FFFFFF},
                    new float[] {0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
            band.setShader(bandShader);
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            c.drawRect(b.left, b.top, b.right, b.top + b.height() * 0.55f, gloss);
            if (pos < 0 || bandShader == null) return;
            float span = b.width() + b.height() * 1.8f;
            shift.setTranslate(b.left - b.height() * 1.8f + pos * span, b.top);
            bandShader.setLocalMatrix(shift);
            c.drawRect(b, band);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @SuppressWarnings("deprecation") // abstract in Drawable, so it must still be implemented
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Soft glow around a shortcut pill; hidden until faded in through setAlpha on focus. */
    static final class PillGlow extends Drawable {
        // Full-strength glow: white at ~31% opacity (the 0x50 alpha byte).
        private static final int COLOR = 0x50FFFFFF;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private final float radius;

        PillGlow(float blur, float radius) {
            this.radius = radius;
            p.setColor(COLOR);
            // OUTER: the blur spreads only outside the pill, so the pill's own fill stays crisp.
            p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.OUTER));
            p.setAlpha(0);
        }

        @Override protected void onBoundsChange(Rect b) { r.set(b); }

        @Override public void draw(Canvas c) { c.drawRoundRect(r, radius, radius, p); }

        @Override public void setAlpha(int alpha) {
            p.setAlpha(alpha * Color.alpha(COLOR) / 255);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter cf) { }
        @SuppressWarnings("deprecation") // abstract in Drawable, so it must still be implemented
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Small line icons for the shortcut pills, drawn with paths instead of image assets. */
    static final class Glyph extends Drawable {
        static final int WIFI = 0, PAD = 1, TV = 2, MOON = 3;
        private final int type;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        boolean off;

        Glyph(int type) {
            this.type = type;
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
        }

        void setColor(int c) {
            p.setColor(c);
            invalidateSelf();
        }

        @Override public void draw(Canvas c) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()), cx = b.exactCenterX(), cy = b.exactCenterY();
            int color = p.getColor();
            if (off) p.setAlpha(120);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(s * 0.1f);
            switch (type) {
                case WIFI: {
                    float by = cy + s * 0.3f;
                    for (int i = 1; i <= 3; i++) {
                        float rad = s * 0.2f * i;
                        r.set(cx - rad, by - rad, cx + rad, by + rad);
                        c.drawArc(r, 225, 90, false, p);
                    }
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx, by, s * 0.07f, p);
                    if (off) {
                        p.setStyle(Paint.Style.STROKE);
                        p.setAlpha(255);
                        c.drawLine(cx - s * 0.42f, cy - s * 0.42f, cx + s * 0.42f, cy + s * 0.42f, p);
                    }
                    break;
                }
                case PAD: {
                    r.set(cx - s * 0.48f, cy - s * 0.26f, cx + s * 0.48f, cy + s * 0.3f);
                    c.drawRoundRect(r, s * 0.24f, s * 0.24f, p);
                    float dx = cx - s * 0.22f, dy = cy + s * 0.02f, arm = s * 0.1f;
                    c.drawLine(dx - arm, dy, dx + arm, dy, p);
                    c.drawLine(dx, dy - arm, dx, dy + arm, p);
                    p.setStyle(Paint.Style.FILL);
                    c.drawCircle(cx + s * 0.18f, cy - s * 0.04f, s * 0.06f, p);
                    c.drawCircle(cx + s * 0.3f, cy + s * 0.08f, s * 0.06f, p);
                    break;
                }
                case MOON: {
                    Path moon = new Path();
                    moon.addCircle(cx, cy, s * 0.4f, Path.Direction.CW);
                    Path bite = new Path();
                    bite.addCircle(cx + s * 0.22f, cy - s * 0.18f, s * 0.32f, Path.Direction.CW);
                    moon.op(bite, Path.Op.DIFFERENCE);
                    p.setStyle(Paint.Style.FILL);
                    c.drawPath(moon, p);
                    break;
                }
                default: {
                    r.set(cx - s * 0.46f, cy - s * 0.34f, cx + s * 0.46f, cy + s * 0.2f);
                    c.drawRoundRect(r, s * 0.07f, s * 0.07f, p);
                    c.drawLine(cx - s * 0.2f, cy + s * 0.38f, cx + s * 0.2f, cy + s * 0.38f, p);
                    break;
                }
            }
            p.setColor(color);
        }

        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter cf) { }
        @SuppressWarnings("deprecation") // abstract in Drawable, so it must still be implemented
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
