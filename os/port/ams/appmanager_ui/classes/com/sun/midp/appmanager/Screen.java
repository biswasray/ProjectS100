/*
 * S100 shell - one screen in the shell's navigation stack.
 *
 * A Screen paints the content area between the status bar and the soft
 * key bar and receives logical keys (Keymap). The Shell draws the title
 * bar for it when title != null and the soft key labels it returns.
 */

package com.sun.midp.appmanager;

import javax.microedition.lcdui.Graphics;

abstract class Screen {
    /** Owning shell, set by Shell.push(). */
    Shell shell;
    /** Title bar text; null = no title bar (the idle screen). */
    String title;

    Screen(String title) {
        this.title = title;
    }

    /** Text for the right corner of the title bar (e.g. "Abc"), or null. */
    String titleRight() { return null; }

    /** Soft key labels: left / centre / right. Null hides a label. */
    String softLeft() { return null; }
    String softMid() { return null; }
    String softRight() { return "Back"; }

    /** Content area paint; (x, y, w, h) excludes status, title and soft bars. */
    abstract void paint(Graphics g, int x, int y, int w, int h);

    /**
     * A key went down (logical key from Keymap).
     * @return true if consumed; false lets the shell apply the defaults
     *         (right soft key = back(), End = home)
     */
    boolean key(int key) { return false; }

    /** Held for Keymap.LONG_PRESS_MS; key() for the same press was already delivered. */
    void keyLong(int key) { }

    /** Auto-repeat while held (used for scrolling). */
    void keyRepeat(int key) { }

    /** Called when the screen becomes the top of the stack. */
    void onShow() { }

    /** Called when the screen is covered or popped. */
    void onHide() { }

    /** Called once a minute while on top (clock refresh). */
    void tick() { }

    /** Whether the status bar is painted over the content (idle screen). */
    boolean overlayStatus() { return false; }

    /** True while the screen timeout must not turn the backlight off (video, camera). */
    boolean keepScreenOn() { return false; }

    /** Default right soft key behaviour: leave this screen. */
    void back() {
        shell.pop();
    }

    /** Shorthand used by every screen. */
    void repaint() {
        if (shell != null) {
            shell.repaint();
        }
    }
}
