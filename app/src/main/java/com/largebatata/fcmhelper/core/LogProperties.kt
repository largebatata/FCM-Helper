package com.largebatata.fcmhelper.core

/** The entire write allowlist; neither property names nor values come from IPC/UI input. */
enum class LogProperty(val propertyName: String, val expected: String) {
    MCS_INPUT("log.tag.GmsGcmMcsInput", "DEBUG"),
    MCS_SERVICE("log.tag.GmsGcmMcsSvc", "DEBUG"),
    RESUME("log.tag.wm_resume_activity", "INFO"),
    SET_RESUMED("log.tag.wm_set_resumed_activity", "INFO");

    fun readCommand(): List<String> = listOf("/system/bin/getprop", propertyName)
    fun repairCommand(): List<String> = listOf("/system/bin/setprop", propertyName, expected)
}

enum class PropertyCheckState { MATCH, UNSET, MISMATCH, READ_FAILED }

data class LogPropertyCheck(val property: LogProperty, val value: String?) {
    val state: PropertyCheckState get() = when {
        value == null -> PropertyCheckState.READ_FAILED
        value.isBlank() -> PropertyCheckState.UNSET
        value.trim().equals(property.expected, ignoreCase = true) -> PropertyCheckState.MATCH
        else -> PropertyCheckState.MISMATCH
    }
}

fun checkLogProperties(read: (LogProperty) -> String?): List<LogPropertyCheck> =
    LogProperty.entries.map { LogPropertyCheck(it, runCatching { read(it) }.getOrNull()) }

data class LogPropertyRepairReport(
    val before: List<LogPropertyCheck>,
    val after: List<LogPropertyCheck>,
    val exitCodes: List<Int>,
) {
    // A successful command is insufficient; every readback must match as well.
    val verified: Boolean get() = before.map { it.property } == LogProperty.entries &&
        after.map { it.property } == LogProperty.entries &&
        exitCodes.size == LogProperty.entries.size && exitCodes.all { it == 0 } &&
        after.all { it.state == PropertyCheckState.MATCH }
}
