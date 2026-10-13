# How Grenades Work in SimpleCoil

A Recoil grenade is an IR emitter with its own ID. A blaster can't tell who threw the grenade:
any blaster within range simply reports that it was hit by a grenade. To give the right player
credit, SimpleCoil **pairs** each grenade with a player, then looks that pairing up when a
grenade hit comes in. The code lives in `GrenadeDecoder.kt`, `HitResolver.kt` and
`BlasterSessionEngine.kt`, plus the server and client classes `TcpServer.java` and
`TcpClient.java`.

## 1. How a grenade shows up in telemetry

A grenade hit arrives in one of the two hit slots of a blaster's telemetry packet (see
`BLE_PROTOCOL.md` §4).

- **Hit-by byte:** always `167`, the grenade marker.
- **Shot ID byte:** split in two.
  - High 4 bits: the **grenade ID** (1–15).
  - Low 4 bits: the **event**.

| Event | Value | Meaning |
|---|---|---|
| Damage | `0x1` | The grenade went off. |
| Pair | `0xF` | The grenade is offering to pair. |
| New pair | `0xE` | The grenade is starting a new pairing. |
| Disarm | `0xD` | The grenade was disarmed. |

Only damage events can hurt anyone. The other three events are control signals and never cost
health.

## 2. Pairing

1. The player's blaster receives a **pair** event from the grenade.
2. If the player has no grenade yet, the phone saves that grenade ID and shows "grenade
   paired". If the player already has a grenade, the pair event is ignored.
3. A **new pair** or **disarm** event clears the player's grenade on their own phone, so they
   can pair a different one.

In a network game:

1. After pairing, the client sends `{"playerID": <me>, "pairedgrenadeID": <id>}` to the server.
   It sends it again after every reconnect.
2. The server records the pair (grenade ID → player) and sends the full list, `grenadepairings`,
   to every client. Each client replaces its own copy with that list.
3. If a second player pairs a grenade that already has an owner, the most recent pairing wins.
4. The server clears every pairing when it starts up. It drops a player's pairings when it
   removes that player at the reset after a game.

## 3. When a grenade goes off

The **victim's** phone handles the damage:

1. It reads the grenade ID from the shot ID byte, and looks up which player the grenade is
   paired with.
2. It treats that player as the shooter, and applies **that player's** damage setting. In a
   local game (no network), a grenade does the default damage.
3. **No friendly fire protection:** a normal shot from a teammate is ignored, but a grenade
   hurts everyone in range: enemies, teammates, and the player who threw it.
4. The rest is the same as a normal hit:
   - The victim's phone sends `HIT` / `OUT` to the shooter (here, the grenade's owner).
   - If health reaches zero, it sends a reliable `ELIMINATED<owner>`.

**Repeated hits:** the duplicate filter for normal shots doesn't apply to grenades, so
grenade hits in back-to-back packets each do damage.

## 4. Scoring

The server decides who scores (see `NETWORK_PROTOCOL.md` §6.2):

| Who was eliminated | Victim | Grenade owner |
|---|---|---|
| An enemy | Loses a life | Scores a point |
| The owner | Loses a life | Nothing |
| A teammate of the owner | Loses a life | Nothing; the team doesn't score either |
| Anyone, by an unpaired grenade (no known owner) | Loses a life | Nobody scores |
