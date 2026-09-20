/*
 * S100 shell - a scrolling list with a title bar, the workhorse screen.
 *
 * Subclasses (usually anonymous) fill `items` and override select(),
 * options() and/or refresh(). Each row shows a label, an optional
 * second line and an optional icon. Left soft key = "Select" (or
 * "Options" when optionsMenu() returns something), right = "Back".
 */

package com.sun.midp.appmanager;

import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

class ListScreen extends Screen {
    static class Item {
        String label;
        String sub;
        Image icon;
        Object data;
        /** Right-aligned value text (settings lists). */
        String value;

        Item(String label) {
            this.label = label;
        }

        Item(String label, Object data) {
            this.label = label;
            this.data = data;
        }

        Item(String label, String sub, Object data) {
            this.label = label;
            this.sub = sub;
            this.data = data;
        }

        Item icon(Image i) {
            icon = i;
            return this;
        }

        Item value(String v) {
            value = v;
            return this;
        }
    }

    final Vector items = new Vector();
    int selected;
    int first;
    /** Shown centred when the list is empty. */
    String emptyText = "(empty)";
    /** Row height: 24 for single line, 34 with sub text. */
    int rowH = Theme.ROW_H;
    /** Number keys jump to and open the n-th row (menus). */
    boolean numberKeys;

    ListScreen(String title) {
        super(title);
    }

    /* ----- hooks ----- */

    /** Rebuilds `items`; called from onShow(). */
    void refresh() { }

    /** Centre key / "Select" on the current item. */
    void select(Item it) { }

    /** Option labels for the left soft key menu, or null for plain "Select". */
    String[] optionsMenu(Item it) { return null; }

    /** An option was chosen from optionsMenu(). */
    void option(Item it, int index) { }

    /** Clear key on an item (e.g. delete). */
    void clear(Item it) { }

    /* ----- helpers ----- */

    Item add(String label) {
        Item it = new Item(label);
        items.addElement(it);
        return it;
    }

    Item add(Item it) {
        items.addElement(it);
        return it;
    }

    Item current() {
        if (items.isEmpty() || selected < 0 || selected >= items.size()) {
            return null;
        }
        return (Item) items.elementAt(selected);
    }

    void clamp() {
        if (selected >= items.size()) {
            selected = items.size() - 1;
        }
        if (selected < 0) {
            selected = 0;
        }
    }

    void selectData(Object data) {
        for (int i = 0; i < items.size(); i++) {
            if (((Item) items.elementAt(i)).data == data) {
                selected = i;
                return;
            }
        }
    }

    void onShow() {
        refresh();
        clamp();
    }

    String softLeft() {
        Item it = current();
        if (it == null) {
            return null;
        }
        return optionsMenu(it) != null ? "Options" : "Select";
    }

    private int visibleRows(int h) {
        return Math.max(1, h / rowH);
    }

    void paint(Graphics g, int x, int y, int w, int h) {
        int n = items.size();
        if (n == 0) {
            g.setColor(Theme.C_TEXT_DIM);
            g.drawString(emptyText, x + w / 2, y + h / 3, Graphics.TOP | Graphics.HCENTER);
            return;
        }
        int rows = visibleRows(h);
        if (selected < first) {
            first = selected;
        } else if (selected >= first + rows) {
            first = selected - rows + 1;
        }
        boolean scroll = n > rows;
        int textRight = x + w - Theme.MARGIN - (scroll ? 8 : 0);
        for (int i = 0; i < rows && first + i < n; i++) {
            Item it = (Item) items.elementAt(first + i);
            int ry = y + i * rowH;
            boolean sel = (first + i == selected);
            if (sel) {
                Theme.selection(g, x + 1, ry, w - 2 - (scroll ? 7 : 0), rowH);
            }
            int tx = x + Theme.MARGIN + 2;
            if (it.icon != null) {
                Theme.imageCentered(g, it.icon, tx, ry, 20, rowH);
                tx += 24;
            }
            int labelW = textRight - tx;
            int ty = ry + (rowH - Theme.FONT_H) / 2;
            if (it.sub != null) {
                ty = ry + 2;
            }
            if (it.value != null) {
                g.setColor(sel ? Theme.C_SEL_TEXT : Theme.C_TEXT_DIM);
                String v = Theme.fit(it.value, labelW / 2);
                g.drawString(v, textRight, ty, Graphics.TOP | Graphics.RIGHT);
                labelW -= Theme.FONT.stringWidth(v) + 6;
            }
            g.setColor(sel ? Theme.C_SEL_TEXT : Theme.C_TEXT);
            g.drawString(Theme.fit(it.label, labelW), tx, ty, Graphics.TOP | Graphics.LEFT);
            if (it.sub != null) {
                g.setColor(sel ? Theme.C_SEL_TEXT : Theme.C_TEXT_DIM);
                g.drawString(Theme.fit(it.sub, labelW), tx, ty + Theme.FONT_H,
                             Graphics.TOP | Graphics.LEFT);
            }
        }
        Theme.scrollBar(g, x + w - 6, y, h, n, rows, first);
    }

    boolean key(int k) {
        int n = items.size();
        switch (k) {
        case Keymap.UP:
            if (n > 0) {
                selected = (selected + n - 1) % n;
                repaint();
            }
            return true;
        case Keymap.DOWN:
            if (n > 0) {
                selected = (selected + 1) % n;
                repaint();
            }
            return true;
        case Keymap.SELECT:
            if (current() != null) {
                select(current());
            }
            return true;
        case Keymap.SOFT_L: {
            Item it = current();
            if (it == null) {
                return true;
            }
            final String[] opts = optionsMenu(it);
            if (opts == null) {
                select(it);
            } else {
                final Item target = it;
                shell.showPopup(Popup.menu("Options", opts, new Popup.Listener() {
                    public void onResult(int r) {
                        option(target, r);
                    }
                }));
            }
            return true;
        }
        case Keymap.CLEAR:
            if (current() != null) {
                clear(current());
            }
            return true;
        default:
            if (numberKeys && Keymap.isDigit(k) && k != '0') {
                int i = k - '1';
                if (i < n) {
                    selected = i;
                    repaint();
                    select(current());
                }
                return true;
            }
            return false;
        }
    }

    void keyRepeat(int k) {
        if (k == Keymap.UP || k == Keymap.DOWN) {
            key(k);
        }
    }
}
