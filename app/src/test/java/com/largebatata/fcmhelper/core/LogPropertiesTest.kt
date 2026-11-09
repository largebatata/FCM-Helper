package com.largebatata.fcmhelper.core

import org.junit.Assert.*
import org.junit.Test

class LogPropertiesTest {
    @Test fun writeAllowlistIsExactlyFourTemporaryProperties() {
        assertEquals(listOf(
            listOf("/system/bin/setprop", "log.tag.GmsGcmMcsInput", "DEBUG"),
            listOf("/system/bin/setprop", "log.tag.GmsGcmMcsSvc", "DEBUG"),
            listOf("/system/bin/setprop", "log.tag.wm_resume_activity", "INFO"),
            listOf("/system/bin/setprop", "log.tag.wm_set_resumed_activity", "INFO"),
        ), LogProperty.entries.map(LogProperty::repairCommand))
        assertFalse(LogProperty.entries.any { it.propertyName.startsWith("persist.") })
        LogProperty.entries.forEach {
            assertEquals(listOf("/system/bin/getprop", it.propertyName), it.readCommand())
        }
    }

    @Test fun readOnlyChecksVisitEveryPropertyAndKeepFailuresDistinctFromUnset() {
        val read = mutableListOf<LogProperty>()
        val checks = checkLogProperties {
            read.add(it)
            when (it) {
                LogProperty.MCS_INPUT -> "DEBUG"
                LogProperty.MCS_SERVICE -> ""
                LogProperty.RESUME -> "ERROR"
                LogProperty.SET_RESUMED -> error("cannot read")
            }
        }
        assertEquals(LogProperty.entries, read)
        assertEquals(listOf(PropertyCheckState.MATCH, PropertyCheckState.UNSET,
            PropertyCheckState.MISMATCH, PropertyCheckState.READ_FAILED), checks.map { it.state })
    }

    @Test fun checkCanBeRepeatedAfterRebootOrManualRepair() {
        var set = false
        fun read() = checkLogProperties { if (set) it.expected else "" }
        assertTrue(read().all { it.state == PropertyCheckState.UNSET })
        set = true
        assertTrue(read().all { it.state == PropertyCheckState.MATCH })
        assertTrue(checkLogProperties { it.expected.lowercase() }.all { it.state == PropertyCheckState.MATCH })
    }

    @Test fun exitZeroWithoutMatchingReadbackIsNotSuccess() {
        val before = checkLogProperties { "" }
        assertFalse(LogPropertyRepairReport(before, before, listOf(0, 0, 0, 0)).verified)
        val after = checkLogProperties { if (it == LogProperty.RESUME) null else it.expected }
        assertFalse(LogPropertyRepairReport(before, after, listOf(0, 0, 0, 0)).verified)
    }

    @Test fun everyCommandAndEveryReadbackMustSucceed() {
        val before = checkLogProperties { "" }
        val after = checkLogProperties { it.expected }
        assertTrue(LogPropertyRepairReport(before, after, listOf(0, 0, 0, 0)).verified)
        assertFalse(LogPropertyRepairReport(before, after, listOf(0, -2, 0, 0)).verified)
        assertFalse(LogPropertyRepairReport(before, after, emptyList()).verified)
        assertFalse(LogPropertyRepairReport(before, after.dropLast(1), listOf(0, 0, 0, 0)).verified)
    }
}
