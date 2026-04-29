package com.github.ma1co.pmcademo.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.view.View;

public class DiptychOverlayView extends View {
    private Paint linePaint;
    private Paint thumbPaint;
    private Paint darkPaint;
    private Paint framePaint;
    private Bitmap thumbnail;
    private boolean thumbOnLeft = true;
    private boolean doubleExposureMode = false;
    private int state = DiptychManager.STATE_NEED_FIRST;

    public DiptychOverlayView(Context context) {
        super(context);

        linePaint = new Paint();
        linePaint.setColor(Color.WHITE);
        linePaint.setStrokeWidth(2);

        thumbPaint = new Paint();
        thumbPaint.setAlpha(255);

        darkPaint = new Paint();
        darkPaint.setColor(Color.BLACK);
        darkPaint.setAlpha(180);

        framePaint = new Paint();
        framePaint.setColor(Color.WHITE);
        framePaint.setStyle(Paint.Style.STROKE);
        framePaint.setStrokeWidth(3);
        framePaint.setAntiAlias(false);
    }

    public void setState(int state) {
        this.state = state;
        if (state == DiptychManager.STATE_NEED_FIRST) {
            clearThumbnail();
            thumbOnLeft = true;
        }
        invalidate();
    }

    public void clearThumbnail() {
        Bitmap oldThumb = this.thumbnail;
        this.thumbnail = null; // Sever UI link immediately
        if (oldThumb != null && !oldThumb.isRecycled()) {
            oldThumb.recycle();
        }
        invalidate();
    }

    public void setThumbnail(Bitmap thumb) {
        Bitmap oldThumb = this.thumbnail;
        this.thumbnail = thumb;
        if (oldThumb != null && !oldThumb.isRecycled()) {
            oldThumb.recycle();
        }
        invalidate();
    }

    public void setThumbOnLeft(boolean onLeft) {
        this.thumbOnLeft = onLeft;
        invalidate();
    }

    public void setDoubleExposureMode(boolean enabled) {
        this.doubleExposureMode = enabled;
        invalidate();
    }

    public boolean isThumbOnLeft() {
        return thumbOnLeft;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int mid = w / 2;

        if (state == DiptychManager.STATE_NEED_FIRST) {
            int quarter = w / 4;
            int mg = Math.max(8, w / 32);
            int bl = h / 10;

            if (!doubleExposureMode) {
                darkPaint.setAlpha(220);
                canvas.drawRect(0, 0, quarter, h, darkPaint);
                canvas.drawRect(w - quarter, 0, w, h, darkPaint);
                darkPaint.setAlpha(180);
            }

            int left = doubleExposureMode ? mg : quarter + mg;
            int right = doubleExposureMode ? w - mg : mid + quarter - mg;
            canvas.drawLine(left, mg, left + bl, mg, framePaint);
            canvas.drawLine(left, mg, left, mg + bl, framePaint);
            canvas.drawLine(right, mg, right - bl, mg, framePaint);
            canvas.drawLine(right, mg, right, mg + bl, framePaint);
            canvas.drawLine(left, h - mg, left + bl, h - mg, framePaint);
            canvas.drawLine(left, h - mg, left, h - mg - bl, framePaint);
            canvas.drawLine(right, h - mg, right - bl, h - mg, framePaint);
            canvas.drawLine(right, h - mg, right, h - mg - bl, framePaint);
        } else if (state == DiptychManager.STATE_NEED_SECOND || state == DiptychManager.STATE_STITCHING) {
            if (!doubleExposureMode) {
                if (thumbOnLeft) {
                    canvas.drawRect(0, 0, mid, h, darkPaint);
                } else {
                    canvas.drawRect(mid, 0, w, h, darkPaint);
                }
            }

            if (thumbnail != null && !thumbnail.isRecycled()) {
                int tW = thumbnail.getWidth();
                int tH = thumbnail.getHeight();

                Rect srcRect = new Rect(0, 0, tW, tH);
                Rect dstRect = doubleExposureMode
                        ? new Rect(0, 0, w, h)
                        : (thumbOnLeft ? new Rect(0, 0, mid, h) : new Rect(mid, 0, w, h));
                thumbPaint.setAlpha(doubleExposureMode ? 128 : 255);
                canvas.drawBitmap(thumbnail, srcRect, dstRect, thumbPaint);
                thumbPaint.setAlpha(255);
            }

            if (state == DiptychManager.STATE_STITCHING) {
                darkPaint.setAlpha(120);
                canvas.drawRect(0, 0, w, h, darkPaint);
                darkPaint.setAlpha(180);
            }
        }

        if (!doubleExposureMode && state != DiptychManager.STATE_NEED_FIRST) {
            canvas.drawLine(mid, 0, mid, h, linePaint);
        }
    }
}
