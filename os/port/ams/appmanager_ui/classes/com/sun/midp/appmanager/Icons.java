/*
 * S100 shell - icon loader.
 *
 * The PNGs in os/port/ams/icons are converted by the MIDP build into
 * appdb/<name>.raw (the AMS image format) and read back here through the
 * same path the installer uses for its own pictures. Missing icons (for
 * instance when the resources were not deployed) fall back to a drawn
 * placeholder so the menu still works.
 */

package com.sun.midp.appmanager;

import java.util.Hashtable;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;
import com.sun.midp.installer.GraphicalInstaller;

final class Icons {
    static final int SIZE = 40;
    private static final Hashtable cache = new Hashtable();
    private static final Object MISSING = new Object();

    private Icons() { }

    /** Loads "s100_<name>"; null if the resource is not there. */
    static Image get(String name) {
        if (name == null) {
            return null;
        }
        Object o = cache.get(name);
        if (o == MISSING) {
            return null;
        }
        if (o != null) {
            return (Image) o;
        }
        Image img = null;
        try {
            img = GraphicalInstaller.getImageFromInternalStorage("s100_" + name);
        } catch (Throwable t) {
            // fall through: placeholder
        }
        cache.put(name, img == null ? MISSING : (Object) img);
        return img;
    }

    /** Draws the icon (or a placeholder) centred in a box. */
    static void draw(Graphics g, String name, Image explicit, int x, int y,
                     int w, int h) {
        Image img = (explicit != null) ? explicit : get(name);
        if (img != null) {
            Theme.imageCentered(g, img, x, y, w, h);
            return;
        }
        int s = Math.min(SIZE, Math.min(w, h)) - 8;
        int ix = x + (w - s) / 2, iy = y + (h - s) / 2;
        g.setColor(Theme.C_SEL_LIGHT);
        g.fillRoundRect(ix, iy, s, s, 8, 8);
        g.setColor(Theme.C_SEL);
        g.drawRoundRect(ix, iy, s - 1, s - 1, 8, 8);
        g.setColor(Theme.C_SEL_TEXT);
        String ch = (name == null || name.length() == 0) ? "?"
            : name.substring(0, 1).toUpperCase();
        Theme.bold(g, ch, ix + s / 2, iy + (s - Theme.FONT_H) / 2,
                   Graphics.TOP | Graphics.HCENTER);
    }
}
