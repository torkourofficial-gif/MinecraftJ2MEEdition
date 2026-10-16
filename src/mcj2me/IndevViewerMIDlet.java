package mcj2me;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;

/**
 * First playable milestone: procedural Indev-sized terrain, spectator camera.
 * Requires MIDP 2.0, CLDC 1.1 and JSR-184 (Mobile 3D API).
 */
public final class IndevViewerMIDlet extends MIDlet {
    private WorldCanvas canvas;

    public void startApp() {
        if (canvas == null) {
            canvas = new WorldCanvas(this);
        }
        Display.getDisplay(this).setCurrent(canvas);
        canvas.start();
    }

    public void pauseApp() {
        if (canvas != null) {
            canvas.pause();
        }
    }

    public void destroyApp(boolean unconditional) {
        if (canvas != null) {
            canvas.stop();
        }
    }
}
