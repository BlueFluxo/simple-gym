package com.simplegym.data

import java.util.Locale

private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

/** Stable id for an exercise name: lowercase, runs of non-alphanumerics become `-`, ends trimmed. */
fun slug(name: String): String =
    name.lowercase(Locale.ROOT).replace(NON_ALPHANUMERIC, "-").trim('-')
