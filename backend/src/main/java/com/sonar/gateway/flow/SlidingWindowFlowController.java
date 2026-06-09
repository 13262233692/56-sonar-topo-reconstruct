package com.sonar.gateway.flow;

import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class SlidingWindowFlowController {

    private static final Logger logger = LoggerFactory.getLogger(SlidingWindowFlowController.class);

    private final int windowSize;
    private final ConcurrentHashMap<Channel, WindowState> channelWindows = new ConcurrentHashMap<>();
    private final AtomicLong totalDroppedFrames = new AtomicLong(0);
    private final AtomicLong totalAckedFrames = new AtomicLong(0);

    public SlidingWindowFlowController(int windowSize) {
        this.windowSize = windowSize;
    }

    public static class WindowState {
        final AtomicInteger inFlightCount = new AtomicInteger(0);
        volatile long lastAckTime = System.currentTimeMillis();
        volatile boolean paused = false;
        final AtomicLong droppedCount = new AtomicLong(0);

        public int getInFlightCount() {
            return inFlightCount.get();
        }
    }

    public WindowState getOrCreateWindowState(Channel channel) {
        return channelWindows.computeIfAbsent(channel, ch -> new WindowState());
    }

    public boolean canSend(Channel channel) {
        WindowState state = getOrCreateWindowState(channel);

        if (!channel.isActive()) {
            return false;
        }

        if (!channel.isWritable()) {
            state.paused = true;
            return false;
        }

        if (state.inFlightCount.get() >= windowSize) {
            return false;
        }

        if (state.paused && channel.isWritable() && state.inFlightCount.get() < windowSize / 2) {
            state.paused = false;
        }

        return !state.paused;
    }

    public void onSend(Channel channel) {
        WindowState state = getOrCreateWindowState(channel);
        state.inFlightCount.incrementAndGet();
    }

    public void onAck(Channel channel) {
        WindowState state = getOrCreateWindowState(channel);
        int newCount = state.inFlightCount.decrementAndGet();
        state.lastAckTime = System.currentTimeMillis();
        totalAckedFrames.incrementAndGet();

        if (newCount < 0) {
            state.inFlightCount.set(0);
        }
    }

    public void onSendFailed(Channel channel) {
        WindowState state = getOrCreateWindowState(channel);
        state.inFlightCount.decrementAndGet();
    }

    public void onDrop(Channel channel) {
        totalDroppedFrames.incrementAndGet();

        WindowState state = getOrCreateWindowState(channel);
        state.droppedCount.incrementAndGet();

        if (state.droppedCount.get() % 100 == 0) {
            logger.warn("Channel {} dropped {} frames total, in-flight={}",
                    channel.id().asShortText(), state.droppedCount.get(),
                    state.inFlightCount.get());
        }
    }

    public void onChannelWritabilityChanged(Channel channel) {
        WindowState state = getOrCreateWindowState(channel);
        if (channel.isWritable()) {
            state.paused = false;
            logger.debug("Channel {} became writable, resuming flow - in-flight={}",
                    channel.id().asShortText(), state.inFlightCount.get());
        } else {
            state.paused = true;
            logger.warn("Channel {} NOT writable, pausing flow - in-flight={}",
                    channel.id().asShortText(), state.inFlightCount.get());
        }
    }

    public void removeChannel(Channel channel) {
        WindowState state = channelWindows.remove(channel);
        if (state != null && state.droppedCount.get() > 0) {
            logger.info("Channel {} removed - dropped {} frames during lifetime",
                    channel.id().asShortText(), state.droppedCount.get());
        }
    }

    public int getWindowSize() {
        return windowSize;
    }

    public FlowControlStats getStats() {
        FlowControlStats stats = new FlowControlStats();
        stats.activeChannels = channelWindows.size();
        stats.totalDroppedFrames = totalDroppedFrames.get();
        stats.totalAckedFrames = totalAckedFrames.get();

        long totalInFlight = 0;
        for (WindowState state : channelWindows.values()) {
            totalInFlight += state.inFlightCount.get();
        }
        stats.totalInFlight = totalInFlight;

        return stats;
    }

    public static class FlowControlStats {
        public int activeChannels;
        public long totalDroppedFrames;
        public long totalAckedFrames;
        public long totalInFlight;

        @Override
        public String toString() {
            return String.format("FlowControl{channels=%d, inFlight=%d, dropped=%d, acked=%d}",
                    activeChannels, totalInFlight, totalDroppedFrames, totalAckedFrames);
        }
    }
}
