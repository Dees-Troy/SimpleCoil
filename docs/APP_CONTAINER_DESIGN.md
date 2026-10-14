# SimpleCoil Dependency Injection: One App Container — Design Document

Status: **APPROVED (2026-10-13); steps 1–4 IMPLEMENTED and verified on devices (roadmap Stages 1 and 3; step 4 with the network conversions); step 5 implemented with the server screen and verified on devices (roadmap Stage 4).** All decisions in §6 are resolved.

Revision 2 (2026-10-13): decisions recorded (§6). No Hilt (Q1): the app is meant to stay small and simple and to
run well on old and slow devices, so it won't be split into modules. The dedicated server gets its own state (Q3).
Branch audited: `V2-refactor` @ `b460c02`.
Date: 2026-10-13
Part of: `MODERNIZATION_ROADMAP.md`. The network stack and dedicated server documents apply this design while they
rewrite their classes.

---

## 0. Summary

`SimpleCoilApp` is already a small manual container: it builds the BLE stack, the session engine, the network
gateway, sounds and preferences, and `GameViewModelFactory` injects them through constructors. The three shared
repositories are still Java singletons reached through `getInstance()`, and so are a few managers:

| Singleton | `getInstance()` call sites |
|---|---|
| `GameSettingsRepository` | 194 |
| `PlayerRepository` | 83 |
| `GameStateRepository` | 24 |
| `MapLocationManager`, `AudioHapticsManager` | 1 each |

The result:

- **Tests share global state.** `GameViewModelTest`, `TcpServerLoopbackTest`, `DedicatedServerViewModelTest` and
  others reset singleton fields in `@Before`. A forgotten field leaks between tests: `STATE_CONSOLIDATION_DESIGN.md`
  §8.5 records one case that had to be fixed this way.
- **Dependencies are invisible.** A class's constructor doesn't say that it reads the team layout or writes the game
  state.
- **The server and client roles share one copy of state.** `DedicatedServerActivity.onCreate` quietly zeroes the
  client's player id and game state (:164–165) because the server reuses the same objects.

**Most of the work happens elsewhere.** About 260 of the ~300 call sites are in five Java files that the other
documents rewrite: `TcpClient` 74, `DedicatedServerActivity` 67, `TcpServer` 61, `PlayerSettingsAlertDialog` 36 and
`PlayerDisplayDataListAdapter` 18. Doing a separate injection sweep over those files first would mean editing them
twice. So this document:

1. defines the container and the access rules (§2);
2. makes the repositories constructible and adds a shim that keeps one instance per process during the migration
   (§3);
3. converts the Kotlin classes now, and leaves the Java files to be converted inside their rewrites (§4);
4. deletes `getInstance()` once nothing calls it, and optionally gives the server its own state (Q3).

No DI framework is used (Q1).

---

## 1. Goals and rules

| # | Goal |
|---|---|
| G1 | Every plain class (ViewModels, engines, handlers, renderers, `GameServer`, `ClientConnection`) receives its dependencies through its constructor and never looks them up. |
| G2 | Android entry points (Activity, Service, Fragment) get dependencies from `(application as SimpleCoilApp).container` in `onCreate` and pass them on. ViewModels are built by factories from the container, as `GameViewModelFactory` does now. |
| G3 | A unit test builds a fresh object graph and never touches process-global state. |
| G4 | Exactly one instance of each repository exists per process, at every step of the migration. |
| G5 | No change in behavior: same state, same locks, same lock order (`STATE_CONSOLIDATION_DESIGN.md` §5: `GameRoster` → `PlayerRepository` → `GameSettingsRepository`). |

---

## 2. The container

```kotlin
class AppContainer(private val app: Application) {
    val dispatchers = AppDispatchers()                       // main, io, default: injectable for tests
    val clock: () -> Long = SystemClock::elapsedRealtime
    val appScope = CoroutineScope(SupervisorJob() + dispatchers.mainImmediate)

    // State
    val settings = GameSettingsRepository()
    val gameState = GameStateRepository()
    val players = PlayerRepository(settings, gameState)     // its 4 cross-repository lookups become fields
    val gameConfig: GameConfig = RepositoryGameConfig(settings, players, gameState)
    val userPreferences by lazy { UserPreferencesRepository(app) }

    // Events (NETWORK_STACK_DESIGN §6)
    val clientEvents = EventBus<ClientEvent>()
    val serverEvents = EventBus<ServerEvent>()

    // Hardware and session: today's SimpleCoilApp members, moved unchanged
    val blasterManager by lazy { ... }; val bleDeviceRepository by lazy { ... }
    val bleConnectionManager by lazy { ... }; val sessionEngine by lazy { ... }
    val networkGateway by lazy { ... }; val soundEffects ...; val haptics ...

    // Server (DEDICATED_SERVER_DESIGN §5)
    val serverController by lazy { ServerController(app, ...) }
}

class SimpleCoilApp : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this).also { it.installLegacySingletons() } }
}
```

- `SimpleCoilApp`'s current `lazy` members move into `AppContainer` unchanged. Their callers, `GameViewModelFactory`
  and the `GameDialogFragment` base, read `app.container.x`.
- `RepositoryGameConfig` changes from an `object` that calls `getInstance()` into a class that takes the three
  repositories.
- `AudioHapticsManager.getInstance().also { it.initialize(this) }` becomes a container member.
  `MapLocationManager` is left for the map work (§4 step 6).
- `AppDispatchers` and `clock` are injected wherever time or threads matter (`ServerGameClock`, `FrameReader`
  liveness, `ClientConnection`), following the pattern `BlasterSessionEngine` already uses (`clock`, `scope`).

---

## 3. Migration mechanics: constructible repositories, one instance

1. **Make the constructors public.** `PlayerRepository(settings, gameState)` takes its dependencies; its four
   cross-repository `getInstance()` calls (game-running in `removePlayer` :179; team layout at :336, :344, :419)
   become fields. The lock order doesn't change, because the same calls happen on the same objects.
2. **Install shim.** Each repository keeps a static holder:

   ```java
   private static volatile GameSettingsRepository instance;
   /** Called once by AppContainer, before any component starts. */
   public static void install(GameSettingsRepository repo) {
       if (instance != null && instance != repo) throw new IllegalStateException("created before install");
       instance = repo;
   }
   /** @deprecated inject the instance from AppContainer instead. */
   @Deprecated public static GameSettingsRepository getInstance() { /* lazy create, as today, for unconverted JVM tests */ }
   ```

   - In the app, `Application.onCreate` runs before any Activity, Service or BroadcastReceiver, so `install` comes
     first. The app has no ContentProvider of its own that could run earlier. The `IllegalStateException` turns any
     ordering mistake into an immediate crash during development instead of two silent copies of the state (G4).
   - In JVM tests with no Application, `getInstance()` still creates one lazily, so unconverted tests keep working.
     Converted tests construct their own instances and never call it.
   - `@Deprecated` turns each remaining call site into a compiler warning, which doubles as the burn-down count.
3. **Remove** `getInstance()`, `install()` and the static holders once there are no callers left (§4 step 7).

---

## 4. Plan

The gate on every step: `./gradlew test` and `assembleDebug` pass. **[device]** marks the steps where object identity
could go wrong, which get the BLE smoke test (`REFACTOR_DESIGN.md` §3.0) and the network smoke test
(`NETWORK_STACK_DESIGN.md` §9.1).

| Step | Work | Call sites removed | When |
|---|---|---|---|
| 1 | `AppContainer` extracted from `SimpleCoilApp` (a mechanical move). `RepositoryGameConfig` becomes a class. `AppDispatchers`. | 4 (`GameConfig.kt`) | Any time |
| 2 | Public constructors, `PlayerRepository(settings, gameState)`, install shim (§3). **[device]** | 4 (`PlayerRepository`) | After 1 |
| 3 | Kotlin consumers take injected repositories: `GameViewModel` (its three `get() = getInstance()` properties), `NetworkSessionGateway`, `BlasterSessionEngine` through `GameConfig`. Their tests construct fresh repositories and drop their `@Before` singleton resets (`GameViewModelTest`, `NetworkSessionGatewayTest`, and the three repository tests). | 7 + their tests | After 2 |
| 4 | Network classes, as each one is converted (`NETWORK_STACK_DESIGN.md` §7.1 steps 2, 5, 6, 7): `GameRoster`, `TcpClient` / `ClientMessageHandler`, `TcpServer` / `GameServer` / `ServerMessageHandler` / `SnapshotBuilder`, `UDPListenerService`. Services read the container in `onCreate`. `TcpServerLoopbackTest` builds a server with fresh repositories. | ~146 | With the network Phase 3 conversions |
| 5 | Dedicated server and shared UI, as each one is rewritten (`DEDICATED_SERVER_DESIGN.md` §9 steps 1, 4, 7, 8): scoreboard, player settings dialog, the server's ViewModel, activity and controller. | ~124 | With the dedicated server rewrite |
| 6 | `MapFragment` (6), `MapLocationManager` (3, plus its own `getInstance`). | 9 | With the map work, or as a final sweep |
| 7 | Delete `getInstance()`, `install()` and the static holders, plus `AudioHapticsManager.getInstance`. **[device]** | — | When steps 4–6 are done |
| 8 | (Q3) Server-scoped state. **[device]** | — | After 7 |

### 4.1 Tests

- A repository test constructs `GameSettingsRepository()` directly. No test calls `getInstance()` after step 7, so
  test order can no longer matter.
- A `TestGraph` helper (test sources) builds the repositories, a `RepositoryGameConfig`, the two event buses, and a
  `TestScope`-backed `AppDispatchers` in one call, so tests don't each repeat the wiring.
- The `Thread.sleep(200)` in `TcpServerLoopbackTest.tearDown` (it waits for static flags) goes away with the network
  document's N7 fix and per-test repositories.

---

## 5. Static and process-global state outside the repositories

| State | Handled by |
|---|---|
| `TcpServer.keepListening`, `mGPSRunning`, `mGPSIntervalCount`; `TcpClient.keepListening`, `isListening` (static) | `NETWORK_STACK_DESIGN.md` N7 (instance fields) |
| `MapFragment.mLongitude`, `mLatitude` (static) | Map work |
| `PlayerRepository.getIPAddress()` / `getIPAddressStr()` (static, stateless) | Move to a stateless `NetworkAddresses` object. No state, so no injection needed. |
| `GameStateRepository.serverGameTimeRemaining` written by the dedicated server UI | `DEDICATED_SERVER_DESIGN.md` §5.3 (`ServerGameClock`) |

---

## 6. Decisions (all resolved 2026-10-13)

| # | Question | Options | Recommendation |
|---|---|---|---|
| Q1 | Hilt? | Hilt / Koin / manual container | **Decided: manual container, no DI framework.** The app is meant to stay small and simple and to run well on old and slow devices, and it won't be split into modules. About 25 injectable types in one module, and the manual container already works. Hilt would add KSP, a Gradle plugin and annotations on every entry point, plus compile-time cost, for no capability the app needs. |
| Q2 | Keep `@Deprecated getInstance()` as a shim during the migration, or convert in one sweep? | Shim / big-bang | **Accepted default: shim.** It lets the injection follow the rewrites instead of preceding them, and `install()`'s check keeps it to one instance per process. |
| Q3 | Should the dedicated server get its own repository instances? | Yes, after step 7 / no | **Decided: yes, after step 7.** Once nothing calls `getInstance()`, `ServerController` can build a server-scoped `GameSettingsRepository`/`PlayerRepository`/`GameStateRepository` set when the server starts and hand it to `GameServer`. The play screen's player id, name and settings can then never be clobbered by running a server, and the zeroing hacks in `DedicatedServerActivity.onCreate` go away. Hosting and playing on the same device at once stays out of scope; this only stops the two roles from interfering. Device check: play screen → dedicated server → back to the play screen keeps the player's name, id, settings and saved blaster. |
| Q4 | Inject `UserPreferencesRepository` as an interface (`SessionPreferences` already exists for the engine)? | Interfaces only where a test needs a fake / an interface for everything | **Accepted default: only where needed.** Repositories are cheap to construct for real in JVM tests. |

---

## 7. Verification

- The unit suite at every step. From step 3 on, every converted test uses fresh instances. Step 7 is the proof:
  it doesn't compile if anything still reaches a singleton.
- At steps 2 and 7, the BLE and network smoke tests, because both steps touch object identity in the running app.
  In a debug build, step 2's `install()` check fails fast on a second instance.
- At step 8, the dedicated server round-trip check from Q3.

---

## 8. Out of scope

Hilt, Koin or any DI framework; splitting the app into modules; `MapLocationManager` design (map work); the
DataStore migration (still excluded, as in `REFACTOR_DESIGN.md` §5).
