package com.gathin.tvhome;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;

/**
 * Liquid glass for the top-bar pills: a translucent white veil, a soft sheen down the pill and a crisp
 * rim, so the wallpaper behind reads through it the way the focus highlight and the dock tray do.
 *
 * The theme's glass_style only reaches the moving highlight; the pills keep their flat drawable, which
 * on a bright wallpaper looks like a cut-out. This draws the same material at the pill's own radius.
 */
final class PillGlassDrawable extends Drawable {

    private final View owner;
    private final Paint veil = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private Shader sheenShader;

    PillGlassDrawable(View owner) {
        this.owner = owner;
        this.density = owner.getResources().getDisplayMetrics().density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        rect.set(bounds);
        float height = Math.max(1, bounds.height());
        // Light from above: a faint white sheen that fades to nothing at the bottom edge.
        sheenShader = new LinearGradient(0, bounds.top, 0, bounds.top + height,
                0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP);
        sheen.setShader(sheenShader);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        Rect bounds = getBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float radius = bounds.height() / 2f;
        boolean focused = owner.isFocused();

        veil.setColor(focused ? 0x30FFFFFF : 0x1CFFFFFF);
        canvas.drawRoundRect(rect, radius, radius, veil);
        if (sheenShader != null) canvas.drawRoundRect(rect, radius, radius, sheen);
        rim.setColor(focused ? 0xE6FFFFFF : 0x59FFFFFF);
        canvas.drawRoundRect(rect, radius, radius, rim);
    }

    @Override
    public boolean isStateful() {
        return true;
    }

    @Override
    protected boolean onStateChange(int[] state) {
        invalidateSelf();
        return true;
    }

    @Override
    public void setAlpha(int alpha) {
        // The colours above are already translucent; the veil is the only layer that would need it.
        veil.setAlpha(alpha);
        sheen.setAlpha(alpha);
        rim.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        veil.setColorFilter(colorFilter);
        sheen.setColorFilter(colorFilter);
        rim.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
