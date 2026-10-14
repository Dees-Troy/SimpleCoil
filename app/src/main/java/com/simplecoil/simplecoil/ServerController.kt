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

package com.simplecoil.simplecoil

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import com.simplecoil.simplecoil.data.model.PlayerSettings
import com.simplecoil.simplecoil.data.preferences.UserPreferencesRepository
import com.simplecoil.simplecoil.data.repository.GameSettingsRepository
import com.simplecoil.simplecoil.domain.rules.Limits
import com.simplecoil.simplecoil.domain.team.TeamMode
import com.simplecoil.simplecoil.network.server.GameServer
import com.simplecoil.simplecoil.network.server.SavedServerSettings
import com.simplecoil.simplecoil.network.server.ServerSnapshot
import com.simplecoil.simplecoil.ui.playersettings.PlayerSettingsValues
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The dedicated server as the UI sees it (dedicated server design §5.1): starts and stops it,
 * and passes the operator's commands on. The server screen, its view model and the
 * notification's Stop action all go through it, so the binding to the service is owned in one
 * place (S4). Main thread.
 *
 * Commands sent while the server isn't running (or is still binding) are dropped, and those
 * that answer return false.
 */
interface ServerController {
    /** The server's state; null while it isn't running. */
    val snapshot: StateFlow<ServerSnapshot?>

    /** From [start] until [stop]. */
    val isRunning: StateFlow<Boolean>

    /** Starts the server, unless it is already running: the screen then just shows it again (R4). */
    fun start()

    /** Kicks every player, stops the beacon and the server, and lets the service go. */
    fun stop()

    /** The server screen is in the foreground: keep Wi-Fi awake (§5.6). */
    fun setScreenVisible(visible: Boolean)

    /** Starts a game from the lobby; false if not in the lobby or nobody is there. */
    fun startGame(): Boolean
    fun endGame()

    /** Reset after a game; false outside POST_GAME. */
    fun resetSession(): Boolean

    // The settings: true if the change reset a finished game first.
    fun changeTeamMode(mode: TeamMode): Boolean
    fun sortTeams(): Boolean
    fun changeTeamSizes(sizes: IntArray): Boolean
    fun changeLimits(limits: Limits): Boolean
    fun changeStartTime(seconds: Long): Boolean
    fun changeGpsMode(mode: Int): Boolean

    fun setAllowJoinInProgress(allow: Boolean)
    fun setOnlyServerSettings(only: Boolean)
    fun setAllowChooseTeams(allow: Boolean)

    /** [playerId]'s settings, or the defaults if it has none. */
    fun playerSettings(playerId: Byte): PlayerSettingsValues

    /** Stores, moves, copies and sends a player's settings (S11); false if [targetTeam] (0: stay) is full. */
    fun applyPlayerSettings(playerId: Byte, values: PlayerSettingsValues, applyAll: Boolean, applyTeam: Boolean, targetTeam: Int): Boolean

    fun kickPlayer(playerId: Byte)
}

/** [ServerController] over the [TcpServer] service, which it starts and binds while the server runs. */
class TcpServerController(
    private val app: Application,
    /** Main-thread scope, for forwarding the snapshot. */
    private val scope: CoroutineScope,
    private val preferences: () -> UserPreferencesRepository,
) : ServerController {

    private val _snapshot = MutableStateFlow<ServerSnapshot?>(null)
    override val snapshot: StateFlow<ServerSnapshot?> = _snapshot.asStateFlow()

    private val _running = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _running.asStateFlow()

    private var connection: ServiceConnection? = null
    private var service: TcpServer? = null
    private var forwarding: Job? = null
    private var screenVisible = false

    private val server: GameServer? get() = service?.server

    override fun start() {
        if (connection != null) return
        _running.value = true
        val intent = Intent(app, TcpServer::class.java)
        try {
            // Started as well as bound, so it outlives the screen; it goes foreground once serving.
            app.startService(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not start the server service: " + e.message)
        }
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (connection !== this) return // stopped while binding
                val bound = (binder as TcpServer.LocalBinder).service
                service = bound
                bound.startServer(savedSettings())
                bound.setScreenVisible(screenVisible)
                forwarding = scope.launch { bound.server.snapshot.collect { _snapshot.value = it } }
            }

            override fun onServiceDisconnected(name: ComponentName) {
                // The service runs in this process, so this only follows a crash that took it down
                forwarding?.cancel()
                service = null
            }
        }
        connection = conn
        app.bindService(intent, conn, Context.BIND_AUTO_CREATE)
    }

    override fun stop() {
        val conn = connection ?: return
        service?.stopServer()
        forwarding?.cancel()
        forwarding = null
        connection = null
        service = null
        app.unbindService(conn)
        app.stopService(Intent(app, TcpServer::class.java))
        _snapshot.value = null
        _running.value = false
    }

    override fun setScreenVisible(visible: Boolean) {
        screenVisible = visible
        service?.setScreenVisible(visible)
    }

    override fun startGame(): Boolean = server?.startGame() ?: false

    override fun endGame() {
        server?.endGame()
    }

    override fun resetSession(): Boolean = server?.resetSession() ?: false

    override fun changeTeamMode(mode: TeamMode): Boolean = server?.changeTeamMode(mode) ?: false

    override fun sortTeams(): Boolean = server?.sortTeams() ?: false

    override fun changeTeamSizes(sizes: IntArray): Boolean = server?.changeTeamSizes(sizes) ?: false

    override fun changeLimits(limits: Limits): Boolean =
        server?.changeLimits(limits.gameLimit, limits.timeLimit, limits.livesLimit, limits.scoreLimit) ?: false

    override fun changeStartTime(seconds: Long): Boolean = server?.changeStartTime(seconds) ?: false

    override fun changeGpsMode(mode: Int): Boolean = server?.changeGpsMode(mode) ?: false

    override fun setAllowJoinInProgress(allow: Boolean) {
        server?.setAllowJoinInProgress(allow)
    }

    override fun setOnlyServerSettings(only: Boolean) {
        server?.setOnlyServerSettings(only)
    }

    override fun setAllowChooseTeams(allow: Boolean) {
        server?.setAllowChooseTeams(allow)
    }

    override fun playerSettings(playerId: Byte): PlayerSettingsValues =
        server?.readPlayerSettings(playerId) { PlayerSettingsValues.of(it ?: PlayerSettings()) }
            ?: PlayerSettingsValues.of(PlayerSettings())

    override fun applyPlayerSettings(
        playerId: Byte,
        values: PlayerSettingsValues,
        applyAll: Boolean,
        applyTeam: Boolean,
        targetTeam: Int,
    ): Boolean = server?.applyPlayerSettings(playerId, { values.applyTo(it) }, applyAll, applyTeam, targetTeam) ?: true

    override fun kickPlayer(playerId: Byte) {
        server?.kickPlayer(playerId)
    }

    /** The operator's settings as last saved, under the keys the server screen always used (R5). */
    private fun savedSettings(): SavedServerSettings = with(preferences()) {
        SavedServerSettings(
            teamMode = teamMode(),
            timeLimit = timeLimit(),
            livesLimit = livesLimit(),
            scoreLimit = scoreLimit(),
            startTimeSeconds = startTime(GameSettingsRepository.START_TIME_SECONDS.toInt()).toLong(),
            gpsMode = gpsMode(GameSettingsRepository.GPS_ALL),
            allowChooseTeams = allowChooseTeams(),
        )
    }

    private companion object {
        const val TAG = "ServerController"
    }
}
