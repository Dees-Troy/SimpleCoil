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
import com.simplecoil.simplecoil.NameGenerator
import com.simplecoil.simplecoil.NetworkSessionGateway
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.ProfanityFilter
import com.simplecoil.simplecoil.TcpClient
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.player.PlayerNames
import com.simplecoil.simplecoil.domain.rules.GameLimitRules
import com.simplecoil.simplecoil.domain.rules.LimitKind
import com.simplecoil.simplecoil.domain.rules.Limits
import com.simplecoil.simplecoil.domain.rules.TeamSizes
import com.simplecoil.simplecoil.domain.session.BlasterSessionEngine
import com.simplecoil.simplecoil.domain.session.SessionEffect
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.session.SessionState
import com.simplecoil.simplecoil.domain.team.TeamColors
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.hardware.audio.SoundEffectPlayer
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionEvent
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionManager
import com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
import com.simplecoil.simplecoil.hardware.blaster.QrPairingPayload
import com.simplecoil.simplecoil.network.ClientCommand
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.GameUpdate
import com.simplecoil.simplecoil.network.server.SessionPhase
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsValues
import com.simplecoil.simplecoil.network.ServerMessage
import com.simplecoil.simplecoil.ui.scoreboard.ScoreMatrixModel
import com.simplecoil.simplecoil.ui.scoreboard.ScoreMatrixState
import com.simplecoil.simplecoil.ui.scoreboard.ScoreboardContext
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

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
    private val publishGpsSetting: () -> Unit,
    private val settingsRepo: GameSettingsRepository,
    private val gameStateRepo: GameStateRepository,
    private val playerRepo: PlayerRepository,
) : ViewModel() {

    /** The screen, while one exists. */
    var screen: GameScreen? = null

    /** Session values for the play screen to render. */
    val session: StateFlow<SessionState> get() = engine.state

    var selectedPlayerId: Byte = 0
        private set
    var deviceAddress: String = ""
        private set
    @Volatile var ready = false
        private set
    var networkMenuType = NETWORK_TYPE_ENABLED
        private set
    /** The server's session phase, from its game info; drives the admin menu (§3.7.1). */
    var serverPhase = SessionPhase.LOBBY
        private set
    private var autoScanStarted = false
    private var resumed = false
    /**
     * Paused between games with the screen out of view: the blaster rescans and the server search
     * wait for the next resume instead of running in the background all day (battery drain).
     */
    private var paused = false
    private var reconnectOnResume = false
    private var serverSearchOnResume = false

    /** The connecting popup's status; null when it should be closed. */
    var connectStatus: ConnectStatus? = null
        private set
    /** The blaster disconnected dialog should be showing. */
    var disconnectDialogShowing = false
        private set
    /** The scoreboard should be showing. */
    var scoreboardVisible = false
        private set
    private var latestPlayerData: Array<PlayerDisplayData?>? = null
    /** The scoreboard dialog shows the score matrix rather than the list (kept across recreation). */
    var scoreMatrixVisible = false
        private set
    /** The latest score matrix, or what to show instead; null until one is asked for. */
    var scoreMatrixState: ScoreMatrixState? = null
        private set
    /** The server's game id from its game info (0 in the lobby): a matrix for another game is stale. */
    private var serverGameId = 0L
    /** Running for [SCORE_MATRIX_MIN_INTERVAL_MS] after each request: the next one waits for it. */
    private var scoreMatrixCooldown: Job? = null
    /** A request is due when the cooldown ends. */
    private var scoreMatrixPending = false
    private var scoreMatrixTimeout: Job? = null
    private var searchTimeout: Job? = null

    val useNetwork: Boolean get() = net.useNetwork
    val isScanning: Boolean get() = ble.isScanning

    /** For the player settings dialog. */
    val tcpClient: TcpClient? get() = net.tcpClient

    /** The latest scoreboard data, if any arrived yet. */
    val scoreboardPlayers: Array<PlayerDisplayData?>? get() = latestPlayerData

    /** How the scoreboard dialog groups and labels the players. */
    fun scoreboardContext() = ScoreboardContext(
        layout = settingsRepo.teamLayout,
        gameLimit = settingsRepo.gameLimit,
        livesLimit = settingsRepo.livesLimit,
        isClient = true,
        selfId = playerRepo.playerID,
        isAdmin = playerRepo.isAdmin,
    )

    init {
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { blasterEvents.collect { onBlasterEvent(it) } }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { ble.events.collect { onConnectionEvent(it) } }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.effects.collect { onSessionEffect(it) } }
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { net.events.collect { onClientEvent(it) } }
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
        playerRepo.playerID = selectedPlayerId
    }

    /** The rest of onCreate, once the views exist. */
    fun onViewsCreated() {
        showConnectLayout()
        playerRepo.playerName = PlayerNames.sanitize(prefs.playerName("Player"))
        if (playerRepo.playerName.isEmpty() || playerRepo.playerName.equals("Player", ignoreCase = true) ||
            ProfanityFilter.containsProfanity(playerRepo.playerName)) {
            val randomName = NameGenerator.getRandomName()
            playerRepo.playerName = randomName
            prefs.savePlayerName(randomName)
        }
        settingsRepo.currentFiringMode = prefs.firingMode(GameSettingsRepository.FIRING_MODE_OUTDOOR_NO_CONE)
        selectedPlayerId = prefs.selectedPlayerId().toByte()
        playerRepo.playerID = selectedPlayerId
        screen?.showFiringMode(settingsRepo.currentFiringMode)
        engine.restore(prefs.shotMode(GameSettingsRepository.SHOT_MODE_SINGLE), prefs.recoilEnabled())
        settingsRepo.setTeamMode(prefs.teamMode())
        screen?.showTeamMode(settingsRepo.teamMode)
        var gameLimit = GameSettingsRepository.GAME_LIMIT_NONE
        settingsRepo.timeLimit = prefs.timeLimit()
        if (settingsRepo.timeLimit != 0) gameLimit += GameSettingsRepository.GAME_LIMIT_TIME
        settingsRepo.livesLimit = prefs.livesLimit()
        if (settingsRepo.livesLimit != 0) gameLimit += GameSettingsRepository.GAME_LIMIT_LIVES
        settingsRepo.scoreLimit = prefs.scoreLimit()
        if (settingsRepo.scoreLimit != 0) gameLimit += GameSettingsRepository.GAME_LIMIT_SCORE
        settingsRepo.gameLimit = gameLimit
        setDeviceAddress(prefs.deviceAddress())
        screen?.setReconnectVisible(deviceAddress.isNotEmpty())
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
        if (selectedPlayerId.toInt() != 0 && playerRepo.playerID.toInt() == 0) playerRepo.playerID = selectedPlayerId
        val savedName = prefs.playerName("")
        if (savedName.isNotEmpty()) playerRepo.playerName = savedName
        displayCurrentTeam()
        updatePlayerSettings()

        ble.reconnect(deviceAddress)
        net.bindServices()
        if (net.useNetwork) net.tcpClient?.sendPlayerInfo(true)

        paused = false
        if (reconnectOnResume) {
            reconnectOnResume = false
            screen?.requestConnect()
        }
        if (serverSearchOnResume) {
            serverSearchOnResume = false
            if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINING) {
                startServerSearchTimeout()
                net.udp?.joinServer()
            }
        }
    }

    fun onPause() {
        resumed = false
        if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) return // a game keeps reconnecting
        paused = true
        if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINING) {
            cancelServerSearchTimeout()
            net.udp?.endScanning()
            serverSearchOnResume = true
        }
    }

    fun onScreenDestroyed() {
        // The matrix face stays chosen: a recreated dialog comes back on it.
        closeScoreboard(keepMatrixFace = true)
        ble.reset()
        net.unbindServices()
        screen = null
    }

    private fun setDeviceAddress(address: String?) {
        deviceAddress = address ?: ""
        playerRepo.deviceAddress = deviceAddress
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

    /** The Generate QR button: a pairing label for this blaster, offered between games. */
    fun onGenerateQrPressed() {
        if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) return
        if (deviceAddress.isEmpty()) {
            screen?.showMessage(GameMessage.QR_NO_BLASTER)
            return
        }
        screen?.showQrNameDialog(playerRepo.playerName ?: "")
    }

    /** A name for the QR label; blank makes a MAC-only code (docs/QR_CODES.md). */
    fun onQrNameEntered(name: String) {
        val trimmed = PlayerNames.sanitize(name)
        if (ProfanityFilter.containsProfanity(trimmed)) {
            screen?.showMessage(GameMessage.INAPPROPRIATE_NAME)
            return
        }
        screen?.showQrCode(QrPairingPayload.encode(deviceAddress, trimmed), trimmed)
    }

    fun onLocationPermissionsGranted() {
        screen?.requestConnect()
        publishGpsSetting()
    }

    /** Permissions and Bluetooth are ready: scan (with the 30 s give-up). False if scanning wasn't allowed. */
    fun startScan(adapter: BluetoothAdapter): Boolean {
        if (ble.startScan(adapter, deviceAddress) == BleConnectionManager.ScanStart.PERMISSION_DENIED) return false
        screen?.setConnectButtonsEnabled(false)
        setConnectStatus(ConnectStatus.SCANNING)
        return true
    }

    fun onDedicatedServerPressed() {
        net.releaseForDedicatedServer()
        screen?.openDedicatedServer()
    }

    private fun onConnectionEvent(event: BleConnectionEvent) {
        when (event) {
            is BleConnectionEvent.ScanMatched -> {
                setConnectStatus(ConnectStatus.CONNECTING)
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
                setConnectStatus(ConnectStatus.COMMUNICATING)
                hideWeaponDisconnect()
            }
            is BlasterEvent.TypeDiscovered -> screen?.showBlasterType(event.blasterType == GattProtocolHandler.BLASTER_TYPE_RIFLE, deviceAddress)
            else -> Unit
        }
    }

    private fun handleDisconnect() {
        Log.d(TAG, "handleDisconnect called. hasConnectedToBlaster=" + engine.hasConnectedToBlaster + ", mGameState=" + gameStateRepo.gameState)
        ble.cancelScanTimeout()
        ble.clearScanning()
        if (engine.hasConnectedToBlaster || gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) {
            if (paused && gameStateRepo.gameState == GameStateRepository.GAME_STATE_NONE) {
                Log.d(TAG, "blaster disconnected while paused, reconnecting on resume")
                reconnectOnResume = true
            } else {
                Log.e(TAG, "blaster disconnected, attempt to reconnect")
                screen?.requestConnect()
            }
            showWeaponDisconnect()
            return
        }
        resetToConnectScreen()
        endGame()
        sounds.play(SessionSound.ELIMINATED)
    }

    /** Cancel on the connecting popup: stop scanning or connecting and go back to the connect screen. */
    fun onConnectCancelled() {
        Log.d(TAG, "Connect cancelled")
        ble.cancelScanTimeout()
        ble.stopScan()
        resetToConnectScreen()
    }

    private fun resetToConnectScreen() {
        ble.reset()
        showConnectLayout()
        screen?.setConnectButtonsEnabled(true)
        setConnectStatus(null)
        engine.onBlasterReset()
    }

    /** Reconnecting mid-game is covered by the disconnected dialog, so the popup stays closed then. */
    private fun setConnectStatus(status: ConnectStatus?) {
        val shown = status.takeUnless { disconnectDialogShowing }
        if (shown == connectStatus) return
        connectStatus = shown
        screen?.showConnectStatus(shown)
    }

    private fun showConnectLayout() {
        screen?.showConnectScreen(deviceAddress.isNotEmpty())
    }

    private fun showWeaponDisconnect() {
        if (disconnectDialogShowing) return
        disconnectDialogShowing = true
        setConnectStatus(null)
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
        gameStateRepo.gameState = GameStateRepository.GAME_STATE_NONE
        ble.cancelScanTimeout()
        ble.stopScan()
        stopServerSearch(true)
        closeScoreboard()
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
                setConnectStatus(null)
                screen?.showPlayScreen()
                updatePreGameButtonVisibilities()
                autoStartServerScan()
            }
            SessionEffect.TeamSent -> saveTeamAndDisplay()
            SessionEffect.PlayerIdReported -> displayCurrentTeam()
            SessionEffect.EndGameRequested -> endGame()
            SessionEffect.ThumbLongPressed -> toggleScoreboard()
            // Sounds and vibration are played app-side by SessionFeedback.
            is SessionEffect.Sound, SessionEffect.Vibrate -> Unit
        }
    }

    // ---------------------------------------------------------------------------------------
    // Team and player
    // ---------------------------------------------------------------------------------------

    fun onTeamMinusPressed() {
        if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) return
        var playerId = playerRepo.playerID.toInt()
        playerId = if (net.useNetwork) {
            if (playerId > 0) playerId - 1 else GameSettingsRepository.MAX_PLAYER_ID.toInt()
        } else {
            if (playerId > 1) playerId - 1 else GameSettingsRepository.MAX_PLAYER_ID.toInt()
        }
        playerRepo.playerID = playerId.toByte()
        selectedPlayerId = playerId.toByte()
        setTeam()
    }

    fun onTeamPlusPressed() {
        if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) return
        var playerId = playerRepo.playerID.toInt()
        playerId = if (net.useNetwork) {
            if (playerId < GameSettingsRepository.MAX_PLAYER_ID) playerId + 1 else 0x00
        } else {
            if (playerId < GameSettingsRepository.MAX_PLAYER_ID) playerId + 1 else 0x01
        }
        playerRepo.playerID = playerId.toByte()
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

    /** Persists the player id / team mode and redisplays the team (the rest of setTeam()). */
    private fun saveTeamAndDisplay() {
        prefs.savePlayerIdAndTeamMode(playerRepo.playerID.toInt(), settingsRepo.teamMode)
        displayCurrentTeam()
    }

    private fun displayCurrentTeam() {
        if ((net.useNetwork || selectedPlayerId.toInt() == 0) && playerRepo.playerID.toInt() == 0) {
            screen?.showTeam(TeamDisplay.AutoSort)
            return
        }
        if (!net.useNetwork && playerRepo.playerID.toInt() == 0) {
            playerRepo.playerID = if (selectedPlayerId.toInt() != 0) selectedPlayerId else 1
        }
        val layout = settingsRepo.teamLayout
        val dedicatedName = if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINED) {
            playerRepo.playerName?.takeIf { it.isNotEmpty() }
        } else null
        if (!layout.mode.isFreeForAll) {
            val networkTeam = layout.teamOf(playerRepo.playerID)
            engine.networkTeam = networkTeam
            val startID = layout.teamStartId(networkTeam)
            val player = playerRepo.playerID - startID + 1
            screen?.showTeam(TeamDisplay.Team(networkTeam, player, TeamColors.colorOf(networkTeam), net.useNetwork, dedicatedName))
        } else {
            screen?.showTeam(TeamDisplay.FreeForAll(playerRepo.playerID, dedicatedName))
        }
    }

    fun onPlayerNameEntered(name: String) = setPlayerName(name)

    private fun setPlayerName(name: String?) {
        var trimmed = PlayerNames.sanitize(name)
        if (trimmed.isEmpty()) return
        if (trimmed.equals("Player", ignoreCase = true) || ProfanityFilter.containsProfanity(trimmed)) {
            if (ProfanityFilter.containsProfanity(trimmed)) screen?.showMessage(GameMessage.INAPPROPRIATE_NAME)
            trimmed = NameGenerator.getRandomName()
        }
        playerRepo.playerName = trimmed
        prefs.savePlayerName(trimmed)
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
        if (!settingsRepo.isAllowPlayerSettings) {
            screen?.showMessage(GameMessage.PLAYER_SETTINGS_NOT_ALLOWED)
            return
        }
        screen?.showLocalPlayerSettings()
    }

    /** The local player's settings, for the player settings dialog. */
    fun localPlayerSettings() = PlayerSettingsValues.of(settingsRepo)

    /** The player settings dialog's OK: keep them, tell the server, and apply them. */
    fun onLocalSettingsSaved(values: PlayerSettingsValues) {
        values.applyTo(settingsRepo)
        net.tcpClient?.sendPlayerSettings()
        updatePlayerSettings()
    }

    fun onFiringModeSelected(firingMode: Int) {
        settingsRepo.currentFiringMode = firingMode
        screen?.showFiringMode(firingMode)
        engine.setShotMode(engine.shotMode)
        prefs.saveFiringMode(settingsRepo.currentFiringMode)
    }

    /** The team mode button: a player not on a server picks 2 teams / 4 teams / FFA locally. */
    fun onTeamModePressed() {
        if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) return
        if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINED) return
        screen?.showTeamModeMenu()
    }

    fun onTeamModeSelected(mode: TeamMode) {
        settingsRepo.setTeamMode(mode)
        screen?.showTeamMode(mode)
        setTeam()
    }

    private fun updatePlayerSettings() {
        screen?.showPlayerSettings(settingsRepo.fullHealth, settingsRepo.fullReload, settingsRepo.damage * -1)
        setGameLimit()
        updateAdminButtonVisibility()
        engine.applyPlayerSettings()
        screen?.showFiringMode(settingsRepo.currentFiringMode)
    }

    // ---------------------------------------------------------------------------------------
    // Game limits
    // ---------------------------------------------------------------------------------------

    fun onGameLimitPressed() {
        screen?.showGameLimitDialog(net.useNetwork)
    }

    fun onGameLimitEntered(text: String, kind: LimitKind) {
        val current = Limits(settingsRepo.gameLimit, settingsRepo.timeLimit, settingsRepo.livesLimit, settingsRepo.scoreLimit)
        when (val result = GameLimitRules.apply(current, kind, text)) {
            GameLimitRules.Result.Ignored -> return
            GameLimitRules.Result.TooHigh -> {
                screen?.showMessage(GameMessage.LIMIT_TOO_HIGH)
                return
            }
            is GameLimitRules.Result.Applied -> {
                val limits = result.limits
                settingsRepo.timeLimit = limits.timeLimit
                settingsRepo.livesLimit = limits.livesLimit
                settingsRepo.scoreLimit = limits.scoreLimit
                settingsRepo.gameLimit = limits.gameLimit
            }
        }
        setGameLimit()
    }

    private fun setGameLimit() {
        val timeLimit = if ((settingsRepo.gameLimit and GameSettingsRepository.GAME_LIMIT_TIME) != 0) settingsRepo.timeLimit else null
        // Lives or eliminations, including a server override of the lives (the count itself is
        // part of the session state).
        engine.applyGameLimit()
        val scoreLimit = if ((settingsRepo.gameLimit and GameSettingsRepository.GAME_LIMIT_SCORE) != 0) settingsRepo.scoreLimit else null
        screen?.showGameLimit(timeLimit, engine.hasLivesLimitSet, scoreLimit)
        prefs.saveLimits(settingsRepo.timeLimit, settingsRepo.scoreLimit, settingsRepo.livesLimit)
    }

    // ---------------------------------------------------------------------------------------
    // Game lifecycle
    // ---------------------------------------------------------------------------------------

    fun onStartGamePressed() {
        if (playerRepo.playerID.toInt() == 0) {
            playerRepo.playerID = 1
            setTeam()
        }
        if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINED) {
            if (playersInGame() <= 1) {
                screen?.showMessage(GameMessage.NOT_ENOUGH_PLAYERS)
                screen?.showOnlyOnePlayer()
                return
            }
            net.tcpClient!!.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.StartGame))
        } else {
            if (net.useNetwork) stopServerSearch(true)
            startGame()
        }
    }

    private fun startGame() {
        closeScoreboard()
        net.score = 0
        net.teamScore = 0
        screen?.showGameStarted(net.useNetwork, playerRepo.isAdmin, freeForAll())
        // Lives/eliminations, health, shot mode, first spawn
        engine.startGame()
        updateAdminButtonVisibility()
    }

    fun onStartRespawnPressed() {
        engine.startRespawn()
    }

    fun onEndGamePressed() {
        screen?.confirmEndGame()
    }

    fun onEndGameConfirmed() {
        if (net.useNetwork) {
            if (playerRepo.isAdmin) {
                net.tcpClient?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.AdminEndGame))
            } else {
                net.tcpClient!!.leaveServer()
                endGame()
            }
        } else {
            endGame()
        }
    }

    private fun endGame() {
        val wasInNetworkGame = net.useNetwork && gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE
        // Game state NONE, timers, spawn/shield/clock display, reload lock
        engine.endGame()
        hideWeaponDisconnect()
        screen?.showGameEnded(net.useNetwork, freeForAll())
        updatePreGameButtonVisibilities()
        if (ready && net.useNetwork) {
            setNetworkMenu(NETWORK_TYPE_JOINED)
            screen?.setScoreboardAvailable(true)
        } else {
            setNetworkMenu(NETWORK_TYPE_ENABLED)
        }
        if (wasInNetworkGame) openScoreboard()
    }

    private fun updatePreGameButtonVisibilities() {
        if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) return
        val connectedClient = net.useNetwork && networkMenuType == NETWORK_TYPE_JOINED
        screen?.showPreGameButtons(PreGameButtons(
            ready = ready,
            offline = !net.useNetwork,
            joined = connectedClient,
            startVisible = !(settingsRepo.isOnlyServerSettings || connectedClient || serverPhase == SessionPhase.POST_GAME),
            serverSettingsOnly = settingsRepo.isOnlyServerSettings,
        ))
        updateAdminButtonVisibility()
    }

    /** Hidden during a game: its only entry then is End Game, which has its own button. */
    private fun updateAdminButtonVisibility() {
        val inGame = engine.state.value.gameInProgress
        val showAdmin = playerRepo.isAdmin && net.useNetwork && ready && !inGame
        screen?.showAdminButton(showAdmin)
        Log.d(TAG, "updateAdminButtonVisibility: mIsAdmin=" + playerRepo.isAdmin + ", mUseNetwork=" + net.useNetwork + ", mReady=" + ready + ", inGame=" + inGame + " -> show=" + showAdmin)
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
            playerRepo.playerID = selectedPlayerId
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
        NETWORK_TYPE_ENABLED -> listOf(
            NetworkMenuEntry.Join, NetworkMenuEntry.JoinIp, NetworkMenuEntry.PlayerName(playerRepo.playerName), NetworkMenuEntry.DisableNetwork,
        )
        NETWORK_TYPE_JOINING -> listOf(NetworkMenuEntry.JoinIp, NetworkMenuEntry.DisableNetwork)
        NETWORK_TYPE_JOINED -> buildList {
            add(NetworkMenuEntry.PlayerName(playerRepo.playerName))
            if (settingsRepo.isAllowChooseTeams && gameStateRepo.gameState == GameStateRepository.GAME_STATE_NONE) {
                val layout = settingsRepo.teamLayout
                if (!layout.mode.isFreeForAll) {
                    val currentTeam = layout.teamOf(playerRepo.playerID)
                    for (t in 1..layout.mode.teamCount) {
                        if (t != currentTeam) add(NetworkMenuEntry.SwitchTeam(t, TeamColors.colorOf(t)))
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
            is NetworkMenuEntry.PlayerName -> screen?.showPlayerNameDialog(playerRepo.playerName)
            is NetworkMenuEntry.SwitchTeam -> requestSwitchTeam(entry.team)
        }
    }

    private fun serverIpSuggestion(): String {
        val ip = gameStateRepo.serverIP?.toString() ?: PlayerRepository.getIPAddressStr()
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
        if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) {
            Log.w(TAG, "Cannot request team change while game is running")
            return
        }
        client.sendTCPMessage(NetworkSessionGateway.teamChangeRequest(playerRepo.playerID, targetTeam))
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
            settingsRepo.isOnlyServerSettings = false
            settingsRepo.isUseGPS = false
            if (sendPlayerLeft) net.tcpClient?.leaveServer()
            net.udp?.stopListen()
            net.tcpClient?.stopTcpClient()
            serverPhase = SessionPhase.LOBBY
            serverGameId = 0
            resetScoreMatrix()
            screen?.showNotReady()
            setNetworkMenu(NETWORK_TYPE_ENABLED)
            screen?.setScoreboardAvailable(false)
        }
        updatePreGameButtonVisibilities()
        publishGpsSetting()
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
            updatePreGameButtonVisibilities()
            if (playerRepo.playerID.toInt() == 0) {
                playerRepo.playerID = if (selectedPlayerId.toInt() != 0) selectedPlayerId else 1
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
            playerRepo.playerID = selectedPlayerId
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

    /** The admin menu for the server's phase: Reset takes Start Game's place after a game. */
    fun adminActions(): List<AdminAction> {
        Log.d(TAG, "showAdminControlsMenu: serverPhase=$serverPhase")
        return when (serverPhase) {
            SessionPhase.LOBBY -> listOf(AdminAction.START_GAME, AdminAction.TWO_TEAMS, AdminAction.FOUR_TEAMS, AdminAction.FREE_FOR_ALL, AdminAction.SHUFFLE_TEAMS)
            SessionPhase.RUNNING -> listOf(AdminAction.END_GAME)
            SessionPhase.POST_GAME -> listOf(AdminAction.RESET_GAME, AdminAction.TWO_TEAMS, AdminAction.FOUR_TEAMS, AdminAction.FREE_FOR_ALL, AdminAction.SHUFFLE_TEAMS)
        }
    }

    fun onAdminAction(action: AdminAction) {
        val client = net.tcpClient
        when (action) {
            AdminAction.START_GAME -> {
                Log.d(TAG, "Server Controls: Start Game clicked. mTcpClient=" + (client != null))
                client?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.StartGame))
                client?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.AdminStartGame))
            }
            AdminAction.END_GAME -> {
                Log.d(TAG, "Server Controls: End Game clicked. mTcpClient=" + (client != null))
                client?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.AdminEndGame))
            }
            AdminAction.TWO_TEAMS -> changeAdminTeamMode(TeamMode.TWO_TEAMS)
            AdminAction.FOUR_TEAMS -> changeAdminTeamMode(TeamMode.FOUR_TEAMS)
            AdminAction.FREE_FOR_ALL -> changeAdminTeamMode(TeamMode.FREE_FOR_ALL)
            AdminAction.SHUFFLE_TEAMS -> client?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.AdminSortTeams))
            AdminAction.RESET_GAME -> screen?.confirmReset(latestPlayerData?.count { it != null && !it.isConnected } ?: 0)
        }
    }

    /** The admin confirmed the reset. Team mode and sort after a game need no confirmation: the server resets first. */
    fun onResetConfirmed() {
        net.tcpClient?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.AdminReset))
    }

    private fun changeAdminTeamMode(mode: TeamMode) {
        Log.d(TAG, "changeAdminTeamMode: mode=" + mode + ", mTcpClient=" + (net.tcpClient != null))
        net.tcpClient?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.AdminGameMode(mode.wireValue)))
    }

    fun onKickPlayer(playerId: Byte) {
        val client = net.tcpClient ?: return
        client.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.AdminKick(playerId)))
        screen?.showMessage(GameMessage.KICK_SENT)
    }

    fun onTeamRatiosRequested() {
        if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) {
            screen?.showMessage(GameMessage.RATIOS_DURING_GAME)
            return
        }
        if (!playerRepo.isAdmin) {
            screen?.showMessage(GameMessage.RATIOS_ADMIN_ONLY)
            return
        }
        val layout = settingsRepo.teamLayout
        if (layout.mode.isFreeForAll) {
            screen?.showMessage(GameMessage.RATIOS_NOT_IN_FFA)
            return
        }
        screen?.showTeamRatioDialog(layout.mode.teamCount, layout.teamSizes())
    }

    /** Team sizes entered in the ratio dialog. Returns true when accepted (close the dialog). */
    fun onTeamSizesEntered(texts: List<String>): Boolean {
        val newSizes = when (val result = TeamSizes.validate(texts)) {
            TeamSizes.Result.BelowOne -> {
                screen?.showMessage(GameMessage.RATIOS_MIN_ONE)
                return false
            }
            is TeamSizes.Result.WrongTotal -> {
                screen?.showMessage(GameMessage.RATIOS_TOTAL, result.total)
                return false
            }
            is TeamSizes.Result.Valid -> result.sizes
        }
        settingsRepo.setTeamSizes(newSizes)
        net.tcpClient?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.AdminTeamSizes(newSizes.toList())))
        return true
    }

    // ---------------------------------------------------------------------------------------
    // Scoreboard
    // ---------------------------------------------------------------------------------------

    fun openScoreboard() {
        scoreboardVisible = true
        latestPlayerData?.let { screen?.showScoreboard(it) }
        if (net.useNetwork) net.tcpClient?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.PlayerDataRequest))
        if (scoreMatrixVisible) {
            screen?.showScoreMatrix(scoreMatrixState ?: ScoreMatrixState.Loading.also { scoreMatrixState = it })
            requestScoreMatrix()
        }
    }

    /** Thumb button long press: opens or closes the scoreboard while joined to a server. */
    private fun toggleScoreboard() {
        if (!net.useNetwork || networkMenuType != NETWORK_TYPE_JOINED) return
        if (scoreboardVisible) closeScoreboard() else openScoreboard()
    }

    private fun closeScoreboard(keepMatrixFace: Boolean = false) {
        scoreboardVisible = false
        if (!keepMatrixFace) resetScoreMatrix()
        screen?.closeScoreboard()
    }

    fun onScoreboardDismissed() {
        scoreboardVisible = false
        resetScoreMatrix()
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
    // Score matrix (SCORE_MATRIX_DESIGN.md §5)
    // ---------------------------------------------------------------------------------------

    /** The scoreboard dialog's SCORE MATRIX / SCOREBOARD button: switches faces; the matrix face asks the server. */
    fun onScoreMatrixToggled() {
        if (scoreMatrixVisible) {
            scoreMatrixVisible = false
            scoreMatrixPending = false
            return
        }
        scoreMatrixVisible = true
        val state = scoreMatrixState ?: ScoreMatrixState.Loading.also { scoreMatrixState = it }
        screen?.showScoreMatrix(state)
        requestScoreMatrix()
    }

    private val scoreMatrixShowing get() = scoreboardVisible && scoreMatrixVisible

    /**
     * Asks for the matrix, at most once every [SCORE_MATRIX_MIN_INTERVAL_MS]. One asked for
     * sooner is sent when the interval ends, if the matrix is still showing, so the last change
     * always gets through (Q3).
     */
    private fun requestScoreMatrix() {
        if (!net.useNetwork) return
        if (scoreMatrixCooldown != null) {
            scoreMatrixPending = true
            return
        }
        scoreMatrixPending = false
        net.tcpClient?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.ScoreMatrixRequest))
        scoreMatrixTimeout?.cancel()
        scoreMatrixTimeout = viewModelScope.launch {
            delay(SCORE_MATRIX_TIMEOUT_MS)
            // No answer, and nothing shown yet: an older server. A late reply still replaces it.
            if (scoreMatrixState == ScoreMatrixState.Loading) {
                scoreMatrixState = ScoreMatrixState.Unsupported
                if (scoreMatrixShowing) screen?.showScoreMatrix(ScoreMatrixState.Unsupported)
            }
        }
        scoreMatrixCooldown = viewModelScope.launch {
            delay(SCORE_MATRIX_MIN_INTERVAL_MS)
            scoreMatrixCooldown = null
            if (scoreMatrixPending && scoreMatrixShowing) requestScoreMatrix() else scoreMatrixPending = false
        }
    }

    /** Something that changes the matrix reached this phone: an open matrix asks again. */
    private fun refreshScoreMatrix() {
        if (scoreMatrixShowing) requestScoreMatrix()
    }

    private fun onScoreMatrix(message: ServerMessage.ScoreMatrix) {
        if ((message.gameId ?: 0L) != serverGameId) {
            Log.d(TAG, "Dropping a score matrix for game ${message.gameId}; this is game $serverGameId")
            return
        }
        scoreMatrixTimeout?.cancel()
        val state = ScoreMatrixState.Ready(ScoreMatrixModel.from(message, settingsRepo.teamLayout, playerRepo.playerID))
        scoreMatrixState = state
        if (scoreMatrixShowing) screen?.showScoreMatrix(state)
    }

    /** The server's game changed (started, or reset): the last matrix is gone. */
    private fun onServerGameId(gameId: Long) {
        if (gameId == serverGameId) return
        serverGameId = gameId
        if (scoreMatrixState == null) return
        scoreMatrixState = ScoreMatrixState.Loading
        if (scoreMatrixShowing) {
            screen?.showScoreMatrix(ScoreMatrixState.Loading)
            requestScoreMatrix()
        }
    }

    private fun resetScoreMatrix() {
        scoreMatrixVisible = false
        scoreMatrixState = null
        scoreMatrixPending = false
        scoreMatrixTimeout?.cancel()
    }

    // ---------------------------------------------------------------------------------------
    // NETMSG_* (D4: handled for the screen's whole lifetime)
    // ---------------------------------------------------------------------------------------

    private fun onClientEvent(event: ClientEvent) {
        when (event) {
            ClientEvent.UdpServiceConnected -> if (engine.isCommunicating) autoStartServerScan()
            ClientEvent.UdpServiceDisconnected -> {
                ready = false
                setReady(false)
                if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) endGame()
            }
            is ClientEvent.PlayerData -> {
                // Scoreboard parsed off the main thread (scores already updated)
                screen?.showScore(net.score)
                screen?.showTeamScore(net.teamScore)
                updateScoreboardData(event.players)
                refreshScoreMatrix()
            }
            is ClientEvent.ScoreMatrix -> onScoreMatrix(event.matrix)
            ClientEvent.ShotFired -> {
                engine.onRemoteShotFired() // Keeps us from spamming shots fired messages
                screen?.showShotsFired()
            }
            is ClientEvent.Hit -> screen?.showHitOtherPlayer(false, playerName(event.playerId))
            is ClientEvent.Out -> screen?.showHitOtherPlayer(true, playerName(event.playerId))
            is ClientEvent.Eliminated -> {
                onEliminatedSomeone(event.playerId)
                refreshScoreMatrix()
            }
            ClientEvent.TeamEliminated -> {
                refreshScoreMatrix()
                // Increase team score in team games
                net.teamScore = net.teamScore + 1
                screen?.showTeamScore(net.teamScore)
                if (!net.useNetwork && (settingsRepo.gameLimit and GameSettingsRepository.GAME_LIMIT_SCORE) != 0 && net.teamScore >= settingsRepo.scoreLimit) endGame()
            }
            is ClientEvent.ListPlayers -> {
                event.gameId?.let { onServerGameId(it) }
                onListPlayers(event)
                // The (re)join snapshot: catch up on what was missed while away
                if (event.gameUpdate != null) refreshScoreMatrix()
            }
            is ClientEvent.PlayerDataUpdate -> displayPlayerData(event.json)
            ClientEvent.StartGame -> {
                closeScoreboard()
                if (gameStateRepo.gameState == GameStateRepository.GAME_STATE_NONE) startGame()
            }
            ClientEvent.EndGame -> if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) endGame()
            is ClientEvent.Error -> {
                val message = event.message
                if (!message.isNullOrEmpty()) screen?.showText(message) else Log.d(TAG, "No error message given")
            }
            ClientEvent.VersionError -> {
                screen?.showMessage(GameMessage.UDP_VERSION_ERROR)
                ready = false
                setReady()
            }
            ClientEvent.FailedToJoin -> {
                Log.d(TAG, "FailedToJoin received. mUseNetwork=" + net.useNetwork + ", mNetworkMenuType=" + networkMenuType)
                if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINING) {
                    viewModelScope.launch {
                        delay(FAILED_JOIN_RETRY_MILLIS)
                        val udp = net.udp
                        if (net.useNetwork && networkMenuType == NETWORK_TYPE_JOINING && udp != null && !paused) {
                            Log.d(TAG, "Auto-retrying server scan...")
                            udp.joinServer()
                        }
                    }
                }
            }
            ClientEvent.ServerCancel -> {
                cancelServerSearchTimeout()
                if (gameStateRepo.gameState != GameStateRepository.GAME_STATE_NONE) {
                    endGame()
                } else {
                    ready = false
                    setReady(false)
                }
                net.useNetwork = true
                setNetworkMenu(NETWORK_TYPE_ENABLED)
                screen?.showNetworkOptions(true, freeForAll())
                playerRepo.playerID = selectedPlayerId
                setTeam()
                screen?.showMessage(GameMessage.KICKED_BY_SERVER)
            }
            is ClientEvent.ServerReply -> {
                cancelServerSearchTimeout()
                val assigned = event.assignedPlayerId
                if (assigned != null && assigned.toInt() != 0) {
                    playerRepo.playerID = assigned
                    setTeam()
                }
                // A rejoin (on resume, or after the connection drops) answers a session already
                // shown as joined. setReady would put the "searching" label back, and mid-game no
                // player list clears it.
                if (networkMenuType != NETWORK_TYPE_JOINED) {
                    ready = true
                    setReady()
                }
                net.startTcpClient()
                setNetworkMenu(NETWORK_TYPE_JOINED)
            }
            ClientEvent.NetworkConnected -> {
                cancelServerSearchTimeout()
                screen?.showNetworkConnected(true)
                if (ready && net.useNetwork) setNetworkMenu(NETWORK_TYPE_JOINED)
            }
            ClientEvent.NetworkDisconnected -> screen?.showNetworkConnected(false)
            ClientEvent.PlayerSettingsUpdate -> updatePlayerSettings()
            is ClientEvent.GpsData, ClientEvent.GpsSettingChanged -> Unit // the map's
        }
    }

    private fun onEliminatedSomeone(playerId: Byte) {
        // Increase score
        net.score = net.score + 1
        screen?.showScore(net.score)
        screen?.showScoreIncrease(playerName(playerId))
        sounds.play(SessionSound.SCORE)
        if (!settingsRepo.teamMode.isFreeForAll) {
            net.teamScore = net.teamScore + 1
            screen?.showTeamScore(net.teamScore)
            net.tcpClient?.sendTCPMessage(NetworkSessionGateway.command(ClientCommand.TeamEliminated))
        }
        if (!net.useNetwork && (settingsRepo.gameLimit and GameSettingsRepository.GAME_LIMIT_SCORE) != 0) {
            val currentScore = if (!settingsRepo.teamMode.isFreeForAll) net.teamScore else net.score
            if (currentScore >= settingsRepo.scoreLimit) endGame()
        }
    }

    /** The server's roster leaves this phone out, so count it here. */
    private fun playersInGame() = playerRepo.playerCount + 1

    private fun onListPlayers(event: ClientEvent.ListPlayers) {
        if (!ready) return
        net.udp!!.endScanning()
        screen?.showPlayerCount(playersInGame())
        screen?.showTeamMode(settingsRepo.teamMode)
        setTeam()
        if (ready && net.useNetwork && gameStateRepo.gameState == GameStateRepository.GAME_STATE_NONE) {
            ready = true
            setReady()
            setGameLimit()
            setNetworkMenu(NETWORK_TYPE_JOINED)
            screen?.hideStartButton()
            var ip = gameStateRepo.serverIP?.toString() ?: ""
            if (ip.startsWith("/")) ip = ip.substring(1)
            screen?.showConnectedToServer(ip)
        }
        event.sessionPhase?.let { onServerPhase(SessionPhase.fromWire(it)) }
        event.gameUpdate?.let { applyGameUpdate(it) }
        event.gameState?.let { serverGameState ->
            if (serverGameState != GameStateRepository.GAME_STATE_ELIMINATED) {
                if (serverGameState == GameStateRepository.GAME_STATE_NONE && gameStateRepo.gameState != serverGameState) {
                    endGame()
                } else if (serverGameState == GameStateRepository.GAME_STATE_RUNNING) {
                    closeScoreboard()
                    if (gameStateRepo.gameState == GameStateRepository.GAME_STATE_NONE) startGame()
                }
            }
        }
        updatePreGameButtonVisibilities()
    }

    private fun onServerPhase(phase: SessionPhase) {
        if (phase == serverPhase) return
        if (serverPhase == SessionPhase.POST_GAME && phase == SessionPhase.LOBBY) {
            // The server was reset: the last game's score is gone.
            net.score = 0
            net.teamScore = 0
            screen?.showScore(0)
            screen?.showTeamScore(0)
        }
        serverPhase = phase
        screen?.rebuildAdminMenu()
    }

    private fun applyGameUpdate(update: GameUpdate) {
        net.score = update.score
        screen?.showScore(net.score)
        if (!settingsRepo.teamMode.isFreeForAll) {
            net.teamScore = update.teamScore
            screen?.showTeamScore(net.teamScore)
        }
        // Eliminations (or lives left) and the game countdown follow the server
        engine.syncFromServer(update.eliminations, update.timeRemaining)
    }

    private fun playerName(playerId: Byte): String = if (playerId.toInt() == 0) "" else playerRepo.getPlayerName(playerId) ?: ""

    private fun freeForAll() = settingsRepo.teamMode.isFreeForAll

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

        /** At most one score matrix request this often (Q3). */
        const val SCORE_MATRIX_MIN_INTERVAL_MS = 5_000L

        /** No score matrix this long after asking: the server doesn't have it. */
        const val SCORE_MATRIX_TIMEOUT_MS = 3_000L
    }
}
