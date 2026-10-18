package mcj2me;

import javax.microedition.lcdui.Choice;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.List;
import javax.microedition.lcdui.TextBox;
import javax.microedition.lcdui.TextField;

/** Native, compact world picker suitable for small MIDP phone screens. */
final class WorldSelectScreen implements CommandListener {
    private final IndevViewerMIDlet owner;
    private List list;
    private WorldStore.WorldInfo[] worlds;
    private final Command back = new Command("Back", Command.BACK, 1);
    private final Command create = new Command("Create", Command.OK, 1);
    private final Command open = new Command("Play", Command.OK, 1);
    private final Command delete = new Command("Delete", Command.ITEM, 2);
    private final Command confirmDelete = new Command("Delete world", Command.OK, 1);
    private int pendingDeleteId = -1;
    private final Command cancel = new Command("Cancel", Command.BACK, 1);

    WorldSelectScreen(IndevViewerMIDlet midlet) { owner = midlet; }

    void show() {
        try {
            worlds = WorldStore.list();
            list = new List("Select world", Choice.IMPLICIT);
            list.append("Create new world...", null);
            for (int i = 0; i < worlds.length; i++) list.append(worlds[i].name, null);
            list.addCommand(back);
            list.addCommand(delete);
            list.setCommandListener(this);
            Display.getDisplay(owner).setCurrent(list);
        } catch (Exception e) {
            owner.showWorldError("Could not read saved worlds.");
        }
    }

    public void commandAction(Command command, Displayable displayable) {
        if (displayable == list) {
            if (command == back) { owner.returnToMainMenu(); return; }
            int selected = list.getSelectedIndex();
            if (command == delete) {
                if (selected > 0 && selected <= worlds.length) confirmDelete(worlds[selected - 1]);
                return;
            }
            if (selected == 0 || command == create) { askName(); return; }
            if (selected > 0 && selected <= worlds.length) {
                WorldStore.WorldInfo world = worlds[selected - 1];
                owner.startGame(world.name, world.seed, world.id);
            }
        } else if (displayable instanceof javax.microedition.lcdui.Alert) {
            if (command == confirmDelete) {
                try {
                    if (pendingDeleteId > 0) WorldStore.delete(pendingDeleteId);
                    pendingDeleteId = -1;
                    show();
                } catch (Exception e) {
                    owner.showWorldError("Could not delete this world.");
                }
            } else {
                pendingDeleteId = -1;
                Display.getDisplay(owner).setCurrent(list);
            }
        } else if (displayable instanceof TextBox) {
            if (command == create) {
                try {
                    WorldStore.WorldInfo world = WorldStore.create(((TextBox)displayable).getString());
                    owner.startGame(world.name, world.seed, world.id);
                } catch (Exception e) {
                    owner.showWorldError("Could not save this world.");
                }
            } else if (command == cancel) {
                Display.getDisplay(owner).setCurrent(list);
            }
        }
    }

    private void confirmDelete(WorldStore.WorldInfo world) {
        pendingDeleteId = world.id;
        javax.microedition.lcdui.Alert alert = new javax.microedition.lcdui.Alert(
            "Delete world", "Delete " + world.name + " permanently?", null,
            javax.microedition.lcdui.AlertType.WARNING);
        alert.addCommand(confirmDelete);
        alert.addCommand(cancel);
        alert.setCommandListener(this);
        Display.getDisplay(owner).setCurrent(alert, list);
    }

    private void askName() {
        TextBox name = new TextBox("Create world", "My World", 24, TextField.ANY);
        name.addCommand(create);
        name.addCommand(cancel);
        name.setCommandListener(this);
        Display.getDisplay(owner).setCurrent(name);
    }
}
