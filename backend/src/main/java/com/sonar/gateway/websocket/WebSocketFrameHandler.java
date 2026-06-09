package com.sonar.gateway.websocket;

import com.google.gson.Gson;
import com.sonar.gateway.model.SonarFrame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

public class WebSocketFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameHandler.class);

    private static final ChannelGroup channels = new DefaultChannelGroup(GlobalEventExecutor.INSTANCE);
    private static final Gson gson = new Gson();
    private static volatile boolean broadcasterStarted = false;
    private static volatile boolean running = true;

    private final BlockingQueue<SonarFrame> frameQueue;

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
                String json = gson.toJson(frame);
                TextWebSocketFrame wsFrame = new TextWebSocketFrame(json);
                channels.writeAndFlush(wsFrame);
                logger.debug("Broadcast frame to {} clients: ping={}", channels.size(), frame.getPingNumber());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Broadcast error", e);
            }
        }
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        channels.add(ctx.channel());
        logger.info("WebSocket client connected: {}, total: {}", ctx.channel().remoteAddress(), channels.size());
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) {
        channels.remove(ctx.channel());
        logger.info("WebSocket client disconnected: {}, total: {}", ctx.channel().remoteAddress(), channels.size());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame msg) {
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("WebSocket handler error", cause);
        ctx.close();
    }

    public static void shutdown() {
        running = false;
    }
}
