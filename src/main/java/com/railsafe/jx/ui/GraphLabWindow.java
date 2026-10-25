package com.railsafe.jx.ui;

import com.railsafe.jx.deadlock.DeadlockCycle;
import com.railsafe.jx.deadlock.DeadlockDetector;
import com.railsafe.jx.model.TrackSegment;
import com.railsafe.jx.recovery.RecoveryStrategy;
import com.railsafe.jx.simulation.CustomScenarioFactory;
import com.railsafe.jx.simulation.ScenarioFactory;
import com.railsafe.jx.simulation.SimulationManager;
import com.railsafe.jx.util.EventLogger;
import com.railsafe.jx.util.SimulationEvent;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** Graph editor, comparison examples, and live custom scenarios backed by the real simulation core. */
public final class GraphLabWindow {
    private enum Kind { TRAIN, TRACK, PLATFORM }
    private record NodeData(String id, Kind kind) { }
    private record Edge(String from, String to) { }
    private record RouteFormData(String name, int priority, List<String> route, List<List<String>> alternates) { }
    private record XY(double x, double y) { }

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final Map<String, NodeData> nodes = new LinkedHashMap<>();
    private final List<Edge> edges = new ArrayList<>();
    private final List<Edge> topologyConnections = new ArrayList<>();
    private final Map<String, RouteFormData> routeDefinitions = new LinkedHashMap<>();
    private final EventLogger eventLogger;
    private final boolean ownsEventLogger;
    private final DeadlockDetector detector = new DeadlockDetector();

    private Stage stage;
    private TabPane tabs;
    private Pane graphCanvas;
    private Label graphResult;
    private ListView<String> edgeList;
    private TextField nodeIdField;
    private ComboBox<String> nodeKindSelector;
    private ComboBox<String> edgeFromSelector;
    private ComboBox<String> edgeToSelector;
    private ComboBox<String> routeTrainSelector;
    private TextField trainNameField;
    private Spinner<Integer> prioritySpinner;
    private TextField routeField;
    private TextArea alternateRoutesField;
    private ListView<String> routeList;
    private ComboBox<String> topologyFromSelector;
    private ComboBox<String> topologyToSelector;
    private ListView<String> topologyList;
    private ComboBox<RecoveryStrategy> strategySelector;
    private Slider speedSlider;
    private Label liveStatus;
    private Label liveWaitStatus;
    private Pane liveNetworkCanvas;
    private Pane liveWaitGraphCanvas;
    private ListView<String> liveEventList;
    private Timeline refreshTimeline;
    private SimulationManager liveManager;
    private ScenarioFactory.ScenarioData currentCustomData;
    private String currentRunName = "Custom Railway Scenario";
    private boolean stageClosing;
    private Consumer<SimulationEvent> eventListener;

    /** Uses the application's logger, so custom runs also appear in the main event log and JDBC history. */
    public GraphLabWindow(EventLogger sharedEventLogger) {
        this.eventLogger = Objects.requireNonNull(sharedEventLogger, "sharedEventLogger");
        this.ownsEventLogger = false;
    }

    /** Standalone mode is useful for tests or opening the Graph Lab outside the main application. */
    public GraphLabWindow() {
        this.eventLogger = new EventLogger();
        this.ownsEventLogger = true;
    }

    public void show() {
        stage = new Stage();
        stage.setTitle("RailSafe-JX | Graph Lab & Custom Simulation Builder");

        BorderPane root = new BorderPane();
        root.setPadding(new Insets(14));
        Label heading = new Label("Graph Lab · Build, simulate, detect and recover");
        heading.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #17324d;");
        Label help = new Label("Compare two default graphs, edit a resource-allocation graph, or define train routes and run them through the same concurrent simulation, deadlock detector and recovery engine.");
        help.setWrapText(true);
        VBox header = new VBox(7, heading, help, buildPresetBar());
        header.setPadding(new Insets(0, 0, 12, 0));
        root.setTop(header);

        tabs = new TabPane();
        tabs.getTabs().addAll(buildExamplesTab(), buildGraphEditorTab(), buildLiveSimulationTab());
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        root.setCenter(tabs);

        Scene scene = new Scene(root, 1370, 850);
        stage.setScene(scene);
        stage.setMinWidth(1120);
        stage.setMinHeight(740);
        stage.setOnCloseRequest(event -> closeResources());
        stage.show();

        eventListener = event -> Platform.runLater(() -> appendLiveEvent(event));
        eventLogger.addListener(eventListener);
        refreshTimeline = new Timeline(new KeyFrame(Duration.millis(220), event -> refreshLiveViews()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE);
        refreshTimeline.play();

        // Open with a reproducible cycle in the editor; the comparison tab shows both examples at once.
        loadDeadlock();
        tabs.getSelectionModel().select(0);
    }

    private HBox buildPresetBar() {
        Button loadCycle = new Button("Load Deadlock Graph");
        loadCycle.setOnAction(event -> { loadDeadlock(); selectEditorTab(); });
        Button loadAcyclic = new Button("Load Deadlock-Free Graph");
        loadAcyclic.setOnAction(event -> { loadDeadlockFree(); selectEditorTab(); });
        Button clear = new Button("Clear Editor Graph");
        clear.setOnAction(event -> {
            if (confirmReplaceGraph()) {
                nodes.clear(); edges.clear(); topologyConnections.clear(); routeDefinitions.clear(); refreshGraphEditor();
            }
        });
        HBox row = new HBox(8, loadCycle, loadAcyclic, clear);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Tab buildExamplesTab() {
        Pane deadlockPreview = createExampleGraph(true);
        Pane safePreview = createExampleGraph(false);
        VBox deadlockCard = new VBox(8,
                headingLabel("Example A · Deadlock"),
                statusLabel("CYCLE DETECTED · T1 → B → T2 → A → T1", "#b42318"),
                deadlockPreview,
                wrapped("T1 holds A and requests B. T2 holds B and requests A. Neither can continue until recovery breaks the cycle."));
        VBox safeCard = new VBox(8,
                headingLabel("Example B · Deadlock-free"),
                statusLabel("NO DIRECTED CYCLE", "#16805d"),
                safePreview,
                wrapped("This dependency graph is a chain. DFS finds no directed cycle, so the graph model does not report a deadlock."));
        deadlockCard.setPadding(new Insets(14));
        safeCard.setPadding(new Insets(14));
        deadlockCard.setStyle(cardStyle());
        safeCard.setStyle(cardStyle());
        HBox comparison = new HBox(14, deadlockCard, safeCard);
        HBox.setHgrow(deadlockCard, Priority.ALWAYS);
        HBox.setHgrow(safeCard, Priority.ALWAYS);
        VBox body = new VBox(12,
                wrapped("These are resource-allocation graphs, not track maps. TRAIN → RESOURCE means a request; RESOURCE → TRAIN means that resource is allocated to the train."),
                comparison,
                wrapped("For the live simulation, set each train's ordered route in the Live Simulation tab. The simulation builds ownership and wait-for edges from real resource reservations."));
        body.setPadding(new Insets(12));
        return new Tab("Compare Examples", body);
    }

    private Pane createExampleGraph(boolean deadlocked) {
        Pane pane = new Pane();
        pane.setMinSize(450, 265);
        pane.setPrefSize(540, 285);
        pane.setStyle("-fx-background-color: #f5f8fc; -fx-background-radius: 10; -fx-border-color: #d5e1ee; -fx-border-radius: 10;");
        List<NodeData> items;
        List<Edge> links;
        Map<String, XY> fixed = new LinkedHashMap<>();
        if (deadlocked) {
            items = List.of(new NodeData("T1", Kind.TRAIN), new NodeData("T2", Kind.TRAIN),
                    new NodeData("A", Kind.TRACK), new NodeData("B", Kind.TRACK));
            links = List.of(new Edge("T1", "B"), new Edge("B", "T2"),
                    new Edge("T2", "A"), new Edge("A", "T1"));
            fixed.put("T1", new XY(78, 68)); fixed.put("B", new XY(224, 68));
            fixed.put("T2", new XY(370, 68)); fixed.put("A", new XY(224, 198));
        } else {
            items = List.of(new NodeData("T1", Kind.TRAIN), new NodeData("T2", Kind.TRAIN),
                    new NodeData("B", Kind.TRACK), new NodeData("P1", Kind.PLATFORM));
            links = List.of(new Edge("T1", "B"), new Edge("B", "T2"), new Edge("T2", "P1"));
            fixed.put("T1", new XY(70, 135)); fixed.put("B", new XY(195, 135));
            fixed.put("T2", new XY(320, 135)); fixed.put("P1", new XY(445, 135));
        }
        drawDirectedGraph(pane, items, links, deadlocked
                ? Set.of("T1->B", "B->T2", "T2->A", "A->T1") : Set.of(), fixed, false);
        return pane;
    }

    private Tab buildGraphEditorTab() {
        graphCanvas = new Pane();
        graphCanvas.setMinSize(680, 450);
        graphCanvas.setPrefSize(850, 560);
        graphCanvas.setStyle("-fx-background-color: #f5f8fc; -fx-background-radius: 10; -fx-border-color: #d5e1ee; -fx-border-radius: 10;");
        ScrollPane graphScroll = new ScrollPane(graphCanvas);
        graphScroll.setFitToWidth(true);
        graphScroll.setFitToHeight(true);

        VBox nodeForm = buildNodeForm();
        VBox edgeForm = buildEdgeForm();
        edgeList = new ListView<>();
        graphResult = new Label("Add nodes and directed edges.");
        graphResult.setWrapText(true);
        graphResult.setStyle("-fx-font-weight: bold;");
        VBox side = new VBox(10, nodeForm, new Separator(), edgeForm, new Separator(), headingLabel("Directed edges"), edgeList, graphResult,
                wrapped("Graph edges are for resource-allocation analysis. They are not interpreted as train routes: enter the ordered route separately in the Live Simulation tab."));
        side.setPadding(new Insets(12));
        side.setPrefWidth(305);
        side.setStyle(cardStyle());
        VBox.setVgrow(edgeList, Priority.ALWAYS);
        SplitPane split = new SplitPane(graphScroll, side);
        split.setDividerPositions(0.70);
        return new Tab("Graph Editor", split);
    }

    private VBox buildNodeForm() {
        nodeIdField = new TextField();
        nodeIdField.setPromptText("e.g. T4, TrackH, P4");
        nodeKindSelector = new ComboBox<>();
        nodeKindSelector.getItems().setAll("TRAIN", "TRACK", "PLATFORM");
        nodeKindSelector.setValue("TRAIN");
        nodeKindSelector.setMaxWidth(Double.MAX_VALUE);
        Button add = new Button("Add Node");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> addNode());
        return new VBox(7, headingLabel("1. Add a node"), new Label("Node ID"), nodeIdField,
                new Label("Node type"), nodeKindSelector, add);
    }

    private VBox buildEdgeForm() {
        edgeFromSelector = new ComboBox<>();
        edgeFromSelector.setPromptText("From node");
        edgeFromSelector.setMaxWidth(Double.MAX_VALUE);
        edgeToSelector = new ComboBox<>();
        edgeToSelector.setPromptText("To node");
        edgeToSelector.setMaxWidth(Double.MAX_VALUE);
        Button add = new Button("Add Directed Edge");
        add.setMaxWidth(Double.MAX_VALUE);
        add.setOnAction(event -> addEdge());
        Button remove = new Button("Remove Selected Edge");
        remove.setMaxWidth(Double.MAX_VALUE);
        remove.setOnAction(event -> removeEdge());
        Label hint = wrapped("For a resource-allocation cycle, alternate TRAIN and TRACK/PLATFORM nodes. Example: T1 → B → T2 → A → T1.");
        hint.setStyle("-fx-font-size: 11px; -fx-text-fill: #52677c;");
        return new VBox(7, headingLabel("2. Connect nodes"), new Label("From"), edgeFromSelector,
                new Label("To"), edgeToSelector, add, remove, hint);
    }

    private Tab buildLiveSimulationTab() {
        routeTrainSelector = new ComboBox<>();
        routeTrainSelector.setMaxWidth(Double.MAX_VALUE);
        routeTrainSelector.setPromptText("Select a TRAIN node");
        routeTrainSelector.valueProperty().addListener((obs, oldValue, newValue) -> loadRouteForm(newValue));
        trainNameField = new TextField(); trainNameField.setPromptText("e.g. Coastal Express");
        prioritySpinner = new Spinner<>(1, 99, 2); prioritySpinner.setEditable(true); prioritySpinner.setMaxWidth(Double.MAX_VALUE);
        routeField = new TextField(); routeField.setPromptText("A,B,C (ordered route)");
        alternateRoutesField = new TextArea();
        alternateRoutesField.setPromptText("Optional: A,D,C; A,E,C");
        alternateRoutesField.setPrefRowCount(2);
        Button saveRoute = new Button("Save Train Route"); saveRoute.setMaxWidth(Double.MAX_VALUE); saveRoute.setOnAction(event -> saveRouteDefinition());
        routeList = new ListView<>(); routeList.setPrefHeight(125);

        topologyFromSelector = new ComboBox<>(); topologyFromSelector.setPromptText("From resource"); topologyFromSelector.setMaxWidth(Double.MAX_VALUE);
        topologyToSelector = new ComboBox<>(); topologyToSelector.setPromptText("To resource"); topologyToSelector.setMaxWidth(Double.MAX_VALUE);
        Button addTopology = new Button("Add Physical Connection"); addTopology.setMaxWidth(Double.MAX_VALUE); addTopology.setOnAction(event -> addPhysicalConnection());
        Button removeTopology = new Button("Remove Selected Connection"); removeTopology.setMaxWidth(Double.MAX_VALUE); removeTopology.setOnAction(event -> removePhysicalConnection());
        topologyList = new ListView<>(); topologyList.setPrefHeight(95);
        VBox topologyForm = new VBox(6, headingLabel("Railway network connections"),
                wrapped("These undirected links represent adjacent TRACK/PLATFORM resources in the live railway map. Every consecutive pair in a train route must have a connection here."),
                new Label("Resource A"), topologyFromSelector, new Label("Resource B"), topologyToSelector,
                addTopology, removeTopology, topologyList);

        strategySelector = new ComboBox<>();
        strategySelector.getItems().setAll(RecoveryStrategy.values()); strategySelector.setValue(RecoveryStrategy.AUTO);
        strategySelector.setMaxWidth(Double.MAX_VALUE);
        speedSlider = new Slider(100, 1200, 450); speedSlider.setShowTickMarks(true); speedSlider.setShowTickLabels(true);

        Button runNormal = new Button("▶ Run Custom Simulation"); runNormal.setMaxWidth(Double.MAX_VALUE); runNormal.setOnAction(event -> startCustomRun(false));
        Button runDeadlock = new Button("⚠ Run Deterministic Deadlock Demo"); runDeadlock.setMaxWidth(Double.MAX_VALUE); runDeadlock.setOnAction(event -> startCustomRun(true));
        Button pause = new Button("Ⅱ Pause"); pause.setMaxWidth(Double.MAX_VALUE); pause.setOnAction(event -> { if (liveManager != null) liveManager.pause(); });
        Button resume = new Button("▶ Resume"); resume.setMaxWidth(Double.MAX_VALUE); resume.setOnAction(event -> { if (liveManager != null) liveManager.resume(); });
        Button reset = new Button("↻ Reset Live Run"); reset.setMaxWidth(Double.MAX_VALUE); reset.setOnAction(event -> resetLiveRun());

        VBox config = new VBox(9,
                headingLabel("Train route configuration"),
                wrapped("Add TRAIN, TRACK and PLATFORM nodes in Graph Editor first. Connect the physical TRACK/PLATFORM network below, then define ordered routes using existing resources. Every train receives a private virtual safety siding for recovery."),
                topologyForm, new Separator(),
                new Label("Train"), routeTrainSelector,
                new Label("Display name"), trainNameField,
                new Label("Priority (1 = lowest, 99 = highest)"), prioritySpinner,
                new Label("Main route (comma-separated)"), routeField,
                new Label("Alternate routes (separate routes with semicolons)"), alternateRoutesField,
                saveRoute, headingLabel("Configured routes"), routeList,
                new Separator(), headingLabel("Simulation controls"),
                new Label("Recovery strategy"), strategySelector, new Label("Train step delay (ms)"), speedSlider,
                runNormal, runDeadlock, new HBox(8, pause, resume), reset);
        config.setPadding(new Insets(12)); config.setPrefWidth(330); config.setMinWidth(310); config.setStyle(cardStyle());
        VBox.setVgrow(routeList, Priority.SOMETIMES);

        liveStatus = statusLabel("No custom simulation has been started", "#52677c");
        liveNetworkCanvas = new Pane(); liveNetworkCanvas.setMinSize(680, 280); liveNetworkCanvas.setPrefSize(850, 320);
        liveNetworkCanvas.setStyle("-fx-background-color: #f5f8fc; -fx-background-radius: 10; -fx-border-color: #d5e1ee; -fx-border-radius: 10;");
        liveWaitStatus = statusLabel("No wait-for graph yet", "#52677c");
        liveWaitGraphCanvas = new Pane(); liveWaitGraphCanvas.setMinSize(440, 190); liveWaitGraphCanvas.setPrefSize(560, 220);
        liveWaitGraphCanvas.setStyle("-fx-background-color: #f5f8fc; -fx-background-radius: 10; -fx-border-color: #d5e1ee; -fx-border-radius: 10;");
        liveEventList = new ListView<>(); liveEventList.setPrefHeight(240);
        VBox liveRight = new VBox(10,
                headingLabel("Live railway resource network"), liveNetworkCanvas,
                new Separator(), headingLabel("Actual wait-for graph · last cycle retained"), liveWaitStatus, liveWaitGraphCanvas,
                new Separator(), headingLabel("Simulation and recovery events"), liveEventList);
        liveRight.setPadding(new Insets(12)); liveRight.setStyle(cardStyle());
        VBox.setVgrow(liveNetworkCanvas, Priority.ALWAYS); VBox.setVgrow(liveWaitGraphCanvas, Priority.ALWAYS); VBox.setVgrow(liveEventList, Priority.ALWAYS);
        ScrollPane configScroll = new ScrollPane(config); configScroll.setFitToWidth(true); configScroll.setPrefViewportWidth(340);
        SplitPane split = new SplitPane(configScroll, liveRight); split.setDividerPositions(0.27);
        VBox wrapper = new VBox(8, liveStatus, split); wrapper.setPadding(new Insets(8)); VBox.setVgrow(split, Priority.ALWAYS);
        return new Tab("Live Simulation", wrapper);
    }

    private void addNode() {
        String id = nodeIdField.getText() == null ? "" : nodeIdField.getText().trim();
        if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,19}")) {
            alert("Node IDs must begin with a letter and contain only letters, digits, underscore or hyphen (maximum 20 characters)."); return;
        }
        if (nodes.containsKey(id)) { alert("That node ID already exists. IDs must be unique across all node types."); return; }
        if (nodes.size() >= 42) { alert("This graph supports up to 42 user-defined nodes."); return; }
        nodes.put(id, new NodeData(id, Kind.valueOf(nodeKindSelector.getValue())));
        nodeIdField.clear();
        refreshGraphEditor();
        if (nodes.get(id).kind() == Kind.TRAIN && routeTrainSelector != null) routeTrainSelector.setValue(id);
    }

    private void addEdge() {
        String from = edgeFromSelector.getValue(), to = edgeToSelector.getValue();
        if (from == null || to == null) { alert("Choose both endpoints."); return; }
        if (from.equals(to)) { alert("Self-edges are not allowed."); return; }
        Edge edge = new Edge(from, to);
        if (edges.contains(edge)) { alert("That directed edge already exists."); return; }
        edges.add(edge); refreshGraphEditor();
    }

    private void removeEdge() {
        int index = edgeList.getSelectionModel().getSelectedIndex();
        if (index >= 0 && index < edges.size()) { edges.remove(index); refreshGraphEditor(); }
    }

    private void loadDeadlock() {
        if (!confirmReplaceGraph()) return;
        nodes.clear(); edges.clear(); topologyConnections.clear(); routeDefinitions.clear();
        add("T1", Kind.TRAIN); add("T2", Kind.TRAIN);
        add("A", Kind.TRACK); add("B", Kind.TRACK); add("C", Kind.TRACK); add("D", Kind.TRACK);
        connect("T1", "B"); connect("B", "T2"); connect("T2", "A"); connect("A", "T1");
        routeDefinitions.put("T1", new RouteFormData("Coastal Express", 3, List.of("A", "B", "C"), List.of(List.of("A", "D", "C"))));
        routeDefinitions.put("T2", new RouteFormData("City Connector", 1, List.of("B", "A", "D"), List.of(List.of("B", "D"))));
        addTopology("A", "B"); addTopology("B", "C"); addTopology("A", "D"); addTopology("D", "C"); addTopology("B", "D");
        refreshGraphEditor();
        if (routeTrainSelector != null) loadRouteForm(routeTrainSelector.getValue());
    }

    private void loadDeadlockFree() {
        if (!confirmReplaceGraph()) return;
        nodes.clear(); edges.clear(); topologyConnections.clear(); routeDefinitions.clear();
        add("T1", Kind.TRAIN); add("T2", Kind.TRAIN); add("T3", Kind.TRAIN);
        for (String id : List.of("A", "B", "C", "D", "E", "F", "G")) add(id, Kind.TRACK);
        add("P1", Kind.PLATFORM);
        connect("T1", "B"); connect("B", "T2"); connect("T2", "P1");
        routeDefinitions.put("T1", new RouteFormData("Coastal Express", 3, List.of("A", "B", "C"), List.of(List.of("A", "D", "C"))));
        routeDefinitions.put("T2", new RouteFormData("City Connector", 2, List.of("D", "E", "F"), List.of(List.of("D", "B", "F"))));
        routeDefinitions.put("T3", new RouteFormData("Harbour Local", 1, List.of("B", "E", "G"), List.of(List.of("B", "C", "G"))));
        addTopology("A", "B"); addTopology("B", "C"); addTopology("A", "D"); addTopology("D", "C");
        addTopology("D", "E"); addTopology("E", "F"); addTopology("D", "B"); addTopology("B", "F");
        addTopology("B", "E"); addTopology("E", "G"); addTopology("C", "G");
        refreshGraphEditor();
        if (routeTrainSelector != null) loadRouteForm(routeTrainSelector.getValue());
    }

    private void refreshGraphEditor() {
        if (edgeFromSelector != null) {
            String oldFrom = edgeFromSelector.getValue(), oldTo = edgeToSelector.getValue();
            edgeFromSelector.getItems().setAll(nodes.keySet()); edgeToSelector.getItems().setAll(nodes.keySet());
            if (oldFrom != null && nodes.containsKey(oldFrom)) edgeFromSelector.setValue(oldFrom);
            if (oldTo != null && nodes.containsKey(oldTo)) edgeToSelector.setValue(oldTo);
        }
        if (edgeList != null) edgeList.getItems().setAll(edges.stream().map(e -> e.from() + "  →  " + e.to()).toList());
        if (graphCanvas != null) {
            List<NodeData> list = new ArrayList<>(nodes.values());
            Set<String> cycleEdges = cycleEdges(findCycle(nodes.keySet(), edges));
            drawDirectedGraph(graphCanvas, list, edges, cycleEdges, Map.of(), true);
        }
        if (graphResult != null) {
            List<String> cycle = findCycle(nodes.keySet(), edges);
            if (cycle.isEmpty()) graphResult.setText("DEADLOCK-FREE · No directed cycle found");
            else graphResult.setText("CYCLE DETECTED · " + String.join(" → ", cycle));
            graphResult.setStyle(cycle.isEmpty() ? "-fx-text-fill: #16805d; -fx-font-weight: bold;"
                    : "-fx-text-fill: #b42318; -fx-font-weight: bold;");
        }
        refreshRouteControls();
        refreshTopologyControls();
    }

    private void refreshRouteControls() {
        if (routeTrainSelector == null) return;
        String previous = routeTrainSelector.getValue();
        List<String> trainIds = nodes.values().stream().filter(n -> n.kind() == Kind.TRAIN).map(NodeData::id).toList();
        routeTrainSelector.getItems().setAll(trainIds);
        if (previous != null && trainIds.contains(previous)) routeTrainSelector.setValue(previous);
        else if (!trainIds.isEmpty()) routeTrainSelector.setValue(trainIds.getFirst());
        else {
            routeTrainSelector.getSelectionModel().clearSelection();
            loadRouteForm(null);
        }
        if (routeList != null) {
            routeList.getItems().setAll(routeDefinitions.entrySet().stream()
                    .filter(entry -> trainIds.contains(entry.getKey()))
                    .map(entry -> entry.getKey() + "  priority=" + entry.getValue().priority() + "  route="
                            + String.join(" → ", entry.getValue().route())
                            + (entry.getValue().alternates().isEmpty() ? "" : "  (alternate available)"))
                    .toList());
        }
    }

    private void loadRouteForm(String trainId) {
        if (trainNameField == null) return;
        RouteFormData form = trainId == null ? null : routeDefinitions.get(trainId);
        trainNameField.setText(form == null ? (trainId == null ? "" : trainId + " Custom Train") : form.name());
        prioritySpinner.getValueFactory().setValue(form == null ? 2 : form.priority());
        routeField.setText(form == null ? "" : String.join(",", form.route()));
        alternateRoutesField.setText(form == null ? "" : form.alternates().stream().map(route -> String.join(",", route)).collect(Collectors.joining("; ")));
    }

    private void saveRouteDefinition() {
        String trainId = routeTrainSelector.getValue();
        if (trainId == null || !nodes.containsKey(trainId) || nodes.get(trainId).kind() != Kind.TRAIN) {
            alert("Add or select a TRAIN node first."); return;
        }
        try {
            List<String> route = parseRoute(routeField.getText(), "Main route");
            List<List<String>> alternates = parseAlternates(alternateRoutesField.getText());
            RouteFormData def = new RouteFormData(
                    trainNameField.getText() == null || trainNameField.getText().isBlank() ? trainId + " Custom Train" : trainNameField.getText().trim(),
                    prioritySpinner.getValue(), route, alternates);
            // Run the factory's route/resource validation when all routes are configured; this local check gives immediate feedback.
            Set<String> resourceIds = nodes.values().stream().filter(n -> n.kind() != Kind.TRAIN).map(NodeData::id).collect(Collectors.toSet());
            for (String segment : route) if (!resourceIds.contains(segment)) throw new IllegalArgumentException("Main route refers to a missing TRACK/PLATFORM node: " + segment);
            for (List<String> alt : alternates) {
                for (String segment : alt) if (!resourceIds.contains(segment)) throw new IllegalArgumentException("Alternate route refers to a missing TRACK/PLATFORM node: " + segment);
                if (!alt.getFirst().equals(route.getFirst()) || !alt.getLast().equals(route.getLast())) {
                    throw new IllegalArgumentException("Each alternate route must start and finish at the same resources as the main route.");
                }
            }
            routeDefinitions.put(trainId, def);
            refreshRouteControls();
            alert("Saved route for " + trainId + ". The live scenario will use the real train and resource manager classes.");
        } catch (IllegalArgumentException ex) { alert(ex.getMessage()); }
    }

    private List<String> parseRoute(String text, String label) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException(label + " is required. Enter resource IDs separated by commas, e.g. A,B,C.");
        List<String> values = Arrays.stream(text.split(",")).map(String::trim).filter(value -> !value.isEmpty()).toList();
        if (values.isEmpty()) throw new IllegalArgumentException(label + " is empty.");
        for (String value : values) if (!value.matches("[A-Za-z][A-Za-z0-9_-]{0,19}")) throw new IllegalArgumentException("Invalid resource ID in " + label + ": " + value);
        return List.copyOf(values);
    }

    private List<List<String>> parseAlternates(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<List<String>> alternatives = new ArrayList<>();
        for (String entry : text.split(";")) if (!entry.isBlank()) alternatives.add(parseRoute(entry, "Alternate route"));
        return List.copyOf(alternatives);
    }

    private void refreshTopologyControls() {
        if (topologyFromSelector == null || topologyToSelector == null) return;
        String oldFrom = topologyFromSelector.getValue(), oldTo = topologyToSelector.getValue();
        List<String> resources = nodes.values().stream().filter(node -> node.kind() != Kind.TRAIN).map(NodeData::id).toList();
        topologyFromSelector.getItems().setAll(resources);
        topologyToSelector.getItems().setAll(resources);
        if (oldFrom != null && resources.contains(oldFrom)) topologyFromSelector.setValue(oldFrom);
        if (oldTo != null && resources.contains(oldTo)) topologyToSelector.setValue(oldTo);
        if (topologyList != null) topologyList.getItems().setAll(topologyConnections.stream()
                .map(edge -> edge.from() + "  —  " + edge.to()).toList());
    }

    private void addPhysicalConnection() {
        String from = topologyFromSelector.getValue(), to = topologyToSelector.getValue();
        if (from == null || to == null) { alert("Choose both TRACK/PLATFORM endpoints first."); return; }
        if (from.equals(to)) { alert("A resource cannot connect to itself."); return; }
        boolean exists = topologyConnections.stream().anyMatch(edge ->
                (edge.from().equals(from) && edge.to().equals(to)) || (edge.from().equals(to) && edge.to().equals(from)));
        if (exists) { alert("That physical connection already exists."); return; }
        topologyConnections.add(new Edge(from, to));
        refreshTopologyControls();
    }

    private void removePhysicalConnection() {
        int index = topologyList.getSelectionModel().getSelectedIndex();
        if (index >= 0 && index < topologyConnections.size()) {
            topologyConnections.remove(index);
            refreshTopologyControls();
        }
    }

    private void addTopology(String from, String to) {
        boolean exists = topologyConnections.stream().anyMatch(edge ->
                (edge.from().equals(from) && edge.to().equals(to)) || (edge.from().equals(to) && edge.to().equals(from)));
        if (!exists) topologyConnections.add(new Edge(from, to));
    }

    private void startCustomRun(boolean deterministicDeadlock) {
        ScenarioFactory.ScenarioData data;
        List<CustomScenarioFactory.TrainDefinition> definitions;
        try {
            List<CustomScenarioFactory.ResourceDefinition> resources = nodes.values().stream()
                    .filter(node -> node.kind() != Kind.TRAIN)
                    .map(node -> new CustomScenarioFactory.ResourceDefinition(node.id(), node.kind().name())).toList();
            definitions = new ArrayList<>();
            for (NodeData node : nodes.values()) if (node.kind() == Kind.TRAIN) {
                RouteFormData route = routeDefinitions.get(node.id());
                if (route == null) throw new IllegalArgumentException("Define a route for train " + node.id() + " before running the simulation.");
                definitions.add(new CustomScenarioFactory.TrainDefinition(node.id(), route.name(), route.priority(), route.route(), route.alternates()));
            }
            List<String[]> physical = topologyConnections.stream()
                    .map(edge -> new String[]{edge.from(), edge.to()}).toList();
            data = CustomScenarioFactory.create(resources, definitions, physical);
            if (deterministicDeadlock) validateDeterministicPair(definitions);
        } catch (IllegalArgumentException ex) { alert(ex.getMessage()); return; }

        stopPreviousRunForReplacement();
        currentCustomData = data;
        currentRunName = deterministicDeadlock ? "User-built Deterministic Deadlock Demo" : "User-built Custom Scenario";
        liveManager = new SimulationManager(eventLogger);
        try {
            liveManager.resetCustom(data, currentRunName, deterministicDeadlock);
            liveManager.setRecoveryStrategy(strategySelector.getValue());
            liveManager.setStepDelayMillis((int) speedSlider.getValue());
            liveManager.start();
            liveStatus.setText("RUNNING · " + currentRunName + " · " + data.trains().size() + " trains · recovery=" + strategySelector.getValue());
            liveStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #176b45;");
            liveEventList.getItems().clear();
            tabs.getSelectionModel().select(2);
            refreshLiveViews();
        } catch (RuntimeException ex) {
            liveManager.close(); liveManager = null;
            alert("Could not start custom simulation: " + ex.getMessage());
        }
    }

    private void validateDeterministicPair(List<CustomScenarioFactory.TrainDefinition> definitions) {
        if (definitions.size() < 2) throw new IllegalArgumentException("A deterministic deadlock demo requires at least two configured trains.");
        List<String> first = definitions.get(0).route();
        List<String> second = definitions.get(1).route();
        if (first.size() < 2 || second.size() < 2
                || !first.getFirst().equals(second.get(1))
                || !second.getFirst().equals(first.get(1))
                || first.getFirst().equals(second.getFirst())) {
            throw new IllegalArgumentException("To force a repeatable deadlock, the first two trains need crossed starting routes. Example: T1 route A,B,C and T2 route B,A,D. The app synchronizes them after their first reservation so both request the other's resource.");
        }
    }

    private void stopPreviousRunForReplacement() {
        if (liveManager != null) {
            liveManager.close();
            liveManager = null;
        }
    }

    private void resetLiveRun() {
        if (liveManager != null) {
            liveManager.close();
            liveManager = null;
        }
        liveStatus.setText("READY · Live run reset. Configure routes and run again.");
        liveStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #52677c;");
        liveWaitStatus.setText("No wait-for graph yet");
        liveEventList.getItems().clear();
        liveNetworkCanvas.getChildren().clear();
        liveWaitGraphCanvas.getChildren().clear();
    }

    private void appendLiveEvent(SimulationEvent event) {
        if (stageClosing || liveEventList == null || event == null) return;
        liveEventList.getItems().add(TIME_FORMAT.format(event.timestamp()) + "  " + event.type() + "  " + event.trainId() + "  " + event.message());
        if (liveEventList.getItems().size() > 500) liveEventList.getItems().remove(0);
        liveEventList.scrollTo(liveEventList.getItems().size() - 1);
        if (event.type().equals("SIMULATION_FINISHED")) {
            liveStatus.setText("FINISHED · " + currentRunName + " · " + event.message());
            liveStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #176b45;");
        } else if (event.type().equals("DEADLOCK_DETECTED")) {
            liveStatus.setText("DEADLOCK DETECTED · recovery strategy=" + (liveManager == null ? "—" : liveManager.getRecoveryStrategy()));
            liveStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #b42318;");
        } else if (event.type().equals("DEADLOCK_RESOLVED")) {
            liveStatus.setText("DEADLOCK RESOLVED · " + event.message());
            liveStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #176b45;");
        }
    }

    private void refreshLiveViews() {
        if (liveStatus == null || stageClosing) return;
        if (liveManager == null) {
            if (currentCustomData == null) return;
            return;
        }
        drawRailwayNetwork();
        drawLiveWaitForGraph();
        if (liveManager.isRunning() && liveManager.isPaused()) {
            liveStatus.setText("PAUSED · " + currentRunName);
            liveStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #8a5a00;");
        }
    }

    private void drawRailwayNetwork() {
        liveNetworkCanvas.getChildren().clear();
        Map<String, TrackSegment> tracks = liveManager.getTracks();
        if (tracks.isEmpty()) return;
        Map<String, XY> positions = gridPositions(tracks.keySet(), Math.max(700, liveNetworkCanvas.getWidth()), Math.max(260, liveNetworkCanvas.getHeight()));
        for (String[] connection : liveManager.getConnections()) {
            XY a = positions.get(connection[0]), b = positions.get(connection[1]);
            if (a == null || b == null) continue;
            Line line = new Line(a.x(), a.y(), b.x(), b.y());
            line.setStroke(Color.web("#9aaec1")); line.setStrokeWidth(2.2);
            liveNetworkCanvas.getChildren().add(line);
        }
        Map<String, String> owners = liveManager.getResourceManager().getOwnershipSnapshot();
        for (Map.Entry<String, TrackSegment> entry : tracks.entrySet()) {
            String id = entry.getKey(); XY point = positions.get(id); if (point == null) continue;
            String owner = owners.get(id);
            boolean siding = entry.getValue().getDisplayName().startsWith("Safety Siding");
            boolean platform = entry.getValue().getDisplayName().startsWith("Platform ");
            Color fill = owner == null ? (siding ? Color.web("#e8e5f7") : platform ? Color.web("#fff0d6") : Color.web("#dceaff")) : Color.web("#ffe0a6");
            if (platform) {
                Rectangle rect = new Rectangle(point.x() - 27, point.y() - 18, 54, 36);
                rect.setArcWidth(10); rect.setArcHeight(10); rect.setFill(fill); rect.setStroke(Color.web("#45647f")); rect.setStrokeWidth(1.8);
                liveNetworkCanvas.getChildren().add(rect);
            } else {
                Circle circle = new Circle(point.x(), point.y(), siding ? 20 : 23, fill);
                circle.setStroke(Color.web("#45647f")); circle.setStrokeWidth(1.8);
                liveNetworkCanvas.getChildren().add(circle);
            }
            Label idLabel = new Label(id); idLabel.setLayoutX(point.x() - 34); idLabel.setLayoutY(point.y() - 9); idLabel.setMinWidth(68); idLabel.setAlignment(Pos.CENTER);
            idLabel.setStyle("-fx-font-weight: bold; -fx-font-size: 11px; -fx-text-fill: #17324d;");
            Label ownerLabel = new Label(owner == null ? "FREE" : owner); ownerLabel.setLayoutX(point.x() - 34); ownerLabel.setLayoutY(point.y() + 25); ownerLabel.setMinWidth(68); ownerLabel.setAlignment(Pos.CENTER);
            ownerLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: " + (owner == null ? "#64748b" : "#a05b00") + "; -fx-font-weight: bold;");
            liveNetworkCanvas.getChildren().addAll(idLabel, ownerLabel);
        }
    }

    private void drawLiveWaitForGraph() {
        liveWaitGraphCanvas.getChildren().clear();
        Map<String, Set<String>> waitGraph = liveManager.getResourceManager().getWaitForGraph();
        List<DeadlockCycle> liveCycles = detector.findCycles(waitGraph);
        List<NodeData> graphNodes = new ArrayList<>();
        List<Edge> graphEdges = new ArrayList<>();
        Set<String> highlighted = new HashSet<>();
        boolean activeCycle = !liveCycles.isEmpty();
        String text;
        if (activeCycle) {
            Set<String> ids = new LinkedHashSet<>();
            waitGraph.forEach((from, targets) -> { ids.add(from); ids.addAll(targets); targets.forEach(to -> graphEdges.add(new Edge(from, to))); });
            for (String id : ids) graphNodes.add(new NodeData(id, Kind.TRAIN));
            for (DeadlockCycle cycle : liveCycles) highlighted.addAll(closedCycleEdges(cycle.trainIds()));
            text = "ACTIVE WAIT-FOR CYCLE · recovery in progress";
            liveWaitStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #b42318;");
        } else if (!waitGraph.isEmpty()) {
            Set<String> ids = new LinkedHashSet<>();
            waitGraph.forEach((from, targets) -> { ids.add(from); ids.addAll(targets); targets.forEach(to -> graphEdges.add(new Edge(from, to))); });
            for (String id : ids) graphNodes.add(new NodeData(id, Kind.TRAIN));
            text = "WAITING RELATIONSHIPS · no cycle detected";
            liveWaitStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #8a5a00;");
        } else if (liveManager.getLastDetectedDeadlock() != null) {
            DeadlockCycle last = liveManager.getLastDetectedDeadlock();
            List<String> cycleNodes = last.trainIds();
            for (String id : cycleNodes) graphNodes.add(new NodeData(id, Kind.TRAIN));
            for (int i = 0; i < cycleNodes.size(); i++) graphEdges.add(new Edge(cycleNodes.get(i), cycleNodes.get((i + 1) % cycleNodes.size())));
            highlighted.addAll(closedCycleEdges(cycleNodes));
            boolean resolved = liveManager.isLastDetectedDeadlockResolved();
            text = (resolved ? "LAST CYCLE · RESOLVED · " : "LAST CYCLE · NOT YET RESOLVED · ") + last;
            liveWaitStatus.setStyle(resolved ? "-fx-font-weight: bold; -fx-text-fill: #16805d;" : "-fx-font-weight: bold; -fx-text-fill: #b42318;");
            if (resolved) highlighted.clear();
        } else {
            liveWaitStatus.setText("NO WAIT-FOR EDGES · no deadlock has occurred in this run");
            liveWaitStatus.setStyle("-fx-font-weight: bold; -fx-text-fill: #16805d;");
            return;
        }
        liveWaitStatus.setText(text);
        drawDirectedGraph(liveWaitGraphCanvas, graphNodes, graphEdges, highlighted, Map.of(), false);
    }

    private ScenarioFactory.ScenarioData buildCurrentDataForValidation() {
        List<CustomScenarioFactory.ResourceDefinition> resources = nodes.values().stream()
                .filter(node -> node.kind() != Kind.TRAIN)
                .map(node -> new CustomScenarioFactory.ResourceDefinition(node.id(), node.kind().name())).toList();
        List<CustomScenarioFactory.TrainDefinition> definitions = new ArrayList<>();
        for (NodeData node : nodes.values()) if (node.kind() == Kind.TRAIN) {
            RouteFormData route = routeDefinitions.get(node.id());
            if (route == null) throw new IllegalArgumentException("Define a route for train " + node.id() + " before running the simulation.");
            definitions.add(new CustomScenarioFactory.TrainDefinition(node.id(), route.name(), route.priority(), route.route(), route.alternates()));
        }
        List<String[]> physical = topologyConnections.stream().map(edge -> new String[]{edge.from(), edge.to()}).toList();
        return CustomScenarioFactory.create(resources, definitions, physical);
    }

    private void selectEditorTab() { if (tabs != null) tabs.getSelectionModel().select(1); }

    private boolean confirmReplaceGraph() {
        if (nodes.isEmpty() && routeDefinitions.isEmpty() && edges.isEmpty() && topologyConnections.isEmpty()) return true;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, "Replace the current editor graph and route definitions with a preset?", ButtonType.YES, ButtonType.NO);
        alert.initOwner(stage); alert.setTitle("Load graph example"); alert.setHeaderText("Replace current graph?");
        return alert.showAndWait().orElse(ButtonType.NO) == ButtonType.YES;
    }

    private void drawDirectedGraph(Pane pane, List<NodeData> items, List<Edge> links,
                                   Set<String> highlightedEdges, Map<String, XY> fixedPositions,
                                   boolean scalable) {
        pane.getChildren().clear();
        if (items.isEmpty()) return;
        double width = Math.max(scalable ? 680 : 450, pane.getWidth() > 0 ? pane.getWidth() : pane.getPrefWidth());
        double height = Math.max(scalable ? 420 : 200, pane.getHeight() > 0 ? pane.getHeight() : pane.getPrefHeight());
        Map<String, XY> positions = new LinkedHashMap<>();
        if (!fixedPositions.isEmpty()) positions.putAll(fixedPositions);
        else positions.putAll(gridPositions(items.stream().map(NodeData::id).toList(), width, height));
        for (Edge edge : links) {
            XY a = positions.get(edge.from()), b = positions.get(edge.to());
            if (a == null || b == null) continue;
            double dx = b.x() - a.x(), dy = b.y() - a.y(), length = Math.max(1, Math.hypot(dx, dy));
            double ux = dx / length, uy = dy / length;
            Line line = new Line(a.x() + ux * 25, a.y() + uy * 25, b.x() - ux * 28, b.y() - uy * 28);
            boolean highlighted = highlightedEdges.contains(edge.from() + "->" + edge.to());
            line.setStroke(highlighted ? Color.web("#c0392b") : Color.web("#66829d"));
            line.setStrokeWidth(highlighted ? 3.2 : 2.2);
            pane.getChildren().add(line);
            double ex = line.getEndX(), ey = line.getEndY();
            Polygon arrow = new Polygon(ex, ey, ex - ux * 10 - uy * 5, ey - uy * 10 + ux * 5, ex - ux * 10 + uy * 5, ey - uy * 10 - ux * 5);
            arrow.setFill(line.getStroke()); pane.getChildren().add(arrow);
        }
        for (NodeData node : items) {
            XY point = positions.get(node.id()); if (point == null) continue;
            Color fill = switch (node.kind()) { case TRAIN -> Color.web("#dceaff"); case TRACK -> Color.web("#e3f2e8"); case PLATFORM -> Color.web("#fff0d6"); };
            if (node.kind() == Kind.PLATFORM) {
                Rectangle shape = new Rectangle(point.x() - 28, point.y() - 20, 56, 40);
                shape.setArcWidth(10); shape.setArcHeight(10); shape.setFill(fill); shape.setStroke(Color.web("#2c5578")); shape.setStrokeWidth(1.8);
                pane.getChildren().add(shape);
            } else {
                Circle shape = new Circle(point.x(), point.y(), 24, fill); shape.setStroke(Color.web("#2c5578")); shape.setStrokeWidth(1.8); pane.getChildren().add(shape);
            }
            Label idLabel = new Label(node.id()); idLabel.setLayoutX(point.x() - 28); idLabel.setLayoutY(point.y() - 8); idLabel.setMinWidth(56); idLabel.setAlignment(Pos.CENTER);
            idLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #17324d;");
            Label kindLabel = new Label(node.kind().name()); kindLabel.setLayoutX(point.x() - 42); kindLabel.setLayoutY(point.y() + 27);
            kindLabel.setStyle("-fx-font-size: 9px; -fx-text-fill: #52677c;");
            pane.getChildren().addAll(idLabel, kindLabel);
        }
    }

    private Map<String, XY> gridPositions(Collection<String> ids, double width, double height) {
        Map<String, XY> positions = new LinkedHashMap<>();
        List<String> ordered = new ArrayList<>(ids);
        int count = ordered.size();
        int columns = Math.max(1, (int) Math.ceil(Math.sqrt(count)));
        int rows = Math.max(1, (int) Math.ceil((double) count / columns));
        double left = 55, right = Math.max(left + 10, width - 55);
        double top = 45, bottom = Math.max(top + 10, height - 48);
        double xStep = columns == 1 ? 0 : (right - left) / (columns - 1);
        double yStep = rows == 1 ? 0 : (bottom - top) / (rows - 1);
        for (int i = 0; i < count; i++) {
            int row = i / columns, col = i % columns;
            positions.put(ordered.get(i), new XY(left + col * xStep, top + row * yStep));
        }
        return positions;
    }

    private List<String> findCycle(Collection<String> nodeIds, List<Edge> sourceEdges) {
        Map<String, List<String>> adjacency = new LinkedHashMap<>();
        nodeIds.forEach(id -> adjacency.put(id, new ArrayList<>()));
        for (Edge edge : sourceEdges) adjacency.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
        Map<String, Integer> states = new HashMap<>();
        List<String> stack = new ArrayList<>();
        for (String node : adjacency.keySet()) {
            List<String> found = dfs(node, adjacency, states, stack);
            if (!found.isEmpty()) return found;
        }
        return List.of();
    }

    private List<String> dfs(String node, Map<String, List<String>> adjacency,
                             Map<String, Integer> states, List<String> stack) {
        int state = states.getOrDefault(node, 0);
        if (state == 2) return List.of();
        if (state == 1) {
            int start = stack.indexOf(node);
            List<String> cycle = new ArrayList<>(stack.subList(start, stack.size())); cycle.add(node); return cycle;
        }
        states.put(node, 1); stack.add(node);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            List<String> found = dfs(next, adjacency, states, stack);
            if (!found.isEmpty()) return found;
        }
        stack.remove(stack.size() - 1); states.put(node, 2); return List.of();
    }

    /** Cycle produced by the editor DFS repeats its starting node at the end. */
    private Set<String> cycleEdges(List<String> cycle) {
        Set<String> result = new HashSet<>();
        for (int i = 0; i + 1 < cycle.size(); i++) result.add(cycle.get(i) + "->" + cycle.get(i + 1));
        return result;
    }

    /** DeadlockCycle stores each train once, so close the edge loop explicitly. */
    private Set<String> closedCycleEdges(List<String> cycleNodes) {
        Set<String> result = new HashSet<>();
        if (cycleNodes == null || cycleNodes.size() < 2) return result;
        for (int i = 0; i < cycleNodes.size(); i++) {
            result.add(cycleNodes.get(i) + "->" + cycleNodes.get((i + 1) % cycleNodes.size()));
        }
        return result;
    }

    private String cardStyle() { return "-fx-background-color: white; -fx-background-radius: 10; -fx-border-color: #d5e1ee; -fx-border-radius: 10;"; }
    private Label headingLabel(String text) { Label label = new Label(text); label.setStyle("-fx-font-weight: bold; -fx-text-fill: #17324d; -fx-font-size: 13px;"); return label; }
    private Label statusLabel(String text, String color) { Label label = new Label(text); label.setWrapText(true); label.setStyle("-fx-font-weight: bold; -fx-text-fill: " + color + ";"); return label; }
    private Label wrapped(String text) { Label label = new Label(text); label.setWrapText(true); return label; }

    private void alert(String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        if (stage != null) alert.initOwner(stage);
        alert.setTitle("RailSafe-JX Graph Lab"); alert.setHeaderText("Graph Lab"); alert.showAndWait();
    }

    private void closeResources() {
        if (stageClosing) return;
        stageClosing = true;
        if (refreshTimeline != null) refreshTimeline.stop();
        if (liveManager != null) { liveManager.close(); liveManager = null; }
        if (eventListener != null) eventLogger.removeListener(eventListener);
        if (ownsEventLogger) eventLogger.close();
    }

    // Test helpers are deliberately package-private; they keep business validation independent of dialogs.
    static List<String> findCycleForTest(Map<String, List<String>> adjacency) {
        GraphLabWindow helper = new GraphLabWindow();
        List<Edge> allEdges = new ArrayList<>();
        adjacency.forEach((from, targets) -> targets.forEach(to -> allEdges.add(new Edge(from, to))));
        return helper.findCycle(adjacency.keySet(), allEdges);
    }

    private void add(String id, Kind kind) { nodes.put(id, new NodeData(id, kind)); }
    private void connect(String from, String to) { edges.add(new Edge(from, to)); }
}
