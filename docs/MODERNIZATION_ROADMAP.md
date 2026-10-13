# SimpleCoil Modernization Roadmap (after V2 refactor)

Status: **Stages 0–2 DONE in code (2026-10-13); Stage 1 and Stage 2 device checks pending; Stage 3 next.** All four documents are approved and their decisions resolved.
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

**Done in code (2026-10-13), one commit per step; device checks pending.** 495 unit tests pass, `assembleRelease`
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

Device checks still to run before Stage 2 builds on this: the network smoke test (`NETWORK_STACK_DESIGN.md` §9.1,
with items 4 and 7 for Phase 1); the BLE and network smoke tests for app container step 2 (object identity); the
dedicated-server checks for steps 1 and 3 (scroll position kept, dialogs) and §10 items 5 and 7; and the platform
checks: dialogs look identical (step 2), Chromebook resize mid-game (step 3), `bmgr` backup and restore (step 4),
switch tint (step 6), the launcher's themed icon, and the full BLE + QR + network smoke on the R8 release APK
(step 7).

### Stage 2 — events and the server clock

- **Network Phase 2**: the `ClientEvent` / `ServerEvent` buses, and the deletion of the broadcast plumbing. This
  includes `MapFragment`'s three GPS broadcasts (network Q4).
- **Dedicated server step 6**: `ServerGameClock`, hosted by the server, built on the Stage 1 dispatcher.

**Done in code (2026-10-13), one commit per step; device checks pending.** 512 unit tests pass, `assembleDebug` is
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

Device checks still to run (on top of Stage 1's): the network smoke test (`NETWORK_STACK_DESIGN.md` §9.1); the
dedicated server screen's join/leave/start/end/reset updates and the GPS map (network §6.5); and dedicated server §10
item 1 for the clock (a timed game ends on time with the screen closed, and the clock is right after reopening it).

### Stage 3 — Kotlin network stack

- **Network Phase 3**: golden wire fixtures, `WireCodec`, then the class-by-class conversion, injecting
  dependencies as each class is converted (app container step 4).

### Stage 4 — server screen

- **Dedicated server steps 7–8**: `ServerSnapshot`, `ServerController`, the Kotlin ViewModel and activity, stop
  confirmation (app container step 5 happens here).
- **Platform step 8**: large-screen layouts and the insets check, now that the server layout is final.

### Stage 5 — finish

- **App container steps 6–8**: the map sweep (or leave it to the map work), delete `getInstance()`, server-scoped
  state.
- **Platform step 9**: lint policy (promote the cleaned categories to errors and baseline the rest).

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
