package com.sonar.gateway.model;

import java.util.List;

public class SonarFrame {
    private long timestamp;
    private int pingNumber;
    private double heading;
    private List<DepthPoint> points;

    public SonarFrame() {
    }

    public SonarFrame(long timestamp, int pingNumber, double heading, List<DepthPoint> points) {
        this.timestamp = timestamp;
        this.pingNumber = pingNumber;
        this.heading = heading;
        this.points = points;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public int getPingNumber() {
        return pingNumber;
    }

    public void setPingNumber(int pingNumber) {
        this.pingNumber = pingNumber;
    }

    public double getHeading() {
        return heading;
    }

    public void setHeading(double heading) {
        this.heading = heading;
    }

    public List<DepthPoint> getPoints() {
        return points;
    }

    public void setPoints(List<DepthPoint> points) {
        this.points = points;
    }
}
