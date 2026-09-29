/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.util

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.reflect.KProperty

class DelegateState<T>(initial: T, private val sideEffectSetter: (T) -> Unit) {
    private var snapshot by mutableStateOf(initial)

    var value: T
        get() = snapshot
        set(value) {
            snapshot = value
            sideEffectSetter(snapshot)
        }

    operator fun component1(): T = value

    operator fun component2(): (T) -> Unit = { value = it }
}

fun <T> delegateStateOf(initial: T, sideEffectSetter: (T) -> Unit) = DelegateState(initial, sideEffectSetter)

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> DelegateState<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

@Suppress("NOTHING_TO_INLINE")
inline operator fun <T> DelegateState<T>.setValue(thisObj: Any?, property: KProperty<*>, value: T) {
    this.value = value
}
