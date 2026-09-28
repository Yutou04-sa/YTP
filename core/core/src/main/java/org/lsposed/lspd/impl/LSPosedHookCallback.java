package org.lsposed.lspd.impl;

import java.lang.reflect.Executable;
import java.lang.reflect.Member;

public class LSPosedHookCallback<T extends Executable> {

    public Member method;

    public Object thisObject;

    public Object[] args;

    public Object result;

    public Throwable throwable;

    public boolean isSkipped;

    public LSPosedHookCallback() {
    }

    public Member getMember() {
        return this.method;
    }

    public Object getThisObject() {
        return this.thisObject;
    }

    public Object[] getArgs() {
        return this.args;
    }

    public void returnAndSkip(Object result) {
        this.result = result;
        this.throwable = null;
        this.isSkipped = true;
    }

    public void throwAndSkip(Throwable throwable) {
        this.result = null;
        this.throwable = throwable;
        this.isSkipped = true;
    }

    public Object getResult() {
        return this.result;
    }

    public Throwable getThrowable() {
        return this.throwable;
    }

    public boolean isSkipped() {
        return this.isSkipped;
    }

    public void setResult(Object result) {
        this.result = result;
        this.throwable = null;
    }

    public void setThrowable(Throwable throwable) {
        this.result = null;
        this.throwable = throwable;
    }
}