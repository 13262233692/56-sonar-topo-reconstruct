package com.sonar.gateway;

import com.sonar.gateway.flow.SlidingWindowFlowController;
import com.sonar.gateway.model.SonarFrame;
import com.sonar.gateway.monitor.DirectMemoryMonitor;
import com.sonar.gateway.simulator.SonarSimulator;
import com.sonar.gateway.udp.UdpServer;
import com.sonar.gateway.websocket.WebSocketFrameHandler;
import com.sonar.gateway.websocket.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class SonarGatewayApplication {

    private static final Logger logger = LoggerFactory.getLogger(SonarGatewayApplication.class);

    private static final int UDP_PORT = 6000;
    private static final int WS_PORT = 8080;
    private static final int QUEUE_CAPACITY = 256;

    public static void main(String[] args) {
        boolean simulate = false;
        for (String arg : args) {
            if ("--simulate".equals(arg)) {
                simulate = true;
            }
        }

        long maxDirectMem = DirectMemoryMonitor.estimateMaxDirectMemory();
        logger.info("JVM Max Direct Memory: {}MB, Max Heap: {}MB",
                maxDirectMem / 1024 / 1024,
                Runtime.getRuntime().maxMemory() / 1024 / 1024);

        if (maxDirectMem < 64 * 1024 * 1024) {
            logger.warn("Direct memory limit {}MB is too low! Recommended: -XX:MaxDirectMemorySize=256m",
                    maxDirectMem / 1024 / 1024);
        }

        DirectMemoryMonitor memoryMonitor = new DirectMemoryMonitor(maxDirectMem, 10);
        memoryMonitor.start();

        BlockingQueue<SonarFrame> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        WebSocketServer wsServer = new WebSocketServer(WS_PORT, frameQueue);
        UdpServer udpServer = new UdpServer(UDP_PORT, frameQueue);
        SonarSimulator simulator = new SonarSimulator(frameQueue);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down sonar gateway...");
            if (simulate) simulator.stop();
            WebSocketFrameHandler.shutdown();
            udpServer.stop();
            wsServer.stop();
            memoryMonitor.stop();
        }));

        try {
            wsServer.start();
            logger.info("WebSocket server started on port {}", WS_PORT);
        } catch (InterruptedException e) {
            logger.error("Failed to start WebSocket server", e);
            Thread.currentThread().interrupt();
            return;
        }

        if (simulate) {
            simulator.start();
            logger.info("Sonar simulator started (no real hardware needed)");
        } else {
            try {
                udpServer.start();
                logger.info("UDP server started on port {}", UDP_PORT);
            } catch (InterruptedException e) {
                logger.error("Failed to start UDP server", e);
                Thread.currentThread().interrupt();
                wsServer.stop();
                return;
            }
        }

        logger.info("Sonar Gateway is running - UDP:{}, WS:{}{}", UDP_PORT, WS_PORT,
                simulate ? " [SIMULATION MODE]" : "");
        logger.info("Flow control: sliding window={}, queueCapacity={}",
                64, QUEUE_CAPACITY);

        Thread monitorThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(30000);

                    Runtime runtime = Runtime.getRuntime();
                    long heapUsed = (runtime.totalMemory() - runtime.freeMemory()) / 1024 / 1024;
                    long heapMax = runtime.maxMemory() / 1024 / 1024;
                    long directUsed = memoryMonitor.getLastDirectMemoryBytes() / 1024 / 1024;
                    long directPeak = memoryMonitor.getPeakDirectMemoryBytes() / 1024 / 1024;

                    SlidingWindowFlowController flowCtrl = WebSocketFrameHandler.getFlowController();
                    SlidingWindowFlowController.FlowControlStats stats = flowCtrl != null ? flowCtrl.getStats() : null;

                    logger.info("System Monitor: heap={}/{}MB, direct={}/{}MB(peak={}), queue={}/{}, flow={}",
                            heapUsed, heapMax, directUsed, maxDirectMem / 1024 / 1024, directPeak,
                            frameQueue.size(), QUEUE_CAPACITY,
                            stats != null ? stats.toString() : "N/A");

                    double heapUsage = (double) heapUsed / heapMax;
                    if (heapUsage > 0.85) {
                        logger.warn("Heap usage {}% is critically high! Consider increasing -Xmx or reducing load",
                                (int) (heapUsage * 100));
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        monitorThread.setDaemon(true);
        monitorThread.setName("system-monitor");
        monitorThread.start();

        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception", e));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.info("Main thread interrupted, shutting down");
            Thread.currentThread().interrupt();
        }
    }
}
