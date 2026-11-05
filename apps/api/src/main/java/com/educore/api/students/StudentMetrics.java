package com.educore.api.students;

public record StudentMetrics(
        long activeCount,
        long pendingPlacementCount,
        long placedCount,
        long portalReadyCount) {
}
