package mcj2me;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;

/** Keeps the UI responsive while terrain and the first M3G scene are prepared. */
final class WorldLoadingCanvas extends Canvas implements Runnable {
    private final IndevViewerMIDlet owner;
    private final String worldName;
    private final int seed;
    private Thread worker;
    private volatile boolean running;
    private volatile String status = "Generating terrain";
    private volatile int dots;

    WorldLoadingCanvas(IndevViewerMIDlet midlet, String name, int worldSeed) {
        owner = midlet;
        worldName = name;
        seed = worldSeed;
        setFullScreenMode(true);
    }

    synchronized void start() {
        if (running) return;
        running = true;
        worker = new Thread(this);
        worker.start();
    }

    synchronized void stopLoading() {
        running = false;
        if (worker != null) { worker.interrupt(); worker = null; }
    }

    public void run() {
        Thread animator = new Thread(new Runnable() {
            public void run() {
                while (running) {
                    dots = (dots + 1) % 4;
                    repaint();
                    try { Thread.sleep(250); } catch (InterruptedException ignored) { }
                }
            }
        });
        animator.start();
        WorldCanvas canvas = null;
        String failure = null;
        try {
            status = "Building terrain";
            canvas = new WorldCanvas(owner, worldName, seed);
            if (canvas.hasSetupError()) failure = canvas.getSetupError();
        } catch (OutOfMemoryError oom) {
            failure = "Not enough memory to load this world.";
        } catch (Throwable error) {
            failure = "Could not load world: " + error.toString();
        }
        running = false;
        animator.interrupt();
        if (failure != null) owner.worldLoadFailed(failure);
        else owner.worldLoaded(canvas);
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        g.setColor(0x87BFEA); g.fillRect(0, 0, w, h);
        g.setColor(0xFFFFFF);
        g.drawString("Minecraft J2ME Edition", w / 2, h / 3, Graphics.TOP | Graphics.HCENTER);
        g.setColor(0x333333);
        g.drawString("Loading world", w / 2, h / 2 - 20, Graphics.TOP | Graphics.HCENTER);
        g.drawString(worldName, w / 2, h / 2 + 2, Graphics.TOP | Graphics.HCENTER);
        String suffix = dots == 0 ? "" : (dots == 1 ? "." : (dots == 2 ? ".." : "..."));
        g.drawString(status + suffix, w / 2, h / 2 + 28, Graphics.TOP | Graphics.HCENTER);
        int bw = Math.min(w - 32, 180), bx = (w - bw) / 2, by = h / 2 + 52;
        g.setColor(0xFFFFFF); g.drawRect(bx, by, bw, 8);
        int fill = (dots + 1) * bw / 4;
        g.fillRect(bx + 1, by + 1, Math.max(1, fill - 1), 7);
        g.setColor(0xFFFFFF);
        g.drawString("Preparing nearby chunks", w / 2, by + 20, Graphics.TOP | Graphics.HCENTER);
    }
}
