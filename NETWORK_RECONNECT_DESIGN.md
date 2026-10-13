# SimpleCoil Dedicated Server: Reconnect, Post-Game & Reset — Design Document

Status: **APPROVED (2026-10-12).** All decisions in §7 are resolved except Q-D and Q-E, which have accepted defaults
(§7.2). No source has been modified for this design yet.
Branch audited: `V2-refactor` @ `c826752` (plus the uncommitted foreground-service changes in the working tree, which
this design does not depend on).
Date: 2026-10-12

Revision 2 (2026-10-12): open questions Q-A, Q-B, Q-C and Q-G are resolved (§7). Two additions from review: in
POST_GAME, changing team settings, game limits or GPS mode, or sorting teams, first performs an implicit Reset (§3.1.1).
The phone-hosted network mode is removed entirely (§3.9). Its menu entries are already gone, so it can't be reached
today. While designing the remote Reset I also found that the server never checks admin status on `ADMIN_*` messages
(D14); a fix is added to the plan. §0, §1, §2.3, §3.1, §3.3, §3.7, §3.8, §4, §5, §6, §7 and §8 are updated to match.

Revision 3 (2026-10-12): Q-F (an implicit Reset needs no confirmation) and Q-H (the list of settings that trigger it)
are confirmed. Document approved.

---

## 0. Summary

The dedicated server is supposed to remember every player in a game by blaster MAC address, so that a phone that walks
out of Wi-Fi range can come back with the same player ID, team, score and lives, and so that the eliminations it
recorded while it was away get counted. Pieces of this exist (`activeGameMacMap`, `ClientData.rejoin()`, the client's
`messageQueue`, the scoreboard's disconnected icon), but the design has structural holes, and several later changes
broke it outright (§2.3).

This document proposes:

1. An explicit **session phase** on the server: `LOBBY → RUNNING → POST_GAME → (Reset) → LOBBY`.
2. Splitting **player state** (ID, team, name, score, lives, MAC) from **connection state** (socket, streams), so a
   player can exist without a connection.
3. A **game roster** (the MAC cache) that is filled when a game starts, survives the end of the game, and is cleared by
   a new **Reset** action. Reset replaces **Start Game** after a game, both on the dedicated server and in remote
   admins' menus. Changing game settings after a game also triggers it.
4. **Reliable delivery** for game-critical client messages (eliminations): sequence numbers, server acks, and replay of
   unacknowledged messages inside the rejoin handshake, with de-duplication on the server.
5. A **phase-aware reconnect policy** on the client that keeps retrying for as long as the server could still
   recognize it, and wakes up as soon as Wi-Fi comes back.
6. **Removal of the phone-hosted network mode.** The dedicated server becomes the only server, so every "is this the
   host?" branch on the server and the client goes away.

---

## 1. Requirements

Restated from the request, numbered for reference.

| # | Requirement |
|---|---|
| Q1 | The server identifies each player by blaster MAC address and keeps a cache of the players in the current game. |
| Q2 | During a game, a client that loses Wi-Fi queues the messages it needs to send to the server (eliminations, etc.). |
| Q3 | While a client is disconnected, the server still lists it on the scoreboard, with an icon showing it is not communicating. |
| Q4 | When Wi-Fi returns, the client reconnects on its own. |
| Q5 | On reconnect the server recognizes the player, keeps its score and life totals, and accepts and processes the queued messages. |
| Q6 | The reconnected client gets the same player ID and team. |
| Q7 | After the game ends, the server stays in a state where cached clients are still recognized and restored to their old team, ID, etc. |
| Q8 | After a game, the server shows a **Reset** button instead of **Start Game**. |
| Q9 | Reset zeroes all scores and lives and removes cached players that are currently disconnected. |
| Q10 | After Reset, players are not cached again until the next game starts. A player that disconnects in this phase is not saved. |
| Q11 | New players may join after a game ends (Q-A). |
| Q12 | A player who leaves on purpose during or after a game stays on the scoreboard as disconnected until Reset, then is removed (Q-B). |
| Q13 | Remote admin clients get a Reset option in place of Start Game after a game. After the reset, Start Game comes back, as on the dedicated server (Q-C). |
| Q14 | After a game ends and before Reset, changing team settings, game limits or GPS mode, or sorting teams, triggers the Reset first. |
| Q15 | The phone-hosted network mode is removed. |

---

## 2. Current state

### 2.1 Components involved

| Component | Role in this feature |
|---|---|
| `TcpServer.java` (2,275 lines) | Accept loop (`runTcpServer` :1305), one polling `ClientThread` (:1600) for all sockets, all game rules, MAC cache handling, rejoin. |
| `TcpServer.ClientData` (:1512) | **Both** the socket wrapper **and** the player's score (`points`, `eliminated`) and identity (`mPlayerID`, `mNetworkTeam`). Holds a server→client `messageQueue`. |
| `PlayerRepository.java` | ID↔IP, ID↔name, ID↔MAC maps, and `activeGameMacMap` (MAC → `ActiveGamePlayerInfo{playerId, team, originalName}`). |
| `TcpClient.java` | Client socket, reconnect loop (`runTcpClient` :226), client→server `messageQueue`. |
| `NetworkSessionGateway.kt` | Only `sendEliminated` asks for queuing (`sendTCPMessage(..., true)` :293). HIT/OUT/SHOTFIRED are fire-and-forget. |
| `GameViewModel.kt` / `BlasterSessionEngine.kt` | Applies the server's per-player game update on (re)join: `applyGameUpdate` → `engine.syncFromServer(eliminations, timeRemaining)` (`BlasterSessionEngine.kt:280`). |
| `DedicatedServerActivity.java` | Start/End buttons (:229, :218), `startGame()` :701 / `endGame()` :732 UI, scoreboard refresh `getPlayerDisplayData()` :827. |
| `PlayerDisplayDataListAdapter.java` | Already shows `ic_network_connected_24dp` / `ic_network_disconnected_24dp` from `PlayerDisplayData.isConnected` (:313–321, server only). |

### 2.2 How a disconnect and reconnect work today

1. **Detection.** Both sides poll `available()`. The server drops a client after 35 empty polls of 100 ms (3.5 s; pings
   at polls 10 and 20; `TcpServer.java:1770`). The client gives up on the socket after 35 empty polls (3.5 s on a
   dedicated server, 7 s otherwise; `TcpClient.java:355`).
2. **Server on drop.** `removeClient(client, id, false)` (:2239). If a game is running, it only closes the socket. The
   `ClientData` (with its score) stays in `mClientData`, so `getScore()` reports `isConnected = false` and the
   scoreboard shows the disconnected icon.
3. **Client on drop.** It broadcasts `NETWORKDISCONNECTED` and loops: new socket, `connect()` with a 1 s timeout. While
   the client thinks a game is running, retries are unlimited. Otherwise it stops after 3 tries and broadcasts
   `SERVERCANCEL`, which throws the player back to the network menu (`TcpClient.java:251–260, 383`).
4. **Client on reconnect.** Sends the join JSON (`playerID`, `playername`, `blaster_mac`, `rejoin: true`), then
   immediately flushes its `messageQueue` (`sendPlayerInfo` :414–434).
5. **Server on reconnect.** `parsePlayerInfo` (:1988–2048): if the game is running and the MAC is in
   `activeGameMacMap`, it finds the old `ClientData` by player ID, moves the new socket into it (`rejoin()` :1587, which
   also flushes the server→client queue), sends `SERVERREPLY<id>` and a per-player game update (score, eliminations,
   team score, time).
6. **Game end.** `TcpServer.endGame()` (:346) clears `activeGameMacMap`, sends `ENDGAME` (unqueued), and
   `cleanUpDisconnectedClients()` (:377) deletes every disconnected player.

### 2.3 Why it does not work

Each item is a reason the feature fails today. Items D1–D7 must be fixed for the requirements to be met.

| # | Defect | Effect | Where |
|---|---|---|---|
| D1 | **Messages written during the detection window are silently lost.** Until the 3.5 s timeout fires, `out != null`, so `writeUTF` succeeds into the kernel buffer and the message is never queued. When the socket is closed, it is gone. The most likely moment to be eliminated is exactly while walking out of range. | Eliminations lost; the server's life count for that player is too high. | `TcpClient.sendTCPMessage` :168–205 |
| D2 | **On rejoin, the server's stale life count overwrites the client's.** The server sends the game update *before* it processes the client's queued `ELIMINATED` messages, and never knows about the ones lost in D1. `syncFromServer` then sets the client's lives from the server's number. | The player gets lives back on every reconnect (Q5 fails). | `TcpServer.java:2044–2045`, `BlasterSessionEngine.kt:280` |
| D3 | **Queued messages are not sent in order.** `sendTCPMessage` starts a new thread per message, so the queued `ELIMINATED` messages can reach the server before the join JSON. The server then applies them to the temporary, unidentified `ClientData` (player ID 0). The shooter is credited, but the victim's elimination is counted on a throwaway object that the rejoin path discards, and the shooter is told it eliminated player "0". | Queued deaths lost. | `TcpClient.java:179–204, 428–434` |
| D4 | **`mClientData` keys collide.** New connections are keyed by an incrementing counter (`clientID++` :1327). `rebalanceAllPlayers` (:694, :830, :868), `switchPlayerTeam` (:454–458) and team-change requests (:2197–2201) re-key clients by player ID. Once teams have been sorted or shuffled, a new connection's counter value can equal a re-keyed player's key, and `put()` replaces that player's `ClientData`. A reconnecting player is then restored with a fresh `ClientData`: **score and eliminations reset to 0**. The rejoin path's `mClientData.remove(client.clientID)` (:2030) can also remove the wrong entry. Introduced with re-keying in `eeda681`, which is plausibly when this "stopped working". | Score/lives lost on reconnect; players vanish from the scoreboard. | listed |
| D5 | **The game's roster is discarded at game end.** `endGame()` clears `activeGameMacMap` and deletes disconnected players. After that, a returning player is a stranger: a new ID, possibly a new team, score 0. | Q7 fails. | `TcpServer.java:351, 370` |
| D6 | **A client that is disconnected when the game ends gives up.** The `ENDGAME` message is not queued. If the client drops after it switched to `GAME_STATE_NONE`, it retries only 3 times (~3–6 s) and then gives up with `SERVERCANCEL`. | Q4/Q7 fail after the game. | `TcpClient.java:257–258, 383` |
| D7 | **Score lives on the connection object.** `points`/`eliminated` live in `ClientData`. Any code path that replaces or removes a `ClientData` (D4, kick, cleanup) destroys the score. There is no place to keep a player who has no socket. | Root cause behind D4/D5. | `TcpServer.java:1524–1525` |

Related defects found while auditing. They are not the cause, but the new design removes or must avoid them:

| # | Defect | Where |
|---|---|---|
| D8 | `GameStateRepository` is written only once, at `Globals` construction. It always reports "not running". So `handleClientTeamChangeRequest` lets clients **change teams mid-game**, and `kickPlayer` → `PlayerRepository.removePlayer(id)` always takes the "game not running" branch. | `Globals.java:203`, `TcpServer.java:2135`, `PlayerRepository.java:207` |
| D9 | `kickPlayer` first looks up `mClientData.get((int) playerID)`, a **connection** key, so it can kick the wrong player (same key confusion as D4). | `TcpServer.java:508` |
| D10 | When the network is unreachable, `connect()` fails immediately rather than after the 1 s timeout. The client retry loop has no delay, so it spins (CPU and log spam) while out of range. | `TcpClient.java:251–381` |
| D11 | The server→client `messageQueue` replays `ELIMINATED`/`HIT`/`OUT` notifications before `SERVERREPLY` and the absolute score update. They are stale UI events, and the score they carry is already in the update. | `TcpServer.java:1587–1597` |
| D12 | Each kill in a team game increments teammates' team score twice: the server sends `TEAMELIMINATED` to the shooter's team (:1658), and the shooter's client sends its own `TEAMELIMINATED` (`GameViewModel.kt:1087`), which the server relays again (:1699). The next player-data update corrects the number. Replay must not make this worse. | listed |
| D13 | Non-dedicated (phone-hosted) mode: `startGame()` sets the static `keepListening = false` (:339–340), which appears to shut down the TCP server and its client thread when a hosted game starts. Moot: hosted mode is removed (§3.9). | `TcpServer.java:339` |
| D14 | The server acts on every `ADMIN_*` message (start, end, game mode, sort, kick, team sizes) without checking the sender's `isAdmin` flag. Plain `STARTGAME` from any client also starts the game. Only the client UI hides the admin menu, so any client can send them. | `TcpServer.java:1702–1760` |

---

## 3. Design

### 3.1 Session phases (server)

A new server-side `SessionPhase`, owned by the server's roster (§3.2). It is the single source of truth.
`Globals.mGameState` is derived from it (`RUNNING` ⇔ `GAME_STATE_RUNNING`) so existing readers keep working.
`GameStateRepository` is updated on every transition (fixes D8).

```
            Start Game                End Game / limit reached
  LOBBY ───────────────▶ RUNNING ─────────────────────────────▶ POST_GAME
    ▲                                                              │
    └───────────────────────────── Reset ──────────────────────────┘
```

| | LOBBY | RUNNING | POST_GAME |
|---|---|---|---|
| Roster cache (MAC → player) | empty | filled at start; join-in-progress players added | kept, frozen (no additions) |
| Disconnected cached player | n/a | kept, shown with disconnected icon | kept, shown with disconnected icon |
| Disconnected uncached player | removed (today's behavior) | n/a (everyone in a running game is cached; see §4 for no-MAC players) | removed |
| Cached player who leaves on purpose (`LEAVE`) | n/a | kept as disconnected | kept as disconnected; removed at Reset like any other disconnected player |
| Returning MAC in cache | n/a | restored: ID, team, name, score, lives | restored: ID, team, name, final score |
| New (unknown) player | joins normally | allowed only if *Allow join* is on; then added to cache | joins as an uncached "lobby" player, gets an ID not reserved by the cache (Q11) |
| Score / lives | 0 | live | frozen final values (late replays from this game still counted, §3.4) |
| Server buttons | **Start Game** (enabled with ≥ 2 players), End Game disabled | Start hidden, **End Game** enabled | **Reset** in Start's place, End Game disabled |
| Remote admin menu | **Start Game**, game modes, Sort teams | End Game | **Reset** in Start Game's place, game modes, Sort teams |
| Game mode, team sizes, game limits, GPS mode, Sort teams | allowed | blocked (as today) | allowed, but **triggers Reset first** (§3.1.1) |
| Server-only settings, Allow join, Allow choose teams, per-player settings | allowed | as today | allowed, no Reset |
| Client team-change requests | allowed (if enabled) | blocked | blocked: they would reassign an ID out from under the cache |

**Reset** (POST_GAME → LOBBY):

1. Remove every cached player that is currently disconnected: player record, `PlayerRepository` entries (IP, name,
   MAC, settings, GPS), grenade pairing.
2. Set points and eliminations to 0 for every remaining (connected) player. IDs and teams stay as they are.
3. Clear the roster cache and the current `gameId`.
4. Phase = LOBBY. Send game info (with the new phase) and player data to all clients. Broadcast locally so the activity
   refreshes.

From this point, a disconnect removes the player (Q10). Nobody is cached until **Start Game**.

Three things can trigger a Reset, all through the same `resetSession()`:

| Trigger | Confirmation |
|---|---|
| **Reset** button on the dedicated server | Yes: "Reset scores and remove N disconnected players?" |
| **Reset** in a remote admin's menu (`ADMIN_RESET`) | Yes, on the admin's phone, same text (N from the scoreboard). |
| Changing a game setting in POST_GAME (§3.1.1) | No. The settings change is the intent. A toast says "Game reset". |

`resetSession()` does nothing outside POST_GAME, so a duplicate or late reset is harmless.

#### 3.1.1 Implicit Reset on settings changes

In POST_GAME, the following actions first call `resetSession()`, then apply the change as they would in LOBBY:

| Action | Dedicated server entry point | Remote admin entry point |
|---|---|---|
| Game mode (2 teams / 4 teams / FFA) | `onMenuItemClick` game-mode items | `ADMIN_GAMEMODE` |
| Team sizes | team-ratio dialog OK | `ADMIN_TEAMSIZES` |
| Game limits (time / lives / score) | game-limit dialog OK | none today |
| GPS mode | `onMenuItemClick` GPS items | none today |
| Sort teams | `shuffle_teams_button` | `ADMIN_SORTTEAMS` |

Rules:

- The trigger is **committing** the action: picking a menu item, pressing OK with valid input, or tapping Sort. A
  cancelled or rejected dialog (invalid team sizes, limit > 100) does not reset.
- Reset runs before the change. The rebalance that a game-mode, team-size or sort change causes then runs without the
  disconnected players, and assigns IDs freely because the cache is empty.
- The check lives in one place, `TcpServer.resetIfPostGame()`, which calls `GameRoster.reset()` under the roster lock
  and returns whether a reset happened. The activity calls it at the top of each handler above (so the toast and the
  scoreboard refresh happen before the change is applied). The server's `ADMIN_*` handlers call it before acting.
- Settings that only affect the next game's flow, not the roster or scoring (server-only settings, Allow join, Allow
  choose teams), and per-player settings and kicks, do **not** reset.

### 3.2 Server data model

Split `ClientData` into two types and add one owner for both:

```java
/** One per player the server knows about. Survives disconnects. */
final class PlayerRecord {
    byte playerId;
    int team;
    String name;
    String blasterMac;          // "" if the client sent none
    int points;
    int eliminated;
    boolean cached;             // part of the current game's roster
    long gameId;                // game the record's lastAppliedSeq belongs to
    int lastAppliedSeq;         // reliable-message de-dup (§3.4)
    Connection connection;      // null while disconnected
}

/** One per accepted socket. Has no game state. */
final class Connection {
    final int connectionId;     // from the accept counter; never re-keyed
    Socket socket; DataInputStream in; DataOutputStream out;
    PlayerRecord player;        // null until the hello is processed
    int noReadCount;
}

/** Owns records, connections, the MAC cache and the phase. Plain Java, no Android types: unit-testable. */
final class GameRoster {
    SessionPhase phase;
    long gameId;
    Map<Byte, PlayerRecord> byId;
    Map<String, PlayerRecord> cacheByMac;   // the roster cache
    Map<Integer, Connection> connections;   // keyed by connectionId only
    // transitions: startGame(), endGame(), reset()
    // events: onHello(conn, hello), onDisconnect(conn), onReliable(conn, seq, gameId, msg), kick(id), ...
}
```

Rules:

- `connections` is keyed only by `connectionId`. Nothing re-keys it (fixes D4/D9). Everything that today searches
  `mClientData` by player ID goes through `byId`.
- Rebalance, shuffle and team change change `PlayerRecord.playerId`/`team` and move the record in `byId`. They also call
  `PlayerRepository.reassignPlayer` as today. They are allowed only in LOBBY.
- `getScore(id)` reads the record. `isConnected = record.connection != null`. The scoreboard code in
  `DedicatedServerActivity.getPlayerDisplayData()` keeps working unchanged and the icon becomes correct.
- `checkLivesLimitEndGame` and score-limit checks iterate `byId`, so disconnected players still count as alive, as they
  do today.
- One lock (`synchronized (roster)`) guards roster state. No socket I/O is done while holding it: the code builds the
  message under the lock and sends it after releasing. This replaces the `mClientDataSemaphore` plus five `Globals`
  semaphores for this state. `PlayerRepository`/`Globals` maps are still updated, for the screens that read them.
- The server→client `messageQueue` is removed. Messages to a player with no connection are dropped. The rejoin snapshot
  (§3.5) carries everything the client needs (fixes D11).

`GameRoster` lives in a new `network/server/` package. `TcpServer` keeps the sockets, threads and JSON, and delegates
decisions to it. This follows the refactor's pattern of pulling rules out of Android classes into testable ones.

### 3.3 Server event handling by phase

| Event | LOBBY | RUNNING | POST_GAME |
|---|---|---|---|
| **Hello, MAC in cache** | n/a (cache empty) | Bind connection to the record. Close any previous connection of that record ("last connection wins"). Apply `pending` replay (§3.4). Send `SERVERREPLY<id>` + snapshot. | Same as RUNNING. End conditions are not evaluated. |
| **Hello, unknown MAC or no MAC** | Today's logic: honor the requested ID if free, else auto-sort; uniquify name. | If *Allow join* is off: `ERROR` + close (as today). Else assign a free ID (all records count as occupied, connected or not) and add to cache if it has a MAC. | Assign an ID not used by any record (cached IDs stay reserved). Not cached. |
| **Socket dropped / timeout / IOException** | Remove record + repository entries, broadcast `LEAVE` (today's behavior). | Cached: `connection = null`, broadcast `PLAYERDATAUPDATE` (icon flips). | Cached: same as RUNNING. Uncached: remove. |
| **`LEAVE` (explicit) / `ENDGAME` from a non-admin client** | Remove. | Treated as a disconnect: the record stays so the scoreboard is intact and the player can come back (Q12). | Same as RUNNING. Removed at Reset. |
| **Admin kick** | Remove. | Remove record **and** cache entry, close socket. | Same. |
| **Reliable `ELIMINATED<shooter>`** | Ack, ignore. | De-dup, apply to sender's record (`eliminated++`) and shooter's record (`points++`, even if the shooter is disconnected), notify the shooter and team, evaluate limits, ack. | If `gameId` matches: de-dup, apply, ack, push player data. No end-condition checks, no live notifications. Else: ack, ignore. |
| **Start Game (button / `STARTGAME` / `ADMIN_STARTGAME`)** | New random `gameId`. Every connected record with a MAC → `cached = true`, scores 0. Phase RUNNING. | ignored | **rejected** with `ERROR` "Reset the game first" (Q8). The menus never offer it here; this guards stale or old clients. |
| **End Game / limit reached / timer** | ignored | Phase POST_GAME. Send `ENDGAME` + final player data. Nothing is purged. | ignored |
| **Reset (button / `ADMIN_RESET` from an admin)** | ignored | ignored | §3.1 Reset. The server checks that the sender's `PlayerSettings.isAdmin` is set; if not, the message is ignored. (Today the server checks admin status on **no** `ADMIN_*` message, D14; the same check should cover all of them.) |
| **Game mode / team sizes / Sort teams (button or `ADMIN_*`), game limits, GPS mode** | Apply (today's behavior). | Blocked (as today). | `resetIfPostGame()`, then apply as in LOBBY (§3.1.1). |

### 3.4 Reliable client messages

Goal: any elimination the client records during a game reaches the server exactly once, no matter when the connection
dies (fixes D1, D3).

**Client outbox** (in `TcpClient`, behind `NetworkSessionGateway.sendEliminated`):

- Each reliable message gets `seq` (per client, starts at 1 for each `gameId`) and the `gameId` the client last
  received. The outbox keeps it until the server acks it. It is **not** removed on a successful write.
- All writes, reliable or not, go through **one writer thread** (a single-thread executor with a FIFO queue). This
  replaces thread-per-message and guarantees wire order (fixes D3).
- Writes are not retried on a live connection, because TCP already retransmits. Unacked entries are re-sent only in
  the next hello.
- The outbox is cleared when a snapshot reports a different `gameId` (a new game started or the server was reset) and
  on `leaveServer()`. It is capped at 256 entries (drop oldest, log). Persisting it across process death is out of
  scope (§8).

**Wire format** (client → server, JSON envelope so the existing `MESG` parser is reused after unwrapping):

```json
{"rmsg": "ELIMINATED7", "seq": 12, "gameid": 418873301}
```

Server → client ack, cumulative: `MESG` + `ACK12`. The client drops every outbox entry with `seq ≤ 12`.

**Replay in the hello.** On every (re)connect, the client sends its unacked entries inside the hello, so the server
identifies the player and applies the backlog in one atomic step, *before* it builds the snapshot:

```json
{"playerID": 7, "playername": "Rook", "blaster_mac": "AA:BB:...", "rejoin": true,
 "gameid": 418873301,
 "pending": [{"rmsg": "ELIMINATED3", "seq": 11}, {"rmsg": "ELIMINATED9", "seq": 12}]}
```

**Server de-dup.** For each entry: if `gameId` ≠ the record's game → ack and drop. If `seq ≤ record.lastAppliedSeq` →
ack and drop (already applied: the original arrived but its ack was lost). Otherwise apply it through the same handler
as live messages and set `lastAppliedSeq = seq`.

**Which messages are reliable:** `ELIMINATED` only. `HIT`, `OUT` and `SHOTFIRED` are momentary UI cues and stay
fire-and-forget. A stale "you hit X" ten seconds later is noise. `TEAMELIMINATED` from the client stays unreliable.
The cleaner fix is to stop sending it from the client at all, because the server already sends it (D12), but that is
separate (§7 Q-E). Name, settings and grenade pairing are state, not events, and are already re-sent on every connect.

### 3.5 State sync on rejoin

After the hello (and any replay), the server sends `SERVERREPLY<id>` and then the existing per-player game info
(`sendAllGameInfo(id)`), extended with:

| Key | Meaning |
|---|---|
| `sessionphase` | `0` LOBBY, `1` RUNNING, `2` POST_GAME |
| `gameid` | current game (absent in LOBBY) |
| `playergameupdate.ackseq` | highest `seq` applied for this player |
| `playergameupdate.points` / `eliminated` / `teampoints` / `timeremaining` | as today, read from the record |

Client reconciliation (fixes D2): when it applies the update, the client computes

```
eliminations = update.eliminated + count(outbox entries that are ELIMINATED with seq > update.ackseq)
```

This counts the race where the player is eliminated between sending the hello and receiving the snapshot. The client
never loses a life it recorded and never regains one it lost.

Phase handling on the client (most of it already exists in `GameViewModel.onListPlayers`):

- `RUNNING` and the client is not in a game (process restarted) → `startGame()` with the synced values (exists).
- `POST_GAME` or `LOBBY` while the client is in a game (it missed `ENDGAME`) → `endGame()` (exists, keyed on
  `gamestate`).
- `LOBBY` after `POST_GAME` (server was reset) → zero the displayed score and team score, and clear the outbox.

`SERVERREPLY<id>` is already applied by the client (`g.mPlayerID = assigned; setTeam()`), so the restored ID and team
reach the blaster without new code (Q6).

### 3.6 Client reconnect policy

Changes to `TcpClient.runTcpClient` (fixes D6, D10):

| Server phase last seen | Retry policy |
|---|---|
| RUNNING or POST_GAME | Unlimited, until the user leaves, the server sends `SERVERCANCEL`, or a snapshot reports LOBBY. |
| LOBBY | Today's 3 tries, then `SERVERCANCEL` (Q10: nothing to come back to). |

- Backoff between attempts: 1 s, 2 s, 4 s, then 5 s (cap), with a real sleep, so an immediate `ENETUNREACH` cannot
  spin.
- `ConnectivityManager.registerNetworkCallback` for `TRANSPORT_WIFI`: `onAvailable` wakes the retry loop at once and
  resets the backoff (Q4: reconnects within about a second of Wi-Fi returning instead of up to 5 s later).
- Bind the socket to the Wi-Fi `Network` (`network.bindSocket(socket)`) before `connect()`. When the field's Wi-Fi has
  no internet and mobile data is on, Android can make cellular the default network. An unbound socket to the server's
  LAN address may then never route over Wi-Fi, so the client would never reconnect. Only `ACCESS_NETWORK_STATE` is
  needed, and it is already in the manifest.
- The phase is read from every game-info message, not only from the hello reply. A client that is connected when the
  server resets switches to the LOBBY policy.

The UI already reacts to `NETWORKDISCONNECTED`/`NETWORKCONNECTED` (`showNetworkConnected`). No new client screens are
needed.

### 3.7 Dedicated server UI

`activity_dedicated_server.xml`:

- Add `reset_game_button` in the same position as `start_game_button` (same `layout_below`/alignment), with a new
  string `reset_game_button` = "Reset". Exactly one of the two is visible at a time.
- Add string `dedicated_game_over` = "Game over. Players can still reconnect. Tap Reset for a new game."

`DedicatedServerActivity`:

- Replace the `startGame()`/`endGame()` UI methods with one `renderPhase(SessionPhase)` that sets button visibility
  and enabled state and the status text per the table in §3.1.
- **Reset** shows a confirmation dialog ("Reset scores and remove N disconnected players?"), then calls
  `mTcpServer.resetSession()`.
- `TcpServer` broadcasts `NETMSG_SESSIONRESET` locally after a reset. The activity's receiver handles it like
  `PLAYERDATAUPDATE` + `renderPhase(LOBBY)`.
- In POST_GAME, the game mode, team-ratio, game-limit and GPS controls and Sort teams are **enabled**. Each handler
  starts with `if (mTcpServer.resetIfPostGame()) toast("Game reset")`, then continues as in LOBBY (§3.1.1). The
  team-ratio dialog's "Cannot edit team ratios while a game is in progress" check becomes "phase is RUNNING".
- Keep the UDP discovery beacon on during RUNNING as well. Today `allowJoin(false)` turns it off. A client whose app
  restarted mid-game must be able to rediscover the server to be restored. Whether a new player may join is already
  enforced at the TCP hello (`isAllowJoinInProgress`).
- `DedicatedServerViewModel` gets a `phase` LiveData fed by the same broadcasts. Its current `gameStateLiveData` is
  never updated (D8).

Scoreboard rows need no change. Disconnected cached players already render with `ic_network_disconnected_24dp` once
`isConnected` comes from the record. Optional: add `connected` to each player in the `playerdata` JSON so client
scoreboards can show the same icon (the adapter hides it for clients today).

### 3.7.1 Remote admin menu (client)

`GameViewModel.adminActions()` currently picks the menu from the client's own `g.mGameState`, which is
`GAME_STATE_NONE` both before and after a game. It switches to the server's `sessionphase` (§3.5), which the client
stores whenever it receives game info:

| Server phase | Admin actions |
|---|---|
| LOBBY | `START_GAME`, `TWO_TEAMS`, `FOUR_TEAMS`, `FREE_FOR_ALL`, `SHUFFLE_TEAMS` (as today) |
| RUNNING | `END_GAME` (as today) |
| POST_GAME | **`RESET_GAME`** (in `START_GAME`'s slot), `TWO_TEAMS`, `FOUR_TEAMS`, `FREE_FOR_ALL`, `SHUFFLE_TEAMS` |

- New `AdminAction.RESET_GAME` with a string "Reset game". Choosing it shows the same confirmation as the server
  (§3.1), then sends `MESG ADMIN_RESET`.
- The game-mode and shuffle actions in POST_GAME send the same `ADMIN_*` messages as today. The server performs the
  implicit Reset (§3.1.1), so the client needs no extra logic and no confirmation for them.
- After any reset, the server sends game info with `sessionphase = LOBBY` to every client. The next time an admin opens
  the menu it shows `START_GAME` again (Q13). If the menu is open at that moment, it is rebuilt.
- The same rule covers the client's pre-game Start button (`onStartPressed`): hidden in POST_GAME.

### 3.8 Wire protocol changes

The message formats change, so `NetMsg.NETWORK_VERSION` goes from `"06"` to `"07"`. Mixed versions are already
rejected with `VERSIONERROR`.

| Direction | Change |
|---|---|
| C→S hello JSON | adds `gameid` (optional), `pending` (array of `{rmsg, seq}`) |
| C→S | new reliable envelope `{"rmsg", "seq", "gameid"}`. `ELIMINATED` is sent only this way. |
| S→C | new `MESG` `ACK<seq>` |
| S→C game info JSON | adds `sessionphase`, `gameid`. `playergameupdate` adds `ackseq`. |
| S→C player data JSON | adds per-player `connected` (optional, §3.7) |
| C→S admin | new `MESG` `ADMIN_RESET` (admin only, §3.3) |
| S→C game info JSON | `dedicatedserver` is removed: every server is dedicated (§3.9) |
| Local broadcast (server) | new `NETMSG_SESSIONRESET` |

The BLE protocol is untouched. None of this crosses `REFACTOR_DESIGN.md` §0 rule 1.

### 3.9 Removing the phone-hosted network mode

The menu entries that started a hosted game are already gone, so this is dead code. Removing it first makes
`TcpServer` dedicated-only, which shrinks the code the roster extraction has to move. That is why it is step 1 of the
plan (§5).

**Server (`TcpServer`).**
- Delete `mIsDedicated`, `setDedicated()` and every `!mIsDedicated` branch (about 30 sites). These cover: the host's
  own entry in player lists and JSON, `mHostPoints`/`mHostEliminated`/`setHostScore()`, host handling in
  `getScore()`, `checkLivesLimitEndGame()`, `rebalanceAllPlayers()`, name uniquifying and `occupiedIDs`, and the
  `keepListening = false` at game start (D13).
- Use the dedicated poll interval only (`TCP_DEDICATED_READ_WAIT_MS`). Delete `TCP_READ_WAIT_MS`.
- Drop the `dedicatedserver` key from game info.
- `DedicatedServerActivity` stops calling `setDedicated()`.

**Client (`TcpClient`).**
- Delete `mIsDedicatedServer`/`isDedicatedServer()`. Code that branched on it behaves as the dedicated branch always:
  100 ms poll and timeout math, the 75 ms flush delay in `stopTcpClient()`, and `sendPlayerGrenade` always sent
  (`NetworkSessionGateway.kt:298`).

**Gateway and game screen.**
- `NetworkSessionGateway`: remove `tcpServer`, `tcpServerConnection`, `bindTcpServer()`, `isServer`, and the
  `setHostScore`/`endGame` calls (:261, :302, :306). `releaseForDedicatedServer()` only has to unbind UDP and the
  client. `attachForTest` loses its `tcpServer` parameter.
- `GameViewModel`: remove `isServer`/`tcpServer` and every `net.isServer` branch. That includes host start/end/shuffle/
  game mode/kick/team-size paths in `onAdminAction`, `changeAdminGameMode` and the start/end handlers (about 25 sites),
  `shuffleVisible = net.isServer`, and `showAdmin = net.isServer || g.mIsAdmin`, which becomes `g.mIsAdmin`.
- `onServerIpEntered()` sets `net.isServer = true` temporarily to "trick" `setReady` into not searching for a server.
  Replace that with an explicit `setReady(searchForServer = false)` parameter.
- `SavedGameScreen.isServer` and the `mIsServer` saved-state key in `FullscreenActivity` go away.
- UI: `showGameStarted(..., isServer, ...)`, `displayInGameNetworkingOptions(isServer, ...)` and the host-only
  `endNetworkGameButton` visibility (`GameScreenRenderer.kt:91–104, 252–259`) lose the parameter. The button follows
  `mIsAdmin` like the rest of the admin UI. `ScoreboardDialog`/`showScoreboard` drop `isServer` (always client).
  `PlayerSettingsDialog` only uses `setLocal(vm.tcpClient)`. Its `setServer(id, vm.tcpServer)` branch is deleted.
- `PlayerSettingsAlertDialog.setServer()` stays: `DedicatedServerActivity` uses it.

**Tests.** The two `setHostScore` cases in `NetworkSessionGatewayTest` (:102–107, :219–228) and the `net.isServer`
case in `GameViewModelTest` (:360) are deleted. Each one only checks hosted behavior. Before deleting them, grep both
test files for any other `isServer`/`tcpServer` use.

**What stays.** `TcpServer` remains an Android `Service`, and `DedicatedServerActivity` binds it as it does today.
`UDPListenerService.createServer()`/`cancelServer()` stay because the dedicated server uses them.

---

## 4. Edge cases

| Case | Behavior |
|---|---|
| Client reconnects before the server noticed the drop (old socket still looks alive) | Same MAC → the new connection wins. The old one is closed. The server can't tell a fast reconnect from a duplicate, and the newest connection is the only one that can be live. |
| Two phones present the same MAC (one blaster handed over) | Same as above: last connection wins. The previous phone is disconnected and reconnects into a fight it loses. Acceptable; logged. |
| Player swaps to a different blaster mid-game | New MAC → treated as a new player. Their old record stays as "disconnected" until Reset. An admin can kick the stale one. Mitigation: Q-D. |
| Client sent no MAC (blaster never connected, `TEST_NETWORK`) | Plays normally but is never cached. It behaves like LOBBY rules: if it disconnects it is removed. The server logs a warning. |
| Shooter is disconnected when its kill is reported | Points go to the shooter's record. It sees the corrected score in its snapshot on return. |
| Reset happens while a cached player is disconnected | That player is removed. When it returns: the snapshot says LOBBY with a different/no `gameId`, so the client clears its outbox, ends its game if still running, and joins as a new lobby player (its old ID is honored if free). |
| Replay arrives in POST_GAME for the game that just ended | Counted in the final scores. Limits are not re-evaluated. |
| Replay arrives for an older game | Acked and dropped (`gameId` mismatch). |
| Lives limit: the last opponent's death is still in their outbox | The game continues until they reconnect or the timer/admin ends it. This is the correct outcome given what the server knows. |
| Dedicated server activity destroyed / app killed | Out of scope. The roster is in memory only, so a server restart starts in LOBBY. Clients see a different `gameId` and clear their outboxes. |
| Admin client sends `ADMIN_STARTGAME` in POST_GAME (stale menu or old build) | Rejected with `ERROR` "Reset the game first". |
| Settings change in POST_GAME while a cached player is walking back | The implicit Reset removes that player. When it returns it joins as a new lobby player (the row above). This is what Q14 asks for. |
| Server operator and a remote admin reset at the same moment (button, `ADMIN_RESET` or a settings change) | The first reset runs. The second finds LOBBY and does nothing. A settings change that arrives second is still applied, in LOBBY. |
| Remote admin opened the menu in POST_GAME, and the server reset before they chose | Choosing Reset sends a no-op `ADMIN_RESET`. A game-mode/shuffle choice is applied in LOBBY. The menu is rebuilt from the new phase (§3.7.1). |

---

## 5. Implementation plan

Each step is its own commit, keeps `./gradlew test` green, and follows the existing rule that moves and fixes are not
mixed.

| Step | Change | Verifies |
|---|---|---|
| 1 | Remove the phone-hosted mode (§3.9): server, client, gateway, game screen, and the hosted-only tests. No dedicated-server behavior changes. | `./gradlew test`, network smoke test (join, play, end with the dedicated server) |
| 2 | Characterization tests for today's server join/rejoin/end paths, using `TcpServer` against loopback sockets. Pins current behavior, including D4. | baseline |
| 3 | Extract `GameRoster`, `PlayerRecord` and `Connection` from `TcpServer`. Behavior-preserving: same phases (implicit), same purge at end. `connections` keyed by connection ID (fixes D4/D9 as a side effect of the move; the step-2 test that pinned D4 flips in this commit). | roster unit tests |
| 4 | Explicit `SessionPhase` + POST_GAME: stop purging/clearing at game end, reserve cached IDs, block client team changes outside LOBBY, keep explicit leavers as disconnected (Q12), sync `GameStateRepository` (D5, D8). | roster tests |
| 5 | `resetSession()` / `resetIfPostGame()`. Dedicated server: Reset button with confirmation, `renderPhase`, implicit Reset on the §3.1.1 settings, beacon stays on in RUNNING. | roster tests + device |
| 6 | Server-side admin check on all `ADMIN_*` messages and plain `STARTGAME` (D14). | `TcpServer` loopback test |
| 7 | Protocol v07, part 1: `sessionphase`/`gameid` in game info, `ADMIN_RESET`, `dedicatedserver` removed. Remote admin menu driven by `sessionphase` with `RESET_GAME` (§3.7.1). The version bumps once, here; later steps extend v07 before it ships. | `GameViewModelTest` (menu per phase), device |
| 8 | Client single writer thread (D3). | `TcpClient` test with a fake socket |
| 9 | Protocol v07, part 2: reliable envelope, outbox, `ACK`, `pending` replay, server de-dup, `ackseq` reconciliation; remove server→client queue (D1, D2, D11). | outbox + roster tests, client↔server loopback test |
| 10 | Phase-aware reconnect policy, backoff, Wi-Fi network callback + socket binding (D6, D10). | `TcpClient` test (policy), device |
| 11 | Optional: `connected` flag on client scoreboards. | device |

---

## 6. Test plan

### 6.1 Unit tests (JVM, no Robolectric; `org.json` is already a test dependency)

`GameRosterTest`:
- Start caches exactly the connected players that have MACs. Scores are 0.
- Disconnect in RUNNING keeps the record (`isConnected == false`). Disconnect in LOBBY removes it.
- Hello with a cached MAC restores ID, team, name, points and eliminations, in RUNNING and in POST_GAME.
- New player in RUNNING with *Allow join* off is rejected. With it on, the player never gets a cached player's ID and
  is cached.
- New player in POST_GAME is not cached and does not get a reserved ID.
- Reset removes disconnected cached players, zeroes connected ones, empties the cache, phase = LOBBY. A disconnect
  after Reset removes the player.
- Start is rejected in POST_GAME. Client team-change requests are rejected outside LOBBY.
- Explicit `LEAVE` in RUNNING and POST_GAME keeps the record as disconnected. Reset removes it.
- `resetIfPostGame()`: resets and returns true in POST_GAME; does nothing and returns false in LOBBY and RUNNING.
  Calling it twice resets once.
- Game mode, team sizes and Sort teams in POST_GAME: the disconnected cached players are gone **before** the rebalance
  runs. The resulting IDs match the same rebalance done in LOBBY with the same connected players.
- `ADMIN_RESET`/`ADMIN_*` from a non-admin sender is ignored (D14).
- Reliable de-dup: the same `seq` twice is applied once. A lower `seq` is dropped. A `gameId` mismatch is dropped. All
  of these are acked.
- Shooter credit while the shooter is disconnected.
- Connection-ID keys survive rebalance followed by new connections (D4 regression test).

`ClientOutboxTest`: seq assignment, cumulative ack, cap, clear on `gameId` change, pending-count reconciliation
formula.

`ReconnectPolicyTest`: unlimited vs. 3 tries by phase, backoff sequence, reset on Wi-Fi available (virtual clock).

`GameViewModelTest` additions: admin menu contents for each `sessionphase`; `RESET_GAME` sends `ADMIN_RESET` after
confirmation; the menu goes back to `START_GAME` when a LOBBY game info arrives; no `isServer` paths remain.

Loopback integration test (`TcpServer` + `TcpClient` logic over `127.0.0.1`): drop the client socket mid-game, send
two eliminations while "offline", reconnect. The server shows the player disconnected and then connected, counts
both eliminations once, and the client's lives equal its local count.

### 6.2 On-device checklist (dedicated server + ≥ 2 client phones)

1. Start a 2-team game. Walk one client out of range. Within ~4 s the server shows it with the disconnected icon,
   still on its team with its score.
2. While out of range, get that client eliminated twice. Walk back. It reconnects without user action. Same ID and
   team (blaster team light unchanged). Its lives are 2 lower. The shooter got 2 points. Each counted once.
3. Get eliminated *while* walking out (the D1 window). It is counted after reconnect.
4. Repeat 1–2 after pressing **Shuffle** in the lobby before the game (D4 regression).
5. End the game while a client is out of range. The server shows **Reset**, not Start Game. The client returns: game
   over on its screen, final score shown, same ID/team.
6. In POST_GAME, kill and relaunch the client app. It rediscovers the server and gets its old ID and team.
7. Tap **Reset** with one client still away. Confirm. That player is gone from the scoreboard, everyone else is 0/0,
   Start Game is back. Disconnect another client now: it is removed, not kept.
8. With mobile data on and a no-internet field Wi-Fi: steps 1–2 still reconnect (socket binding).
9. A client leaves the network on purpose mid-game. It stays on the scoreboard as disconnected through the end of the
   game. Reset removes it.
10. After a game, a new phone joins. It gets a free ID (not a disconnected player's) and plays the next game.
11. After a game, on a remote admin phone, the admin menu shows **Reset** instead of Start Game. Reset → confirm. The
    server is back in LOBBY and the admin menu shows **Start Game** again. Start a game from it.
12. After a game, with one client away, do each of these separately: change the game mode, team sizes, time limit or
    GPS mode, or tap Sort teams, on the server and (where available) from the remote admin menu. Each one shows "Game
    reset", the away player is gone, scores are 0, and the change is applied. Cancelling a dialog does not reset.
13. The game screen no longer offers any hosting path; the admin button appears only for admins.

---

## 7. Decisions

### 7.1 Resolved (2026-10-12)

| # | Question | Decision |
|---|---|---|
| Q-A | Should new players be able to join during POST_GAME? | **Yes**, as uncached lobby players. They are cached when the next game starts. (Q11) |
| Q-B | Explicit `LEAVE` during a game: remove the player, or keep them as disconnected? | **Keep as disconnected until Reset, then remove.** (Q12) |
| Q-C | Can an admin *client* trigger Reset? | **Yes.** Remote admins get Reset in place of Start Game after a game. After the reset they get Start Game again, same as the dedicated server. (Q13, §3.7.1) |
| Q-G | Does this apply to phone-hosted games? | **Hosted mode is removed** (Q15, §3.9). |
| — | Settings changes after a game | **Changing team settings, game limits or GPS mode, or sorting teams, in POST_GAME triggers Reset first.** (Q14, §3.1.1) |
| Q-F | Should Reset need confirmation? | **Explicit Reset (server button or admin menu): yes.** It discards players who may be about to walk back in. **Implicit Reset from a settings change: no confirmation**, just a "Game reset" toast. (§3.1) |
| Q-H | Which settings trigger the implicit Reset? | **Confirmed:** game mode, team sizes, game limits, GPS mode, Sort teams. Server-only settings, Allow join, Allow choose teams, per-player settings and kicks do not. (§3.1.1) |

### 7.2 Accepted defaults (not explicitly decided; revisit if needed)

| # | Question | Default |
|---|---|---|
| Q-D | Identity: MAC only (as requested), or MAC plus a per-install random ID as a fallback for blaster swaps and MAC-less clients? | MAC only, as requested. Leave room in the hello for an `installid` later. |
| Q-E | Fix D12 (double team-score increment) in this work? | No: fix it separately, right after. It is unrelated to reconnect, and it changes which side sends `TEAMELIMINATED`. |

---

## 8. Out of scope

- Persisting the roster (server) or the outbox (client) across process death or reboot.
- Reliable delivery for anything other than `ELIMINATED`.
- Replacing the polling `ClientThread` with NIO or coroutines. The roster split makes that easier later but does not
  require it.
- GPS data retention for disconnected players (still cleared at game end, as today).
- Any change to the BLE protocol.
