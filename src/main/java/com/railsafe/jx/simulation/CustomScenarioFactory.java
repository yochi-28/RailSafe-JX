package com.railsafe.jx.simulation;

import com.railsafe.jx.model.Signal;
import com.railsafe.jx.model.TrackSegment;
import com.railsafe.jx.model.Train;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Validates user-defined train routes and converts them into normal simulation model objects. */
public final class CustomScenarioFactory {
    private static final int MAX_TRAINS = 12;
    private static final int MAX_RESOURCES = 30;

    private CustomScenarioFactory() { }

    /** kind is TRACK or PLATFORM. IDs must already be validated by the UI or calling code. */
    public record ResourceDefinition(String id, String kind) {
        public ResourceDefinition {
            id = requireId(id, "resource id");
            kind = Objects.requireNonNull(kind, "kind").trim().toUpperCase();
            if (!kind.equals("TRACK") && !kind.equals("PLATFORM")) {
                throw new IllegalArgumentException("Resource kind must be TRACK or PLATFORM: " + kind);
            }
        }
    }

    public record TrainDefinition(String id, String name, int priority, List<String> route,
                                  List<List<String>> alternateRoutes) {
        public TrainDefinition {
            id = requireId(id, "train id");
            name = name == null || name.isBlank() ? id + " Custom Train" : name.trim();
            if (priority < 1 || priority > 99) throw new IllegalArgumentException("Priority must be between 1 and 99");
            if (route == null || route.isEmpty()) throw new IllegalArgumentException("Train " + id + " needs a non-empty route");
            route = List.copyOf(route.stream().map(value -> requireId(value, "route resource id")).toList());
            List<List<String>> copies = new ArrayList<>();
            if (alternateRoutes != null) {
                for (List<String> alternate : alternateRoutes) {
                    if (alternate != null && !alternate.isEmpty()) {
                        copies.add(List.copyOf(alternate.stream().map(value -> requireId(value, "alternate route resource id")).toList()));
                    }
                }
            }
            alternateRoutes = List.copyOf(copies);
        }
    }

    /**
     * Resources are exclusive segments. A private virtual safety siding is provisioned for each
     * train so recovery always has a distinct fallback resource. Physical connections are derived
     * from route sequences; resource-allocation graph edges are intentionally not used as routes.
     */
    public static ScenarioFactory.ScenarioData create(List<ResourceDefinition> resourceDefinitions,
                                                       List<TrainDefinition> trainDefinitions) {
        return create(resourceDefinitions, trainDefinitions, deriveConnections(trainDefinitions));
    }

    /** Builds a live scenario using the physical track/platform connections entered by the user. */
    public static ScenarioFactory.ScenarioData create(List<ResourceDefinition> resourceDefinitions,
                                                       List<TrainDefinition> trainDefinitions,
                                                       List<String[]> physicalConnections) {
        if (resourceDefinitions == null || resourceDefinitions.isEmpty()) {
            throw new IllegalArgumentException("Add at least one TRACK or PLATFORM node first");
        }
        if (resourceDefinitions.size() > MAX_RESOURCES) {
            throw new IllegalArgumentException("At most " + MAX_RESOURCES + " track/platform resources are supported");
        }
        if (trainDefinitions == null || trainDefinitions.isEmpty()) {
            throw new IllegalArgumentException("Add at least one TRAIN node and define its route first");
        }
        if (trainDefinitions.size() > MAX_TRAINS) {
            throw new IllegalArgumentException("At most " + MAX_TRAINS + " trains are supported in a custom simulation");
        }

        Map<String, ResourceDefinition> resourceById = new LinkedHashMap<>();
        for (ResourceDefinition resource : resourceDefinitions) {
            if (resourceById.putIfAbsent(resource.id(), resource) != null) {
                throw new IllegalArgumentException("Duplicate resource ID: " + resource.id());
            }
        }
        Map<String, TrainDefinition> trainById = new LinkedHashMap<>();
        for (TrainDefinition train : trainDefinitions) {
            if (trainById.putIfAbsent(train.id(), train) != null) {
                throw new IllegalArgumentException("Duplicate train ID: " + train.id());
            }
            validateRoute(train.route(), resourceById.keySet(), train.id(), "main route");
            for (List<String> alternate : train.alternateRoutes()) {
                validateRoute(alternate, resourceById.keySet(), train.id(), "alternate route");
                if (!alternate.getFirst().equals(train.route().getFirst())
                        || !alternate.getLast().equals(train.route().getLast())) {
                    throw new IllegalArgumentException("Alternate routes for " + train.id()
                            + " must start and finish at the same resources as its main route");
                }
            }
        }

        Set<String> uniqueConnections = new LinkedHashSet<>();
        List<String[]> connections = new ArrayList<>();
        if (physicalConnections != null) {
            for (String[] connection : physicalConnections) {
                if (connection == null || connection.length < 2) throw new IllegalArgumentException("A track connection needs two endpoint IDs");
                String from = requireId(connection[0], "connection endpoint");
                String to = requireId(connection[1], "connection endpoint");
                if (from.equals(to)) throw new IllegalArgumentException("A track connection cannot connect a resource to itself: " + from);
                if (!resourceById.containsKey(from) || !resourceById.containsKey(to)) {
                    throw new IllegalArgumentException("Track connection " + from + " — " + to + " must use existing TRACK/PLATFORM nodes");
                }
                addConnection(from, to, uniqueConnections, connections);
            }
        }
        for (TrainDefinition train : trainDefinitions) {
            validateRouteConnections(train.route(), uniqueConnections, train.id(), "main route");
            for (List<String> alternate : train.alternateRoutes()) {
                validateRouteConnections(alternate, uniqueConnections, train.id(), "alternate route");
            }
        }

        Map<String, TrackSegment> tracks = new LinkedHashMap<>();
        for (ResourceDefinition resource : resourceDefinitions) {
            String label = resource.kind().equals("PLATFORM") ? "Platform " + resource.id() : "Track " + resource.id();
            tracks.put(resource.id(), new TrackSegment(resource.id(), label, true));
        }

        Map<String, Train> trains = new LinkedHashMap<>();
        Set<String> occupiedIds = new LinkedHashSet<>(resourceById.keySet());
        for (TrainDefinition definition : trainDefinitions) {
            String sidingId = uniqueSidingId(definition.id(), occupiedIds);
            occupiedIds.add(sidingId);
            tracks.put(sidingId, new TrackSegment(sidingId, "Safety Siding for " + definition.id(), true));
            trains.put(definition.id(), new Train(definition.id(), definition.name(), definition.priority(),
                    definition.route(), definition.alternateRoutes(), sidingId));
        }

        Map<String, Signal> signals = new LinkedHashMap<>();
        tracks.keySet().forEach(id -> signals.put("SIG-" + id, new Signal("SIG-" + id, id)));
        for (TrainDefinition train : trainDefinitions) {
            addConnection(train.route().getFirst(), trains.get(train.id()).getSafeSidingId(), uniqueConnections, connections);
        }
        return new ScenarioFactory.ScenarioData(trains, tracks, signals, connections);
    }

    private static void validateRoute(List<String> route, Set<String> resources, String trainId, String label) {
        for (String resource : route) {
            if (!resources.contains(resource)) {
                throw new IllegalArgumentException("Train " + trainId + " " + label
                        + " refers to missing TRACK/PLATFORM node: " + resource);
            }
        }
    }

    private static List<String[]> deriveConnections(List<TrainDefinition> trainDefinitions) {
        List<String[]> result = new ArrayList<>();
        Set<String> unique = new LinkedHashSet<>();
        if (trainDefinitions != null) for (TrainDefinition train : trainDefinitions) {
            addConnections(train.route(), unique, result);
            for (List<String> alternate : train.alternateRoutes()) addConnections(alternate, unique, result);
        }
        return result;
    }

    private static void addConnections(List<String> route, Set<String> unique, List<String[]> result) {
        for (int i = 0; i + 1 < route.size(); i++) addConnection(route.get(i), route.get(i + 1), unique, result);
    }

    private static void validateRouteConnections(List<String> route, Set<String> connectionKeys,
                                                 String trainId, String routeLabel) {
        for (int i = 0; i + 1 < route.size(); i++) {
            String from = route.get(i), to = route.get(i + 1);
            String key = connectionKey(from, to);
            if (!connectionKeys.contains(key)) {
                throw new IllegalArgumentException("Train " + trainId + " " + routeLabel + " uses "
                        + from + " → " + to + " but no physical connection exists. Add that track/platform connection first.");
            }
        }
    }

    private static void addConnection(String left, String right, Set<String> unique, List<String[]> result) {
        if (unique.add(connectionKey(left, right))) result.add(new String[]{left, right});
    }

    private static String connectionKey(String left, String right) {
        String first = left.compareTo(right) <= 0 ? left : right;
        String second = left.compareTo(right) <= 0 ? right : left;
        return first + "\u0000" + second;
    }

    private static String uniqueSidingId(String trainId, Set<String> reserved) {
        String base = "SIDING_" + trainId.replaceAll("[^A-Za-z0-9_-]", "_");
        String candidate = base;
        int suffix = 2;
        while (reserved.contains(candidate)) candidate = base + "_" + suffix++;
        return candidate;
    }

    private static String requireId(String id, String label) {
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]{0,19}")) {
            throw new IllegalArgumentException("Invalid " + label + ": " + id
                    + ". Use 1-20 letters, digits, underscore or hyphen, starting with a letter.");
        }
        return id;
    }
}
