package dev.errorintelligence.ingestion;

import java.util.UUID;

/** Published by ingestion within its transaction; consumers decide how to react. */
public record ErrorEventStored(UUID eventId) {}
