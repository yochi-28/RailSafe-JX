RAILSAFE-JX

Project Guide, Report & Placement Handbook

System explanation • Report-ready material • Viva Q&A • Learning plan

| For project understanding, academic report preparation, technical viva and placement interviews |
| --- |

Project version 1.3.5  |  Prepared 25 October 2026

Academic demonstrator • Java 21 • JavaFX • Maven • JDBC • MySQL / H2

## How to use this handbook

This handbook explains what the supplied RailSafe-JX code actually implements and provides material to help you explain it in a report, viva or placement interview. Use it as a study source, not as a substitute for reading the code and running the application yourself. Replace evidence placeholders with your own screenshots, actual test outputs and institution-specific details.

| Accuracy and safety note / RailSafe-JX is an educational concurrency demonstrator. The fixed topology, simplified signals and predefined recovery routes are not railway-grade control. Do not claim real-world deployment, certified safety, measured performance improvement or a passing CI/build unless you have evidence from the relevant run. |
| --- |

### Handbook map

Part I — Understand the project  Problem, objectives, architecture, algorithms, concurrency and database.

Part II — Demonstrate and validate  UI workflows, Graph Lab, tests and evidence checklist.

Part III — Write the project report  Draft abstract, chapter content, results and conclusion prompts.

Part IV — Viva and placement  Short pitch, technical questions/answers, résumé bullets and follow-up prompts.

Part V — Learn the code  Reading order, seven-session plan, practice tasks and final checklist.

## PART I — UNDERSTAND THE PROJECT

## 1. Project overview

Project title: “RailSafe-JX: Dynamic Railway Resource Allocation and Deadlock Resolution System Using Concurrent Java.” The project simulates multiple trains that run concurrently and compete for exclusive track/platform resources. When trains hold different resources while waiting for each other, a wait-for graph can contain a cycle. RailSafe-JX detects cycles and demonstrates recovery through predefined alternate routes, a reserved siding delay, or controlled rollback/siding fallback.

### 1.1 Explain it in one minute

Imagine two trains approach a single-track area. Train T1 reserves segment A and then asks for segment B, while T2 reserves B and asks for A. Each train holds one resource and waits for a resource held by the other. Neither can move forward, producing a circular wait. RailSafe-JX makes the resource ownership explicit, records the pending requests, builds a wait-for graph, detects a cycle with depth-first search, and asks a recovery engine to safely alter one train’s movement. The JavaFX interface shows the trains and log events, and JDBC can save run history so the outcome can be inspected after the simulation finishes.

### 1.2 Problem statement

A concurrent railway-resource simulation needs to prevent two trains from owning the same exclusive track segment. If a train retains one segment while requesting a second occupied segment, trains can form a circular wait. Without detection and recovery, the simulation may stop making progress. The problem addressed is how to coordinate ownership, detect cyclic waits during execution, and demonstrate controlled recovery while maintaining internally consistent train positions and resource ownership.

### 1.3 Objectives

- Model trains as concurrent tasks with ordered routes through exclusive resources.

- Enforce one owner per track/platform segment and track pending acquisition requests.

- Construct a live wait-for graph from pending requests and current resource owners.

- Detect directed cycles with DFS and report cycle participants.

- Demonstrate `REROUTE`, `DELAY`, `RELEASE` and `AUTO` recovery policies, recording failed attempts and safe fallbacks.

- Visualize status, ownership, deadlock and recovery events using JavaFX.

- Persist run summaries and events through JDBC using local MySQL or embedded H2, and export recent events to UTF-8 CSV.

- Exercise core invariants and scenarios using JUnit 5 tests.

### 1.4 Scope and non-goals

| Included in this project | Not implemented / not claimed |
| --- | --- |
| Synthetic railway resource topology; train state, route progression and exclusive reservation. | Real railway track data integration, train physics, certified signalling/interlocking or operational dispatch. |
| Dynamic waiting relationships, DFS cycle detection, deterministic deadlock demo. | Formal verification or proof of safety for railway use; broad deadlock guarantees beyond the simplified resource model. |
| Predefined alternate routes, dedicated virtual sidings, controlled rollback, fallback behavior. | Global shortest-path or timetable optimization; real-world safety siding topology validation. |
| JavaFX dashboard, custom graph editor/live scenario builder, recent history and CSV export. | Web/cloud deployment or a full historical database export interface. |
| Local MySQL persistence with embedded H2 fallback. | Centralized production database, multi-user security model or backup service. |

## 2. Technology stack

| Technology / area | Use in this project | What you should be able to explain |
| --- | --- | --- |
| Java 21 | Models trains and simulation logic; thread-safe state, executors, locks, conditions and records/modern language APIs. | Objects, collections, exceptions, generics, synchronization, executor services and interruption. |
| JavaFX | Desktop home page, dashboard, graph lab, train/resource visualizations, buttons, forms and event log. | Event-driven UI, JavaFX application thread, controls, layouts and CSS. |
| Maven Wrapper | Reproducible build/test commands through `mvnw` / `mvnw.cmd`. | `pom.xml`, dependencies, lifecycle goals and test execution. |
| JDBC | Persistence abstraction and parameterized SQL statements. | Connections, prepared statements, result sets, transactions/errors. |
| MySQL + H2 | Local MySQL for normal cross-platform persistent history; embedded H2 fallback when no saved/explicit JDBC config exists. | Backend selection, schema, persistent local data and credential hygiene. |
| JUnit 5 | Unit and integration coverage for ownership, deadlock, recovery, custom routes, database service and CSV export. | Arrange–Act–Assert, assertions, repeatable scenarios, test limitations. |
| Shell / PowerShell | OS-specific local MySQL setup/launcher; native app packaging scripts. | Why the platform script is separate from Java code and which steps may require permissions. |

## 3. Architecture and key classes

Architecture diagram derived from the responsibilities and dependencies in the source snapshot.

The UI is intentionally separate from the simulation logic. `SimulationManager` coordinates workers and lifecycle; `ResourceManager` is the authority for resource ownership; `DeadlockDetector` detects cycles from a graph snapshot; `RecoveryEngine` applies a recovery policy; `EventLogger` fans events out to the UI and persistence sink. Database access is behind service/logger classes so train movement does not issue SQL directly.

| Class / package | Responsibility | Where to read it |
| --- | --- | --- |
| `model/Train` | Train identity, priority, route, route index, current segment, status, wait and delay timers. Mutations/getters are synchronized. | `src/main/java/com/railsafe/jx/model/Train.java` |
| `model/TrackSegment`, `Signal` | Represents the resource segment and its simplified signal/occupancy state. | `.../model/TrackSegment.java`, `Signal.java` |
| `simulation/TrainTask` | Worker code that advances one train through route steps, waits for reservations and checks pause/delay/interrupt states. | `.../simulation/TrainTask.java` |
| `simulation/SimulationManager` | Creates scenarios, manages worker pool/monitor, pause/resume/reset, deadlock rechecks and counters. | `.../simulation/SimulationManager.java` |
| `resource/ResourceManager` | Maps segments to owners, tracks pending requests, exposes wait-for/ownership snapshots, makes safe transfers and wakes blocked workers. | `.../resource/ResourceManager.java` |
| `deadlock/DeadlockDetector` | DFS-based directed cycle detection; extracts cycle members from a back-edge path. | `.../deadlock/DeadlockDetector.java` |
| `recovery/RecoveryEngine` | Attempts configured strategy with logged outcomes and a safe fallback policy. | `.../recovery/RecoveryEngine.java` |
| `util/EventLogger` and `EventSink` | Thread-safe recent event journal and optional persistence sink/listeners. | `.../util/EventLogger.java`, `EventSink.java` |
| `database/AsyncDatabaseLogger` | Serial background writer for database events/run lifecycle. | `.../database/AsyncDatabaseLogger.java` |
| `database/JdbcDatabaseService` | Selects configured backend, creates tables, writes run/event/recovery rows and queries recent runs. | `.../database/JdbcDatabaseService.java` |
| `ui/RailSafeApplication` | Welcome page, dashboard, simulation controls, history and CSV export. | `.../ui/RailSafeApplication.java` |
| `ui/GraphLabWindow` | Compare examples, graph editor and custom live simulation builder. | `.../ui/GraphLabWindow.java` |

## 4. The algorithms

### 4.1 Resource ownership and train movement

A track/platform is an exclusive resource. `ResourceManager` owns the authoritative map from resource ID to train ID. Before advancing, a train asks the manager to reserve the next segment. If the segment is already owned by another train, the request is recorded as pending and the train waits. Ownership and pending-request mutations take place under a `ReentrantLock`; a `Condition` wakes waiting workers when resource state changes. When a train moves, the code maintains the logical train position and resource ownership together.

| Core invariant / At most one train owns an exclusive resource at any point. A train must not be shown as occupying a segment that it does not own. Recovery reserves a safe target before releasing the old segment; it does not simply delete ownership to clear the graph. |
| --- |

### 4.2 Wait-for graph

The resource manager builds the wait-for graph from actual pending requests. If train A is waiting for a segment whose current owner is train B, it adds directed edge A → B. The graph contains train IDs as nodes and current waiting dependencies as edges. An idle train that is not waiting for an occupied resource does not add a wait-for edge.

Example: two trains form a circular wait.

### 4.3 DFS cycle detection

`DeadlockDetector.findCycles` runs depth-first search over a snapshot. The implementation uses three colors: white (unvisited), gray (on the current DFS path), and black (fully processed). When DFS reaches a gray node, it has found a back edge; the detector extracts the path segment from that node to the end of the current path as a cycle. Cycle signatures are used to avoid duplicate reports.

| for each node in graph: /     if node is unvisited: /         DFS(node) /   / DFS(node): /     mark node GRAY and push it on current path /     for each neighbour: /         if neighbour is WHITE: DFS(neighbour) /         else if neighbour is GRAY: extract cycle from path /     pop node from path and mark it BLACK |
| --- |

Time complexity is O(V + E) for V graph vertices and E directed edges under the usual adjacency-list model. The actual graph is derived from a locked snapshot so the DFS does not hold the resource manager lock for the entire traversal.

### 4.4 Recovery strategies

| Strategy | What it does | Safety idea / limitation |
| --- | --- | --- |
| `REROUTE` | Finds a predefined alternate route from the train’s current position to its destination and replaces the route; cancels its old pending request. | Applicable only when a matching alternate route exists. If no route fits, it is logged as a failed attempt and a fallback is tried. |
| `DELAY` | Transfers a train to its dedicated virtual siding, marks it delayed, waits for a configured interval, then allows it to resume. | The siding is reserved and the train’s logical position is updated before the old segment is released. |
| `RELEASE` | Attempts controlled rollback to a previously visited segment if it is free; otherwise transfers to the safe siding. | It is not an unsafe unconditional release; target resource ownership is acquired before current ownership is relinquished. |
| `AUTO` | Attempts reroute, then controlled rollback/release, then delay according to the engine. | Records failed attempts and final result so the event trail explains why a method did or did not apply. |

The recovery ordering uses train priority (the code defines larger priority values as higher priority, so the lower-numbered train is generally considered first as the recovery candidate) with accumulated wait as a tie-breaker in the comparator. Avoid paraphrasing priority as “higher number is less important”; it is the opposite in this implementation.

### 4.5 Simulation lifecycle

1.  Choose or construct a scenario (normal, deterministic deadlock demo or custom graph/network).

2.  `SimulationManager` creates fresh train/resource state and creates a fixed thread pool large enough for the current train set (minimum three worker threads).

3.  Each `TrainTask` advances its train one segment at a time using `ResourceManager` to reserve/release resources.

4.  A scheduled monitor periodically snapshots current waiting relationships and invokes the detector.

5.  If a cycle appears, the configured recovery policy is attempted and events are written to the event logger.

6.  The monitor rechecks the graph; the dashboard retains the last cycle as a post-mortem display after resolution.

7.  On completion or reset, worker/monitor/recovery executors are stopped and run outcome is finalized for persistence.

## 5. Concurrency design: what to explain

| Question / concern | Project answer |
| --- | --- |
| Race condition | Two trains may ask for the same segment close together. The locked ownership map serializes acquisition decisions so both cannot become owners at the same time. |
| Why a single resource manager? | It provides one authority for owners, pending requests and transitions. This keeps the graph and displayed resource occupancy consistent. |
| Why `Condition`? | A waiting worker uses a condition wait that releases the `ReentrantLock`, allowing other workers and the detector to observe/change state. `signalAll` wakes eligible waiters when ownership/request state changes. |
| Why graph snapshots? | DFS can take time. Copying the wait-for graph under lock and traversing the immutable snapshot avoids holding the ownership lock for the entire DFS. |
| Why safe transfer before release? | The ownership map must remain consistent with the logical train position. The code reserves the destination, changes the train position and then releases the old resource within one locked transition. |
| Why async JDBC? | Database I/O can be slow. A single background writer consumes queued log events so SQL work does not block train-worker steps; persistence failures should be logged without corrupting simulation state. |
| What does pause/reset mean? | Lifecycle controls are coordinated through the manager/resource manager. Reset stops old executors, creates a new scenario state and clears stale deadlock display/counters. |

## 6. Database and event trail

The persistence layer records a run summary and related detailed events. The Java database service accepts explicit environment settings first, next reads a per-user `mysql.properties` file created by the OS setup script, and finally uses embedded H2 if no saved JDBC URL is present. Required tables are created if missing. Local MySQL setup scripts also create the dedicated database and app account. The async writer keeps persistence outside the train movement critical path.

Simplified data model: one run can have many train events, deadlock events and recovery actions.

| Table | Important fields / semantics |
| --- | --- |
| `simulation_runs` | `run_id` primary key, scenario name, started/ended timestamps and outcome. |
| `train_events` | Foreign key `run_id`, timestamp, train ID, event type and message. Many operational events belong to a run. |
| `deadlock_events` | Foreign key `run_id`, detected time, cycle member IDs, resolved flag and resolution summary. |
| `recovery_actions` | Foreign key `run_id`, action time, affected train, strategy, reason and outcome. |

Prepared statements are used for SQL values. Table names and schema are defined by the project. The schema uses cascading deletion from a run to its event rows; users should avoid manually deleting the database if they want to keep history.

## PART II — DEMONSTRATE AND VALIDATE

## 7. User interface and demonstration

### 7.1 Main user flow

1.  Welcome home → Open Control Center.

2.  Start normal traffic and point out train statuses, track ownership, signals, counters and the event log.

3.  Select `REROUTE`; click Deadlock Demo; wait for `DEADLOCK_DETECTED` and explain the cycle `T1 → T2 → T1`.

4.  Repeat with `DELAY` and `RELEASE`; compare the selected action, reason, success/failure and fallback logs.

5.  Open History to inspect run ID/scenario/outcome. Close and relaunch to verify persistence.

6.  Export the recent event snapshot to UTF-8 CSV and open it in a spreadsheet editor.

7.  Open Graph Lab and show the cyclic example, the acyclic chain and a custom live route network.

### 7.2 Graph Lab vs live simulation

The Graph Editor is a graph algorithm demonstration: nodes can be TRAIN, TRACK or PLATFORM, and the editor adds directed edges. The Live Simulation builder is different: it also requires undirected physical connections between route resources and route definitions for each train. A visually circular drawing is not automatically a live deadlock; the detector sees cycles created by actual pending requests against resources owned by other trains.

### 7.3 Five-to-seven-minute viva demo script

| Time | Action | What to say |
| --- | --- | --- |
| 0:00–0:40 | Introduce RailSafe-JX and the exclusive resource problem. | “Each train is a concurrent worker; each track segment has at most one owner.” |
| 0:40–1:30 | Start normal traffic. | Show movement, status, and the resource manager’s ownership rule. |
| 1:30–2:20 | Choose REROUTE and click Deadlock Demo. | Explain T1 waiting for B held by T2, and T2 waiting for A held by T1. |
| 2:20–4:10 | Run demo with REROUTE, DELAY and RELEASE. | Show action logs, fallbacks and graph recheck rather than claiming a fixed timing. |
| 4:10–5:00 | Explain lock/Condition and snapshot-based DFS. | Mention exclusivity, wait-for graph and O(V+E) cycle detection. |
| 5:00–5:45 | Open History and export CSV. | Explain asynchronous JDBC and local MySQL/H2 selection. |
| 5:45–6:20 | State limitations and future work. | Be clear that this is a simulator, not a real train-control system. |

## 8. Testing and evidence

The current source snapshot contains 24 JUnit `@Test` methods across database, resource, recovery, detector, CSV export, scenario factory and simulation test classes. This is a source-based inventory, not a statement that all tests passed on the reader’s machine. Run `./mvnw clean test` on macOS or `mvnw.cmd clean test` on Windows and base any result claim on the actual Surefire output.

| Test area / file | What it checks |
| --- | --- |
| `ResourceManagerTest` | A segment cannot have two owners; waiting acquisition proceeds after owner release. |
| `DeadlockDetectorTest` | Finds a two-train cycle, does not label an ordinary chain a deadlock, and excludes a non-cycle tail. |
| `RecoveryEngineTest` | Alternate route, reserved siding transfer, safe rollback, fallback and failed-safe behavior. |
| `SimulationManagerTest` | Deterministic demo detects/resolves a cycle and normal scenario finishes trains. |
| `CustomScenarioFactoryTest` | Builds custom resources/sidings and rejects missing resources, disconnected pairs or invalid alternate destination. |
| `CustomSimulationIntegrationTest` | User-defined crossed routes create/resolve a real cycle; disconnected/no-cycle scenario completes. |
| `JdbcDatabaseServiceTest`, `AsyncDatabaseLoggerTest` | Schema initialization and persistence, event queue behavior and clear missing-config behavior. |
| `EventCsvExporterTest` | UTF-8 output, header and correct escaping for commas/quotes/newlines, empty export. |

### 8.1 Evidence to collect for the report

- Screenshot: welcome page and dashboard.

- Screenshot: normal traffic with train state/status visible.

- Screenshot: deadlock event and the retained last-cycle panel before/after resolution.

- Screenshot: custom Graph Lab deadlock graph and deadlock-free chain.

- Screenshot: History view with a real local run ID and outcome (mask sensitive user directories if shown).

- Terminal screenshot or copied output from `clean test` showing total tests/failures/errors as actually run.

- CSV export sample with representative rows and no fabricated result counts.

- Native app screenshot after the package has been built and run on each target OS, if claiming cross-platform packaging is verified.

## PART III — WRITE THE PROJECT REPORT

## 9. Draft abstract (edit before submission)

RailSafe-JX is a desktop demonstrator for studying concurrent resource allocation and deadlock resolution in a simplified railway network. Multiple trains execute as independent Java tasks and request exclusive track or platform resources along predefined routes. A thread-safe resource manager maintains ownership and pending requests. When a train waits for a resource currently owned by another train, a directed edge is added to a wait-for graph. A depth-first-search cycle detector identifies circular waits, after which a recovery engine attempts a configured policy: alternate-route selection, temporary transfer to a reserved siding, or controlled rollback with a safe-siding fallback. A JavaFX interface visualizes train state, resource occupancy, signal indicators, deadlock cycles and recovery events. An event logging layer supports an in-memory journal, UTF-8 CSV export and JDBC persistence to local MySQL or an embedded H2 fallback. JUnit 5 tests cover resource ownership, cycle detection, recovery behaviors, custom scenario validation, simulation outcomes, persistence and CSV escaping. The project focuses on explainable concurrency behavior and repeatable conflict demonstration. The railway topology, signals, routes and sidings are intentionally simplified; the software is intended for education and demonstration and is not suitable for real railway control. Evaluation should report only test results and measurements collected from actual runs.

## 10. Chapter-by-chapter report writing guide

### Chapter 1 — Introduction

Suggested opening: “Concurrent systems coordinate multiple actors that share limited resources. A simplified railway network illustrates this challenge because a track segment must not be occupied by two trains at the same time. If each train keeps its current segment while waiting for a resource owned by another train, their requests can form a circular wait. RailSafe-JX models this behavior so that ownership, deadlock detection and recovery can be observed and tested.”

Include background, problem statement, motivation, objectives, scope, assumptions and organization of the report. Explain the term resource as a track/platform segment and the term process/task as a train worker.

### Chapter 2 — Existing and proposed systems

Review relevant approaches to resource allocation and deadlock handling using sources you have actually read. General background may discuss deadlock prevention, avoidance, detection and recovery. Then describe what this project demonstrates: runtime resource ownership, a live wait-for graph, DFS cycle detection, logged recovery policies, visualization and local persistence. Do not claim that the project outperforms an existing railway system unless you designed a measured comparison.

### Chapter 3 — Requirements and system design

- Functional requirements: run/reset/pause/resume trains; reserve/release exclusive segments; build a wait-for graph; detect cycles; apply a recovery action; show logs/status; save history; export recent events; build custom graph scenarios.

- Non-functional requirements: thread safety, understandable event traces, responsive UI, stable reset/shutdown and reproducible test scenarios.

- Design figures: architecture diagram, class diagram, normal movement sequence diagram, deadlock/recovery sequence diagram, simplified data model.

- Assumptions: fixed/synthetic topology; finite predefined routes; single-owner track/platform segments; virtual siding per train; simplified signals and recovery.

### Chapter 4 — Methodology and algorithms

Explain the locked ownership map, pending request tracking, graph construction, DFS cycle identification and recovery ordering. Include pseudocode and an example `T1 → T2 → T1`. Discuss why the detector uses a snapshot and why safe transfer reserves the destination before releasing the old segment. Include the time complexity O(V+E) for DFS and clarify that this is the complexity of cycle detection, not the entire simulation.

### Chapter 5 — Implementation

Map implementation details to packages: `model`, `simulation`, `resource`, `deadlock`, `recovery`, `util`, `database`, `report` and `ui`. Describe the JavaFX dashboard, Graph Lab, history modal and CSV exporter. Describe the database’s four tables and its local MySQL/H2 configuration. Include the actual software version and dependency versions from `pom.xml` rather than using generic version numbers.

### Chapter 6 — Testing and results

Create a test case table that states input/scenario, expected behavior, actual behavior and evidence. Run tests on your machine and fill the actual pass/fail counts. Demonstrate at least: normal traffic completes; T1/T2 deterministic demo detects and resolves a cycle; an ordinary chain is not a cycle; recovery fallback is recorded when an alternate route is unavailable; and the History record remains after restart when the chosen database is persistent.

| Do not invent numerical results / If you have not measured deadlock detection latency, average wait, number of completed trains or recovery time, leave those as unmeasured or run a documented experiment. Use actual terminal/test output and screenshots. |
| --- |

### Chapter 7 — Conclusion and future scope

Draft conclusion: “RailSafe-JX demonstrates how exclusive resource allocation, concurrency control, wait-for graphs and cycle detection can be combined in a repeatable simulation. The implementation separates train workers from resource ownership, identifies cycles in a snapshot and records recovery attempts and outcomes. Its JavaFX dashboard and local event persistence make the process observable for teaching and debugging. The current scope is intentionally limited to a simplified topology and predefined recovery options; it is not a replacement for certified railway planning or safety systems.”

Possible future work: configurable scenario files, graph-based route cost selection, richer historical database queries/exports, randomized concurrency stress tests, metrics for wait time/recovery latency, resource allocation trace visualization, improved automated package testing and clean-machine installer tests. Present these as future work, not completed features.

### 11. Report formatting and submission checklist

- Use the institution’s title page, certificate/declaration and formatting rules; fill in your name, registration number, guide and academic year yourself.

- Add a real literature review with references that you have read. Cite every external algorithm, definition, figure and borrowed idea required by your university standard.

- Number diagrams and tables; refer to them in the text and make all labels readable.

- Add actual screenshots, actual test output and actual database persistence observations.

- Distinguish implemented features from future work and explicitly state the simulation limitations.

- Run spelling and consistency checks for class names, project title, database tables and strategy labels.

## PART IV — VIVA AND PLACEMENT INTERVIEW

## 12. Short project pitches

### 30-second version

“RailSafe-JX is a Java 21 concurrency project that simulates multiple trains competing for exclusive track resources. It builds a live wait-for graph, detects circular waits with DFS, and demonstrates recovery using rerouting, a reserved siding delay or controlled rollback. JavaFX visualizes the simulation, and JDBC saves run and recovery history to local MySQL with an embedded H2 fallback. The key focus is thread-safe resource ownership, explainable deadlock handling and repeatable tests.”

### 90-second version

“The core problem is circular waiting: one train owns A and requests B while another owns B and requests A. Each train is an independent worker in a thread pool. `ResourceManager` serializes ownership changes with a lock, records pending requests and derives a train-to-train wait-for graph from actual resource owners. A DFS detector finds a cycle by identifying a back edge into the current search path. `RecoveryEngine` tries a selected policy and logs failures as well as success—rerouting if a valid alternate route exists, moving a train to an exclusive virtual siding for a delay, or rolling back to a safe previous resource with siding fallback. A JavaFX dashboard and Graph Lab make the behavior visible. The logging path can persist run summaries and events through JDBC asynchronously. It is a teaching demonstrator with simplified topology, not railway-grade control.”

## 13. Technical interview questions and model answers

### Q1. What is a deadlock?

A deadlock is a state where a set of concurrent actors cannot proceed because each is waiting for a resource held by another actor in the set. This project demonstrates a circular wait over exclusive track resources.

### Q2. What are the four Coffman conditions?

Mutual exclusion, hold-and-wait, no preemption and circular wait. They are the classic conditions associated with deadlock. The simulator particularly makes exclusive ownership, held resources, blocking requests and circular waiting visible.

### Q3. What is a wait-for graph?

A directed graph whose nodes are trains and whose edge Ti → Tj means Ti is currently waiting for a resource owned by Tj. In this single-owner resource model, a cycle indicates a deadlock.

### Q4. Why use DFS?

DFS is straightforward for detecting directed cycles using the current path. A back edge to a gray/active node identifies a cycle. With adjacency lists, the traversal is O(V+E).

### Q5. How do you avoid two trains reserving the same track?

All acquisition decisions pass through `ResourceManager`, whose ownership map is protected by a `ReentrantLock`. It checks the owner and records at most one owner for a resource before returning success.

### Q6. Why use a Condition instead of busy waiting?

A worker can wait efficiently until resource state changes. `Condition.await()` releases the associated lock while waiting, so other threads can acquire the lock, release resources or inspect/update state; `signalAll()` wakes waiting tasks when the condition may have changed.

### Q7. Why is the graph detector separate from ResourceManager?

The resource manager owns live synchronized state; the detector is a smaller algorithm component that processes a graph snapshot. This separation makes the graph algorithm easier to test without coupling it to UI or resource mutation.

### Q8. Why is a snapshot used for DFS?

Traversing under the resource lock for a long time would block acquisition/release. The manager builds a consistent snapshot while protected, then DFS traverses that snapshot outside the critical section.

### Q9. Explain the recovery policies.

REROUTE replaces the remaining route if a configured alternative matches current position and destination. DELAY transfers a train to a reserved virtual siding and resumes later. RELEASE attempts a controlled rollback to a free prior route segment, or a safe-siding fallback. AUTO attempts strategies in a defined order and logs results.

### Q10. Why reserve the new resource before releasing the old one?

It preserves the ownership/position invariant. If the destination cannot be secured, the train should not lose its current safe position. The transition is performed under the resource manager lock.

### Q11. What happens if rerouting is impossible?

The attempt is recorded as not applicable and the recovery engine tries the next strategy in its fallback sequence. It should not invent a route or silently drop the pending request and ownership state.

### Q12. How do you keep the UI responsive?

The simulation runs in worker executors, and persistent database writes use a dedicated asynchronous writer. JavaFX view updates are scheduled through the JavaFX application thread. The UI reflects manager state instead of performing train movement itself.

### Q13. Why asynchronous database logging?

JDBC calls may block on I/O. A dedicated serial writer decouples SQL persistence from the train worker threads and maintains an ordered write queue. A persistence failure should not turn into an ownership change.

### Q14. What is JDBC?

Java Database Connectivity is the API used to connect Java to relational databases, execute SQL and read results. This project uses connections, prepared statements and result sets behind `JdbcDatabaseService`.

### Q15. Why PreparedStatement?

It binds values separately from SQL text, is safer than concatenating user-controlled values into SQL and makes parameter handling clearer.

### Q16. How does the app choose MySQL or H2?

Explicit `RAILSAFE_DB_URL` settings take priority. If they are absent, the service checks the user’s `.railsafe-jx/mysql.properties`. If no URL is saved, it uses an embedded H2 file in the user’s home folder. Platform launch scripts configure local MySQL and set the variables for that process.

### Q17. What is the database schema?

`simulation_runs` stores a run summary; `train_events` stores timestamped events; `deadlock_events` stores cycle detection/resolution records; `recovery_actions` stores strategy, reason and outcome. The child event tables reference the run ID.

### Q18. How is the CSV export different from History?

History queries recent run summaries from the configured database. The CSV exporter exports the recent in-memory event snapshot retained by EventLogger (maximum 2,000 events), not every database row across all previous runs.

### Q19. What does Graph Lab test?

It can compare a directed cycle with a deadlock-free chain, edit arbitrary directed resource-allocation graphs, and build a custom live scenario. The live scenario requires physical adjacency connections as well as train routes; the visual graph alone does not cause a live deadlock.

### Q20. How do you test deadlock detection?

A unit test feeds a known cyclic graph and asserts the cycle is found, then feeds an acyclic chain and asserts none is found. Integration tests create crossed routes and verify the live simulation detects and resolves the real waiting cycle.

### Q21. What are the key limitations?

The topology and signals are simplified, alternative routes are predefined, the sidings are virtual resources, and the model does not optimize timetables or certify safety. It is not suitable for operational railway decisions.

### Q22. How would you improve the project?

Add scenario import/export, configurable route cost selection, more historical query/export controls, randomized concurrency tests, performance metrics, better logging/observability and clean-machine packaging tests. A production railway context would require domain experts, formal safety assurance and certified infrastructure—not just feature additions.

### Q23. What makes this a concurrency project?

Independent train tasks execute concurrently, compete over shared resources, wait using conditions, and can create a real circular wait. Correctness depends on synchronization, safe ownership mutation and lifecycle management.

### Q24. What is the most important invariant?

Each exclusive resource has at most one owner; every active train’s current segment agrees with the ownership map. Recovery must update the logical train position and resource map consistently.

### Q25. What does `AUTO` mean in code?

The current engine tries reroute, then controlled release/rollback, then delay. A chosen strategy can also have a fallback; actual action and result are written to the log.

### Q26. Why do you use a deterministic deadlock demo?

It makes the first two resource reservations predictable by synchronizing those trains, so the cycle is repeatable for tests and viva demonstrations instead of relying on lucky thread scheduling.

### Q27. What should you say about test results?

Show the output from `./mvnw clean test` or `mvnw.cmd clean test` on the actual version being demonstrated. Explain which behaviors are covered, but do not claim all tests pass or give test counts unless the output verifies them.

### Q28. Is this production-ready railway control software?

No. It is an educational simulator intended to demonstrate concurrency and deadlock handling. It lacks real track data, certified signalling/interlocking, safety validation and operational deployment requirements.

## 14. Behavioral / project-ownership questions

### “What was the hardest technical problem?”

A grounded answer is to discuss maintaining consistent resource ownership while several train threads wait, while detection and recovery can also mutate requests. Give a specific case you personally debugged and show the code/test evidence.

### “What did you learn?”

Concurrency is not only launching threads. The shared state must have one owner, waits must release locks, recovery must preserve invariants, and the UI/database must not become a hidden bottleneck.

### “What would you do differently?”

Describe one honest improvement, such as adding randomized stress tests, richer metrics, more configurable routes or a fuller history export. Explain a first implementation step rather than claiming it is already done.

### “How do you prove it works?”

Show unit tests for the detector/resource manager, integration test for a real crossed-route deadlock, actual `clean test` output, and a demo followed by a History restart check. A screenshot by itself is not sufficient proof of all behavior.

### “What is your individual contribution?”

Use only tasks you actually performed. The implementation guide can help you explain modules, but do not claim authorship of components you did not personally design, code, test or understand.

## 15. Résumé / portfolio wording

| Suggested résumé entry — edit to match your actual contribution / RailSafe-JX | Java 21, JavaFX, Concurrency, JDBC, MySQL/H2, JUnit 5 / • Built a concurrent railway-resource simulation with exclusive resource ownership and route progression across independent train workers. / • Implemented dynamic wait-for graph construction and DFS-based cycle detection, with logged reroute, siding-delay and controlled rollback/fallback strategies. / • Developed a JavaFX dashboard and Graph Lab for live status, custom scenarios, recovery events, history and CSV export. / • Added JDBC persistence with local MySQL setup scripts and an embedded H2 fallback; wrote JUnit tests for resource ownership, deadlock detection, recovery, scenario validation and CSV behavior. |
| --- |

Do not say “optimized by X%”, “zero deadlocks”, “railway-safe”, “all tests passed” or “CI is green” unless you have measured and verifiable evidence for that exact claim.

## PART V — LEARN THE CODE

## 16. Recommended source-reading order

| Step | File(s) | Questions to answer while reading |
| --- | --- | --- |
| 1 | `Train.java`, `TrackSegment.java`, `TrainStatus.java`, `Signal.java` | What data defines a train? How is its current segment/route index changed? Which state methods are synchronized? |
| 2 | `ResourceManager.java`, `AcquireResult.java` | Where is ownership stored? When is the lock acquired/released? How are waits, cancellation and ownership snapshots handled? |
| 3 | `TrainTask.java`, `SimulationManager.java` | How does a train make progress? Who starts the executor, monitor and deterministic barrier? What happens on reset/shutdown? |
| 4 | `DeadlockDetector.java`, `DeadlockCycle.java` | How are graph nodes traversed? What does a gray-node/back-edge mean? How are cycles deduplicated? |
| 5 | `RecoveryEngine.java`, `RecoveryStrategy.java`, `RecoveryAction.java` | What order does AUTO use? How are failed attempts and fallbacks logged? What makes transfer safe? |
| 6 | `EventLogger.java`, `EventSink.java`, database classes | How are events queued? How do JDBC/H2/MySQL settings get chosen? What is stored per run? |
| 7 | `RailSafeApplication.java`, `GraphLabWindow.java` | Which UI controls invoke manager methods? How does the UI display the last cycle? How is Graph Lab’s custom network validated? |
| 8 | `src/test/java/...` | Which invariants are tested? Which test would fail if ownership were incorrect? How can you add a new test before a code change? |

## 17. Seven-session learning plan

| Session | Focus | Practice task / self-check |
| --- | --- | --- |
| 1 — Project map | Folders, Maven, application entry point, JavaFX UI flow. | Draw the source tree and explain how Home opens Control Center and Graph Lab. |
| 2 — Java concurrency | ExecutorService, threads, `ReentrantLock`, `Condition`, interruption and race conditions. | Trace an `acquire` call for a free segment and for an occupied segment. |
| 3 — Graph algorithm | Wait-for graph and DFS colors/path tracking. | By hand, classify `A→B→C`, `A→B→A`, and a cycle with a tail. |
| 4 — Recovery | REROUTE, DELAY, RELEASE/AUTO order, safe transfer invariant. | Remove the alternate route in a test scenario; predict and verify the fallback log. |
| 5 — Persistence | Event sink, asynchronous queue, JDBC, table relationships, H2 vs MySQL. | Run a demo, query a run summary, restart the app, verify history. |
| 6 — Test and debugging | Read JUnit tests and Maven output. | Run clean test; write one test for an edge case; do not skip a failing assertion. |
| 7 — Viva/review | Report narrative, limitations, project pitch and future improvements. | Deliver a 5-minute demo and answer five questions without reading the document. |

## 18. Quick learning checks

Check: If T1 waits for an empty segment, is there an edge to another train?

Answer: No. The wait-for edge is created only if the requested resource has another train as current owner.

Check: Does an ordinary chain T1→T2→T3 necessarily mean a deadlock?

Answer: No. It has no cycle; the detector test explicitly checks an ordinary waiting chain.

Check: Why can a graph cycle disappear quickly in the dashboard?

Answer: Recovery may clear the active waiting graph promptly; the UI keeps the last detected cycle as a resolved post-mortem item.

Check: Why can History show persistent data while CSV only includes recent events?

Answer: History queries database run summaries. CSV export snapshots the in-memory recent-event journal.

Check: What should be checked after changing a train route?

Answer: The route resource IDs must exist, every adjacent pair must be physically connected, and an alternate route should start at the current segment and reach the same destination.

Check: What is the difference between a Graph Editor arrow and a physical connection?

Answer: A directed graph edge is used to express graph relationships in the editor. A physical connection is an undirected adjacency relationship used to validate/live-run routes in the custom simulator.

Check: What should you do if a strategy cannot be applied?

Answer: Read the event log: the engine records failed attempts and tries the fallback policy; explain why that selected action was not applicable.

Check: How do you establish persistence rather than just a visible run?

Answer: Close and reopen the program with the same selected database and confirm the run still appears in History; also inspect the actual database if needed.

## 19. Final readiness checklist

- I can explain the problem, project objective and scope without reading notes.

- I can draw and explain T1 → T2 → T1 and tell why an acyclic chain is different.

- I can locate `ResourceManager`, `DeadlockDetector`, `RecoveryEngine`, `SimulationManager` and `JdbcDatabaseService` in code.

- I can explain why the ownership lock and `Condition` are needed.

- I can run `clean test` on the correct project version and describe the actual output.

- I can demonstrate normal traffic, Deadlock Demo, a recovery policy, History and CSV export.

- I can explain when the app uses local MySQL versus H2 and where credentials are saved.

- I can describe the limitations honestly and propose an achievable next improvement.

- I have added actual report screenshots, test evidence, and properly cited references rather than invented results.

| Last reminder / The strongest viva answer is one you can connect to a real source file, a test or an observed run. Use this handbook to prepare, then reopen the code and verify each claim you plan to make. |
| --- |
