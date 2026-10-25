package com.railsafe.jx.ui;

import com.railsafe.jx.database.AsyncDatabaseLogger;
import com.railsafe.jx.database.JdbcDatabaseService;
import com.railsafe.jx.database.RunSummary;
import com.railsafe.jx.deadlock.DeadlockCycle;
import com.railsafe.jx.model.Signal;
import com.railsafe.jx.model.SignalAspect;
import com.railsafe.jx.model.Train;
import com.railsafe.jx.model.TrainStatus;
import com.railsafe.jx.report.EventCsvExporter;
import com.railsafe.jx.recovery.RecoveryStrategy;
import com.railsafe.jx.simulation.SimulationManager;
import com.railsafe.jx.simulation.ScenarioType;
import com.railsafe.jx.util.EventLogger;
import com.railsafe.jx.util.SimulationEvent;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.Interpolator;
import javafx.animation.KeyValue;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Separator;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Taskbar;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.io.IOException;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** JavaFX desktop UI. All state displayed here is read from SimulationManager. */
public final class RailSafeApplication extends Application {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final Map<String, Point2D> POSITIONS = Map.of(
            "A", new Point2D(90, 90), "B", new Point2D(230, 90), "C", new Point2D(370, 90),
            "D", new Point2D(90, 185), "E", new Point2D(230, 185), "F", new Point2D(370, 185),
            "G", new Point2D(510, 185), "S1", new Point2D(90, 25), "S2", new Point2D(230, 25),
            "S3", new Point2D(230, 250));
    private static final Map<String, Color> TRAIN_COLORS = Map.of(
            "T1", Color.web("#2176FF"), "T2", Color.web("#E76F51"), "T3", Color.web("#2A9D8F"));

    private EventLogger eventLogger;
    private AsyncDatabaseLogger databaseLogger;
    private JdbcDatabaseService databaseService;
    private SimulationManager manager;
    private Stage stage;
    private Pane networkPane;
    private final Map<String, TrackVisual> trackVisuals = new HashMap<>();
    private final Map<String, TrainMarker> trainMarkers = new HashMap<>();
    private final Map<String, String> lastTrainPositions = new HashMap<>();
    private final Map<String, Timeline> markerAnimations = new HashMap<>();
    private TableView<Train> trainTable;
    private ListView<SimulationEvent> eventList;
    private ListView<DeadlockCycle> deadlockList;
    private Label runStateLabel;
    private Label totalCountLabel;
    private Label completeCountLabel;
    private Label conflictCountLabel;
    private Label resolvedCountLabel;
    private ComboBox<RecoveryStrategy> strategySelector;
    private Slider speedSlider;
    private Timeline refreshTimeline;
    private boolean shuttingDown;
    private boolean eventListenerRegistered;

    @Override public void start(Stage stage) {
        this.stage = stage;
        eventLogger = new EventLogger();
        databaseService = new JdbcDatabaseService();
        try {
            databaseService.initialize();
            eventLogger.log("DATABASE", "SYSTEM", "Persistent run history initialized automatically.");
        } catch (SQLException ex) {
            eventLogger.log("DATABASE", "SYSTEM", "Database initialization failed; simulation remains available: " + rootMessage(ex));
        }
        databaseLogger = new AsyncDatabaseLogger(databaseService,
                message -> eventLogger.log("DATABASE", "SYSTEM", message));
        eventLogger.setSink(databaseLogger);
        manager = new SimulationManager(eventLogger);
        stage.setTitle("RailSafe-JX | Railway Resource Control");
        installApplicationIcon();
        stage.setMinWidth(1000);
        stage.setMinHeight(720);
        stage.setOnCloseRequest(event -> shutdown());
        showHome();
        stage.show();
    }

    /** Sets the JavaFX window icon and, where supported, the macOS Dock/application icon. */
    private void installApplicationIcon() {
        try (InputStream stream = getClass().getResourceAsStream("/railsafe-icon.png")) {
            if (stream == null) return;
            byte[] iconBytes = stream.readAllBytes();
            stage.getIcons().add(new Image(new ByteArrayInputStream(iconBytes)));

            // Stage.getIcons() only changes the window icon. Taskbar handles the Dock icon on macOS.
            try {
                if (Taskbar.isTaskbarSupported()) {
                    Taskbar taskbar = Taskbar.getTaskbar();
                    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
                        BufferedImage dockImage = ImageIO.read(new ByteArrayInputStream(iconBytes));
                        if (dockImage != null) taskbar.setIconImage(dockImage);
                    }
                }
            } catch (UnsupportedOperationException ignored) {
                // Some desktop environments do not support a programmable application icon.
            }
        } catch (Exception ignored) {
            // The app should still launch even if an icon resource cannot be read.
        }
    }

    private void showHome() {
        if (refreshTimeline != null) {
            refreshTimeline.stop();
        }
        BorderPane page = new BorderPane();
        page.getStyleClass().add("home-root");
        VBox sidebar = new VBox(16);
        sidebar.getStyleClass().add("home-sidebar");
        sidebar.setPadding(new Insets(28, 18, 24, 18));
        Label brandIcon = new Label("🚆"); brandIcon.getStyleClass().add("home-brand-icon");
        Label brand = new Label("RailSafe-JX"); brand.getStyleClass().add("home-brand");
        Label brandSub = new Label("SAFE TRACKS · SMARTER JOURNEYS"); brandSub.getStyleClass().add("home-brand-sub");
        VBox brandBox = new VBox(5, brandIcon, brand, brandSub); brandBox.setAlignment(Pos.CENTER);
        sidebar.getChildren().add(brandBox);
        VBox nav = new VBox(8);
        nav.getChildren().addAll(navButton("⌂   Home", true, this::showHome),
                navButton("▶   Simulation", false, this::showDashboard),
                navButton("⑂   Graph Lab", false, () -> new GraphLabWindow(eventLogger).show()),
                navButton("▤   Network & Trains", false, this::showDashboard),
                navButton("◷   History & Analytics", false, this::showDashboard));
        sidebar.getChildren().add(nav);
        Region push = new Region(); VBox.setVgrow(push, Priority.ALWAYS); sidebar.getChildren().add(push);
        Label quote = new Label("“Efficient rails for a safer tomorrow.”"); quote.setWrapText(true); quote.getStyleClass().add("home-quote"); sidebar.getChildren().add(quote);
        page.setLeft(sidebar);

        Image heroImage;
        var heroStream = getClass().getResourceAsStream("/railsafe-hero.jpg");
        heroImage = heroStream == null ? null : new Image(heroStream);
        ImageView imageView = new ImageView();
        if (heroImage != null) { imageView.setImage(heroImage); imageView.setPreserveRatio(false); imageView.fitWidthProperty().bind(page.widthProperty().subtract(235)); imageView.setFitHeight(350); }
        StackPane hero = new StackPane(); hero.getStyleClass().add("home-hero");
        if (heroImage != null) hero.getChildren().add(imageView);
        VBox heroCopy = new VBox(12); heroCopy.setPadding(new Insets(38, 42, 32, 42)); heroCopy.setAlignment(Pos.CENTER_LEFT);
        Label eyebrow = new Label("SIMULATE  ·  DETECT  ·  RESOLVE  ·  OPTIMIZE"); eyebrow.getStyleClass().add("home-eyebrow");
        Label heroTitle = new Label("RailSafe-JX"); heroTitle.getStyleClass().add("home-hero-title");
        Label heroSubtitle = new Label("Dynamic Railway Resource Allocation\nand Deadlock Resolution"); heroSubtitle.getStyleClass().add("home-hero-subtitle");
        Label description = new Label("Build railway networks, simulate train operations, detect deadlocks in real time, and apply intelligent recovery strategies."); description.setWrapText(true); description.setMaxWidth(590); description.getStyleClass().add("home-description");
        HBox actions = new HBox(12);
        Button start = new Button("▶   Open Control Center"); start.getStyleClass().add("home-primary"); start.setOnAction(e -> showDashboard());
        Button graph = new Button("⑂   Open Graph Lab"); graph.getStyleClass().add("home-secondary"); graph.setOnAction(e -> new GraphLabWindow(eventLogger).show());
        actions.getChildren().addAll(start, graph); heroCopy.getChildren().addAll(eyebrow, heroTitle, heroSubtitle, description, actions);
        StackPane.setAlignment(heroCopy, Pos.CENTER_LEFT); hero.getChildren().add(heroCopy); hero.setMinHeight(350); hero.setPrefHeight(350); hero.setMaxHeight(350);

        GridPane cards = new GridPane(); cards.setHgap(14); cards.setVgap(14); cards.setPadding(new Insets(18, 0, 18, 0));
        cards.add(homeCard("🚆", "Run Simulation", "Run concurrent trains and inspect live resource ownership.", "Open dashboard →", this::showDashboard, "home-card-blue"),0,0);
        cards.add(homeCard("⑂", "Graph Lab", "Create custom train, track and platform graphs; detect cycles.", "Build a graph →", () -> new GraphLabWindow(eventLogger).show(), "home-card-purple"),1,0);
        cards.add(homeCard("🛡", "Smart Recovery", "Demonstrate AUTO, DELAY, RELEASE and REROUTE strategies.", "Explore recovery →", this::showDashboard, "home-card-green"),0,1);
        cards.add(homeCard("▥", "History & Reports", "Review persisted runs and export event logs for evaluation.", "View history →", this::showDashboard, "home-card-gold"),1,1);
        ColumnConstraints c1 = new ColumnConstraints(); c1.setPercentWidth(50); ColumnConstraints c2 = new ColumnConstraints(); c2.setPercentWidth(50); cards.getColumnConstraints().addAll(c1,c2);
        VBox content = new VBox(hero, cards); content.setPadding(new Insets(18)); content.getStyleClass().add("home-content");
        ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true); scroll.getStyleClass().add("home-scroll"); page.setCenter(scroll);
        Scene scene = new Scene(page, 1280, 820); var stylesheet = getClass().getResource("/style.css"); if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm()); stage.setScene(scene);
    }

    private Button navButton(String text, boolean active, Runnable action) {
        Button button = new Button(text); button.setMaxWidth(Double.MAX_VALUE); button.setAlignment(Pos.CENTER_LEFT); button.getStyleClass().add("home-nav-button"); if (active) button.getStyleClass().add("home-nav-active"); button.setOnAction(e -> action.run()); return button;
    }

    private VBox homeCard(String icon, String title, String description, String actionText, Runnable action, String style) {
        Label iconLabel = new Label(icon); iconLabel.getStyleClass().add("home-card-icon"); Label heading = new Label(title); heading.getStyleClass().add("home-card-title"); Label body = new Label(description); body.setWrapText(true); body.getStyleClass().add("home-card-body"); Button actionButton = new Button(actionText); actionButton.getStyleClass().add("home-card-action"); actionButton.setOnAction(e -> action.run()); VBox card = new VBox(10, iconLabel, heading, body, actionButton); card.setPadding(new Insets(20)); card.setMinHeight(190); card.getStyleClass().addAll("home-card", style); return card;
    }

    private void showDashboard() {
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-root");
        root.setTop(buildHeaderAndControls());
        root.setCenter(buildMainContent());
        root.setBottom(buildFooter());
        if (!eventListenerRegistered) {
            eventLogger.addListener(event -> Platform.runLater(() -> {
                if (eventList == null) return;
                eventList.getItems().add(event);
                if (eventList.getItems().size() > 350) eventList.getItems().remove(0);
                eventList.scrollTo(eventList.getItems().size() - 1);
            }));
            eventListenerRegistered = true;
        }
        if (eventList != null) for (SimulationEvent event : eventLogger.getRecentEvents(150)) eventList.getItems().add(event);
        if (refreshTimeline != null) refreshTimeline.stop();
        refreshTimeline = new Timeline(new KeyFrame(Duration.millis(180), ignored -> refreshDashboard()));
        refreshTimeline.setCycleCount(Timeline.INDEFINITE); refreshTimeline.play();
        Scene scene = new Scene(root, 1260, 850); var stylesheet = getClass().getResource("/style.css"); if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm()); stage.setScene(scene); refreshDashboard();
    }

    private Node buildHeaderAndControls() {
        VBox container = new VBox(12);
        container.setPadding(new Insets(18, 22, 14, 22));
        container.getStyleClass().add("top-panel");

        HBox titleRow = new HBox(14);
        titleRow.setAlignment(Pos.CENTER_LEFT);
        VBox titleBlock = new VBox(3);
        Label title = new Label("RailSafe-JX");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("Dynamic railway resource allocation and deadlock resolution");
        subtitle.getStyleClass().add("app-subtitle");
        titleBlock.getChildren().addAll(title, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button homeButton = new Button("⌂  Home");
        homeButton.getStyleClass().add("home-return-button");
        homeButton.setTooltip(new Tooltip("Return to the RailSafe-JX welcome page"));
        homeButton.setOnAction(event -> showHome());
        runStateLabel = new Label("READY · NORMAL TRAFFIC");
        runStateLabel.getStyleClass().add("state-pill");
        titleRow.getChildren().addAll(titleBlock, spacer, homeButton, runStateLabel);

        Button startButton = new Button("▶  Start");
        startButton.getStyleClass().add("primary-button");
        startButton.setOnAction(event -> manager.start());
        Button pauseButton = new Button("Ⅱ  Pause");
        pauseButton.setOnAction(event -> manager.pause());
        Button resumeButton = new Button("▶  Resume");
        resumeButton.setOnAction(event -> manager.resume());
        Button resetButton = new Button("↻  Reset");
        resetButton.setOnAction(event -> {
            resetMarkerPositions();
            manager.reset(ScenarioType.NORMAL);
            manager.setRecoveryStrategy(strategySelector.getValue());
            eventList.getItems().clear();
            deadlockList.getItems().clear();
        });
        Button demoButton = new Button("⚠  Deadlock Demo");
        demoButton.getStyleClass().add("warning-button");
        demoButton.setOnAction(event -> {
            resetMarkerPositions();
            manager.reset(ScenarioType.DEADLOCK_DEMO);
            manager.setRecoveryStrategy(strategySelector.getValue());
            manager.start();
        });
        Button historyButton = new Button("History");
        historyButton.setOnAction(event -> showHistory());
        Button graphLabButton = new Button("Graph Lab");
        graphLabButton.setTooltip(new Tooltip("Build custom resource-allocation graphs and compare deadlock vs deadlock-free cases"));
        graphLabButton.setOnAction(event -> new GraphLabWindow(eventLogger).show());
        Button exportButton = new Button("Export CSV");
        exportButton.setTooltip(new Tooltip("Export the recent event log to a UTF-8 CSV file"));
        exportButton.setOnAction(event -> exportEvents());

        strategySelector = new ComboBox<>();
        strategySelector.getItems().setAll(RecoveryStrategy.values());
        strategySelector.setValue(RecoveryStrategy.AUTO);
        strategySelector.setPrefWidth(140);
        strategySelector.setOnAction(event -> manager.setRecoveryStrategy(strategySelector.getValue()));
        Label strategyLabel = new Label("Recovery");
        strategyLabel.getStyleClass().add("toolbar-label");
        speedSlider = new Slider(100, 1200, manager.getStepDelayMillis());
        speedSlider.setPrefWidth(140);
        speedSlider.setShowTickMarks(false);
        speedSlider.valueProperty().addListener((observable, oldValue, newValue) -> manager.setStepDelayMillis(newValue.intValue()));
        Label speedLabel = new Label("Speed");
        speedLabel.getStyleClass().add("toolbar-label");
        Label speedValue = new Label();
        speedValue.textProperty().bind(speedSlider.valueProperty().asString("%.0f ms"));
        HBox controls = new HBox(8, startButton, pauseButton, resumeButton, resetButton, demoButton,
                new Separator(), strategyLabel, strategySelector, speedLabel, speedSlider, speedValue, historyButton, graphLabButton, exportButton);
        controls.setAlignment(Pos.CENTER_LEFT);
        controls.getStyleClass().add("toolbar");
        container.getChildren().addAll(titleRow, controls);
        return container;
    }

    private Node buildMainContent() {
        VBox main = new VBox(12);
        main.setPadding(new Insets(14, 22, 14, 22));
        GridPane cards = new GridPane();
        cards.setHgap(10);
        cards.setVgap(10);
        totalCountLabel = createMetricValue("3");
        completeCountLabel = createMetricValue("0");
        conflictCountLabel = createMetricValue("0");
        resolvedCountLabel = createMetricValue("0");
        cards.add(metricCard("TOTAL TRAINS", totalCountLabel, "fleet-icon"), 0, 0);
        cards.add(metricCard("COMPLETED", completeCountLabel, "complete-icon"), 1, 0);
        cards.add(metricCard("ACTIVE CONFLICTS", conflictCountLabel, "conflict-icon"), 2, 0);
        cards.add(metricCard("RESOLVED DEADLOCKS", resolvedCountLabel, "resolved-icon"), 3, 0);
        for (int i = 0; i < 4; i++) GridPane.setHgrow(cards.getChildren().get(i), Priority.ALWAYS);

        networkPane = buildNetworkPane();
        VBox networkCard = card("RAILWAY NETWORK", new Label("Occupied tracks and signal states update live"), networkPane);
        networkCard.setPrefHeight(330);
        trainTable = buildTrainTable();
        VBox trainCard = card("TRAIN OPERATIONS", trainTable);
        trainCard.setPrefHeight(255);
        SplitPane topSplit = new SplitPane(networkCard, trainCard);
        topSplit.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        topSplit.setDividerPositions(0.53);

        eventList = new ListView<>();
        eventList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(SimulationEvent item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) { setText(null); setGraphic(null); }
                else {
                    String prefix = TIME_FORMAT.format(item.timestamp()) + "  " + item.type() + "  " + item.trainId() + "  ";
                    setText(prefix + item.message());
                    getStyleClass().removeAll("event-error", "event-deadlock", "event-recovery");
                    if (item.type().contains("FAILED") || item.type().contains("ERROR")) getStyleClass().add("event-error");
                    else if (item.type().contains("DEADLOCK")) getStyleClass().add("event-deadlock");
                    else if (item.type().contains("RECOVERY")) getStyleClass().add("event-recovery");
                }
            }
        });
        deadlockList = new ListView<>();
        deadlockList.setPlaceholder(new Label("No cycle detected yet.\nClick Deadlock Demo to create T1 ↔ T2."));
        deadlockList.setTooltip(new Tooltip("Wait-for graph: A → B means train A is waiting for a resource currently held by train B."));
        deadlockList.setCellFactory(list -> new ListCell<>() {
            @Override protected void updateItem(DeadlockCycle item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                boolean active = manager != null && manager.getActiveDeadlocks().stream()
                        .anyMatch(cycle -> cycle.signature().equals(item.signature()));
                VBox content = new VBox(6);
                Label status = new Label(active ? "ACTIVE DEADLOCK · recovery in progress"
                        : (manager != null && manager.isLastDetectedDeadlockResolved()
                        ? "LAST CYCLE · RESOLVED" : "CYCLE DETECTED · CHECKING"));
                status.setStyle(active
                        ? "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #B42318;"
                        : "-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #16805D;");
                HBox graph = new HBox(6);
                graph.setAlignment(Pos.CENTER_LEFT);
                List<String> cycleNodes = new ArrayList<>(item.trainIds());
                cycleNodes.add(item.trainIds().getFirst());
                Color nodeFill = active ? Color.web("#FDE8E7") : Color.web("#E4F5EC");
                Color nodeStroke = active ? Color.web("#D9485F") : Color.web("#2A9D70");
                for (int i = 0; i < cycleNodes.size(); i++) {
                    String trainId = cycleNodes.get(i);
                    Circle nodeCircle = new Circle(15, nodeFill);
                    nodeCircle.setStroke(nodeStroke);
                    nodeCircle.setStrokeWidth(1.8);
                    Label nodeLabel = new Label(trainId);
                    nodeLabel.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #17324D;");
                    StackPane node = new StackPane(nodeCircle, nodeLabel);
                    node.setMinSize(32, 32);
                    node.setPrefSize(32, 32);
                    graph.getChildren().add(node);
                    if (i < cycleNodes.size() - 1) {
                        Label arrow = new Label("→");
                        arrow.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: "
                                + (active ? "#B42318" : "#496A85") + ";");
                        graph.getChildren().add(arrow);
                    }
                }
                Label hint = new Label("A → B means A waits for a track held by B.");
                hint.setStyle("-fx-font-size: 10px; -fx-text-fill: #587087;");
                content.getChildren().addAll(status, graph, hint);
                setText(null);
                setGraphic(content);
                setPrefHeight(82);
            }
        });
        VBox eventCard = card("LIVE EVENT LOG", eventList);
        Label graphHelp = new Label("Directed edges show which train owns the requested resource.");
        graphHelp.getStyleClass().add("footer-note");
        VBox deadlockCard = card("WAIT-FOR GRAPH · LAST CYCLE", graphHelp, deadlockList);
        VBox.setVgrow(eventList, Priority.ALWAYS);
        VBox.setVgrow(deadlockList, Priority.ALWAYS);
        SplitPane bottomSplit = new SplitPane(eventCard, deadlockCard);
        bottomSplit.setDividerPositions(0.73);
        bottomSplit.setPrefHeight(215);
        VBox.setVgrow(topSplit, Priority.ALWAYS);
        VBox.setVgrow(bottomSplit, Priority.ALWAYS);
        main.getChildren().addAll(cards, topSplit, bottomSplit);
        VBox.setVgrow(topSplit, Priority.ALWAYS);
        return main;
    }

    private Pane buildNetworkPane() {
        Pane pane = new Pane();
        pane.setMinHeight(285);
        pane.setPrefSize(690, 300);
        pane.getStyleClass().add("network-canvas");
        List<String[]> connections = List.of(
                new String[]{"A", "B"}, new String[]{"B", "C"}, new String[]{"D", "E"},
                new String[]{"E", "F"}, new String[]{"F", "G"}, new String[]{"B", "E"},
                new String[]{"A", "D"}, new String[]{"C", "F"}, new String[]{"A", "S1"},
                new String[]{"B", "S2"}, new String[]{"E", "S3"});
        for (String[] edge : connections) {
            Point2D a = POSITIONS.get(edge[0]);
            Point2D b = POSITIONS.get(edge[1]);
            Line line = new Line(a.getX(), a.getY(), b.getX(), b.getY());
            line.getStyleClass().add("track-line");
            pane.getChildren().add(line);
        }
        for (Map.Entry<String, Point2D> entry : POSITIONS.entrySet()) {
            String id = entry.getKey();
            Point2D p = entry.getValue();
            Circle body = new Circle(p.getX(), p.getY(), id.startsWith("S") ? 15 : 22);
            body.getStyleClass().add(id.startsWith("S") ? "siding-node" : "track-node");
            Label idLabel = new Label(id);
            idLabel.setLayoutX(p.getX() - (id.startsWith("S") ? 8 : 9));
            idLabel.setLayoutY(p.getY() - 10);
            idLabel.getStyleClass().add("track-label");
            Circle signal = new Circle(p.getX() + 24, p.getY() - 20, 5);
            signal.getStyleClass().add("signal-green");
            Label owner = new Label("FREE");
            owner.setLayoutX(p.getX() - 28);
            owner.setLayoutY(p.getY() + 27);
            owner.getStyleClass().add("owner-label");
            pane.getChildren().addAll(body, signal, idLabel, owner);
            trackVisuals.put(id, new TrackVisual(body, signal, owner));
            Tooltip.install(body, new Tooltip("Track " + id));
        }
        Label legend = new Label("● occupied   ● clear signal   ● red signal   S = safe siding");
        legend.getStyleClass().add("network-legend");
        legend.setLayoutX(365);
        legend.setLayoutY(8);
        pane.getChildren().add(legend);
        for (Train train : manager.getTrains().values()) {
            Circle marker = new Circle(-25, -25, 9, TRAIN_COLORS.getOrDefault(train.getId(), Color.BLACK));
            marker.setStroke(Color.WHITE);
            marker.setStrokeWidth(2);
            Label markerLabel = new Label(train.getId());
            markerLabel.setFont(Font.font(9));
            markerLabel.setTextFill(Color.web("#132238"));
            markerLabel.layoutXProperty().bind(marker.centerXProperty().add(8));
            markerLabel.layoutYProperty().bind(marker.centerYProperty().subtract(15));
            marker.setVisible(false);
            markerLabel.setVisible(false);
            pane.getChildren().addAll(marker, markerLabel);
            trainMarkers.put(train.getId(), new TrainMarker(marker, markerLabel));
        }
        return pane;
    }

    private TableView<Train> buildTrainTable() {
        TableView<Train> table = new TableView<>();
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("Waiting for simulation"));
        table.getColumns().add(column("ID", Train::getId, 48));
        table.getColumns().add(column("Train", Train::getName, 140));
        table.getColumns().add(column("Priority", train -> Integer.toString(train.getPriority()), 64));
        table.getColumns().add(column("Position", train -> valueOrDash(train.getCurrentSegment()), 68));
        table.getColumns().add(column("Destination", Train::getDestination, 75));
        table.getColumns().add(column("Status", train -> train.getStatus().name(), 94));
        table.getColumns().add(column("Waiting", train -> train.getWaitingDurationMillis() + " ms", 76));
        table.setRowFactory(view -> new javafx.scene.control.TableRow<>() {
            @Override protected void updateItem(Train item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("row-waiting", "row-completed", "row-delayed");
                if (!empty && item != null) {
                    if (item.getStatus() == TrainStatus.WAITING) getStyleClass().add("row-waiting");
                    if (item.getStatus() == TrainStatus.COMPLETED) getStyleClass().add("row-completed");
                    if (item.getStatus() == TrainStatus.DELAYED) getStyleClass().add("row-delayed");
                }
            }
        });
        return table;
    }

    private TableColumn<Train, String> column(String title, java.util.function.Function<Train, String> getter, double width) {
        TableColumn<Train, String> column = new TableColumn<>(title);
        column.setPrefWidth(width);
        column.setCellValueFactory(data -> new ReadOnlyStringWrapper(getter.apply(data.getValue())));
        return column;
    }

    private VBox metricCard(String title, Label value, String styleClass) {
        VBox card = new VBox(5);
        card.setPadding(new Insets(12, 14, 12, 14));
        card.getStyleClass().addAll("metric-card", styleClass);
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("metric-title");
        value.getStyleClass().add("metric-value");
        card.getChildren().addAll(titleLabel, value);
        return card;
    }

    private Label createMetricValue(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("metric-value");
        return label;
    }

    private VBox card(String title, Node... nodes) {
        VBox box = new VBox(8);
        box.setPadding(new Insets(12));
        box.getStyleClass().add("content-card");
        Label heading = new Label(title);
        heading.getStyleClass().add("section-heading");
        box.getChildren().add(heading);
        box.getChildren().addAll(nodes);
        VBox.setVgrow(nodes.length > 0 ? nodes[nodes.length - 1] : new Region(), Priority.ALWAYS);
        return box;
    }

    private Node buildFooter() {
        HBox footer = new HBox(8);
        footer.setPadding(new Insets(8, 22, 10, 22));
        footer.setAlignment(Pos.CENTER_LEFT);
        Label note = new Label("SIMULATION ONLY  ·  Exclusive resource ownership  ·  Live wait-for graph  ·  Optional JDBC persistence");
        note.getStyleClass().add("footer-note");
        footer.getChildren().add(note);
        return footer;
    }

    private void refreshDashboard() {
        if (manager == null) return;
        totalCountLabel.setText(Integer.toString(manager.getTotalTrains()));
        completeCountLabel.setText(Integer.toString(manager.getCompletedTrains()));
        conflictCountLabel.setText(Long.toString(manager.getActiveConflicts()));
        resolvedCountLabel.setText(Integer.toString(manager.getResolvedDeadlocks()));
        String state = manager.isRunning() ? (manager.isPaused() ? "PAUSED" : "RUNNING") : "READY / STOPPED";
        runStateLabel.setText(state + " · " + manager.getScenarioType().getDisplayName().toUpperCase());
        runStateLabel.getStyleClass().removeAll("state-running", "state-paused", "state-ready");
        runStateLabel.getStyleClass().add(manager.isRunning() ? (manager.isPaused() ? "state-paused" : "state-running") : "state-ready");
        trainTable.getItems().setAll(manager.getTrains().values());
        List<DeadlockCycle> activeCycles = manager.getActiveDeadlocks();
        DeadlockCycle lastCycle = manager.getLastDetectedDeadlock();
        List<DeadlockCycle> shownCycles = !activeCycles.isEmpty() ? activeCycles
                : (lastCycle == null ? List.of() : List.of(lastCycle));
        if (!deadlockList.getItems().equals(shownCycles)) deadlockList.getItems().setAll(shownCycles);
        else deadlockList.refresh();
        Map<String, String> ownership = manager.getResourceManager().getOwnershipSnapshot();
        for (Map.Entry<String, TrackVisual> entry : trackVisuals.entrySet()) {
            String id = entry.getKey();
            TrackVisual visual = entry.getValue();
            String ownerId = ownership.get(id);
            visual.ownerLabel().setText(ownerId == null ? "FREE" : ownerId);
            visual.body().setFill(ownerId == null ? Color.web("#E8EEF5") : TRAIN_COLORS.getOrDefault(ownerId, Color.web("#E9B44C")));
            Signal signal = manager.getSignals().get("SIG-" + id);
            if (signal != null) {
                signal.updateForOccupancy(ownerId != null);
                visual.signal().setFill(signal.getAspect() == SignalAspect.RED ? Color.web("#D9485F") : Color.web("#36B37E"));
                visual.signal().getStyleClass().removeAll("signal-green", "signal-red");
                visual.signal().getStyleClass().add(signal.getAspect() == SignalAspect.RED ? "signal-red" : "signal-green");
            }
        }
        for (Train train : manager.getTrains().values()) {
            TrainMarker marker = trainMarkers.get(train.getId());
            if (marker == null) continue;
            String position = train.getCurrentSegment();
            // Immutable maps such as Map.of(...) reject null lookup keys. A train may
            // not have entered its first segment yet, so hide its marker until it has
            // a valid position rather than crashing the dashboard refresh loop.
            Point2D target = position == null ? null : POSITIONS.get(position);
            if (target == null) {
                marker.circle().setVisible(false);
                marker.label().setVisible(false);
                lastTrainPositions.remove(train.getId());
                continue;
            }
            boolean wasVisible = marker.circle().isVisible();
            String lastPosition = lastTrainPositions.get(train.getId());
            marker.circle().setVisible(true);
            marker.label().setVisible(true);
            if (!wasVisible || lastPosition == null) {
                marker.circle().setCenterX(target.getX());
                marker.circle().setCenterY(target.getY());
            } else if (!position.equals(lastPosition)) {
                Timeline oldAnimation = markerAnimations.get(train.getId());
                if (oldAnimation != null) oldAnimation.stop();
                double duration = Math.max(120, Math.min(360, manager.getStepDelayMillis() * 0.65));
                Timeline animation = new Timeline(new KeyFrame(Duration.millis(duration),
                        new KeyValue(marker.circle().centerXProperty(), target.getX(), Interpolator.EASE_BOTH),
                        new KeyValue(marker.circle().centerYProperty(), target.getY(), Interpolator.EASE_BOTH)));
                markerAnimations.put(train.getId(), animation);
                animation.play();
            }
            lastTrainPositions.put(train.getId(), position);
        }
    }

    private void resetMarkerPositions() {
        markerAnimations.values().forEach(Timeline::stop);
        markerAnimations.clear();
        lastTrainPositions.clear();
        trainMarkers.values().forEach(marker -> {
            marker.circle().setVisible(false);
            marker.label().setVisible(false);
            marker.circle().setCenterX(-25);
            marker.circle().setCenterY(-25);
        });
    }

    private void exportEvents() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export RailSafe-JX Event Log");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV files (*.csv)", "*.csv"));
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        chooser.setInitialFileName("railsafe-events-" + timestamp + ".csv");
        File selected = chooser.showSaveDialog(stage);
        if (selected == null) return;

        List<SimulationEvent> snapshot = eventLogger.getRecentEvents(2000);
        try {
            EventCsvExporter.export(selected.toPath(), snapshot);
            showMessage(Alert.AlertType.INFORMATION, "Export complete",
                    "Exported " + snapshot.size() + " events to:\n" + selected.getAbsolutePath());
        } catch (IOException | RuntimeException ex) {
            showMessage(Alert.AlertType.ERROR, "Export failed",
                    "Could not export the event log: " + rootMessage(ex));
        }
    }

    private void showHistory() {
        if (databaseService == null || !databaseService.isConfigured()) {
            showMessage(Alert.AlertType.INFORMATION, "Database history", "Persistent history is disabled for this launch. By default RailSafe-JX creates a local database automatically. To use a custom database, set RAILSAFE_DB_URL (and credentials if required) before launching.");
            return;
        }
        CompletableFuture.supplyAsync(() -> {
            try { return databaseLogger.recentRuns(20); }
            catch (SQLException ex) { throw new RuntimeException(ex); }
        }).whenComplete((runs, error) -> Platform.runLater(() -> {
            if (error != null) {
                showMessage(Alert.AlertType.WARNING, "Database history unavailable", "Could not load history: " + rootMessage(error) + "\nThe simulation remains usable even if persistence is unavailable.");
                return;
            }
            TextArea area = new TextArea();
            area.setEditable(false);
            area.setWrapText(true);
            StringBuilder output = new StringBuilder("Recent simulation runs\n\n");
            for (RunSummary run : runs) {
                output.append("Run #").append(run.runId()).append(" | ").append(run.scenarioName()).append(" | ")
                        .append(run.outcome()).append(" | started=").append(run.startedAt()).append(" | ended=").append(run.endedAt()).append('\n');
            }
            if (runs.isEmpty()) output.append("No saved runs were found.");
            area.setText(output.toString());
            area.setPrefColumnCount(80);
            area.setPrefRowCount(20);
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.initOwner(stage);
            alert.setTitle("Simulation history");
            alert.setHeaderText("Persistent run history");
            alert.getDialogPane().setContent(area);
            alert.showAndWait();
        }));
    }

    private void showMessage(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type, message);
        alert.initOwner(stage);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage();
    }

    private static String valueOrDash(String value) { return value == null ? "—" : value; }

    private void shutdown() {
        if (shuttingDown) return;
        shuttingDown = true;
        if (refreshTimeline != null) refreshTimeline.stop();
        if (manager != null) manager.close();
        if (eventLogger != null) eventLogger.close();
    }

    @Override public void stop() { shutdown(); }

    private record TrackVisual(Circle body, Circle signal, Label ownerLabel) { }
    private record TrainMarker(Circle circle, Label label) { }

    public static void main(String[] args) { launch(args); }
}
