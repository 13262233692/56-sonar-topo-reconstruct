package com.sonar.gateway.simulator;

import com.sonar.gateway.model.DepthPoint;
import com.sonar.gateway.model.SonarFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;

public class SonarSimulator {

    private static final Logger logger = LoggerFactory.getLogger(SonarSimulator.class);

    private final BlockingQueue<SonarFrame> frameQueue;
    private volatile boolean running = false;
    private Thread simThread;

    private int pingCounter = 0;
    private int numBeams = 128;
    private double maxSwathAngle = 75.0;
    private double baseDepth = 250.0;
    private double soundVelocity = 1500.0;

    public SonarSimulator(BlockingQueue<SonarFrame> frameQueue) {
        this.frameQueue = frameQueue;
    }

    public void start() {
        if (running) return;
        running = true;
        simThread = new Thread(this::simulateLoop);
        simThread.setDaemon(true);
        simThread.setName("sonar-simulator");
        simThread.start();
        logger.info("Sonar simulator started");
    }

    public void stop() {
        running = false;
        if (simThread != null) {
            simThread.interrupt();
        }
    }

    private void simulateLoop() {
        while (running) {
            try {
                SonarFrame frame = generateFrame();
                frameQueue.offer(frame);
                pingCounter++;
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private SonarFrame generateFrame() {
        List<DepthPoint> points = new ArrayList<>(numBeams);
        long timestamp = System.currentTimeMillis();

        double shipDriftX = Math.sin(pingCounter * 0.02) * 50;
        double shipDriftY = pingCounter * 0.5;

        for (int i = 0; i < numBeams; i++) {
            double beamAngle = -maxSwathAngle + (2 * maxSwathAngle * i / (numBeams - 1));
            double beamAngleRad = Math.toRadians(beamAngle);

            double terrainVariation = 0;
            terrainVariation += 30 * Math.sin(beamAngleRad * 3 + pingCounter * 0.05);
            terrainVariation += 15 * Math.sin(beamAngleRad * 7 + pingCounter * 0.03);
            terrainVariation += 8 * Math.cos(beamAngleRad * 13 + pingCounter * 0.07);
            terrainVariation += 5 * Math.sin(beamAngleRad * 21 + pingCounter * 0.11);

            double seamount = 0;
            double distFromCenter = Math.abs(beamAngle);
            if (distFromCenter < 30) {
                seamount = 80 * Math.exp(-distFromCenter * distFromCenter / 200) * Math.sin(pingCounter * 0.04);
            }

            double depth = baseDepth + terrainVariation + seamount;
            depth = Math.max(10, depth);

            double range = depth / Math.cos(beamAngleRad);
            double acrossTrack = range * Math.sin(beamAngleRad);

            double x = acrossTrack + shipDriftX;
            double y = shipDriftY;
            double z = depth;

            double intensity = -30 + 20 * Math.cos(beamAngleRad) + (Math.random() - 0.5) * 5;

            points.add(new DepthPoint(x, y, z, intensity, i, timestamp));
        }

        SonarFrame frame = new SonarFrame();
        frame.setTimestamp(timestamp);
        frame.setPingNumber(pingCounter);
        frame.setHeading((pingCounter * 0.1) % 360);
        frame.setPoints(points);
        return frame;
    }
}
