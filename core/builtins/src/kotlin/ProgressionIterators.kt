/*
 * Copyright 2010-2015 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

// Auto-generated file. DO NOT EDIT!

package kotlin

/**
 * An iterator over a progression of values of type `Byte`.
 * @property increment the number by which the value is incremented on each step.
 */
internal class ByteProgressionIterator(first: Byte, last: Byte, val increment: Int) : ByteIterator() {
    private var next = first.toInt()
    private val finalElement = last.toInt()
    private var hasNext: Boolean = if (increment > 0) first <= last else first >= last

    override fun hasNext(): Boolean = hasNext

    override fun nextByte(): Byte {
        val value = next
        if (value == finalElement) {
            hasNext = false
        }
        else {
            next += increment
        }
        return value.toByte()
    }
}

/**
 * An iterator over a progression of values of type `Char`.
 * @property increment the number by which the value is incremented on each step.
 */
internal class CharProgressionIterator(first: Char, last: Char, val increment: Int) : CharIterator() {
    private var next = first.toInt()
    private val finalElement = last.toInt()
    private var hasNext: Boolean = if (increment > 0) first <= last else first >= last

    override fun hasNext(): Boolean = hasNext

    override fun nextChar(): Char {
        val value = next
        if (value == finalElement) {
            hasNext = false
        }
        else {
            next += increment
        }
        return value.toChar()
    }
}

/**
 * An iterator over a progression of values of type `Short`.
 * @property increment the number by which the value is incremented on each step.
 */
internal class ShortProgressionIterator(first: Short, last: Short, val increment: Int) : ShortIterator() {
    private var next = first.toInt()
    private val finalElement = last.toInt()
    private var hasNext: Boolean = if (increment > 0) first <= last else first >= last

    override fun hasNext(): Boolean = hasNext

    override fun nextShort(): Short {
        val value = next
        if (value == finalElement) {
            hasNext = false
        }
        else {
            next += increment
        }
        return value.toShort()
    }
}

/**
 * An iterator over a progression of values of type `Int`.
 * @property increment the number by which the value is incremented on each step.
 */
internal class IntProgressionIterator(first: Int, last: Int, val increment: Int) : IntIterator() {
    private var next = first
    private val finalElement = last
    private var hasNext: Boolean = if (increment > 0) first <= last else first >= last

    override fun hasNext(): Boolean = hasNext

    override fun nextInt(): Int {
        val value = next
        if (value == finalElement) {
            hasNext = false
        }
        else {
            next += increment
        }
        return value
    }
}

/**
 * An iterator over a progression of values of type `Long`.
 * @property increment the number by which the value is incremented on each step.
 */
internal class LongProgressionIterator(first: Long, last: Long, val increment: Long) : LongIterator() {
    private var next = first
    private val finalElement = last
    private var hasNext: Boolean = if (increment > 0) first <= last else first >= last

    override fun hasNext(): Boolean = hasNext

    override fun nextLong(): Long {
        val value = next
        if (value == finalElement) {
            hasNext = false
        }
        else {
            next += increment
        }
        return value
    }
}

