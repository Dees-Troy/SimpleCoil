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
import com.simplecoil.simplecoil.hardware.blaster.BlasterManager
import com.simplecoil.simplecoil.hardware.blaster.BleDeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Application-scoped container (manual DI). Owns the single BlasterManager for the process.
 */
class SimpleCoilApp : Application() {

    /** Main-thread scope that lives as long as the process. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val blasterManager: BlasterManager by lazy { BlasterManager(appScope) }

    val bleDeviceRepository: BleDeviceRepository by lazy { BleDeviceRepository(blasterManager) }
}
