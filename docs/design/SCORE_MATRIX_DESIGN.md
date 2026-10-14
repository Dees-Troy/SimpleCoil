# SimpleCoil Score Matrix (Dedicated Server Mode) — Design Document

Status: **IMPLEMENTED AND VERIFIED (2026-10-14).** All decisions in §8 are resolved. Steps 1–7 of §9 are done, and
the device check (§10) has been run.

Implementation notes: the roster's matrix cells are a `MatrixCell` class rather than a `Triple`, and `Matrix` carries
the game id so the reply and its `gameid` come from one lock. The view's geometry and scroll math live in a pure
`ScoreMatrixGeometry`, tested on the JVM (`ScoreMatrixGeometryTest`). The project has no Robolectric, so step 5's
Robolectric test was replaced by that.

Revision 2 (2026-10-14): decisions recorded (§8). Refreshes are limited to one every 5 s, not one per second (Q3).
This phone's own name is highlighted in the scoreboard's orange, in both the row and the column header, instead of
a highlight band (Q8). §5.5, §6.1, §7, §9 and §10 are updated to match.
Branch audited: `V2-refactor` @ `36e020a`.
Date: 2026-10-14
Builds on `NETWORK_STACK_DESIGN.md` (dispatcher, `WireCodec`, `GameRoster`), `NETWORK_RECONNECT_DESIGN.md`
(reliable eliminations, the game roster) and `DEDICATED_SERVER_DESIGN.md` §7.4 (the shared scoreboard).

---

## 0. Summary

The dedicated server counts eliminations per player (`PlayerRecord.points`, `PlayerRecord.eliminated`), but it
throws away *who eliminated whom*. This feature adds that:

- **Server.** `GameRoster` records every applied elimination as a (shooter, victim) count. It starts at zero when a
  game starts and is cleared on reset, the same as the scores. It is never shown on the server screen.
- **Wire.** A new client command `SCOREMATRIXREQUEST`. The server answers only the player who asked, with a new
  JSON message `scorematrix`.
- **Client.** The scoreboard dialog gets a **SCORE MATRIX** button to the left of **OK**. Tapping it sends the
  request and switches the dialog to a matrix view. Rows are players (the shooters), columns are players (the
  victims), and cell (r, c) is how many times r eliminated c. Column names are drawn vertically. The grid scrolls in
  both directions, and the name row and name column stay pinned.

---

## 1. Requirements

| # | Requirement |
|---|---|
| R1 | The server keeps, for every pair of players, how many times the first eliminated the second. |
| R2 | The scoreboard dialog on the client shows a **SCORE MATRIX** button to the left of **OK**. |
| R3 | Tapping it sends a request to the server. The server replies with the matrix data. |
| R4 | The client shows a grid: every player name down the left (rows), every player name across the top (columns), and in each cell the number of times the row player eliminated the column player. |
| R5 | Column names are rotated to vertical so that 32 columns fit at a readable size. |
| R6 | The grid scrolls freely on both axes. With 32 players it is much larger than a phone screen. |
| R7 | The server screen does not show the matrix. |

### 1.1 Behavior that must not change

- Scoring, lives, limits and the game ending (`NETWORK_PROTOCOL.md` §6.2) stay exactly as they are. The matrix only
  records them.
- The reliable-elimination de-duplication (§6.1) is the only path that feeds the matrix. A duplicate or stale
  elimination must not be counted in the matrix either.
- The scoreboard dialog's current list, its live updates, and its behavior across rotation and recreation stay the
  same.
- Older clients that don't know `scorematrix` keep working against a new server, and the other way round (§4.3).

---

## 2. Current state

**Where an elimination is applied.** The victim's phone sends `ELIMINATED<shooter>` as a reliable message.
`ServerMessageHandler.handleReliable` checks it with `GameRoster.checkReliable`. On `APPLY` it calls
`handleEliminated`, which calls `GameRoster.onEliminated(sender, shooterId)` under the roster's lock:

```kotlin
// GameRoster.onEliminated (today)
if (phase == SessionPhase.LOBBY) return null
val victim = sender.player
if (victim != null) victim.eliminated++
val shooter = byId[shooterId]
val credited = shooter != null && (victim == null || !sameSide(shooter.playerId, victim.playerId))
val shooterPoints = if (credited) ++shooter!!.points else 0
```

That is the one place where both the shooter and the victim are known and the elimination is known to be new. The
matrix is recorded there.

**When ids can change.** Player ids are renumbered only in the lobby (`movePlayer`, `changeTeam` and `rebalance`
all return early outside `LOBBY`). Eliminations are counted only outside the lobby. So within one game a player id
names the same `PlayerRecord` from start to reset, with two exceptions:

- `kick(playerId)` during or after a game removes the record. With *allow join in progress*, a new player can then
  get the same id.
- `onDisconnect` during a game removes a player who is not on the game's roster (no blaster MAC). Their id can
  also be reused.

**The scoreboard dialog** (`ui/game/GameDialogFragments.kt`, `ScoreboardDialog`) is an `AlertDialog` whose view is
`player_data_dialog.xml` (one `RecyclerView`) and which has a single negative button, **OK**. `GameViewModel`
holds `scoreboardVisible` and `latestPlayerData`. `openScoreboard()` sends `PLAYERDATAREQUEST`, and the reply
reaches the dialog through `ClientEvent.PlayerData` → `updateScoreboardData` → `DialogCoordinator.showScoreboard`
→ `ScoreboardDialog.setData`. After a recreation the dialog asks for fresh data in `onCreate`.

---

## 3. Server

### 3.1 Storage: `GameRoster`

The matrix lives in `GameRoster`, next to the scores, under the same lock:

```kotlin
/** Eliminations in the current game: (shooter id, victim id) → count. Cleared with the scores. */
private val eliminationCounts = HashMap<Int, Int>()

private fun pairKey(shooter: Byte, victim: Byte): Int = (shooter.toInt() and 0xFF shl 8) or (victim.toInt() and 0xFF)
```

The map is sparse. A game between 32 players rarely has more than a few hundred non-zero cells, and the worst case is
1,024 entries. It is not kept in `PlayerRecord` because a count belongs to a pair, and both rows and columns have to
be purged when a record goes away (§3.3).

### 3.2 Recording

In `onEliminated`, after the existing scoring and only when both ends are known:

```kotlin
if (victim != null && shooter != null) {
    val key = pairKey(shooter.playerId, victim.playerId)
    eliminationCounts[key] = (eliminationCounts[key] ?: 0) + 1
}
```

- **Every applied elimination is recorded, credited or not** (Q1). A player's own grenade adds to the diagonal. A
  teammate's grenade adds to a same-team cell. The matrix answers "who eliminated whom", and the scoreboard's
  points still answer "who scored".
- **Unknown shooter** (an id with no record, such as an unpaired grenade or a player already removed): not
  recorded. There is no row to put it in. The victim's `eliminated` count still goes up, so a column total can be
  higher than the column's sum. This is already true of the scoreboard today.
- **Victim without a hello**: not recorded, as today, and it has no scoreboard row anyway.
- **Post-game eliminations** count, the same as they do in the final scores (`NETWORK_PROTOCOL.md` §6.2).
- Because `onEliminated` is reached only through `checkReliable == APPLY` (or a bare v06-style `ELIMINATED`, which is
  applied without de-duplication today), the matrix inherits the scores' exactly-once guarantee.

### 3.3 Clearing and purging

| Event | Matrix |
|---|---|
| `startGame()` | Cleared (in `resetScoresLocked`, beside `points = 0`). |
| `reset()` (POST_GAME → LOBBY) | Cleared (same place). |
| `resetScores()` | Cleared (same place). |
| `clear()` (server starting) | Cleared. |
| A record removed (`removeRecordLocked`: kick, a non-roster player disconnecting, a rebalance dropping a player) | That id's whole row and column are removed, so a later player who gets the id starts at zero. |
| A roster player disconnects (`KEPT`) | Kept. The player stays on the scoreboard, and so do their cells. |
| A roster player rejoins | Same record, same id, so the cells carry on. |

Purging on removal does mean a shooter's row sum can end up lower than their points, because the points for a
kicked victim are kept. This matches the scoreboard, which also drops the kicked player but not the points scored
against them.

### 3.4 Reading

```kotlin
/** One player as the matrix shows it. */
class MatrixPlayer internal constructor(
    @JvmField val playerId: Byte, @JvmField val name: String?, @JvmField val team: Int, @JvmField val connected: Boolean,
)

/** The current game's matrix: every player, and the non-zero (shooter, victim, count) cells. */
class Matrix internal constructor(@JvmField val players: List<MatrixPlayer>, @JvmField val cells: List<Triple<Byte, Byte, Int>>)

@Synchronized
fun scoreMatrix(): Matrix
```

`players` is every record in `byId`, connected or not, in id order. This is the same set the scoreboard shows. The
client picks its own order (§5.3), so the server doesn't need to know how the scoreboard sorts.

### 3.5 Answering the request

`ServerMessageHandler.handleCommand` gets one new branch next to `PlayerDataRequest`:

```kotlin
command == ClientCommand.ScoreMatrixRequest -> server.sendScoreMatrix(client)
```

`GameServer.sendScoreMatrix(client)` runs on the dispatcher (it already does: frames are handled there). It builds
`ServerMessage.ScoreMatrix` from `roster.scoreMatrix()` through `SnapshotBuilder.scoreMatrix()`, encodes it and sends
it only to `client`. It works for every player in every phase:

- **Lobby**: players, no cells. The client shows its empty state (§5.4).
- **Running / post-game**: the current game.

Any connected player may ask. It is not an admin command, and the data is no more private than the scoreboard.
There is no rate limit beyond what the client does (one request per tap or refresh, §5.5). A request is one lock
and one ≤ 6 KB frame. A connection that has not said hello gets the same reply as anyone else.

No `ServerEvent` is published. The server screen does not show the matrix (R7).

---

## 4. Wire protocol

### 4.1 Request (client → server, `MESG`)

| Command | Meaning | What the server does |
|---|---|---|
| `SCOREMATRIXREQUEST` | Send me the score matrix. | Replies with `scorematrix` (§4.2) to the sender only. |

New in `WireProtocol`: `NETMSG_SCOREMATRIXREQUEST = "SCOREMATRIXREQUEST"`. New in `WireMessages`:
`ClientCommand.ScoreMatrixRequest`. `WireCodec.text` and `decodeClientCommand` map between the two. It does not
start with `ADMIN_`, so `isAdminCommand` is false.

### 4.2 Reply (server → client, `JSON`)

Identified by the key `scorematrix`:

```json
{"scorematrix": {
   "players": [{"playerID": 1, "playername": "Alice", "team": 1, "connected": true},
               {"playerID": 2, "playername": "Bob",   "team": 1, "connected": false},
               {"playerID": 17, "playername": "Cara", "team": 2, "connected": true}],
   "cells": [[1, 17, 4], [17, 1, 2], [17, 17, 1]],
   "gameid": 418873301}}
```

- `players`: every player the matrix has rows and columns for. It uses the scoreboard's existing keys (`playerID`,
  `playername`, `connected`) plus `team` (for colouring; `1` in free for all).
- `cells`: the non-zero cells only, as `[shooter, victim, count]` arrays. Any cell not listed is 0.
- `gameid`: the game this matrix belongs to. It is left out in the lobby. The client uses it to drop a reply that
  arrives after the game changed (§5.5).

New in `WireMessages`:

```kotlin
data class WireMatrixPlayer(val playerId: Int, val name: String, val team: Int, val connected: Boolean)

/** [shooterId] eliminated [victimId] [count] times. */
data class WireMatrixCell(val shooterId: Int, val victimId: Int, val count: Int)

// in ServerMessage
/** Who eliminated whom in the current game. Only non-zero cells are sent. */
data class ScoreMatrix(val players: List<WireMatrixPlayer>, val cells: List<WireMatrixCell>, val gameId: Long?) : ServerMessage
```

`WireCodec.decodeServerJson` gets a `json.has(WireKeys.SCOREMATRIX)` branch, checked before `PLAYERS`. Order doesn't
matter here, because the reply has no other top-level key, but it keeps the "most specific first" pattern.

**Size.** It is one `writeUTF` frame, so the limit is 65,535 bytes. In the worst case, 32 players with 20-code-point
names in 4-byte scripts (modified UTF-8 encodes each as a 6-byte surrogate pair) come to ~4.5 KB of `players`.
1,024 cells at ≤ 14 bytes each (`[32,32,999],`) come to ~14 KB. That is well under the limit. A test pins the worst
case (§9).

### 4.3 Compatibility and the protocol version

The version stays `07` (Q2). The change only adds messages:

- **New client, old server.** The old server decodes `SCOREMATRIXREQUEST` as `ClientCommand.Other`. Since it doesn't
  start with `ADMIN_`, no branch of `handleCommand` matches, and the server ignores it. The client times out (§5.5)
  and shows "This server doesn't support the score matrix."
- **Old client, new server.** The old client never sends the request, so it never gets the reply. If it did, its
  `decodeServerJson` would return null for an unknown key, and the frame would be dropped.

`NETWORK_PROTOCOL.md` §4.1 and §4.2 get the new rows.

---

## 5. Client

### 5.1 Data path

```
tap SCORE MATRIX ─► GameViewModel.openScoreMatrix()
                      ├─ scoreMatrixVisible = true; showScoreMatrix(latest or Loading)
                      └─ send MESG SCOREMATRIXREQUEST, start 3 s timeout
server reply ─► ClientMessageHandler: ServerMessage.ScoreMatrix ─► ClientEvent.ScoreMatrix(message)
            ─► GameViewModel.onClientEvent: ScoreMatrixModel.from(message, layout, selfId)
            ─► latestScoreMatrix = model; if visible: screen.showScoreMatrix(model)
            ─► DialogCoordinator.showScoreMatrix ─► ScoreboardDialog.setMatrix(model)
```

- `ClientEvent.ScoreMatrix(val matrix: ServerMessage.ScoreMatrix)` is new in `NetworkEvents.kt`. The message is
  already typed when it is decoded on the reader thread, and at ≤ 1,024 cells there is nothing heavy left to do off
  the main thread. So, unlike `PlayerDataUpdate`, it doesn't go through `NetworkSessionGateway`'s parse dispatcher.
- `GameViewModel` gains `scoreMatrixVisible` (the dialog is showing the matrix, not the list) and
  `latestScoreMatrix: ScoreMatrixState?`. Both reset to false/null whenever the scoreboard closes, the client leaves
  the server, or a new `gameid` arrives in a game info.
- `GameScreen` gains `showScoreMatrix(state: ScoreMatrixState)`. `DialogCoordinator` implements it the same way as
  `showScoreboard`: it updates the open `ScoreboardDialog`, or opens one.

### 5.2 The model: `ScoreMatrixModel` (pure, `ui/scoreboard/`)

```kotlin
sealed interface ScoreMatrixState {
    data object Loading : ScoreMatrixState
    data object Unsupported : ScoreMatrixState
    data class Ready(val matrix: ScoreMatrixModel) : ScoreMatrixState
}

data class ScoreMatrixModel(
    val players: List<Entry>,      // row order == column order
    /** counts[row][col]: times players[row] eliminated players[col]. */
    val counts: Array<IntArray>,
    val rowTotals: IntArray,       // eliminations made
    val columnTotals: IntArray,    // times eliminated (by known shooters)
    val gameId: Long?,
) {
    data class Entry(val playerId: Byte, val name: String, val team: Int, val connected: Boolean, val isSelf: Boolean)
    val isEmpty: Boolean get() = rowTotals.all { it == 0 }

    companion object { fun from(message: ServerMessage.ScoreMatrix, layout: TeamLayout, selfId: Byte): ScoreMatrixModel }
}
```

`from` builds the dense array from the sparse cells. It ignores cells that name an id missing from `players`, and
clamps negative counts to 0. It is pure and unit-tested, like `ScoreboardRows`.

### 5.3 Ordering

Rows and columns use the same order, so the diagonal is the self-elimination line:

- **Team games**: by team number, then player id. Teams form diagonal blocks, so friendly fire stands out as
  coloured squares on the diagonal and cross-team kills fill the rest.
- **Free for all**: by player id.

The order is deliberately not the scoreboard's ranking. A stable order means a player stays in the same row and
column when the matrix is refreshed (§5.5) and the scores change.

### 5.4 The dialog

`ScoreboardDialog` keeps one dialog with two faces:

- **Buttons.** `setNeutralButton(R.string.score_matrix)` is added next to the existing `setNegativeButton(R.string.ok)`.
  `AlertDialog` puts the neutral button at the start edge and the negative one at the end, so **SCORE MATRIX**
  sits to the left of **OK** (to the right in RTL locales, which is correct for those). An `AlertDialog` button
  dismisses the dialog by default, so the neutral button's click listener is replaced in `setOnShowListener`
  (`getButton(BUTTON_NEUTRAL).setOnClickListener { … }`). It calls `vm.onScoreMatrixToggled()`.
- **Toggle.** In the matrix face the neutral button reads **SCOREBOARD** and goes back to the list. **OK** closes the
  dialog from either face, as today.
- **Layout.** `player_data_dialog.xml` gets a sibling of `player_list`: a `FrameLayout` holding the
  `ScoreMatrixView` (§6), a progress indicator (`Loading`) and a message `TextView` ("No eliminations yet" for an
  empty matrix, "This server doesn't support the score matrix" for `Unsupported`). The face that isn't shown is
  `GONE`. The dialog's `setData` (list) and the new `setMatrix(state)` each update their own face, so a live
  scoreboard update while the matrix is showing changes nothing visible.
- **Size.** In the matrix face the content is given a fixed height, `min(content height, 80 % of the window
  height)`, so the dialog doesn't jump as data arrives. The width is the dialog's normal width. On a phone that is
  ~90 % of the screen, and the grid scrolls inside it.
- **Recreation.** The face is kept in `GameViewModel.scoreMatrixVisible`, not in the fragment, so a rotation or
  dark-mode switch brings the dialog back on the same face. `ScoreboardDialog.onCreate`, which already calls
  `vm.openScoreboard()` after a recreation, also re-requests the matrix if that face was showing. The scroll
  position is saved through `ScoreMatrixView.onSaveInstanceState`.
- **Visibility.** The button is shown only while joined to a dedicated server (`networkMenuType ==
  NETWORK_TYPE_JOINED`), which is the only case where the scoreboard opens today.

### 5.5 Requests, refresh and timeout

- **On tap** (into the matrix face): show `latestScoreMatrix` straight away if there is one, otherwise `Loading`, and
  send `SCOREMATRIXREQUEST`, subject to the limit below.
- **Refresh while open** (Q3): while the matrix face is showing, the view model re-requests whenever something that
  changes the matrix reaches this phone: `ClientEvent.PlayerData` (the server pushes it after a post-game
  elimination, end of game, kick and reset), and this phone's own `ELIMINATED`/`TEAMELIMINATED`. The matrix of a
  running game therefore follows this phone's own kills and catches up with everyone else's on the next push.
  There is no polling.
- **Limit: at most one request every 5 s** (`SCORE_MATRIX_MIN_INTERVAL_MS = 5_000`), counted from the last request
  sent, and at most one in flight. This covers taps and refreshes. A trigger inside the window isn't dropped. It
  marks one request as pending, sent when the window ends if the matrix face is still showing, so the last change
  always reaches the screen within 5 s. Leaving the matrix face cancels the pending request. Toggling the faces
  quickly therefore shows the cached matrix and sends nothing new.
- **Timeout**: if no reply arrives within 3 s of the first request, and nothing was shown yet, the state becomes
  `Unsupported`. A late reply still replaces it. A timeout on a refresh keeps the last matrix on screen.
- **Stale replies**: a reply whose `gameid` differs from the client's current game id is dropped. This happens when
  the request crossed a reset or a new game.
- **Disconnects**: while reconnecting, the last matrix stays on screen. After the rejoin snapshot, an open matrix face
  re-requests.

---

## 6. `ScoreMatrixView` (custom `View`, `ui/scoreboard/`)

A single custom view that draws the whole grid on a `Canvas` and handles its own scrolling. The alternatives were
rejected:

| Option | Why not |
|---|---|
| `ScrollView` inside `HorizontalScrollView` around a `TableLayout` | Scrolls one axis at a time, no diagonal fling, and the name row and column can't stay pinned without three synced scroll views. |
| `RecyclerView` with a 2-D `LayoutManager` | A lot of code for at most 1,024 cells, and pinned headers still need custom work. |
| `RecyclerView` of rows, each a `HorizontalScrollView` | Rows scroll separately, or every row has to be kept in sync. |
| Jetpack Compose `LazyVerticalGrid` | The play screen is View-based (`DEDICATED_SERVER_DESIGN.md` §11); adding Compose for one dialog is out of proportion. |

Drawing ≤ 33 × 33 cells (with the totals) per frame on a `Canvas` is trivially cheap, so no recycling is needed.

### 6.1 Geometry

```
┌──────────┬───┬───┬───┬───┬─────┐
│          │ A │ B │ C │ D │  Σ  │   ← column header: names rotated −90° (read bottom-to-top),
│ (corner) │ l │ o │ a │ a │     │     height = widest name, clamped to 120 dp
│          │ i │ b │ r │ n │     │
├──────────┼───┼───┼───┼───┼─────┤
│ Alice    │ · │ 2 │ 4 │ 1 │  7  │   ← row header: names left-aligned, width = widest name,
│ Bob      │ 1 │ · │   │ 3 │  4  │     clamped to 120 dp, ellipsized
│ Cara     │ 2 │   │ 1 │   │  3  │
│ Dan      │   │ 5 │   │ · │  5  │
├──────────┼───┼───┼───┼───┼─────┤
│ Σ        │ 3 │ 7 │ 5 │ 4 │ 19  │   ← column totals: times eliminated
└──────────┴───┴───┴───┴───┴─────┘
```

- **Cells** are square, 40 dp (the 48 dp touch target isn't needed: cells aren't tappable, §6.4). The count is
  centered. Zero is drawn as nothing, except on the diagonal, which gets a shaded background and draws its count
  when non-zero. Text sizes come from `dimens.xml`, in `sp`, so the grid follows the font scale.
- **Pinned headers.** The column header band and the row header band are drawn last, over the scrolled cells, at a
  fixed position on their own axis. They scroll along with the cells on the other axis. The corner cell is fixed on
  both axes and is drawn on top of everything.
- **Totals** (Q4): a Σ column at the right and a Σ row at the bottom, drawn in bold with a separator line. They
  scroll with the grid; they are not pinned.
- **Rotation.** Column names are drawn with `canvas.rotate(-90f)` around each column's center, baseline toward the
  grid. Names longer than the header band are ellipsized with `TextUtils.ellipsize`, measured once per data change.
  Text layout is never redone per frame.
- **Colour.** In team games, each name gets a small team-colour bar (`TeamColors.colorOf`) on the side nearest the
  grid, and the same-team blocks on the diagonal get a faint team-colour tint. In free for all there is none.
- **Self** (Q8). This phone's own name is drawn in the scoreboard's self colour (orange, `0xFFFF8C00`), in both the
  row header and the column header, exactly as the scoreboard list colours it. There is no highlight band and the
  cells aren't tinted. The colour is shared rather than copied: `ScoreboardAdapter.SELF_COLOR` moves to
  `ui/scoreboard/ScoreboardColors.kt` (`SELF_NAME`), and both the adapter and `ScoreMatrixView` use it.
- **Disconnected** players' names are drawn at reduced alpha, matching the scoreboard list.

### 6.2 Scrolling

- `GestureDetector` for drags and flings, and `OverScroller` for fling animation via `computeScroll()`. The view
  scrolls on both axes at once, so diagonal drags and flings work.
- Scroll range: `max(0, content − viewport)` on each axis, clamped.
- Edge effects (`EdgeEffect`) on all four sides, as a standard scroll view has.
- `requestDisallowInterceptTouchEvent(true)` once a drag starts, so the dialog doesn't take the gesture.
- Scrollbars: `isHorizontalScrollBarEnabled`/`isVerticalScrollBarEnabled` with
  `computeHorizontalScrollRange()`/`Extent()`/`Offset()` (and the vertical ones) overridden, so the system draws
  fading scrollbars. These show at once how big the grid is.
- When opened, the view starts scrolled so that this phone's own row is visible (Q5).
- Pinch to zoom (Q6) is **not** in the first version. `ScaleGestureDetector` can be added later, scaling the cell
  size between 28 dp and 64 dp, without changing anything else.

### 6.3 Data changes

`setMatrix(model)` keeps the scroll position when a player is still present, and clamps it to the new range. It then
calls `requestLayout()` only when the content size changed, and otherwise just `invalidate()`. A refresh while
scrolled therefore doesn't jump.

### 6.4 Accessibility

`ScoreMatrixView` uses an `ExploreByTouchHelper`. Each visible cell is a virtual view with a description such as
"Alice eliminated Bob 2 times", and the headers read their names and totals. Cells are not otherwise interactive.
A tap does nothing in the first version.

---

## 7. Strings and resources

`strings.xml`: `score_matrix` ("SCORE MATRIX"), `scoreboard` ("SCOREBOARD"), `score_matrix_empty` ("No eliminations
yet"), `score_matrix_unsupported` ("This server doesn't support the score matrix"), `score_matrix_total` ("Σ"),
`score_matrix_cell_description` ("%1$s eliminated %2$s %3$d times", as a plural). `dimens.xml`: cell size, header
clamp, text sizes. `colors.xml`: diagonal shade. The self colour comes from `ScoreboardColors` (§6.1).

---

## 8. Decisions (all resolved 2026-10-14)

| # | Question | Options | Recommendation |
|---|---|---|---|
| Q1 | Record uncredited eliminations (own grenade, teammate's grenade)? | Record all / credited only | **Accepted: record all.** It is "who eliminated whom", the diagonal and friendly-fire blocks are useful information, and they are visually separated (§6.1). Row totals then count eliminations, not points; the Σ header says so in its content description. |
| Q2 | Bump the protocol version to `08`? | Bump / keep `07` | **Accepted: keep `07`.** The change only adds messages, and both directions degrade safely (§4.3). A bump would lock every older phone out of a new server for the sake of one button. |
| Q3 | Should an open matrix update live? | Once per tap / event-driven refresh (§5.5) / poll every N s | **Decided: event-driven refresh, at most one request every 5 s** (§5.5). A trigger inside the window is sent when it ends, so the matrix is never more than 5 s behind what this phone has heard. No traffic when nothing changes. |
| Q4 | Totals row and column? | Yes / no | **Accepted: yes.** It costs one row and column of drawing, and "eliminations made / times eliminated" is the first thing people read off a matrix. |
| Q5 | Where does the view start scrolled? | Top-left / own row visible | **Accepted: own row visible**, top of the row header aligned to it when that fits. |
| Q6 | Pinch to zoom? | Now / later / never | **Accepted: later.** Two-axis scrolling with pinned headers covers R6; zoom is additive (§6.2). |
| Q7 | Should the server's own screen get the matrix later? | — | Out of scope (R7). `GameRoster.scoreMatrix()` and `ScoreMatrixView` don't depend on the play screen, so it would be a small change. |
| Q8 | How is this phone's own player marked? | Highlight band on row and column / name coloured as on the scoreboard | **Decided: the name is coloured as on the scoreboard** (orange, shared `ScoreboardColors.SELF_NAME`) in both the row and the column header (§6.1). |

---

## 9. Plan

The gate on every step: `./gradlew test` and `assembleDebug` pass. **[device]** marks a step that ends with the
check in §10.

| Step | Work | Depends on |
|---|---|---|
| 1 | `GameRoster`: `eliminationCounts`, recording in `onEliminated`, clearing in `resetScoresLocked`/`clear`, purging in `removeRecordLocked`, `scoreMatrix()`. `GameRosterTest`: credited, self and teammate kills, unknown shooter, duplicate reliable not double-counted, cleared on start/reset, purged on kick, kept across a roster player's disconnect and rejoin, post-game kill counted. | — |
| 2 | Wire: `NETMSG_SCOREMATRIXREQUEST`, `WireKeys.SCOREMATRIX`, `ClientCommand.ScoreMatrixRequest`, `ServerMessage.ScoreMatrix`, `WireMatrixPlayer`/`WireMatrixCell`, codec in both directions. `WireCodecTest` round trips. `WireFixtures` + `ClientWireFixturesTest`/`ServerWireFixturesTest` fixtures for both frames. A worst-case-size test (32 players, max-length 4-byte-script names, 1,024 cells at 999) asserts the frame fits in `writeUTF`. An unknown-command test confirms an old-style server path ignores it. | — |
| 3 | Server: `SnapshotBuilder.scoreMatrix()`, `GameServer.sendScoreMatrix(client)`, the `ServerMessageHandler` branch. `TcpServerLoopbackTest`: two clients, one eliminates the other, the requester (and only the requester) receives the matrix with the right cell. | 1, 2 |
| 4 | `ScoreMatrixModel` + `ScoreMatrixState` (pure). `ScoreMatrixModelTest`: ordering for FFA, 2-team and 4-team; totals; cells for unknown ids ignored; empty matrix. | 2 |
| 5 | Move `ScoreboardAdapter.SELF_COLOR` to `ScoreboardColors.SELF_NAME`. `ScoreMatrixView`: drawing (self name in `SELF_NAME`), pinned headers, two-axis scroll and fling, edge effects, scrollbars, saved state, `ExploreByTouchHelper`. Robolectric test for measure, scroll clamping and saved state. | 4 |
| 6 | Client wiring: `ClientEvent.ScoreMatrix`, `ClientMessageHandler`, `GameViewModel` (visible flag, latest state, 5 s request limit with a trailing pending request, timeout, stale-`gameid` drop, refresh triggers), `GameScreen.showScoreMatrix`, `DialogCoordinator`, `ScoreboardDialog` (neutral button that doesn't dismiss, two faces, recreation). `GameViewModel` tests with the fake gateway: toggle, timeout → `Unsupported`, late reply, a burst of triggers sends one request now and one 5 s later, leaving the matrix face cancels the pending one, reset clears. **[device]** | 3, 5 |
| 7 | Docs: `NETWORK_PROTOCOL.md` §4.1/§4.2 rows; this document's status. | 6 |

---

## 10. Verification

**Score matrix check (devices, ~10 min; at least one phone and one tablet or Chromebook):**

1. Server plus three clients, two-team game. A eliminates C twice, C eliminates A once, B is eliminated by their own
   grenade, A is eliminated by teammate B's grenade. On every client, Scoreboard → **SCORE MATRIX** shows exactly
   those cells, the diagonal and friendly-fire cells shaded, and correct Σ totals. The server screen is unchanged.
2. **SCORE MATRIX** sits to the left of **OK**. Tapping it doesn't close the dialog. **SCOREBOARD** goes back, and
   **OK** closes from either face.
3. With the matrix open on A, A eliminates C again: A's cell updates within ~5 s without reopening. A's name is
   orange in its row and its column, as it is on A's scoreboard list; nobody else's is.
4. Rotate the device and toggle dark mode with the matrix open and scrolled: it comes back on the matrix face, at
   the same scroll position.
5. 32 players (the loopback test clients from `TcpServerLoopbackTest`, or a test build that fakes the reply): drag
   and fling on both axes, including diagonally. The name row and column stay pinned, the scrollbars show, and
   vertical names are readable and ellipsized when too long.
6. Kick C after the game: C's row and column disappear from the matrix, as C does from the scoreboard.
7. Reset: the matrix shows "No eliminations yet". Start a new game: it starts empty.
8. A client drops and rejoins mid-game: its cells are still there afterwards.
9. A new client against a server built from `36e020a`: the button shows "This server doesn't support the score
   matrix" after ~3 s, and nothing else changes.
10. TalkBack: swiping across the grid reads "Alice eliminated Bob 2 times".

---

## 11. Out of scope

Showing the matrix on the dedicated server screen (R7); keeping matrices from earlier games or exporting them; a
matrix for server-less (blaster-only) games; tapping a cell for details (time of each elimination, weapon or
grenade); pinch to zoom (Q6); Compose.
