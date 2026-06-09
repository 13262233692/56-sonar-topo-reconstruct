package com.sonar.gateway.websocket;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

import java.util.concurrent.BlockingQueue;

import com.sonar.gateway.model.SonarFrame;

public class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final String WEBSOCKET_PATH = "/ws";

    private final BlockingQueue<SonarFrame> frameQueue;

    public WebSocketServerInitializer(BlockingQueue<SonarFrame> frameQueue) {
        this.frameQueue = frameQueue;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(65536));
        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true, 65536));
        pipeline.addLast(new WebSocketFrameHandler(frameQueue));
    }
}
