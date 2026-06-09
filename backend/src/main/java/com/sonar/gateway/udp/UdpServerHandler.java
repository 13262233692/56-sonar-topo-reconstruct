package com.sonar.gateway.udp;

import com.sonar.gateway.model.DepthPoint;
import com.sonar.gateway.model.SonarFrame;
import com.sonar.gateway.parser.KongsbergRawParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class UdpServerHandler extends SimpleChannelInboundHandler<DatagramPacket> {

    private static final Logger logger = LoggerFactory.getLogger(UdpServerHandler.class);

    private final BlockingQueue<SonarFrame> frameQueue;
    private final KongsbergRawParser parser;
    private final AtomicLong droppedFrames = new AtomicLong(0);
    private final AtomicLong processedFrames = new AtomicLong(0);

    public UdpServerHandler(BlockingQueue<SonarFrame> frameQueue) {
        this.frameQueue = frameQueue;
        this.parser = new KongsbergRawParser();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
        ByteBuf buf = packet.content();
        byte[] data = null;

        try {
            int length = buf.readableBytes();
            if (length < 16) {
                return;
            }

            data = new byte[length];
            buf.getBytes(buf.readerIndex(), data);

            List<DepthPoint> points = parser.parse(data);

            if (points.isEmpty()) {
                return;
            }

            SonarFrame frame = new SonarFrame();
            frame.setTimestamp(System.currentTimeMillis());
            frame.setPingNumber(extractPingCounter(data));
            frame.setHeading(0.0);
            frame.setPoints(points);

            if (!frameQueue.offer(frame)) {
                long dropped = droppedFrames.incrementAndGet();
                if (dropped % 1000 == 0) {
                    logger.warn("UDP frame queue full! Dropped {} frames total, queue size={}",
                            dropped, frameQueue.size());
                }
            } else {
                long processed = processedFrames.incrementAndGet();
                if (processed % 10000 == 0) {
                    logger.info("UDP processed {} frames, queue size={}, dropped={}",
                            processed, frameQueue.size(), dropped.get());
                }
            }
        } catch (Exception e) {
            logger.error("Error processing UDP datagram", e);
        }
    }

    private int extractPingCounter(byte[] data) {
        if (data == null || data.length < 7) {
            return 0;
        }
        return (data[5] & 0xFF) | ((data[6] & 0xFF) << 8);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("UDP handler error", cause);
    }
}
