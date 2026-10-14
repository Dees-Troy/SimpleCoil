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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The Quit action on the blaster and network notifications; both quit the same way ([AppQuitter]). */
class QuitReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_QUIT) return
        (context.applicationContext as SimpleCoilApp).container.appQuitter.quit()
    }

    companion object {
        const val ACTION_QUIT = "com.simplecoil.simplecoil.action.QUIT"

        fun pendingIntent(context: Context): PendingIntent =
            PendingIntent.getBroadcast(context, 0, Intent(context, QuitReceiver::class.java).setAction(ACTION_QUIT),
                PendingIntent.FLAG_IMMUTABLE)
    }
}
