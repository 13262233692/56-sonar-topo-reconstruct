package com.sonar.gateway.parser;

import com.sonar.gateway.converter.CoordinateConverter;
import com.sonar.gateway.model.DepthPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public class KongsbergRawParser {

    private static final Logger logger = LoggerFactory.getLogger(KongsbergRawParser.class);

    private static final int EM_START_IDENTIFIER = 0x3000;
    private static final byte DATAGRAM_TYPE_RAW = 0x52;

    private double shipOffsetX = 0.0;
    private double shipOffsetY = 0.0;
    private double heave = 0.0;
    private double pitch = 0.0;
    private double roll = 0.0;

    public List<DepthPoint> parse(byte[] data) {
        List<DepthPoint> points = new ArrayList<>();

        if (data == null || data.length < 16) {
            logger.warn("Datagram too short: {} bytes", data == null ? 0 : data.length);
            return points;
        }

        int startId = readUint16LE(data, 0);
        if (startId != EM_START_IDENTIFIER) {
            logger.warn("Invalid start identifier: 0x{}, expected 0x3000", Integer.toHexString(startId));
            return points;
        }

        byte datagramType = (byte) (data[2] & 0xFF);
        if (datagramType != DATAGRAM_TYPE_RAW) {
            logger.debug("Skipping non-RAW datagram type: 0x{}", Integer.toHexString(datagramType & 0xFF));
            return points;
        }

        int emModel = data[3] & 0xFF;
        int datagramVersion = data[4] & 0xFF;

        int pingCounter = readUint16LE(data, 5);
        int serialNumber = readUint16LE(data, 7);

        int soundVelocityRaw = readUint16LE(data, 9);
        double soundVelocity = soundVelocityRaw * 0.1;

        int sampleRateRaw = readUint16LE(data, 11);
        double sampleRate = sampleRateRaw;

        int numBeams = readUint16LE(data, 13);
        int numSamplesPerBeam = readUint16LE(data, 15);

        logger.debug("Parsing RAW datagram: model={}, version={}, ping={}, beams={}, soundVel={}",
                emModel, datagramVersion, pingCounter, numBeams, soundVelocity);

        int offset = 17;

        int beamDataSize = 6;
        int expectedSize = offset + numBeams * beamDataSize;

        if (data.length < expectedSize) {
            logger.warn("Datagram too short for {} beams: {} bytes, expected {}", numBeams, data.length, expectedSize);
            numBeams = (data.length - offset) / beamDataSize;
            if (numBeams <= 0) {
                return points;
            }
        }

        long timestamp = System.currentTimeMillis();

        for (int i = 0; i < numBeams; i++) {
            int baseOffset = offset + i * beamDataSize;

            int travelTimeRaw = readUint16LE(data, baseOffset);
            short tiltAngleRaw = readInt16LE(data, baseOffset + 2);
            short reflectivityRaw = readInt16LE(data, baseOffset + 4);

            double travelTimeSeconds = travelTimeRaw * 0.5e-6;
            double tiltAngleDegrees = tiltAngleRaw * 0.01;
            double reflectivityDb = reflectivityRaw * 0.1;

            DepthPoint point = CoordinateConverter.convert(
                    travelTimeSeconds,
                    soundVelocity,
                    tiltAngleDegrees,
                    reflectivityDb,
                    i,
                    timestamp,
                    shipOffsetX,
                    shipOffsetY,
                    heave,
                    pitch,
                    roll
            );

            points.add(point);
        }

        return points;
    }

    private int readUint16LE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
    }

    private short readInt16LE(byte[] buf, int offset) {
        int raw = (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
        if ((raw & 0x8000) != 0) {
            raw = raw | 0xFFFF0000;
        }
        return (short) raw;
    }

    private int readUint32LE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF)
                | ((buf[offset + 1] & 0xFF) << 8)
                | ((buf[offset + 2] & 0xFF) << 16)
                | ((buf[offset + 3] & 0xFF) << 24);
    }

    public void setShipOffsets(double x, double y) {
        this.shipOffsetX = x;
        this.shipOffsetY = y;
    }

    public void setAttitudeCorrections(double heave, double pitch, double roll) {
        this.heave = heave;
        this.pitch = pitch;
        this.roll = roll;
    }
}
