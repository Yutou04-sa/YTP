/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.ui.viewstate

sealed class ProcessingState<out T> {
    object Idle : ProcessingState<Nothing>()
    object Processing : ProcessingState<Nothing>()
    data class Done<T>(val result: T) : ProcessingState<T>()
}
