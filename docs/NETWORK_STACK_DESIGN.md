# SimpleCoil Network Stack: I/O, Events and Kotlin — Design Document

Status: **APPROVED (2026-10-13).** All decisions in §8 are resolved. No source has been changed.

Revision 2 (2026-10-13): decisions recorded (§8). The wire stays JSON on org.json (Q2). `MapFragment`'s GPS broadcasts
move in Phase 2 (Q4): the map work is waiting for this roadmap to finish, so nothing else will touch them first.
Branch audited: `V2-refactor` @ `b460c02` ("Ignore app/debug folder"). Baseline: 443 unit tests passing, `lintDebug`
0 errors.
Date: 2026-10-13
Part of: `MODERNIZATION_ROADMAP.md` (ordering across documents).

---

## 0. Summary

`REFACTOR_DESIGN.md` §5 left the network stack (`TcpServer`, `TcpClient`, `UDPListenerService`, `NetMsg`, discovery)
in Java, behind `NetworkSessionGateway`, as a follow-up with its own wire-format freeze. This is that follow-up.

The audit (§3) found that the stack's problems are mostly in its I/O model, not its language:

- The server writes every outgoing message on a **new thread**, so two messages to the same phone can arrive in the
  wrong order. Several messages are full snapshots, and when a stale one arrives last it wins (N1).
- **A single slow phone can freeze the server for everyone.** The one read loop blocks on a half-received frame
  (N3), and it also writes pings and ACKs inline, so it blocks when a phone stops reading (N2).
- **A phone can hang forever on a half-received frame** without ever noticing that the connection is dead. When that
  happens the reconnect logic from `NETWORK_RECONNECT_DESIGN.md` never starts (N4).
- **Short or unexpected frames crash the app.** On the client this includes the server's own `VERSIONERROR` reply
  (N5).
- The app talks to itself through **system broadcasts**: 48 `sendBroadcast` sites and string actions, some of them
  shared by the server and client roles (N8).

The plan has three phases. Each one ships on its own and is verified on devices:

1. **I/O fixes, in Java** (§5). Each connection gets one writer queue and one reader thread with a socket timeout. A
   frame reader survives partial frames, and a single server dispatcher thread runs the game logic in order. A frame
   parser never throws. These are bug fixes, so they land before any conversion, which keeps a regression bisectable
   to either a fix or a move (the `REFACTOR_DESIGN.md` §0 rule 5 convention).
2. **An in-process event bus** (§6) in place of the `NETMSG_*` broadcasts: typed `ClientEvent` and `ServerEvent`
   flows, the same pattern D8 used for BLE.
3. **Kotlin conversion and a typed wire model** (§7). One class per commit, with golden wire fixtures captured before
   the first conversion.

The v07 wire protocol does not change (§1).

---

## 1. Ground rules

1. **The v07 wire protocol is frozen.** That covers the framing (`writeUTF`: 2-byte length plus modified UTF-8), the
   `SimpleCoil:07MESG` / `SimpleCoil:07JSON` headers, the bare `ping` / `pong` / `VERSIONERROR`, every command and
   its argument format, and every JSON key with its type and whether it is optional. It also covers the UDP discovery
   strings and ports 17500/17510. A build from this roadmap and a v07 build already in the field must play in the
   same game.
2. **JSON is compared semantically, not byte for byte.** Key order and number formatting may change wherever both
   parsers accept the result (org.json on both ends today). A key, a type or a presence rule may not change. The
   golden fixtures in §7.2 enforce this.
3. **Protocol timings are frozen.** Server: ping after 1 s and 2 s of silence, drop after 3.5 s. Client: drop after
   3.5 s, retry delays 1/2/4/5 s, an immediate retry when Wi-Fi returns. GPS relay every 1 s, a full update every
   20th. Rate limits stay in `BlasterSessionEngine`. A timing may become *more* accurate (for example a 100 ms poll
   that drifts to 130 ms under load); it may not change its nominal value.
4. **Gate.** Every commit keeps `./gradlew test` at 0 failures and `assembleDebug` green. A test that pins wire
   values (bytes, keys, commands) may only change in *how* it reaches them, never in what it expects (as in
   `REFACTOR_DESIGN.md` §1.3.4).
5. **Bugs are fixed in their own commits**, never inside a move or conversion. The fix commit updates the test that
   pinned the old behavior.
6. **Network-affecting commits are verified on devices** with the network smoke test (§9.1) before the next step
   starts. Network-affecting means any change to `TcpServer`, `TcpClient`, `Connection`, `GameRoster`,
   `UDPListenerService`, `ServerDiscoveryManager`, the frame or JSON code, or anything that publishes or consumes
   network events.

---

## 2. Current state

### 2.1 Components

| Component | Lines | Role |
|---|---|---|
| `TcpServer.java` | 1,384 | `Service`. Accept loop (`runTcpServer` :707), one polling `ClientThread` (:823) for every socket, all game rules, snapshot builders, GPS relay on a main-thread `Handler` (:137, :475). |
| `network/server/Connection.java` | 124 | One socket. `send()` writes synchronously under `outLock` (:71). `available()` / `readUTF()` for the poll loop. |
| `network/server/GameRoster.java` | 667 | Players, connections, phase, game id, MAC cache. `synchronized` throughout. Already unit-tested (`GameRosterTest`). |
| `TcpClient.java` | 919 | `Service`. Reconnect loop (`runTcpClient` :350) that polls `available()` (:406). A single writer executor (:101, added by reconnect step 8). `parseGameInfo` (:668–892) writes the repositories and broadcasts. |
| `UDPListenerService.java` | 198 | `Service` around `ServerDiscoveryManager`. Broadcasts `SERVERCREATED`, `SERVERREPLY`, `FAILEDTOJOIN`. |
| `network/discovery/ServerDiscoveryManager.java` | 327 | UDP beacon (server) and discovery (client), one thread each. |
| `NetMsg.java` | 81 | Wire commands **and** broadcast actions **and** Intent extra names, all in one namespace. |
| `NetworkSessionGateway.kt` | 431 | Turns the client-side broadcasts into `NetworkEvent`s (`toEvent` :365, `intentFilter` :405). Builds message strings. Parses the scoreboard. |
| `network/client/ClientOutbox.kt`, `ReconnectPolicy.kt` | 97, — | Reliable eliminations and retry pacing. Kotlin already, unit-tested. |

### 2.2 Threads today

**Server**

```
accept thread ──► GameRoster.accept() ──► (first connection only) starts ClientThread
ClientThread (one):  for each connection: available()>0 ? readUTF() + handle inline : count silence, ping/drop
                     sleep(100 ms − elapsed)
any caller thread ──► sendTCPMessageAll/ID/Team ──► new Thread ──► Connection.send() (blocking write)
main thread ──► Handler.postDelayed(1 s) ──► builds GPS JSON under PlayerRepository lock ──► new Thread ──► send
UI / Activity ──► startGame/endGame/kick/rebalance/... ──► new Thread ──► sends + broadcasts
```

**Client**

```
reader thread (one): connect, then available()>0 ? readUTF() + parse + broadcast : sleep(100 ms), count silence
writer executor (one): every write in call order                      ← already correct
main thread: NETMSG_GPSLOCUPDATE receiver ──► writer
```

### 2.3 Message flow inside the app

`TcpClient` and `UDPListenerService` broadcast `NETMSG_*` intents (with `setPackage`). `NetworkSessionGateway`
receives them on the main thread and re-emits typed `NetworkEvent`s on a `SharedFlow` (capacity 64, drops with a log).
`TcpServer` broadcasts `JOIN`, `LEAVE`, `STARTGAME`, `ENDGAME`, `SESSIONRESET`, `PLAYERDATAUPDATE` and `ERROR` to
`DedicatedServerActivity`. Several of these action strings are the same ones `TcpClient` uses for the play screen. The
gateway stops listening (`handedOff`, :124) while the dedicated server owns the network. `MapFragment` and `TcpClient`
exchange GPS through `GPSLOCUPDATE` / `GPSDATAUPDATE` / `GPSSETTING`.

---

## 3. Defects and debts

N1–N5 are bugs. They are fixed in Phase 1.

| # | Defect | Effect | Where |
|---|---|---|---|
| N1 | **Server sends can be reordered.** Each `sendTCPMessageAll/ID/Team` call, and each admin action, starts its own thread. Two calls in a row race for the connection's `outLock`. Messages that carry a **full snapshot** then let a stale copy win: `grenadepairings` (the lock at :1151 serializes the pairing but not the delivery, so its comment's promise doesn't hold); game info (End Game sends POST_GAME, and an immediate Reset sends LOBBY, from two threads); and GPS (a `gpsfullupdate`, which clears the map, can land after a newer delta). The same race can deliver `ELIMINATED` after `ENDGAME`. | Wrong grenade owner until the next pairing. A client that believes the phase is POST_GAME while the server is in LOBBY. GPS markers flicker. | `TcpServer.java:169–224, 237, 262, 293, 325, 341, 365, 457, 1004` |
| N2 | **Server writes block, sometimes on the read loop.** `Connection.send` writes synchronously. The read loop itself sends `ping` (:969), `ACK` (:1134) and `SERVERREPLY` (:1282, :1295), so when one phone's TCP send buffer is full (out of range, not yet timed out) the **whole read loop** blocks. Until TCP retransmission gives up, which can take minutes, nobody else's messages are read. The thread-per-send threads for that phone pile up on its `outLock` with no bound. | Server freezes for every player while one phone is in a dead zone. Unbounded thread growth. | `Connection.java:71–84`; `TcpServer.java:969, 1134, 1282, 1295` |
| N3 | **Partial frames stall the server.** After `available() > 0` the loop calls `readUTF()`, which blocks until the whole frame arrives. Accepted sockets have no `SO_TIMEOUT` (only the listening socket has one, :716). A phone that drops mid-frame blocks the loop until TCP gives up. | Same as N2: every player stops being served. | `TcpServer.java:835–837` |
| N4 | **Partial frames hang the client forever.** The client polls the same way (:406–408) with no socket timeout. Its silence counter only advances while `available() == 0`, so once it is blocked inside `readUTF` it never reaches the 3.5 s drop, and the reconnect loop never runs. Server→client frames are the large ones (game info with 32 players' settings is several TCP segments), so walking out of range mid-snapshot is a realistic trigger. | The phone stays "connected" to a dead socket. Reconnect (Q4 of the reconnect design) never happens until the app is restarted. | `TcpClient.java:389–408` |
| N5 | **Unexpected frames crash the app.** Both sides `substring` the frame header before checking its length. Server: any frame that starts with `SimpleCoil:07` and is shorter than 17 characters throws `StringIndexOutOfBoundsException` (:841, :846). The read thread only catches `IOException`, and Android kills the process on an uncaught exception in any thread, so **one malformed frame crashes the dedicated server**. Client: the bare `VERSIONERROR` (12 characters) reaches `substring(13, 17)` at :413 before the `VERSIONERROR` branch at :484, so **the version-error path crashes the client instead of showing the error**. That will happen at the next protocol bump. `Integer.parseInt` at :420 is also unguarded. | Process crashes. The version check doesn't work as designed. | `TcpServer.java:841, 846, 972`; `TcpClient.java:413, 420, 484` |
| N6 | **One read loop for all connections.** It is only started for connection id ≤ 1 (:725). Six `break`s (:845, :854, :898, :959, :967, :975) skip the rest of the connections for that pass. If the server is stopped and started within one accept timeout (1 s), the new `ServerSocket` fails to bind and the error is only printed (see `DEDICATED_SERVER_DESIGN.md` S1). | Latency spikes. A silent dead server after a quick restart. | `TcpServer.java:707–751, 823–984` |
| N7 | **Static and main-thread state.** `keepListening`, `mGPSRunning` and `mGPSIntervalCount` are `static` (:130, :135, :141), as are `TcpClient.keepListening/isListening` (:78–79). The GPS relay builds JSON on the main thread through the deprecated `new Handler()` (:137). Tests rely on `Thread.sleep(200)` in `tearDown` for the static flag to settle. | Instances share state. The main thread does network work. Tests are flaky-prone. | listed |
| N8 | **Broadcasts as the in-process bus.** 48 `sendBroadcast` sites. Each one is an IPC round trip through system_server. Payloads travel as Intent extras with silent defaults (`getByteExtra(..., 0)`). Action strings are shared by the server and client roles, so they need the `handedOff` mute. `SAMETEAM` is never sent. `SERVERCREATED` is registered by the gateway but never handled. Android 14+ also defers broadcasts to context-registered receivers while an app is cached, so delivery depends on process state. | Fragile coupling, untyped payloads, lint suppressions (`UnsafeImplicitIntentLaunch` on `parseGameInfo`). | §2.3 |
| N9 | **Logging.** Every frame is logged at INFO, including GPS every second and every `SHOTFIRED`. The network classes have 43 `printStackTrace()` calls (`TcpServer` 25, `TcpClient` 16, `Connection` 2), and `Log.e` is used for routine information (`TcpServer.java:214, 1296`; `TcpClient.java:827, 906`). | Log spam in release builds, and real errors are hard to find. | listed |
| N10 | **Stringly, duplicated parsing.** Header parsing exists twice. The JSON key constants live in `TcpServer` and are used by the client and the gateway. Player-settings JSON is read twice (server :1187–1230, client :726–786) and copied field by field twice more (:595–609, :624–638). Some setters are called twice in a row (`TcpClient.java:853–854, 859–860`). | Any settings field change touches four places. | listed |
| N11 | **`stopTcpClient()` sleeps 75 ms** on the caller's thread (the main thread, from `GameViewModel`) to "let the end game message get sent". | Main-thread stall, and a race that a queued close would avoid. | `TcpClient.java:643–647` |

---

## 4. Target design (end state)

```
                     ┌──────────────────────────── app process ───────────────────────────┐
 UI / ViewModels ◄── ClientEvent flow ◄──┐                  ┌──► ServerEvent flow ──► Dedicated server UI
                                         │                  │
            ┌──────────── client ────────┴┐      ┌──────────┴─────────── server ────────────────────┐
            │ ClientConnection            │      │ GameServer                                        │
            │  reader (FrameReader, IO)   │      │  acceptor ─► per-connection reader (FrameReader)  │
            │  writer queue (one thread)  │      │                 │ decoded frames                  │
            │  ReconnectPolicy, Outbox    │      │                 ▼                                 │
            │ ClientMessageHandler        │      │  dispatcher (one thread): ServerMessageHandler,   │
            │  WireCodec ─► repositories  │      │   admin, snapshots, GPS tick, UI commands         │
            └─────────────────────────────┘      │                 │                                 │
                                                 │  per-connection writer queue (bounded)            │
                                                 └───────────────────────────────────────────────────┘
```

- **One writer per connection** (both sides). Messages to one peer go out in exactly the order they were sent.
  Sending never blocks the caller.
- **One reader per connection** with a socket timeout and a `FrameReader` that keeps partial frames across timeouts.
  Silence is measured as time since the last byte arrived.
- **One server dispatcher** runs every handler and every state change in a single order: frames from all
  connections, operator commands and the GPS tick. That keeps today's serial semantics (one `ClientThread`) without
  any thread doing blocking I/O.
- **Typed in-process events** in place of broadcasts.
- **A typed wire model**: `Frame` → `ClientMessage` / `ServerMessage` through a `WireCodec`. The codec is the only
  code that knows header strings and JSON keys.

---

## 5. Phase 1 — I/O fixes (Java, behavior-preserving except the bugs)

Each step is one commit with its tests. Steps 1–3 are independent of 4–6.

### 5.1 `Frame` parser (fixes N5)

A small Java class shared by both sides, which becomes Kotlin in Phase 3:

```java
final class Frame {
    enum Kind { MESG, JSON, PING, PONG, VERSION_ERROR, OTHER_VERSION, UNKNOWN }
    final Kind kind; final String body;     // body: the command or the JSON text, "" otherwise
    static Frame parse(String raw)           // never throws; null/short/garbage → UNKNOWN
}
```

`TcpServer.ClientThread` and `TcpClient.runTcpClient` switch on `kind` in place of the `substring` checks. Server
behavior is unchanged: `OTHER_VERSION` → reply `VERSIONERROR`, `UNKNOWN` → log and ignore. On the client,
`VERSION_ERROR` now reaches its intended branch. The client's `Integer.parseInt` calls move into a guarded
`parseId()`.

Tests: `FrameTest` covers every kind, plus the empty string, `SimpleCoil:07`, `SimpleCoil:07JS`, `VERSIONERROR`,
`SimpleCoil:06MESG...` and non-ASCII. In loopback: a client sends `SimpleCoil:07X` and the server keeps serving the
other clients. `TcpClient` gets a test that `VERSIONERROR` yields the version-error event.

### 5.2 Exception containment (fixes the rest of N5)

The server read loop catches `RuntimeException` per frame: log it with the frame and continue. Only `IOException`
drops the connection. On the client, a `RuntimeException` while handling a frame is logged and the frame skipped. A
`RuntimeException` that escapes the reader drops the connection, and the reconnect policy decides what happens next.
No network thread may throw out of its `run()`.

### 5.3 `FrameReader` (fixes N3, N4)

```java
final class FrameReader {
    FrameReader(InputStream in)
    /** A complete frame, or null if the socket timed out first. Keeps partial bytes across calls. */
    String poll() throws IOException          // EOF → IOException
    long lastByteAtMillis()                   // elapsedRealtime of the last byte received
}
```

It reads the 2-byte length, then the body, into its own buffer with `InputStream.read` (a timeout leaves the bytes
already read in the buffer). A complete frame is decoded with `DataInputStream.readUTF` over the buffered bytes, so the
decoding is exactly today's.

- **Client:** `socket.setSoTimeout(100)`. The read loop calls `poll()`. On `null` it checks
  `now − lastByteAt ≥ 3.5 s` → drop. The timing is unchanged, but the silence counter now runs while a frame is half
  received.
- **Server:** the same, inside the per-connection reader (§5.5).

Tests: in loopback, a client writes a length prefix and half a body, then stalls. The other clients keep being served
(server), and the stalled peer is dropped after 3.5 s ± 0.2 s. Client side: a fake server sends half a game info and
goes silent, and the client reports `NETWORKDISCONNECTED` and enters its retry loop within 3.5 s ± 0.2 s.

### 5.4 Per-connection writer queue on the server (fixes N1, N2)

`Connection` gets its own single writer thread fed by a queue. `send(msg)` enqueues and returns at once. Writes happen
in enqueue order.

- **Bound:** at most 256 queued frames. Above that the connection is closed and handled as a drop, so during a game the
  roster keeps the player and reconnect restores them. 256 frames is more than two minutes of GPS plus game traffic.
- `close()` drains nothing: it closes the socket and stops the writer.
- `sendTCPMessageAll/ID/Team` become plain loops (no `new Thread`). Every other `new Thread` in a send path is removed
  by §5.5.

Tests: send N numbered messages to one client from two threads and assert they arrive in each sender's order. A
"client that never reads" test: the server keeps serving the others and closes the stuck connection when the queue is
full. The existing `endGame_sendsFinalPlayerDataBeforeEndGame` keeps passing without its timing slack.

### 5.5 Reader per connection and one dispatcher (fixes N2, N6, N7)

- **Acceptor:** unchanged accept loop. For each socket: `roster.accept()`, then start that connection's reader.
- **Reader** (one per connection, named `sc-read-<id>`): `FrameReader.poll()` with `SO_TIMEOUT` 100 ms. Every
  complete frame is posted to the dispatcher. On silence it posts `ping` at 1 s and 2 s and a drop at 3.5 s (the same
  thresholds as today, measured from the last byte). An `IOException` posts a drop.
- **Dispatcher** (one single-thread executor, `sc-server`): runs today's `ClientThread` message handling unchanged
  (`parsePlayerInfo`, `handleEliminated`, the admin commands, `removeClient`). Every public `TcpServer` method that
  used to start a thread posts to it instead: `startGame`, `endGame`, `resetSession`, `kickPlayer`,
  `switchPlayerTeam`, `rebalanceAllPlayers`, the `sendAllGameInfo(id)` fan-out, and `sendPlayerSettings`. Methods that
  return a value (`startGame`, `resetSession`, `resetIfPostGame`) run on the dispatcher through
  `submit(...).get()` when they are called from another thread. That is safe because the dispatcher never blocks on
  I/O.
- **GPS tick:** `ScheduledExecutorService.scheduleAtFixedRate(1 s)` that posts to the dispatcher. It replaces the
  main-thread `Handler` (:137). Same cadence and the same full update every 20th tick.
- `keepListening`, `mGPSRunning` and `mGPSIntervalCount` become instance fields. `stopTcpServer()` closes the
  listening socket, so `accept()` returns at once and the 1 s restart window from N6 goes away.

Because every handler still runs on one thread, the behavior is today's, minus the latency added by blocked
connections. `GameRoster`'s locks stay, because UI reads (`getScore`, `getSessionPhase`) still come from the main
thread.

Tests: the whole `TcpServerLoopbackTest` suite passes unchanged (its `Thread.sleep(200)` in `tearDown` can go). New
tests: thirty-two clients, one of them stalled mid-frame; every other client's elimination is applied and
acknowledged within 500 ms.

### 5.6 Client: queued close (fixes N11)

`leaveServer()` enqueues `LEAVE` and then a close task on the existing writer, so `LEAVE` is written before the socket
closes. `stopTcpClient()` enqueues only the close. The `sleep(75)` goes. Test: `TcpClientWriterTest` asserts `LEAVE`
is the last frame written before close.

### 5.7 Phase 1 device gate

The network smoke test (§9.1), with extra focus on items 4 (walking out of range) and 7 (stalled client).

---

## 6. Phase 2 — in-process event bus (fixes N8)

### 6.1 Types

The existing `NetworkEvent` (`NetworkSessionGateway.kt:47`) becomes `ClientEvent` and moves to
`network/NetworkEvents.kt`, unchanged except for the additions marked †:

```kotlin
sealed interface ClientEvent {          // play screen + map
    // ... every NetworkEvent case today, same payloads ...
    data class GpsData(val fullUpdate: Boolean) : ClientEvent          // † was NETMSG_GPSDATAUPDATE
    data object GpsSettingChanged : ClientEvent                        // † was NETMSG_GPSSETTING
}

sealed interface ServerEvent {          // dedicated server screen
    data object PlayerJoined : ServerEvent
    data object PlayerLeft : ServerEvent
    data object GameStarted : ServerEvent
    data object GameEnded : ServerEvent
    data object SessionReset : ServerEvent
    data class PlayerDataChanged(val json: String?) : ServerEvent
    data class Error(val message: String) : ServerEvent
    data class ServerCreated(val ip: String) : ServerEvent
    data object ServerCancelled : ServerEvent
}

class EventBus<E : Any>(capacity: Int = 256) {
    val events: SharedFlow<E>
    fun publish(event: E)               // any thread; tryEmit; on overflow: Log.w + counter (as today)
}
```

`ClientEvent` and `ServerEvent` are separate types. A server event can't reach the play screen, which removes the
need for `handedOff`.

### 6.2 Ownership and delivery

- Both buses are app-scoped and live in the application container (`APP_CONTAINER_DESIGN.md`). Services reach them
  through `(application as SimpleCoilApp).container`, the same way services get dependencies in manual DI.
- Producers publish from whatever thread they're on. The order from one producer thread is preserved, as it is with
  broadcasts from one sender.
- Consumers collect on `Dispatchers.Main`, **not** `Main.immediate`. A broadcast is always posted to a later main
  loop turn, never handled inline, and collecting on plain `Main` keeps that, so no reentrancy appears that didn't
  exist before.
- There is no replay: an event with no collector is dropped, which is what a broadcast with no registered receiver
  does today. State that a late subscriber needs (phase, scoreboard) comes from repositories or `StateFlow`s, as it
  does now.

### 6.3 Producers and consumers

| Today | After |
|---|---|
| `TcpClient` → 20 actions | `clientEvents.publish(...)` with typed payloads built where the frame is parsed |
| `UDPListenerService` → `SERVERREPLY`, `FAILEDTOJOIN` (discovery, client role) | `clientEvents` |
| `UDPListenerService` → `SERVERCREATED` (server role) | `serverEvents.publish(ServerCreated(ip))` |
| `TcpServer` → `JOIN`, `LEAVE`, `STARTGAME`, `ENDGAME`, `SESSIONRESET`, `PLAYERDATAUPDATE`, `ERROR` | `serverEvents` |
| `PlayerSettingsAlertDialog` → `PLAYERSETTINGSUPDATE` / `PLAYERDATAUPDATE` | `clientEvents` / `serverEvents` (the dialog itself is rewritten in `DEDICATED_SERVER_DESIGN.md`) |
| `GameViewModelFactory` → `GPSSETTING` | `clientEvents.publish(GpsSettingChanged)` |
| `MapFragment` → `GPSLOCUPDATE` → `TcpClient` receiver | `NetworkSessionGateway.sendLocation(lat, lon)` → `TcpClient.sendLocation` (a direct call) |
| `NetworkSessionGateway.receiver` + `toEvent` + `intentFilter` | Deleted. `events` is the client bus's flow. The gateway publishes its own `UdpServiceConnected/Disconnected` to it. |
| `DedicatedServerActivity.mServerUpdateReceiver` | Collects `serverEvents` (later moved into its ViewModel by the dedicated server design) |
| `MapFragment.mGPSDataReceiver` | Collects `clientEvents`, filtered to `GpsData`, `ListPlayers` and `GpsSettingChanged` (Q4: moved in this phase) |

### 6.4 `NetMsg` split

`NetMsg` keeps only wire strings (commands and the header pieces), which are frozen. Constants that were only ever
broadcast actions or Intent extras are deleted: `GPSLOCUPDATE`, `GPSDATAUPDATE`, `GPSSETTING`, `PLAYERDATAUPDATE`,
`PLAYERSETTINGSUPDATE`, `NETWORKCONNECTED`, `NETWORKDISCONNECTED`, `SESSIONRESET`, `LISTPLAYERS`, `SERVERCREATED`,
`FAILEDTOJOIN`, `SAMETEAM`, and every `INTENT_*`. `JOIN` stays: discovery matches `SimpleCoil:JOIN` on UDP.
`UDPListenerService.INTENT_PLAYERID/INTENT_MESSAGE` go too.

### 6.5 Tests

- `NetworkSessionGatewayTest`'s broadcast→event mapping tests become producer tests. Feed `TcpClient`'s handler a
  frame and assert the published `ClientEvent`, including the old defaults (a missing id is 0, a missing time is −1).
  The defaults are now explicit in the parsing code rather than in `getXxxExtra` calls.
- `TcpServerLoopbackTest` can now assert `ServerEvent`s. Today `sendBroadcast` is a no-op under
  `returnDefaultValues`, so these are new assertions.
- One ordering test per bus: 1,000 events from two threads arrive in each thread's order.

Device gate: the network smoke test (§9.1), plus the dedicated server screen's join/leave/start/end/reset updates and
the GPS map.

---

## 7. Phase 3 — Kotlin and a typed wire model

### 7.1 Order (one class per commit; conversion commits contain no behavior change)

1. `NetMsg` → `WireProtocol.kt` (an `object` of `const val`s). The JSON key constants move out of `TcpServer` into
   `WireKeys`. Java callers keep compiling through `@JvmField` / `const`.
2. `SessionPhase`, `PlayerRecord`, `Connection`, `GameRoster` → Kotlin (`GameRosterTest`, 454 lines, covers the
   roster).
3. **Golden wire fixtures (§7.2)**, captured from the current Java builders. This is the safety net for steps 4–6.
4. `WireCodec` plus the `ClientMessage` / `ServerMessage` sealed hierarchies (§7.3). The builders and parsers in
   `TcpServer`, `TcpClient` and `NetworkSessionGateway` move into it one message family per commit. Player settings
   get **one** wire mapping (N10).
5. `TcpClient` → Kotlin, split into:
   - `ClientConnection`: socket, `FrameReader`, the writer, liveness, and the reconnect loop as a coroutine on
     `Dispatchers.IO`. The Wi-Fi wake becomes a `Channel<Unit>(CONFLATED)` in place of `wait/notify`. A blocking read
     is cancelled by closing the socket.
   - `ClientMessageHandler`: decoded `ServerMessage` → repository writes and `ClientEvent`s.
   - `TcpClient`: the `Service` shell (notification, binder, Wi-Fi callback).
6. `TcpServer` → Kotlin, split into:
   - `GameServer`: a plain class with the acceptor, connections, the dispatcher (a single-thread
     `ExecutorCoroutineDispatcher`), and the GPS tick.
   - `ServerMessageHandler`: hello, eliminations, admin commands, team change.
   - `SnapshotBuilder`: game info, player data, player settings, GPS, grenade pairings.
   - `TcpServer`: the `Service` shell that hosts `GameServer`. The dedicated server design makes it a foreground
     service and the owner of the game clock.
7. `UDPListenerService` / `ServerDiscoveryManager` → Kotlin `DiscoveryBeacon` and `DiscoveryClient`. Get `WifiManager`
   from the application context (fixes lint `WifiManagerPotentialLeak` ×2) and leave a single beacon owner (the
   duplicate beacon is S5 in `DEDICATED_SERVER_DESIGN.md`).
8. Logging (N9): `NetTrace`, a debug-only logger in the style of `ProtocolTrace` (tag `SCNet`, off in release), for
   frame-level logging. `printStackTrace` becomes `Log.w(TAG, msg, e)`, and routine `Log.e` calls become `Log.d`.

Each conversion runs the full unit suite, and each network-affecting step runs the device smoke test.

### 7.2 Golden wire fixtures

Before the first codec commit, capture every message family as it is built today, using the existing Java builders
with fixed inputs:

- Client → server: hello (fresh, rejoin with pending, without MAC), name change, player settings (with and without
  `liveslimit`), team change request, grenade pairing, GPS location, reliable envelope, and every `MESG` command.
- Server → client: game info (lobby, running with a time limit, post-game; with and without GPS; with
  `playergameupdate`), player data, player settings, grenade pairings, GPS (delta and full), and every `MESG`
  command.

The fixtures are files under `app/src/test/resources/wire/`. Two tests guard them:

- **Encode:** `WireCodec.encode(msg)` is JSON-equal to the fixture (same keys, values and types; order ignored).
  `MESG` frames are byte-equal.
- **Decode:** `WireCodec.decode(fixture)` gives the expected typed message, and decoding the old Java parser's input
  gives the same repository state as today's parser (comparison tests while both exist).

A fixture may never be edited to make a conversion pass (ground rule 4).

### 7.3 Wire model sketch

```kotlin
sealed interface ClientMessage {
    data class Hello(val playerId: Int, val name: String, val mac: String?, val rejoin: Boolean,
                     val gameId: Long?, val pending: List<Pending>) : ClientMessage
    data class Reliable(val rmsg: String, val seq: Int, val gameId: Long) : ClientMessage
    data class Settings(val playerId: Int, val settings: WirePlayerSettings) : ClientMessage
    data class Command(val command: WireCommand) : ClientMessage   // SHOTFIRED, HIT<n>, ADMIN_..., etc.
    // NameChange, TeamChangeRequest, GrenadePairing, GpsLocation
}
sealed interface ServerMessage { /* GameInfo, PlayerData, PlayerSettings, GrenadePairings, GpsUpdate, Command */ }

object WireCodec {
    fun encode(m: ClientMessage): String; fun decodeClient(frame: Frame): ClientMessage?   // null = ignore
    fun encode(m: ServerMessage): String; fun decodeServer(frame: Frame): ServerMessage?
}
```

The codec uses org.json internally (Q2).

---

## 8. Decisions (all resolved 2026-10-13)

| # | Question | Options | Recommendation |
|---|---|---|---|
| Q1 | Writer-queue bound per server connection | 128 / 256 / unbounded | **Accepted default: 256 frames**, then close (handled as a drop). Unbounded just moves N2 into memory. |
| Q2 | JSON library for the codec | org.json / kotlinx.serialization | **Decided: stay with JSON on org.json.** It ships with the platform (no dependency, no compiler plugin) and is what both ends parse with today, which makes semantic equality easy to guarantee. The value is in the typed message model, not the library. Revisit with a future v08 redesign. |
| Q3 | I/O primitives for Phase 3 | Blocking sockets on `Dispatchers.IO` + coroutines for loops/timers / NIO / Ktor sockets | **Accepted default: blocking sockets + coroutines.** At most 32 connections, so threads are cheap. NIO or Ktor adds complexity or a dependency for no gain. |
| Q4 | Move `MapFragment`'s three GPS broadcasts in Phase 2, or leave them for the upcoming map work? | Move them in Phase 2 / leave them | **Decided: move them in Phase 2.** About 20 lines in `MapFragment` (swap a receiver for a collector). The GPS/map work is waiting for this roadmap, and it will start from a typed `ClientEvent.GpsData` and `sendLocation()`. |
| Q5 | Reader socket timeout | 50 ms / 100 ms / 250 ms | **Accepted default: 100 ms**, the granularity of today's poll, so ping and drop timing stay as documented. |

---

## 9. Verification

### 9.1 Network smoke test (devices; ~20 min)

The server runs on the operator device (Chromebook or phone), with at least three client phones and blasters.

1. Discovery join, and manual IP join.
2. Lobby: name change, player team-change request, admin sort/mode/sizes, kick.
3. A game with shots, hits, outs and eliminations relayed; team scores; a grenade pairing and a grenade kill; GPS on
   (teammate and all) showing on the map.
4. A phone walks out of Wi-Fi range mid-game, is eliminated while away, and comes back: same ID, the life counted
   once. Another phone is away when the game ends and comes back before the reset.
5. Time, score and lives limits each end a game. Reset.
6. Version mismatch: a debug build with `NETWORK_VERSION = "99"` joins. The client shows the version error and does
   not crash.
7. **Stalled client** (after Phase 1): a phone with the app frozen in the debugger (or `adb shell am freeze`). Every
   other phone keeps playing normally, and the frozen one is dropped after 3.5 s.

Record the build hash, device models and pass/fail per item in the PR description, as `REFACTOR_DESIGN.md` §3.0 does.

### 9.2 Unit-test additions (summary)

`FrameTest`, `FrameReaderTest`, server ordering and stall tests (§5.4, §5.5), the client partial-frame test (§5.3),
the `VERSIONERROR` test, bus ordering tests, producer→event tests (§6.5), and the golden fixtures (§7.2).

---

## 10. Out of scope

Changing the wire protocol or bumping `NETWORK_VERSION`; TLS or authentication; restoring a server after process
death; the dedicated server screen and service lifecycle (`DEDICATED_SERVER_DESIGN.md`); repository injection
(`APP_CONTAINER_DESIGN.md`, which the Phase 3 conversions follow); map features.
