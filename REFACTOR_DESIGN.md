# SimpleCoil `FullscreenActivity` Decomposition & Modernization — Design Document

Status: **APPROVED (2026-10-12).** All decisions in §4 are resolved. No production or test source has been modified yet.
Branch audited: `V2` @ `316a81c` ("Unit tests for BLE protocol").
Date: 2026-10-12

Revision 2 (2026-10-12): the existing tests are **no longer immutable**. They may be changed when that
unblocks modernizing the app and moving it to Kotlin instead of leaving Kotlin and Java commingled. The
BLE wire protocol stays frozen. Work proceeds in phases, and every change that touches the BLE layer is
verified on real blasters before the next step starts. §0, §1.3, §2, §3, §4 and §5 are updated to match.

---

## 0. Ground rules (restated, binding for every phase)

1. **The Recoil BLE wire protocol is frozen.** UUIDs, 20-byte packet layouts, opcodes, the rolling
   command-id, write type (`WRITE_TYPE_DEFAULT`, i.e. with response), CCCD sequencing, and every
   protocol-relevant delay (§1.6) are preserved byte-for-byte and millisecond-for-millisecond. This
   holds no matter which language or class implements them.
2. **Tests may change; the protocol they assert may not.** Files under `app/src/test/` may be edited,
   ported to Kotlin, split, merged or deleted when that is needed to modernize the code. The rules for
   doing so are in §1.3.4. In short: the *expected protocol values* (golden bytes, UUIDs, offsets, CCCD
   order, timings) are never edited or dropped; only *how* they are reached may change. A test change
   that alters an expected protocol value is a protocol change and is forbidden.
3. **Gate.** Every commit keeps `./gradlew test` at 0 failures and `./gradlew assembleDebug` succeeding.
   Every phase also ends with the protocol coverage catalog (§1.3.1) still fully asserted. The test count
   is no longer fixed at 168 and may go down when tests are merged, but catalog coverage may not.
4. **BLE changes are verified on hardware.** Any change that affects the BLE layer (§3.0 defines what
   counts) is followed by the on-device BLE smoke test (§3.0) with real blasters before the next step
   starts. Unit tests alone are not sufficient for these steps.
5. **Bugs found (§1.7) are fixed, but never inside an extraction commit.** Extractions stay
   behavior-preserving moves. Each bug is fixed in **its own commit**, made right after the code it lives in has
   been extracted and tested (schedule in §1.7.1). The fix commit updates the test that pinned the buggy behavior
   (§1.3.4 rule 4), so a device regression can be bisected to either a move or a fix, never both.

---

## 1. Current state & dependency audit

### 1.1 Codebase shape

| Item | Value |
|---|---|
| Language | 100% Java (no Kotlin plugin, no coroutines, no `lifecycle-*-ktx`) |
| AGP / Gradle | 8.13.2 / 8.13 |
| compile/target/min SDK | 34 / 34 / 21 |
| Java source/target | 1.8 (deprecated warning under JDK 21) |
| UI | XML layouts + `findViewById`; no ViewBinding/Compose |
| Launcher | `com.simplecoil.simplecoil.FullscreenActivity` (manifest) |
| `FullscreenActivity.java` | **3,587 lines**, 1 class, ~95 fields |

A partial decomposition already exists (from earlier V2 commits), but it is **mostly not wired in**:

| Already-extracted component | Used at runtime by `FullscreenActivity`? |
|---|---|
| `hardware/blaster/GattProtocolHandler` (packet build/parse) | **Yes**, indirectly. Outbound packets go through `BlasterManager`. Inbound telemetry does **not**: the activity decodes it inline. |
| `hardware/blaster/BlasterManager` (singleton) | **Partially.** Used for outbound writes (`setTeam`, `startReload`, `finishReload`, `setShotMode`, `setRecoil`) and `discoverGattCharacteristics()`. Its own `connect()`/broadcast receiver/telemetry decoding is **never invoked** because the activity binds the service and scans itself. |
| `ui/main/MainViewModel` | Instantiated; activity observes only `health` and `ammo` LiveData. Its `BlasterEventListener` callbacks **never fire** (see above), so its `GameEngine` wiring is dead. |
| `domain/engine/GameEngine` | Dead at runtime (only reached via `MainViewModel`). Its game rules **differ** from the activity's (§1.5). |
| `hardware/audio/AudioHapticsManager` (SoundPool) | Initialized by `MainViewModel`; the activity plays sounds with its own `MediaPlayer.create()` per sound. |
| `data/repository/*` | Used for a few lookups (`GameSettingsRepository.isAllowChooseTeams`, `PlayerRepository.getGrenadePairing`). The activity's source of truth is `Globals`. |
| `ui/main/MainActivity` | Subclass of `FullscreenActivity`; **not in the manifest** (dead). |

### 1.2 Responsibility inventory of `FullscreenActivity`

Line numbers refer to `FullscreenActivity.java` at `316a81c`.

| # | Responsibility | Where | Notes |
|---|---|---|---|
| R1 | **BLE scanning** | `connectWeapon()` 2265–2362, `mLeScanCallback` 2082–2128 | Unfiltered `startScan`. Matches name prefix `"SRG1"` when no saved address, or exact saved MAC. 30 s give-up (`startConnectFailTest`). |
| R2 | **GATT service binding/connection** | `setupBLEServiceConnection()` 339–367, `resetBluetoothServices()` 2392 | Starts `BluetoothLeService` as FGS, binds, `connect(addr)`, hands service to `BlasterManager`. Persists `DeviceAddress` pref. |
| R3 | **GATT event handling** | `mGattUpdateReceiver` 2476–2530 | Duplicates `BlasterManager.handleGattIntent`. Drives reload stage 2 off `CHARACTERISTIC_WRITE_FINISHED`. Blaster-type toast. |
| R4 | **Telemetry deserialization (legacy decoder)** | `processTelemetryData()` 2608–2955, offset consts 278–287, grenade consts 299–302 | Parallel to `GattProtocolHandler.parseTelemetry`. Also does grenade nibble decoding, which `TelemetryData` cannot express. |
| R5 | **Button edge detection** | inside R4 | Trigger/reload/thumb/power counters, re-baselined on first packet per connection. |
| R6 | **Hit processing / damage** | 2736–2919 | (player, shotId) de-dup across 2 slots, grenade pairing, friendly fire, per-player damage, shield, network HIT/OUT messages rate-limited at 400 ms, lives/eliminations. |
| R7 | **Reload state machine** | 261–272, 1786–1818, 2512–2527 | `NONE/STARTED/FINISHING/ELIMINATED`; two-stage BLE command; auto-reload after 3 empty pulls; reload-on-empty. |
| R8 | **Spawn / respawn / shield / game timers** | 1820–1944 | 5 `CountDownTimer`s + `Chronometer`. |
| R9 | **Game lifecycle** | `startGame()` 1507, `endGame()` 1592, `confirmEndGame()` 1560 | Mutates window flags/insets, ~25 views. |
| R10 | **Shot mode / firing mode / recoil policy** | 1948–1989, 3545–3586, thumb/power handling in R4 | Server-forced recoil; allowed shot-mode cycling. |
| R11 | **Battery averaging & display** | 240–252, 1497–1505, 2702–2735 | 100-sample rolling average seeded with one `16`; pistol/rifle thresholds. |
| R12 | **Connection watchdog** | `startConnectionTest()` 2543–2565 | 5 s `Handler` loop; disconnect if no telemetry since last tick. |
| R13 | **Network session (client/server)** | 369–603, 955–1231, 1280–1313, 1448–1483, 2957–3244, 3281–3396 | Binds `UDPListenerService`, `TcpClient`, `TcpServer`; 21-action `mUDPUpdateReceiver`; admin menu; team switching; server-search retry loop. |
| R14 | **Scoreboard** | 379–430, 3398–3534 | Background `Thread` parses JSON, mutates `mScore`/`mTeamScore` off main thread. |
| R15 | **Settings / preferences persistence** | 324–337 (13 keys in `"SimpleCoil"` prefs), writes scattered across ~10 methods | Also mirrors into `Globals`. |
| R16 | **Dialogs** | player name, server IP, game limit, team ratio, blaster disconnected, end game, player settings | All inline `AlertDialog.Builder`. |
| R17 | **Sound & haptics** | `playSound()` 2579, `vibrate()` 2568 | New `MediaPlayer` per sound (can return `null` → NPE). |
| R18 | **UI state mutation** | everywhere | ~45 view fields; visibility logic spread across `updatePreGameButtonVisibilities`, `displayAllNetworkingOptions`, `displayInGameNetworkingOptions`, `displayCurrentTeam`, `setGameLimit`, etc. |
| R19 | **Window management** | 1509–1515, 1456/1475/1509/1596 | Keep-screen-on, hide nav bars in game. |
| R20 | **Permissions** | 2145–2180, 2284–2311, 673–680 | Legacy `requestPermissions` + `onRequestPermissionsResult`; request code "LOCATION" covers BLE+location+notifications. |
| R21 | **Bluetooth adapter state** | `mBluetoothReceiver` 2182, `enableBtLauncher` 120 | Auto-reconnect when BT turns on. |
| R22 | **QR pairing** | 124–131, 2130–2142, 2227–2261 | `MAC[-PlayerName]` payload. |
| R23 | **Lifecycle & state restore** | `onCreate/onResume/onPause/onDestroy/onSaveInstanceState` | Re-binds 3 services on every `onResume` (§1.4). |

### 1.3 Existing test suite audit (baseline)

**Baseline run (`./gradlew test`, 2026-10-12):** `BUILD SUCCESSFUL`; **168 tests, 0 failures, 0 errors, 0 skipped**
in each of `testDebugUnitTest` and `testReleaseUnitTest`. `./gradlew assembleDebug`: `BUILD SUCCESSFUL`.
No `androidTest` source set exists.

| Test class | # | Category |
|---|---|---|
| `GattAttributesProtocolTest` | 7 | BLE: UUIDs |
| `TelemetryParsingProtocolTest` | 26 | BLE: inbound decode |
| `OutboundCommandProtocolTest` | 20 | BLE: outbound packets |
| `BluetoothLeServiceGattTest` | 18 | BLE: GATT plumbing |
| `BlasterManagerGattTest` | 33 | BLE: routing, sequencing, events |
| `GattProtocolHandlerTest` | 7 | BLE: builder/parser smoke |
| `BlasterConnectionMockTest` | 2 | BLE: manager singleton |
| `LegacyTelemetryDecoderConstantsTest` | 3 | BLE: activity ↔ handler constant parity |
| `RecoilProtocolFixtures` | — | Golden vectors + reference hit/grenade decoder |
| `GameEngineTest`, `GameStateRepositoryTest`, `GameSettingsRepositoryTest`, `PlayerRepositoryTest`, `MainViewModelTest`, `DedicatedServerViewModelTest`, `MapLocationManagerTest`, `GlobalsTest` | 52 | Non-BLE (still part of the gate) |

#### 1.3.1 Protocol coverage catalog

**UUIDs** (`GattAttributesProtocolTest`): CCCD `0x2902`; Recoil service `e6f59d10…`; ID `…d11`; Telemetry
`…d12`; Command `…d13`; Config `…d14`. It also checks lowercase canonical form, the shared vendor base, distinctness,
`BluetoothLeService.UUID_RECOIL_*` derivations, and that `GattAttributes` has **exactly** these six `static String`
fields (reflection).

**Outbound packets** (`OutboundCommandProtocolTest`, `GattProtocolHandlerTest`, `BlasterManagerGattTest`). All are 20 bytes and zero-padded:

| Packet | Characteristic | Golden format | Coverage |
|---|---|---|---|
| Set team | Command | `CID 00 80 00 PID` | golden, min/max PID, no validation in builder; manager rejects PID < 1 (signed) without consuming a CID |
| Start reload | Command | `CID 00 02 00 PID` | golden, boundaries |
| Finish reload | Command | `CID 00 04 00 PID PROFILE SHOTS` | golden, 0/127/128/255 shots, profile byte |
| Shot mode | Config | `PROF 00 09 S R CA CB FF FF REC 02 34` | full 2×3×3 matrix (pistol/rifle × single/burst/auto × 3 cones), rifle-burst recoil `0x78`, unknown mode/cone/type characterization |
| Recoil | Config | `10 00 02 {03 on / 02 off} FF` | both values |
| Rolling CID | — | `+0x10`, wraps after `0xF0`; config writes don't consume; dropped writes don't consume | |
| Buffer hygiene | — | builders return fresh arrays | |

**Inbound telemetry** (`TelemetryParsingProtocolTest`, `GattProtocolHandlerTest`): offsets 1,2,3,4,5,7,8,9,11,12,14,15;
golden idle/trigger/reload/single-hit/double-hit/grenade-damage/grenade-pair captures; exhaustive 0–255 checks for
nibble split, unsigned hit-by, shot-id `& 0x07`, raw pass-through fields; ignored bytes; null/<16 → `null`;
16–20 identical; oversize ignored; 5,000 random packets never throw; no input mutation; reference grenade
nibble decoding (`id = hi nibble`, `event = lo nibble`, damage `0x01`, disarm `0x0D`, new-pair `0x0E`, pair `0x0F`,
grenade IR id `167`).

**GATT plumbing** (`BluetoothLeServiceGattTest`): telemetry notification → `TELEMETRY_DATA_AVAILABLE` with full
payload (API 33 and legacy callbacks); ID read → `ID_DATA_AVAILABLE` with byte 10 (or no extra if ≤10 bytes);
failed read not broadcast; unknown characteristic dropped; `onCharacteristicWrite` **always** broadcasts
`CHARACTERISTIC_WRITE_FINISHED` (even on failure); descriptor write always broadcasts; services discovered only on
success; disconnect broadcast; CCCD sequence `setNotification(false)` → `setNotification(true)` → `setValue(01 00)`
→ `writeDescriptor`; disable writes `00 00`; non-telemetry doesn't touch CCCD; pre-33 write path sets value →
write type → write; null/no-GATT guards.

**State transitions / events** (`BlasterManagerGattTest`): discovery enables notifications and reads ID and
**must not write** (the recoil write is deferred through `Handler`); case-insensitive service match; non-Recoil
services ignored; blaster type default pistol, rifle changes burst recoil; write-finished forwarded; telemetry edges for
trigger/reload/thumb/power (once per counter change, wrap-around counts); power edge flips the manager's flag
without writing; hits per slot without de-dup; slot 2 reported even when slot 1 is empty; battery rolling integer
average of 100 with **no seed**; battery carries blaster type; truncated/null packets ignored; fallback to
`characteristic.getValue()`.

This catalog is the **protocol contract** for the rest of the roadmap. Each item must stay asserted
somewhere in the suite at every phase gate, whichever file or language asserts it.

#### 1.3.2 Gaps in coverage (to be filled with new tests, or by extending existing ones)

1. The activity's runtime logic, R5–R12, has **no tests**: the re-baseline on first packet, hit de-dup, grenade pairing, friendly fire,
   per-player damage, slot-2-only-if-slot-1 rule, reload state machine, auto-reload, battery seed and thresholds, and watchdog.
   This is the behavior users actually experience.
2. Protocol timing (§1.6) is not asserted anywhere.
3. Scan matching (`SRG1` prefix / exact MAC) and the QR payload parser are untested.
4. The network wire protocol (`NetMsg` strings, `TcpServer` JSON keys, `TCPMESSAGE_PREFIX`) is untested. It must stay
   compatible with other phones and dedicated servers running older builds, so it gets the same "frozen"
   treatment as BLE in this refactor.

#### 1.3.3 Test coupling to implementation details (to be removed)

The current BLE tests pin several Java implementation details that have nothing to do with the wire
protocol. Under the original rules these blocked a Kotlin conversion of the BLE layer. With tests now
mutable, each one is replaced by an equivalent **behavioral** assertion through a public or injected API,
so the classes can be converted to Kotlin.

| Coupling | Source | Planned treatment |
|---|---|---|
| `BluetoothLeService` fields named `mBluetoothAdapter`, `mBluetoothGatt`, `mGattCallback`, set by reflection; no-arg constructible on the JVM | `BluetoothLeServiceGattTest` | Introduce a `GattIo` seam (a thin interface over `BluetoothGatt` / adapter calls) that tests inject. Tests drive the callback through the seam instead of reflected fields. The service keeps a no-arg constructor for the framework. |
| Service reports events by constructing `new Intent(String action)`; tests count those constructions | same | Tests assert the **event sequence** (action + payload) delivered to a recording sink, not Intent constructions. That leaves the transport free to change (Decision D8). |
| `BlasterManager.instance` static field, private `handleGattIntent(Intent)`, `getInstance()` | `BlasterManagerGattTest` reflection | Tests construct a `BlasterManager` directly with injected collaborators and feed events through its public input. The singleton goes away and the app container owns one instance (§2.2.3). |
| Recoil write after discovery deferred via `new Handler(Looper.getMainLooper()).postDelayed`; the test asserts "no write" only because the `Handler` is a no-op under `returnDefaultValues` | `discovery_…` test | Inject a `CoroutineScope`/dispatcher. The test is rewritten to assert **no write at 499 ms, recoil write at 500 ms** under virtual time. That is stronger than today's assertion and pins the §1.6 timing directly. |
| `GattAttributes` must have exactly six `static String` fields | `GattAttributesProtocolTest` | Keep the six UUID value assertions verbatim. Replace the reflective field count with an explicit list of the six constants, so a Kotlin `object` (with synthetic members) passes. |
| `FullscreenActivity.RECOIL_OFFSET_*` (public static int) and **private static byte** `GRENADE_DAMAGE/DISARM/NEW_PAIR/PAIR_ID` read by reflection | `LegacyTelemetryDecoderConstantsTest` | Once the inline decoder moves out (Phase 3), the parity check points at the new decoder and the constants leave `FullscreenActivity`, as that test's own Javadoc anticipates. See **Decision D1**. |
| `BlasterEventListener` method set; `TelemetryData` public 12-arg shape | `BlasterManagerGattTest.RecordingListener` | Free to change. `TelemetryData` becomes a Kotlin `data class` that carries the raw shot-id bytes (D6), and the listener is replaced by the `BlasterEvent` stream (§2.1.3). The recorded **values** in the tests stay the same. |
| `BlasterManager` battery average starts unseeded; counters start at 0 with no re-baseline | characterization tests of the dead path | These pin behavior that has never shipped. The manager adopts the **live** activity semantics (seed of `16`, re-baseline on the first packet per connection, §1.5), and those tests are updated **in the same commit**, with the commit message saying so (D3). |
| Singleton shared across test classes in one JVM (`BlasterConnectionMockTest` assumes recoil = `true` on a possibly reused instance) | latent order dependency | Disappears with the singleton. Each test builds its own instance. |

#### 1.3.4 Rules for changing existing tests

1. **Protocol values are never edited.** A golden byte array, UUID string, offset, opcode, CCCD value,
   write type or delay in an expected position may be moved or re-expressed (Java → Kotlin, a new
   helper, a new file), but its value stays identical. `RecoilProtocolFixtures` vectors are moved
   verbatim, never regenerated.
2. **Coverage only moves, it never shrinks.** Deleting a test is allowed only when the same protocol
   assertion exists elsewhere (or is added in the same commit). The commit message names where it went.
3. **Port first, then change.** Where possible, a test is restructured in its own commit that passes
   against **unchanged** production code, and the production change follows in a later commit. Where
   the seam itself is a production change (`GattIo`, injected scope), the seam and the test rewrite share
   one commit that changes no behavior.
4. **Behavior changes are explicit.** A test whose expected value changes because behavior changes (D3
   semantics, an approved bug fix) is updated in the same commit as the code, and the commit message
   says which behavior changed and why.
5. **Language.** Tests that are ported or newly written are written in Kotlin. Untouched Java tests stay
   Java until the class they test is converted.

### 1.4 Memory-leak & lifecycle risks

| # | Risk | Evidence |
|---|---|---|
| L1 | **ServiceConnections leak on every resume.** `onResume` calls `setupUDPServiceConnection()`, `setupTcpClientServiceConnection()` and `setupTcpServerServiceConnection()`, creating new anonymous `ServiceConnection`s (each capturing the Activity) and binding again. `onPause` never unbinds, and `onDestroy` unbinds only the latest. N resumes → N live bindings → Activity leaked and stale `onServiceDisconnected` callbacks mutate state. | 2033–2035, 2049–2060 |
| L2 | `mBLEServiceConnection` and `mLeScanCallback` are inner classes holding the Activity. The scan is not stopped in `onDestroy`. | 339, 2082 |
| L3 | **5 `CountDownTimer`s** (spawn, reload, game countdown, connect-fail, shield) are not cancelled in `onDestroy`. Their callbacks touch views and send BLE/TCP after destroy. | 192–196 |
| L4 | `connectionTestHandler` self-reposting runnable plus ~10 ad-hoc `new Handler(...).postDelayed` lambdas capturing views; none removed in `onDestroy`. | 2546, 2637–2641, 2880, 2969, 2989, 3014, 3040, 3172, 1934, 2471 |
| L5 | **Static mutable UI/game state on the Activity class**: `mHealth`, `mLastTeam`, `mBlasterType`, `mEliminationCount`, `mEmptyTriggerCount`, `mStartGameTimer`, `mHasLivesLimit`, `mLives`. It outlives Activity instances, and `mLastTeam` suppresses team re-detection after recreation. | 218–230, 256 |
| L6 | `PlayerSettingsAlertDialog` and `PlayerDisplayDataListAdapter` hold an Activity `Context`. That's fine while scoped to a dialog, but `mScoreboardDialog` can outlive `onDestroy` paths. | 3504, 3522 |
| L7 | `BluetoothLeService` holds `mBluetoothGatt` across activity lifetimes, which is intended. `BlasterManager` correctly stores the **application** context. | — |

**Thread-safety issues**

| # | Issue |
|---|---|
| C1 | `displayPlayerData()` spawns a raw `Thread` that writes non-volatile `mScore`/`mTeamScore` and calls `mTcpServer.setHostScore` off the main thread. Data race with main-thread writers. |
| C2 | Telemetry, timers and network receivers all run on the main thread and share ~40 mutable fields with no ownership model. Ordering is implicit. |
| C3 | `Globals` is a mutable singleton with public `volatile` fields plus 6 `Semaphore`s (137 uses in `TcpServer`). Repositories partially mirror it (`Globals.updatePlayerIDFromRepo`), so there are **two sources of truth**. |
| C4 | `GameEngine` uses its own `ScheduledExecutorService`, so its listener callbacks would arrive on a background thread. That's harmless today only because it is dead code. |

**Legacy concurrency inventory:** no `AsyncTask` anywhere. In `FullscreenActivity`: 12 × `new Handler`, 11 ×
`CountDownTimer`, 1 × `new Thread`, 4 × `runOnUiThread`. Elsewhere: `BluetoothLeService` (2 `Handler`),
`BlasterManager` (1 `Handler`), `TcpServer` (12 `Thread`, 1 `Handler`), `TcpClient`/`UDPListenerService`/`ServerDiscoveryManager`
(raw threads), `DedicatedServerActivity` (5 `CountDownTimer`).

**Other legacy/platform issues**

| # | Issue | Notes |
|---|---|---|
| P1 | Internal broadcasts are registered with `RECEIVER_EXPORTED` (`FullscreenActivity`, `BlasterManager`, `MapFragment`, `TcpClient`). Another app can spoof `TELEMETRY_DATA_AVAILABLE` (fake hits) or `NETMSG_*`. | Should be `RECEIVER_NOT_EXPORTED`. Senders already `setPackage`, so in-app delivery is unaffected. |
| P2 | `BluetoothLeService` is started with `startForegroundService` but calls `startForeground` only on GATT connect. On Android 12+ a slow or failed `connectGatt` can throw `ForegroundServiceDidNotStartInTimeException`. | |
| P3 | Manifest: `BLUETOOTH_PRIVILEGED` (system-only, never granted); `BLUETOOTH`/`BLUETOOTH_ADMIN` lack `maxSdkVersion="30"`. | |
| P4 | Deprecated `requestPermissions`/`onRequestPermissionsResult`. No rationale or permanently-denied handling. | |
| P5 | `connectWeapon()` calls `finish()` without `return` when BLE is unsupported. | 2281 |
| P6 | `MediaPlayer.create()` per sound, called at full-auto rates. It can return `null`, which then NPEs. | 2579 |
| P7 | `loadFragment()` is dead because `mMapFragment` is never assigned. The map fragment is inflated statically via `<fragment>` in `activity_fullscreen.xml`. | 2070 |

### 1.5 Divergence between the two decoders/engines (critical for Phase 2/3)

Switching the runtime from the activity's inline decoder to `BlasterManager` + `GameEngine` **as they stand** would
change gameplay. The differences:

| Behavior | Activity (live today) | `BlasterManager` / `GameEngine` (tested, dead) |
|---|---|---|
| Counter baseline | Re-baselined to the first packet of **each connection** | Starts at 0 and never re-baselines → spurious trigger/reload/shot-mode/**recoil-toggle** events on connect |
| Battery average | Seeded with one `16` sample | Unseeded |
| Short packet (< 15 B) | `ArrayIndexOutOfBounds` in a receiver → crash | Dropped (`null`) |
| Hit slot 2 | Considered only when slot 1 ≠ 0 | Reported independently |
| Hit de-dup | (player, shotId) across both slots, with grenade exclusions | None (manager); 500 ms cooldown (`GameEngine`) |
| Grenade pair/disarm | Decoded from raw nibbles | Not expressible (`TelemetryData` keeps `& 0x07` only) |
| Ammo | Trusts blaster byte 14 | `GameEngine` counts locally |
| Reload | Two-stage BLE command sequenced on write-finished + `mReloadTime` | `GameEngine` timer only, **sends no BLE commands** |
| Shield | Only on respawn, not on the first spawn | Also after every respawn, same as activity, but game start isn't modeled |
| Self-hit | Not filtered | Filtered |
| Damage | Per-shooter `PlayerSettings.damage` (network) or `DAMAGE_PER_HIT` | `abs(settingsRepo.getDamage())` |

**Conclusion:** `GameEngine` is not a drop-in domain engine. The new domain layer is a **faithful extraction of the
activity logic**. `GameEngine` and its tests stay until the new engine replaces them, and then both are deleted (D7).
The activity column is the behavior users have, so where the two differ, the activity wins and the tested-but-dead
`BlasterManager` semantics are changed to match it (D3).

### 1.6 Protocol timing invariants (must be preserved exactly)

| Event | Delay / rule | Current owner |
|---|---|---|
| GATT connected → `discoverServices()` | 500 ms, main looper | `BluetoothLeService` |
| CCCD descriptor missing → retry | 300 ms, once | `BluetoothLeService` |
| Characteristics discovered → `setRecoil(recoilEnabled)` | 500 ms | `BlasterManager` |
| First telemetry packet of a connection | `setShotMode` immediately; `setTeam` at +250 ms (if PID ≠ 0); `applyCurrentRecoilSetting` at +500 ms | Activity |
| Disconnect dialog dismissed → `applyCurrentRecoilSetting` | +600 ms | Activity |
| Reload | Stage-1 write → on `CHARACTERISTIC_WRITE_FINISHED` wait `mReloadTime` (default 1500 ms) → stage-2 write → on next write-finished, done | Activity |
| Telemetry watchdog | 5,000 ms tick; disconnect if no packet since last tick (only once communicating) | Activity |
| Scan give-up | 30,000 ms | Activity |
| Network HIT/OUT and SHOTFIRED rate limit | 400 ms | Activity |
| Server game end → local `endGame` fallback | 3,000 ms | Activity |
| Writes | Not queued; overlapping writes may fail silently (current behavior). **No GATT op queue will be introduced.** | — |

Only the recoil-after-discovery rule is (indirectly) touched by a test today. Each row gets a virtual-time test
**before** the code that owns it is converted or moved, so a conversion cannot silently shift a delay.

### 1.7 Behavioral bugs found (all approved for fixing; see §1.7.1 for when)

| ID | Bug | Location |
|---|---|---|
| B1 | Slot-2 network shooter ID uses `hit_by_player1 >> 2` instead of `hit_by_player2 >> 2`. Wrong attribution and friendly-fire check on double hits. | 2817 |
| B2 | Receivers are unregistered in `onPause`, but the 5 s watchdog keeps running. Backgrounding the app for > 5–10 s while connected likely triggers `handleDisconnect()` → rescan + "blaster disconnected" dialog with a connected device that isn't advertising. (Inferred from reading; **not reproduced on a device**.) | 2041–2047, 2543 |
| B3 | Short telemetry packet crashes the receiver (§1.5). | 2608 |
| B4 | L1 duplicate service bindings. | 2033 |
| B5 | P2 foreground-service start timeout. | `BluetoothLeService` |
| B6 | P5 `finish()` without `return`. | 2281 |
| B7 | C1 data race on scores. | 3401 |

#### 1.7.1 Fix schedule

| ID | Fixed in | Fix | Verification |
|---|---|---|---|
| B6 | Phase 1, standalone commit before any extraction | Add `return` after `finish()` in `connectWeapon()`. | Code review. It can't be exercised on a BLE-capable phone, so no device step. |
| B1 | Phase 1 step 4, the commit after `HitResolver` is extracted | Slot-2 network attribution and friendly-fire check use `hit_by_player2 >> 2`. | The `HitResolver` characterization test that pinned B1 flips to the correct shooter. Smoke test item 5 (double hit), ideally with two shooters on different teams. **[device]** |
| B3 | Phase 2 step 5, when the activity consumes `TelemetryData` | Malformed packets are dropped by `parseTelemetry` (§2.1.3 e). | New test: a 14-byte packet through the repository produces no event and no crash. **[device]** |
| B5 | Phase 2 step 4, the commit after `BluetoothLeService` is converted | Call `startForeground` (type `connectedDevice`, already declared in the manifest) from `onStartCommand`, before `connectGatt`, instead of on GATT connect. | Service test asserts `startForeground` happens before any GATT call. Smoke test item 6 with the blaster powered **off** at connect time (the slow-connect case). **[device]** |
| B2 | Phase 2 step 5 (watchdog moves to `BleConnectionManager`) | Per D4 (app-scoped), the watchdog and telemetry consumer no longer depend on activity receivers, so backgrounding can't starve the watchdog. | First **reproduce on a device** at the start of Phase 2 (it's inferred, not observed), then a virtual-time test: background > 10 s while connected → no disconnect. **[device]** |
| B4 | BLE binding: Phase 2 step 5. Network bindings: Phase 3 step 3. | `BleConnectionManager` and `NetworkSessionGateway` bind once on the application context; the activity stops binding in `onResume`. | Test: N resume cycles → one binding each. Device check: rotate or background the app repeatedly mid-game, then end the game (no stale disconnect callbacks). **[device]** for the BLE half. |
| P1 | Phase 1, standalone commit next to B6 | Register every remaining internal receiver (`FullscreenActivity`, `BlasterManager`, `MapFragment`, `TcpClient`) with `RECEIVER_NOT_EXPORTED`. Senders already `setPackage`. The `BlasterManager` receiver later disappears with D8. Treated as hygiene, not a security priority. | Smoke test (telemetry still arrives) and network smoke test (`NETMSG_*` still delivered). **[device]** |
| B7 | Phase 3 step 3, with `NetworkSessionGateway` | Scoreboard JSON parsed on `Dispatchers.Default`; scores written only to state on the main thread. | Engine/gateway test; network smoke test (scoreboard on host and client). |

The leak and lifecycle items behind these (L1–L5, P2, P5, P6) are covered by the same commits or by Phase 4 step 5.

---

## 2. Proposed architecture

```
┌──────────────────────────────── UI / Presentation ────────────────────────────────┐
│ FullscreenActivity (window + lifecycle orchestration only, ViewBinding)           │
│   ├─ GameScreenRenderer      (GameUiState → views; animations)                    │
│   ├─ DialogCoordinator       (one-shot UiEffect → dialogs/toasts/popups)          │
│   ├─ WindowController        (keep-screen-on, immersive bars)                     │
│   ├─ BlePermissionController (ActivityResult API)                                 │
│   └─ collects GameViewModel.uiState / effects with repeatOnLifecycle              │
├──────────────────────────────── Domain / State ───────────────────────────────────┤
│ GameViewModel (UDF: intents → reducer → StateFlow<GameUiState>, Channel<UiEffect>)│
│   ├─ BlasterSessionEngine   (R5–R12 faithful extraction: edges, hits, reload,     │
│   │                          spawn/shield timers, battery, watchdog)              │
│   ├─ HitResolver / GrenadeDecoder (pure functions, fully unit tested)             │
│   ├─ ShotModePolicy, RecoilPolicy (pure)                                          │
│   ├─ NetworkSessionGateway  (adapter over UDPListenerService/TcpClient/TcpServer) │
│   └─ FeedbackPort → SoundEffectPlayer / HapticsPlayer                             │
├──────────────────────────────── Data / Hardware ──────────────────────────────────┤
│ BleDeviceRepository  (Kotlin; SharedFlow<BlasterEvent>, StateFlow<BleConnState>)  │
│ BleConnectionManager (Kotlin; scan, bind service, BT adapter state, timeouts)     │
│        │ owns (converted to Kotlin in Phase 2; protocol behavior frozen)          │
│        ▼                                                                          │
│ BlasterManager ◄── events (D8) ── BluetoothLeService ── GattIo ── BluetoothGatt    │
│        │ uses                                                                     │
│ GattProtocolHandler / GattAttributes (frozen wire format, Kotlin implementation)  │
│ UserPreferencesRepository (SharedPreferences "SimpleCoil", same 13 keys)          │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### 2.0 Language & build decision (**Decision D2**)

The brief asks for Coroutines, `Flow`, `StateFlow` and sealed hierarchies. The project is pure Java. Proposal:

- Add the Kotlin Android plugin (Kotlin 2.x, compatible with AGP 8.13), `kotlinx-coroutines-android`,
  `lifecycle-viewmodel-ktx`, `lifecycle-runtime-ktx`, `activity-ktx`; tests add `kotlinx-coroutines-test`.
- Raise `compileOptions` to Java 17 and set `kotlinOptions.jvmTarget = 17`. The existing tests run on JDK 21 already.
- **New** classes are written in Kotlin.
- **The BLE layer is converted to Kotlin** in Phase 2, one class per commit, in dependency order:
  `GattAttributes` → `GattProtocolHandler` (+ `TelemetryData`) → `BlasterEventListener` → `BlasterManager` →
  `BluetoothLeService`. Each conversion is preceded by the test rewrites from §1.3.3 and followed by the on-device
  smoke test (§3.0).
- `FullscreenActivity` is converted to Kotlin at the end of Phase 4, once it is a thin renderer. D1 removes the
  reflected constants that would otherwise block it.
- The network stack (`Globals`, `TcpServer`, `TcpClient`, `UDPListenerService`, `NetMsg`) and
  `DedicatedServerActivity` stay Java in this roadmap. Kotlin APIs they consume get `@JvmStatic`/`@JvmOverloads`
  where needed. Converting them is a follow-up roadmap with its own wire-format freeze (§1.3.2 item 4).

The goal at the end of Phase 4 is that the app module's game and BLE code is Kotlin, and the only remaining Java is
the network stack listed above, behind `NetworkSessionGateway`.

Alternative if Kotlin is rejected: same layering in Java with `LiveData` + `ExecutorService`. The phase plan is unchanged
except that the Phase 2 conversions become refactors in place.

### 2.1 Data / Hardware layer

#### 2.1.1 Frozen protocol core (implementation may change)
`GattAttributes`, `GattProtocolHandler` (builders, parser, constants) and `BluetoothLeService` (GATT I/O, CCCD,
timing) produce exactly the bytes, UUIDs, write types, descriptor sequence and delays they produce today. Their
**implementation** may change: Kotlin conversion, the `GattIo` seam, an injected scope instead of `Handler`, and
an in-process event transport instead of broadcasts (D8). Every such change is checked three ways: the §1.3.1
catalog stays green, the §1.6 timings are asserted under virtual time, and the on-device smoke test passes.

#### 2.1.2 `BleConnectionManager` (new, Kotlin, application-scoped)
Absorbs R1, R2, R12-transport and R21 from the activity:
- **Scan:** same unfiltered `startScan`, same software match (`name.startsWith("SRG1")` when no saved address,
  else exact MAC), same 30 s give-up. No `ScanFilter` is introduced, because Android filters can't express a name prefix.
- **Bind:** `startForegroundService` + `bindService` on the **application** context. This fixes L1/L2. It hands the
  service to `BlasterManager.setBluetoothLeService()`, exactly as the activity does today.
- **Receiver:** calls `BlasterManager.connect(address)`, which subscribes the manager to the service's events. The
  activity's `mGattUpdateReceiver` is deleted, so **one consumer** handles GATT events.
- **BT adapter state** receiver → auto-reconnect when turned on.
- Exposes `connectionState: StateFlow<BleConnectionState>`:
  ```kotlin
  sealed interface BleConnectionState {
      data object Idle : BleConnectionState
      data object Scanning : BleConnectionState
      data class Connecting(val address: String) : BleConnectionState
      data class ServicesDiscovered(val address: String) : BleConnectionState
      data class Communicating(val address: String, val blasterType: Byte) : BleConnectionState
      data class Disconnected(val reason: Reason) : BleConnectionState  // ScanTimeout, LinkLost, WatchdogTimeout, User
  }
  ```
- Timers use a coroutine `scope` built on `SupervisorJob() + Dispatchers.Main.immediate`, with `delay()` replacing
  `CountDownTimer`/`Handler`. **The constants in §1.6 are unchanged.** The scope and dispatcher are constructor-injected so
  new tests can use `StandardTestDispatcher` + virtual time.

#### 2.1.3 `BleDeviceRepository` (new, Kotlin) — the bridge to the validated models
- Subscribes to `BlasterManager`'s events (while `BlasterEventListener` still exists, it implements it and is
  registered via `setListener`). It replaces `MainViewModel` as the consumer.
- Re-emits **every** callback, in order, into a single ordered stream:
  ```kotlin
  val events: SharedFlow<BlasterEvent>   // MutableSharedFlow(replay = 0, extraBufferCapacity = 256)
  sealed interface BlasterEvent {
      data class Connected(val address: String?) : BlasterEvent
      data object Disconnected : BlasterEvent
      data object ServicesDiscovered : BlasterEvent
      data class TypeDiscovered(val type: Byte) : BlasterEvent
      data class Telemetry(val data: GattProtocolHandler.TelemetryData) : BlasterEvent
      data object WriteFinished : BlasterEvent
      // edge events forwarded verbatim (trigger, reload, thumb, power, battery, hit, playerId)
  }
  ```
  One stream instead of one per event type preserves the relative order of `ServicesDiscovered` →
  `Telemetry` → `WriteFinished`, which the reload state machine depends on. Emission uses `tryEmit` from the main
  thread, and an overflow is logged. 256 events is about 25 s of telemetry at the observed rate, so overflow means a
  stalled collector, which is a bug.
- Outbound commands are thin pass-throughs to `BlasterManager` (`setTeam`, `startReload`, `finishReload`,
  `setShotMode(mode, firingMode)`, `setRecoil`). **No packet is built outside `GattProtocolHandler`.**

The manager is changed to reproduce the activity's (live) semantics. Tests that pinned the dead-path semantics are
updated in the same commit under §1.3.4 rule 4:

| Need | Mechanism | Test impact |
|---|---|---|
| (a) Re-baseline counters on the first packet of each connection | `BlasterManager` re-baselines on the first telemetry after a connect: it copies counters into `last*` **without** raising edge events. | Edge tests gain a "connected" step or an explicit first baseline packet. A new test pins "no spurious trigger/reload/shot-mode/recoil-toggle edge on connect". |
| (b) Battery seed of 16 | `BlasterManager` seeds its rolling average with one `16`, exactly as the activity does (**Decision D3**). | The battery-average tests' expected values are recomputed for the seed, and the commit message says so. |
| (c) Grenade nibbles | `TelemetryData` (Kotlin `data class`) carries `rawShotIdByte1/2`, populated by `parseTelemetry` (D6). | Existing field values are unchanged; `shotId1 == 7` for the pair packet still holds. Reference decoder parity becomes a direct assertion. |
| (d) Slot-2-only-if-slot-1 rule, de-dup | Done in the domain from `Telemetry.data` (`HitResolver`), not in the manager. The manager keeps reporting both slots. | None. |
| (e) Short-packet crash | Disappears automatically (`parseTelemetry` returns `null`). This is the only observable change (B3), and it only affects malformed input. | None. |
| (f) Exported receivers (P1) | Disappears for BLE when D8 replaces broadcasts with an in-process transport. Otherwise it's a one-line `RECEIVER_NOT_EXPORTED` change in `BlasterManager.connect`. | None. Approved; scheduled in §1.7.1. |

#### 2.1.4 `UserPreferencesRepository` (new)
Wraps `SharedPreferences("SimpleCoil")` with the **exact existing keys** (`PlayerName`, `PlayerID`, `SelectedPlayerID`,
`FiringMode`, `ShotMode`, `RecoilEnabled`, `GameMode`, `TimeLimit`, `LivesLimit`, `ScoreLimit`, `DeviceAddress`).
Persisted user data is a compatibility surface too. Migrating to DataStore is out of scope for this roadmap.

#### 2.1.5 `NetworkSessionGateway` (new adapter; network code unchanged)
Owns the UDP/TCP service bindings (application context, bound once) and the 21-action `NETMSG_*` receiver. It
exposes `SharedFlow<NetworkEvent>` and command methods (`sendHit(id)`, `sendOut(id)`, `sendShotFired()`,
`startGame()`, `leave()`, …) that build **byte-identical** strings to today's inline `TCPMESSAGE_PREFIX + TCPPREFIX_MESG + …`.
`TcpClient`, `TcpServer`, `UDPListenerService`, `NetMsg` and `Globals` are not modified.

### 2.2 Domain / State layer

#### 2.2.1 `BlasterSessionEngine` (new, Kotlin, plain class, no Android types)
A line-by-line extraction of R5–R12 from `processTelemetryData`, the reload/spawn/shield/countdown methods, and the
watchdog. Inputs: `BlasterEvent`, `NetworkEvent`, and user intents. Outputs: an immutable `SessionState` and a stream of
`SessionEffect` (send BLE command, send network message, play sound, vibrate, show hit animation, toast).
Pure helpers live in separate files with exhaustive tests:
- `HitResolver`: de-dup, slot rules, friendly fire, per-shooter damage, grenade exclusion, network attribution
  (**including B1 as-is** until approved).
- `GrenadeDecoder`: implemented to agree with `RecoilProtocolFixtures`' reference decoder. New tests assert equality
  against the fixture functions for all 256 × 256 inputs.
- `ShotModePolicy` (thumb cycling + `updatePlayerSettings` coercion), `RecoilPolicy` (server-forced vs toggle),
  `BatteryLevelPolicy` (seeded average + pistol 14/12/10 and rifle 21/18/15 thresholds).
- `ReloadStateMachine` (`None/Started/Finishing/Eliminated`), driven by `WriteFinished` and the reload timer.

Timers use injected `CoroutineScope` + `delay` with the same durations. Game config is read through a
`GameConfig` interface backed by **`Globals`**, the activity's current single source of truth, so there is no new divergence
from the repositories (C3). Consolidating `Globals` into the repositories is out of scope.

#### 2.2.2 `GameViewModel` (new; replaces `MainViewModel` usage)
- UDF: `fun onIntent(intent: GameIntent)` → engine → `StateFlow<GameUiState>`; one-shot `effects: Flow<UiEffect>`
  backed by `Channel(BUFFERED)`.
  ```kotlin
  sealed interface GameUiState {
      data class Connect(val status: ConnectStatus, val canReconnect: Boolean, val buttonsEnabled: Boolean) : GameUiState
      data class Lobby(val hud: Hud, val network: NetworkPanel, val controls: PreGameControls) : GameUiState
      data class InGame(val hud: Hud, val phase: Phase /* Spawning(n)/Alive/Shielded(n)/Eliminated(by)/OutOfLives */,
                        val timer: GameTimer, val network: NetworkPanel) : GameUiState
  }
  ```
  `Hud` holds health, max, shots, reloading, shot mode, firing mode, recoil, battery icon, team label/color,
  score, team score, eliminations/lives and hits taken. Every field maps 1:1 to a view mutation that exists today.
- `MainViewModel`, `GameEngine` and `MainActivity` (dead, not in the manifest) are deleted once `GameViewModel` is
  live, together with `MainViewModelTest` and `GameEngineTest` (D7). The repository tests inside `MainViewModelTest`
  that still describe live code move to the matching repository test files first.

#### 2.2.3 Process-lifetime decision (**Decision D4**)
`BleConnectionManager`, `BleDeviceRepository`, `BlasterSessionEngine` and `NetworkSessionGateway` live in an
application-scoped container (`SimpleCoilApp : Application`, manual DI; no Hilt in this roadmap). Today telemetry
processing stops in `onPause` (B2).

**Decided: app-scoped.** While the app is backgrounded the session keeps running in full:
- **Hits:** telemetry is decoded, damage/eliminations/lives applied, and HIT/OUT messages sent.
- **Reloads:** the button, auto-reload and reload-on-empty all work, including the two-stage BLE command sequence.
- **Client-server data:** the TCP/UDP traffic is processed (game start/end, player settings, scoreboard, team changes).

The process stays alive because `BluetoothLeService` runs as a `connectedDevice` foreground service, which is why
the B5 fix matters. Sound and haptics come from app-scoped players (§2.3), so they keep playing. Purely visual work
(animations, view updates) doesn't happen while backgrounded. On return, the activity renders the current
`GameUiState`. One-shot UI effects that are no longer relevant, such as a disconnect dialog for a link that has since
recovered, are dropped instead of replayed. This also fixes B2.

New tests: a virtual-time engine test with no UI collector proves that hits, reloads and network events are processed
and the outbound BLE bytes / network strings are produced. Device check: background the app mid-game for > 30 s while
being shot, reloading, and with the host ending the game, then foreground and verify health, ammo and score. **[device]**

### 2.3 UI / Presentation layer
- **ViewBinding** (enable `buildFeatures.viewBinding`) over the **unchanged** `activity_fullscreen.xml`, so the
  layout is pixel-identical. Compose is explicitly deferred: it would rewrite all 45 views at once with no visual
  regression tests.
- `GameScreenRenderer`: renders `GameUiState`, with all visibility rules moved here from the scattered
  `display*`/`update*` methods. Hit/score/shots-fired animations keep their 400/800 ms durations, which must match `res/anim`.
- `DialogCoordinator`: player name, server IP, game limit, team ratio, end-game confirm, blaster-disconnected,
  scoreboard (`DialogFragment`s so they survive recreation), popups/menus.
- **Sound routing:** `SoundEffectPlayer` interface; the implementation delegates to the existing `AudioHapticsManager`
  (SoundPool, preloaded), replacing per-call `MediaPlayer.create` (P6). The same raw resources are played on the same
  triggers. `HapticsPlayer` keeps the 250 ms one-shot. The effects are emitted by the engine and consumed app-side, not by the Activity.
- `WindowController`: keep-screen-on and immersive navigation-bar hiding with today's exact triggers.

### 2.4 Permissions layer
`BlePermissionController` (Activity-owned, `ActivityResultContracts.RequestMultiplePermissions`):

| API level | Requested for "Connect blaster" (behavior-preserving set) |
|---|---|
| 31+ | `BLUETOOTH_SCAN` (`neverForLocation`), `BLUETOOTH_CONNECT`, `ACCESS_FINE_LOCATION` (needed by the GPS map feature), `POST_NOTIFICATIONS` on 33+ |
| 23–30 | `ACCESS_FINE_LOCATION` (required for BLE scan) |
| 21–22 | install-time |
| QR button | `CAMERA`, on demand |

Adds rationale UI, permanently-denied handling (deep link to app settings), and BT-enable via
`ACTION_REQUEST_ENABLE` only after `BLUETOOTH_CONNECT` is granted (the existing order). Manifest cleanup (P3) is
approved: remove `BLUETOOTH_PRIVILEGED`, add `maxSdkVersion="30"` to `BLUETOOTH`/`BLUETOOTH_ADMIN`. Results flow to
the ViewModel as `GameIntent.PermissionsResult`.

---

## 3. Multi-phase execution plan

Each phase is a series of small commits, and each commit keeps the gate green. "Gate" = `./gradlew test`
(0 failures) **and** `./gradlew assembleDebug`, plus at phase end the §1.3.1 catalog still fully asserted. Steps
marked **[device]** affect the BLE layer and end with the on-device smoke test before the next step starts.

### 3.0 On-device verification

**What counts as BLE-affecting.** Any change to: `GattAttributes`, `GattProtocolHandler`, `TelemetryData`,
`BlasterManager`, `BluetoothLeService`, `GattIo`; scanning, binding or connection code; the BT-adapter or
permission flow in front of a connect; the path from telemetry to outbound BLE commands (first-packet
shot-mode/team/recoil writes, reload stages, recoil toggle); or anything that owns a §1.6 delay. Pure UI,
network-only and test-only commits are not BLE-affecting.

**BLE smoke test (~10 min, run with a pistol, and a rifle when available).**
1. Fresh install → permissions → scan → connect by `SRG1` name; connect by saved MAC; connect by QR.
2. Blaster type detected (toast), team set, recoil matches the setting on connect.
3. Trigger fires; every shot mode × firing mode applies; recoil toggle (thumb/power) works.
4. Reload button: two-stage reload completes and ammo refills; auto-reload after 3 empty pulls.
5. Take a hit, a double hit, grenade damage; grenade pair and disarm.
6. Battery icon is sane. Power off the blaster → disconnect dialog → power on → reconnect. Toggle BT off/on → auto-reconnect.

**Recording.** Results go in the PR / commit description: build hash, blaster model(s), pass/fail per item. A failure
blocks the next step. The fix-or-revert decision is made before continuing.

**Protocol trace (recommended).** Phase 1 adds a debug-only log line for every outbound write (characteristic, hex
bytes, timestamp) and every inbound telemetry packet. A scripted session (connect, fire, reload, toggle recoil) on the
baseline build gives a reference trace. Each [device] step compares its trace against it: same bytes, same order,
delays within scheduler jitter. The logger is read-only, so it doesn't count as a protocol change.

The developer runs the device tests on their own blasters; they can't be automated in CI.

### Phase 1 — Baseline, tooling & safety net
1. Record the baseline: 168/168 in both variants, `assembleDebug` OK (**done during this audit**). Re-run at phase start.
2. Add the build tooling from D2 (Kotlin, coroutines, lifecycle-ktx, coroutines-test, Java 17), then re-run the gate.
   Java 17 is the only change that can affect existing tests; it's verified on its own commit.
3. Add the debug protocol-trace logger and capture the baseline trace on a device. **[device]**
4. Add **characterization tests** that pin today's activity behavior *before* anything moves. Because the logic is
   still inline in the Activity, these tests target the pure helpers as they are extracted **verbatim**
   (`HitResolver`, `GrenadeDecoder`, `ShotModePolicy`, `RecoilPolicy`, `BatteryLevelPolicy`, QR parser, scan
   matcher). They use `RecoilProtocolFixtures` golden packets and cross-check `GrenadeDecoder` against the fixture
   reference decoder. B1 is pinned as current behavior and annotated as a known bug. Moving the activity's inline
   decoding onto these helpers is BLE-affecting. **[device]**

Exit: gate green; characterization tests green against the extracted helpers, which the activity now calls (a pure
move); baseline protocol trace captured.

### Phase 2 — BLE layer modernization & extraction
Each step is a commit (or a short series) followed by the device smoke test.

1. **Seams + test rewrites (no behavior change).** Introduce `GattIo` and an injected `CoroutineScope` for the
   deferred writes in `BluetoothLeService`/`BlasterManager`. Rewrite the coupled tests from §1.3.3 to use the seams
   instead of reflection, and add virtual-time tests for every §1.6 row owned by these classes. Everything is still
   Java. **[device]**
2. **Convert `GattAttributes` and `GattProtocolHandler` to Kotlin**, with `TelemetryData` as a `data class` with
   `rawShotIdByte1/2` (D6). Their tests are ported to Kotlin with golden vectors moved verbatim. **[device]**
3. **Convert `BlasterManager` to Kotlin** and remove the singleton (the app container owns the instance). Adopt the
   live semantics (§2.1.3 a, b) with their test updates in the same commit. Replace `BlasterEventListener` with the
   `BlasterEvent` stream. **[device]**
4. **Convert `BluetoothLeService` to Kotlin** and, if D8 is approved, replace its broadcasts with the in-process
   event transport. **[device]**
5. **Add `BleDeviceRepository` and `BleConnectionManager`** (scan, bind, adapter state, scan timeout), and switch the
   activity over: remove `mBluetoothLeService`, `mBLEServiceConnection`, `mLeScanCallback`, `mGattUpdateReceiver`
   and `mBluetoothReceiver`. The activity consumes `BleDeviceRepository.events` and feeds the **existing** inline
   handler bodies, still in the activity. This keeps the game logic unchanged while the transport changes. Make sure
   `MainViewModel` is no longer a consumer *before* the manager's event subscription goes live; otherwise the dead
   `GameEngine` path wakes up and double-processes events. **[device]**
6. New tests: connection-state transitions, scan matching, event ordering through the repository, and an end-to-end
   "golden packet in → expected outbound packet bytes out" test using a fake `GattIo` (for example: first packet →
   shot-mode config immediately, set-team at 250 ms, recoil at 500 ms).

Exit: gate green; BLE layer is Kotlin; protocol trace matches the baseline; full smoke test passed on the final
commit of the phase.

### Phase 3 — Presentation & state extraction
1. Introduce `BlasterSessionEngine` and move R5–R12 out of the activity (reload, spawn, shield, countdown, watchdog,
   battery, hits). The activity renders its state. This moves the code that issues first-packet and reload
   writes. **[device]**
2. Resolve D1: the legacy offset/grenade constants leave `FullscreenActivity`, and `LegacyTelemetryDecoderConstantsTest`
   is rewritten to check parity between `GattProtocolHandler` and `GrenadeDecoder`.
3. Introduce `NetworkSessionGateway` and move R13/R14 (fixes C1: JSON parsing on `Dispatchers.Default`, results posted
   to state). Not BLE-affecting, but the network smoke test (host/join/dedicated server) runs on a device.
4. Introduce `GameViewModel` with `StateFlow<GameUiState>` + `UiEffect` channel; the activity becomes a renderer.
5. Decouple audio and haptics through `SoundEffectPlayer`/`HapticsPlayer` → `AudioHapticsManager`.
6. Introduce `UserPreferencesRepository`; remove all direct `SharedPreferences` use from the activity. `DeviceAddress`
   and `RecoilEnabled` feed the connect path. **[device]**
7. New tests: engine reducer tests per state transition (spawn → alive → hit → eliminated → respawn → shield; reload
   stages; lives limit; score limit; server-driven start/end), each asserting the outbound BLE bytes and network
   strings it produces.

Exit: gate green; activity contains no game logic and no BLE/network code; protocol trace matches the baseline.

### Phase 4 — UI & Activity modernization
1. ViewBinding; `GameScreenRenderer`, `DialogCoordinator` (dialogs as `DialogFragment`s), `WindowController`.
2. `BlePermissionController` (ActivityResult API) and manifest permission cleanup (P3, approved: remove `BLUETOOTH_PRIVILEGED`; add `maxSdkVersion="30"` to `BLUETOOTH`/`BLUETOOTH_ADMIN`). Device check on Android 12+ and, if available, an Android ≤ 11 phone. **[device]**
3. `FullscreenActivity` reduced to: inflate binding, wire controllers, `repeatOnLifecycle(STARTED)` collection,
   back handling, window flags, QR/permission/BT-enable launchers. Target size ≤ 300 lines. It keeps its class name
   (it is the launcher component, so renaming would break home-screen shortcuts).
4. Convert `FullscreenActivity` to Kotlin. **[device]**
5. Remove static mutable fields (L5); cancel all remaining timers/callbacks via scopes (L3/L4).
6. Delete `GameEngine`, `MainViewModel`, `MainActivity` and their tests (D7).
7. Confirm every §1.7 bug is closed per the §1.7.1 schedule. Any that slipped are fixed here, one commit each.

Exit: gate green; game and BLE code is Kotlin.

### Phase 5 — Full regression & build verification
1. `./gradlew clean test assembleDebug`. All tests pass in both variants.
2. **Protocol coverage audit.** Walk the §1.3.1 catalog and §1.6 table and record, for each item, the test that asserts
   it now. Any item without one blocks the release. Cross-check that every golden array in `RecoilProtocolFixtures` at
   `316a81c` still exists byte-identical (`git diff 316a81c` on the fixtures, or a script comparing the arrays if the
   file was ported to Kotlin).
3. Protocol trace of the final build matches the Phase 1 baseline trace.
4. Lint (`./gradlew lintDebug`) with no new errors.
5. On-device checklist (pistol and rifle if available): scan/connect/QR connect, reconnect after BT toggle, every
   shot mode × firing mode, recoil toggle/forced, reload button, auto-reload after 3 empty pulls, reload-on-empty,
   hits/double hits/grenade damage/pair/disarm, elimination/respawn/shield, lives/time/score limits, network
   host/join/dedicated server, scoreboard, background → foreground mid-game, process death.

---

## 4. Decisions (all resolved)

| ID | Question | Decision |
|---|---|---|
| **D1** | `LegacyTelemetryDecoderConstantsTest` reflects `FullscreenActivity.RECOIL_OFFSET_*` and private `GRENADE_*`. Its own Javadoc says to delete those assertions once the decoder moves. | **Approved.** Do that in Phase 3: the constants leave `FullscreenActivity`, and the test is rewritten to check parity between `GattProtocolHandler` and `GrenadeDecoder`. This unblocks the Kotlin conversion of the activity. |
| **D2** | Introduce Kotlin + coroutines and convert the BLE layer and `FullscreenActivity` (§2.0)? | **Approved.** The network stack stays Java in this roadmap. |
| **D3** | Battery average and counter baseline: the activity seeds with one `16` and re-baselines per connection; `BlasterManager` does neither, and tests pin that. | **Approved.** Change `BlasterManager` to the activity's semantics and update those tests in the same commit. The UI behavior users see is unchanged. |
| **D4** | App-scoped session (keeps processing while backgrounded; fixes B2) vs. lifecycle-gated (identical to today). | **Decided: app-scoped.** Hits, reloads and client-server data keep being processed while backgrounded (§2.2.3). Fixes B2. |
| **D5** | Which bugs from §1.7 to fix, and when? | **Decided:** fix all of B1–B7, each in its own commit right after the code it lives in is extracted (never inside an extraction commit). Schedule in §1.7.1. |
| **D6** | `TelemetryData` carries `rawShotIdByte1/2` (the test comment anticipates this). | **Approved.** Done as part of its Kotlin conversion in Phase 2. |
| **D7** | `GameEngine`/`MainViewModel`/`MainActivity` are unused once `GameViewModel` is live. | **Approved.** Delete them and `GameEngineTest`/`MainViewModelTest` in Phase 4, after moving any live repository assertions out. |
| **D8** | Replace `BluetoothLeService`'s internal broadcasts with an in-process transport (a `SharedFlow` exposed through the local binder). Internal Intents aren't part of the BLE protocol. | **Approved.** It removes the exported receiver for BLE events (P1) and the Intent plumbing. The event order and payloads stay identical and are asserted by the rewritten `BluetoothLeServiceGattTest`. |
| **P1** | Register the remaining internal receivers as not exported? | **Approved** as a one-line hygiene fix per receiver. Spoofing by other apps isn't a concern, so it's not a security priority. Phase 1 (§1.7.1). |
| **P3** | Manifest permission cleanup? | **Approved.** Remove `BLUETOOTH_PRIVILEGED`; add `maxSdkVersion="30"` to `BLUETOOTH`/`BLUETOOTH_ADMIN`. Phase 4 step 2. |

## 5. Explicitly out of scope
Changing any BLE byte, UUID, delay or write type; adding a GATT operation queue; changing `NetMsg`/TCP/UDP wire
formats; converting the network stack (`Globals`, `TcpServer`, `TcpClient`, `UDPListenerService`, `NetMsg`) to Kotlin;
consolidating `Globals` into repositories; `DedicatedServerActivity` refactor; Compose migration; DataStore
migration; Hilt; raising `targetSdk` to 35 (recommended separately, since it has its own behavior changes).
