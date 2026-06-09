package com.sonar.gateway.converter;

import com.sonar.gateway.model.DepthPoint;

public class CoordinateConverter {

    public static DepthPoint convert(
            double travelTimeSeconds,
            double soundVelocity,
            double tiltAngleDegrees,
            double reflectivityDb,
            int beamIndex,
            long timestamp,
            double shipOffsetX,
            double shipOffsetY,
            double heave,
            double pitch,
            double roll
    ) {
        double range = travelTimeSeconds * soundVelocity / 2.0;

        double tiltAngleRad = Math.toRadians(tiltAngleDegrees);
        double pitchRad = Math.toRadians(pitch);
        double rollRad = Math.toRadians(roll);

        double acrossTrack = range * Math.sin(tiltAngleRad);
        double depth = range * Math.cos(tiltAngleRad);

        double correctedAcrossTrack = acrossTrack * Math.cos(rollRad) + depth * Math.sin(rollRad);
        double correctedDepth = -acrossTrack * Math.sin(rollRad) + depth * Math.cos(rollRad);

        double alongTrackOffset = correctedDepth * Math.sin(pitchRad);
        double correctedAlongTrack = alongTrackOffset;
        correctedDepth = correctedDepth * Math.cos(pitchRad);

        double x = correctedAcrossTrack + shipOffsetX;
        double y = correctedAlongTrack + shipOffsetY;
        double z = correctedDepth + heave;

        return new DepthPoint(x, y, z, reflectivityDb, beamIndex, timestamp);
    }
}
