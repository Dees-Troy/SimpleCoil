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
package com.simplecoil.simplecoil.domain.session

/** Spawn/elimination status shown over the play screen. */
sealed interface SpawnPhase {
    /** Nothing shown (alive, or no game). */
    data object None : SpawnPhase

    /** Eliminated (or starting): respawn countdown in whole seconds. */
    data class Spawning(val seconds: Long, val eliminatedBy: String, val startingGame: Boolean) : SpawnPhase

    /** Eliminated with no lives left; no respawn. */
    data class OutOfLives(val eliminatedBy: String) : SpawnPhase
}

/** The game clock. */
sealed interface GameClock {
    /** Not running; [countdownText] is what the countdown view shows ("MM:SS"), if set. */
    data class Stopped(val countdownText: String?) : GameClock

    /** Counting up from [base] (SystemClock.elapsedRealtime()). */
    data class Chronometer(val base: Long) : GameClock

    /** Counting down; [text] is "MM:SS". */
    data class Countdown(val text: String) : GameClock
}

/** Everything the play screen shows from the blaster session. */
data class SessionState(
    val health: Int,
    val maxHealth: Int,
    val shotsRemaining: Byte = 0,
    /** Reload bar instead of the shot count; null until the first reload. */
    val reloadBarShown: Boolean? = null,
    val hitsTaken: Int = 0,
    /** Eliminations, or lives left with a lives limit. */
    val eliminationDisplay: Int = 0,
    val shotMode: Int,
    val recoilEnabled: Boolean = true,
    val battery: BatteryLevelPolicy.Level? = null,
    val spawn: SpawnPhase = SpawnPhase.None,
    /** Name shown by the "you're hit" overlay while it plays; null when not playing. */
    val hitOverlay: String? = null,
    /** Respawn shield seconds left; null when not shielded. */
    val shieldSeconds: Long? = null,
    val clock: GameClock = GameClock.Stopped(null),
)

/** Game sounds. SCORE is played by the network side, the rest by the session. */
enum class SessionSound { BEEP, ELIMINATED, EMPTY, HIT, RELOAD, SCORE, SHOT, SPAWN }

enum class SessionToast { GRENADE_PAIRED, OUT_OF_LIVES, RECOIL_FORCED_ENABLED, RECOIL_FORCED_DISABLED, GAME_TIME_EXPIRED }

/** One-shot outputs. */
sealed interface SessionEffect {
    data class Sound(val sound: SessionSound) : SessionEffect
    data object Vibrate : SessionEffect
    data class Toast(val toast: SessionToast) : SessionEffect

    /** First telemetry packet of a connection: show the play screen, start network discovery. */
    data object CommunicationStarted : SessionEffect

    /** The engine sent the player id to the blaster: persist it and redisplay the team. */
    data object TeamSent : SessionEffect

    /** The blaster reports a new player id (already stored): redisplay the team. */
    data object PlayerIdReported : SessionEffect

    /** The thumb (mode) button was held for a long press; it did not cycle the shot mode. */
    data object ThumbLongPressed : SessionEffect

    /** The game must end (time ran out). */
    data object EndGameRequested : SessionEffect
}
