package com.android.quickstep

import android.view.Display
import android.view.View
import com.android.launcher3.AbstractFloatingViewHelper
import com.android.launcher3.R
import com.android.launcher3.popup.SystemShortcut
import com.android.quickstep.views.RecentsView
import com.android.quickstep.views.RecentsViewContainer
import com.android.quickstep.views.TaskContainer
import javax.inject.Inject

/** Phone Recents offers only a live, single-task Wonad transfer. Shell resolves
 * the actual task and destination again when the user taps this entry.
 */
class WonadSendShortcut private constructor(
    container: RecentsViewContainer,
    helper: AbstractFloatingViewHelper,
    private val taskContainer: TaskContainer,
) : SystemShortcut<RecentsViewContainer>(
    R.drawable.ic_external_display,
    R.string.recent_task_option_send_to_wonad,
    container,
    taskContainer.itemInfo,
    taskContainer.taskView,
    helper,
) {
    override fun onClick(view: View) {
        dismissTaskMenuView()
        mTarget.getOverviewPanel<RecentsView<*, *>>().sendTaskToWonad(taskContainer)
    }

    class Factory @Inject constructor(
        private val helper: AbstractFloatingViewHelper,
    ) : TaskShortcutFactory {
        override fun getShortcuts(
            container: RecentsViewContainer,
            taskContainer: TaskContainer,
        ): List<WonadSendShortcut>? {
            val key = taskContainer.task.key
            val validKey = key.displayId == Display.DEFAULT_DISPLAY && key.baseActivity != null &&
                key.token != null && key.lastActiveTime > 0
            if (!validKey) return null
            val allowed = SystemUiProxy.INSTANCE.get(container.asContext())
                .canSendTaskToWonad(key.id, key.userId, key.lastActiveTime, key.baseActivity,
                    key.token?.asBinder())
            if (!allowed) return null
            return listOf(WonadSendShortcut(container, helper, taskContainer))
        }
    }
}
