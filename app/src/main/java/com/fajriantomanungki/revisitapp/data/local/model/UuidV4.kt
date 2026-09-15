package com.fajriantomanungki.revisitapp.data.local.model

import java.util.UUID

/**
 * Generator dan validator UUID v4 untuk identitas record lokal.
 */
object UuidV4 {

    private val pattern = Regex(
        """^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-4[0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"""
    )

    fun generate(): String = UUID.randomUUID().toString()

    fun isValid(value: String): Boolean = pattern.matches(value)
}
