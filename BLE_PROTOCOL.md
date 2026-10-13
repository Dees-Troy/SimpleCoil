# SimpleCoil Bluetooth LE Protocol

How the phone talks to a Recoil blaster over Bluetooth Low Energy. The blaster firmware defines
the format, so none of the UUIDs, byte offsets or delays below can change. The code lives in
`GattAttributes.kt`, `GattProtocolHandler.kt`, `BluetoothLeService.kt`, `BlasterManager.kt` and
`BleConnectionManager.kt`.

## 1. Finding the blaster

- **First connection:** the phone scans and connects to the first device whose name starts
  with `SRG1`.
- **After that:** it connects only to the saved MAC address. A QR code can set that address.
  The code holds `MAC` or `MAC-PlayerName`.
- **Scan time limit:** the scan gives up after 30 s.

## 2. The GATT service

One service, `e6f59d10-8230-4a5c-b22f-c062b1d329e3`, with four characteristics:

| Characteristic | UUID | Use |
|---|---|---|
| ID | `e6f59d11-…` | Read once. Bytes 0–1 hold the firmware version. Byte 10 is the blaster type (`1` = rifle, `2` = pistol; pistol if missing). |
| Telemetry | `e6f59d12-…` | Notifications: the blaster's state, about 10 times a second. |
| Command | `e6f59d13-…` | Writes: set player ID, reload. |
| Config | `e6f59d14-…` | Writes: shot mode, firing cone, recoil. |

Every packet in either direction is 20 bytes. Bytes not listed below are zero.

## 3. Connection sequence

1. The GATT connection is made.
2. After 500 ms, the phone discovers services.
3. Once it finds the service:
   - It turns on telemetry notifications by writing to the telemetry characteristic's CCCD
     descriptor. If the descriptor isn't there yet, it retries once after 300 ms.
   - It reads the ID characteristic.
   - 500 ms later, it writes the recoil setting.
4. When the first telemetry packet arrives:
   - The phone writes the shot mode immediately.
   - At +250 ms it writes the player ID, if one is known.
   - At +500 ms it writes the recoil setting again.
5. A watchdog runs every 5 s. Once telemetry has started, a whole 5 s without a packet counts
   as a disconnect, even if Android never reported one.

All writes use the default (acknowledged) write type. They are **not queued**: the phone sends
each one immediately, and if two overlap, one may silently fail. The delays above space the
writes out so they don't overlap.

## 4. Telemetry (blaster → phone)

| Byte | Meaning |
|---|---|
| 1 | The player ID the blaster is currently using. |
| 2 | Buttons currently held (not used). |
| 3 | Low 4 bits: trigger counter. High 4 bits: reload button counter. |
| 4 | Thumb button counter. |
| 5 | Power button counter. |
| 7 | Battery level (raw). |
| 8 | Hit slot 1: shot ID byte. |
| 9 | Hit slot 1: hit-by byte (who shot us). `0` = no hit. |
| 11 | Hit slot 2: shot ID byte. |
| 12 | Hit slot 2: hit-by byte. |
| 14 | Shots remaining. |
| 15 | Status (not used). |

Packets shorter than 16 bytes are ignored.

### Counters

The trigger and button bytes are counters, not on/off states. A press shows up as a change in
the counter. The phone records the counter values from the first packet of each connection, so
connecting never looks like a press. What each counter does:

- **Trigger:** plays a shot sound when the shot count drops. When the player is out of ammo or
  not in a running game, it plays an "empty" sound. Several empty pulls in a row start a
  reload.
- **Reload button:** starts a reload.
- **Thumb button:** switches to the next allowed shot mode.
- **Power button:** turns recoil on or off, unless the server forces a recoil setting.

### Hits

Each packet has room for two hits (slots 1 and 2).

- **Who fired:** the shooter's player ID is the hit-by byte shifted right by 2 bits.
- **Shot ID:** the shot ID is the low 3 bits of the shot ID byte.
- **Repeats:** the blaster keeps reporting a hit across several packets. The phone ignores a
  shooter/shot ID pair it already counted in either slot of the previous packet. It also
  ignores slot 2 when it repeats slot 1.
- **Grenades:** the hit-by byte is always `167`, and the shot ID byte is laid out differently.
  See `GRENADES.md`.

### Battery

The phone shows a rolling average of the last 100 readings as one of four levels. Rifles and
pistols use different thresholds (`BatteryLevelPolicy.kt`).

## 5. Commands (phone → Command characteristic)

Byte 0 is a command counter. It goes up by `0x10` with each command, wrapping around, and is
shared by all commands.

| Command | Bytes 0–6 | Meaning |
|---|---|---|
| Set player ID | `CID 00 80 00 PID` | Sets the ID the blaster sends in its IR shots. |
| Reload, stage 1 | `CID 00 02 00 PID` | Blocks firing. |
| Reload, stage 2 | `CID 00 04 00 PID 00 SHOTS` | Fills the magazine with `SHOTS` rounds (weapon profile 0). |

**How a reload runs:**
1. The phone writes stage 1.
2. When that write completes, it waits the reload time (1.5 s by default).
3. It writes stage 2.
4. When that write completes, the reload is done.

**When the player is eliminated:** the phone sends only stage 1. The blaster can't fire until
the player respawns, at which point the phone sends stage 2.

## 6. Config (phone → Config characteristic)

**Shot mode and firing cone:**
`00 00 09 S1 S2 C1 C2 FF FF R 02 34`

| Field | Values |
|---|---|
| `S1 S2` (shot mode) | Single: `FE 00`. Burst of 3: `03 03`. Full auto: `FE 01`. |
| `C1 C2` (firing cone) | Outdoor, no cone: `FF 00`. Outdoor with cone: `FF C8`. Indoor, no cone: `19 00`. |
| `R` (recoil strength) | `80`. A rifle in burst mode uses `78`. |

**Recoil on/off:** `10 00 02 X FF`, where `X` is `03` for on and `02` for off.
