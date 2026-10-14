# SimpleCoil Architecture

A short, high-level tour of how the app fits together. The other documents in `docs/` go into detail.

## What the app does

SimpleCoil turns a phone into the brain of a Recoil laser-tag blaster. The phone connects to the blaster over
Bluetooth, keeps score, plays sounds and runs the game rules. Players can play alone with their own phone, or join a
game run by a **dedicated server**: another phone running SimpleCoil in server mode on the same Wi-Fi.

## The big picture

```
  Blaster  <--Bluetooth LE-->  Player's phone  <--Wi-Fi (TCP/UDP)-->  Dedicated server phone
                               (play screen)                          (server screen)
```

- Each player's phone talks to its own blaster.
- Players' phones never talk to each other. Everything goes through the server.
- The server is optional. Without it, the phone runs a game on its own.

## Layers

The code is in one app module, split into packages by job:

| Package | What it holds |
|---|---|
| `ui/` | Screens, dialogs and the scoreboard. Shows things and passes the user's taps on. Makes no game decisions. |
| `domain/` | The game rules: hits, reloads, shot modes, team layouts, time and score limits. Plain Kotlin with no Android code, so it is easy to unit test. |
| `data/` | The shared game state: settings, the player list, the current game state and saved preferences. |
| `hardware/` | The blaster (Bluetooth), sounds and vibration, and GPS location. |
| `network/` | The game server, the game client, server discovery and the wire format they share. |

The top-level package holds the Android entry points: the app class, the two screens and the background services.

## The main pieces

**Screens**

- `FullscreenActivity` is the play screen. `GameViewModel` makes every decision for it; the activity only draws
  what the view model tells it to.
- `DedicatedServerActivity` is the server screen. It shows the server's state and sends the operator's commands
  through `DedicatedServerViewModel`.
- `MapFragment` shows players on a map (WhirlyGlobe-Maply, in `com.mousebird.maply`).

**Background services** (so a game keeps running when the screen is off or the app is in the background)

- `BluetoothLeService` keeps the connection to the blaster.
- `TcpClient` connects the player's phone to a server.
- `UDPListenerService` finds a server on the local network.
- `TcpServer` runs the dedicated server.

**The game session**

- `BlasterSessionEngine` is the heart of the play screen. It takes events from the blaster (trigger pulls, hits,
  reloads), applies the rules from `domain/`, and tells the blaster, the sounds and the network what to do. It lives
  as long as the app, not the screen.
- `GameServer` is the heart of the dedicated server. It handles every player's messages, the operator's commands and
  the game clock in one ordered queue, so the server's state never changes from two places at once.

## How the pieces are connected

There is one object, `AppContainer`, built when the app starts. It creates the shared pieces (repositories, the
Bluetooth stack, the session engine, the network gateway, sounds) and hands each class what it needs through its
constructor. No framework is used; it is plain Kotlin. Screens and services get the container from the app class.

Rules that follow from this:

- Plain classes never look up their dependencies. They receive them.
- Tests build their own fresh set of objects, so they never share state.
- The dedicated server has its own copy of the game state, separate from the play screen's.

Events flow from the background to the screens through small event buses (`EventBus`) and Kotlin flows. Screens
watch them; they don't poll.

## Talking to the outside world

| Link | How | Details |
|---|---|---|
| Phone to blaster | Bluetooth LE (GATT), format fixed by the blaster firmware | `BLE_PROTOCOL.md` |
| Finding a server | UDP broadcast on port 17500 | `NETWORK_PROTOCOL.md` |
| Playing over the network | TCP on port 17510, short text and JSON messages | `NETWORK_PROTOCOL.md` |

Both protocols are frozen: the blaster firmware and phones running older builds depend on them.

## Design goals

- **Small and simple.** One module, no dependency-injection framework, so it builds fast and runs well on old,
  slow phones.
- **Keep playing in the background.** Games, connections and the server live in services and app-wide objects, not
  in screens.
- **Rules in plain code.** Game logic sits outside Android classes, so it is easy to test and reason about.
- **One owner per thing.** Each piece of state or connection is owned in one place, and others go through it.

## Further reading

| Document | Topic |
|---|---|
| `MODERNIZATION_ROADMAP.md` | The order the V2 refactor was done in |
| `REFACTOR_DESIGN.md` | The play screen's split into view model, engine and renderer |
| `STATE_CONSOLIDATION_DESIGN.md` | The shared repositories and their locking |
| `APP_CONTAINER_DESIGN.md` | The app container and constructor injection |
| `NETWORK_STACK_DESIGN.md`, `NETWORK_RECONNECT_DESIGN.md` | The server and client internals |
| `DEDICATED_SERVER_DESIGN.md` | The server's lifecycle and screen |
| `PLATFORM_COMPAT_DESIGN.md` | Android version support |
| `BLE_PROTOCOL.md`, `NETWORK_PROTOCOL.md`, `GRENADES.md` | Wire formats |
