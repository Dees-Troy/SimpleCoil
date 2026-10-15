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

import android.content.Context
import android.content.ServiceConnection
import com.simplecoil.simplecoil.NetworkSessionGateway
import com.simplecoil.simplecoil.PlayerDisplayData
import com.simplecoil.simplecoil.TcpClient
import com.simplecoil.simplecoil.MemoryPrefs
import com.simplecoil.simplecoil.UDPListenerService
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.data.repository.GameStateRepository
import com.simplecoil.simplecoil.data.repository.PlayerRepository
import com.simplecoil.simplecoil.domain.rules.LimitKind
import com.simplecoil.simplecoil.domain.session.BlasterSessionEngine
import com.simplecoil.simplecoil.domain.session.Recorder
import com.simplecoil.simplecoil.domain.session.RepositoryGameConfig
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.domain.team.TeamColors
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.hardware.audio.SoundEffectPlayer
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import com.simplecoil.simplecoil.hardware.blaster.BlasterManager
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionManager
import com.simplecoil.simplecoil.network.ClientEvent
import com.simplecoil.simplecoil.network.ServerMessage
import com.simplecoil.simplecoil.network.WireMatrixCell
import com.simplecoil.simplecoil.network.WireMatrixPlayer
import com.simplecoil.simplecoil.network.server.SessionPhase
import com.simplecoil.simplecoil.ui.scoreboard.ScoreMatrixState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * The play screen logic moved out of FullscreenActivity, on virtual time, against the real session
 * engine, network gateway and connection manager (services mocked) and a mocked GameScreen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GameViewModelTest {

    private val scope = TestScope()
    private val main = UnconfinedTestDispatcher(scope.testScheduler)
    private val rec = Recorder()
    private val sounds = mutableListOf<SessionSound>()
    private val blasterEvents = MutableSharedFlow<BlasterEvent>(extraBufferCapacity = 16)
    private lateinit var engine: BlasterSessionEngine
    private lateinit var net: NetworkSessionGateway
    private lateinit var ble: BleConnectionManager
    private lateinit var screen: GameScreen
    private lateinit var udp: UDPListenerService
    private lateinit var client: TcpClient
    private lateinit var vm: GameViewModel
    private var wifi = true
    private var gpsSettingChanges = 0

    private val prefs = MemoryPrefs()
    private val settings = GameSettingsRepository()
    private val gameState = GameStateRepository()
    private val players = PlayerRepository(settings, gameState)

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        settings.isOnlyServerSettings = false
        players.playerName = "Tester"
        engine = BlasterSessionEngine(rec, RepositoryGameConfig(settings, players, gameState), rec, scope, clock = { scope.testScheduler.currentTime })
        net = NetworkSessionGateway(mock(Context::class.java), scope, settings, players)
        udp = mock(UDPListenerService::class.java)
        client = mock(TcpClient::class.java)
        net.attachForTest(udp, client)
        engine.network = net
        val binder = object : BleConnectionManager.ServiceBinder {
            override fun start() = Unit
            override fun stop() = Unit
            override fun bind(connection: ServiceConnection) = Unit
            override fun unbind(connection: ServiceConnection) = Unit
        }
        ble = BleConnectionManager(mock(Context::class.java), BlasterManager(scope), scope, binder, { true }, { })
        screen = mock(GameScreen::class.java)
        vm = GameViewModel(engine, net, ble, blasterEvents, UserPreferencesRepository(prefs),
            object : SoundEffectPlayer { override fun play(sound: SessionSound) { sounds += sound } },
            wifiConnected = { wifi }, publishGpsSetting = { gpsSettingChanges++ },
            settingsRepo = settings, gameStateRepo = gameState, playerRepo = players)
        prefs.values["PlayerName"] = "Tester"
        vm.screen = screen
        vm.onCreate(null)
        vm.onViewsCreated()
        rec.ble.clear() // start-up applies the saved recoil setting
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun advance(millis: Long) {
        scope.testScheduler.advanceTimeBy(millis)
        scope.testScheduler.runCurrent()
    }

    // --------------------------------------------------------------------------------------
    // Local game
    // --------------------------------------------------------------------------------------

    @Test
    fun startLocalGame_setsDefaultPlayerAndStartsTheSession() {
        vm.onStartGamePressed()
        assertEquals(1.toByte(), players.playerID)
        assertEquals(GameStateRepository.GAME_STATE_ELIMINATED, gameState.gameState) // spawning
        verify(screen).showGameStarted(false, false, false)
        assertTrue(rec.ble.contains("team:1"))
        assertEquals(1, prefs.values["PlayerID"])
    }

    @Test
    fun endGameConfirmed_locally_endsSessionAndShowsLobby() {
        vm.onStartGamePressed()
        vm.onEndGameConfirmed()
        assertEquals(GameStateRepository.GAME_STATE_NONE, gameState.gameState)
        verify(screen).showGameEnded(false, false)
        verify(client, never()).sendTCPMessage("SimpleCoil:07MESGPLAYERDATAREQUEST") // no scoreboard after a local game
    }

    @Test
    fun teamButtons_wrapAroundAndPersist() {
        players.playerID = 1
        vm.onTeamMinusPressed()
        assertEquals(GameSettingsRepository.MAX_PLAYER_ID, players.playerID)
        vm.onTeamPlusPressed()
        assertEquals(1.toByte(), players.playerID)
        assertEquals(1, prefs.values["SelectedPlayerID"])
        assertEquals(listOf("team:32", "team:1"), rec.ble)
    }

    @Test
    fun teamButtons_ignoredDuringAGame() {
        vm.onStartGamePressed()
        rec.ble.clear()
        vm.onTeamPlusPressed()
        assertTrue(rec.ble.isEmpty())
    }

    @Test
    fun gameLimit_parsesAndIgnoresTooHigh() {
        vm.onGameLimitsEntered(mapOf(LimitKind.TIME to "10", LimitKind.LIVES to "0"))
        assertEquals(GameSettingsRepository.GAME_LIMIT_TIME, settings.gameLimit)
        assertEquals(10, prefs.values["TimeLimit"])
        verify(screen).showGameLimit(10, false, null)
        vm.onGameLimitsEntered(mapOf(LimitKind.LIVES to "1001"))
        assertEquals(GameSettingsRepository.GAME_LIMIT_TIME, settings.gameLimit)
        vm.onGameLimitsEntered(mapOf(LimitKind.LIVES to "abc"))
        assertEquals(GameSettingsRepository.GAME_LIMIT_TIME, settings.gameLimit)
    }

    // --------------------------------------------------------------------------------------
    // Connection
    // --------------------------------------------------------------------------------------

    @Test
    fun linkLost_beforeEverConnecting_resetsToConnectScreen() {
        blasterEvents.tryEmit(BlasterEvent.Disconnected)
        verify(screen).setConnectButtonsEnabled(true)
        assertNull(vm.connectStatus)
        assertEquals(listOf(SessionSound.ELIMINATED), sounds)
        verify(screen, never()).showDisconnectDialog()
    }

    @Test
    fun linkLost_midGame_reconnectsBehindTheDialog_andDiscoveryDismissesIt() {
        vm.onStartGamePressed()
        blasterEvents.tryEmit(BlasterEvent.Disconnected)
        blasterEvents.tryEmit(BlasterEvent.Disconnected) // the dialog is shown once
        verify(screen, times(2)).requestConnect()
        verify(screen, times(1)).showDisconnectDialog()

        rec.ble.clear()
        blasterEvents.tryEmit(BlasterEvent.ServicesDiscovered)
        verify(screen).dismissDisconnectDialog()
        advance(600)
        assertTrue(rec.ble.contains("recoil:true"))
    }

    @Test
    fun linkLost_betweenGamesWhilePaused_waitsForResumeToRescan() {
        engine.hasConnectedToBlaster = true
        vm.onPause()
        blasterEvents.tryEmit(BlasterEvent.Disconnected)
        verify(screen).showDisconnectDialog()
        verify(screen, never()).requestConnect()

        vm.onResume()
        verify(screen).requestConnect()
        vm.onPause()
        vm.onResume()
        verify(screen).requestConnect() // only the one that was waiting
    }

    @Test
    fun linkLost_midGameWhilePaused_keepsReconnecting() {
        vm.onStartGamePressed()
        vm.onPause()
        blasterEvents.tryEmit(BlasterEvent.Disconnected)
        verify(screen).requestConnect()
    }

    @Test
    fun connectStatusPopup_showsWhileConnecting_andClosesOnFirstPacket() {
        blasterEvents.tryEmit(BlasterEvent.ServicesDiscovered)
        assertEquals(ConnectStatus.COMMUNICATING, vm.connectStatus)
        verify(screen).showConnectStatus(ConnectStatus.COMMUNICATING)

        engine.onBlasterEvent(BlasterEvent.Connected("AA"))
        engine.onBlasterEvent(BlasterEvent.Telemetry(com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
            .parseTelemetry(com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_IDLE)!!))
        scope.testScheduler.runCurrent()
        assertNull(vm.connectStatus)
        verify(screen).showConnectStatus(null)
    }

    @Test
    fun connectStatusPopup_closesWhenTheLinkIsLost() {
        blasterEvents.tryEmit(BlasterEvent.ServicesDiscovered)
        blasterEvents.tryEmit(BlasterEvent.Disconnected)
        assertNull(vm.connectStatus)
        verify(screen).showConnectStatus(null)
    }

    @Test
    fun connectStatusPopup_cancel_returnsToConnectScreenQuietly() {
        blasterEvents.tryEmit(BlasterEvent.ServicesDiscovered)
        vm.onConnectCancelled()
        assertNull(vm.connectStatus)
        verify(screen).showConnectStatus(null)
        verify(screen).setConnectButtonsEnabled(true)
        assertTrue(sounds.isEmpty())
    }

    @Test
    fun connectStatusPopup_staysClosedBehindTheDisconnectDialog() {
        vm.onStartGamePressed()
        blasterEvents.tryEmit(BlasterEvent.Disconnected)
        blasterEvents.tryEmit(BlasterEvent.ServicesDiscovered)
        assertNull(vm.connectStatus)
        verify(screen, never()).showConnectStatus(ConnectStatus.COMMUNICATING)
    }

    @Test
    fun disconnectQuit_endsGameAndNetworkAndReturnsToConnectScreen() {
        net.useNetwork = true
        vm.onStartGamePressed()
        vm.onDisconnectQuit()
        assertEquals(GameStateRepository.GAME_STATE_NONE, gameState.gameState)
        assertFalse(net.useNetwork)
        assertNull(vm.connectStatus)
        verify(client, org.mockito.Mockito.atLeastOnce()).leaveServer()
    }

    @Test
    fun disconnectQuit_closesAnOpenScoreboard() {
        net.useNetwork = true
        vm.openScoreboard()
        vm.onDisconnectQuit()
        assertFalse(vm.scoreboardVisible)
        verify(screen).closeScoreboard()
    }

    @Test
    fun qrPayload_setsAddressAndNameThenConnects() {
        vm.onQrScanned("AA:BB:CC:DD:EE:FF-Bob")
        assertEquals("AA:BB:CC:DD:EE:FF", vm.deviceAddress)
        assertEquals("Bob", players.playerName)
        assertEquals("Bob", prefs.values["PlayerName"])
        verify(screen).requestConnect()
    }

    @Test
    fun generateQr_withoutABlasterAddress_saysToConnectFirst() {
        vm.onGenerateQrPressed()
        verify(screen).showMessage(GameMessage.QR_NO_BLASTER, 0)
        verify(screen, never()).showQrNameDialog(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun generateQr_offersTheCurrentName_andEncodesTheEnteredOne() {
        vm.onQrScanned("AA:BB:CC:DD:EE:FF")
        vm.onGenerateQrPressed()
        verify(screen).showQrNameDialog("Tester")
        vm.onQrNameEntered("  Mary-Jane ")
        verify(screen).showQrCode("AA:BB:CC:DD:EE:FF-Mary-Jane", "Mary-Jane")
        vm.onQrNameEntered("   ")
        verify(screen).showQrCode("AA:BB:CC:DD:EE:FF", "")
        assertEquals("Tester", players.playerName) // the label's name isn't this player's
    }

    @Test
    fun generateQr_ignoredDuringAGame() {
        vm.onQrScanned("AA:BB:CC:DD:EE:FF")
        vm.onStartGamePressed()
        vm.onGenerateQrPressed()
        verify(screen, never()).showQrNameDialog(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun reconnectButton_withoutSavedAddress_hidesItself() {
        vm.onReconnectPressed()
        verify(screen, never()).requestConnect()
        prefs.values["DeviceAddress"] = "11:22:33:44:55:66"
        vm.onReconnectPressed()
        verify(screen).requestConnect()
    }

    // --------------------------------------------------------------------------------------
    // Network session
    // --------------------------------------------------------------------------------------

    @Test
    fun firstPacket_autoStartsServerSearch_onWifi() {
        engine.onBlasterEvent(BlasterEvent.Connected("AA"))
        engine.onBlasterEvent(BlasterEvent.Telemetry(com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
            .parseTelemetry(com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_IDLE)!!))
        scope.testScheduler.runCurrent()
        verify(screen).showPlayScreen()
        assertTrue(net.useNetwork)
        assertEquals(GameViewModel.NETWORK_TYPE_JOINING, vm.networkMenuType)
        verify(udp).joinServer()
        // No server yet: the search restarts every 10 s.
        advance(10_000)
        verify(udp, times(2)).joinServer()
    }

    @Test
    fun serverSearch_stopsWhilePaused_andRestartsOnResume() {
        vm.onNetworkButtonPressed() // searching
        verify(udp).joinServer()
        vm.onPause()
        verify(udp).endScanning()
        emitNetwork(ClientEvent.FailedToJoin)
        advance(60_000)
        verify(udp).joinServer() // no re-scans in the background

        vm.onResume()
        verify(udp, times(2)).joinServer()
        advance(10_000)
        verify(udp, times(3)).joinServer()
    }

    @Test
    fun firstPacket_withoutWifi_staysOffline() {
        wifi = false
        engine.onBlasterEvent(BlasterEvent.Connected("AA"))
        engine.onBlasterEvent(BlasterEvent.Telemetry(com.simplecoil.simplecoil.hardware.blaster.GattProtocolHandler
            .parseTelemetry(com.simplecoil.simplecoil.hardware.blaster.RecoilProtocolFixtures.GOLDEN_IDLE)!!))
        scope.testScheduler.runCurrent()
        assertFalse(net.useNetwork)
        verify(udp, never()).joinServer()
        // Set up like the menu's Disable Network: the offline lobby controls show.
        val order = inOrder(screen)
        order.verify(screen).showPlayScreen()
        order.verify(screen).showNetworkOptions(false, false)
        order.verify(screen).showNetworkButton(NetworkButton.USE_NETWORK)
        order.verify(screen).showPreGameButtons(PreGameButtons(
            ready = false, offline = true, joined = false, startVisible = true, serverSettingsOnly = false))

        // A later UDP service (re)bind doesn't search either.
        emitNetwork(ClientEvent.UdpServiceConnected)
        verify(udp, never()).joinServer()
    }

    @Test
    fun networkButton_withoutWifi_stillEnablesNetwork() {
        wifi = false
        vm.onNetworkButtonPressed()
        assertTrue(net.useNetwork)
        assertEquals(GameViewModel.NETWORK_TYPE_JOINING, vm.networkMenuType)
        verify(udp).joinServer()
        verify(screen).showNetworkMenu()
    }

    @Test
    fun join_thenServerReply_connectsTcpAndShowsJoined() {
        vm.onNetworkButtonPressed() // enable network (searching)
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        assertTrue(vm.ready)
        val order = inOrder(udp)
        order.verify(udp, times(2)).joinServer()

        emitNetwork(ClientEvent.ServerReply(7))
        assertEquals(7.toByte(), players.playerID)
        assertEquals(GameViewModel.NETWORK_TYPE_JOINED, vm.networkMenuType)
        verify(screen).showNetworkButton(NetworkButton.CONNECTED)
        // Joined: no more automatic re-scans.
        advance(30_000)
        verify(udp, times(2)).joinServer()
    }

    @Test
    fun joined_teamLabelOffersTheOtherTeams_networkMenuDoesNot() {
        settings.isAllowChooseTeams = true
        vm.onNetworkButtonPressed()
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        emitNetwork(ClientEvent.ServerReply(1))
        vm.onTeamModeSelected(TeamMode.TWO_TEAMS)
        val entries = listOf(SwitchTeamEntry(2, TeamColors.colorOf(2)))
        assertEquals(entries, vm.switchTeamEntries())
        assertEquals(listOf(NetworkMenuEntry.PlayerName("Tester"), NetworkMenuEntry.DisableNetwork), vm.networkMenuEntries())
        vm.onTeamLabelPressed()
        verify(screen).showSwitchTeamMenu(entries)

        settings.isAllowChooseTeams = false
        assertEquals(emptyList<SwitchTeamEntry>(), vm.switchTeamEntries())
    }

    @Test
    fun rejoinServerReply_whileJoined_doesNotShowSearchingAgain() {
        vm.onNetworkButtonPressed()
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        emitNetwork(ClientEvent.ServerReply(7))
        clearInvocations(screen)

        // Back from the background mid-game: the hello's reply must leave the label alone.
        gameState.gameState = GameStateRepository.GAME_STATE_RUNNING
        vm.onPause()
        vm.onResume()
        emitNetwork(ClientEvent.ServerReply(7))
        verify(screen, never()).showServerSearching()
        assertEquals(GameViewModel.NETWORK_TYPE_JOINED, vm.networkMenuType)
    }

    @Test
    fun gameModePressed_opensTheMenuOnlyBeforeAGameWhenNotOnAServer() {
        vm.onTeamModePressed()
        verify(screen).showTeamModeMenu()

        gameState.gameState = GameStateRepository.GAME_STATE_RUNNING
        vm.onTeamModePressed()
        verify(screen, times(1)).showTeamModeMenu()

        gameState.gameState = GameStateRepository.GAME_STATE_NONE
        vm.onNetworkButtonPressed()
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        emitNetwork(ClientEvent.ServerReply(7))
        assertEquals(GameViewModel.NETWORK_TYPE_JOINED, vm.networkMenuType)
        vm.onTeamModePressed()
        verify(screen, times(1)).showTeamModeMenu()
    }

    @Test
    fun gameModeSelected_fourTeams_splitsPlayerIdsIntoTeamsOfEight() {
        vm.onTeamModeSelected(TeamMode.FOUR_TEAMS)
        assertEquals(1, players.calcNetworkTeam(8))
        assertEquals(2, players.calcNetworkTeam(9))
        assertEquals(4, players.calcNetworkTeam(32))
        vm.onTeamModeSelected(TeamMode.TWO_TEAMS)
        assertEquals(1, players.calcNetworkTeam(16))
        assertEquals(2, players.calcNetworkTeam(17))
    }

    @Test
    fun networkMenuEntries_followTheMenuState() {
        assertEquals(listOf(NetworkMenuEntry.Join, NetworkMenuEntry.JoinIp, NetworkMenuEntry.PlayerName("Tester"),
            NetworkMenuEntry.DisableNetwork), vm.networkMenuEntries())
        vm.onNetworkButtonPressed()
        assertEquals(listOf(NetworkMenuEntry.JoinIp, NetworkMenuEntry.DisableNetwork), vm.networkMenuEntries())
    }

    @Test
    fun serverStartsAndEndsGame_networkGameShowsScoreboardAtTheEnd() {
        net.useNetwork = true
        emitNetwork(ClientEvent.StartGame)
        verify(screen).showGameStarted(true, false, false)
        emitNetwork(ClientEvent.EndGame)
        assertEquals(GameStateRepository.GAME_STATE_NONE, gameState.gameState)
        verify(client).sendTCPMessage("SimpleCoil:07MESGPLAYERDATAREQUEST")
    }

    @Test
    fun eliminatingSomeone_scoresAndTellsTheTeam() {
        net.useNetwork = true
        players.playerID = 1
        emitNetwork(ClientEvent.Eliminated(2))
        assertEquals(1, net.score)
        assertEquals(1, net.teamScore)
        verify(screen).showScore(1)
        verify(client).sendTCPMessage("SimpleCoil:07MESGTEAMELIMINATED")
        assertEquals(listOf(SessionSound.SCORE), sounds)
    }

    @Test
    fun localScoreLimit_endsTheGame() {
        vm.onGameLimitsEntered(mapOf(LimitKind.SCORE to "1"))
        vm.onStartGamePressed()
        emitNetwork(ClientEvent.Eliminated(2))
        assertEquals(GameStateRepository.GAME_STATE_NONE, gameState.gameState)
    }

    @Test
    fun serverCancel_kicksBackToLobby() {
        vm.onNetworkButtonPressed()
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        emitNetwork(ClientEvent.ServerCancel)
        assertFalse(vm.ready)
        assertEquals(GameViewModel.NETWORK_TYPE_ENABLED, vm.networkMenuType)
        verify(screen).showMessage(GameMessage.KICKED_BY_SERVER, 0)
    }

    @Test
    fun teamSizes_validateLikeTheDialogDid() {
        assertFalse(vm.onTeamSizesEntered(listOf("16", "")))
        verify(screen).showMessage(GameMessage.RATIOS_MIN_ONE, 0)
        assertFalse(vm.onTeamSizesEntered(listOf("10", "10")))
        verify(screen).showMessage(GameMessage.RATIOS_TOTAL, 20)
        assertTrue(vm.onTeamSizesEntered(listOf("20", "12")))
        verify(client).sendTCPMessage("SimpleCoil:07MESGADMIN_TEAMSIZES20,12")
    }

    private fun joinServer() {
        vm.onNetworkButtonPressed()
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        emitNetwork(ClientEvent.ServerReply(7))
    }

    private fun serverPhase(phase: SessionPhase, gameId: Long? = 42) =
        emitNetwork(ClientEvent.ListPlayers(null, null, phase.wireValue, if (phase == SessionPhase.LOBBY) null else gameId))

    @Test
    fun adminActions_followTheServerPhase_andResetTakesStartGamesPlace() {
        joinServer()
        val settings = listOf(AdminAction.TWO_TEAMS, AdminAction.FOUR_TEAMS, AdminAction.FREE_FOR_ALL, AdminAction.SHUFFLE_TEAMS)
        assertEquals(listOf(AdminAction.START_GAME) + settings, vm.adminActions())
        serverPhase(SessionPhase.RUNNING)
        assertEquals(listOf(AdminAction.END_GAME), vm.adminActions())
        serverPhase(SessionPhase.POST_GAME)
        assertEquals(listOf(AdminAction.RESET_GAME) + settings, vm.adminActions())
        serverPhase(SessionPhase.POST_GAME) // unchanged: no rebuild
        verify(screen, times(2)).rebuildAdminMenu()
        serverPhase(SessionPhase.LOBBY) // the server was reset (Q13)
        assertEquals(listOf(AdminAction.START_GAME) + settings, vm.adminActions())
        verify(screen, times(3)).rebuildAdminMenu()
    }

    @Test
    fun resetGame_confirmsWithTheDisconnectedCount_thenSendsAdminReset() {
        joinServer()
        serverPhase(SessionPhase.POST_GAME)
        val away = PlayerDisplayData().apply { isConnected = false }
        val here = PlayerDisplayData().apply { isConnected = true }
        emitNetwork(ClientEvent.PlayerData(arrayOf(null, here, away)))
        vm.onAdminAction(AdminAction.RESET_GAME)
        verify(screen).confirmReset(1)
        verify(client, never()).sendTCPMessage("SimpleCoil:07MESGADMIN_RESET")
        vm.onResetConfirmed()
        verify(client).sendTCPMessage("SimpleCoil:07MESGADMIN_RESET")
    }

    @Test
    fun teamModeAndSortAfterAGame_sendTheUsualAdminMessages() {
        joinServer()
        serverPhase(SessionPhase.POST_GAME)
        vm.onAdminAction(AdminAction.FREE_FOR_ALL)
        vm.onAdminAction(AdminAction.SHUFFLE_TEAMS)
        verify(client).sendTCPMessage("SimpleCoil:07MESGADMIN_GAMEMODE" + TeamMode.FREE_FOR_ALL.wireValue)
        verify(client).sendTCPMessage("SimpleCoil:07MESGADMIN_SORTTEAMS")
        verify(screen, never()).confirmReset(org.mockito.ArgumentMatchers.anyInt())
    }

    @Test
    fun serverResetAfterAGame_zeroesTheShownScore() {
        joinServer()
        serverPhase(SessionPhase.POST_GAME)
        net.score = 5
        net.teamScore = 7
        serverPhase(SessionPhase.LOBBY)
        assertEquals(0, net.score)
        assertEquals(0, net.teamScore)
        verify(screen).showTeamScore(0)
    }

    @Test
    fun resume_restoresTheSavedPlayerName() {
        prefs.values["PlayerName"] = "Saved"
        vm.onResume()
        assertEquals("Saved", players.playerName)
    }

    @Test
    fun localPlayerSettings_areKeptSentAndApplied() {
        val values = vm.localPlayerSettings().copy(health = 40, shots = 12, damage = -2)
        vm.onLocalSettingsSaved(values)
        assertEquals(40, settings.fullHealth)
        assertEquals(12.toByte(), settings.fullReload)
        assertEquals(-2, settings.damage)
        verify(client).sendPlayerSettings()
        verify(screen).showPlayerSettings(40, 12.toByte(), 2)
    }

    // --------------------------------------------------------------------------------------
    // Score matrix
    // --------------------------------------------------------------------------------------

    private val matrixRequest = "SimpleCoil:07MESGSCOREMATRIXREQUEST"

    private fun matrixReply(gameId: Long? = null, vararg cells: WireMatrixCell) = ClientEvent.ScoreMatrix(ServerMessage.ScoreMatrix(
        listOf(WireMatrixPlayer(1, "Tester", 1, true), WireMatrixPlayer(17, "Bravo", 2, true)), cells.toList(), gameId))

    /** Joined, the scoreboard open on its matrix face. */
    private fun openMatrix() {
        net.useNetwork = true
        players.playerID = 1
        vm.openScoreboard()
        vm.onScoreMatrixToggled()
    }

    @Test
    fun scoreMatrix_toggleShowsLoadingAndAsks_thenTheReply() {
        openMatrix()
        assertTrue(vm.scoreMatrixVisible)
        verify(screen).showScoreMatrix(ScoreMatrixState.Loading)
        verify(client, times(1)).sendTCPMessage(matrixRequest)
        emitNetwork(matrixReply(null, WireMatrixCell(17, 1, 2)))
        val ready = vm.scoreMatrixState as ScoreMatrixState.Ready
        assertEquals(2, ready.matrix.counts[1][0])
        assertTrue(ready.matrix.players[0].isSelf)
        verify(screen).showScoreMatrix(ready)
        // Back to the list: no new request, and the matrix is kept for the next toggle
        vm.onScoreMatrixToggled()
        assertFalse(vm.scoreMatrixVisible)
        assertEquals(ready, vm.scoreMatrixState)
    }

    @Test
    fun scoreMatrix_noReply_isUnsupported_butALateReplyStillShows() {
        openMatrix()
        advance(GameViewModel.SCORE_MATRIX_TIMEOUT_MS)
        assertEquals(ScoreMatrixState.Unsupported, vm.scoreMatrixState)
        verify(screen).showScoreMatrix(ScoreMatrixState.Unsupported)
        emitNetwork(matrixReply())
        assertTrue(vm.scoreMatrixState is ScoreMatrixState.Ready)
    }

    @Test
    fun scoreMatrix_aTimeoutOnARefresh_keepsTheLastMatrix() {
        openMatrix()
        emitNetwork(matrixReply())
        advance(GameViewModel.SCORE_MATRIX_MIN_INTERVAL_MS)
        emitNetwork(ClientEvent.TeamEliminated) // refresh, never answered
        verify(client, times(2)).sendTCPMessage(matrixRequest)
        advance(GameViewModel.SCORE_MATRIX_TIMEOUT_MS)
        assertTrue(vm.scoreMatrixState is ScoreMatrixState.Ready)
    }

    @Test
    fun scoreMatrix_aBurstOfTriggers_sendsOneNowAndOneWhenTheIntervalEnds() {
        openMatrix()
        emitNetwork(matrixReply())
        emitNetwork(ClientEvent.Eliminated(17))
        emitNetwork(ClientEvent.TeamEliminated)
        emitNetwork(ClientEvent.PlayerData(arrayOfNulls(GameSettingsRepository.MAX_PLAYER_ID + 2)))
        verify(client, times(1)).sendTCPMessage(matrixRequest)
        advance(GameViewModel.SCORE_MATRIX_MIN_INTERVAL_MS)
        verify(client, times(2)).sendTCPMessage(matrixRequest)
        advance(GameViewModel.SCORE_MATRIX_MIN_INTERVAL_MS * 2)
        verify(client, times(2)).sendTCPMessage(matrixRequest)
    }

    @Test
    fun scoreMatrix_leavingTheMatrixFace_cancelsThePendingRequest() {
        openMatrix()
        emitNetwork(ClientEvent.Eliminated(17))
        vm.onScoreMatrixToggled() // back to the list
        vm.onScoreMatrixToggled() // and quickly back: the cached state, nothing new sent
        vm.onScoreMatrixToggled()
        advance(GameViewModel.SCORE_MATRIX_MIN_INTERVAL_MS)
        verify(client, times(1)).sendTCPMessage(matrixRequest)
    }

    @Test
    fun scoreMatrix_refreshesOnlyWhileTheMatrixFaceIsShowing() {
        net.useNetwork = true
        vm.openScoreboard()
        emitNetwork(ClientEvent.Eliminated(17))
        advance(GameViewModel.SCORE_MATRIX_MIN_INTERVAL_MS)
        verify(client, never()).sendTCPMessage(matrixRequest)
    }

    @Test
    fun scoreMatrix_forAnotherGame_isDropped_andANewGameIdClearsIt() {
        prefs.values["PlayerID"] = 1
        vm.onNetworkButtonPressed()
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        emitNetwork(ClientEvent.ServerReply(1))
        emitNetwork(ClientEvent.ListPlayers(null, 0, SessionPhase.POST_GAME.wireValue, 41))
        openMatrix()
        emitNetwork(matrixReply(40)) // crossed a new game
        assertEquals(ScoreMatrixState.Loading, vm.scoreMatrixState)
        emitNetwork(matrixReply(41, WireMatrixCell(1, 17, 1)))
        assertTrue(vm.scoreMatrixState is ScoreMatrixState.Ready)
        // The server was reset: the last game's matrix is gone, and an open matrix asks again
        advance(GameViewModel.SCORE_MATRIX_MIN_INTERVAL_MS)
        emitNetwork(ClientEvent.ListPlayers(null, 0, SessionPhase.LOBBY.wireValue, 0))
        assertEquals(ScoreMatrixState.Loading, vm.scoreMatrixState)
        verify(client, times(2)).sendTCPMessage(matrixRequest)
        emitNetwork(matrixReply(null))
        assertTrue((vm.scoreMatrixState as ScoreMatrixState.Ready).matrix.isEmpty)
    }

    @Test
    fun scoreMatrix_closingTheScoreboardResetsIt_butARecreationKeepsTheFace() {
        openMatrix()
        emitNetwork(matrixReply())
        vm.onScreenDestroyed()
        assertTrue(vm.scoreMatrixVisible)
        vm.screen = screen
        advance(GameViewModel.SCORE_MATRIX_MIN_INTERVAL_MS)
        vm.openScoreboard() // the recreated dialog
        verify(client, times(2)).sendTCPMessage(matrixRequest)
        vm.onScoreboardDismissed()
        assertFalse(vm.scoreMatrixVisible)
        assertNull(vm.scoreMatrixState)
    }

    private fun emitNetwork(event: ClientEvent) {
        net.emit(event)
        scope.testScheduler.runCurrent()
    }
}
