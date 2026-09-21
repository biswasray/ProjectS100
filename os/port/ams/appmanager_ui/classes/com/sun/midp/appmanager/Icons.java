/*
 * S100 shell - icon loader.
 *
 * The PNGs in os/port/ams/icons are converted by the MIDP build into
 * appdb/<name>.raw (the AMS image format) and read back here through the
 * same path the installer uses for its own pictures. Missing icons (for
 * instance when the resources were not deployed) fall back to a drawn
 * placeholder so the menu still works.
 *
 * appIcon() gives the picture of an installed MIDlet suite for the main
 * menu: the icon shipped in the JAR (MIDlet-Icon / MIDlet-1), resized to
 * the menu cell, or our s100_app tile when the suite has none. The AMS
 * never leaves RunningMIDletSuiteInfo.icon null - it substitutes the
 * reference _ch_single/_ch_suite pictures - so those are recognised by
 * content and treated as "no icon".
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

    /** Row icon size for lists (Collection, Running, list-view menu). */
    static final int ROW = 20;
    private static final Hashtable smallCache = new Hashtable();

    /** The named icon shrunk to ROW pixels for list rows; null if missing. */
    static Image small(String name) {
        if (name == null) {
            return null;
        }
        Object o = smallCache.get(name);
        if (o == MISSING) {
            return null;
        }
        if (o != null) {
            return (Image) o;
        }
        Image big = get(name);
        Image img = (big == null) ? null : fit(big, ROW);
        smallCache.put(name, img == null ? MISSING : (Object) img);
        return img;
    }

    /* ---------------- installed suite icons ---------------- */

    /** original Image -> Image[2] {fitted to SIZE, fitted to ROW}. */
    private static final Hashtable appCache = new Hashtable();
    /** RGB of the AMS placeholder icons, loaded on first use. */
    private static int[][] placeholders;
    private static int[] placeholderW;
    private static int[] placeholderH;

    /**
     * The icon to show for an installed suite, fitted into a max x max box
     * (SIZE for the menu grid, ROW for list rows). Never null unless even
     * the s100_app resource is missing.
     */
    static Image appIcon(RunningMIDletSuiteInfo si, int max) {
        Image own = (si == null) ? null : si.icon;
        if (own == null || isPlaceholder(own)) {
            return (max <= ROW) ? get("app_small") : get("app");
        }
        int slot = (max <= ROW) ? 1 : 0;
        Image[] fitted = (Image[]) appCache.get(own);
        if (fitted == null) {
            fitted = new Image[2];
            appCache.put(own, fitted);
        }
        if (fitted[slot] == null) {
            fitted[slot] = fit(own, max);
        }
        return fitted[slot];
    }

    /** True for the AMS's own _ch_single / _ch_suite stand-in pictures. */
    private static boolean isPlaceholder(Image img) {
        if (placeholders == null) {
            String[] names = {"_ch_single", "_ch_suite"};
            int[][] px = new int[names.length][];
            int[] ws = new int[names.length];
            int[] hs = new int[names.length];
            for (int i = 0; i < names.length; i++) {
                try {
                    Image d = GraphicalInstaller.getImageFromInternalStorage(names[i]);
                    if (d != null) {
                        ws[i] = d.getWidth();
                        hs[i] = d.getHeight();
                        px[i] = new int[ws[i] * hs[i]];
                        d.getRGB(px[i], 0, ws[i], 0, 0, ws[i], hs[i]);
                    }
                } catch (Throwable t) {
                    px[i] = null;
                }
            }
            placeholders = px;
            placeholderW = ws;
            placeholderH = hs;
        }
        int w = img.getWidth(), h = img.getHeight();
        int[] mine = null;
        for (int i = 0; i < placeholders.length; i++) {
            int[] ref = placeholders[i];
            if (ref == null || placeholderW[i] != w || placeholderH[i] != h) {
                continue;
            }
            if (mine == null) {
                mine = new int[w * h];
                try {
                    img.getRGB(mine, 0, w, 0, 0, w, h);
                } catch (Throwable t) {
                    return false;
                }
            }
            boolean same = true;
            for (int k = 0; k < mine.length && same; k++) {
                same = (mine[k] == ref[k]);
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resizes an icon so that neither side exceeds max: box-filter
     * average when shrinking, integer nearest-neighbour blow-up for tiny
     * (16 px) icons that would otherwise vanish in the 40 px cell.
     */
    static Image fit(Image img, int max) {
        int w = img.getWidth(), h = img.getHeight();
        if (w <= 0 || h <= 0) {
            return img;
        }
        int nw, nh;
        if (w > max || h > max) {
            if (w >= h) {
                nw = max;
                nh = Math.max(1, h * max / w);
            } else {
                nh = max;
                nw = Math.max(1, w * max / h);
            }
        } else if (Math.max(w, h) * 2 <= max) {
            int f = max / Math.max(w, h);
            nw = w * f;
            nh = h * f;
        } else {
            return img;
        }
        try {
            int[] src = new int[w * h];
            img.getRGB(src, 0, w, 0, 0, w, h);
            int[] dst = new int[nw * nh];
            for (int y = 0; y < nh; y++) {
                int y0 = y * h / nh;
                int y1 = Math.max(y0 + 1, (y + 1) * h / nh);
                for (int x = 0; x < nw; x++) {
                    int x0 = x * w / nw;
                    int x1 = Math.max(x0 + 1, (x + 1) * w / nw);
                    // premultiplied average so transparent edges stay clean
                    int a = 0, r = 0, g = 0, b = 0, n = 0;
                    for (int sy = y0; sy < y1; sy++) {
                        for (int sx = x0; sx < x1; sx++) {
                            int p = src[sy * w + sx];
                            int pa = (p >>> 24);
                            a += pa;
                            r += ((p >> 16) & 0xFF) * pa;
                            g += ((p >> 8) & 0xFF) * pa;
                            b += (p & 0xFF) * pa;
                            n++;
                        }
                    }
                    if (a == 0) {
                        dst[y * nw + x] = 0;
                    } else {
                        dst[y * nw + x] = ((a / n) << 24) | ((r / a) << 16)
                            | ((g / a) << 8) | (b / a);
                    }
                }
            }
            return Image.createRGBImage(dst, nw, nh, true);
        } catch (Throwable t) {
            return img;
        }
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
