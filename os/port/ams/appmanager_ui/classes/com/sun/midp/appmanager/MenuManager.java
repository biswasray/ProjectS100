/*
 * S100 shell - the menu tree and how to get around it.
 *
 * Builds the Nokia-style main menu (Messaging, Contacts, Log, Settings,
 * Organiser, Applications) from the feature modules, opens items
 * (submenu -> MenuScreen, leaf -> action) and resolves the ids that the
 * idle-screen shortcuts (Keymap) and openId() use.
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

    private MenuItem root;

    MenuManager(Shell shell) {
        this.shell = shell;
        messaging = new Messaging(shell);
        contacts = new Contacts(shell);
        log = new CallLog(shell);
        settings = new SettingsMenu(shell);
        organiser = new Organiser(shell);
        apps = new AppsMenu(shell);
    }

    MenuItem root() {
        if (root == null) {
            root = new MenuItem("menu", "Menu", null, new MenuItem[] {
                new MenuItem("messaging", "Messaging", "messaging", messaging.items()),
                new MenuItem("contacts", "Contacts", "contacts", contacts.items()),
                new MenuItem("log", "Log", "log", log.items()),
                new MenuItem("settings", "Settings", "settings", settings.items()),
                new MenuItem("organiser", "Organiser", "organiser", organiser.items()),
                new MenuItem("applications", "Applications", "applications", apps.items()),
            });
        }
        return root;
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
