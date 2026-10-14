package com.mousebird.maply;

import android.content.Context;

public class MapController extends MaplyBaseController {
    public GlobeMapFragment gestureDelegate;

    public void init(Context context) {
        this.mapView = new MapView(context, this);
    }

    public void addLayer(QuadImageTileLayer layer) {}

    public void setPositionGeo(double lonRad, double latRad, double height) {
        this.centerLon = lonRad * (180.0 / Math.PI);
        this.centerLat = latRad * (180.0 / Math.PI);
        if (mapView != null) {
            mapView.postInvalidate();
        }
    }

    public void currentMapZoom(Point2d pt) {}

    public void setZoomLimits(double min, double max) {}
}
