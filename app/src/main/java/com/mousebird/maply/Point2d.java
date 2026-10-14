package com.mousebird.maply;

public class Point2d {
    public double x;
    public double y;

    public Point2d() {
        this.x = 0;
        this.y = 0;
    }

    public Point2d(double x, double y) {
        this.x = x;
        this.y = y;
    }

    public static Point2d FromDegrees(double lon, double lat) {
        return new Point2d(lon, lat);
    }
}
