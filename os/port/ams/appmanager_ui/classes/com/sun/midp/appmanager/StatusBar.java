/*
 * S100 shell - the status line at the top: signal, profile, time, battery.
 *
 * Painted by the Shell on every screen. On the idle screen it is drawn
 * over the wallpaper (white glyphs with a shadow); elsewhere on its own
 * light bar. The battery level is polled from sysfs once a minute
 * (Shell's minute timer calls refresh()).
 */

package com.sun.midp.appmanager;

import java.util.Calendar;
import javax.microedition.lcdui.Graphics;

final class StatusBar {
    private static int battery = -1;
    private static boolean charging;
    private static long lastPoll;

    private StatusBar() { }

    static void refresh() {
        long now = System.currentTimeMillis();
        if (now - lastPoll < 30000) {
            return;
        }
        lastPoll = now;
        battery = SysInfo.batteryPercent();
        charging = SysInfo.charging();
    }

    static int battery() {
        return battery;
    }

    static void paint(Graphics g, int w, boolean overlay) {
        int h = Theme.STATUS_H;
        int fg = overlay ? Theme.C_HOME_TEXT : Theme.C_TEXT;
        if (!overlay) {
            g.setColor(Theme.C_STATUS_BG);
            g.fillRect(0, 0, w, h);
            g.setColor(Theme.C_SOFT_LINE);
            g.drawLine(0, h - 1, w, h - 1);
        }
        // signal: the radio is KaiOS's, we never see it -> antenna with no bars
        int x = Theme.MARGIN;
        if (overlay) {
            g.setColor(Theme.C_HOME_SHADOW);
            paintAntenna(g, x + 1, 4);
        }
        g.setColor(fg);
        paintAntenna(g, x, 3);
        x += 14;
        if (Prefs.get(Prefs.PROFILE, "General").equals("Silent")) {
            paintBellOff(g, x, 3, fg, overlay);
            x += 14;
        }

        // clock (menus only; the idle screen has its own big clock)
        if (!overlay) {
            g.setColor(fg);
            g.drawString(Clock.time(), w / 2, 2, Graphics.TOP | Graphics.HCENTER);
        }

        // battery
        paintBattery(g, w - Theme.MARGIN - 20, 4, fg, overlay);
    }

    private static void paintAntenna(Graphics g, int x, int y) {
        g.drawLine(x + 4, y + 1, x + 4, y + 10);
        g.drawLine(x, y, x + 8, y);
        g.drawLine(x + 1, y + 1, x + 4, y + 4);
        g.drawLine(x + 7, y + 1, x + 4, y + 4);
    }

    private static void paintBellOff(Graphics g, int x, int y, int fg, boolean overlay) {
        if (overlay) {
            g.setColor(Theme.C_HOME_SHADOW);
            g.fillRoundRect(x + 2, y + 2, 7, 7, 4, 4);
        }
        g.setColor(fg);
        g.fillRoundRect(x + 1, y + 1, 7, 7, 4, 4);
        g.fillRect(x, y + 7, 9, 2);
        g.drawLine(x, y, x + 9, y + 10);
    }

    private static void paintBattery(Graphics g, int x, int y, int fg, boolean overlay) {
        if (overlay) {
            g.setColor(Theme.C_HOME_SHADOW);
            g.drawRect(x + 1, y + 1, 16, 9);
        }
        g.setColor(fg);
        g.drawRect(x, y, 16, 9);
        g.fillRect(x + 17, y + 3, 2, 4);
        if (battery < 0) {
            g.drawString("?", x + 8, y - 2, Graphics.TOP | Graphics.HCENTER);
            return;
        }
        int bars = (battery + 12) / 25;           // 0..4
        if (battery > 0 && bars == 0) {
            bars = 1;
        }
        for (int i = 0; i < bars; i++) {
            g.fillRect(x + 2 + i * 4, y + 2, 3, 6);
        }
        if (charging) {
            g.setColor(overlay ? 0xFFE060 : 0xD08000);
            g.drawLine(x + 9, y - 1, x + 6, y + 5);
            g.drawLine(x + 6, y + 5, x + 10, y + 5);
            g.drawLine(x + 10, y + 5, x + 7, y + 11);
        }
    }
}
