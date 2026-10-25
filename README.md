# RailSafe-JX — Setup & User Manual

**Version:** 1.3.5  
**Purpose:** Cross-platform Java desktop demonstrator for concurrent railway resource allocation, deadlock detection, and recovery.

> **Safety notice:** RailSafe-JX is an educational simulation. It is not railway signalling, dispatching, or train-control software and must not be used for real-world operational decisions.

## 1. Choose how to run it

| Goal | macOS | Windows |
|---|---|---|
| Run with automatic local MySQL setup | `./scripts/run-railsafe-macos.sh` | `.\run-railsafe.cmd` |
| Run from Maven directly | `./mvnw javafx:run` | `.\mvnw.cmd javafx:run` |
| Run tests | `./mvnw clean test` | `.\mvnw.cmd clean test` |
| Build a native desktop app | `./BUILD-APP-MAC.command` or `bash ./packaging/scripts/build-macos.sh` | `.\BUILD-APP-WINDOWS.cmd` |
| Build a portable Windows app image | — | `.\packaging\scripts\build-windows.ps1 -PortableOnly` |

Run commands from the **project root**: the folder containing `pom.xml`, `mvnw`, `mvnw.cmd`, `src/`, and `scripts/`. If `./mvnw` or `mvnw.cmd` cannot be found, you are likely in the wrong folder.

## 2. Requirements

- JDK 21 (a full JDK, including `jpackage` for native app packaging).
- Internet access on the first Maven build so dependencies can be downloaded.
- A graphical desktop session for JavaFX.
- For automatic local MySQL: a local MySQL server and administrator access on first setup. The launcher can help install/detect MySQL according to the platform script; installation may still require confirmation or a one-time MySQL Configurator step.
- For Windows `.exe` installer creation, install WiX Toolset 3.x. Without it, use the portable build option.

Check Java:
```bash
java -version
```
On Windows PowerShell:
```powershell
java -version
```
The output should report Java 21.

## 3. Extract and open the project

1. Extract the ZIP completely.
2. Open Terminal (macOS) or PowerShell (Windows) in the inner `RailSafe-JX` folder.
3. Confirm `pom.xml`, `mvnw`, `mvnw.cmd`, `src`, and `scripts` are present.
4. If using IntelliJ IDEA, open the folder containing `pom.xml`, set Project SDK to JDK 21, and let Maven import dependencies.

## 4. Automatic local MySQL setup and launch

### macOS
Run:
```bash
chmod +x mvnw scripts/*.sh packaging/scripts/build-macos.sh
./scripts/run-railsafe-macos.sh
```
The launcher checks for explicit `RAILSAFE_DB_URL` settings first. Otherwise it uses the local MySQL bootstrap and saved per-user configuration. The setup creates/uses the `railsafe_jx` database and a restricted `railsafe_app` account; it is designed not to drop or reset an existing database. The saved configuration is under `~/.railsafe-jx/`.

If you need to run setup separately:
```bash
./scripts/setup-mysql-macos.sh
```

### Windows
Open PowerShell in the project root and run:
```powershell
.\run-railsafe.cmd
```
If PowerShell blocks the script due to execution policy, the `.cmd` launcher invokes PowerShell with a process-scoped bypass for this launch. To run setup separately:
```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\setup-mysql-windows.ps1
```
The script detects/starts a MySQL service and may use WinGet for installation if available. Depending on the computer, administrator permission and a one-time MySQL Configurator step may be needed. The per-user configuration is saved under `%USERPROFILE%\.railsafe-jx\`.

### Database behavior
- Default local MySQL database: `railsafe_jx`.
- Application account: `railsafe_app` (least-privilege account created by setup).
- Per-user settings: `.railsafe-jx/mysql.properties`.
- Embedded H2 is available as a fallback for supported configurations/tests; do not assume it is the active backend when MySQL setup succeeds.
- Explicit environment settings such as `RAILSAFE_DB_URL`, `RAILSAFE_DB_USER`, and `RAILSAFE_DB_PASSWORD` take precedence when configured.
- Do not commit credentials or `mysql.properties` to GitHub. Never paste database passwords into an issue or public log.

## 5. Manual Maven run (without platform launcher)

Use this when developing or debugging. It starts the Java application through Maven; it does not run the platform-specific MySQL bootstrap automatically.

macOS:
```bash
./mvnw clean test
./mvnw javafx:run
```

Windows:
```powershell
.\mvnw.cmd clean test
.\mvnw.cmd javafx:run
```

To build the project JAR/package:
```bash
# macOS
./mvnw clean package

# Windows
.\mvnw.cmd clean package
```
If the wrapper cannot download Maven dependencies, check internet/DNS/proxy access and retry. Do not delete the Maven wrapper files.

## 6. Build installable desktop apps

Native packages must be built on their target operating system. A Windows installer cannot be produced reliably by running the Windows packaging script on macOS, and vice versa.

If the macOS GitHub Actions job reports `Permission denied` for `build-macos.sh`, use the workflow's explicit `bash ./packaging/scripts/build-macos.sh` invocation. The builder also runs `bash ./mvnw`, so both scripts are resilient to missing executable-bit metadata in copied ZIP files.

### macOS `.app` and `.dmg`
On the Mac:
```bash
chmod +x BUILD-APP-MAC.command
bash ./packaging/scripts/build-macos.sh
```
Or double-click `BUILD-APP-MAC.command`. The output is placed under `dist/macos/`. The app bundles a Java runtime through `jpackage`, so the end user does not need to install Java separately. Local MySQL is a separate service and must be installed/configured on the user's computer if MySQL persistence is required.

### Windows portable app / installer
On Windows:
```powershell
.\packaging\scripts\build-windows.ps1 -PortableOnly
```
For an installer:
```powershell
.\packaging\scripts\build-windows.ps1 -InstallerType exe
```
Or double-click `BUILD-APP-WINDOWS.cmd`. Output is placed under `dist\windows\`. The `.exe` installer option requires the supported WiX Toolset version; if that prerequisite is absent, create the portable app image instead.

### Build both using GitHub Actions
The workflow at `.github/workflows/package-installers.yml` can build on native GitHub-hosted macOS and Windows runners. Push the complete source project, including `.github/workflows/`, to GitHub. The workflow is configured for version tags beginning with `v` and/or manual dispatch as specified in the workflow YAML. Check the actual trigger in that file. When complete, download the macOS and Windows artifacts from **GitHub → Actions → the completed workflow run**. Artifacts are not necessarily permanent GitHub Releases unless the workflow explicitly publishes a release.

## 7. Use the application

1. Launch RailSafe-JX; the welcome/home page opens.
2. Choose **Open Control Center** to open the simulation dashboard.
3. Use **Start**, **Pause**, **Resume**, and **Reset** to control a simulation.
4. Choose a recovery policy such as `AUTO`, `DELAY`, `RELEASE`, or `REROUTE` where available.
5. Click **Deadlock Demo** or **Run Deterministic Deadlock Demo** to trigger the reproducible scenario.
6. Inspect the Railway Network, Train Operations table, Active Conflicts, Resolved Deadlocks, Live Event Log, and Wait-for Graph.
7. Click **History** to inspect persisted simulation history (requires the database backend to be configured and logging enabled).
8. Click **Graph Lab** to inspect example graphs, edit a graph, and test cycle detection.
9. Click **Export CSV** to export event data. Open the exported file in a spreadsheet tool.
10. Use **⌂ Home** in the dashboard to return to the welcome page.

### Graph Lab: a basic check
- Load the deadlock example and confirm that the graph reports a cycle.
- Load the deadlock-free example and confirm that no directed cycle is reported.
- For the live deterministic demo, check the event log for detection and resolution events, and then check History if persistence is enabled.
- The editor's directed graph represents wait/ownership relationships; it is distinct from the physical track network and train routes used by the live simulation.

## 8. Verify a fresh checkout

Run the tests before building an installer.

macOS:
```bash
./mvnw clean test
```
Windows:
```powershell
.\mvnw.cmd clean test
```
A successful run should end with `BUILD SUCCESS` and zero test failures. Do not describe a test suite as passing until the command actually passes on the target environment. The project contains unit/integration tests for deadlock detection, recovery, resource ownership, simulation behavior, CSV export, and database logging.

## 9. Troubleshooting

| Symptom | What to check |
|---|---|
| `./mvnw: no such file or directory` | Change directory to the project root containing `mvnw`. |
| `mvnw.cmd` not found | Use PowerShell in the project root and confirm `mvnw.cmd` exists. |
| Maven dependency download fails | Check internet, DNS, proxy and repository access; retry after access is restored. |
| `package com.railsafe.jx.database does not exist` | Ensure you extracted the complete project ZIP, not a patch ZIP; open the root containing the complete `src/main/java` tree. |
| History says MySQL is not configured | Launch with the platform launcher, complete first-time MySQL setup, and restart. |
| MySQL service will not start | Check the service status, port 3306 conflicts, permissions, and MySQL server logs. |
| Saved credentials stop working | Rerun the platform setup script and follow its credential-repair prompts. Do not delete the database unless you intentionally want to remove history. |
| JavaFX window does not appear | Check JDK 21, graphical desktop access, and Maven dependency downloads. |
| Windows installer build fails | Run on Windows with JDK 21; install WiX Toolset 3.x for the `.exe` installer or use `-PortableOnly`. |
| Mac app is blocked by Gatekeeper | Unsigned developer builds may require the user to approve the app in macOS Privacy & Security. For public distribution, sign/notarize it. |
| CSV test fails only on Windows | Inspect `target/surefire-reports` for the exact assertion and newline/encoding details. |

## 10. Useful commands

| Task | macOS | Windows |
|---|---|---|
| Test | `./mvnw clean test` | `.\mvnw.cmd clean test` |
| Run app directly | `./mvnw javafx:run` | `.\mvnw.cmd javafx:run` |
| Automatic DB + launch | `./scripts/run-railsafe-macos.sh` | `.\run-railsafe.cmd` |
| DB setup only | `./scripts/setup-mysql-macos.sh` | `.\scripts\setup-mysql-windows.ps1` |
| Package Maven project | `./mvnw clean package` | `.\mvnw.cmd clean package` |
| Native app build | `bash ./packaging/scripts/build-macos.sh` | `.\packaging\scripts\build-windows.ps1` |

## 11. Documentation map

- [`docs/PROJECT-GUIDE.md`](docs/PROJECT-GUIDE.md) — consolidated project explanation, report-writing material, demo/validation guidance, placement interview Q&A and learning plan.
- Source code: `src/main/java/`
- Tests: `src/test/java/`
- SQL schema: `database/schema.sql`
- CI and packaging workflow: `.github/workflows/`
- App packaging scripts: `packaging/scripts/`
