package com.sonar.gateway.monitor;

import io.netty.util.internal.PlatformDependent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class DirectMemoryMonitor {

    private static final Logger logger = LoggerFactory.getLogger(DirectMemoryMonitor.class);

    private final long warnThresholdBytes;
    private final long criticalThresholdBytes;
    private final int monitorIntervalSeconds;
    private final AtomicLong lastDirectMemoryBytes = new AtomicLong(0);
    private final AtomicLong peakDirectMemoryBytes = new AtomicLong(0);
    private final AtomicLong leakWarningCount = new AtomicLong(0);
    private ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    public DirectMemoryMonitor(long maxDirectMemoryBytes, int monitorIntervalSeconds) {
        this.warnThresholdBytes = (long) (maxDirectMemoryBytes * 0.7);
        this.criticalThresholdBytes = (long) (maxDirectMemoryBytes * 0.9);
        this.monitorIntervalSeconds = monitorIntervalSeconds;
    }

    public void start() {
        if (running) return;
        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "direct-mem-monitor");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleAtFixedRate(this::check, monitorIntervalSeconds, monitorIntervalSeconds, TimeUnit.SECONDS);
        logger.info("Direct memory monitor started - warn={}MB, critical={}MB, interval={}s",
                warnThresholdBytes / 1024 / 1024,
                criticalThresholdBytes / 1024 / 1024,
                monitorIntervalSeconds);
    }

    public void stop() {
        running = false;
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void check() {
        try {
            long directUsed = getDirectMemoryUsage();
            lastDirectMemoryBytes.set(directUsed);

            if (directUsed > peakDirectMemoryBytes.get()) {
                peakDirectMemoryBytes.set(directUsed);
            }

            long heapUsed = getHeapMemoryUsage();

            if (directUsed >= criticalThresholdBytes) {
                leakWarningCount.incrementAndGet();
                logger.error("CRITICAL: Direct memory usage {}MB exceeds critical threshold {}MB! " +
                                "Peak={}MB, Heap={}MB, LeakWarnings={}. " +
                                "Possible ByteBuf leak - check ReferenceCountUtil.release() calls!",
                        directUsed / 1024 / 1024,
                        criticalThresholdBytes / 1024 / 1024,
                        peakDirectMemoryBytes.get() / 1024 / 1024,
                        heapUsed / 1024 / 1024,
                        leakWarningCount.get());
            } else if (directUsed >= warnThresholdBytes) {
                logger.warn("Direct memory usage {}MB exceeds warn threshold {}MB. " +
                                "Peak={}MB, Heap={}MB",
                        directUsed / 1024 / 1024,
                        warnThresholdBytes / 1024 / 1024,
                        peakDirectMemoryBytes.get() / 1024 / 1024,
                        heapUsed / 1024 / 1024);
            } else {
                logger.debug("Direct memory: {}MB, Heap: {}MB, Peak: {}MB",
                        directUsed / 1024 / 1024,
                        heapUsed / 1024 / 1024,
                        peakDirectMemoryBytes.get() / 1024 / 1024);
            }
        } catch (Exception e) {
            logger.error("Direct memory monitor error", e);
        }
    }

    private long getDirectMemoryUsage() {
        try {
            long directUsed = PlatformDependent.usedDirectMemory();
            if (directUsed >= 0) {
                return directUsed;
            }
        } catch (Throwable ignored) {
        }

        try {
            MemoryMXBean memoryMxBean = ManagementFactory.getMemoryMXBean();
            for (java.lang.management.MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
                if (pool.getName().contains("Direct")) {
                    MemoryUsage usage = pool.getUsage();
                    if (usage != null) {
                        return usage.getUsed();
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        return nonHeap != null ? nonHeap.getUsed() : 0;
    }

    private long getHeapMemoryUsage() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        return heap != null ? heap.getUsed() : 0;
    }

    public long getLastDirectMemoryBytes() {
        return lastDirectMemoryBytes.get();
    }

    public long getPeakDirectMemoryBytes() {
        return peakDirectMemoryBytes.get();
    }

    public static long estimateMaxDirectMemory() {
        try {
            return PlatformDependent.maxDirectMemory();
        } catch (Throwable ignored) {
        }

        try {
            String jvmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments().toString();
            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.startsWith("-XX:MaxDirectMemorySize=")) {
                    String value = arg.substring("-XX:MaxDirectMemorySize=".length());
                    if (value.endsWith("g") || value.endsWith("G")) {
                        return Long.parseLong(value.substring(0, value.length() - 1)) * 1024 * 1024 * 1024;
                    } else if (value.endsWith("m") || value.endsWith("M")) {
                        return Long.parseLong(value.substring(0, value.length() - 1)) * 1024 * 1024;
                    } else if (value.endsWith("k") || value.endsWith("K")) {
                        return Long.parseLong(value.substring(0, value.length() - 1)) * 1024;
                    }
                    return Long.parseLong(value);
                }
            }
        } catch (Throwable ignored) {
        }

        Runtime runtime = Runtime.getRuntime();
        return runtime.maxMemory();
    }
}
