# SimpleCoil Modernization Roadmap (after V2 refactor)

Status: **Stages 0–4 DONE (2026-10-13), device checks included; Stage 5 done in code, its device checks still to run.** All four documents are approved and their decisions resolved.
Branch audited: `V2-refactor` @ `b460c02`. The on-device checklists in `REFACTOR_DESIGN.md` §6.5 and
`STATE_CONSOLIDATION_DESIGN.md` §8.6 have been completed.
Date: 2026-10-13

This page orders the work in four design documents. Map features are out of scope, because new map work is coming;
where a document touches the map, it says so.

| Document | Covers |
|---|---|
| `NETWORK_STACK_DESIGN.md` | Server and client I/O bugs, the event bus that replaces broadcasts, Kotlin conversion and a typed wire model for `TcpServer` / `TcpClient` / discovery, network logging |
| `DEDICATED_SERVER_DESIGN.md` | The server's lifecycle (foreground service, server-side game clock), the server screen in Kotlin, and the dialogs and scoreboard shared with the play screen |
| `PLATFORM_COMPAT_DESIGN.md` | Foreground service types, large screens and orientation, edge-to-edge, predictive back, backup rules, theme and widgets, the lint cleanup, R8 |
| `APP_CONTAINER_DESIGN.md` | Replacing the repository singletons with constructor injection from one app container, applied during the rewrites above |

Shared rules: the v07 wire protocol and the BLE protocol are frozen. Bugs are fixed in their own commits, never inside
moves. Every commit keeps `./gradlew test` and `assembleDebug` green. Network-, BLE- and lifecycle-affecting steps are
verified on devices before the next step.

---

## Order

### Stage 0 — hotfixes (small, independent; ship first)

| Fix | Document | Why first |
|---|---|---|
| Configuration change on the dedicated server kicks every player (H1) | Dedicated server §4 | A dark-mode toggle or window resize ends the game |
| `TcpServer` as a foreground service (H2) | Dedicated server §4 | A minimized server can be frozen |
| GPS relay switched off by `onPause` (H3), null-server crashes (H4), empty-field crash in player settings (H5) | Dedicated server §4 | Crashes and silent feature loss |
| `TcpClient` foreground service type (after the logcat check) | Platform §2 | Probably never foreground on Android 14+ |
| `Frame` parser + exception containment (N5) | Network §5.1–5.2 | One malformed frame crashes the server; `VERSIONERROR` crashes the client |

**Done (2026-10-13), one commit per fix: H1 `d35c022`, H2 `4291e3e`, H3 `b5343a1`, H4 `d530ce8`, H5 `94f5fbd`,
`TcpClient` type `505db43`, `Frame` `c6363fc`, containment `dee497a`.** 459 unit tests pass and `lintDebug` has 0
errors.

- The `TcpClient` logcat check was skipped (no Android 14+ device was attached). The type is required from
  `targetSdk` 34 regardless.
- `TcpClient`'s per-frame handling moved into `handleFrame` so the `VERSIONERROR` path is unit-tested. An
  `ELIMINATED` without a valid id is now logged and skipped instead of crashing.
- No server message still throws after the `Frame` fix, so the §5.2 containment is defense in depth: the hostile-frame
  loopback test passes on the code before it too.
- **Added during Stage 0 (`29c678d`):** Android 17 makes local network access a runtime permission
  (`ACCESS_LOCAL_NETWORK`). Without it the dedicated server's traffic failed with EPERM and no client could connect.
  The Dedicated server and Use network buttons now ask for it (required) and for notifications (optional) through
  `BlePermissionController`. Verified on an Android 17 device: hosting, joining a server and connecting a blaster.
- The device checks for H1–H5 (dedicated server §10 items 1, 4 and 5) were waived by the owner as low priority.

### Stage 1 — independent foundations (can run in parallel)

- **Network Phase 1**, I/O fixes in Java: `FrameReader`, per-connection writers and readers, the server dispatcher,
  the queued client close.
- **App container steps 1–3**: the container, constructible repositories with the install shim, injected Kotlin
  consumers.
- **Dedicated server steps 1–5**: the scoreboard on `RecyclerView`, `GameLimitRules` / `TeamSizes` (and the 0-limit
  fix), host-agnostic dialogs, `PlayerSettingsDialog`, settings through `UserPreferencesRepository`.
- **Platform steps 1–7**: manifest and `configChanges`, backup rules, the lint cleanup, `SwitchCompat`, R8.

**Done (2026-10-13), one commit per step; device checks passed.** 495 unit tests pass, `assembleRelease`
runs R8 with no warnings, and `lintDebug` is down from 215 warnings to 26 (0 errors).

- **Network Phase 1:** `FrameReader` and the client's liveness `d29c8b4` (N4); per-connection writer queues
  `b0f7469` (N1, N2); per-connection readers and the `sc-server` dispatcher `dbd18a0` (N2, N3, N6, N7); the client's
  queued close `7ab8180` (N11). Follow-up `43f3757`: the GPS relay ticks with a fixed delay, as the `Handler` did
  (`scheduleAtFixedRate` would burst after a frozen process; lint `DiscouragedApi`).
- **App container steps 1–3:** `AppContainer` `3061bd6`, constructible repositories and the install shim `0489a6a`,
  injected `GameViewModel` / `NetworkSessionGateway` `2e1dadb`.
- **Dedicated server steps 1–5:** `RecyclerView` scoreboard `f61b7d8` (S10); `GameLimitRules` / `TeamSizes` `b772909`
  and the S9 fix `7377818`; shared dialogs through the Fragment Result API `a287a7b`; `PlayerSettingsForm` /
  `PlayerSettingsDialog` `f5c60e2` (S8 kept, S12 fixed); server settings through `UserPreferencesRepository` `38f4704`
  (S13).
- **Platform steps 2–7** (step 1 was Stage 0): shadowed dialog style `07b0237`; manifest orientation and
  `configChanges` `c37e8ee`; backup rules `210772c`; lint cleanup `75ceead` (DefaultLocale), `49d5e43` (SetTextI18n),
  `ea0607e` (HardcodedText), `943137c` (Autofill, LabelFor, ContentDescription), `907bc6c` (UseKtx), `5563d2d`
  (PluralsCandidate), `7a48d5d` (UnusedResources), `81c9a04` (ObsoleteSdkInt, InlinedApi), `80141e8` (CheckResult),
  `c66569c` (MonochromeLauncherIcon), `c99a050` (one-offs); `SwitchCompat` `5e6a31f`; R8 `c873ec4`.

Notes:

- Running every server command on one dispatcher also serializes a team switch with the settings save that follows
  it, so S11 no longer races. `applyPlayerSettings` (dedicated server step 7) still makes it one operation.
- The play screen's numbers now use the locale's digits (a `%1$d` resource); the clock (`ClockFormat`, `Locale.US`)
  and the dialogs' number fields stay ASCII, because the fields are parsed back.
- `network_menu.xml` was never inflated; its item ids moved to `ids.xml`.
- Left for later, as planned: `Overdraw` (8) and `RelativeOverlap` (5) need visual checks; `WifiManagerPotentialLeak`
  goes with network Phase 3; `LockedOrientationActivity` is intentional; the map-owned warnings wait for the map
  work. The rest go into the Stage 5 baseline.

Device checks (passed, 2026-10-13): the network smoke test (`NETWORK_STACK_DESIGN.md` §9.1,
with items 4 and 7 for Phase 1); the BLE and network smoke tests for app container step 2 (object identity); the
dedicated-server checks for steps 1 and 3 (scroll position kept, dialogs) and §10 items 5 and 7; and the platform
checks: dialogs look identical (step 2), Chromebook resize mid-game (step 3), `bmgr` backup and restore (step 4),
switch tint (step 6), the launcher's themed icon, and the full BLE + QR + network smoke on the R8 release APK
(step 7).

### Stage 2 — events and the server clock

- **Network Phase 2**: the `ClientEvent` / `ServerEvent` buses, and the deletion of the broadcast plumbing. This
  includes `MapFragment`'s three GPS broadcasts (network Q4).
- **Dedicated server step 6**: `ServerGameClock`, hosted by the server, built on the Stage 1 dispatcher.

**Done (2026-10-13), one commit per step; device checks passed.** 512 unit tests pass, `assembleDebug` is
green, and `lintDebug` is unchanged (0 errors, 26 warnings).

- **Network Phase 2:** `EventBus`, `ClientEvent` / `ServerEvent` and the container's two buses `e8fd869`; the map's
  three GPS broadcasts (`GpsData`, `GpsSettingChanged`, `sendLocation`) `759cd09`; `TcpClient` and discovery publish
  `ClientEvent`s, and the gateway's receiver and `handedOff` are deleted `366adf9`; `TcpServer` and the beacon publish
  `ServerEvent`s, which the server screen collects `9423a85`; `NetMsg` keeps only wire strings `fef63f3`. No
  `sendBroadcast` is left in the app.
- **Dedicated server step 6:** `ServerGameClock` `f36a7e1` (S2).

Notes:

- `ServerEvent` has no `Error` or `ServerCancelled`, unlike the sketch in network §6.1. The server screen only ever
  received `ERROR` and `SERVERCANCEL` from `TcpClient`, through the shared action strings (N8), so they had no server
  producer. The time-expired toast is its own event, `TimeExpired`, and the clock reports its state with
  `ClockChanged`. `ServerCreated` carries the address the screen shows.
- `ClientEvent.Join`, `Leave` and `SameTeam` are kept but have no producer: only the dedicated server sent `JOIN` and
  `LEAVE`, and `SAMETEAM` was never sent. They can go with Phase 3's `ClientMessageHandler`.
- The Java screens collect through `EventBus.observe(owner, state, listener)`, on `Dispatchers.Main` (network §6.2).
  The server screen and the map collect while resumed, as their receivers were registered. `GameViewModel` keeps
  collecting for its whole lifetime.
- `ClockState` (dedicated server §5.2) lives with `ServerGameClock` in `network/server`. The screen draws the clock
  from its timestamps and reads it again when it binds, so a recreated screen shows the right time; `ServerSnapshot`
  (Stage 4) will carry it.
- `TcpServerLoopbackTest` now asserts `ServerEvent`s, and a time-limit test ends a game on the server with no screen.
  The client's producer tests are in `TcpClientFrameTest`. Bus ordering: `EventBusTest` (generic, so one test covers
  both buses).

Device checks (passed, 2026-10-13): the network smoke test (`NETWORK_STACK_DESIGN.md` §9.1); the
dedicated server screen's join/leave/start/end/reset updates and the GPS map (network §6.5); and dedicated server §10
item 1 for the clock (a timed game ends on time with the screen closed, and the clock is right after reopening it).

### Stage 3 — Kotlin network stack

- **Network Phase 3**: golden wire fixtures, `WireCodec`, then the class-by-class conversion, injecting
  dependencies as each class is converted (app container step 4).

**Done (2026-10-13), one commit per step; device checks passed.** 542 unit tests pass,
`assembleDebug` and `assembleRelease` (R8) are green, and `lintDebug` is down from 26 warnings to 24 (0 errors).
The network package has no Java left.

- **Step 1:** `NetMsg` → `WireProtocol`, the JSON keys → `WireKeys` `2348c5f`.
- **Step 2:** `SessionPhase` `5db420c`, `GameRoster` with injected repositories `1dd3e9b`, `PlayerRecord`
  `05def9c`, `Connection` `3e25156`.
- **Step 3:** golden wire fixtures `338ec50`: every client and server message family under
  `app/src/test/resources/wire/`, checked against the real builders (`ClientWireFixturesTest`,
  `ServerWireFixturesTest`, the latter over 127.0.0.1).
- **Step 4:** `WireCodec` and the typed messages, one family per commit: commands `e6161ec`, the client's JSON
  with the single player-settings mapping (N10) `26c0599`, the server's JSON `baac736`; follow-up `8287ee8`.
- **Step 5:** `TcpClient` → `GameClient`, `ClientConnection`, `ClientMessageHandler` and the service shell
  `bd9c9b6`.
- **Step 6:** `TcpServer` → `GameServer`, `ServerMessageHandler`, `SnapshotBuilder` and the service shell
  `ff142f1`.
- **Step 7:** `DiscoveryBeacon`, `DiscoveryClient` and `UDPListenerService` in Kotlin `0d406fe` (clears
  `WifiManagerPotentialLeak` ×2); one beacon for the dedicated server `4f73083` (S5).
- **Step 8:** `NetTrace`, frame logging in debug builds only (N9) `13a9c5d`.
- Also: `Frame` and `FrameReader` in Kotlin `9b1b597`; the producer-less `ClientEvent.Join`, `Leave` and
  `SameTeam` removed `0fedb3f`; a send-order test that overfilled the writer queue under load fixed `a922f14`.

Notes:

- **One class more than the design lists.** The client's senders (hello, settings, name, grenade, location,
  reliable messages, leave) live in `GameClient`, a plain class over `ClientConnection` and
  `ClientMessageHandler`, as `GameServer` is for the server. `TcpClient` keeps its API and delegates to it, so
  the tests build a `GameClient` with fresh repositories in place of a service.
- **Decoding is all or nothing.** A frame that doesn't decode is ignored as a whole; the old parsers applied
  whatever came before the bad key. The codec requires exactly what the old parsers required, so every frame a
  v07 build sends decodes (the smallest game info an older server sends is a test case). A player entry
  without an address is skipped. Player settings with a bad field still make the server resend everyone's.
- The server's GPS tick is a coroutine on the dispatcher with a fixed 1 s delay, in place of a scheduled thread
  that posted to it. The client's retry wait is a conflated channel, woken by Wi-Fi or a stop.
- The discovery beacon and client hold their own multicast locks; the one shared lock could be released by one
  role while the other still ran.
- `TcpServerLoopbackTest`, `ServerWireFixturesTest`, `GameRosterTest` and the client tests build fresh
  repositories (app container §4.1). The remaining `getInstance()` callers are the Stage 4–5 files: the server
  screen and its ViewModel, `MapFragment` and `MapLocationManager`.
- `ServerEvent.PlayerDataChanged` and `ClientEvent.PlayerDataUpdate` still carry the player data's JSON, which
  the screens decode through `WireCodec.decodePlayerData`; `ServerSnapshot` (Stage 4) can type them.

Device checks (passed, 2026-10-13): the full network smoke test (`NETWORK_STACK_DESIGN.md` §9.1, items 1–7, with a v07
build from before this stage in the same game for ground rule 1); the dedicated server screen's join, leave,
start, end and reset updates, the GPS map, and a timed game; discovery answers once per query (S5).

### Stage 4 — server screen

- **Dedicated server steps 7–8**: `ServerSnapshot`, `ServerController`, the Kotlin ViewModel and activity, stop
  confirmation (app container step 5 happens here).
- **Platform step 8**: large-screen layouts and the insets check, now that the server layout is final.

**Done (2026-10-13), one commit per step; device checks passed.** 555 unit tests pass,
`assembleDebug` is green, and `lintDebug` has 0 errors and 27 warnings (24 before, plus the portrait server layout's
three `RelativeOverlap` warnings copied into the landscape one).

- **Dedicated server step 7:** `GameServer.applyPlayerSettings`, the player settings save as one dispatcher
  operation `b0faa84` (S11); `ServerSnapshot`, rebuilt on the dispatcher after every task, and the operator's
  settings changes as `GameServer` operations `d1c12a6`.
- **Dedicated server step 8 and app container step 5:** `ServerController`, `TcpServer` hosting the beacon,
  notification and Wi-Fi lock, and the Kotlin `DedicatedServerViewModel`, `DedicatedServerRenderer` and
  `DedicatedServerActivity` `2c82735` (S4, S14 partly, S15; Q1, Q3).
- **Platform step 8:** the two-pane `layout-sw600dp-land` server layout `1a32da3`.

Notes:

- **Steps 7 and 8 are one commit for the screen.** `ServerController` and the Kotlin screen landed together, so the
  Java activity wasn't rewired to the controller only to be deleted in the next commit. The snapshot and S11 came
  first, on their own.
- **`ServerController` is an interface**, with `TcpServerController` (in the container) as the real one, so
  `DedicatedServerViewModelTest` runs on a fake, as §6.1 asks. It binds the service once, from start to stop, and
  forwards the server's snapshot as a `StateFlow<ServerSnapshot?>` (null while not running). Commands while
  unbound are dropped, as the old null checks did.
- **The operator's settings changes run on the server's dispatcher** (`changeTeamMode`, `changeLimits`,
  `changeGpsMode`, ...), each with the old screen's order: reset after a game, change, renumber or resend. They
  return whether they reset, for the toast. The view model saves the settings under the same keys (R5).
  `loadSettings` applies them when the server starts, and turns join-in-progress off, as the screen's switch always
  started.
- **The beacon moved into `TcpServer`**, so `UDPListenerService` only discovers. `ServerEvent.ServerCreated` went:
  the snapshot carries the address. The play screen's `udp.startGame()` only stopped a beacon that never ran on a
  phone, so it went too.
- **The zeroing of the play screen's player id and game state** (S14) moved from the activity's `onCreate` into
  `TcpServer.startServer`; it goes for good with server-scoped state (Stage 5).
- **Notification:** "N players · phase", tap to open, and a Stop server action. `POST_NOTIFICATIONS` is not asked
  again on the server screen: since Stage 0 the Dedicated server button asks for it on every path to the screen.
- **Reset and Stop confirmations** use a new host-agnostic `ConfirmDialog` (Fragment Result API), so they survive
  recreation. The play screen keeps its own `ResetConfirmDialog`, bound to `GameViewModel`; it was not moved.
- **Platform §3.2 item 5 was not done.** The connect layout's buttons (about 320dp) fit a large screen in
  landscape, and its info `WebView` already scrolls itself. A `ScrollView` around it would nest the `WebView` in a
  scroller on phones too, a visual change ground rule 1 rules out. The play layout keeps its 400dp map (Q2).
- The `getInstance()` callers left are `MapFragment` and `MapLocationManager` (Stage 5).

Device checks (passed, 2026-10-13): the dedicated-server check (`DEDICATED_SERVER_DESIGN.md` §10, items 1–9, with the
Wi-Fi lock and keep-screen-on of item 1 and the notification's Stop of item 3); the network smoke test
(`NETWORK_STACK_DESIGN.md` §9.1); and platform step 8's matrix (`PLATFORM_COMPAT_DESIGN.md` §3.2, §4.2): the server
screen in portrait, landscape (two panes) and a free-form window at its minimum size on a tablet or the resizable
emulator and a Chromebook, with no overlap at the display cutout or a side navigation bar; on a phone, still
portrait.

### Stage 5 — finish

- **App container steps 6–8**: the map sweep (or leave it to the map work), delete `getInstance()`, server-scoped
  state.
- **Platform step 9**: lint policy (promote the cleaned categories to errors and baseline the rest).

**Done in code (2026-10-13), one commit per step.** 552 unit tests pass (the three install-shim tests went with
the shim), `assembleDebug` and `assembleRelease` (R8) are green, and `lintDebug` reports no new issues: 0 errors,
with the 26 remaining warnings in the baseline.

- **App container step 6:** the map sweep `c77df61`. `MapFragment` reads `MapLocationManager` and the two
  repositories from the container in `onCreate`; `MapLocationManager` takes the repositories through its
  constructor and is a container member instead of a singleton. Nothing else in the map changed.
- **App container step 7:** `getInstance()`, `install()` and the static holders deleted from the three repositories,
  and `AudioHapticsManager` built by the container `8c509ed`. No singleton is left in the app; `RepositoryInstallTest`
  went with the shim.
- **App container step 8:** server-scoped state `8432b7f` (Q3). `TcpServer` builds its own
  `GameSettingsRepository` / `GameStateRepository` / `PlayerRepository` for its `GameServer` when it is created; the
  controller binds it from start to stop, so the state lives exactly as long as the server. The S14 zeroing of the
  play screen's player id and game state is gone, and so is the server's write of its own address into the play
  screen's server IP.
- **Platform step 9:** the lint policy `1066c34`. `HardcodedText`, `SetTextI18n`, `DefaultLocale` and the two
  `UseSwitchCompatOrMaterial` checks are errors (checked: a `String.format` without a locale fails `lintDebug`); the
  rest is in `app/lint-baseline.xml`.

Notes:

- **The server's unsaved settings start at their defaults.** The operator's saved settings (team mode, limits, start
  time, choose teams, GPS) are loaded as before. The two the operator sets only on the screen, only server settings
  and allow player settings, used to start at whatever the play screen had left in the shared repository; now they
  start at their defaults (both on), as they did in a fresh process.
- **The baseline:** `Overdraw` 8 and `RelativeOverlap` 8 (still waiting for visual checks), `LockedOrientationActivity`
  2 (intentional), `DiscouragedApi` 2, the map-owned `FragmentTagUsage` and `DrawAllocation`, and one each of
  `GradleDependency`, `TextFields`, `UselessParent` and `ViewConstructor`. 26, not 27 as after Stage 4: the
  `AudioHapticsManager` singleton's static field is gone.
- `assembleRelease` prints that the baseline's 26 entries were "not found" for `lintVitalRelease`. That is lint
  comparing the full baseline with the vital checks only; it doesn't fail the build.

Device checks still to run: the BLE and network smoke tests for step 7 (`REFACTOR_DESIGN.md` §3.0,
`NETWORK_STACK_DESIGN.md` §9.1), and for step 8 the Q3 round trip: play screen → dedicated server → back to the
play screen keeps the player's name, id, settings and saved blaster. The map's GPS markers after step 6 are covered
by the network smoke test's GPS item.

---

## Decisions (resolved 2026-10-13)

The decisions that shape the plan are below. Every other open question in the four documents takes its recommended
default.

| Document | # | Question | Decision |
|---|---|---|---|
| Network | Q2 | JSON library behind the typed wire model | Stay with JSON on org.json |
| Network | Q4 | Move the map's GPS broadcasts now or in the map work | Now, in Phase 2: the GPS/map work waits for this roadmap to finish |
| Dedicated server | Q1 | How the operator stops the server | Back asks for confirmation (the notification's Stop action stays) |
| Dedicated server | Q3 | Keeping the device awake | Always keep it awake unless the operator is on the home screen: screen on and a Wi-Fi lock while the server screen is in the foreground, released in the background |
| Platform | Q1 | Dedicated server layout on large screens | Two-pane `layout-sw600dp-land` |
| Platform | Q5 | Material3 | Later, as its own project with screenshot tests first (default) |
| App container | Q1 | Hilt | No DI framework: the app stays small and simple, in one module, and must run well on old and slow devices |
| App container | Q3 | Separate server-scoped state | Yes, as the last step |

The GPS/map feature work starts after Stage 5.
