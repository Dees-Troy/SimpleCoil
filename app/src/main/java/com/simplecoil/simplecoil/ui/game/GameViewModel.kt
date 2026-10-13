/*
 * Copyright (C) 2026 Ethan Yonker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.simplecoil.simplecoil.ui.game

import android.bluetooth.BluetoothAdapter
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.simplecoil.simplecoil.GameUpdate
import com.simplecoil.simplecoil.Globals
import com.simplecoil.simplecoil.NameGenerator
import com.simplecoil.simplecoil.NetMsg
import com.simplecoil.simplecoil.NetworkEvent
import com.simplecoil.simplecoil.NetworkSessionGateway
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.ProfanityFilter
import com.simplecoil.simplecoil.TcpClient
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.session.BlasterSessionEngine
import com.simplecoil.simplecoil.domain.session.SessionEffect
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.hardware.audio.SoundEffectPlayer
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionEvent
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionManager
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import com.simplecoil.simplecoil.hardware.blaster.QrPairingPayload
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONException

/** What the activity saves across recreation. */
data class SavedGameScreen(
    val selectedPlayerId: Byte,
    val deviceAddress: String,
    val useNetwork: Boolean,
    val networkMenuType: Int,
)

/**
 * The play screen's logic (Phase 3 step 4): connection handling, game start/end, the network
 * session (server search, join/leave, menus, admin actions, NETMSG_* handling), team and player
 * settings. It owns every call into the session engine, the network gateway, the BLE connection
 * and the saved settings; FullscreenActivity only shows what [screen] is told and forwards input.
 *
 * Moved from FullscreenActivity method by method with the same conditions and the same order of
 * calls. It consumes the blaster, connection, session and network events for its whole lifetime
 * (D4), so the logic keeps running while the screen is in the background.
 */
class GameViewModel(
    private val engine: BlasterSessionEngine,
    private val net: NetworkSessionGateway,
    private val ble: BleConnectionManager,
    blasterEvents: Flow<BlasterEvent>,
    private val prefs: UserPreferencesRepository,
    private val sounds: SoundEffectPlayer,
    private val wifiConnected: () -> Boolean,
    private val broadcastGpsSetting: () -> Unit,
) : ViewModel() {

    /** The screen, while one exists. */
    var screen: GameScreen? = null

    /** Session values for the play screen to render. */
    val session: StateFlow<SessionState> get() = engine.state

    private val g get() = Globals.getInstance()

    var selectedPlayerId: Byte = 0
        private set
    var deviceAddress: String = ""
        private set
    @Volatile var ready = false
        private set
    var networkMenuType = NETWORK_TYPE_ENABLED
        private set
    private var autoScanStarted = false
    private var resumed = false

    /** The blaster disconnected dialog should be showing. */
    var disconnectDialogShowing = false
        private set
    /** The scoreboard should be showing. */
    var scoreboardVisible = false
        private set
    private var latestPlayerData: Array<PlayerDisplayData?>? = null
    private var searchTimeout: Job? = null

    val useNetwork: Boolean get() = net.useNetwork
    val isScanning: Boolean get() = ble.isScanning

    /** For the player settings dialog. */
    val tcpClient: TcpClient? get() = net.tcpClient

    /** The latest scoreboard data, if any arrived yet. */
    val scoreboardPlayers: Array<PlayerDisplayData?>? get() = latestPlayerData

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { blasterEvents.collect { onBlasterEvent(it) } }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { ble.events.collect { onConnectionEvent(it) } }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.effects.collect { onSessionEffect(it) } }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { net.events.collect { onNetworkEvent(it) } }
    }

    // ---------------------------------------------------------------------------------------
    // Screen lifecycle
    // ---------------------------------------------------------------------------------------

    fun onCreate(saved: SavedGameScreen?) {
        if (saved != null) {
            selectedPlayerId = saved.selectedPlayerId
            setDeviceAddress(saved.deviceAddress)
            net.useNetwork = saved.useNetwork
            networkMenuType = saved.networkMenuType
        } else {
            selectedPlayerId = prefs.selectedPlayerId().toByte()
            if (selectedPlayerId.toInt() == 0) selectedPlayerId = prefs.playerId().toByte()
        }
        g.mPlayerID = selectedPlayerId
    }

    /** The rest of onCreate, once the views exist. */
    fun onViewsCreated() {
        showConnectLayout()
        g.mPlayerName = prefs.playerName("Player")
        if (g.mPlayerName.isEmpty() || g.mPlayerName.equals("Player", ignoreCase = true) ||
            ProfanityFilter.containsProfanity(g.mPlayerName)) {
            val randomName = NameGenerator.getRandomName()
            g.mPlayerName = randomName
            prefs.savePlayerName(randomName)
        }
        g.mCurrentFiringMode = prefs.firingMode(Globals.FIRING_MODE_OUTDOOR_NO_CONE)
        selectedPlayerId = prefs.selectedPlayerId().toByte()
        g.mPlayerID = selectedPlayerId
        screen?.showFiringMode(g.mCurrentFiringMode)
        engine.restore(prefs.shotMode(Globals.SHOT_MODE_SINGLE), prefs.recoilEnabled())
        g.mGameMode = prefs.gameMode(Globals.GAME_MODE_2TEAMS)
        var gameLimit = Globals.GAME_LIMIT_NONE
        g.mTimeLimit = prefs.timeLimit()
        if (g.mTimeLimit != 0) gameLimit += Globals.GAME_LIMIT_TIME
        g.mLivesLimit = prefs.livesLimit()
        if (g.mLivesLimit != 0) gameLimit += Globals.GAME_LIMIT_LIVES
        g.mScoreLimit = prefs.scoreLimit()
        if (g.mScoreLimit != 0) gameLimit += Globals.GAME_LIMIT_SCORE
        g.mGameLimit = gameLimit
        setDeviceAddress(prefs.deviceAddress())
        screen?.setReconnectVisible(deviceAddress.isNotEmpty())
        screen?.showPlayerName(g.mPlayerName)
        screen?.showNetworkOptions(false, freeForAll())
        setGameLimit()
        updatePlayerSettings()
    }

    fun saveState() = SavedGameScreen(selectedPlayerId, deviceAddress, net.useNetwork, networkMenuType)

    fun onResume() {
        resumed = true
        if (selectedPlayerId.toInt() == 0) {
            selectedPlayerId = prefs.selectedPlayerId().toByte()
            if (selectedPlayerId.toInt() == 0) selectedPlayerId = prefs.playerId().toByte()
        }
        if (selectedPlayerId.toInt() != 0 && g.mPlayerID.toInt() == 0) g.mPlayerID = selectedPlayerId
        val savedName = prefs.playerName("")
        if (savedName.isNotEmpty()) g.mPlayerName = savedName
        g.mPlayerName?.let { screen?.showPlayerName(it) }
        displayCurrentTeam()
        updatePlayerSettings()

        ble.reconnect(deviceAddress)
        net.bindServices()
        if (net.useNetwork) net.tcpClient?.sendPlayerInfo(true)
    }

    fun onPause() {
        resumed = false
    }

    fun onScreenDestroyed() {
        closeScoreboard()
        ble.reset()
        net.unbindServices()
        screen = null
    }

    private fun setDeviceAddress(address: String?) {
        deviceAddress = address ?: ""
        g.mDeviceAddress = deviceAddress
    }

    // ---------------------------------------------------------------------------------------
    // Connection
    // ---------------------------------------------------------------------------------------

    fun onReconnectPressed() {
        setDeviceAddress(prefs.deviceAddress())
        if (deviceAddress.isNotEmpty()) screen?.requestConnect() else screen?.setReconnectVisible(false)
    }

    fun onConnectPressed() {
        setDeviceAddress("")
        screen?.requestConnect()
    }

    fun onQrScanned(rawQr: String?) {
        val payload = QrPairingPayload.parse(rawQr)
        if (payload == null) {
            Log.e(TAG, "Did not get any good QR result")
            return
        }
        setDeviceAddress(payload.macAddress)
        Log.d(TAG, "Got QR Code MAC: $deviceAddress")
        if (payload.playerName.isNotEmpty()) {
            Log.d(TAG, "Got QR Code Player Name: " + payload.playerName)
            setPlayerName(payload.playerName)
        }
        screen?.requestConnect()
    }

    fun onLocationPermissionsGranted() {
        screen?.requestConnect()
        broadcastGpsSetting()
    }

    /** Permissions and Bluetooth are ready: scan (with the 30 s give-up). False if scanning wasn't allowed. */
    fun startScan(adapter: BluetoothAdapter): Boolean {
        if (ble.startScan(adapter, deviceAddress) == BleConnectionManager.ScanStart.PERMISSION_DENIED) return false
        screen?.setConnectButtonsEnabled(false)
        screen?.showConnectStatus(ConnectStatus.SCANNING)
        return true
    }

    fun onDedicatedServerPressed() {
        net.releaseForDedicatedServer()
        screen?.openDedicatedServer()
    }

    private fun onConnectionEvent(event: BleConnectionEvent) {
        when (event) {
            is BleConnectionEvent.ScanMatched -> {
                screen?.showConnectStatus(ConnectStatus.CONNECTING)
                setDeviceAddress(event.address)
            }
            BleConnectionEvent.ScanTimedOut, BleConnectionEvent.WatchdogTimedOut -> handleDisconnect()
            BleConnectionEvent.InitializeFailed -> screen?.finishScreen()
            // Auto-reconnect when Bluetooth is turned on (only while resumed, as before).
            BleConnectionEvent.AdapterTurnedOn -> if (resumed) screen?.requestConnect()
        }
    }

    private fun onBlasterEvent(event: BlasterEvent) {
        when (event) {
            BlasterEvent.Disconnected -> handleDisconnect()
            BlasterEvent.ServicesDiscovered -> {
                screen?.showConnectStatus(ConnectStatus.COMMUNICATING)
                hideWeaponDisconnect()
            }
            is BlasterEvent.TypeDiscovered -> screen?.showBlasterType(event.blasterType == GattProtocolHandler.BLASTER_TYPE_RIFLE, deviceAddress)
            else -> Unit
        }
    }

    private fun handleDisconnect() {
        Log.d(TAG, "handleDisconnect called. hasConnectedToBlaster=" + engine.hasConnectedToBlaster + ", mGameState=" + g.mGameState)
        ble.cancelScanTimeout()
        ble.clearScanning()
        if (engine.hasConnectedToBlaster || g.mGameState != Globals.GAME_STATE_NONE) {
            Log.e(TAG, "blaster disconnected, attempt to reconnect")
            screen?.requestConnect()
            showWeaponDisconnect()
            return
        }
        ble.reset()
        showConnectLayout()
        screen?.setConnectButtonsEnabled(true)
        screen?.showConnectStatus(ConnectStatus.NOT_CONNECTED)
        engine.onBlasterReset()
        endGame()
        sounds.play(SessionSound.ELIMINATED)
    }

    private fun showConnectLayout() {
        screen?.showConnectScreen(deviceAddress.isNotEmpty())
    }

    private fun showWeaponDisconnect() {
        if (disconnectDialogShowing) return
        disconnectDialogShowing = true
        screen?.showDisconnectDialog()
    }

    private fun hideWeaponDisconnect() {
        if (disconnectDialogShowing) {
            disconnectDialogShowing = false
            screen?.dismissDisconnectDialog()
            engine.onDisconnectDialogDismissed()
        }
    }

    /** "Disconnect & Quit" on the blaster disconnected dialog. */
    fun onDisconnectQuit() {
        Log.d(TAG, "Disconnect & Quit clicked")
        engine.hasConnectedToBlaster = false
        g.mGameState = Globals.GAME_STATE_NONE
        ble.cancelScanTimeout()
        ble.stopScan()
        stopServerSearch(true)
        endGame()
        handleDisconnect()
        disconnectDialogShowing = false
    }

    // ---------------------------------------------------------------------------------------
    // Session effects
    // ---------------------------------------------------------------------------------------

    private fun onSessionEffect(effect: SessionEffect) {
        when (effect) {
            is SessionEffect.Toast -> screen?.showSessionToast(effect.toast)
            SessionEffect.CommunicationStarted -> {
                screen?.showPlayScreen()
                autoStartServerScan()
            }
            SessionEffect.TeamSent -> saveTeamAndDisplay()
            SessionEffect.PlayerIdReported -> displayCurrentTeam()
            SessionEffect.EndGameRequested -> endGame()
            // Sounds and vibration are played app-side by SessionFeedback.
            is SessionEffect.Sound, SessionEffect.Vibrate -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------
    // Team and player
    // ---------------------------------------------------------------------------------------

    fun onTeamMinusPressed() {
        if (g.mGameState != Globals.GAME_STATE_NONE) return
        var playerId = g.mPlayerID.toInt()
        playerId = if (net.useNetwork) {
            if (playerId > 0) playerId - 1 else Globals.MAX_PLAYER_ID.toInt()
        } else {
            if (playerId > 1) playerId - 1 else Globals.MAX_PLAYER_ID.toInt()
        }
        g.mPlayerID = playerId.toByte()
        selectedPlayerId = playerId.toByte()
        setTeam()
    }

    fun onTeamPlusPressed() {
        if (g.mGameState != Globals.GAME_STATE_NONE) return
        var playerId = g.mPlayerID.toInt()
        playerId = if (net.useNetwork) {
            if (playerId < Globals.MAX_PLAYER_ID) playerId + 1 else 0x00
        } else {
            if (playerId < Globals.MAX_PLAYER_ID) playerId + 1 else 0x01
        }
        g.mPlayerID = playerId.toByte()
        selectedPlayerId = playerId.toByte()
        setTeam()
    }

    /* Sending to Command a packet with 10 00 80 00 PLAYER# then 15 more 00's sets the player ID.
       This works for initially setting the ID, but does not work consistently afterwards. You can
       consistently change the player ID by doing a reload procedure, but doing a reload without
       having previously set a team does not seem to work. We do a reload cycle before starting the
       game each time to ensure that the player starts on the right team and with full ammo. */
    private fun setTeam() {
        if (!TEST_NETWORK) engine.sendTeamToBlaster()
        saveTeamAndDisplay()
    }

    /** Persists the player id / game mode and redisplays the team (the rest of setTeam()). */
    private fun saveTeamAndDisplay() {
        prefs.savePlayerIdAndGameMode(g.mPlayerID.toInt(), g.mGameMode)
        displayCurrentTeam()
    }

    private fun displayCurrentTeam() {
        if ((net.useNetwork || selectedPlayerId.toInt() == 0) && g.mPlayerID.toInt() == 0) {
            screen?.showTeam(TeamDisplay.AutoSort)
            return
        }
        if (!net.useNetwork && g.mPlayerID.toInt() == 0) {
            g.mPlayerID = if (selectedPlayerId.toInt() != 0) selectedPlayerId else 1
        }
        if (g.mGameMode != Globals.GAME_MODE_FFA) {
            val networkTeam = g.calcNetworkTeam(g.mPlayerID)
            engine.networkTeam = networkTeam
            val startID = g.getTeamStartID(networkTeam)
            val player = g.mPlayerID - startID + 1
            val dedicatedName = if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINED) {
                g.mPlayerName?.takeIf { it.isNotEmpty() }?.let { "$it ($networkTeam-$player)" }
            } else null
            screen?.showTeam(TeamDisplay.Team(networkTeam, player, Globals.getTeamColor(networkTeam), net.useNetwork, dedicatedName))
        } else {
            screen?.showTeam(TeamDisplay.FreeForAll(g.mPlayerID))
        }
    }

    fun onPlayerNameEntered(name: String) = setPlayerName(name)

    private fun setPlayerName(name: String?) {
        if (name == null || name.trim().isEmpty()) return
        var trimmed = name.trim()
        if (trimmed.equals("Player", ignoreCase = true) || ProfanityFilter.containsProfanity(trimmed)) {
            if (ProfanityFilter.containsProfanity(trimmed)) screen?.showMessage(GameMessage.INAPPROPRIATE_NAME)
            trimmed = NameGenerator.getRandomName()
        }
        g.mPlayerName = trimmed
        prefs.savePlayerName(trimmed)
        screen?.showPlayerName(trimmed)
        displayCurrentTeam()
        val client = net.tcpClient
        if (ready && client != null) {
            client.sendPlayerNameChange()
        } else {
            screen?.showNetworkOptions(true, freeForAll())
            setNetworkMenu(NETWORK_TYPE_ENABLED)
        }
    }

    fun onPlayerSettingsPressed() {
        if (!g.mAllowPlayerSettings) {
            screen?.showMessage(GameMessage.PLAYER_SETTINGS_NOT_ALLOWED)
            return
        }
        screen?.showLocalPlayerSettings()
    }

    fun onFiringModeSelected(firingMode: Int) {
        g.mCurrentFiringMode = firingMode
        screen?.showFiringMode(firingMode)
        engine.setShotMode(engine.shotMode)
        prefs.saveFiringMode(g.mCurrentFiringMode)
    }

    fun onGameModeSelected(gameMode: Int) {
        g.mGameMode = gameMode
        screen?.showGameMode(gameMode)
        setTeam()
    }

    private fun updatePlayerSettings() {
        screen?.showPlayerSettings(g.mFullHealth, g.mFullReload, g.mDamage * -1)
        setGameLimit()
        updateAdminButtonVisibility()
        engine.applyPlayerSettings()
        screen?.showFiringMode(g.mCurrentFiringMode)
    }

    // ---------------------------------------------------------------------------------------
    // Game limits
    // ---------------------------------------------------------------------------------------

    fun onGameLimitPressed() {
        screen?.showGameLimitDialog(net.useNetwork)
    }

    enum class LimitKind { TIME, LIVES, SCORE }

    fun onGameLimitEntered(text: String, kind: LimitKind) {
        if (text.isEmpty()) return
        val limit = try {
            Integer.parseInt(text)
        } catch (ignored: Exception) {
            return
        }
        if (limit > 100) {
            screen?.showMessage(GameMessage.LIMIT_TOO_HIGH)
            return
        }
        var gameLimit = g.mGameLimit
        when (kind) {
            LimitKind.TIME -> {
                if ((gameLimit and Globals.GAME_LIMIT_TIME) == 0) gameLimit += Globals.GAME_LIMIT_TIME
                else if (limit <= 0) gameLimit -= Globals.GAME_LIMIT_TIME
                g.mTimeLimit = limit
            }
            LimitKind.LIVES -> {
                if ((gameLimit and Globals.GAME_LIMIT_LIVES) == 0) gameLimit += Globals.GAME_LIMIT_LIVES
                else if (limit <= 0) gameLimit -= Globals.GAME_LIMIT_LIVES
                g.mLivesLimit = limit
            }
            LimitKind.SCORE -> {
                if ((gameLimit and Globals.GAME_LIMIT_SCORE) == 0) gameLimit += Globals.GAME_LIMIT_SCORE
                else if (limit <= 0) gameLimit -= Globals.GAME_LIMIT_SCORE
                g.mScoreLimit = limit
            }
        }
        g.mGameLimit = gameLimit
        setGameLimit()
    }

    private fun setGameLimit() {
        val timeLimit = if ((g.mGameLimit and Globals.GAME_LIMIT_TIME) != 0) g.mTimeLimit else null
        // Lives or eliminations, including a server override of the lives (the count itself is
        // part of the session state).
        engine.applyGameLimit()
        val scoreLimit = if ((g.mGameLimit and Globals.GAME_LIMIT_SCORE) != 0) g.mScoreLimit else null
        screen?.showGameLimit(timeLimit, engine.hasLivesLimitSet, scoreLimit)
        prefs.saveLimits(g.mTimeLimit, g.mScoreLimit, g.mLivesLimit)
    }

    // ---------------------------------------------------------------------------------------
    // Game lifecycle
    // ---------------------------------------------------------------------------------------

    fun onStartGamePressed() {
        if (g.mPlayerID.toInt() == 0) {
            g.mPlayerID = 1
            setTeam()
        }
        if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINED) {
            if (Globals.getPlayerCount() <= 1) {
                screen?.showMessage(GameMessage.NOT_ENOUGH_PLAYERS)
                screen?.showOnlyOnePlayer()
                return
            }
            net.tcpClient!!.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_STARTGAME))
        } else {
            if (net.useNetwork) stopServerSearch(true)
            startGame()
        }
    }

    private fun startGame() {
        closeScoreboard()
        net.score = 0
        net.teamScore = 0
        screen?.showGameStarted(net.useNetwork, g.mIsAdmin, freeForAll())
        if (net.useNetwork) net.udp?.startGame()
        // Lives/eliminations, health, shot mode, first spawn
        engine.startGame()
    }

    fun onEndGamePressed() {
        screen?.confirmEndGame()
    }

    fun onEndGameConfirmed() {
        if (net.useNetwork) {
            if (g.mIsAdmin) {
                net.tcpClient?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_ENDGAME))
            } else {
                net.tcpClient!!.leaveServer()
                endGame()
            }
        } else {
            endGame()
        }
    }

    private fun endGame() {
        val wasInNetworkGame = net.useNetwork && g.mGameState != Globals.GAME_STATE_NONE
        // Game state NONE, timers, spawn/shield/clock display, reload lock
        engine.endGame()
        hideWeaponDisconnect()
        screen?.showGameEnded(net.useNetwork, freeForAll())
        updatePreGameButtonVisibilities()
        if (ready && net.useNetwork) {
            setNetworkMenu(NETWORK_TYPE_JOINED)
            screen?.setPlayerDataButtonVisible(true)
        } else {
            setNetworkMenu(NETWORK_TYPE_ENABLED)
        }
        if (wasInNetworkGame) openScoreboard()
    }

    private fun updatePreGameButtonVisibilities() {
        if (g.mGameState != Globals.GAME_STATE_NONE) return
        val connectedClient = net.useNetwork && networkMenuType == NETWORK_TYPE_JOINED
        val searching = net.useNetwork && networkMenuType == NETWORK_TYPE_JOINING
        screen?.showPreGameButtons(PreGameButtons(
            ready = ready,
            startVisible = !(g.mOnlyServerSettings || connectedClient),
            startEnabled = !searching,
            serverSettingsOnly = g.mOnlyServerSettings,
        ))
        updateAdminButtonVisibility()
    }

    private fun updateAdminButtonVisibility() {
        val showAdmin = g.mIsAdmin && net.useNetwork && ready
        screen?.showAdminButton(showAdmin)
        Log.d(TAG, "updateAdminButtonVisibility: mIsAdmin=" + g.mIsAdmin + ", mUseNetwork=" + net.useNetwork + ", mReady=" + ready + " -> show=" + showAdmin)
    }

    // ---------------------------------------------------------------------------------------
    // Network session
    // ---------------------------------------------------------------------------------------

    fun onNetworkButtonPressed() {
        if (!wifiConnected()) {
            screen?.showMessage(GameMessage.NO_WIFI)
            net.useNetwork = false
            return
        }
        screen?.prepareNetworkMenu()
        if (!net.useNetwork) {
            net.useNetwork = true
            screen?.showNetworkOptions(true, freeForAll())
            g.mPlayerID = selectedPlayerId
            setNetworkMenu(NETWORK_TYPE_JOINING)
            net.udp?.joinServer()
        } else {
            setNetworkMenu(networkMenuType)
        }
        screen?.showNetworkMenu()
        setTeam()
    }

    /** What the network popup lists for the current menu state. */
    fun networkMenuEntries(): List<NetworkMenuEntry> = when (networkMenuType) {
        NETWORK_TYPE_ENABLED -> buildList {
            add(NetworkMenuEntry.Join)
            add(NetworkMenuEntry.JoinIp)
            add(NetworkMenuEntry.PlayerName(g.mPlayerName))
            if (g.mGameState == Globals.GAME_STATE_NONE) add(NetworkMenuEntry.GameMode)
            add(NetworkMenuEntry.DisableNetwork)
        }
        NETWORK_TYPE_JOINING -> listOf(NetworkMenuEntry.JoinIp, NetworkMenuEntry.DisableNetwork)
        NETWORK_TYPE_JOINED -> buildList {
            add(NetworkMenuEntry.PlayerName(g.mPlayerName))
            if (GameSettingsRepository.getInstance().isAllowChooseTeams && g.mGameState == Globals.GAME_STATE_NONE) {
                val gameMode = g.mGameMode
                if (gameMode == Globals.GAME_MODE_2TEAMS || gameMode == Globals.GAME_MODE_4TEAMS) {
                    val currentTeam = g.calcNetworkTeam(g.mPlayerID)
                    val numTeams = if (gameMode == Globals.GAME_MODE_4TEAMS) 4 else 2
                    for (t in 1..numTeams) {
                        if (t != currentTeam) add(NetworkMenuEntry.SwitchTeam(t, Globals.getTeamColor(t)))
                    }
                }
            }
            add(NetworkMenuEntry.DisableNetwork)
        }
        else -> emptyList()
    }

    fun onNetworkMenuEntry(entry: NetworkMenuEntry) {
        when (entry) {
            NetworkMenuEntry.DisableNetwork -> stopServerSearch(true)
            NetworkMenuEntry.Join -> {
                if (networkMenuType == NETWORK_TYPE_JOINED) return
                ready = true
                setReady()
                setNetworkMenu(NETWORK_TYPE_JOINING)
                startServerSearchTimeout()
                net.udp!!.joinServer()
            }
            NetworkMenuEntry.JoinIp -> {
                if (networkMenuType == NETWORK_TYPE_JOINED) return
                screen?.showServerIpDialog(serverIpSuggestion())
            }
            is NetworkMenuEntry.PlayerName -> screen?.showPlayerNameDialog(g.mPlayerName)
            is NetworkMenuEntry.SwitchTeam -> requestSwitchTeam(entry.team)
            NetworkMenuEntry.GameMode -> screen?.showGameModeMenu()
        }
    }

    private fun serverIpSuggestion(): String {
        val ip = g.mServerIP?.toString() ?: Globals.getIPAddressStr()
        return if (ip.startsWith("/")) ip.substring(1) else ip
    }

    fun onServerIpEntered(ip: String) {
        ready = true
        setReady()
        net.udp!!.joinServer(ip)
        setNetworkMenu(NETWORK_TYPE_JOINING)
    }

    private fun requestSwitchTeam(targetTeam: Int) {
        val client = net.tcpClient ?: return
        if (g.mGameState != Globals.GAME_STATE_NONE) {
            Log.w(TAG, "Cannot request team change while game is running")
            return
        }
        try {
            client.sendTCPMessage(NetworkSessionGateway.teamChangeRequest(g.mPlayerID, targetTeam))
        } catch (e: JSONException) {
            Log.e(TAG, "JSON Exception: " + e.message)
        }
    }

    private fun setNetworkMenu(type: Int) {
        networkMenuType = type
        when (type) {
            NETWORK_TYPE_ENABLED -> screen?.showNetworkButton(NetworkButton.MENU)
            NETWORK_TYPE_JOINING -> screen?.showNetworkButton(NetworkButton.SEARCHING)
            NETWORK_TYPE_JOINED -> screen?.showNetworkButton(NetworkButton.CONNECTED)
        }
        screen?.rebuildNetworkMenu()
        updatePreGameButtonVisibilities()
    }

    private fun setReady(sendPlayerLeft: Boolean = true) {
        if (ready) {
            if (net.useNetwork) screen?.showServerSearching()
            screen?.setKeepScreenOn(true)
        } else {
            g.mOnlyServerSettings = false
            g.mUseGPS = false
            if (sendPlayerLeft) net.tcpClient?.leaveServer()
            net.udp?.stopListen()
            net.tcpClient?.stopTcpClient()
            screen?.showNotReady()
            setNetworkMenu(NETWORK_TYPE_ENABLED)
            screen?.setPlayerDataButtonVisible(false)
        }
        updatePreGameButtonVisibilities()
        broadcastGpsSetting()
    }

    private fun startServerSearchTimeout() {
        cancelServerSearchTimeout()
        searchTimeout = viewModelScope.launch {
            delay(SEARCH_TIMEOUT_MILLIS)
            if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINING) {
                Log.d(TAG, "Server search cycle ended; re-triggering scan...")
                net.udp?.joinServer()
                startServerSearchTimeout()
            }
        }
    }

    private fun cancelServerSearchTimeout() {
        searchTimeout?.cancel()
        searchTimeout = null
    }

    private fun stopServerSearch(disableNetwork: Boolean) {
        cancelServerSearchTimeout()
        net.udp?.endScanning()
        ready = false
        setReady(disableNetwork)
        if (disableNetwork) {
            autoScanStarted = false
            screen?.showNetworkOptions(false, freeForAll())
            net.useNetwork = false
            screen?.showNetworkButton(NetworkButton.USE_NETWORK)
            if (g.mPlayerID.toInt() == 0) {
                g.mPlayerID = if (selectedPlayerId.toInt() != 0) selectedPlayerId else 1
            }
            setTeam()
        } else {
            setNetworkMenu(NETWORK_TYPE_ENABLED)
            screen?.showNetworkOptions(true, freeForAll())
        }
    }

    private fun autoStartServerScan() {
        val udp = net.udp
        if (!autoScanStarted && udp != null) {
            if (!wifiConnected()) {
                Log.d(TAG, "autoStartServerScan: Wi-Fi not connected, skipping auto-scan")
                return
            }
            autoScanStarted = true
            Log.d(TAG, "autoStartServerScan: starting automatic server discovery on launch")
            net.useNetwork = true
            g.mPlayerID = selectedPlayerId
            screen?.showNetworkOptions(true, freeForAll())
            setNetworkMenu(NETWORK_TYPE_JOINING)
            setTeam()
            startServerSearchTimeout()
            udp.joinServer()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Admin
    // ---------------------------------------------------------------------------------------

    fun adminActions(): List<AdminAction> {
        Log.d(TAG, "showAdminControlsMenu: current mGameState=" + g.mGameState)
        return if (g.mGameState == Globals.GAME_STATE_NONE) {
            listOf(AdminAction.START_GAME, AdminAction.TWO_TEAMS, AdminAction.FOUR_TEAMS, AdminAction.FREE_FOR_ALL, AdminAction.SHUFFLE_TEAMS)
        } else {
            listOf(AdminAction.END_GAME)
        }
    }

    fun onAdminAction(action: AdminAction) {
        val client = net.tcpClient
        when (action) {
            AdminAction.START_GAME -> {
                Log.d(TAG, "Server Controls: Start Game clicked. mTcpClient=" + (client != null))
                client?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_STARTGAME))
                client?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_STARTGAME))
            }
            AdminAction.END_GAME -> {
                Log.d(TAG, "Server Controls: End Game clicked. mTcpClient=" + (client != null))
                client?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_ENDGAME))
            }
            AdminAction.TWO_TEAMS -> changeAdminGameMode(Globals.GAME_MODE_2TEAMS)
            AdminAction.FOUR_TEAMS -> changeAdminGameMode(Globals.GAME_MODE_4TEAMS)
            AdminAction.FREE_FOR_ALL -> changeAdminGameMode(Globals.GAME_MODE_FFA)
            AdminAction.SHUFFLE_TEAMS -> client?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_SORTTEAMS))
        }
    }

    private fun changeAdminGameMode(mode: Int) {
        Log.d(TAG, "changeAdminGameMode: mode=" + mode + ", mTcpClient=" + (net.tcpClient != null))
        net.tcpClient?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_GAMEMODE + mode))
    }

    fun onKickPlayer(playerId: Byte) {
        val client = net.tcpClient ?: return
        client.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_KICK + playerId))
        screen?.showMessage(GameMessage.KICK_SENT)
    }

    fun onTeamRatiosRequested() {
        if (g.mGameState != Globals.GAME_STATE_NONE) {
            screen?.showMessage(GameMessage.RATIOS_DURING_GAME)
            return
        }
        if (!g.mIsAdmin) {
            screen?.showMessage(GameMessage.RATIOS_ADMIN_ONLY)
            return
        }
        val gameMode = g.mGameMode
        if (gameMode == Globals.GAME_MODE_FFA) {
            screen?.showMessage(GameMessage.RATIOS_NOT_IN_FFA)
            return
        }
        screen?.showTeamRatioDialog(if (gameMode == Globals.GAME_MODE_4TEAMS) 4 else 2, g.teamSizes)
    }

    /** Team sizes entered in the ratio dialog. Returns true when accepted (close the dialog). */
    fun onTeamSizesEntered(texts: List<String>): Boolean {
        val newSizes = IntArray(texts.size)
        var total = 0
        for ((i, raw) in texts.withIndex()) {
            val text = raw.trim()
            val value = text.toIntOrNull()
            if (text.isEmpty() || value == null || value <= 0) {
                screen?.showMessage(GameMessage.RATIOS_MIN_ONE)
                return false
            }
            newSizes[i] = value
            total += value
        }
        if (total != Globals.MAX_PLAYER_ID.toInt()) {
            screen?.showMessage(GameMessage.RATIOS_TOTAL, total)
            return false
        }
        g.setTeamSizes(newSizes)
        net.tcpClient?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_ADMIN_TEAMSIZES + newSizes.joinToString(",")))
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Scoreboard
    // ---------------------------------------------------------------------------------------

    fun openScoreboard() {
        scoreboardVisible = true
        latestPlayerData?.let { screen?.showScoreboard(it) }
        if (net.useNetwork) net.tcpClient?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_PLAYERDATAREQUEST))
    }

    private fun closeScoreboard() {
        scoreboardVisible = false
        screen?.closeScoreboard()
    }

    fun onScoreboardDismissed() {
        scoreboardVisible = false
    }

    private fun displayPlayerData(json: String?) {
        net.updatePlayerData(json)
    }

    private fun updateScoreboardData(players: Array<PlayerDisplayData?>) {
        latestPlayerData = players
        if (!scoreboardVisible) return
        screen?.showScoreboard(players)
    }

    // ---------------------------------------------------------------------------------------
    // NETMSG_* (D4: handled for the screen's whole lifetime)
    // ---------------------------------------------------------------------------------------

    private fun onNetworkEvent(event: NetworkEvent) {
        when (event) {
            NetworkEvent.UdpServiceConnected -> if (engine.isCommunicating) autoStartServerScan()
            NetworkEvent.UdpServiceDisconnected -> {
                ready = false
                setReady(false)
                if (g.mGameState != Globals.GAME_STATE_NONE) endGame()
            }
            is NetworkEvent.PlayerData -> {
                // Scoreboard parsed off the main thread (scores already updated)
                screen?.showScore(net.score)
                screen?.showTeamScore(net.teamScore)
                updateScoreboardData(event.players)
            }
            NetworkEvent.ShotFired -> {
                engine.onRemoteShotFired() // Keeps us from spamming shots fired messages
                screen?.showShotsFired()
            }
            is NetworkEvent.Hit -> screen?.showHitOtherPlayer(false, playerName(event.playerId))
            is NetworkEvent.Out -> screen?.showHitOtherPlayer(true, playerName(event.playerId))
            is NetworkEvent.Eliminated -> onEliminatedSomeone(event.playerId)
            NetworkEvent.TeamEliminated -> {
                // Increase team score in team games
                net.teamScore = net.teamScore + 1
                screen?.showTeamScore(net.teamScore)
                if (!net.useNetwork && (g.mGameLimit and Globals.GAME_LIMIT_SCORE) != 0 && net.teamScore >= g.mScoreLimit) endGame()
            }
            NetworkEvent.Join, NetworkEvent.Leave -> {
                screen?.showPlayerCount(Globals.getPlayerCount())
                // Everyone else is out so game is over - this only works in FFA because we don't
                // keep track of who and how many people are on each team
                if (g.mGameMode == Globals.GAME_MODE_FFA && g.mGameState != Globals.GAME_STATE_NONE &&
                    event == NetworkEvent.Leave && Globals.getPlayerCount() <= 1) endGame()
            }
            is NetworkEvent.ListPlayers -> onListPlayers(event)
            is NetworkEvent.PlayerDataUpdate -> displayPlayerData(event.json)
            NetworkEvent.StartGame -> {
                closeScoreboard()
                if (g.mGameState == Globals.GAME_STATE_NONE) startGame()
            }
            NetworkEvent.EndGame -> if (g.mGameState != Globals.GAME_STATE_NONE) endGame()
            is NetworkEvent.Error -> {
                val message = event.message
                if (!message.isNullOrEmpty()) screen?.showText(message) else Log.d(TAG, "No error message given")
            }
            NetworkEvent.VersionError -> {
                screen?.showMessage(GameMessage.UDP_VERSION_ERROR)
                ready = false
                setReady()
            }
            NetworkEvent.SameTeam -> {
                screen?.showMessage(GameMessage.SAME_ID_ERROR)
                ready = false
                setReady()
            }
            NetworkEvent.FailedToJoin -> {
                Log.d(TAG, "NETMSG_FAILEDTOJOIN received. mUseNetwork=" + net.useNetwork + ", mNetworkMenuType=" + networkMenuType)
                if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINING) {
                    viewModelScope.launch {
                        delay(FAILED_JOIN_RETRY_MILLIS)
                        val udp = net.udp
                        if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINING && udp != null) {
                            Log.d(TAG, "Auto-retrying server scan...")
                            udp.joinServer()
                        }
                    }
                }
            }
            NetworkEvent.ServerCancel -> {
                cancelServerSearchTimeout()
                if (g.mGameState != Globals.GAME_STATE_NONE) {
                    endGame()
                } else {
                    ready = false
                    setReady(false)
                }
                net.useNetwork = true
                setNetworkMenu(NETWORK_TYPE_ENABLED)
                screen?.showNetworkOptions(true, freeForAll())
                g.mPlayerID = selectedPlayerId
                setTeam()
                screen?.showMessage(GameMessage.KICKED_BY_SERVER)
            }
            is NetworkEvent.ServerReply -> {
                cancelServerSearchTimeout()
                val assigned = event.assignedPlayerId
                if (assigned != null && assigned.toInt() != 0) {
                    g.mPlayerID = assigned
                    setTeam()
                }
                ready = true
                setReady()
                net.startTcpClient()
                setNetworkMenu(NETWORK_TYPE_JOINED)
            }
            NetworkEvent.NetworkConnected -> {
                cancelServerSearchTimeout()
                screen?.showNetworkConnected(true)
                if (ready && net.useNetwork) setNetworkMenu(NETWORK_TYPE_JOINED)
            }
            NetworkEvent.NetworkDisconnected -> screen?.showNetworkConnected(false)
            NetworkEvent.PlayerSettingsUpdate -> updatePlayerSettings()
        }
    }

    private fun onEliminatedSomeone(playerId: Byte) {
        // Increase score
        net.score = net.score + 1
        screen?.showScore(net.score)
        screen?.showScoreIncrease(playerName(playerId))
        sounds.play(SessionSound.SCORE)
        if (g.mGameMode != Globals.GAME_MODE_FFA) {
            net.teamScore = net.teamScore + 1
            screen?.showTeamScore(net.teamScore)
            net.tcpClient?.sendTCPMessage(NetworkSessionGateway.message(NetMsg.NETMSG_TEAMELIMINATED))
        }
        if (!net.useNetwork && (g.mGameLimit and Globals.GAME_LIMIT_SCORE) != 0) {
            val currentScore = if (g.mGameMode != Globals.GAME_MODE_FFA) net.teamScore else net.score
            if (currentScore >= g.mScoreLimit) endGame()
        }
    }

    private fun onListPlayers(event: NetworkEvent.ListPlayers) {
        if (!ready) return
        net.udp!!.endScanning()
        screen?.showPlayerCount(Globals.getPlayerCount())
        screen?.showGameMode(g.mGameMode)
        setTeam()
        if (ready && net.useNetwork && g.mGameState == Globals.GAME_STATE_NONE) {
            ready = true
            setReady()
            setGameLimit()
            setNetworkMenu(NETWORK_TYPE_JOINED)
            screen?.hideStartButton()
            var ip = g.mServerIP?.toString() ?: ""
            if (ip.startsWith("/")) ip = ip.substring(1)
            screen?.showConnectedToServer(ip)
        }
        event.gameUpdate?.let { applyGameUpdate(it) }
        event.gameState?.let { serverGameState ->
            if (serverGameState != Globals.GAME_STATE_ELIMINATED) {
                if (serverGameState == Globals.GAME_STATE_NONE && g.mGameState != serverGameState) {
                    endGame()
                } else if (serverGameState == Globals.GAME_STATE_RUNNING) {
                    closeScoreboard()
                    if (g.mGameState == Globals.GAME_STATE_NONE) startGame()
                }
            }
        }
        updatePreGameButtonVisibilities()
    }

    private fun applyGameUpdate(update: GameUpdate) {
        net.score = update.score
        screen?.showScore(net.score)
        if (g.mGameMode != Globals.GAME_MODE_FFA) {
            net.teamScore = update.teamScore
            screen?.showTeamScore(net.teamScore)
        }
        // Eliminations (or lives left) and the game countdown follow the server
        engine.syncFromServer(update.eliminations, update.timeRemaining)
    }

    private fun playerName(playerId: Byte): String = if (playerId.toInt() == 0) "" else g.getPlayerName(playerId) ?: ""

    private fun freeForAll() = g.mGameMode == Globals.GAME_MODE_FFA

    companion object {
        private const val TAG = "scmain"

        // For testing and debugging network only -- dumps you straight to the play game layout and allows you to switch teams without connecting a blaster
        const val TEST_NETWORK = false

        const val NETWORK_TYPE_ENABLED = 1
        const val NETWORK_TYPE_JOINING = 2
        const val NETWORK_TYPE_JOINED = 3

        /** Server search: re-scan after this long without a server. */
        const val SEARCH_TIMEOUT_MILLIS = 10_000L

        /** Failed to join: retry the scan after this long. */
        const val FAILED_JOIN_RETRY_MILLIS = 2_000L
    }
}
