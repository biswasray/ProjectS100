/*
 * S100 shell - the menu tree and how to get around it.
 *
 * Builds the Nokia-style main menu (Messaging, Contacts, Log, Settings,
 * Organiser, Applications, Camera) from the feature modules, opens items
 * (submenu -> MenuScreen, leaf -> action) and resolves the ids that the
 * idle-screen shortcuts (Keymap) and openId() use.
 *
 * Every installed MIDlet suite is also a main-menu entry ("app.<suiteId>",
 * icon from the JAR or the default app tile, see Icons.appIcon) right after
 * the built-in ones, so a freshly installed .jad/.jar shows up in the Menu
 * at once: AppManagerUIImpl calls appsChanged() on every suite event.
 */

package com.sun.midp.appmanager;

import java.util.Vector;

class MenuManager {
    final Shell shell;
    final Messaging messaging;
    final Contacts contacts;
    final CallLog log;
    final SettingsMenu settings;
    final Organiser organiser;
    final AppsMenu apps;
    final Camera camera;
    final FileManager files;
    final Connectivity connectivity;
    final NetworkSettings network;
    final LocationSettings location;
    final SecuritySettings security;
    final MediaPlayer media;

    private MenuItem root;
    /** The fixed part of the main menu, built once. */
    private MenuItem[] builtin;

    MenuManager(Shell shell) {
        this.shell = shell;
        messaging = new Messaging(shell);
        contacts = new Contacts(shell);
        log = new CallLog(shell);
        settings = new SettingsMenu(shell);
        organiser = new Organiser(shell);
        apps = new AppsMenu(shell);
        camera = new Camera(shell);
        files = new FileManager(shell);
        connectivity = new Connectivity(shell);
        network = new NetworkSettings(shell);
        location = new LocationSettings(shell);
        security = new SecuritySettings(shell);
        media = new MediaPlayer(shell);
    }

    MenuItem root() {
        if (root == null) {
            root = new MenuItem("menu", "Menu", null, (MenuItem[]) null);
            root.children = buildRoot();
        }
        return root;
    }

    private MenuItem[] buildRoot() {
        if (builtin == null) {
            builtin = new MenuItem[] {
                new MenuItem("messaging", "Messaging", "messaging", messaging.items()),
                new MenuItem("contacts", "Contacts", "contacts", contacts.items()),
                new MenuItem("log", "Log", "log", log.items()),
                new MenuItem("settings", "Settings", "settings", settings.items()),
                new MenuItem("organiser", "Organiser", "organiser", organiser.items()),
                new MenuItem("applications", "Applications", "applications", apps.items()),
                camera.item(),
            };
        }
        Vector v = new Vector();
        for (int i = 0; i < builtin.length; i++) {
            v.addElement(builtin[i]);
        }
        Vector suites = shell.ams.userSuites();
        for (int i = 0; i < suites.size(); i++) {
            v.addElement(appItem((RunningMIDletSuiteInfo) suites.elementAt(i)));
        }
        MenuItem[] out = new MenuItem[v.size()];
        v.copyInto(out);
        return out;
    }

    /** Main-menu entry of one installed suite. */
    private MenuItem appItem(final RunningMIDletSuiteInfo si) {
        MenuItem it = new MenuItem(appId(si), si.displayName, "app", new Runnable() {
            public void run() { shell.ams.open(si); }
        });
        it.image = Icons.appIcon(si, Icons.SIZE);
        it.rowImage = Icons.appIcon(si, Icons.ROW);
        return it;
    }

    static String appId(RunningMIDletSuiteInfo si) {
        return "app." + si.suiteId;
    }

    /**
     * A suite was installed, removed or got a new icon: rebuild the main
     * menu in place (the MenuScreen on the stack keeps pointing at root
     * and re-reads its children in refresh()).
     */
    void appsChanged() {
        if (root != null) {
            root.children = buildRoot();
        }
    }

    /** Opens the main menu from the idle screen. */
    void openMain() {
        shell.push(new MenuScreen(root(), true));
    }

    void open(MenuItem item) {
        if (item == null) {
            return;
        }
        if (item.isMenu()) {
            shell.push(new MenuScreen(item, false));
        } else if (item.action != null) {
            item.action.run();
        }
    }

    boolean openId(String id) {
        MenuItem it = find(id);
        if (it == null) {
            return false;
        }
        open(it);
        return true;
    }

    MenuItem find(String id) {
        return find(root(), id);
    }

    private static MenuItem find(MenuItem node, String id) {
        if (node.id.equals(id)) {
            return node;
        }
        if (node.children != null) {
            for (int i = 0; i < node.children.length; i++) {
                MenuItem r = find(node.children[i], id);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Human readable path of an item ("Settings > Display"), or the id. */
    String label(String id) {
        String p = path(root(), id, null);
        return (p == null) ? id : p;
    }

    private static String path(MenuItem node, String id, String prefix) {
        String here = (prefix == null) ? null
            : (prefix.length() == 0 ? node.label : prefix + " > " + node.label);
        if (node.id.equals(id)) {
            return here == null ? node.label : here;
        }
        if (node.children != null) {
            String next = (prefix == null) ? "" : here;
            for (int i = 0; i < node.children.length; i++) {
                String r = path(node.children[i], id, next);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    /** Every leaf item, for the shortcut picker (Settings > My shortcuts). */
    Vector leaves() {
        Vector v = new Vector();
        collect(root(), v);
        return v;
    }

    private static void collect(MenuItem node, Vector v) {
        if (node.children == null) {
            v.addElement(node);
            return;
        }
        for (int i = 0; i < node.children.length; i++) {
            collect(node.children[i], v);
        }
    }
}
