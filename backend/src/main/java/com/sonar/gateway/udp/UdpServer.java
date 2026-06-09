package com.sonar.gateway.udp;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;

import com.sonar.gateway.model.SonarFrame;

public class UdpServer {

    private static final Logger logger = LoggerFactory.getLogger(UdpServer.class);

    private final int port;
    private final BlockingQueue<SonarFrame> frameQueue;
    private EventLoopGroup group;

    public UdpServer(int port, BlockingQueue<SonarFrame> frameQueue) {
        this.port = port;
        this.frameQueue = frameQueue;
    }

    public void start() throws InterruptedException {
        group = new NioEventLoopGroup();

        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioDatagramChannel.class)
                .option(ChannelOption.SO_BROADCAST, true)
                .option(ChannelOption.SO_RCVBUF, 1024 * 1024 * 8)
                .option(ChannelOption.SO_SNDBUF, 1024 * 1024 * 8)
                .handler(new UdpServerHandler(frameQueue));

        bootstrap.bind(port).sync();
        logger.info("UDP server started on port {}", port);
    }

    public void stop() {
        if (group != null) {
            group.shutdownGracefully();
            logger.info("UDP server stopped");
        }
    }
}
