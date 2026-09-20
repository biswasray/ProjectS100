/*
 * S100 shell - File manager (Applications > File manager).
 *
 *   Phone memory        /storage/emulated/0 (the KaiOS "internal" storage)
 *   Memory card         the SD card, when vold has mounted one
 *   Java runtime        /data/j2me (installed suites, logs, helper scripts)
 *
 * Folders open as further DirScreens; files open by extension: pictures
 * in the image viewer, text in a text view, sound and video clips in the
 * music/video player (MediaPlayer), .jad/.jar through the package
 * installer (AppManagerUIImpl.installFrom). Options offer the
 * usual Nokia set: Open, Details, New folder, Rename, Copy, Move
 * (Paste), Delete. The same screens double as a file picker
 * (pick()) for "Install from file" and friends.
 */

package com.sun.midp.appmanager;

import java.util.Vector;
import javax.microedition.lcdui.Image;

class FileManager {
    interface PickListener {
        void onPick(String path);
    }

    /** A pending file chooser: which extensions and who gets the path. */
    static class Picker {
        String[] exts;
        PickListener listener;
        Screen origin;
    }

    static final String[] IMAGE_EXTS = {"jpg", "jpeg", "png", "gif"};
    static final String[] TEXT_EXTS = {"txt", "log", "ini", "cfg", "conf", "xml", "json",
                                       "html", "htm", "csv", "jad", "sh", "properties", "md"};
    static final String[] AUDIO_EXTS = {"mp3", "wav", "amr", "mid", "midi", "ogg", "aac", "m4a"};
    static final String[] VIDEO_EXTS = {"avi", "3gp", "mp4", "mkv", "webm"};
    static final String[] PACKAGE_EXTS = {"jar", "jad"};

    private final Shell shell;
    /** Copy/Move source, until pasted. */
    private String clipPath;
    private boolean clipMove;

    FileManager(Shell shell) {
        this.shell = shell;
    }

    MenuItem item() {
        return new MenuItem("applications.files", "File manager", "files", new Runnable() {
            public void run() { shell.push(new RootsScreen(null)); }
        });
    }

    /** Opens a chooser for files with one of exts (null = any). */
    void pick(String[] exts, PickListener l) {
        Picker p = new Picker();
        p.exts = exts;
        p.listener = l;
        p.origin = shell.top();
        shell.push(new RootsScreen(p));
    }

    /* ------------------------------------------------------------------ */
    /*                              file types                            */
    /* ------------------------------------------------------------------ */

    static boolean in(String[] list, String ext) {
        for (int i = 0; i < list.length; i++) {
            if (list[i].equals(ext)) {
                return true;
            }
        }
        return false;
    }

    static boolean isImage(String ext) { return in(IMAGE_EXTS, ext); }
    static boolean isText(String ext) { return in(TEXT_EXTS, ext); }
    static boolean isAudio(String ext) { return in(AUDIO_EXTS, ext); }
    static boolean isVideo(String ext) { return in(VIDEO_EXTS, ext); }
    static boolean isPackage(String ext) { return in(PACKAGE_EXTS, ext); }

    static String typeName(String ext) {
        if (isImage(ext)) return "Picture";
        if (isVideo(ext)) return "Video clip";
        if (isAudio(ext)) return "Sound clip";
        if (isPackage(ext)) return "Java application";
        if (isText(ext)) return "Text";
        return ext.length() == 0 ? "File" : ext.toUpperCase() + " file";
    }

    static Image iconFor(Sys.Entry e) {
        if (e.dir) {
            return Icons.get("folder");
        }
        String ext = e.ext();
        String name = "file";
        if (isImage(ext)) name = "file_image";
        else if (isVideo(ext)) name = "file_video";
        else if (isAudio(ext)) name = "file_audio";
        else if (isPackage(ext)) name = "file_java";
        else if (isText(ext)) name = "file_text";
        Image img = Icons.get(name);
        return img != null ? img : Icons.get("file");
    }

    /* ------------------------------------------------------------------ */
    /*                              opening                               */
    /* ------------------------------------------------------------------ */

    /** Opens a file with the viewer for its extension. */
    void open(final String path) {
        String name = Sys.nameOf(path);
        String ext = Sys.extOf(name);
        if (isImage(ext)) {
            shell.push(new ImageViewScreen(path));
        } else if (isPackage(ext)) {
            install(path);
        } else if (isText(ext)) {
            long size = Sys.size(path);
            String text = SysInfo.readFile(path, 32768);
            if (text == null) {
                shell.info("Cannot read " + name);
                return;
            }
            if (size > 32768) {
                text += "\n\n(first 32 kB of " + Sys.sizeText(size) + ")";
            }
            shell.push(new TextViewScreen(name, text));
        } else if (isVideo(ext)) {
            shell.menus.media.playVideo(path);
        } else if (isAudio(ext)) {
            shell.menus.media.playAudio(path);
        } else {
            shell.showPopup(Popup.info("No application can open\n" + name, 0));
        }
    }

    /** The package installer: confirms, then hands a file:// URL to the AMS. */
    void install(final String path) {
        final String name = Sys.nameOf(path);
        String ext = Sys.extOf(name);
        if (!isPackage(ext)) {
            shell.info("Not a Java application");
            return;
        }
        String detail = "";
        if (ext.equals("jad")) {
            String jad = SysInfo.readFile(path, 8192);
            String suite = jadAttr(jad, "MIDlet-Name");
            String vendor = jadAttr(jad, "MIDlet-Vendor");
            String version = jadAttr(jad, "MIDlet-Version");
            if (suite != null) {
                detail = "\n" + suite + (version != null ? " " + version : "")
                    + (vendor != null ? "\n" + vendor : "");
            }
        } else {
            detail = "\n" + Sys.sizeText(Sys.size(path));
        }
        shell.showPopup(Popup.confirm("Install " + name + "?" + detail, new Popup.Listener() {
            public void onResult(int r) {
                if (r == 1) {
                    shell.ams.installFrom("file://" + path, Sys.stripExt(name));
                }
            }
        }).labels("Install", "Cancel"));
    }

    static String jadAttr(String jad, String key) {
        if (jad == null) {
            return null;
        }
        int i = jad.indexOf(key + ":");
        if (i < 0 || (i > 0 && jad.charAt(i - 1) != '\n' && jad.charAt(i - 1) != '\r')) {
            return null;
        }
        int e = jad.indexOf('\n', i);
        if (e < 0) {
            e = jad.length();
        }
        return jad.substring(i + key.length() + 1, e).trim();
    }

    /* ------------------------------------------------------------------ */
    /*                                roots                               */
    /* ------------------------------------------------------------------ */

    class RootsScreen extends ListScreen {
        private final Picker picker;

        RootsScreen(Picker picker) {
            super(picker == null ? "File manager" : "Select file");
            this.picker = picker;
            rowH = 34;
        }

        void refresh() {
            items.removeAllElements();
            String phone = Sys.phoneRoot();
            if (phone != null) {
                add(new Item("Phone memory", space(phone), phone)).icon(Icons.get("folder"));
            }
            String card = Sys.cardRoot();
            if (card != null) {
                add(new Item("Memory card", space(card), card)).icon(Icons.get("folder"));
            } else {
                add(new Item("Memory card", "No card inserted", null)).icon(Icons.get("folder"));
            }
            if (Sys.isDir(Sys.HOME)) {
                add(new Item("Java runtime", Sys.HOME, Sys.HOME)).icon(Icons.get("folder"));
            }
            if (phone == null) {
                add(new Item("File system", "/", "/")).icon(Icons.get("folder"));
            }
        }

        private String space(String path) {
            long free = Sys.free(path), total = Sys.total(path);
            if (free < 0 || total < 0) {
                return path;
            }
            return Sys.sizeText(free) + " free of " + Sys.sizeText(total);
        }

        String softLeft() { return current() == null ? null : "Open"; }

        String[] optionsMenu(Item it) {
            if (picker != null || it.data == null) {
                return null;
            }
            return new String[] {"Open", "Memory status", "Browse file system"};
        }

        void option(Item it, int r) {
            if (r == 0) {
                select(it);
            } else if (r == 1) {
                String phone = Sys.phoneRoot();
                String card = Sys.cardRoot();
                shell.push(new TextViewScreen("Memory status",
                    "Phone memory\n  " + (phone == null ? "not available" : space(phone))
                    + "\n\nMemory card\n  " + (card == null ? "no card" : space(card))
                    + "\n\nJava runtime\n  " + space(Sys.HOME)));
            } else {
                shell.push(new DirScreen("/", "/", picker));
            }
        }

        void select(Item it) {
            if (it.data == null) {
                shell.info("Insert a memory card");
                return;
            }
            shell.push(new DirScreen((String) it.data, it.label, picker));
        }
    }

    /* ------------------------------------------------------------------ */
    /*                             a directory                            */
    /* ------------------------------------------------------------------ */

    class DirScreen extends ListScreen {
        final String path;
        private final Picker picker;
        private boolean unreadable;

        DirScreen(String path, String label, Picker picker) {
            super(label);
            this.path = path;
            this.picker = picker;
            emptyText = "(empty folder)";
        }

        void refresh() {
            Object keep = current() == null ? null : current().data;
            items.removeAllElements();
            Vector v = Sys.list(path);
            unreadable = (v == null);
            if (v == null) {
                emptyText = "Cannot open folder";
                return;
            }
            emptyText = "(empty folder)";
            for (int i = 0; i < v.size(); i++) {
                Sys.Entry e = (Sys.Entry) v.elementAt(i);
                if (picker != null && !e.dir && picker.exts != null
                        && !in(picker.exts, e.ext())) {
                    continue;
                }
                Item it = new Item(e.name, e).icon(iconFor(e));
                if (!e.dir) {
                    it.value = Sys.sizeText(e.size);
                }
                add(it);
            }
            if (keep != null) {
                for (int i = 0; i < items.size(); i++) {
                    Sys.Entry e = (Sys.Entry) ((Item) items.elementAt(i)).data;
                    if (e.path.equals(((Sys.Entry) keep).path)) {
                        selected = i;
                    }
                }
            }
        }

        String titleRight() {
            return items.isEmpty() ? null : (selected + 1) + "/" + items.size();
        }

        String softLeft() {
            if (current() == null) {
                return (picker == null && !unreadable) ? "Options" : null;
            }
            return "Options";
        }

        String[] optionsMenu(Item it) {
            Vector v = new Vector();
            Sys.Entry e = (Sys.Entry) it.data;
            if (picker != null) {
                v.addElement(e.dir ? "Open" : "Select");
                v.addElement("Details");
            } else {
                if (!e.dir && isPackage(e.ext())) {
                    v.addElement("Install");
                }
                v.addElement("Open");
                v.addElement("Details");
                v.addElement("New folder");
                v.addElement("Rename");
                v.addElement("Copy");
                v.addElement("Move");
                if (clipPath != null) {
                    v.addElement("Paste here");
                }
                v.addElement("Delete");
            }
            String[] out = new String[v.size()];
            v.copyInto(out);
            return out;
        }

        boolean key(int k) {
            // an empty folder still needs "New folder" / "Paste"
            if (k == Keymap.SOFT_L && current() == null && picker == null && !unreadable) {
                String[] opts = clipPath == null ? new String[] {"New folder"}
                    : new String[] {"New folder", "Paste here"};
                shell.showPopup(Popup.menu("Options", opts, new Popup.Listener() {
                    public void onResult(int r) {
                        if (r == 0) {
                            newFolder();
                        } else {
                            paste();
                        }
                    }
                }));
                return true;
            }
            return super.key(k);
        }

        void select(Item it) {
            Sys.Entry e = (Sys.Entry) it.data;
            if (e.dir) {
                shell.push(new DirScreen(e.path, e.name, picker));
            } else if (picker != null) {
                Picker p = picker;
                shell.popTo(p.origin);
                p.listener.onPick(e.path);
            } else {
                open(e.path);
            }
        }

        void option(Item it, int r) {
            String[] opts = optionsMenu(it);
            String what = opts[r];
            final Sys.Entry e = (Sys.Entry) it.data;
            if (what.equals("Open") || what.equals("Select")) {
                select(it);
            } else if (what.equals("Install")) {
                install(e.path);
            } else if (what.equals("Details")) {
                details(e);
            } else if (what.equals("New folder")) {
                newFolder();
            } else if (what.equals("Rename")) {
                shell.push(new TextInputScreen("Name:", e.name, TextInputScreen.TEXT, 60, "OK",
                    new TextInputScreen.Listener() {
                        public void onText(String t) {
                            t = t.trim();
                            if (t.length() == 0 || t.indexOf('/') >= 0) {
                                shell.info("Invalid name");
                                return;
                            }
                            String to = Sys.join(path, t);
                            if (Sys.exists(to)) {
                                shell.info("Name already in use");
                            } else if (Sys.rename(e.path, to)) {
                                shell.info("Renamed");
                            } else {
                                shell.info("Cannot rename");
                            }
                            refresh();
                            clamp();
                        }
                    }));
            } else if (what.equals("Copy") || what.equals("Move")) {
                clipPath = e.path;
                clipMove = what.equals("Move");
                shell.info(e.name + "\nselect a folder, then Options > Paste here");
            } else if (what.equals("Paste here")) {
                paste();
            } else if (what.equals("Delete")) {
                clear(it);
            }
        }

        private void details(Sys.Entry e) {
            String text = "Name: " + e.name + "\nType: " + (e.dir ? "Folder" : typeName(e.ext()))
                + (e.dir ? "" : "\nSize: " + Sys.sizeText(e.size) + " (" + e.size + " bytes)")
                + "\nModified: " + Sys.dateText(e.mtime) + "\nLocation: " + path;
            if (e.dir) {
                Vector v = Sys.list(e.path);
                if (v != null) {
                    int dirs = 0;
                    for (int i = 0; i < v.size(); i++) {
                        if (((Sys.Entry) v.elementAt(i)).dir) {
                            dirs++;
                        }
                    }
                    text += "\nContains: " + dirs + " folders, " + (v.size() - dirs) + " files";
                }
            } else if (isPackage(e.ext()) && e.ext().equals("jad")) {
                String jad = SysInfo.readFile(e.path, 8192);
                String n = jadAttr(jad, "MIDlet-Name");
                if (n != null) {
                    text += "\n\nMIDlet suite: " + n + "\nVendor: " + jadAttr(jad, "MIDlet-Vendor")
                        + "\nVersion: " + jadAttr(jad, "MIDlet-Version");
                }
            }
            shell.push(new TextViewScreen("Details", text));
        }

        private void newFolder() {
            shell.push(new TextInputScreen("Folder name:", null, TextInputScreen.TEXT, 60, "OK",
                new TextInputScreen.Listener() {
                    public void onText(String t) {
                        t = t.trim();
                        if (t.length() == 0 || t.indexOf('/') >= 0) {
                            shell.info("Invalid name");
                            return;
                        }
                        if (Sys.mkdir(Sys.join(path, t))) {
                            shell.info("Folder created");
                        } else {
                            shell.info("Cannot create folder");
                        }
                        refresh();
                        clamp();
                    }
                }));
        }

        private void paste() {
            if (clipPath == null) {
                return;
            }
            final String src = clipPath;
            final boolean move = clipMove;
            final String name = Sys.nameOf(src);
            final String dst = Sys.join(path, name);
            if (dst.equals(src) || (dst + "/").startsWith(src + "/")) {
                shell.info("Cannot paste into itself");
                return;
            }
            if (Sys.exists(dst)) {
                shell.info(name + " already exists here");
                return;
            }
            shell.showPopup(Popup.wait((move ? "Moving " : "Copying ") + name + "\u2026"));
            shell.later(new Runnable() {
                public void run() {
                    boolean ok;
                    if (move && Sys.rename(src, dst)) {
                        ok = true;                       // same file system
                    } else {
                        ok = Sys.copyTree(src, dst);
                        if (ok && move) {
                            Sys.removeTree(src);
                        }
                    }
                    shell.closePopup();
                    shell.info(ok ? (move ? "Moved" : "Copied") : "Failed");
                    if (ok) {
                        clipPath = null;
                    }
                    refresh();
                    clamp();
                }
            }, 50);
        }

        void clear(Item it) {
            final Sys.Entry e = (Sys.Entry) it.data;
            if (picker != null) {
                return;
            }
            shell.showPopup(Popup.confirm("Delete " + e.name + "?"
                    + (e.dir ? "\n(and everything in it)" : ""), new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        boolean ok = e.dir ? Sys.removeTree(e.path) : Sys.remove(e.path);
                        shell.info(ok ? "Deleted" : "Cannot delete");
                        refresh();
                        clamp();
                    }
                }
            }));
        }
    }
}
