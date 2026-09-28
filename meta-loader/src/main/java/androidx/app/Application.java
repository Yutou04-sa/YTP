package androidx.app;

import android.content.Context;

public class Application extends android.app.Application{

    @Override
    public void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Init.load(this);
    }
}
