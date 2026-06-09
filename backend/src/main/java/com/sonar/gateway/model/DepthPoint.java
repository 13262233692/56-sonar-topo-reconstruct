package com.sonar.gateway.model;

public class DepthPoint {
    private double x;
    private double y;
    private double z;
    private double intensity;
    private int beamIndex;
    private long timestamp;

    public DepthPoint() {
    }

    public DepthPoint(double x, double y, double z, double intensity, int beamIndex, long timestamp) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.intensity = intensity;
        this.beamIndex = beamIndex;
        this.timestamp = timestamp;
    }

    public double getX() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double getY() {
        return y;
    }

    public void setY(double y) {
        this.y = y;
    }

    public double getZ() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    public double getIntensity() {
        return intensity;
    }

    public void setIntensity(double intensity) {
        this.intensity = intensity;
    }

    public int getBeamIndex() {
        return beamIndex;
    }

    public void setBeamIndex(int beamIndex) {
        this.beamIndex = beamIndex;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }
}
