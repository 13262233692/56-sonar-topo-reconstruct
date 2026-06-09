package com.sonar.gateway.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

import com.sonar.gateway.model.SonarFrame;

public class WebSocketServer {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketServer.class);

    private final int port;
    private final BlockingQueue<SonarFrame> frameQueue;
    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;

    public WebSocketServer(int port, BlockingQueue<SonarFrame> frameQueue) {
        this.port = port;
        this.frameQueue = frameQueue;
    }

    public void start() throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new WebSocketServerInitializer(frameQueue));

        ChannelFuture future = bootstrap.bind(port).sync();
        logger.info("WebSocket server started on port {}", port);
    }

    public void stop() {
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        logger.info("WebSocket server stopped");
    }
}
