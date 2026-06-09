package com.sonar.gateway;

import com.sonar.gateway.model.SonarFrame;
import com.sonar.gateway.simulator.SonarSimulator;
import com.sonar.gateway.udp.UdpServer;
import com.sonar.gateway.websocket.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class SonarGatewayApplication {

    private static final Logger logger = LoggerFactory.getLogger(SonarGatewayApplication.class);

    private static final int UDP_PORT = 6000;
    private static final int WS_PORT = 8080;
    private static final int QUEUE_CAPACITY = 1024;

    public static void main(String[] args) {
        boolean simulate = false;
        for (String arg : args) {
            if ("--simulate".equals(arg)) {
                simulate = true;
            }
        }

        BlockingQueue<SonarFrame> frameQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);

        WebSocketServer wsServer = new WebSocketServer(WS_PORT, frameQueue);
        UdpServer udpServer = new UdpServer(UDP_PORT, frameQueue);
        SonarSimulator simulator = new SonarSimulator(frameQueue);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Shutting down sonar gateway...");
            if (simulate) simulator.stop();
            udpServer.stop();
            wsServer.stop();
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

        Thread.currentThread().setUncaughtExceptionHandler((t, e) -> logger.error("Uncaught exception", e));

        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            logger.info("Main thread interrupted, shutting down");
            Thread.currentThread().interrupt();
        }
    }
}
