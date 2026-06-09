package com.sonar.gateway.websocket;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import com.sonar.gateway.model.SonarFrame;

public class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServerInitializer.class);

    private static final String WEBSOCKET_PATH = "/ws";
    private static final int WRITE_BUFFER_LOW_WATER_MARK = 32 * 1024;
    private static final int WRITE_BUFFER_HIGH_WATER_MARK = 64 * 1024;
    private static final int READER_IDLE_SECONDS = 120;
    private static final int WRITER_IDLE_SECONDS = 60;
    private static final int ALL_IDLE_SECONDS = 180;

    private final BlockingQueue<SonarFrame> frameQueue;

    public WebSocketServerInitializer(BlockingQueue<SonarFrame> frameQueue) {
        this.frameQueue = frameQueue;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.config().setWriteBufferWaterMark(new io.netty.channel.WriteBufferWaterMark(
                WRITE_BUFFER_LOW_WATER_MARK, WRITE_BUFFER_HIGH_WATER_MARK));

        ch.config().setAutoRead(true);
        ch.config().setRecvByteBufAllocator(new io.netty.channel.AdaptiveRecvByteBufAllocator(64, 2048, 65536));

        ChannelPipeline pipeline = ch.pipeline();

        pipeline.addLast("idleState", new IdleStateHandler(READER_IDLE_SECONDS, WRITER_IDLE_SECONDS, ALL_IDLE_SECONDS, TimeUnit.SECONDS));

        pipeline.addLast("httpCodec", new HttpServerCodec());
        pipeline.addLast("httpAggregator", new HttpObjectAggregator(65536));
        pipeline.addLast("wsProtocol", new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, 65536));
        pipeline.addLast("wsFrameHandler", new WebSocketFrameHandler(frameQueue));

        pipeline.addLast("idleHandler", new io.netty.channel.ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(io.netty.channel.ChannelHandlerContext ctx, Object evt) {
                if (evt instanceof IdleStateEvent) {
                    IdleStateEvent e = (IdleStateEvent) evt;
                    logger.warn("Channel {} idle event: {}, closing connection",
                            ctx.channel().id().asShortText(), e.state());
                    ctx.close();
                } else {
                    ctx.fireUserEventTriggered(evt);
                }
            }
        });
    }
}
