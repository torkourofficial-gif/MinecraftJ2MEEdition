package mcj2me;

import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;

/** Custom dirt-tiled world menu with create, play and delete actions. */
final class WorldSelectScreen extends Canvas implements CommandListener {
    private final IndevViewerMIDlet owner;
    private WorldStore.WorldInfo[] worlds = new WorldStore.WorldInfo[0];
    private Image dirtTile;
    private int selected;
    private boolean creating;
    private int createSelection;
    private String worldName = "New World";
    private final Command editName = new Command("Edit name", Command.OK, 1);
    private final Command cancel = new Command("Cancel", Command.BACK, 1);
    private final Command deleteCommand = new Command("Delete world", Command.OK, 1);
    private int pendingDeleteId = -1;

    WorldSelectScreen(IndevViewerMIDlet midlet) {
        owner = midlet;
        setFullScreenMode(true);
        try {
            Image atlas = ResourceImages.load("/mcj2me/textures.png");
            int[] pixels = new int[16 * 16];
            atlas.getRGB(pixels, 0, 16, 2 * 16, 0, 16, 16);
            dirtTile = Image.createRGBImage(pixels, 16, 16, true);
        } catch (Exception ignored) { }
    }

    void show() {
        try {
            worlds = WorldStore.list();
            creating = false;
            if (selected > worlds.length) selected = worlds.length;
            Display.getDisplay(owner).setCurrent(this);
            repaint();
        } catch (Exception e) {
            owner.showWorldError("Could not read saved worlds.");
        }
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (dirtTile != null) {
            for (int y = 0; y < h; y += 16)
                for (int x = 0; x < w; x += 16)
                    g.drawImage(dirtTile, x, y, Graphics.TOP | Graphics.LEFT);
        } else {
            g.setColor(0x6B4326); g.fillRect(0, 0, w, h);
        }
        g.setColor(0x66000000); g.fillRect(0, 0, w, h);
        g.setColor(0xFFFFFF);
        if (creating) {
            g.drawString("Create New World", w / 2, 14, Graphics.TOP | Graphics.HCENTER);
            g.drawString("Choose a name and create your world", w / 2, 36, Graphics.TOP | Graphics.HCENTER);
            drawRow(g, 10, h / 2 - 36, w - 20, 25, "World name: " + worldName, createSelection == 0);
            drawRow(g, 10, h / 2 - 5, w - 20, 25, "Create New World", createSelection == 1);
            drawRow(g, 10, h / 2 + 26, w - 20, 25, "Cancel", createSelection == 2);
            g.drawString("UP/DOWN: choose   FIRE: select", w / 2, h - 25, Graphics.BOTTOM | Graphics.HCENTER);
            g.drawString("BACK: world list", w / 2, h - 8, Graphics.BOTTOM | Graphics.HCENTER);
            return;
        }
        g.drawString("Select World", w / 2, 8, Graphics.TOP | Graphics.HCENTER);
        g.drawString("ENTER: open/create   *: delete", w / 2, 27, Graphics.TOP | Graphics.HCENTER);
        int visible = Math.max(1, (h - 76) / 23);
        int first = selected - visible / 2;
        if (first < 0) first = 0;
        int maxFirst = Math.max(0, worlds.length - visible + 1);
        if (first > maxFirst) first = maxFirst;
        int y = 52;
        drawRow(g, 8, y, w - 16, 21, "Create New World...", selected == 0);
        y += 24;
        for (int i = first; i < worlds.length && i < first + visible - 1; i++) {
            drawRow(g, 8, y, w - 16, 21, worlds[i].name, selected == i + 1);
            y += 24;
        }
        g.setColor(0xFFFFFF);
        g.drawString("UP/DOWN: choose   FIRE: select", w / 2, h - 25, Graphics.BOTTOM | Graphics.HCENTER);
        g.drawString("BACK: main menu", w / 2, h - 8, Graphics.BOTTOM | Graphics.HCENTER);
    }

    private void drawRow(Graphics g, int x, int y, int width, int height, String text, boolean active) {
        g.setColor(active ? 0x777777 : 0x333333);
        g.fillRect(x, y, width, height);
        g.setColor(active ? 0xFFFF55 : 0xDDDDDD);
        g.drawRect(x, y, width - 1, height - 1);
        g.setColor(active ? 0xFFFFFF : 0xCCCCCC);
        String label = text;
        while (label.length() > 24) label = label.substring(0, label.length() - 1);
        g.drawString(label, x + 5, y + 3, Graphics.TOP | Graphics.LEFT);
    }

    protected void keyPressed(int keyCode) {
        int action = getGameAction(keyCode);
        if (creating) {
            if (isBackKey(keyCode)) { creating = false; repaint(); return; }
            if (action == UP || keyCode == '2') { createSelection = (createSelection + 2) % 3; repaint(); return; }
            if (action == DOWN || keyCode == '8') { createSelection = (createSelection + 1) % 3; repaint(); return; }
            if (action == FIRE || keyCode == '5' || keyCode == ' ' || keyCode == 10) {
                if (createSelection == 0) askName();
                else if (createSelection == 1) createWorld();
                else { creating = false; repaint(); }
            }
            return;
        }
        if (isBackKey(keyCode)) {
            owner.returnToMainMenu();
            return;
        }
        if (action == UP || keyCode == '2') {
            selected = (selected + worlds.length) % (worlds.length + 1);
            repaint(); return;
        }
        if (action == DOWN || keyCode == '8') {
            selected = (selected + 1) % (worlds.length + 1);
            repaint(); return;
        }
        if (keyCode == '*' || keyCode == 42) {
            if (selected > 0 && selected <= worlds.length) confirmDelete(worlds[selected - 1]);
            return;
        }
        if (action == FIRE || keyCode == '5' || keyCode == ' ' || keyCode == 10) {
            if (selected == 0) { creating = true; createSelection = 0; repaint(); }
            else if (selected > 0 && selected <= worlds.length) {
                WorldStore.WorldInfo world = worlds[selected - 1];
                try { owner.startGame(world.name, world.seed, world.id); }
                catch (Exception e) { owner.showWorldError("Could not open this world."); }
            }
        }
    }

    private void askName() {
        TextBox name = new TextBox("World name", worldName, 24, TextField.ANY);
        name.addCommand(editName);
        name.addCommand(cancel);
        name.setCommandListener(this);
        Display.getDisplay(owner).setCurrent(name);
    }

    private void createWorld() {
        try {
            WorldStore.WorldInfo world = WorldStore.create(worldName);
            creating = false;
            owner.startGame(world.name, world.seed, world.id);
        } catch (Exception e) {
            owner.showWorldError("Could not create this world.");
        }
    }

    private boolean isBackKey(int keyCode) {
        return keyCode == 27 || keyCode == 8 || keyCode == 127 || keyCode == -7 || keyCode == -6;
    }

    private void confirmDelete(WorldStore.WorldInfo world) {
        pendingDeleteId = world.id;
        javax.microedition.lcdui.Alert alert = new javax.microedition.lcdui.Alert(
            "Delete world", "Delete " + world.name + " permanently?", null,
            javax.microedition.lcdui.AlertType.WARNING);
        alert.addCommand(deleteCommand);
        alert.addCommand(cancel);
        alert.setCommandListener(this);
        Display.getDisplay(owner).setCurrent(alert, this);
    }

    public void commandAction(Command command, Displayable displayable) {
        if (displayable instanceof TextBox) {
            if (command == editName) {
                String value = ((TextBox) displayable).getString();
                if (value != null && value.trim().length() > 0) worldName = value.trim();
            }
            Display.getDisplay(owner).setCurrent(this);
            repaint();
        } else if (displayable instanceof javax.microedition.lcdui.Alert) {
            if (command == deleteCommand && pendingDeleteId > 0) {
                try {
                    WorldStore.delete(pendingDeleteId);
                    pendingDeleteId = -1;
                    show();
                } catch (Exception e) {
                    owner.showWorldError("Could not delete this world.");
                }
            } else {
                pendingDeleteId = -1;
                Display.getDisplay(owner).setCurrent(this);
            }
        }
    }
}
