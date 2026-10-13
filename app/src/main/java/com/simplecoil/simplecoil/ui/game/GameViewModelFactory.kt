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
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.simplecoil.simplecoil.SimpleCoilApp
import com.simplecoil.simplecoil.network.ClientEvent

/** Builds [GameViewModel] from the application container. */
class GameViewModelFactory(private val app: SimpleCoilApp) : ViewModelProvider.Factory {
    private val container get() = app.container

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = GameViewModel(
        engine = container.sessionEngine,
        net = container.networkGateway,
        ble = container.bleConnectionManager,
        blasterEvents = container.bleDeviceRepository.events,
        prefs = container.userPreferences,
        sounds = container.soundEffects,
        wifiConnected = { isWifiConnected(app) },
        publishGpsSetting = { container.clientEvents.publish(ClientEvent.GpsSettingChanged) },
        settingsRepo = container.settings,
        gameStateRepo = container.gameState,
        playerRepo = container.players,
    ) as T

    companion object {
        /** Connected over Wi-Fi (the network game needs the LAN). */
        fun isWifiConnected(context: Context): Boolean {
            val connManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager? ?: return false
            val network = connManager.activeNetwork ?: return false
            val capabilities = connManager.getNetworkCapabilities(network)
            return capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }
}
