package mcj2me;

import javax.microedition.midlet.MIDlet;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Alert;
import javax.microedition.lcdui.AlertType;
import javax.microedition.rms.RecordStore;
import javax.microedition.rms.RecordStoreException;

/** MIDP entry point. The world is created only after Singleplayer is selected. */
public final class IndevViewerMIDlet extends MIDlet {
    static int sensitivity = 3;
    static int fogLevel = 1;
    static int frameskip = 0;
    static boolean humbleMode = false;
    static final int MODE_CREATIVE = 0;
    static final int MODE_SURVIVAL = 1;

    // Values above 1000 represent MIDP directional actions, not device key codes.
    public static final int CONTROL_FORWARD = 0, CONTROL_BACKWARD = 1;
    public static final int CONTROL_STRAFE_LEFT = 2, CONTROL_STRAFE_RIGHT = 3;
    public static final int CONTROL_LOOK_UP = 4, CONTROL_LOOK_DOWN = 5;
    public static final int CONTROL_TURN_LEFT = 6, CONTROL_TURN_RIGHT = 7;
    public static final int CONTROL_JUMP = 8, CONTROL_BREAK = 9, CONTROL_PLACE = 10;
    public static final int CONTROL_COUNT = 11;
    public static final int KEY_UP = 1001, KEY_DOWN = 1002, KEY_LEFT = 1003, KEY_RIGHT = 1004;
    static final String[] CONTROL_NAMES = {
        "Move forward", "Move backward", "Strafe left", "Strafe right",
        "Look up", "Look down", "Turn left", "Turn right",
        "Jump / swim", "Break / attack", "Place block"
    };
    static final int[] controlKeys = {
        '2', '8', '4', '6', KEY_UP, KEY_DOWN, KEY_LEFT, KEY_RIGHT, '5', '1', '3'
    };
    private static boolean controlsLoaded;

    static synchronized void loadControls() {
        if (controlsLoaded) return;
        controlsLoaded = true;
        RecordStore store = null;
        try {
            store = RecordStore.openRecordStore("mcj2me-controls", true);
            if (store.getNumRecords() > 0) {
                String saved = new String(store.getRecord(1), "UTF-8");
                int start = 0;
                for (int i = 0; i < CONTROL_COUNT && start < saved.length(); i++) {
                    int end = saved.indexOf(',', start);
                    if (end < 0) end = saved.length();
                    int value = Integer.parseInt(saved.substring(start, end));
                    if ((value >= 0 && value <= 65535) || (value >= KEY_UP && value <= KEY_RIGHT))
                        controlKeys[i] = value;
                    start = end + 1;
                }
            }
        } catch (Exception e) {
            // Keep defaults on first launch or if RMS is unavailable.
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (RecordStoreException e) { }
        }
    }

    static synchronized void saveControls() {
        RecordStore store = null;
        try {
            StringBuffer saved = new StringBuffer();
            for (int i = 0; i < CONTROL_COUNT; i++) {
                if (i > 0) saved.append(',');
                saved.append(controlKeys[i]);
            }
            byte[] data = saved.toString().getBytes("UTF-8");
            store = RecordStore.openRecordStore("mcj2me-controls", true);
            if (store.getNumRecords() > 0) store.setRecord(1, data, 0, data.length);
            else store.addRecord(data, 0, data.length);
        } catch (Exception e) {
            // The current session still uses the selected bindings.
        } finally {
            if (store != null) try { store.closeRecordStore(); } catch (RecordStoreException e) { }
        }
    }

    static int directionalKey(int keyCode, int gameAction) {
        // Numeric keypad keys must remain numeric even on phones that also map them to D-pad actions.
        if (keyCode == '2' || keyCode == '4' || keyCode == '6' || keyCode == '8') return -1;
        if (gameAction == javax.microedition.lcdui.Canvas.UP) return KEY_UP;
        if (gameAction == javax.microedition.lcdui.Canvas.DOWN) return KEY_DOWN;
        if (gameAction == javax.microedition.lcdui.Canvas.LEFT) return KEY_LEFT;
        if (gameAction == javax.microedition.lcdui.Canvas.RIGHT) return KEY_RIGHT;
        return -1;
    }

    public static boolean controlPressed(int control, int keyCode, int gameAction) {
        int binding = controlKeys[control];
        if (binding == KEY_UP || binding == KEY_DOWN || binding == KEY_LEFT || binding == KEY_RIGHT) {
            if (keyCode == '2' || keyCode == '4' || keyCode == '6' || keyCode == '8') return false;
            if (binding == KEY_UP) return gameAction == javax.microedition.lcdui.Canvas.UP;
            if (binding == KEY_DOWN) return gameAction == javax.microedition.lcdui.Canvas.DOWN;
            if (binding == KEY_LEFT) return gameAction == javax.microedition.lcdui.Canvas.LEFT;
            return gameAction == javax.microedition.lcdui.Canvas.RIGHT;
        }
        return keyCode == binding;
    }

    static String controlKeyName(int binding) {
        if (binding == KEY_UP) return "UP";
        if (binding == KEY_DOWN) return "DOWN";
        if (binding == KEY_LEFT) return "LEFT";
        if (binding == KEY_RIGHT) return "RIGHT";
        if (binding == ' ') return "SPACE";
        if (binding >= 32 && binding <= 126) return String.valueOf((char) binding).toUpperCase();
        return "KEY " + binding;
    }

    static int fogFarDistance() {
        if (fogLevel == 0) return 32;
        if (fogLevel == 2) return 80;
        return 52;
    }

    private TitleScreen titleScreen;
    private WorldCanvas worldCanvas;
    private WorldSelectScreen worldSelectScreen;
    private WorldLoadingCanvas loadingCanvas;

    private void saveWorld(WorldCanvas canvas) {
        if (canvas == null || canvas.getWorldId() <= 0 || canvas.hasSetupError()) return;
        try { WorldStore.saveState(canvas.getWorldId(), canvas.saveTerrainState()); }
        catch (Exception ignored) { /* Keep the current session playable if storage is full. */ }
    }

    public void startApp() {
        loadControls();
        if (worldCanvas!=null) {
            Display.getDisplay(this).setCurrent(worldCanvas);
            worldCanvas.start();
            return;
        }
        if (titleScreen==null) titleScreen=new TitleScreen(this);
        Display.getDisplay(this).setCurrent(titleScreen);
        titleScreen.start();
    }
    void openWorldSelect() {
        if (titleScreen != null) titleScreen.stop();
        if (worldSelectScreen == null) worldSelectScreen = new WorldSelectScreen(this);
        worldSelectScreen.show();
    }

    void startGame(String worldName, int seed, int worldId, int mode) {
        if (titleScreen != null) titleScreen.stop();
        if (worldCanvas != null) { worldCanvas.stop(); worldCanvas = null; }
        loadingCanvas = new WorldLoadingCanvas(this, worldName, seed, worldId, mode);
        Display.getDisplay(this).setCurrent(loadingCanvas);
        loadingCanvas.start();
    }

    void worldLoaded(WorldCanvas loaded) {
        if (loadingCanvas != null) loadingCanvas.stopLoading();
        loadingCanvas = null;
        worldCanvas = loaded;
        Display.getDisplay(this).setCurrent(worldCanvas);
        worldCanvas.start();
    }

    void worldLoadFailed(String message) {
        if (loadingCanvas != null) loadingCanvas.stopLoading();
        loadingCanvas = null;
        if (titleScreen == null) titleScreen = new TitleScreen(this);
        titleScreen.showMainMenu();
        Display.getDisplay(this).setCurrent(titleScreen);
        titleScreen.start();
        showWorldError(message);
    }

    void showWorldError(String message) {
        Alert alert = new Alert("Worlds", message, null, AlertType.ERROR);
        alert.setTimeout(Alert.FOREVER);
        Display.getDisplay(this).setCurrent(alert, titleScreen);
    }
    void showPauseMenu() {
        if (worldCanvas != null) worldCanvas.pause();
    }
    void openPauseSettings() {
        saveWorld(worldCanvas);
        if (titleScreen == null) titleScreen = new TitleScreen(this);
        titleScreen.showPauseMenu();
        titleScreen.openSettings();
        Display.getDisplay(this).setCurrent(titleScreen);
        titleScreen.start();
    }
    void settingsChanged() {
        if (worldCanvas != null) worldCanvas.applySettingsChange();
    }
    void resumeGame() {
        if (titleScreen!=null) titleScreen.stop();
        Display.getDisplay(this).setCurrent(worldCanvas);
        if (worldCanvas!=null) worldCanvas.resume();
    }
    void returnToMainMenu() {
        saveWorld(worldCanvas);
        if (worldCanvas!=null) worldCanvas.stop();
        worldCanvas = null;
        if (titleScreen==null) titleScreen=new TitleScreen(this);
        titleScreen.showMainMenu();
        Display.getDisplay(this).setCurrent(titleScreen);
        titleScreen.start();
    }
    public void quitGame() {
        saveWorld(worldCanvas);
        if (titleScreen!=null) titleScreen.stop();
        if (worldCanvas!=null) worldCanvas.stop();
        if (loadingCanvas!=null) loadingCanvas.stopLoading();
        notifyDestroyed();
    }
    public void pauseApp() {
        saveWorld(worldCanvas);
        if (titleScreen!=null) titleScreen.stop();
        if (worldCanvas!=null) worldCanvas.pause();
    }
    public void destroyApp(boolean unconditional) {
        saveWorld(worldCanvas);
        if (titleScreen!=null) titleScreen.stop();
        if (worldCanvas!=null) worldCanvas.stop();
        if (loadingCanvas!=null) loadingCanvas.stopLoading();
    }
}
