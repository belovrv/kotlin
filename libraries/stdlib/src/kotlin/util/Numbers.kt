@file:kotlin.jvm.JvmMultifileClass
@file:kotlin.jvm.JvmName("MathKt")
package kotlin

/**
 * Returns `true` if the specified number is a
 * Not-a-Number (NaN) value, `false` otherwise.
 */
@kotlin.jvm.JvmName("\$isNaN")
public fun Double.isNaN(): Boolean = this != this

/**
 * Returns `true` if the specified number is a
 * Not-a-Number (NaN) value, `false` otherwise.
 */
@kotlin.jvm.JvmName("\$isNaN")
public fun Float.isNaN(): Boolean = this != this

/**
 * Returns `true` if this value is infinitely large in magnitude.
 */
@kotlin.jvm.JvmName("\$isInfinite")
public fun Double.isInfinite(): Boolean = this == Double.POSITIVE_INFINITY || this == Double.NEGATIVE_INFINITY

/**
 * Returns `true` if this value is infinitely large in magnitude.
 */
@kotlin.jvm.JvmName("\$isInfinite")
public fun Float.isInfinite(): Boolean = this == Float.POSITIVE_INFINITY || this == Float.NEGATIVE_INFINITY

/**
 * Returns `true` if the argument is a finite floating-point value; returns `false` otherwise (for `NaN` and infinity arguments).
 */
@kotlin.jvm.JvmName("\$isFinite")
public fun Double.isFinite(): Boolean = !isInfinite() && !isNaN()

/**
 * Returns `true` if the argument is a finite floating-point value; returns `false` otherwise (for `NaN` and infinity arguments).
 */
@kotlin.jvm.JvmName("\$isFinite")
public fun Float.isFinite(): Boolean = !isInfinite() && !isNaN()


@Deprecated("Use toInt() instead.", ReplaceWith("toInt()"))
public fun Number.intValue(): Int = toInt()

@Deprecated("Use toLong() instead.", ReplaceWith("toLong()"))
public fun Number.longValue(): Long = toLong()

@Deprecated("Use toShort() instead.", ReplaceWith("toShort()"))
public fun Number.shortValue(): Short = toShort()

@Deprecated("Use toByte() instead.", ReplaceWith("toByte()"))
public fun Number.byteValue(): Byte = toByte()

@Deprecated("Use toDouble() instead.", ReplaceWith("toDouble()"))
public fun Number.doubleValue(): Double = toDouble()

@Deprecated("Use toFloat() instead.", ReplaceWith("toFloat()"))
public fun Number.floatValue(): Float = toFloat()
