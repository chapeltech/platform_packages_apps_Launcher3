package com.android.quickstep.sysuiconnection

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.RemoteCallback
import android.os.SystemClock
import com.android.quickstep.SystemUiProxy
import org.wonad.focusproposal.v2.FocusActorAdapter
import org.wonad.focusproposal.v2.FocusMainScheduler
import org.wonad.focusproposal.v2.ProcessFocusWorker

/** Private Launcher adapter. Never invokes the private Binder query on Launcher MAIN. */
internal class AndroidFocusRouter(private val eligible: (Int) -> Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val main = object : FocusMainScheduler {
        override fun assertMain() { check(Looper.myLooper() === Looper.getMainLooper()) }
        override fun post(task: Runnable) = handler.post(task)
        override fun postDelayed(task: Runnable, delayMillis: Long) =
            handler.postDelayed(task, delayMillis)
        override fun remove(task: Runnable) = handler.removeCallbacks(task)
    }
    private val actor = FocusActorAdapter(main, ProcessFocusWorker,
        SystemClock::elapsedRealtime, eligible)

    fun replace(connection: SystemUiProxy.FocusQueryConnection?) {
        main.assertMain()
        actor.replace(connection?.let { current ->
            FocusActorAdapter.Connection(current.binder, current.epoch) { token, receive ->
                // ProcessFocusWorker executes this one-way transact, not MAIN or TaskbarUi.
                current.transitions.requestFocusedDisplay(token, RemoteCallback { bundle ->
                    // No Handler supplied to RemoteCallback: claim/post bounds are actor-owned.
                    receive(exactPayload(bundle))
                })
            }
        })
    }

    fun submit(route: (Int) -> Unit) = actor.submit(route)
    fun destroy() = actor.destroy()

    private fun exactPayload(bundle: Bundle?): Map<String, Any?>? = try {
        if (bundle == null || bundle.keySet() != setOf("request_token", "display_id", "status")) null
        else mapOf("request_token" to bundle.get("request_token"),
            "display_id" to bundle.get("display_id"), "status" to bundle.get("status"))
    } catch (_: RuntimeException) { null }
}
