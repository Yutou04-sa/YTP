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
 * 按顺序执行修补策略。每一步用 continueOn 判断自己的错误能否回退，
 * 下一步再用 previousErrorMatches 判断它是否有能力处理该错误。
 * 这样新增一种容器格式时，只需登记策略及可识别的错误条件。
 */
final class PatchFallbackPipeline {

    @FunctionalInterface
    interface PatchAction {
        void run() throws Exception;
    }

    private static final class Step {
        private final String message;
        private final PatchAction action;
        private final Predicate<Throwable> previousErrorMatches;
        private final Predicate<Throwable> continueOn;

        private Step(
                String message,
                PatchAction action,
                Predicate<Throwable> previousErrorMatches,
                Predicate<Throwable> continueOn) {
            this.message = message;
            this.action = action;
            this.previousErrorMatches = previousErrorMatches;
            this.continueOn = continueOn;
        }
    }

    private final Logger logger;
    private final List<Step> steps = new ArrayList<>();

    PatchFallbackPipeline(Logger logger) {
        this.logger = logger;
    }

    PatchFallbackPipeline add(String message, PatchAction action) {
        return add(message, action, null, error -> false);
    }

    PatchFallbackPipeline add(
            String message,
            PatchAction action,
            Predicate<Throwable> continueOn) {
        return add(message, action, null, continueOn);
    }

    PatchFallbackPipeline addWhen(
            String message,
            PatchAction action,
            Predicate<Throwable> previousErrorMatches) {
        return add(message, action, previousErrorMatches, error -> false);
    }

    PatchFallbackPipeline addWhen(
            String message,
            PatchAction action,
            Predicate<Throwable> previousErrorMatches,
            Predicate<Throwable> continueOn) {
        return add(message, action, previousErrorMatches, continueOn);
    }

    private PatchFallbackPipeline add(
            String message,
            PatchAction action,
            Predicate<Throwable> previousErrorMatches,
            Predicate<Throwable> continueOn) {
        steps.add(new Step(message, action, previousErrorMatches, continueOn));
        return this;
    }

    /**
     * 顺序尝试策略，无法识别的异常立即原样抛出，不让后续策略掩盖根因。
     *
     * @return 成功执行的策略序号，首个普通修补策略为 0
     */
    int execute() throws Exception {
        Throwable previousError = null;
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            if (previousError != null
                    && step.previousErrorMatches != null
                    && !step.previousErrorMatches.test(previousError)) {
                continue;
            }
            logger.i(step.message);
            try {
                step.action.run();
                return index;
            } catch (Exception | YTPPatch.PatchError error) {
                boolean hasNextStep = index + 1 < steps.size();
                if (!hasNextStep || !step.continueOn.test(error)) {
                    throw error;
                }
                previousError = error;
            }
        }

        throw new YTPPatch.PatchError("No patch fallback strategy available");
    }
}
