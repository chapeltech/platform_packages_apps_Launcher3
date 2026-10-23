/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.launcher3.taskbar

import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.BadTokenException
import android.view.WindowManager.InvalidDisplayException
import android.view.WindowManager.LayoutParams
import com.android.launcher3.util.RetryingExecutor

/** A wrapper around [WindowManager] which retries addView using exponential backoff */
class SafeViewManager(
    private val windowManager: WindowManager,
    private val rootLayout: View,
    private val retryingExecutor: RetryingExecutor = RetryingExecutor(),
) {
    private var viewAdded = false
    private var pendingAdd = Any()

    fun addView(
        params: LayoutParams,
        admission: (() -> Boolean)? = null,
        onRejected: (() -> Unit)? = null,
    ) {
        if (viewAdded && admission == null) return
        val scope = Any().also { pendingAdd = it }
        fun reject() {
            if (pendingAdd !== scope) return
            pendingAdd = Any()
            retryingExecutor.cancel()
            onRejected?.invoke()
        }
        if (viewAdded) {
            retryingExecutor.cancel()
            if (admission?.invoke() == false) reject()
            return
        }
        retryingExecutor.execute { tryCount ->
            if (pendingAdd !== scope) return@execute true
            if (admission?.invoke() == false) {
                reject()
                return@execute true
            }
            try {
                windowManager.addView(rootLayout, params)
                viewAdded = true
                if (admission?.invoke() == false) reject()
                true
            } catch (e: InvalidDisplayException) {
                // Only the opt-in external taskbar admission is terminal here.
                // Retain the ordinary phone/default exception behavior.
                if (admission == null) throw e
                reject()
                true
            } catch (e: BadTokenException) {
                if (pendingAdd !== scope) return@execute true
                if (admission?.invoke() == false) {
                    reject()
                    return@execute true
                }
                Log.d(TAG, "Failed to add window, tryCount=$tryCount", e)
                false
            }
        }
    }

    fun removeView() {
        pendingAdd = Any()
        retryingExecutor.cancel()
        if (viewAdded) {
            windowManager.removeViewImmediate(rootLayout)
            viewAdded = false
        }
    }

    companion object {
        private const val TAG = "SafeViewManager"
    }
}
