# SimpleCoil Network Protocol

How phones running SimpleCoil find a dedicated server and talk to it during a game. The server
is the `DedicatedServerActivity` running `TcpServer`; each player's phone is a client
(`TcpClient`). Clients never talk to each other directly. Everything goes through the server.

## 1. Finding the server (UDP, port 17500)

UDP is used for discovery only.

- The server broadcasts `SimpleCoil:BEACON:<server name>` to the Wi-Fi subnet's broadcast
  address every second for as long as it runs.
- A client that is looking for a server broadcasts `SimpleCoil:DISCOVER` every second. The
  server answers each query with a beacon sent straight back to the client.
- The client uses the first beacon it hears. It takes the server's address from the packet and
  stops listening.
- A player can also type the server's IP address in, which skips discovery.

## 2. The connection (TCP, port 17510)

Each client keeps one TCP connection to the server. The client binds the socket to Wi-Fi, so
it still works when the field's Wi-Fi has no internet and Android would rather use mobile data.

### Framing

Every message is one Java `writeUTF` string: a 2-byte big-endian length, then the text in
modified UTF-8. The text always starts with the same header:

```
SimpleCoil:07MESG<command>     a short text command
SimpleCoil:07JSON<json>        a JSON object
```

`07` is the protocol version. The only messages without the header are the keep-alives `ping`
and `pong`, and `VERSIONERROR`.

### Version check

When the server receives a message that starts with `SimpleCoil:` but carries a different
version, it replies with a bare `VERSIONERROR`. The client then shows an error and stops.

### Keep-alive

The server checks each connection every 100 ms. If a client has sent nothing for 1 s, and
again at 2 s, the server sends `ping`. The client answers with `pong`. If the server hears
nothing for 3.5 s it drops the client. The client also drops the connection when it hears
nothing from the server for 3.5 s, then tries to reconnect (see §5).

## 3. Joining

1. The client connects and sends a **hello** (JSON):
   ```json
   {"playerID": 0, "playername": "Alice", "blaster_mac": "AA:BB:...",
    "rejoin": true, "gameid": 418873301, "pending": [{"rmsg": "ELIMINATED7", "seq": 3}]}
   ```
   - `playerID` is the ID the player wants. `0` means "pick one for me".
   - `blaster_mac` identifies the player across reconnects.
   - `rejoin`, `gameid` and `pending` appear only when they apply (see §5).
   - If the player has a paired grenade, a `pairedgrenadeID` message follows (see §4.2).
2. The server picks the player's ID and team and makes the name unique. A name that is empty,
   "Player", profane, or already taken is replaced with a random one.
3. The server replies with `MESG SERVERREPLY<id>`, the player's assigned ID.
4. The server sends a **game info** snapshot (§4.2) to everyone. The new player's copy includes
   their own score.

The server refuses the join in these cases:
- **Game already running** and the server does not allow joining mid-game: it sends
  `MESG ERROR<reason>` and closes the connection.
- **Server full**: it closes the connection.

Player IDs run from 1 to 32. In team games, the ID decides the team. The IDs are split into
consecutive blocks, sized by the team sizes.

## 4. Messages

### 4.1 Text commands (`MESG`)

**Client → server**

| Command | Meaning | What the server does |
|---|---|---|
| `SHOTFIRED` | I fired (sent at most every 400 ms). | Relays `SHOTFIRED` to everyone, so their phones play the sound. |
| `HIT<shooter>` | I was hit by `<shooter>` (rate limited to 400 ms). | Sends `HIT<me>` to the shooter only. |
| `OUT<shooter>` | That hit took my health to zero. | Sends `OUT<me>` to the shooter only. |
| `ELIMINATED<shooter>` | I was eliminated by `<shooter>`. Sent reliably (§6). | Scores it (§6.2), then tells the shooter `ELIMINATED<me>` and the shooter's teammates `TEAMELIMINATED`. |
| `TEAMELIMINATED` | I scored. | Relays it to my teammates. |
| `PLAYERDATAREQUEST` | Send me the scoreboard. | Replies with `playerdata` (§4.2). |
| `LEAVE` | I'm leaving. | Disconnects the player (§5). Ends the game if one player or none is left connected. |
| `ENDGAME` | Sent by a non-admin player leaving a game. | Treated like `LEAVE`. |

**Admin commands (client → server).** The server only accepts these from players the server
operator marked as admin. It ignores them from anyone else.

| Command | Effect |
|---|---|
| `ADMIN_STARTGAME` (or `STARTGAME`) | Start the game. Fails with `ERROR Reset the game first` after a game. |
| `ADMIN_ENDGAME` | End the game. |
| `ADMIN_RESET` | Clear the finished game and go back to the lobby. |
| `ADMIN_GAMEMODE<n>` | Set the game mode (1 = free for all, 2 = two teams, 4 = four teams) and reassign IDs. |
| `ADMIN_TEAMSIZES<a,b,...>` | Set team sizes (they must add up to 32) and reassign IDs. |
| `ADMIN_SORTTEAMS` | Shuffle the players into teams. |
| `ADMIN_KICK<id>` | Remove a player. They receive `SERVERCANCEL`. |

The game mode, team-size and sort commands only work when no game is running. Run after a
game, they reset to the lobby first.

**Server → client**

| Command | Meaning |
|---|---|
| `SERVERREPLY<id>` | Your player ID. Also sent when the server moves you to a new ID or team. |
| `STARTGAME` / `ENDGAME` | The game started or ended. |
| `SHOTFIRED` | Someone fired. |
| `HIT<id>` / `OUT<id>` | You hit player `<id>`, or knocked them out. |
| `ELIMINATED<id>` | You eliminated player `<id>`. Your score goes up by one. |
| `TEAMELIMINATED` | A teammate scored. |
| `ACK<seq>` | All of your reliable messages up to `<seq>` have been applied (§6). |
| `ERROR<text>` | Show this error to the player. |
| `SERVERCANCEL` | You were kicked, or the server shut down. Don't reconnect. |

### 4.2 JSON messages

Each JSON message is identified by the key it contains.

**Client → server**

| Key | Contents |
|---|---|
| hello | `playerID`, `playername`, plus optional `blaster_mac`, `rejoin`, `gameid`, `pending` (§3). |
| `playernamechange` | The player's new name. |
| `playersettings` | The player's own settings: `health`, `reloadshots`, `reloadtime`, `reloadonempty`, `manualrespawn` (optional, default off), `spawntime`, `respawnshieldtime`, `damage`, `liveslimit` (optional), `shotmodesingle`/`shotmodeburst3`/`shotmodeauto`, `firingmode`. Rejected unless the server allows player settings. |
| `requestteamchange` | The team the player wants. Only works in the lobby, in a team mode, when the server lets players choose teams, and when that team has an open slot. |
| `pairedgrenadeID` | Which grenade this player paired (see `GRENADES.md`). |
| `gpslongitude`, `gpslatitude` | The player's location, when GPS is on. |
| `rmsg`, `seq`, `gameid` | A reliable message (§6). |

**Server → client**

| Key | Contents |
|---|---|
| `players` | **Game info**, the full snapshot: every player's `playerID`, `playername`, `playerIP`; `limits` (`timelimit`, `liveslimit`, `scorelimit`); `gamemode`; `teamsizes`; `gamestate`; `sessionphase`; `gameid` (when not in the lobby); `timeremaining`; `usegps`; `allowplayersettings`; `onlyserversettings`; `allowchooseteams`; `starttime`; and `playersettings` for everyone. The copy sent to one player also has `playergameupdate`, which holds that player's `points`, `eliminated`, `teampoints`, `timeremaining` and `ackseq`. |
| `playerdata` | Scoreboard: each player's `playerID`, `playername`, `points`, `eliminated`, and whether they are `connected`. |
| `playersettings` | Every player's settings (fields as above, plus `recoilsetting` and `isadmin`), and `allowplayersettings`. The settings under the client's own ID replace its local settings. |
| `grenadepairings` | Every grenade pairing: a list of `{pairedgrenadeID, playerID}`. |
| `gpsupdate` | Other players' `playerID`, `team` and location, sent every second. Only players who moved are included. Every 20th update (and after anyone joins or leaves) is complete and is flagged with `gpsfullupdate`. |

## 5. Game phases and reconnecting

The server is in one of three phases, sent as `sessionphase`:

| Value | Phase | Ends with |
|---|---|---|
| 0 | Lobby | Start game → Running |
| 1 | Running | End game (by admin, a time/score/lives limit, or everyone leaving) → Post-game |
| 2 | Post-game | Reset → Lobby |

When a game starts, the server picks a random `gameid` and records every player who has a
blaster MAC. That list is the game's **roster**.

**When a player drops:**
- **In the lobby**, the player is removed.
- **During or after a game**, a player on the roster stays on the scoreboard, shown as
  disconnected, and keeps their ID and score. The server removes them only when it resets to
  the lobby. Anyone not on the roster is removed.

**When a player comes back:**
- The client sends a hello with `rejoin: true`.
- If the hello's `blaster_mac` is on the roster, the server gives the player back the same ID,
  team and score, and replies with `SERVERREPLY` and a snapshot.
- If an old connection for that player is still open, the server closes it.

**How the client retries:**
- It waits 1 s, then 2 s, then 4 s, then 5 s between each later attempt.
- When Wi-Fi comes back, it retries at once.
- During or after a game, it keeps trying until the player leaves.
- In the lobby, it gives up after 3 failed tries.
- If it gives up, or it receives `SERVERCANCEL` or `VERSIONERROR`, it stops.

## 6. Reliable eliminations

An elimination changes scores and lives, so it must not be lost or counted twice. Eliminations
are the only messages sent this way. Everything else is sent once, and a dropped message is
fixed by the next snapshot.

### 6.1 Delivery

1. The client numbers each elimination (`seq` 1, 2, 3, … for each game). It keeps the
   elimination in an **outbox** and sends:
   `{"rmsg": "ELIMINATED7", "seq": 12, "gameid": 418873301}`.
2. The server applies it only if `gameid` matches the current game and `seq` is higher than
   the last one it applied for that player. Then it replies `ACK<seq>`.
   - **Duplicates** are acknowledged but not applied again.
   - **Messages from another game** are acknowledged and dropped.
3. The client removes an elimination from the outbox once an `ACK` covers its number.
4. On reconnect, the client resends everything still in the outbox, inside the hello's
   `pending` list. The server applies those before it builds the player's snapshot.
5. The snapshot's `ackseq` tells the client which eliminations the server already counted. The
   client adds any it hasn't counted yet. A player never gets a life back, and never loses the
   same life twice.
6. A new `gameid`, or a reset, empties the outbox.

### 6.2 Scoring

- The victim always loses the life.
- The shooter scores only if they are a different player and, in a team game, on the other
  team. So a kill by your own grenade, or a teammate's grenade, costs you a life and scores
  nothing.
- The server ends the game when:
  - a player (in free for all) or a team reaches the score limit, or
  - one team or none still has lives left.
- An elimination that arrives after the game ends still counts in the final scores.

## 7. Bandwidth estimates

When GPS is enabled (`usegps`), clients send location updates to the server as players move, and the server broadcasts a combined location matrix for all players to every client every second (`GPS_UPDATE_INTERVAL = 1000 ms`).

### 7.1 Data rates per packet

- **Client Upstream (1 Hz):**
  - Payload: 2-byte UTF length + `SimpleCoil:07JSON` (17 B) + `{"gpslongitude":...,"gpslatitude":...}` (~56 B) $\approx$ **75 bytes/sec** (~0.60 kbps).
  - With IPv4/TCP headers (+40 B): **115 bytes/sec** (~0.92 kbps).
- **Server Downstream Broadcast (1 Hz):**
  - Payload: 2-byte UTF length + `SimpleCoil:07JSON` (17 B) + outer JSON wrapper (~16 B) + per-player position entry (~78 B per player).
  - Packet payload sizes by player count ($N$):
    - **8 Players:** ~659 bytes/sec payload (~5.27 kbps per client).
    - **16 Players:** ~1,283 bytes/sec payload (~10.26 kbps per client).
    - **32 Players:** ~2,531 bytes/sec payload (~20.25 kbps per client).
- **Server Scaling:** Inbound traffic scales linearly ($O(N)$), while outbound traffic scales quadratically ($O(N^2)$) as position updates for all $N$ players are relayed to all $N$ connected clients.

### 7.2 Bandwidth summary tables

#### Per-Client Bandwidth

| Player Count ($N$) | Upstream (Payload) | Downstream (Payload) | Total Client Bandwidth (Payload) | Total Client Bandwidth (Wire IP/TCP) | Hourly Data Usage |
|---|---|---|---|---|---|
| **8 Players** | 0.60 kbps (75 B/s) | 5.27 kbps (659 B/s) | **5.87 kbps** (0.73 KB/s) | **6.51 kbps** (0.81 KB/s) | **~2.9 MB / hr** |
| **16 Players** | 0.60 kbps (75 B/s) | 10.26 kbps (1,283 B/s) | **10.86 kbps** (1.36 KB/s) | **11.50 kbps** (1.44 KB/s) | **~5.2 MB / hr** |
| **32 Players** | 0.60 kbps (75 B/s) | 20.25 kbps (2,531 B/s) | **20.85 kbps** (2.53 KB/s) | **21.81 kbps** (2.73 KB/s) | **~9.8 MB / hr** |

#### Dedicated / Host Server Bandwidth

| Player Count ($N$) | Server Inbound (Upload) | Server Outbound (Broadcast) | Total Server Payload Throughput | Total Server Wire Bandwidth (IP/TCP) | Hourly Data Usage |
|---|---|---|---|---|---|
| **8 Players** | 4.80 kbps (0.60 KB/s) | 42.18 kbps (5.27 KB/s) | **46.98 kbps** (5.87 KB/s) | **~52.1 kbps** (6.51 KB/s) | **~23.4 MB / hr** |
| **16 Players** | 9.60 kbps (1.20 KB/s) | 164.22 kbps (20.53 KB/s) | **173.82 kbps** (21.73 KB/s) | **~184.1 kbps** (23.01 KB/s) | **~82.8 MB / hr** |
| **32 Players** | 19.20 kbps (2.40 KB/s) | 647.94 kbps (80.99 KB/s) | **667.14 kbps** (83.39 KB/s) | **~697.9 kbps** (87.23 KB/s) | **~314 MB / hr** |

