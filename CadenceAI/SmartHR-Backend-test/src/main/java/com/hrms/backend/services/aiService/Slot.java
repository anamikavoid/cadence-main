package com.hrms.backend.services.aiService;

import java.time.Instant;

/** A free time window [start, end). */
public record Slot(Instant start, Instant end) {
}
