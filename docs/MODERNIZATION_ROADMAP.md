# SimpleCoil Modernization Roadmap (after V2 refactor)

Status: **APPROVED (2026-10-13).** All four documents are approved and their decisions resolved. Implementation
starts in a new session, at Stage 0.
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

### Stage 1 — independent foundations (can run in parallel)

- **Network Phase 1**, I/O fixes in Java: `FrameReader`, per-connection writers and readers, the server dispatcher,
  the queued client close.
- **App container steps 1–3**: the container, constructible repositories with the install shim, injected Kotlin
  consumers.
- **Dedicated server steps 1–5**: the scoreboard on `RecyclerView`, `GameLimitRules` / `TeamSizes` (and the 0-limit
  fix), host-agnostic dialogs, `PlayerSettingsDialog`, settings through `UserPreferencesRepository`.
- **Platform steps 1–7**: manifest and `configChanges`, backup rules, the lint cleanup, `SwitchCompat`, R8.

### Stage 2 — events and the server clock

- **Network Phase 2**: the `ClientEvent` / `ServerEvent` buses, and the deletion of the broadcast plumbing. This
  includes `MapFragment`'s three GPS broadcasts (network Q4).
- **Dedicated server step 6**: `ServerGameClock`, hosted by the server, built on the Stage 1 dispatcher.

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
