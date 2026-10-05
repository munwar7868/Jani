package com.munawar.ai

/** Picks the right string for the current assistant language. */
fun tr(lang: String, ur: String, hi: String, en: String): String = when {
    lang.startsWith("ur") -> ur
    lang.startsWith("hi") -> hi
    else -> en
}
