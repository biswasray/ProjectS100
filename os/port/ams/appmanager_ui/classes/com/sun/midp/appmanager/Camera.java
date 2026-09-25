/*
 * S100 shell - Camera (Menu > Camera): viewfinder, photos, video clips,
 * album and camera settings.
 *
 * How it works on the JioPhone: s100_cam.sh runs Qualcomm's
 * mm-qcamera-app, which dumps every preview frame (640x480 planar YV12) into
 * /data/back.yuv or /data/front.yuv. The viewfinder timer converts that
 * file into the screen buffer eight times a second (Sys.yuvFrame, native).
 * A 1 MP photo restarts the app in snapshot mode (~5 s, the file is
 * converted to JPEG natively); "VGA" photos are simply the current
 * preview frame. Video is a Motion-JPEG AVI recorded from the preview
 * frames by a native helper thread (10 fps, 240x320), optionally with
 * the microphone through tinycap. Pictures land in DCIM/Camera of the
 * phone memory or the memory card.
 *
 * Keys: centre = take picture / start-stop recording, left soft = Options
 * (album, mode, camera, settings), Left/Right = photo <-> video,
 * '*' = switch camera, right soft = back.
 */

package com.sun.midp.appmanager;

import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;

class Camera {
    static final String P_ROT_BACK = "camera.rot.back";        // 0/90/180/270
    static final String P_ROT_FRONT = "camera.rot.front";
    static final String P_MIRROR = "camera.mirror";            // front camera mirrored
    static final String P_FMT = "camera.fmt";                  // nv21 | nv12
    static final String P_SIZE = "camera.size";                // 1mp | vga
    static final String P_SOUND = "camera.sound";              // 1 | 0
    static final String P_STORE = "camera.store";              // phone | card
    static final String P_QUALITY = "camera.quality";          // 70 | 85 | 95
    /** What the JioPhone's mm-qcamera-app writes (see os/port/README.md). */
    static final String DEFAULT_FMT = "yv12";

    static final String[] SIZE_LABELS = {"1 MP (1280x960)", "VGA (640x480, instant)"};
    static final String[] SIZE_KEYS = {"1mp", "vga"};
    static final String[] STORE_LABELS = {"Phone memory", "Memory card"};
    static final String[] STORE_KEYS = {"phone", "card"};
    static final String[] QUALITY_LABELS = {"Basic", "Normal", "Fine"};
    static final String[] QUALITY_KEYS = {"70", "85", "95"};
    static final String[] ROT_LABELS = {"0\u00b0", "90\u00b0", "180\u00b0", "270\u00b0"};
    static final String[] ROT_KEYS = {"0", "90", "180", "270"};
    static final String[] FMT_LABELS = {"NV21", "NV12", "YV12", "I420"};
    static final String[] FMT_KEYS = {"nv21", "nv12", "yv12", "i420"};

    private final Shell shell;

    Camera(Shell shell) {
        this.shell = shell;
    }

    MenuItem item() {
        return new MenuItem("camera", "Camera", "camera", new Runnable() {
            public void run() { shell.push(new CameraScreen()); }
        });
    }

    MenuItem albumItem() {
        return new MenuItem("applications.album", "Album", "album", new Runnable() {
            public void run() { shell.push(new AlbumScreen()); }
        });
    }

    /* ------------------------------------------------------------------ */
    /*                              helpers                               */
    /* ------------------------------------------------------------------ */

    /** Chroma layout of the dumps (Sys.NV21..I420), a setting because it is
     *  not documented anywhere: wrong = green/magenta blocks. */
    static int fmt() {
        return SettingsMenu.indexOf(FMT_KEYS, Prefs.get(P_FMT, DEFAULT_FMT));
    }

    static int rotation(int cam) {
        return Prefs.getInt(cam == 0 ? P_ROT_BACK : P_ROT_FRONT, cam == 0 ? 90 : 270);
    }

    static boolean mirror(int cam) {
        return cam == 1 && Prefs.getBool(P_MIRROR, true);
    }

    static int quality() {
        return Prefs.getInt(P_QUALITY, 85);
    }

    /** DCIM/Camera on the chosen storage (created on demand). */
    static String dcim() {
        String root = null;
        if (Prefs.get(P_STORE, "phone").equals("card")) {
            root = Sys.cardRoot();
        }
        if (root == null) {
            root = Sys.phoneRoot();
        }
        if (root == null) {
            root = Sys.HOME;                 // PC emulator
        }
        String dir = root + "/DCIM/Camera";
        Sys.mkdirs(dir);
        return dir;
    }

    static String newPath(String prefix, String ext) {
        String base = dcim() + "/" + prefix + "_" + Sys.stamp();
        String p = base + "." + ext;
        int n = 1;
        while (Sys.exists(p)) {
            p = base + "_" + (n++) + "." + ext;
        }
        return p;
    }

    /** Frame geometry from the dump size (w*h*3/2), 0 if unknown. */
    static int[] dims(long size) {
        int[][] known = {
            {640, 480}, {1280, 960}, {320, 240}, {1280, 720}, {800, 480},
            {720, 480}, {176, 144}, {352, 288}, {1920, 1080}, {2592, 1944}
        };
        for (int i = 0; i < known.length; i++) {
            if ((long) known[i][0] * known[i][1] * 3 / 2 == size) {
                return known[i];
            }
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /*                            viewfinder                              */
    /* ------------------------------------------------------------------ */

    class CameraScreen extends Screen {
        private static final int TICK_MS = 120;

        private int cam;                    // 0 back, 1 front
        private boolean videoMode;
        private int[] buf;
        private int bw, bh;
        private boolean haveFrame;
        private long noFrameSince;
        private String frameFile;
        private int fw = 640, fh = 480;
        private int previewPid = -1;
        private TimerTask ticker;
        private String status;              // centred note ("Capturing...")
        private boolean busy;               // a capture is in flight

        private boolean recording;
        private long recStarted;
        private String recPath;
        private int audioPid = -1;

        CameraScreen() {
            super(null);
        }

        boolean overlayStatus() { return true; }

        boolean keepScreenOn() { return true; }

        String softLeft() { return busy ? null : "Options"; }
        String softMid() {
            if (busy) return null;
            return recording ? "Stop" : (videoMode ? "Record" : "Capture");
        }
        String softRight() { return busy ? null : "Back"; }

        void onShow() {
            startPreview();
        }

        void onHide() {
            if (recording) {
                stopRecording();
            }
            stopPreview();
            buf = null;
        }

        private void startPreview() {
            if (previewPid > 0) {
                return;
            }
            haveFrame = false;
            noFrameSince = System.currentTimeMillis();
            status = null;
            frameFile = cam == 0 ? "/data/back.yuv" : "/data/front.yuv";
            previewPid = Sys.spawn(Sys.CAM_SH + " preview " + cam);
            if (ticker != null) {
                ticker.cancel();
            }
            ticker = shell.every(new Runnable() {
                public void run() { frameTick(); }
            }, TICK_MS);
            repaint();
        }

        private void stopPreview() {
            if (ticker != null) {
                ticker.cancel();
                ticker = null;
            }
            if (previewPid > 0) {
                Sys.kill(previewPid);
                previewPid = -1;
                Sys.exec(Sys.CAM_SH + " stop", 3000);
            }
        }

        private void frameTick() {
            if (busy || buf == null || previewPid <= 0) {
                return;
            }
            long size = Sys.size(frameFile);
            if (size <= 0) {
                checkSignal();
                return;
            }
            int[] d = dims(size);
            if (d != null) {
                fw = d[0];
                fh = d[1];
            }
            int rc = Sys.yuvFrame(frameFile, fw, fh, fmt(), rotation(cam), mirror(cam), buf, bw, bh);
            if (rc == 0) {
                haveFrame = true;
                noFrameSince = System.currentTimeMillis();
                repaint();
            } else {
                checkSignal();
            }
        }

        private void checkSignal() {
            if (!haveFrame && System.currentTimeMillis() - noFrameSince > 12000
                    && status == null) {
                status = Sys.alive(previewPid) ? "No picture from the camera"
                    : "Camera not available";
                repaint();
            }
        }

        void paint(Graphics g, int x, int y, int w, int h) {
            if (buf == null || bw != w || bh != h) {
                bw = w;
                bh = h;
                buf = new int[w * h];
                haveFrame = false;
            }
            if (haveFrame) {
                g.drawRGB(buf, 0, bw, x, y, bw, bh, false);
            } else {
                g.setColor(0x000000);
                g.fillRect(x, y, w, h);
                if (status == null) {
                    Theme.shadowText(g, "Starting camera\u2026", x + w / 2, y + h / 2 - Theme.FONT_H,
                                     Graphics.TOP | Graphics.HCENTER, 0xffffff, 0x000000);
                }
            }
            // mode / camera badge under the status bar
            String badge = (videoMode ? "Video" : "Photo") + (cam == 1 ? " \u00b7 front" : "");
            Theme.shadowText(g, badge, x + Theme.MARGIN, y + Theme.STATUS_H + 2,
                             Graphics.TOP | Graphics.LEFT, 0xffffff, 0x000000);
            if (!videoMode) {
                String sz = Prefs.get(P_SIZE, "1mp").equals("vga") ? "VGA" : "1 MP";
                Theme.shadowText(g, sz, x + w - Theme.MARGIN, y + Theme.STATUS_H + 2,
                                 Graphics.TOP | Graphics.RIGHT, 0xffffff, 0x000000);
            }
            if (recording) {
                long s = (System.currentTimeMillis() - recStarted) / 1000;
                g.setColor(0xff2020);
                g.fillArc(x + Theme.MARGIN, y + Theme.STATUS_H + 20, 9, 9, 0, 360);
                Theme.shadowText(g, "REC " + Theme.two((int) (s / 60)) + ":" + Theme.two((int) (s % 60)),
                                 x + Theme.MARGIN + 13, y + Theme.STATUS_H + 18,
                                 Graphics.TOP | Graphics.LEFT, 0xffffff, 0x000000);
            }
            if (status != null) {
                g.setColor(0x000000);
                g.fillRoundRect(x + 20, y + h / 2 - 14, w - 40, 28, 8, 8);
                g.setColor(0xffffff);
                g.drawString(Theme.fit(status, w - 56), x + w / 2, y + h / 2 - Theme.FONT_H / 2,
                             Graphics.TOP | Graphics.HCENTER);
            }
        }

        boolean key(int k) {
            if (busy) {
                return true;
            }
            switch (k) {
            case Keymap.SELECT:
                if (videoMode) {
                    if (recording) stopRecording(); else startRecording();
                } else {
                    capture();
                }
                return true;
            case Keymap.SOFT_L:
                options();
                return true;
            case Keymap.LEFT:
            case Keymap.RIGHT:
                if (!recording) {
                    videoMode = !videoMode;
                    repaint();
                }
                return true;
            case Keymap.STAR:
                if (!recording) {
                    switchCamera();
                }
                return true;
            default:
                return false;
            }
        }

        private void switchCamera() {
            stopPreview();
            cam = 1 - cam;
            startPreview();
        }

        private void options() {
            String[] opts = {
                "Album",
                videoMode ? "Photo mode" : "Video mode",
                cam == 0 ? "Use front camera" : "Use back camera",
                "Settings"
            };
            shell.showPopup(Popup.menu("Options", opts, new Popup.Listener() {
                public void onResult(int r) {
                    switch (r) {
                    case 0: shell.push(new AlbumScreen()); break;
                    case 1: videoMode = !videoMode; repaint(); break;
                    case 2: switchCamera(); break;
                    case 3: shell.push(new SettingsScreen()); break;
                    }
                }
            }));
        }

        /* ------------------------------ photo ------------------------- */

        private void capture() {
            if (!haveFrame && status != null) {
                shell.info(status);
                return;
            }
            if (Prefs.get(P_SIZE, "1mp").equals("vga")) {
                // the preview frame itself: instant
                String jpg = newPath("IMG", "jpg");
                int rc = Sys.yuvJpeg(frameFile, fw, fh, fmt(), rotation(cam), mirror(cam),
                                     1, quality(), jpg);
                if (rc == 0) {
                    review(jpg);
                } else {
                    shell.info("Could not save the picture");
                }
                return;
            }
            busy = true;
            status = "Capturing\u2026";
            stopPreview();
            repaint();
            final int which = cam;
            Sys.run(shell, Sys.CAM_SH + " snap " + which, 40000, new Sys.JobListener() {
                public void onDone(String out, boolean ok) {
                    busy = false;
                    status = null;
                    String file = Sys.field(out, "file", null);
                    long size = Sys.parseLong(Sys.field(out, "size", "0"));
                    int[] d = dims(size);
                    if (file == null || d == null) {
                        shell.info(Sys.field(out, "error", "No picture taken"));
                        startPreview();
                        return;
                    }
                    String jpg = newPath("IMG", "jpg");
                    int rc = Sys.yuvJpeg(file, d[0], d[1], fmt(), rotation(which), mirror(which),
                                         1, quality(), jpg);
                    Sys.remove(file);
                    if (rc != 0) {
                        shell.info("Could not save the picture");
                        startPreview();
                        return;
                    }
                    review(jpg);
                }
            });
        }

        /** Shows the picture just taken; leaving it restarts the viewfinder. */
        private void review(String jpg) {
            stopPreview();
            ImageViewScreen v = new ImageViewScreen(jpg);
            v.extraOption = "Take another";
            v.extraAction = new Runnable() {
                public void run() { shell.pop(); }
            };
            shell.push(v);
        }

        /* ------------------------------ video ------------------------- */

        private void startRecording() {
            if (!haveFrame) {
                shell.info("Camera not ready");
                return;
            }
            boolean sound = Prefs.getBool(P_SOUND, false);
            recPath = newPath("VID", "avi");
            if (sound) {
                audioPid = Sys.spawn(Sys.CAM_SH + " audio " + Sys.q(recPath + ".wav"));
            }
            int rc = Sys.recStart(frameFile, fw, fh, fmt(), rotation(cam), mirror(cam),
                                  recPath, 10, 2, 70, sound ? 48000 : 0);
            if (rc != 0) {
                if (audioPid > 0) {
                    Sys.kill(audioPid);
                    audioPid = -1;
                }
                shell.info("Cannot start recording");
                return;
            }
            recording = true;
            recStarted = System.currentTimeMillis();
            repaint();
        }

        private void stopRecording() {
            if (!recording) {
                return;
            }
            recording = false;
            if (audioPid > 0) {
                Sys.exec(Sys.CAM_SH + " audio-stop", 4000);
                Sys.kill(audioPid);
                audioPid = -1;
            }
            int n = Sys.recStop();
            if (n <= 0) {
                Sys.remove(recPath);
                shell.info("Nothing recorded");
            } else {
                long s = (System.currentTimeMillis() - recStarted) / 1000;
                shell.info("Saved " + Sys.nameOf(recPath) + "\n" + s + " s, " + n + " frames");
            }
            repaint();
        }
    }

    /* ------------------------------------------------------------------ */
    /*                               album                                */
    /* ------------------------------------------------------------------ */

    class AlbumScreen extends ListScreen {
        AlbumScreen() {
            super("Album");
            emptyText = "No pictures or clips";
            rowH = 34;
        }

        void refresh() {
            items.removeAllElements();
            Vector all = new Vector();
            String phone = Sys.phoneRoot();
            String card = Sys.cardRoot();
            addFrom(all, (phone == null ? Sys.HOME : phone) + "/DCIM/Camera");
            if (card != null) {
                addFrom(all, card + "/DCIM/Camera");
            }
            // newest first
            for (int i = 0; i < all.size(); i++) {
                Sys.Entry e = (Sys.Entry) all.elementAt(i);
                int at = 0;
                while (at < items.size()
                       && ((Sys.Entry) ((Item) items.elementAt(at)).data).mtime > e.mtime) {
                    at++;
                }
                Item it = new Item(e.name, Sys.dateText(e.mtime) + ", " + Sys.sizeText(e.size), e)
                    .icon(FileManager.iconFor(e));
                items.insertElementAt(it, at);
            }
        }

        private void addFrom(Vector all, String dir) {
            Vector v = Sys.list(dir);
            for (int i = 0; v != null && i < v.size(); i++) {
                Sys.Entry e = (Sys.Entry) v.elementAt(i);
                if (!e.dir && (FileManager.isImage(e.ext()) || FileManager.isVideo(e.ext()))) {
                    all.addElement(e);
                }
            }
        }

        String[] optionsMenu(Item it) {
            return new String[] {"Open", "Details", "Delete"};
        }

        void select(Item it) {
            shell.menus.files.open(((Sys.Entry) it.data).path);
        }

        void option(Item it, int r) {
            Sys.Entry e = (Sys.Entry) it.data;
            if (r == 0) {
                select(it);
            } else if (r == 1) {
                shell.push(new TextViewScreen("Details",
                    "Name: " + e.name + "\nType: " + FileManager.typeName(e.ext())
                    + "\nSize: " + Sys.sizeText(e.size) + "\nTaken: " + Sys.dateText(e.mtime)
                    + "\nLocation: " + Sys.parentOf(e.path)));
            } else {
                clear(it);
            }
        }

        void clear(Item it) {
            final Sys.Entry e = (Sys.Entry) it.data;
            shell.showPopup(Popup.confirm("Delete " + e.name + "?", new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        shell.info(Sys.remove(e.path) ? "Deleted" : "Cannot delete");
                        refresh();
                        clamp();
                    }
                }
            }));
        }
    }

    /* ------------------------------------------------------------------ */
    /*                              settings                              */
    /* ------------------------------------------------------------------ */

    class SettingsScreen extends ListScreen {
        SettingsScreen() {
            super("Camera settings");
        }

        String softLeft() { return "Change"; }

        void refresh() {
            items.removeAllElements();
            add(new Item("Photo size", "size")).value(
                Prefs.get(P_SIZE, "1mp").equals("vga") ? "VGA" : "1 MP");
            add(new Item("Picture quality", "quality")).value(
                QUALITY_LABELS[SettingsMenu.indexOf(QUALITY_KEYS, Prefs.get(P_QUALITY, "85"))]);
            add(new Item("Video sound", "sound")).value(Prefs.getBool(P_SOUND, false) ? "On" : "Off");
            add(new Item("Save to", "store")).value(
                STORE_LABELS[SettingsMenu.indexOf(STORE_KEYS, Prefs.get(P_STORE, "phone"))]);
            add(new Item("Back camera rotation", "rotb")).value(
                ROT_LABELS[SettingsMenu.indexOf(ROT_KEYS, String.valueOf(rotation(0)))]);
            add(new Item("Front camera rotation", "rotf")).value(
                ROT_LABELS[SettingsMenu.indexOf(ROT_KEYS, String.valueOf(rotation(1)))]);
            add(new Item("Mirror front camera", "mirror")).value(Prefs.getBool(P_MIRROR, true) ? "Yes" : "No");
            add(new Item("Colour format", "fmt")).value(FMT_LABELS[fmt()]);
        }

        private void choose(String title, final String[] labels, final String[] keys,
                            final String pref, String cur) {
            shell.showPopup(Popup.choice(title, labels, SettingsMenu.indexOf(keys, cur),
                new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.set(pref, keys[r]);
                        refresh();
                        repaint();
                    }
                }));
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("size")) {
                choose("Photo size", SIZE_LABELS, SIZE_KEYS, P_SIZE, Prefs.get(P_SIZE, "1mp"));
            } else if (what.equals("quality")) {
                choose("Picture quality", QUALITY_LABELS, QUALITY_KEYS, P_QUALITY, Prefs.get(P_QUALITY, "85"));
            } else if (what.equals("sound")) {
                choose("Video sound", new String[] {"Off", "On"}, new String[] {"0", "1"}, P_SOUND,
                       Prefs.getBool(P_SOUND, false) ? "1" : "0");
            } else if (what.equals("store")) {
                choose("Save to", STORE_LABELS, STORE_KEYS, P_STORE, Prefs.get(P_STORE, "phone"));
            } else if (what.equals("rotb")) {
                choose("Back camera rotation", ROT_LABELS, ROT_KEYS, P_ROT_BACK, String.valueOf(rotation(0)));
            } else if (what.equals("rotf")) {
                choose("Front camera rotation", ROT_LABELS, ROT_KEYS, P_ROT_FRONT, String.valueOf(rotation(1)));
            } else if (what.equals("mirror")) {
                choose("Mirror front camera", new String[] {"No", "Yes"}, new String[] {"0", "1"},
                       P_MIRROR, Prefs.getBool(P_MIRROR, true) ? "1" : "0");
            } else if (what.equals("fmt")) {
                choose("Colour format", FMT_LABELS, FMT_KEYS, P_FMT, Prefs.get(P_FMT, DEFAULT_FMT));
            }
        }
    }
}
