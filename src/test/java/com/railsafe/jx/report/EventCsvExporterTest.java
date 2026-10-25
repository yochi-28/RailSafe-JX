package com.railsafe.jx.report;

import com.railsafe.jx.util.SimulationEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventCsvExporterTest {
    @TempDir Path tempDir;

    @Test
    void writesHeaderAndEventAsUtf8Csv() throws IOException {
        Path destination = tempDir.resolve("events.csv");
        SimulationEvent event = new SimulationEvent(Instant.parse("2026-10-25T06:30:00Z"),
                "TRAIN_MOVED", "T1", "Entered segment A");

        EventCsvExporter.export(destination, List.of(event));

        String output = Files.readString(destination, StandardCharsets.UTF_8);
        assertTrue(output.startsWith("timestamp,event_type,train_id,message" + System.lineSeparator()));
        assertTrue(output.contains("2026-10-25T06:30:00Z,TRAIN_MOVED,T1,Entered segment A"));
    }

    @Test
    void quotesCommasQuotesAndNewlinesCorrectly() throws IOException {
        Path destination = tempDir.resolve("escaped.csv");
        SimulationEvent event = new SimulationEvent(Instant.parse("2026-10-25T06:30:00Z"),
                "RECOVERY_ACTION", "T2", "Route, said \"alternate\"\nselected");

        EventCsvExporter.export(destination, List.of(event));

        String output = Files.readString(destination, StandardCharsets.UTF_8);
        assertTrue(output.contains("\"Route, said \"\"alternate\"\"\nselected\""));
    }

    @Test
    void emptySnapshotExportsHeaderOnly() throws IOException {
        Path destination = tempDir.resolve("empty.csv");
        EventCsvExporter.export(destination, List.of());
        assertEquals("timestamp,event_type,train_id,message" + System.lineSeparator(),
                Files.readString(destination, StandardCharsets.UTF_8));
    }
}
