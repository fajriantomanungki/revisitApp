package com.fajriantomanungki.revisitapp.data.local.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UuidV4Test {

    @Test
    fun generate_returns_uuid_v4() {
        val value = UuidV4.generate()
        assertTrue(UuidV4.isValid(value))
    }

    @Test
    fun validator_rejects_non_v4_uuid() {
        assertFalse(
            UuidV4.isValid("00000000-0000-0000-8000-000000000000")
        )
    }
}
