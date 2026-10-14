# QR Code Pairing in SimpleCoil

A SimpleCoil QR code tells the phone exactly which blaster to connect to, and can also set the
player's name. Players scan it with **Connect Blaster Using QR** on the connect screen. The code
lives in `QrPairingPayload.kt` (the format), `GameViewModel.onQrScanned()` (scanning),
`QrCodeDialogs.kt` and `ui/qr/QrLabel.kt` (generating and saving).

## 1. Why use QR codes

A plain **Connect Blaster** connects to the first advertising device whose name starts with
`SRG1` (`BLE_PROTOCOL.md` §1). That works for one or two players. With a crowd pairing at once,
it often doesn't:

- **Wrong blaster.** Every powered-on blaster nearby matches, so a phone can grab someone
  else's blaster, and that player's phone then can't find it.
- **Trial and error.** Players have to power blasters off and on, or pair one at a time, to
  sort out who has which.
- **Typing names.** Each player also has to open the network menu and type a name.

A QR code fixes all three:

- **The right blaster every time.** The phone scans only for the MAC address in the code
  (`ScanMatcher`), so other blasters nearby don't matter and everyone can pair at once.
- **The name comes with it.** A code with a player name sets the name as it connects.
- **Fast handover.** Tape the code to the blaster. Whoever picks the blaster up scans it and
  is ready, with no setup to explain at a party or event.
- **Reconnect** keeps working as before: the scanned address is saved like any other.

## 2. Format

The QR code holds plain text, in one of two forms.

### 2.1 Blaster only

```
AA:BB:CC:DD:EE:FF
```

The blaster's Bluetooth MAC address: 17 characters, six two-digit hex bytes separated by
colons, **upper case**, exactly as Android reports it. The scan compares the address exactly,
so `aa:bb:…` won't match.

Scanning it connects to that blaster and leaves the player name unchanged.

### 2.2 Blaster and player name

```
AA:BB:CC:DD:EE:FF-Mary-Jane
```

The MAC address, a `-`, then the player name. The 18th character (index 17) being `-` marks a
full-length MAC, so the name may contain dashes of its own. Scanning it connects to that blaster
and sets the player name, as if it had been typed in the network menu.

### 2.3 Parsing rules

`QrPairingPayload.parse()`:

1. Leading and trailing whitespace is trimmed (Java `String.trim()`).
2. Empty text is rejected.
3. If character 17 is `-`, everything before it is the MAC and everything after it is the
   name.
4. Otherwise, the text splits at its first `-`, if it has one. Without a `-`, all of it is the
   MAC.
5. Both parts are trimmed again. An empty MAC is rejected. An empty name means "no name".

Names go through the same checks as typed names: "Player" or an inappropriate name is replaced
with a random one.

### 2.4 Encoding

| Setting | Value |
|---|---|
| Symbology | QR Code |
| Content | Text from §2.1 or §2.2, ISO-8859-1 (names use letters, digits, space and `.!?-_`) |
| Error correction | M (15%) |
| Quiet zone | 2 modules |

Any QR generator can make a valid code: SimpleCoil only reads the text. A code from another
generator needs the upper-case MAC and the format above.

## 3. Generating a code in the app

1. Connect to the blaster once (either connect button).
2. On the game screen, before a game starts, tap **Generate QR** (above the team mode). The
   button is hidden while a game runs and comes back when it ends.
3. Enter a unique player name, or leave it blank for a blaster-only code. The current player
   name is filled in to start with.
4. Tap **Generate**. The code is shown as it will print, with **Save**.
5. **Save** writes a PNG wherever you choose. Then print it and tape it to the side of the
   blaster.

### 3.1 The saved image

- The QR code with the player name below it in regular text (9 pt, black on white). A
  blaster-only code has no text.
- The PNG carries a print density (a `pHYs` chunk, `PngDensity.kt`) that makes the code itself,
  quiet zone excluded, **1 inch (25.4 mm)** wide. Print at 100% scale, not "fit to page".
- A long name makes the label wider than the code instead of shrinking the text.

## 4. Tips

- Use a different name on each blaster's code, so players can tell them apart on the
  scoreboard.
- Clear tape over the label keeps it readable; avoid glossy tape that reflects light into the
  camera.
- A code is tied to one blaster. Make a new one if you replace the blaster.
