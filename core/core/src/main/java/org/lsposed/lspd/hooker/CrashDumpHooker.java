package org.lsposed.lspd.hooker;

import org.lsposed.lspd.impl.LSPosedBridge;
import org.lsposed.lspd.util.Utils.Log;

import io.github.libxposed.api.XposedInterface;

public class CrashDumpHooker implements XposedInterface.Hooker {

    @Override
    public Object intercept(XposedInterface.Chain chain) throws Throwable {
        beforeIntercept(chain);
        return chain.proceed();
    }

    private void beforeIntercept(XposedInterface.Chain chain) {
        try {
            var e = (Throwable) chain.getArgs().get(0);
            LSPosedBridge.log("Crash unexpectedly: " + Log.getStackTraceString(e));
        } catch (Throwable ignored) {
        }
    }
}