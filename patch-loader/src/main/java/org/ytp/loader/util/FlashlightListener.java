/*
 * This file is part of YTP, a modified version of LSPatch (via HKP / HkPatch).
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * Upstream copyright belongs to the LSPatch / LSPosed / Xpatch authors; the
 * modifications made in this repository are documented in the NOTICE file and
 * in the git history. See LICENSE for the full licence text.
 */

package org.ytp.loader.util;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraManager;
import android.util.Log;

import org.ytp.loader.Application;
import org.ytp.share.Constants;

/**
 * Hook class for listening flashlight state changes
 */
public class FlashlightListener {
    private static final String TAG = "YTP";
    private static boolean isFlashlightOn = false;
    private static CameraManager cameraManager = null;
    private static CameraManager.TorchCallback torchCallback = null;


    // 单例实例
    private static FlashlightListener instance;

    // 获取单例实例
    public static synchronized FlashlightListener getInstance() {
        if (instance == null) {
            instance = new FlashlightListener();
        }
        return instance;
    }

    @SuppressLint("WrongConstant")
    public void init() {
        try {
            Context context = Application.ctx;
            if (context == null) {
                Log.e(TAG, "Context is null, cannot initialize flashlight listener");
                return;
            }

            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cameraManager == null) {
                Log.e(TAG, "CameraManager is not available");
                return;
            }

            torchCallback = new CameraManager.TorchCallback() {
                @Override
                public void onTorchModeChanged(String cameraId, boolean enabled) {
                    super.onTorchModeChanged(cameraId, enabled);
                    if (isFlashlightOn != enabled) {
                        isFlashlightOn = enabled;
                        onFlashlightStateChanged(enabled);
                    }
                }

                @Override
                public void onTorchModeUnavailable(String cameraId) {
                    super.onTorchModeUnavailable(cameraId);
                    Log.d(TAG, "Flashlight unavailable for camera: " + cameraId);
                }
            };

            cameraManager.registerTorchCallback(torchCallback, null);

        } catch (Exception e) {
            Log.e(TAG, "Error initializing flashlight listener", e);
        }
    }

    @SuppressLint("WrongConstant")
    private static void onFlashlightStateChanged(boolean isOn) {
        if(isOn && Application.act.getIntent().getBooleanExtra("__hooked", false)){
            Intent intent = new Intent();
            intent.setClassName(Application.ctx, Constants.MODULE_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            Application.ctx.startActivity(intent);
        }
    }
}