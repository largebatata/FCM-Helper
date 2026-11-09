package com.largebatata.fcmhelper.logconfig

import android.content.Context
import androidx.annotation.Keep
import com.largebatata.fcmhelper.core.LogProperty
import kotlin.system.exitProcess

/** Runs under Shizuku only for a user-requested one-shot repair, independently of WeChat wake. */
@Keep
class LogPropertyUserService : ILogPropertyService.Stub {
    constructor() : super()
    constructor(@Suppress("UNUSED_PARAMETER") context: Context) : super()

    override fun repairProperties(): IntArray = LogProperty.entries.map(LogPropertyCommands::write).toIntArray()

    override fun destroy() { exitProcess(0) }
}
