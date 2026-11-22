package com.iiordanov.bVNC;

import android.graphics.Matrix;
import android.widget.ImageView;
import com.undatech.remoteClientUi.R;

/** Native-pixel rendering with a top-left origin shared by drawing and input. */
final class PixelPerfectScaling extends AbstractScaling {
    private float zoom = 1f;
    private final Matrix matrix = new Matrix();

    PixelPerfectScaling() { super(R.id.itemOneToOne, ImageView.ScaleType.MATRIX); }
    @Override int getDefaultHandlerId() { return R.id.itemInputTouchPanZoomMouse; }
    @Override public boolean isAbleToPan() { return true; }
    @Override boolean isValidInputMode(int mode) { return true; }
    @Override public float getZoomFactor() { return zoom; }
    @Override void setScaleTypeForActivity(RemoteCanvasActivity activity) {
        super.setScaleTypeForActivity(activity);
        RemoteCanvas canvas = activity.getCanvas();
        canvas.shiftX = canvas.shiftY = 0;
        canvas.setImageMatrix(matrix);
        canvas.resetScroll();
    }

    @Override public void changeZoom(RemoteCanvasActivity activity, float factor, float fx, float fy) {
        float x = fx - activity.getxPointerOffset(), y = fy - activity.getyPointerOffset();
        transform(activity.getCanvas(), factor, x, y, x, y);
    }

    void transform(RemoteCanvas canvas, float factor, float oldX, float oldY, float x, float y) {
        int width = canvas.getImageWidth(), height = canvas.getImageHeight();
        if (width <= 0 || height <= 0 || canvas.getWidth() <= 0 || canvas.getHeight() <= 0) return;
        float minimum = Math.min(1f, Math.min((float)canvas.getWidth() / width, (float)canvas.getHeight() / height));
        float anchorX = canvas.absoluteXPosition + oldX / zoom;
        float anchorY = canvas.absoluteYPosition + oldY / zoom;
        zoom = Math.max(minimum, Math.min(4f, zoom * factor));
        matrix.setScale(zoom, zoom);
        canvas.setImageMatrix(matrix);
        canvas.absoluteXPosition = RdpViewport.clampOffset(Math.round(anchorX - x / zoom),
                width, canvas.getVisibleDesktopWidth());
        // Reclaim any empty area left by a dismissed keyboard, even at minimum zoom.
        canvas.absoluteYPosition = RdpViewport.clampOffset(Math.round(anchorY - y / zoom),
                height, canvas.getVisibleDesktopHeight());
        canvas.userPanned = canvas.absoluteXPosition != 0 || canvas.absoluteYPosition != 0 || zoom > minimum;
        canvas.resetScroll();
        canvas.invalidate();
    }
}
