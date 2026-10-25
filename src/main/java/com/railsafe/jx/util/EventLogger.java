package com.railsafe.jx.util;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Thread-safe in-memory event journal with optional persistence sink. */
public final class EventLogger implements AutoCloseable {
    private static final int MAX_EVENTS = 2000;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());
    private final CopyOnWriteArrayList<SimulationEvent> events = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<SimulationEvent>> listeners = new CopyOnWriteArrayList<>();
    private volatile EventSink sink;

    public void setSink(EventSink sink) { this.sink = sink; }
    public void addListener(Consumer<SimulationEvent> listener) { listeners.add(listener); }
    public void removeListener(Consumer<SimulationEvent> listener) { listeners.remove(listener); }

    public void startRun(String scenarioName) {
        EventSink current = sink;
        if (current != null) current.startRun(scenarioName);
    }

    public void finishRun(String outcome) {
        EventSink current = sink;
        if (current != null) current.finishRun(outcome);
    }

    public void log(String type, String trainId, String message) {
        SimulationEvent event = new SimulationEvent(Instant.now(), type, trainId, message);
        events.add(event);
        if (events.size() > MAX_EVENTS) events.remove(0);
        System.out.printf("[%s] %-20s %-8s %s%n", FORMATTER.format(event.timestamp()), event.type(), event.trainId(), event.message());
        EventSink current = sink;
        // Database diagnostics must not be sent back into the same failing database sink.
        if (current != null && !"DATABASE".equals(type)) current.accept(event);
        for (Consumer<SimulationEvent> listener : listeners) {
            try { listener.accept(event); } catch (RuntimeException ex) { ex.printStackTrace(System.err); }
        }
    }

    public List<SimulationEvent> getRecentEvents(int limit) {
        List<SimulationEvent> snapshot = List.copyOf(events);
        int from = Math.max(0, snapshot.size() - Math.max(0, limit));
        return List.copyOf(snapshot.subList(from, snapshot.size()));
    }

    @Override public void close() {
        EventSink current = sink;
        if (current != null) current.close();
    }
}
