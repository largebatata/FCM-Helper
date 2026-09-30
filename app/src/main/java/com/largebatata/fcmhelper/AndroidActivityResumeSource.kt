package com.largebatata.fcmhelper

import com.largebatata.fcmhelper.core.LogConnection
import com.largebatata.fcmhelper.core.LogSource

/** Two verified events-buffer tags only; no ActivityTaskManager stream or polling. */
class AndroidActivityResumeSource : LogSource {
    override fun open(cursor: String): LogConnection = AndroidProcessLogConnection(
        "/system/bin/logcat -b events -v epoch -T 1 " +
            "wm_resume_activity:I wm_set_resumed_activity:I '*:S'",
    )
}
