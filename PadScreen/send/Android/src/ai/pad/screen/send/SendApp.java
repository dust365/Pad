package ai.pad.screen.send;

import android.app.Application;

public class SendApp extends Application {
    private Hub hub;

    @Override
    public void onCreate() {
        super.onCreate();
        hub = new Hub(this);
    }

    Hub hub() {
        return hub;
    }
}
