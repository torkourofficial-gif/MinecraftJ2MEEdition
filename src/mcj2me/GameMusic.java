package mcj2me;

import java.io.InputStream;
import java.util.Random;
import javax.microedition.media.Manager;
import javax.microedition.media.Player;
import javax.microedition.media.PlayerListener;
import javax.microedition.media.control.VolumeControl;

/**
 * Beta-style background music: loop menu.mid in the menus, then play one
 * randomly selected calm track after a ten-second silent delay in a world.
 * Calm tracks are separated by another ten seconds of silence.
 */
final class GameMusic implements Runnable, PlayerListener {
    private static final int STOPPED = 0;
    private static final int MENU = 1;
    private static final int WORLD = 2;
    private static final String[] CALM_TRACKS = {
        "/mcj2me/calm1.mid", "/mcj2me/calm2.mid",
        "/mcj2me/calm3.mid", "/mcj2me/calm4.mid"
    };

    private final Random random = new Random();
    private volatile int mode = STOPPED;
    private volatile boolean trackEnded;
    private Thread worker;
    private Player player;
    private int lastCalm = -1;
    private volatile int volume = 70;

    synchronized void setVolume(int level) {
        if (level < 0) level = 0;
        if (level > 100) level = 100;
        volume = level;
        applyVolume(player);
    }

    synchronized int getVolume() { return volume; }

    private void applyVolume(Player target) {
        if (target == null) return;
        try {
            VolumeControl control = (VolumeControl) target.getControl("VolumeControl");
            if (control != null) control.setLevel(volume);
        } catch (Exception ignored) { /* Volume control is optional in MMAPI. */ }
    }

    synchronized void startMenu() {
        switchMode(MENU);
    }

    synchronized void startWorld() {
        switchMode(WORLD);
    }

    synchronized void stop() {
        switchMode(STOPPED);
    }

    private void switchMode(int nextMode) {
        if (mode == nextMode && worker != null && worker.isAlive()) return;
        mode = nextMode;
        closePlayer();
        if (worker != null) worker.interrupt();
        worker = null;
        if (nextMode != STOPPED) {
            worker = new Thread(this);
            worker.start();
        }
    }

    public void run() {
        int thisMode = mode;
        if (thisMode == MENU) {
            playMenu();
            return;
        }
        if (thisMode != WORLD) return;
        if (!sleepWhileMode(10000, WORLD)) return;
        while (mode == WORLD) {
            int choice = chooseCalm();
            playTrack(CALM_TRACKS[choice], WORLD, false);
            if (mode != WORLD) return;
            if (!sleepWhileMode(10000, WORLD)) return;
        }
    }

    private int chooseCalm() {
        if (CALM_TRACKS.length <= 1) return 0;
        int next;
        do { next = random.nextInt(CALM_TRACKS.length); }
        while (next == lastCalm);
        lastCalm = next;
        return next;
    }

    private void playMenu() {
        while (mode == MENU) {
            if (!playTrack("/mcj2me/menu.mid", MENU, true)) {
                if (!sleepWhileMode(3000, MENU)) return;
            } else return;
        }
    }

    private boolean playTrack(String resource, int expectedMode, boolean loop) {
        InputStream input = null;
        Player created = null;
        try {
            input = getClass().getResourceAsStream(resource);
            if (input == null) return false;
            created = Manager.createPlayer(input, "audio/midi");
            created.addPlayerListener(this);
            created.setLoopCount(loop ? -1 : 1);
            created.realize();
            created.prefetch();
            synchronized (this) {
                if (mode != expectedMode) {
                    created.close();
                    return false;
                }
                player = created;
                trackEnded = false;
                applyVolume(created);
            }
            created.start();
            if (loop) {
                while (mode == expectedMode && created.getState() == Player.STARTED)
                    Thread.sleep(300);
            } else {
                while (mode == expectedMode && !trackEnded)
                    Thread.sleep(250);
            }
            return true;
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception ignored) {
            // Some devices/emulators lack MIDI playback; gameplay must still work.
            return false;
        } finally {
            if (created != null) {
                created.removePlayerListener(this);
                try { created.stop(); } catch (Exception ignored) { }
                created.close();
            }
            if (input != null) try { input.close(); } catch (Exception ignored) { }
            synchronized (this) {
                if (player == created) player = null;
            }
        }
    }

    public void playerUpdate(Player source, String event, Object eventData) {
        if (source == player && PlayerListener.END_OF_MEDIA.equals(event))
            trackEnded = true;
    }

    private boolean sleepWhileMode(long millis, int expectedMode) {
        long end = System.currentTimeMillis() + millis;
        while (mode == expectedMode) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return true;
            try { Thread.sleep(left > 250 ? 250 : left); }
            catch (InterruptedException ignored) { return false; }
        }
        return false;
    }

    private synchronized void closePlayer() {
        Player old = player;
        player = null;
        if (old != null) {
            try { old.stop(); } catch (Exception ignored) { }
            old.close();
        }
    }
}
