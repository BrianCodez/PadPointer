package com.briancodez.padpointer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

final class CursorView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF imageBounds = new RectF();
    private String style = "arrow";
    private int color = 0xFF7BE0C3;
    private float imageHotspotX, imageHotspotY;
    private long imageRevision = -1;
    private Bitmap image;
    private boolean typing;
    private boolean showHotspot;
    CursorView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
    void setTyping(boolean value) { typing = value; invalidate(); }
    void setShowHotspot(boolean value) { showHotspot = value; invalidate(); }
    void applyAppearance(Prefs prefs) {
        style = prefs.cursorStyle(); color = prefs.cursorColor();
        imageHotspotX = prefs.imageHotspotX(); imageHotspotY = prefs.imageHotspotY();
        long revision = prefs.data.getLong("imageRevision", 0);
        if (revision != imageRevision) {
            image = BitmapFactory.decodeFile(CursorImage.file(getContext()).getAbsolutePath());
            imageRevision = revision;
        }
        invalidate();
    }
    private boolean customImage() { return style.equals("image") && image != null; }
    float hotspotX() {
        if (customImage()) return imageHotspotX * 32f * image.getWidth() / Math.max(image.getWidth(), image.getHeight());
        return style.equals("dot") || style.equals("crosshair") ? 16f : 1f;
    }
    float hotspotY() {
        if (customImage()) return imageHotspotY * 32f * image.getHeight() / Math.max(image.getWidth(), image.getHeight());
        return style.equals("dot") || style.equals("crosshair") ? 16f : 1f;
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 32f, getHeight() / 32f);
        if (customImage()) {
            float scale = 32f / Math.max(image.getWidth(), image.getHeight());
            imageBounds.set(0, 0, image.getWidth() * scale, image.getHeight() * scale);
            paint.setStyle(Paint.Style.FILL); paint.setFilterBitmap(true);
            canvas.drawBitmap(image, null, imageBounds, paint);
        } else {
            path.rewind();
            switch (style) {
                case "compact" -> {
                    path.moveTo(1, 1); path.lineTo(12, 29); path.lineTo(17, 17); path.lineTo(29, 12); path.close();
                }
                case "dot" -> path.addCircle(16, 16, 8, Path.Direction.CW);
                case "crosshair" -> {
                    path.addCircle(16, 16, 6, Path.Direction.CW);
                    path.moveTo(16, 1); path.lineTo(16, 10); path.moveTo(16, 22); path.lineTo(16, 31);
                    path.moveTo(1, 16); path.lineTo(10, 16); path.moveTo(22, 16); path.lineTo(31, 16);
                }
                default -> {
                    path.moveTo(1, 1); path.lineTo(1, 25); path.lineTo(8, 19);
                    path.lineTo(14, 31); path.lineTo(19, 28); path.lineTo(13, 17); path.lineTo(25, 17); path.close();
                }
            }
            boolean crosshair = style.equals("crosshair");
            int outline = Color.luminance(color) < 0.15f ? Color.WHITE : Color.BLACK;
            paint.setColor(outline); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(crosshair ? 4 : 2.5f); canvas.drawPath(path, paint);
            paint.setColor(color); paint.setStyle(crosshair ? Paint.Style.STROKE : Paint.Style.FILL); paint.setStrokeWidth(2); canvas.drawPath(path, paint);
        }
        if (typing) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFFFC879); canvas.drawCircle(28, 28, 3, paint);
        }
        if (showHotspot && customImage()) {
            float hx = hotspotX(), hy = hotspotY();
            paint.setStyle(Paint.Style.STROKE); paint.setColor(Color.BLACK); paint.setStrokeWidth(3);
            canvas.drawCircle(hx, hy, 3, paint);
            paint.setColor(0xFFFFC879); paint.setStrokeWidth(1);
            canvas.drawCircle(hx, hy, 3, paint);
            canvas.drawLine(hx - 5, hy, hx + 5, hy, paint);
            canvas.drawLine(hx, hy - 5, hx, hy + 5, paint);
        }
        canvas.restore();
    }
}
