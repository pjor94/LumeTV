import android.os.IBinder;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;

/** Shell-only test probe; never packaged in the application. */
public final class MediaButtonProbe {
    public static void main(String[] args) throws Exception {
        if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) {
            throw new IllegalStateException("Use a disposable emulator, never a physical TV.");
        }
        boolean realKey = args.length > 0 && args[0].equals("real");
        long time = realKey ? SystemClock.uptimeMillis() : 0;
        KeyEvent event = new KeyEvent(time, time, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_STOP, 0, 0, -1, 0, 0,
                realKey ? InputDevice.SOURCE_KEYBOARD : 0);
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "media_session");
        Object manager = Class.forName("android.media.session.ISessionManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);
        Class.forName("android.media.session.ISessionManager").getMethod("dispatchMediaKeyEvent",
                String.class, boolean.class, KeyEvent.class, boolean.class)
                .invoke(manager, "com.android.shell", false, event, false);
        System.out.println("Media STOP sent");
    }
}
