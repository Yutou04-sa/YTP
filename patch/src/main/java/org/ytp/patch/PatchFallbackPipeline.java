/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.patch;

import org.ytp.patch.util.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Executes patch implementations in order and moves to the next one only when
 * the current implementation explicitly recognizes its failure as recoverable.
 *
 * <p>Adding a new compatibility path only requires adding another step to the
 * pipeline. The normal patch path remains outside this class.</p>
 */
final class PatchFallbackPipeline {

    @FunctionalInterface
    interface PatchAction {
        void run() throws Exception;
    }

    private static final class Step {
        private final String message;
        private final PatchAction action;
        private final Predicate<Throwable> continueOn;

        private Step(String message, PatchAction action, Predicate<Throwable> continueOn) {
            this.message = message;
            this.action = action;
            this.continueOn = continueOn;
        }
    }

    private final Logger logger;
    private final List<Step> steps = new ArrayList<>();

    PatchFallbackPipeline(Logger logger) {
        this.logger = logger;
    }

    PatchFallbackPipeline add(String message, PatchAction action) {
        return add(message, action, error -> false);
    }

    PatchFallbackPipeline add(
            String message,
            PatchAction action,
            Predicate<Throwable> continueOn) {
        steps.add(new Step(message, action, continueOn));
        return this;
    }

    void execute() throws Exception {
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            logger.i(step.message);
            try {
                step.action.run();
                return;
            } catch (Exception | YTPPatch.PatchError error) {
                boolean hasNextStep = index + 1 < steps.size();
                if (!hasNextStep || !step.continueOn.test(error)) {
                    throw error;
                }
            }
        }

        throw new YTPPatch.PatchError("No patch fallback strategy available");
    }
}
