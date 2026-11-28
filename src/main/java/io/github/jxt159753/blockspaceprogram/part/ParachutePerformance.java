package io.github.jxt159753.blockspaceprogram.part;

/** Drag areas in square meters, deployment speed in meters per second, height in meters, and time in seconds. */
public record ParachutePerformance(double semiDragArea, double dragArea, double minRelativeDensity,
        double maxDeploySpeed, double fullDeployHeight, double openingTime) {}
