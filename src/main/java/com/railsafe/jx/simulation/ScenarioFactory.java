package com.railsafe.jx.simulation;

import com.railsafe.jx.model.Signal;
import com.railsafe.jx.model.TrackSegment;
import com.railsafe.jx.model.Train;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ScenarioFactory {
    private ScenarioFactory() { }

    public record ScenarioData(Map<String, Train> trains, Map<String, TrackSegment> tracks,
                               Map<String, Signal> signals, List<String[]> connections) { }

    public static ScenarioData create(ScenarioType type) {
        Map<String, TrackSegment> tracks = new LinkedHashMap<>();
        for (String id : List.of("A", "B", "C", "D", "E", "F", "G", "S1", "S2", "S3")) {
            String label = id.startsWith("S") ? "Safety Siding " + id.substring(1) : "Track " + id;
            tracks.put(id, new TrackSegment(id, label, true));
        }
        Map<String, Signal> signals = new LinkedHashMap<>();
        tracks.keySet().forEach(id -> signals.put("SIG-" + id, new Signal("SIG-" + id, id)));

        Map<String, Train> trains = new LinkedHashMap<>();
        if (type == ScenarioType.DEADLOCK_DEMO) {
            trains.put("T1", new Train("T1", "Coastal Express", 3,
                    List.of("A", "B", "C"), List.of(List.of("A", "C")), "S1"));
            trains.put("T2", new Train("T2", "City Connector", 1,
                    List.of("B", "A", "D"), List.of(List.of("B", "D")), "S2"));
            trains.put("T3", new Train("T3", "Harbour Local", 2,
                    List.of("E", "F", "G"), List.of(List.of("E", "G")), "S3"));
        } else {
            trains.put("T1", new Train("T1", "Coastal Express", 3,
                    List.of("A", "B", "C"), List.of(List.of("A", "D", "C")), "S1"));
            trains.put("T2", new Train("T2", "City Connector", 2,
                    List.of("D", "E", "F"), List.of(List.of("D", "B", "F")), "S2"));
            trains.put("T3", new Train("T3", "Harbour Local", 1,
                    List.of("B", "E", "G"), List.of(List.of("B", "C", "G")), "S3"));
        }
        List<String[]> connections = new ArrayList<>();
        connections.add(new String[]{"A", "B"});
        connections.add(new String[]{"B", "C"});
        connections.add(new String[]{"D", "E"});
        connections.add(new String[]{"E", "F"});
        connections.add(new String[]{"F", "G"});
        connections.add(new String[]{"B", "E"});
        connections.add(new String[]{"A", "D"});
        connections.add(new String[]{"C", "F"});
        connections.add(new String[]{"A", "S1"});
        connections.add(new String[]{"D", "S2"});
        connections.add(new String[]{"E", "S3"});
        return new ScenarioData(trains, tracks, signals, connections);
    }
}
