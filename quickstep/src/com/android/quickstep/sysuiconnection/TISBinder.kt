/*
 * Copyright (C) 2025 The Android Open Source Project
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
package com.android.quickstep.sysuiconnection

import android.app.contextualsearch.ContextualSearchConfig
import android.content.Context
import android.graphics.Region
import android.os.Bundle
import android.os.IRemoteCallback
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import android.view.Display
import androidx.annotation.BinderThread
import com.android.app.displaylib.PerDisplayRepository
import com.android.launcher3.concurrent.annotations.Ui
import com.android.launcher3.dagger.ApplicationContext
import com.android.launcher3.taskbar.TaskbarManager
import com.android.launcher3.testing.TestLogging
import com.android.launcher3.testing.shared.TestProtocol
import com.android.launcher3.util.Executors.getTaskbarUiThread
import com.android.launcher3.util.LockedUserState
import com.android.launcher3.util.PostUnlockObject
import com.android.launcher3.util.ThreadSafeRunnableList
import com.android.quickstep.OverviewCommandHelper
import com.android.quickstep.OverviewCommandHelper.CommandType
import com.android.quickstep.OverviewCommandHelper.CommandType.HIDE_ALT_TAB
import com.android.quickstep.OverviewCommandHelper.CommandType.SHOW_ALT_TAB
import com.android.quickstep.OverviewCommandHelper.CommandType.TOGGLE
import com.android.quickstep.OverviewCommandHelper.CommandType.TOGGLE_WITH_FOCUS
import com.android.quickstep.OverviewComponentObserver
import com.android.quickstep.RecentsAnimationDeviceState
import com.android.quickstep.SystemDecorationChangeObserver
import com.android.quickstep.SystemUiProxy
import com.android.quickstep.TaskAnimationManager
import com.android.quickstep.TaskUtils
import com.android.quickstep.TouchInteractionHandler
import com.android.quickstep.actioncorner.ActionCornerHandler
import com.android.quickstep.dagger.CONNECTION_CLEANER
import com.android.quickstep.dagger.SysUIConnectionSingleton
import com.android.quickstep.input.QuickstepKeyGestureEventsManager
import com.android.quickstep.util.ActivityPreloadUtil.preloadOverviewForTIS
import com.android.quickstep.util.ContextualSearchInvoker
import com.android.systemui.shared.recents.ILauncherProxy.Stub
import com.android.systemui.shared.statusbar.phone.BarTransitions.TransitionMode
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Provider

/** Local ILauncherProxy implementation with some methods for local components */
private const val TAG = "TISBinder"

@SysUIConnectionSingleton
class TISBinder
@Inject
internal constructor(
    bindData: BindData,
    @Ui private val uiExecutor: Executor,
    quickstepKeyGestureEventsHandler: QuickstepKeyGestureEventsManager,
    @Named(CONNECTION_CLEANER) private val cleanupTasks: ThreadSafeRunnableList,
) : Stub() {
    private var state: BindData? = bindData
    private val focusRouter = AndroidFocusRouter { display ->
        val current = state
        current != null && current.lockedUserState.isUserUnlocked &&
            current.sysUIProxy.focusQueryConnection != null &&
            current.overviewCommandHelper != null &&
            current.deviceStateRepository[display]?.let {
                !it.isScreenPinningActive && it.canStartOverviewCommand()
            } == true
    }
    private val focusConnectionChanged = Runnable {
        focusRouter.replace(state?.sysUIProxy?.focusQueryConnection)
    }


    private inline fun withState(task: BindData.() -> Unit) {
        state?.apply(task)
    }

    init {
        cleanupTasks.addTask(uiExecutor, quickstepKeyGestureEventsHandler::onDestroy)
        check(Looper.myLooper() === Looper.getMainLooper())
        bindData.sysUIProxy.addOnStateChangeListener(focusConnectionChanged)
        focusConnectionChanged.run()
        cleanupTasks.addTask(uiExecutor) {
            bindData.sysUIProxy.removeOnStateChangeListener(focusConnectionChanged)
            focusRouter.destroy()
            state = null
        }
    }

    @BinderThread
    override fun onInitialize(bundle: Bundle) =
        uiExecutor.execute {
            withState {
                sysUIProxy.setInitializationParams(bundle)
                handler.initInputMonitor("TISBinder#onInitialize()")
                preloadOverviewForTIS(context, fromInit = true)
            }
        }

    @BinderThread
    override fun onTaskbarToggled() = withState { taskbarManager.toggleTaskbarStash() }

    @BinderThread
    override fun onOverviewToggle() {
        requestOverview(TOGGLE, closeWindows = true)
    }

    @BinderThread
    override fun onOverviewShown(triggeredFromAltTab: Boolean) {
        requestOverview(if (triggeredFromAltTab) SHOW_ALT_TAB else TOGGLE_WITH_FOCUS,
            closeWindows = triggeredFromAltTab)
    }

    @BinderThread
    override fun onOverviewHidden(triggeredFromAltTab: Boolean, triggeredFromHomeKey: Boolean) {
        if (triggeredFromAltTab && !triggeredFromHomeKey)
            requestOverview(HIDE_ALT_TAB, closeWindows = false)
    }

    /** Binder ingress is bounded before MAIN post. All callbacks below run synchronously MAIN. */
    private fun requestOverview(type: CommandType, closeWindows: Boolean) {
        focusRouter.submit { display ->
            val captured = state ?: return@submit
            val incarnation = captured.sysUIProxy.focusQueryConnection ?: return@submit
            val helper = captured.overviewCommandHelper ?: return@submit
            // This guard outlives gate.pending. Queue execution + owned late UI callbacks recheck.
            val executionGuard = {
                Looper.myLooper() === Looper.getMainLooper() && state === captured &&
                    captured.sysUIProxy.focusQueryConnection === incarnation &&
                    captured.lockedUserState.isUserUnlocked &&
                    captured.deviceStateRepository[display]?.let {
                        !it.isScreenPinningActive && it.canStartOverviewCommand()
                    } == true
            }
            if (!executionGuard()) return@submit
            if (closeWindows) TaskUtils.closeSystemWindowsAsync(
                ActivityManagerWrapper.CLOSE_SYSTEM_WINDOWS_REASON_RECENTS)
            helper.addCommand(type, display, executionGuard = executionGuard)
        }
    }

    @BinderThread
    override fun onAssistantAvailable(available: Boolean, longPressHomeEnabled: Boolean) =
        uiExecutor.execute {
            withState {
                deviceStateRepository.forEach(/* createIfAbsent= */ true) {
                    it.setAssistantAvailable(available)
                }
                taskbarManager.onLongPressHomeEnabled(longPressHomeEnabled)
            }
        }

    @BinderThread
    override fun onAssistantVisibilityChanged(visibility: Float) =
        uiExecutor.execute {
            withState {
                deviceStateRepository.forEach(/* createIfAbsent= */ true) {
                    it.assistantVisibility = visibility
                }
                handler.onAssistantVisibilityChanged()
            }
        }

    /**
     * Sent when the assistant has been invoked with the given type (defined in AssistManager) and
     * should be shown. This method is used if SystemUiProxy#setAssistantOverridesRequested was
     * previously called including this invocation type.
     */
    override fun onAssistantOverrideInvoked(invocationType: Int) = withState {
        if (!contextualSearchInvoker.get().tryStartAssistOverride(invocationType))
            Log.w(TAG, "Failed to invoke Assist override")
    }

    override fun invokeContextualSearch(entryPoint: Int, config: ContextualSearchConfig?) =
        withState {
            if (!contextualSearchInvoker.get().show(entryPoint, config))
                Log.w(TAG, "Failed to invoke contextual search")
        }

    @BinderThread
    override fun onSystemUiStateChanged(@SystemUiStateFlags stateFlags: Long, displayId: Int) {
        uiExecutor.execute {
            withState {
                val deviceState = deviceStateRepository[displayId] ?: return@execute
                val lastFlags = deviceState.sysuiStateFlags
                deviceState.setSysUIStateFlags(stateFlags)

                taskbarManager.onSystemUiFlagsChanged(stateFlags, displayId)
                if (displayId == Display.DEFAULT_DISPLAY) {
                    // The following don't care about non-default displays, at least for now. If
                    // they ever will, they should be taken care of.
                    sysUIProxy.lastSystemUiStateFlags = stateFlags
                    overviewComponentObserver?.setHomeDisabled(deviceState.isHomeDisabled)
                }
                if (lockedUserState.isUserUnlocked) {
                    taskAnimationManagerRepository[displayId]?.onSystemUiFlagsChanged(
                        lastFlags,
                        stateFlags,
                    )
                }
            }
        }
    }

    @BinderThread
    override fun onActiveNavBarRegionChanges(region: Region) =
        uiExecutor.execute {
            withState {
                deviceStateRepository.forEach(createIfAbsent = true) {
                    it.setDeferredGestureRegion(region)
                }
            }
        }

    @BinderThread
    override fun enterStageSplitFromRunningApp(displayId: Int, leftOrTop: Boolean) = withState {
        overviewComponentObserver
            ?.getContainerInterface(displayId)
            ?.createdContainer
            ?.enterStageSplitFromRunningApp(leftOrTop, displayId)
    }

    @BinderThread
    override fun onDisplayAddSystemDecorations(displayId: Int) = withState {
        systemDecorationChangeObserver.notifyAddSystemDecorations(displayId)
    }

    @BinderThread
    override fun onDisplayRemoved(displayId: Int) = withState {
        systemDecorationChangeObserver.notifyOnDisplayRemoved(displayId)
    }

    @BinderThread
    override fun onDisplayRemoveSystemDecorations(displayId: Int) = withState {
        systemDecorationChangeObserver.notifyDisplayRemoveSystemDecorations(displayId)
    }

    @BinderThread
    override fun updateWallpaperVisibility(displayId: Int, visible: Boolean) = withState {
        taskbarManager.setWallpaperVisible(displayId, visible)
    }

    @BinderThread
    override fun checkNavBarModes(displayId: Int) = withState {
        taskbarManager.checkNavBarModes(displayId)
    }

    @BinderThread
    override fun finishBarAnimations(displayId: Int) = withState {
        taskbarManager.finishBarAnimations(displayId)
    }

    @BinderThread
    override fun touchAutoDim(displayId: Int, reset: Boolean) = withState {
        taskbarManager.touchAutoDim(displayId, reset)
    }

    @BinderThread
    override fun transitionTo(displayId: Int, @TransitionMode barMode: Int, animate: Boolean) =
        withState {
            taskbarManager.transitionTo(displayId, barMode, animate)
        }

    @BinderThread
    override fun appTransitionPending(pending: Boolean) = withState {
        taskbarManager.appTransitionPending(pending)
    }

    override fun onRotationProposal(rotation: Int, isValid: Boolean) = withState {
        taskbarManager.onRotationProposal(rotation, isValid)
    }

    override fun disable(displayId: Int, state1: Int, state2: Int, animate: Boolean) = withState {
        taskbarManager.disableNavBarElements(displayId, state1, state2, animate)
    }

    override fun onSystemBarAttributesChanged(displayId: Int, behavior: Int) = withState {
        taskbarManager.onSystemBarAttributesChanged(displayId, behavior)
    }

    override fun onTransitionModeUpdated(barMode: Int, checkBarModes: Boolean) = withState {
        taskbarManager.onTransitionModeUpdated(barMode, checkBarModes)
    }

    override fun onNavButtonsDarkIntensityChanged(darkIntensity: Float) = withState {
        taskbarManager.onNavButtonsDarkIntensityChanged(darkIntensity)
    }

    override fun onNavigationBarLumaSamplingEnabled(displayId: Int, enable: Boolean) = withState {
        taskbarManager.onNavigationBarLumaSamplingEnabled(displayId, enable)
    }

    override fun onUnbind(reply: IRemoteCallback) {
        cleanupTasks.complete()
        // Wait for both executors to complete before sending a reply
        uiExecutor.execute {
            getTaskbarUiThread().execute {
                try {
                    reply.sendResult(null)
                } catch (e: RemoteException) {
                    Log.w(TAG, "onUnbind: Failed to reply to LauncherProxyService", e)
                }
            }
        }
    }

    override fun onActionCornerActivated(action: Int, displayId: Int) =
        uiExecutor.execute { withState { actionCornerHandler?.handleAction(action, displayId) } }

    /**
     * Wrapper around all the objects inside the sysui connection graph. This is wrapped inside a
     * weak-reference as TISBinder can outlive sysUIConnection if the binder is held by another
     * process.
     */
    internal class BindData
    @Inject
    constructor(
        @ApplicationContext val context: Context,
        val handler: TouchInteractionHandler,
        val taskbarManager: TaskbarManager,
        val deviceStateRepository: PerDisplayRepository<RecentsAnimationDeviceState>,
        val sysUIProxy: SystemUiProxy,
        val contextualSearchInvoker: Provider<ContextualSearchInvoker>,
        val systemDecorationChangeObserver: SystemDecorationChangeObserver,
        val taskAnimationManagerRepository: PerDisplayRepository<TaskAnimationManager>,
        val lockedUserState: LockedUserState,
        overviewCommandHelper: PostUnlockObject<OverviewCommandHelper>,
        overviewComponentObserver: PostUnlockObject<OverviewComponentObserver>,
        actionCornerHandler: PostUnlockObject<ActionCornerHandler>,
    ) {
        val overviewCommandHelper: OverviewCommandHelper? by overviewCommandHelper
        val overviewComponentObserver: OverviewComponentObserver? by overviewComponentObserver
        val actionCornerHandler: ActionCornerHandler? by actionCornerHandler


    }
}
