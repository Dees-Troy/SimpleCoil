# SimpleCoil Platform, Release Build and UI Hygiene — Design Document

Status: **APPROVED (2026-10-13); steps 1–7 IMPLEMENTED and verified on devices (roadmap Stages 0–1).** All decisions in §10 are resolved. Step 8 is implemented for the server screen and verified on devices (roadmap Stage 4; §3.2 item 5 was left out, see the roadmap). Step 9 is implemented (roadmap Stage 5). Every step in §11 is done.

Revision 2 (2026-10-13): decisions recorded (§10). The dedicated server gets a two-pane landscape layout on large
screens (Q1); the other questions take their recommended defaults.
Branch audited: `V2-refactor` @ `b460c02`. `targetSdk`/`compileSdk` 37, `minSdk` 23, AGP 9.4.1, Kotlin 2.4.21.
Baseline lint (`lintDebug`): 0 errors, 215 warnings, 1 hint.
Date: 2026-10-13
Part of: `MODERNIZATION_ROADMAP.md`.

---

## 0. Summary

This document collects the work driven by the Android platform and the build rather than by app architecture:

| § | Topic | Why now |
|---|---|---|
| 2 | Foreground service types | `targetSdk` ≥ 34 requires a type on every foreground service. `TcpClient` has none. |
| 3 | Large screens and orientation | From API 36, `screenOrientation` and resizability limits are ignored on large screens (sw ≥ 600dp), and `targetSdk` 37 removes the opt-out. The dedicated server's main platform is a Chromebook. |
| 4 | Edge-to-edge | Enforced since API 35. The current layouts pass on the tested phones; the remaining risk is landscape and large screens. |
| 5 | Predictive back | On by default for `targetSdk` ≥ 36. |
| 6 | Backup rules | `allowBackup="true"` with no rules means "back up everything, implicitly". |
| 7 | Theme and widgets | A shadowed library style, framework `Switch`es, and the Material3 question. |
| 8 | Strings, accessibility, lint | 215 warnings. About 40 go away with the dedicated server rewrite, and most of the rest are mechanical. |
| 9 | Release build (R8) | Minification is off. The app has no reflection, so turning it on is cheap. |

Map-related items (`MapFragment`'s deprecated permission callback, its `<fragment>` tag, the map view's
`DrawAllocation`) are left for the upcoming map work.

---

## 1. Ground rules

1. **No visual change unless a section says so.** Layout edits are limited to what a section needs (insets,
   landscape variants, widget swaps), and each one is checked on a device against the current look.
2. **No behavior change to the game, BLE or network.** Nothing here touches the BLE protocol or the wire protocol.
3. **Gate:** `./gradlew test`, `assembleDebug` and `lintDebug` (0 errors) on every commit. §9 adds `assembleRelease`.
4. **Device checks** are listed per section. §11 collects them.

---

## 2. Foreground service types

### 2.1 Current state

| Service | Manifest type | `startForeground` call |
|---|---|---|
| `BluetoothLeService` | `connectedDevice` | — (correct) |
| `TcpClient` | **none** | `startForeground(id, notification)`, wrapped in `catch (Exception)` that logs "Could not start foreground" (`TcpClient.java:222–230`) |
| `TcpServer` | none | not foreground. Fixed by `DEDICATED_SERVER_DESIGN.md` H2. |

From `targetSdk` 34, calling `startForeground` on a service with no declared type throws
`MissingForegroundServiceTypeException`. I expect that exception on Android 14+ devices, swallowed by the `catch`, so
the client's network notification never appears and the service is never foreground. The play session likely
survives anyway because `BluetoothLeService` keeps the process in the foreground while a blaster is connected. That
cover is gone when there's no blaster (a Chromebook client, or a phone whose blaster has dropped).

**Verify first:** on an Android 14+ device, join a server and run `adb logcat -s TCPClient`. If "Could not start
foreground" appears, this section is confirmed.

### 2.2 Change

- Manifest: `android:foregroundServiceType="connectedDevice"` on `TcpClient`. The prerequisite permission
  (`CHANGE_WIFI_MULTICAST_STATE`) is already declared, as is `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- `ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)`.
- Keep the `catch`. Android 12+ still refuses a foreground start from the background (the comment at :227 is right
  about that case).

Device check: the network notification appears while joined, disappears when leaving (the 22688b3 behavior), and
logcat shows no "Could not start foreground".

---

## 3. Large screens and orientation

### 3.1 Current state

- The manifest puts `screenOrientation="portrait"` on both activities, and also on `<application>`, where the
  attribute means nothing. `DedicatedServerActivity` also calls `setRequestedOrientation(PORTRAIT)` (:163). Lint
  reports `DiscouragedApi` ×2, `LockedOrientationActivity` and `SourceLockedOrientationActivity`.
- From API 36, on displays with smallest width ≥ 600dp, the system ignores `screenOrientation`, `resizeableActivity`
  and aspect-ratio limits for apps targeting 36+. API 36 still let an app opt out with a manifest property; **37
  removes the opt-out.** On tablets, unfolded foldables and Chromebooks, both screens will therefore run in landscape
  and in freely resizable windows. **Phones are unaffected:** sw < 600dp keeps the portrait lock.
- No `layout-land` or `layout-sw600dp` resources exist. `activity_fullscreen.xml` has a `MapFragment` with a fixed
  400dp height. `activity_dedicated_server.xml` is a single portrait column.
- `FullscreenActivity` handles `orientation|keyboardHidden|screenSize`, but not `smallestScreenSize|screenLayout`, so a
  resize recreates it. Its state is recreation-safe (ViewModel, DialogFragments, `SavedGameScreen`), so this costs a
  flash, not data. `DedicatedServerActivity` is handled by `DEDICATED_SERVER_DESIGN.md` H1 and §5.

### 3.2 Changes

1. Remove `screenOrientation` from `<application>`, and the redundant `setRequestedOrientation` call (it goes with
   the activity's rewrite anyway).
2. Keep `screenOrientation="portrait"` on both activities. It still applies on phones, which is the play screen's real
   platform.
3. Add `smallestScreenSize|screenLayout` to `FullscreenActivity`'s `configChanges`, so a Chromebook resize doesn't
   recreate it mid-game. The layout reflows without recreation because it has no size-specific resources.
4. **Dedicated server, landscape (sw ≥ 600dp):** `layout-sw600dp-land/activity_dedicated_server.xml` with two panes
   (controls on the left, scoreboard on the right) and the same view ids, so the ViewBinding and the renderer work
   unchanged. See Q1.
5. **Play screen on large screens:** no new layout. Wrap the connect and play layouts' content so nothing is clipped
   in landscape (a `ScrollView` on the connect layout; the map keeps its 400dp). See Q2.

Device check: a Pixel Tablet / Resizable emulator and a Chromebook. Both screens are usable in portrait, landscape and
a free-form window resized to its minimum size. On a phone, both screens stay locked to portrait.

---

## 4. Edge-to-edge

### 4.1 Current state

With `targetSdk` ≥ 35, Android 15+ draws every app edge-to-edge. Both layouts put `fitsSystemWindows="true"` on the
top-level `RelativeLayout`s under the root `FrameLayout` (`activity_fullscreen.xml:17, :94`,
`activity_dedicated_server.xml:17`). On API 30+, insets are dispatched to every sibling, so each one pads itself. The
on-device test pass showed no overlap on phones. API 23–29 devices don't enforce edge-to-edge, so they draw opaque
bars as before.

### 4.2 Remaining risk and changes

- **Landscape on large screens (§3)** brings the display cutout and a side navigation bar into play, which the portrait
  phones never exercised. Check after §3. If `fitsSystemWindows` doesn't cover the cutout there, replace it on those
  roots with one `ViewCompat.setOnApplyWindowInsetsListener` that pads with
  `systemBars() or displayCutout()`.
- **Consistency across API levels (optional, Q3):** call `enableEdgeToEdge()` in both activities, so API 23–34 also
  get transparent bars with the same padding. It is a visual change on older phones (the bars become transparent over
  the #080808 background), so it is opt-in.
- `WindowController.enterGame()` hides only the navigation bars (swipe to show). That is unchanged and works the same
  edge-to-edge.

---

## 5. Predictive back

`targetSdk` 36+ turns on predictive back animations by default.

- `FullscreenActivity` registers an always-enabled `OnBackPressedCallback` that calls `moveTaskToBack(true)`. A
  callback that is always enabled opts out of the system back-to-home preview, so Back looks the same.
- `DedicatedServerActivity` gets a confirm-on-Back callback (`DEDICATED_SERVER_DESIGN.md` Q1).
- DialogFragments use the default dialog back handling.

No change is needed beyond the dedicated server's callback. Device check: Back on both screens and in an open dialog,
on an Android 15+ device with gesture navigation.

---

## 6. Backup

### 6.1 Current state

`android:allowBackup="true"` with no `dataExtractionRules` (API 31+) and no `fullBackupContent`. Auto Backup and
device-to-device transfer therefore copy all app data, which is the `SimpleCoil` preferences: player name, player id,
selected player id, team mode, limits, shot/firing mode, recoil, start time, the dedicated server's settings, and
`DeviceAddress`, the saved blaster MAC.

Restoring `DeviceAddress` to a new phone makes it auto-connect to the old phone's blaster. That is usually what a
person upgrading their phone wants. It is wrong if both phones are still in use with different blasters.

### 6.2 Change

Make the policy explicit (Q4): `res/xml/data_extraction_rules.xml` (API 31+) and `res/xml/backup_rules.xml` (API
23–30), including `sharedpref/SimpleCoil.xml` in both cloud backup and device transfer. Nothing else in the app's
data is worth restoring. The recommended default keeps today's behavior, just declared. The alternative that drops
`DeviceAddress` needs that key moved to its own preferences file (a one-time migration in
`UserPreferencesRepository`).

---

## 7. Theme and widgets

### 7.1 Current state

- `AppTheme` extends `Theme.AppCompat.Light.DarkActionBar`, but every screen is a custom dark design (#080808
  backgrounds, `AppDialogTheme`).
- `styles.xml:45` declares a style named **`Theme.AppCompat.DayNight.Dialog.Alert`**, which shadows the library's
  style of the same name so that `R.style.Theme_AppCompat_DayNight_Dialog_Alert` resolves to `AppDialogTheme`.
  `DedicatedServerActivity` (:468, :596, :688, :768), `GameDialogFragments.kt:57` and `PermissionDialog.kt:40` depend
  on that trick. It is
  fragile: any library update that changes the parent style, or a Material migration, breaks it silently.
- Twelve framework `android.widget.Switch`es (lint `UseSwitchCompatOrMaterialXml`/`Code` ×12 each):
  `activity_dedicated_server.xml` has 3 and `player_settings_dialog.xml` has 9.

### 7.2 Changes (no visual change)

1. Use `R.style.AppDialogTheme` directly at every call site, and delete the shadowing style. That is a pure rename,
   and the dialogs look identical.
2. `Switch` → `androidx.appcompat.widget.SwitchCompat` in XML and code. SwitchCompat is tinted from `colorAccent`
   rather than the framework's default, so check it against the current look on a device. If it differs, set
   `app:thumbTint`/`trackTint` to match.

### 7.3 Material3 (Q5)

Moving to `Theme.Material3.*` changes button shapes, typography, dialog layout and colors on every one of the
roughly 45 views. There are no screenshot tests to catch regressions. Recommendation: not part of this roadmap. If
it happens later, first add screenshot tests for the layouts (Paparazzi renders layouts on the JVM without a device or
Robolectric) and do it as its own visual-refresh project.

---

## 8. Strings, accessibility and lint

### 8.1 Inventory (baseline 215 warnings)

| Rule | Count | Where | Handled by |
|---|---|---|---|
| `SetTextI18n` | 40 | `PlayerSettingsAlertDialog` 21, `PlayerDisplayDataListAdapter` 9, `GameScreenRenderer` 6, `GameDialogFragments` 4 | 30 go with the dedicated server rewrite (both classes are replaced). Fix the other 10 here with string resources and placeholders. |
| `HardcodedText` | 18 | `team_ratio_dialog.xml` 9, `activity_fullscreen.xml` 7, `player_display_team_header.xml` 2 | here (`tools:text` for placeholders, `@string` for real labels) |
| `Autofill` / `LabelFor` | 15 / 11 | numeric fields in the settings, limit, ratio, IP and name dialogs | here: `android:importantForAutofill="no"` on numeric game fields, `autofillHints="username"`-style hints where they fit (player name), and `labelFor` on the label `TextView`s |
| `ContentDescription` | 9 | icon `ImageView`s in `activity_fullscreen.xml` and the scoreboard rows | here: descriptions for icons that carry meaning (connected/disconnected, battery), `@null` for decorative ones |
| `UseSwitchCompatOrMaterial*` | 24 | §7.2 | §7.2 |
| `UnusedResources` | 19 | 14 strings, a dimen, a menu, a style, `ic_launcher_background.xml`, `raw/gameburp_sfx_license.pdf` | here: delete what is truly unused. **Keep the SFX license PDF** with `tools:keep` (§9); it isn't referenced from code but must ship. |
| `UseKtx` | 9 | `UserPreferencesRepository` 8, `GameScreenRenderer` 1 | here (`prefs.edit { }`) |
| `Overdraw` | 8 | dialog layouts that set a background the dialog theme already draws | here, low value: remove the duplicate backgrounds only where the dialog looks the same |
| `DefaultLocale` | 6 | `BlasterSessionEngine` 2, `DedicatedServerActivity` 3, `ProfanityFilter` 1 | here: one `formatClock()` helper with `Locale.US` (the clock must show ASCII digits like the blaster HUD); `ProfanityFilter` uses `Locale.ROOT` for lower-casing. Also remove the `@SuppressLint("DefaultLocale")` at `GameScreenRenderer.kt:143`. |
| `PluralsCandidate` | 5 | `strings.xml` | here: `<plurals>` + `getQuantityString` |
| `RelativeOverlap` | 4 | `activity_dedicated_server.xml` 3, `activity_fullscreen.xml` 1 | device check on a narrow phone (also an open item in `STATE_CONSOLIDATION_DESIGN.md` §8.6). Fix only what visibly overlaps. |
| `InflateParams` | 4 | `PlayerDisplayDataListAdapter` 3, `PlayerSettingsAlertDialog` 1 | dedicated server rewrite |
| `CheckResult` | 4 | tests | here |
| `InlinedApi` / `ObsoleteSdkInt` | 3 / 2 | `BlePermissions` (guarded constants); `TcpClient.bindToWifi` and `GameViewModelFactory.isWifiConnected` check `SDK_INT >= M` with `minSdk` 23 | here: remove the obsolete branches. Annotate the guarded constants. |
| `WifiManagerPotentialLeak` | 2 | `ServerDiscoveryManager` | network Phase 3 step 7 |
| `MonochromeLauncherIcon` | 2 | adaptive icons | here: add a `<monochrome>` layer for Android 13+ themed icons |
| others (1 each) | — | `EmptySuperCall` (`DedicatedServerViewModel`), `UseGetLayoutInflater`, `UnusedAttribute`, `RedundantLabel`, `PrivateResource`, `TrimLambda`; map-owned `FragmentTagUsage`, `DrawAllocation` | here, except the map-owned ones |

### 8.2 Lint policy after the cleanup (Q6)

Turn the categories that were just cleaned into errors, so they don't come back, and baseline whatever is left:

```kotlin
lint {
    baseline = file("lint-baseline.xml")          // whatever remains after §8.1, map-owned items included
    error += setOf("HardcodedText", "SetTextI18n", "DefaultLocale", "UseSwitchCompatOrMaterialXml",
                   "UseSwitchCompatOrMaterialCode")
    checkReleaseBuilds = true
}
```

---

## 9. Release build (R8)

### 9.1 Current state

`release { isMinifyEnabled = false }`. `proguard-rules.pro` is empty. The app code has no reflection: no
`Class.forName`, `getDeclaredField` or `getIdentifier` (the only reflection is in a test). `org.json` is part of the
platform. coroutines, AndroidX and zxing-android-embedded ship consumer rules. ViewBinding classes are referenced
statically.

### 9.2 Change

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
}
```

- `res/raw/keep.xml` with `tools:keep="@raw/gameburp_sfx_license"`, because resource shrinking would otherwise drop
  the unreferenced license PDF.
- Keep `build/outputs/mapping/release/mapping.txt` with each release, so crash stack traces can be de-obfuscated.
- Signing configuration is out of scope (unchanged).

### 9.3 Verification

`./gradlew assembleRelease` succeeds with no R8 warnings that need rules. Install the release APK and run the BLE
smoke test (`REFACTOR_DESIGN.md` §3.0), the QR pairing scan, and the network smoke test (`NETWORK_STACK_DESIGN.md`
§9.1), because R8 touches every code path. Also open the license screen and play every sound effect, which checks
that resource shrinking kept the assets in use.

---

## 10. Decisions (all resolved 2026-10-13)

| # | Question | Options | Recommendation |
|---|---|---|---|
| Q1 | Dedicated server on large screens | Two-pane `layout-sw600dp-land` / scroll the portrait layout / nothing | **Decided: two-pane landscape.** The Chromebook is the server's main platform, and landscape is its natural shape. Same ids, so no code change. |
| Q2 | Play screen on large screens | Make the portrait layout tolerate landscape (scroll) / a dedicated landscape layout | **Accepted default: tolerate.** The play screen lives on phones, which stay portrait. Tablets and Chromebooks as clients are rare, and correct beats pretty there. |
| Q3 | `enableEdgeToEdge()` on API 23–34 too | yes / no | **Accepted default: no** for now. It changes the look on older phones for no functional gain. Revisit with any visual refresh. |
| Q4 | Backup policy | Explicitly back up `SimpleCoil.xml` (today's behavior) / exclude `DeviceAddress` / disable backup | **Accepted default: explicit, same as today.** Restoring the blaster MAC is usually what an upgrading player wants. |
| Q5 | Material3 | migrate now / later, after screenshot tests / never | **Accepted default: later**, as its own visual project with Paparazzi screenshots first. |
| Q6 | Lint policy | Promote the cleaned categories to errors + baseline the rest / warnings only | **Accepted default: promote + baseline.** It keeps the cleanup from eroding at no ongoing cost. |

---

## 11. Plan

| Step | Work | Device check |
|---|---|---|
| 1 | §2 `TcpClient` foreground service type (after the logcat check) | Notification appears and disappears; no "Could not start foreground" |
| 2 | §7.2 step 1: remove the shadowed dialog style | Dialogs look identical |
| 3 | §3.2 items 1–3: manifest cleanup, `configChanges` | Chromebook resize mid-game: no recreation flash |
| 4 | §6 backup rules | `adb shell bmgr backupnow` + restore on a test device keeps the settings |
| 5 | §8 lint cleanup in batches (strings, accessibility, KTX, locale helper, plurals, unused resources, icon), one commit per rule family | Visual spot checks |
| 6 | §7.2 step 2: `SwitchCompat` | Switches look the same (tint) |
| 7 | §9 R8 | Full BLE + QR + network smoke on the release APK |
| 8 | §3.2 items 4–5 and §4.2: large-screen layouts and the insets check | Tablet / Chromebook / foldable matrix |
| 9 | §8.2 lint policy | `lintDebug` clean |

Steps 1–7 are independent of the other documents. Step 5's `PlayerSettingsAlertDialog` and
`PlayerDisplayDataListAdapter` items are skipped if `DEDICATED_SERVER_DESIGN.md` §7 has replaced those classes first.
Step 8 goes after the dedicated server screen is rewritten (its layout ids are fixed by then).

---

## 12. Out of scope

Map-related warnings and APIs; Material3 (Q5); Java 21 bytecode (no benefit on Android, and 17 matches the toolchain);
`minSdk` changes; signing and Play Store configuration; Compose.
