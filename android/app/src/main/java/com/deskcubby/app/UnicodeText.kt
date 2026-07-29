package com.deskcubby.app

internal fun String.takeCodePoints(maximum: Int): String {
    require(maximum >= 0) { "maximum must not be negative" }
    if (codePointCount(0, length) <= maximum) return this
    return substring(0, offsetByCodePoints(0, maximum))
}

internal fun String.codePointLength(): Int = codePointCount(0, length)
