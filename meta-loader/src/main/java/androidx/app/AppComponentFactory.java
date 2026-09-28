package androidx.app;

public class AppComponentFactory extends android.app.AppComponentFactory {

    static {
        Init.load();
    }
}
