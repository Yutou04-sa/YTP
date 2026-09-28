package org.ytp.share;

public class R<T> {
    private boolean code = false;
    private T data = null;

    private String dataMsg = null;

    // 添加构造函数
    public R() {}

    public R(boolean code, T data) {
        this.code = code;
        this.data = data;
    }

    public R(boolean code, T data,String dataMsg) {
        this.code = code;
        this.data = data;
        this.dataMsg = dataMsg;
    }

    // 添加getter和setter方法
    public boolean getCode() {
        return code;
    }

    public void setCode(boolean code) {
        this.code = code;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }

    public String getDataMsg() {
        return dataMsg;
    }

    public void setDataMsg(String dataMsg) {
        this.dataMsg = dataMsg;
    }
}
