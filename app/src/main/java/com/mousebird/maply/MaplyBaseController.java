package com.mousebird.maply;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class MaplyBaseController {
    public enum ThreadMode {
        ThreadCurrent,
        ThreadAny
    }

    protected final List<ComponentObject> markers = new CopyOnWriteArrayList<>();
    protected MapView mapView;
    protected double centerLon = 0;
    protected double centerLat = 0;

    public View getContentView() {
        return mapView;
    }

    public ComponentObject addScreenMarker(ScreenMarker marker, MarkerInfo info, ThreadMode threadMode) {
        ComponentObject compObj = new ComponentObject(marker);
        markers.add(compObj);
        if (mapView != null) {
            mapView.postInvalidate();
        }
        return compObj;
    }

    public void removeObject(ComponentObject compObj, ThreadMode threadMode) {
        if (compObj != null) {
            markers.remove(compObj);
            if (mapView != null) {
                mapView.postInvalidate();
            }
        }
    }

    public static class MapView extends View {
        private final MaplyBaseController controller;
        private final Paint backgroundPaint = new Paint();
        private final Paint gridPaint = new Paint();

        public MapView(Context context, MaplyBaseController controller) {
            super(context);
            this.controller = controller;
            backgroundPaint.setColor(Color.parseColor("#1a1a2e"));
            gridPaint.setColor(Color.parseColor("#16213e"));
            gridPaint.setStrokeWidth(2);
            gridPaint.setStyle(Paint.Style.STROKE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();

            canvas.drawRect(0, 0, width, height, backgroundPaint);

            int cx = width / 2;
            int cy = height / 2;
            int maxR = Math.min(cx, cy);

            canvas.drawCircle(cx, cy, maxR * 0.3f, gridPaint);
            canvas.drawCircle(cx, cy, maxR * 0.6f, gridPaint);
            canvas.drawCircle(cx, cy, maxR * 0.9f, gridPaint);
            canvas.drawLine(cx, 0, cx, height, gridPaint);
            canvas.drawLine(0, cy, width, cy, gridPaint);

            double cLon = controller.centerLon;
            double cLat = controller.centerLat;

            for (ComponentObject obj : controller.markers) {
                ScreenMarker marker = obj.getMarker();
                if (marker != null && marker.loc != null && marker.image != null) {
                    float x = cx;
                    float y = cy;

                    if (cLon != 0 || cLat != 0) {
                        double deltaLon = marker.loc.x - cLon;
                        double deltaLat = marker.loc.y - cLat;
                        double scale = maxR * 2000.0;
                        x = (float) (cx + deltaLon * scale);
                        y = (float) (cy - deltaLat * scale);
                    }

                    float w = marker.size != null ? (float) marker.size.x : marker.image.getWidth();
                    float h = marker.size != null ? (float) marker.size.y : marker.image.getHeight();

                    canvas.drawBitmap(marker.image, null, new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), null);
                }
            }
        }
    }
}
