package com.example.awake.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SchoolAdapterRegistryTest {
    @Test
    fun defaultRegistryContainsOnlyScut() {
        val registry = SchoolAdapterRegistry()

        assertEquals(listOf("SCUT"), registry.all().map { it.code })
        assertEquals("华南理工大学", registry.get("SCUT")?.displayName)
    }

    @Test
    fun unknownSchoolIsNotSilentlyMapped() {
        assertNull(SchoolAdapterRegistry().get("UNKNOWN"))
        assertThrows(IllegalStateException::class.java) {
            SchoolAdapterRegistry().require("UNKNOWN", 2026, "3")
        }
    }

    @Test
    fun adapterValidatesSemesterArguments() {
        val registry = SchoolAdapterRegistry()

        assertEquals("SCUT", registry.require("SCUT", 2026, "3").code)
        assertThrows(IllegalStateException::class.java) {
            registry.require("SCUT", 0, "legacy")
        }
        assertThrows(IllegalStateException::class.java) {
            registry.require("SCUT", 2026, "")
        }
    }

    @Test
    fun duplicateCodesAreRejected() {
        val duplicate = object : SchoolAdapter {
            override val code = "SCUT"
            override val displayName = "重复适配器"
        }

        assertThrows(IllegalArgumentException::class.java) {
            SchoolAdapterRegistry(listOf(ScutAdapter(), duplicate))
        }
    }
}
