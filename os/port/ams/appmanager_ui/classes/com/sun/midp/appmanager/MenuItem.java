/*
 * S100 shell - one entry of the menu tree (see MenuManager).
 */

package com.sun.midp.appmanager;

import javax.microedition.lcdui.Image;

class MenuItem {
    /** Stable id ("settings.display"), used by shortcuts and openId(). */
    final String id;
    final String label;
    /** Icon resource name (Icons.get) or null. */
    final String icon;
    /** Ready-made picture (installed suites), takes precedence over icon. */
    Image image;
    /** Smaller copy of image for list rows (Icons.ROW), or null. */
    Image rowImage;
    /** Sub menu, or null for a leaf. */
    MenuItem[] children;
    /** Leaf action. */
    Runnable action;

    MenuItem(String id, String label, String icon, MenuItem[] children) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.children = children;
    }

    MenuItem(String id, String label, String icon, Runnable action) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.action = action;
    }

    boolean isMenu() {
        return children != null;
    }
}
