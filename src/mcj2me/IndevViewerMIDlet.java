package mcj2me;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;

/** MIDP entry point. The world is created only after Singleplayer is selected. */
public final class IndevViewerMIDlet extends MIDlet {
    private TitleScreen titleScreen;
    private WorldCanvas worldCanvas;

    public void startApp() {
        if (worldCanvas!=null) {
            Display.getDisplay(this).setCurrent(worldCanvas);
            worldCanvas.start();
            return;
        }
        if (titleScreen==null) titleScreen=new TitleScreen(this);
        Display.getDisplay(this).setCurrent(titleScreen);
        titleScreen.start();
    }
    void startGame() {
        if (titleScreen!=null) titleScreen.stop();
        if (worldCanvas==null) worldCanvas=new WorldCanvas(this);
        Display.getDisplay(this).setCurrent(worldCanvas);
        worldCanvas.start();
    }
    void quitGame() {
        if (titleScreen!=null) titleScreen.stop();
        if (worldCanvas!=null) worldCanvas.stop();
        notifyDestroyed();
    }
    public void pauseApp() {
        if (titleScreen!=null) titleScreen.stop();
        if (worldCanvas!=null) worldCanvas.pause();
    }
    public void destroyApp(boolean unconditional) {
        if (titleScreen!=null) titleScreen.stop();
        if (worldCanvas!=null) worldCanvas.stop();
    }
}
