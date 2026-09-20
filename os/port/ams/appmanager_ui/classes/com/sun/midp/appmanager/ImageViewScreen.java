/*
 * S100 shell - picture viewer (file manager, camera album, photo review).
 *
 * JPEGs are decoded natively, scaled down to at most a screenful of
 * pixels (Sys.jpegDecode: the IJG decoder's 1/2, 1/4, 1/8 DCT scaling, so
 * a 1280x960 photo costs one 320x240 buffer). PNG/GIF go through
 * Image.createImage when small enough for the Java heap. Anything larger
 * than the screen pans with the navigation keys; Left/Right otherwise
 * step through the pictures of the same folder (also under Options).
 */

package com.sun.midp.appmanager;

import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

class ImageViewScreen extends Screen {
    private static final int MAX_PNG_BYTES = 200 * 1024;

    private String path;
    private int[] rgb;              // decoded JPEG, w x h
    private int w, h;
    private Image image;            // PNG/GIF
    private int panX, panY;
    private String error;
    private boolean loaded;
    /** Extra option shown first (the camera's "Take another"), or null. */
    String extraOption;
    Runnable extraAction;

    ImageViewScreen(String path) {
        super(Sys.nameOf(path));
        this.path = path;
    }

    private void load(int maxW, int maxH) {
        loaded = true;
        rgb = null;
        image = null;
        error = null;
        panX = panY = 0;
        title = Sys.nameOf(path);
        String ext = Sys.extOf(path);
        if (ext.equals("jpg") || ext.equals("jpeg")) {
            // a full-screen buffer: a 4:3 photo decodes to 320x240 or 240x320
            // and pans by the overhang instead of dropping to 1/8 size
            maxW = Theme.W;
            maxH = Theme.H;
            int[] buf = new int[maxW * maxH];
            int r = Sys.jpegDecode(path, maxW, maxH, buf);
            if (r < 0) {
                error = (r == -5) ? "JPEG viewing is not built in"
                    : (r == -2 ? "Not a JPEG picture" : "Cannot decode picture");
                return;
            }
            w = r >> 16;
            h = r & 0xffff;
            rgb = buf;
            return;
        }
        long size = Sys.size(path);
        if (size > MAX_PNG_BYTES) {
            error = "Picture too large to show\n(" + Sys.sizeText(size) + ")";
            return;
        }
        byte[] data = readBytes(path, (int) size);
        if (data == null) {
            error = "Cannot read picture";
            return;
        }
        try {
            image = Image.createImage(data, 0, data.length);
            w = image.getWidth();
            h = image.getHeight();
        } catch (Throwable t) {
            error = "Cannot decode picture";
        }
    }

    static byte[] readBytes(String path, int len) {
        com.sun.midp.io.j2me.storage.RandomAccessStream s =
            new com.sun.midp.io.j2me.storage.RandomAccessStream();
        try {
            s.connect(path, javax.microedition.io.Connector.READ);
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = s.readBytes(buf, off, len - off);
                if (n <= 0) {
                    break;
                }
                off += n;
            }
            return buf;
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                s.disconnect();
            } catch (Throwable t) {
                // ignore
            }
        }
    }

    void onHide() {
        // give the pixels back while covered; reload on return
        rgb = null;
        image = null;
        loaded = false;
    }

    String softLeft() { return "Options"; }
    String softRight() { return "Back"; }

    void paint(Graphics g, int x, int y, int w0, int h0) {
        if (!loaded) {
            load(w0, h0);
        }
        g.setColor(0x000000);
        g.fillRect(x, y, w0, h0);
        if (error != null) {
            g.setColor(0xffffff);
            String[] lines = Theme.wrap(error, w0 - 16);
            for (int i = 0; i < lines.length; i++) {
                g.drawString(lines[i], x + w0 / 2, y + h0 / 2 - Theme.FONT_H + i * Theme.FONT_H,
                             Graphics.TOP | Graphics.HCENTER);
            }
            return;
        }
        int dx = x + (w0 - w) / 2 - panX;
        int dy = y + (h0 - h) / 2 - panY;
        if (w > w0) {
            dx = x - panX;
        }
        if (h > h0) {
            dy = y - panY;
        }
        if (rgb != null) {
            g.drawRGB(rgb, 0, w, dx, dy, w, h, false);
        } else if (image != null) {
            g.drawImage(image, dx, dy, Graphics.TOP | Graphics.LEFT);
        }
    }

    private void pan(int ddx, int ddy, int vw, int vh) {
        int maxX = Math.max(0, w - vw), maxY = Math.max(0, h - vh);
        panX = Math.max(0, Math.min(maxX, panX + ddx));
        panY = Math.max(0, Math.min(maxY, panY + ddy));
        repaint();
    }

    boolean key(int k) {
        int vw = Theme.W, vh = Theme.H - Theme.STATUS_H - Theme.TITLE_H - Theme.SOFT_H;
        switch (k) {
        case Keymap.UP:
            if (h > vh) { pan(0, -40, vw, vh); }
            return true;
        case Keymap.DOWN:
            if (h > vh) { pan(0, 40, vw, vh); }
            return true;
        case Keymap.LEFT:
            if (w > vw) { pan(-40, 0, vw, vh); } else { step(-1); }
            return true;
        case Keymap.RIGHT:
            if (w > vw) { pan(40, 0, vw, vh); } else { step(1); }
            return true;
        case Keymap.SOFT_L:
        case Keymap.SELECT:
            options();
            return true;
        default:
            return false;
        }
    }

    void keyRepeat(int k) {
        if (k == Keymap.UP || k == Keymap.DOWN || k == Keymap.LEFT || k == Keymap.RIGHT) {
            key(k);
        }
    }

    /** Next/previous picture in the same folder. */
    private void step(int dir) {
        Vector v = Sys.list(Sys.parentOf(path));
        if (v == null) {
            return;
        }
        Vector pics = new Vector();
        int cur = -1;
        for (int i = 0; i < v.size(); i++) {
            Sys.Entry e = (Sys.Entry) v.elementAt(i);
            if (!e.dir && FileManager.isImage(e.ext())) {
                if (e.path.equals(path)) {
                    cur = pics.size();
                }
                pics.addElement(e.path);
            }
        }
        if (pics.size() < 2 || cur < 0) {
            return;
        }
        path = (String) pics.elementAt((cur + dir + pics.size()) % pics.size());
        loaded = false;
        repaint();
    }

    private void options() {
        Vector opts = new Vector();
        if (extraOption != null) {
            opts.addElement(extraOption);
        }
        opts.addElement("Next picture");
        opts.addElement("Previous picture");
        opts.addElement("Details");
        opts.addElement("Delete");
        final int base = extraOption != null ? 1 : 0;
        shell.showPopup(Popup.menu("Options", opts, new Popup.Listener() {
            public void onResult(int r) {
                if (base == 1 && r == 0) {
                    extraAction.run();
                } else if (r - base == 0) {
                    step(1);
                } else if (r - base == 1) {
                    step(-1);
                } else if (r - base == 2) {
                    shell.push(new TextViewScreen("Details",
                        "Name: " + Sys.nameOf(path)
                        + "\nSize: " + Sys.sizeText(Sys.size(path))
                        + (error == null ? "\nShown: " + w + "x" + h : "")
                        + "\nModified: " + Sys.dateText(Sys.mtime(path))
                        + "\nLocation: " + Sys.parentOf(path)));
                } else {
                    shell.showPopup(Popup.confirm("Delete " + Sys.nameOf(path) + "?",
                        new Popup.Listener() {
                            public void onResult(int r2) {
                                if (r2 == 1) {
                                    if (Sys.remove(path)) {
                                        shell.info("Deleted");
                                        shell.pop();
                                    } else {
                                        shell.info("Cannot delete");
                                    }
                                }
                            }
                        }));
                }
            }
        }));
    }
}
