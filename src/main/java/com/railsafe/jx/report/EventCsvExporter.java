package com.railsafe.jx.report;

import com.railsafe.jx.util.SimulationEvent;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/** Exports a stable snapshot of simulation events as UTF-8 CSV for audit and project analysis. */
public final class EventCsvExporter {
    private static final String HEADER = "timestamp,event_type,train_id,message";

    private EventCsvExporter() { }

    /**
     * Write every event in the supplied snapshot to {@code destination}.
     * Values are escaped according to RFC 4180: fields containing commas, quotes, or line breaks
     * are quoted and embedded quotes are doubled.
     */
    public static void export(Path destination, List<SimulationEvent> events) throws IOException {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(events, "events");
        Path absolute = destination.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);

        try (BufferedWriter writer = Files.newBufferedWriter(absolute, StandardCharsets.UTF_8)) {
            writer.write(HEADER);
            writer.newLine();
            for (SimulationEvent event : events) {
                Objects.requireNonNull(event, "events cannot contain null entries");
                writer.write(csv(event.timestamp().toString()));
                writer.write(',');
                writer.write(csv(event.type()));
                writer.write(',');
                writer.write(csv(event.trainId()));
                writer.write(',');
                writer.write(csv(event.message()));
                writer.newLine();
            }
        }
    }

    private static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') < 0 && safe.indexOf('"') < 0
                && safe.indexOf('\n') < 0 && safe.indexOf('\r') < 0) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }
}
