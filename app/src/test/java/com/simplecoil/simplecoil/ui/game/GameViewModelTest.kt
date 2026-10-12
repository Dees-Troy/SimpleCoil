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
import android.content.SharedPreferences
import android.content.ServiceConnection
import com.simplecoil.simplecoil.Globals
import com.simplecoil.simplecoil.NetworkEvent
import com.simplecoil.simplecoil.NetworkSessionGateway
import com.simplecoil.simplecoil.TcpClient
import com.simplecoil.simplecoil.TcpServer
import com.simplecoil.simplecoil.UDPListenerService
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.domain.session.BlasterSessionEngine
import com.simplecoil.simplecoil.domain.session.GlobalsGameConfig
import com.simplecoil.simplecoil.domain.session.Recorder
import com.simplecoil.simplecoil.domain.session.SessionSound
import com.simplecoil.simplecoil.hardware.audio.SoundEffectPlayer
import com.simplecoil.simplecoil.hardware.blaster.BlasterEvent
import com.simplecoil.simplecoil.hardware.blaster.BlasterManager
import com.simplecoil.simplecoil.hardware.blaster.BleConnectionManager
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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
    private lateinit var server: TcpServer
    private lateinit var vm: GameViewModel
    private var wifi = true
    private var gpsBroadcasts = 0

    /** In-memory SharedPreferences. */
    private class MemoryPrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()
        override fun getAll() = values
        override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String) = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            override fun putString(key: String, value: String?) = apply { values[key] = value }
            override fun putStringSet(key: String, value: MutableSet<String>?) = apply { values[key] = value }
            override fun putInt(key: String, value: Int) = apply { values[key] = value }
            override fun putLong(key: String, value: Long) = apply { values[key] = value }
            override fun putFloat(key: String, value: Float) = apply { values[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { values[key] = value }
            override fun remove(key: String) = apply { values.remove(key) }
            override fun clear() = apply { values.clear() }
            override fun commit() = true
            override fun apply() = Unit
        }
    }

    private val prefs = MemoryPrefs()

    @Before
    fun setUp() {
        Dispatchers.setMain(main)
        val g = Globals.getInstance()
        g.mGameState = Globals.GAME_STATE_NONE
        g.mGameMode = Globals.GAME_MODE_2TEAMS
        g.mGameLimit = Globals.GAME_LIMIT_NONE
        g.mPlayerID = 0
        g.mOnlyServerSettings = false
        g.mIsAdmin = false
        g.mPlayerName = "Tester"
        engine = BlasterSessionEngine(rec, GlobalsGameConfig, rec, scope, clock = { scope.testScheduler.currentTime })
        net = NetworkSessionGateway(mock(Context::class.java), scope)
        udp = mock(UDPListenerService::class.java)
        client = mock(TcpClient::class.java)
        server = mock(TcpServer::class.java)
        net.attachForTest(udp, client, server)
        engine.network = net
        val binder = object : BleConnectionManager.ServiceBinder {
            override fun start() = Unit
            override fun bind(connection: ServiceConnection) = Unit
            override fun unbind(connection: ServiceConnection) = Unit
        }
        ble = BleConnectionManager(mock(Context::class.java), BlasterManager(scope), scope, binder, { true }, { })
        screen = mock(GameScreen::class.java)
        vm = GameViewModel(engine, net, ble, blasterEvents, UserPreferencesRepository(prefs),
            object : SoundEffectPlayer { override fun play(sound: SessionSound) { sounds += sound } },
            wifiConnected = { wifi }, broadcastGpsSetting = { gpsBroadcasts++ })
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
        assertEquals(1.toByte(), Globals.getInstance().mPlayerID)
        assertEquals(Globals.GAME_STATE_ELIMINATED, Globals.getInstance().mGameState) // spawning
        verify(screen).showGameStarted(false, false, false)
        assertTrue(rec.ble.contains("team:1"))
        assertEquals(1, prefs.values["PlayerID"])
    }

    @Test
    fun endGameConfirmed_locally_endsSessionAndShowsLobby() {
        vm.onStartGamePressed()
        vm.onEndGameConfirmed()
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState)
        verify(screen).showGameEnded(false, false)
        verify(client, never()).sendTCPMessage("SimpleCoil:06MESGPLAYERDATAREQUEST") // no scoreboard after a local game
    }

    @Test
    fun teamButtons_wrapAroundAndPersist() {
        Globals.getInstance().mPlayerID = 1
        vm.onTeamMinusPressed()
        assertEquals(Globals.MAX_PLAYER_ID, Globals.getInstance().mPlayerID)
        vm.onTeamPlusPressed()
        assertEquals(1.toByte(), Globals.getInstance().mPlayerID)
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
    fun gameLimit_parsesAndRejectsTooHigh() {
        vm.onGameLimitEntered("10", GameViewModel.LimitKind.TIME)
        assertEquals(Globals.GAME_LIMIT_TIME, Globals.getInstance().mGameLimit)
        assertEquals(10, prefs.values["TimeLimit"])
        verify(screen).showGameLimit(10, false, null)
        vm.onGameLimitEntered("101", GameViewModel.LimitKind.LIVES)
        verify(screen).showMessage(GameMessage.LIMIT_TOO_HIGH, 0)
        vm.onGameLimitEntered("abc", GameViewModel.LimitKind.LIVES)
        assertEquals(Globals.GAME_LIMIT_TIME, Globals.getInstance().mGameLimit)
    }

    // --------------------------------------------------------------------------------------
    // Connection
    // --------------------------------------------------------------------------------------

    @Test
    fun linkLost_beforeEverConnecting_resetsToConnectScreen() {
        blasterEvents.tryEmit(BlasterEvent.Disconnected)
        verify(screen).setConnectButtonsEnabled(true)
        verify(screen).showConnectStatus(ConnectStatus.NOT_CONNECTED)
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
    fun disconnectQuit_endsGameAndNetworkAndReturnsToConnectScreen() {
        net.useNetwork = true
        vm.onStartGamePressed()
        vm.onDisconnectQuit()
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState)
        assertFalse(net.useNetwork)
        verify(screen).showConnectStatus(ConnectStatus.NOT_CONNECTED)
        verify(client, org.mockito.Mockito.atLeastOnce()).leaveServer()
    }

    @Test
    fun qrPayload_setsAddressAndNameThenConnects() {
        vm.onQrScanned("AA:BB:CC:DD:EE:FF-Bob")
        assertEquals("AA:BB:CC:DD:EE:FF", vm.deviceAddress)
        assertEquals("Bob", Globals.getInstance().mPlayerName)
        assertEquals("Bob", prefs.values["PlayerName"])
        verify(screen).requestConnect()
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
    fun networkButton_withoutWifi_refuses() {
        wifi = false
        vm.onNetworkButtonPressed()
        verify(screen).showMessage(GameMessage.NO_WIFI, 0)
        assertFalse(net.useNetwork)
        verify(screen, never()).showNetworkMenu()
    }

    @Test
    fun join_thenServerReply_connectsTcpAndShowsJoined() {
        vm.onNetworkButtonPressed() // enable network (searching)
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        assertTrue(vm.ready)
        val order = inOrder(udp)
        order.verify(udp, times(2)).joinServer()

        emitNetwork(NetworkEvent.ServerReply(7))
        assertEquals(7.toByte(), Globals.getInstance().mPlayerID)
        assertEquals(GameViewModel.NETWORK_TYPE_JOINED, vm.networkMenuType)
        verify(screen).showNetworkButton(NetworkButton.CONNECTED)
        // Joined: no more automatic re-scans.
        advance(30_000)
        verify(udp, times(2)).joinServer()
    }

    @Test
    fun networkMenuEntries_followTheMenuState() {
        assertEquals(listOf(NetworkMenuEntry.Join, NetworkMenuEntry.JoinIp, NetworkMenuEntry.PlayerName("Tester"),
            NetworkMenuEntry.GameMode, NetworkMenuEntry.DisableNetwork), vm.networkMenuEntries())
        vm.onNetworkButtonPressed()
        assertEquals(listOf(NetworkMenuEntry.JoinIp, NetworkMenuEntry.DisableNetwork), vm.networkMenuEntries())
    }

    @Test
    fun serverStartsAndEndsGame_networkGameShowsScoreboardAtTheEnd() {
        net.useNetwork = true
        emitNetwork(NetworkEvent.StartGame)
        verify(screen).showGameStarted(true, false, false)
        verify(udp).startGame()
        emitNetwork(NetworkEvent.EndGame)
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState)
        verify(client).sendTCPMessage("SimpleCoil:06MESGPLAYERDATAREQUEST")
    }

    @Test
    fun eliminatingSomeone_scoresAndTellsTheTeam() {
        net.useNetwork = true
        Globals.getInstance().mPlayerID = 1
        emitNetwork(NetworkEvent.Eliminated(2))
        assertEquals(1, net.score)
        assertEquals(1, net.teamScore)
        verify(screen).showScore(1)
        verify(client).sendTCPMessage("SimpleCoil:06MESGTEAMELIMINATED")
        assertEquals(listOf(SessionSound.SCORE), sounds)
    }

    @Test
    fun localScoreLimit_endsTheGame() {
        vm.onGameLimitEntered("1", GameViewModel.LimitKind.SCORE)
        vm.onStartGamePressed()
        emitNetwork(NetworkEvent.Eliminated(2))
        assertEquals(Globals.GAME_STATE_NONE, Globals.getInstance().mGameState)
    }

    @Test
    fun serverCancel_kicksBackToLobby() {
        vm.onNetworkButtonPressed()
        vm.onNetworkMenuEntry(NetworkMenuEntry.Join)
        emitNetwork(NetworkEvent.ServerCancel)
        assertFalse(vm.ready)
        assertEquals(GameViewModel.NETWORK_TYPE_ENABLED, vm.networkMenuType)
        verify(screen).showMessage(GameMessage.KICKED_BY_SERVER, 0)
    }

    @Test
    fun teamSizes_validateLikeTheDialogDid() {
        net.isServer = true
        assertFalse(vm.onTeamSizesEntered(listOf("16", "")))
        verify(screen).showMessage(GameMessage.RATIOS_MIN_ONE, 0)
        assertFalse(vm.onTeamSizesEntered(listOf("10", "10")))
        verify(screen).showMessage(GameMessage.RATIOS_TOTAL, 20)
        assertTrue(vm.onTeamSizesEntered(listOf("20", "12")))
        verify(server).rebalanceAllPlayers(false)
    }

    @Test
    fun adminActions_dependOnGameState() {
        assertEquals(AdminAction.START_GAME, vm.adminActions().first())
        vm.onStartGamePressed()
        assertEquals(listOf(AdminAction.END_GAME), vm.adminActions())
    }

    @Test
    fun resume_rebindsNetworkAndReconnectsBlaster() {
        vm.onResume()
        verify(screen, times(2)).showPlayerName("Tester")
    }

    private fun emitNetwork(event: NetworkEvent) {
        net.emit(event)
        scope.testScheduler.runCurrent()
    }
}
