package com.sonar.gateway.websocket;

import com.google.gson.Gson;
import com.sonar.gateway.flow.SlidingWindowFlowController;
import com.sonar.gateway.model.SonarFrame;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class WebSocketFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameHandler.class);

    private static final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private static final Gson gson = new Gson();
    private static volatile boolean broadcasterStarted = false;
    private static volatile boolean running = true;

    private static final int SLIDING_WINDOW_SIZE = 64;
    private static final int MAX_STALE_CHANNEL_MS = 30_000;

    private static final SlidingWindowFlowController flowController =
            new SlidingWindowFlowController(SLIDING_WINDOW_SIZE);

    private static final AtomicLong totalBroadcastFrames = new AtomicLong(0);
    private static final AtomicLong totalBroadcastBytes = new AtomicLong(0);
    private static final AtomicLong totalSkippedFrames = new AtomicLong(0);

    private final BlockingQueue<SonarFrame> frameQueue;

    private static final ChannelFutureListener WRITE_ACK_LISTENER = new ChannelFutureListener() {
        @Override
        public void operationComplete(ChannelFuture future) {
            Channel channel = future.channel();
            if (future.isSuccess()) {
                flowController.onAck(channel);
            } else {
                flowController.onSendFailed(channel);
                flowController.onDrop(channel);
                logger.warn("Write failed for channel {}: {}",
                        channel.id().asShortText(),
                        future.cause() != null ? future.cause().getMessage() : "unknown");
            }
        }
    };

    public WebSocketFrameHandler(BlockingQueue<SonarFrame> frameQueue) {
        this.frameQueue = frameQueue;
        if (!broadcasterStarted) {
            synchronized (WebSocketFrameHandler.class) {
                if (!broadcasterStarted) {
                    broadcasterStarted = true;

                    Thread broadcaster = new Thread(this::broadcastLoop);
                    broadcaster.setDaemon(true);
                    broadcaster.setName("sonar-broadcaster");
                    broadcaster.start();

                    Thread statsThread = new Thread(this::statsLoop);
                    statsThread.setDaemon(true);
                    statsThread.setName("sonar-stats");
                    statsThread.start();

                    Thread staleDetector = new Thread(this::staleChannelDetector);
                    staleDetector.setDaemon(true);
                    staleDetector.setName("stale-channel-detector");
                    staleDetector.start();
                }
            }
        }
    }

    private void broadcastLoop() {
        while (running) {
            try {
                SonarFrame frame = frameQueue.take();

                if (channels.isEmpty()) {
                    continue;
                }

                String json;
                try {
                    json = gson.toJson(frame);
                } catch (Exception e) {
                    logger.error("Failed to serialize SonarFrame", e);
                    continue;
                }

                int frameSizeBytes = json.length() * 2;
                totalBroadcastFrames.incrementAndGet();
                totalBroadcastBytes.addAndGet(frameSizeBytes);

                broadcastWithBackpressure(json);

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Broadcast loop error", e);
            }
        }
    }

    private void broadcastWithBackpressure(String json) {
        List<Channel> writableChannels = new ArrayList<>();
        List<Channel> congestedChannels = new ArrayList<>();

        for (Channel channel : channels) {
            if (!channel.isActive()) {
                continue;
            }

            if (flowController.canSend(channel)) {
                writableChannels.add(channel);
            } else {
                congestedChannels.add(channel);
            }
        }

        if (!congestedChannels.isEmpty()) {
            totalSkippedFrames.incrementAndGet();
            if (totalSkippedFrames.get() % 100 == 0) {
                logger.warn("Backpressure: {} congested channels skipped, writable={}, total frames skipped={}",
                        congestedChannels.size(), writableChannels.size(), totalSkippedFrames.get());
            }

            for (Channel channel : congestedChannels) {
                flowController.onDrop(channel);
            }
        }

        for (Channel channel : writableChannels) {
            TextWebSocketFrame wsFrame = null;
            try {
                wsFrame = new TextWebSocketFrame(json);
                flowController.onSend(channel);

                ChannelFuture writeFuture = channel.writeAndFlush(wsFrame);
                writeFuture.addListener(WRITE_ACK_LISTENER);
                wsFrame = null;
            } catch (Exception e) {
                if (wsFrame != null) {
                    ReferenceCountUtil.release(wsFrame);
                }
                flowController.onSendFailed(channel);
                flowController.onDrop(channel);
                logger.error("Failed to write frame to channel {}", channel.id().asShortText(), e);
            }
        }
    }

    private void statsLoop() {
        while (running) {
            try {
                Thread.sleep(10000);

                SlidingWindowFlowController.FlowControlStats stats = flowController.getStats();
                logger.info("FlowControl Stats: {}, broadcast={}/{}KB, skipped={}",
                        stats.toString(),
                        totalBroadcastFrames.get(),
                        totalBroadcastBytes.get() / 1024,
                        totalSkippedFrames.get());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void staleChannelDetector() {
        while (running) {
            try {
                Thread.sleep(5000);

                long now = System.currentTimeMillis();
                Iterator<Channel> it = channels.iterator();
                while (it.hasNext()) {
                    Channel ch = it.next();
                    SlidingWindowFlowController.WindowState state = flowController.getOrCreateWindowState(ch);
                    long staleMs = now - state.lastAckTime;

                    if (staleMs > MAX_STALE_CHANNEL_MS && state.getInFlightCount() > 0) {
                        logger.warn("Stale channel {} detected: no ACK for {}ms, in-flight={}. Closing.",
                                ch.id().asShortText(), staleMs, state.getInFlightCount());
                        ch.close();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Stale detector error", e);
            }
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channels.add(ctx.channel());
        flowController.getOrCreateWindowState(ctx.channel());
        logger.info("WebSocket client connected: {}, total: {}", ctx.channel().remoteAddress(), channels.size());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        flowController.removeChannel(ctx.channel());
        channels.remove(ctx.channel());
        logger.info("WebSocket client disconnected: {}, total: {}", ctx.channel().remoteAddress(), channels.size());
    }

    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) {
        flowController.onChannelWritabilityChanged(ctx.channel());
        if (ctx.channel().isWritable()) {
            ctx.channel().config().setAutoRead(true);
        } else {
            ctx.channel().config().setAutoRead(false);
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket handler error for channel {}", ctx.channel().id().asShortText(), cause);
        flowController.removeChannel(ctx.channel());
        ctx.close();
    }

    public static void shutdown() {
        running = false;
    }

    public static SlidingWindowFlowController getFlowController() {
        return flowController;
    }
}
