/*
 * S100 shell - renders one MenuItem's children, Nokia style.
 *
 * The main menu is a 3-column icon grid (Settings > Display > Menu view
 * can switch it to a list); submenus are lists. Number keys 1-9 open
 * the n-th entry. From the main menu, '*' pressed right after opening it
 * from the idle screen locks the keypad (Menu, * = Nokia key lock).
 *
 * Installed MIDlet suites are part of the main menu (MenuManager), so the
 * grid scrolls by whole rows once there are more than nine entries and
 * the rows are rebuilt (refresh) when the AMS installs or removes one.
 */

package com.sun.midp.appmanager;

import javax.microedition.lcdui.Graphics;

class MenuScreen extends ListScreen {
    private static final int COLS = 3;
    private static final int CELL_W = 76;
    private static final int CELL_H = 68;

    private final MenuItem node;
    private final boolean isRoot;
    private boolean grid;
    private long openedAt;
    /** First visible grid row. */
    private int firstRow;

    MenuScreen(MenuItem node, boolean isRoot) {
        super(node.label);
        this.node = node;
        this.isRoot = isRoot;
        numberKeys = true;
        refresh();
    }

    /** Rebuilds the rows from node.children (suites come and go). */
    void refresh() {
        items.removeAllElements();
        for (int i = 0; i < node.children.length; i++) {
            MenuItem c = node.children[i];
            Item it = new Item(c.label, c);
            if (isRoot) {
                // list view of the main menu: small icons in front of the names
                it.icon(c.rowImage != null ? c.rowImage : Icons.small(c.icon));
            }
            add(it);
        }
    }

    /** Also accepts a suite (notifySuiteInstalledExt): highlight its entry. */
    void selectData(Object data) {
        if (data instanceof RunningMIDletSuiteInfo) {
            String id = MenuManager.appId((RunningMIDletSuiteInfo) data);
            for (int i = 0; i < items.size(); i++) {
                if (id.equals(((MenuItem) ((Item) items.elementAt(i)).data).id)) {
                    selected = i;
                    return;
                }
            }
            return;
        }
        super.selectData(data);
    }

    void onShow() {
        grid = isRoot && Prefs.get(Prefs.MENU_VIEW, "grid").equals("grid");
        if (openedAt == 0) {
            openedAt = System.currentTimeMillis();
        }
        refresh();
        clamp();
    }

    String softLeft() { return "Select"; }
    String softRight() { return isRoot ? "Exit" : "Back"; }

    void select(Item it) {
        shell.menus.open((MenuItem) it.data);
    }

    void back() {
        if (isRoot) {
            shell.goHome();
        } else {
            shell.pop();
        }
    }

    boolean key(int k) {
        if (grid) {
            int n = items.size();
            switch (k) {
            case Keymap.LEFT:
                selected = (selected + n - 1) % n;
                repaint();
                return true;
            case Keymap.RIGHT:
                selected = (selected + 1) % n;
                repaint();
                return true;
            case Keymap.UP:
                selected = (selected - COLS + n) % n;
                repaint();
                return true;
            case Keymap.DOWN:
                selected = (selected + COLS) % n;
                repaint();
                return true;
            }
        }
        if (k == Keymap.STAR && isRoot
                && System.currentTimeMillis() - openedAt < 2500) {
            shell.home().lock();
            shell.goHome();
            return true;
        }
        return super.key(k);
    }

    void paint(Graphics g, int x, int y, int w, int h) {
        if (!grid) {
            super.paint(g, x, y, w, h);
            return;
        }
        int n = items.size();
        if (n == 0) {
            super.paint(g, x, y, w, h);
            return;
        }
        int rows = (n + COLS - 1) / COLS;
        int labelH = Theme.FONT_H + 12;
        int vis = Math.max(1, (h - 6 - labelH) / CELL_H);
        int selRow = selected / COLS;
        if (selRow < firstRow) {
            firstRow = selRow;
        } else if (selRow >= firstRow + vis) {
            firstRow = selRow - vis + 1;
        }
        if (firstRow > Math.max(0, rows - vis)) {
            firstRow = Math.max(0, rows - vis);
        }
        int shown = Math.min(vis, rows - firstRow);
        int gridW = COLS * CELL_W;
        int gx = x + (w - gridW) / 2;
        int gy = y + 6;
        for (int i = firstRow * COLS; i < n && i < (firstRow + vis) * COLS; i++) {
            MenuItem mi = (MenuItem) ((Item) items.elementAt(i)).data;
            int cx = gx + (i % COLS) * CELL_W;
            int cy = gy + (i / COLS - firstRow) * CELL_H;
            if (i == selected) {
                g.setColor(Theme.C_SEL_LIGHT);
                g.fillRoundRect(cx + 6, cy + 2, CELL_W - 12, CELL_H - 8, 10, 10);
                g.setColor(Theme.C_SEL);
                g.drawRoundRect(cx + 6, cy + 2, CELL_W - 13, CELL_H - 9, 10, 10);
            }
            Icons.draw(g, mi.icon, mi.image, cx, cy + 4, CELL_W, Icons.SIZE + 8);
            if (i < 9) {
                g.setColor(Theme.C_TEXT_DIM);
                g.drawString(String.valueOf(i + 1), cx + CELL_W - 12, cy + 4,
                             Graphics.TOP | Graphics.LEFT);
            }
        }
        if (rows > vis) {
            Theme.scrollBar(g, x + w - 6, y, h - labelH, rows, vis, firstRow);
        }
        // name of the selected entry under the grid, as Series 40 does
        Item cur = current();
        if (cur != null) {
            int ly = gy + shown * CELL_H + 6;
            g.setColor(Theme.C_SOFT_LINE);
            g.drawLine(x + 12, ly - 3, x + w - 12, ly - 3);
            g.setColor(Theme.C_TEXT);
            Theme.bold(g, cur.label, x + w / 2, ly + 2, Graphics.TOP | Graphics.HCENTER);
        }
    }
}
