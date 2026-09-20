/*
 * S100 shell - Music player and Video player (Applications menu, and the
 * viewers the file manager and the camera album open sound and video
 * clips with).
 *
 * Both sit on the one native player in native/s100_media.c (Sys.media*):
 * WAV, MP3 and the Motion-JPEG AVI clips the camera records, decoded
 * on a helper thread and written to the phone's ALSA front end. The
 * codec route (speaker or headphones) is switched with s100_media.sh
 * around playback. 3GP/MP4/AAC/AMR/MIDI files are listed but cannot be
 * played: there is no decoder for them in Java mode.
 *
 * Music keeps playing when the player screen is left (Nokia style); the
 * "Now playing" entry of the library returns to it, Stop ends it. A
 * video clip plays only while its screen is shown.
 *
 * Keys in the players: centre = play/pause, Left/Right = previous/next
 * song (video: back/forward 5 s), hold Left/Right = seek, Up/Down =
 * volume, * = repeat, # = shuffle, left soft = Options.
 */

package com.sun.midp.appmanager;

import java.util.Random;
import java.util.TimerTask;
import java.util.Vector;
import javax.microedition.lcdui.Graphics;
import javax.microedition.lcdui.Image;

class MediaPlayer {
    static final String P_VOLUME = "media.volume";       // 0..100
    static final String P_OUTPUT = "media.output";       // auto | speaker | headphones
    static final String P_REPEAT = "media.repeat";       // off | one | all
    static final String P_SHUFFLE = "media.shuffle";     // 1 | 0
    static final String P_VIDEO_FULL = "video.full";     // 1 = hide the title bar

    static final String[] OUTPUT_LABELS = {"Automatic (jack)", "Loudspeaker", "Headphones"};
    static final String[] OUTPUT_KEYS = {"auto", "speaker", "headphones"};
    static final String[] REPEAT_LABELS = {"Off", "Repeat one", "Repeat all"};
    static final String[] REPEAT_KEYS = {"off", "one", "all"};

    /** Folders searched for songs and clips (under the phone memory and the card). */
    private static final String[] MUSIC_DIRS = {
        "Music", "music", "Download", "Downloads", "Sounds", "Ringtones", "Recordings",
        "Audio", "Podcasts", "DCIM"
    };
    private static final String[] VIDEO_DIRS = {
        "Videos", "Movies", "Video", "DCIM", "Download", "Downloads", "Camera"
    };
    private static final int SCAN_LIMIT = 400;

    private final Shell shell;

    /* now playing */
    private Vector playlist;             // String paths
    private int index = -1;
    private String current;
    private String title, artist, album;
    private int duration;
    private boolean routed;
    private TimerTask ticker;
    private PlayerScreen playerScreen;
    private final Random random = new Random();

    MediaPlayer(Shell shell) {
        this.shell = shell;
    }

    MenuItem musicItem() {
        return new MenuItem("applications.music", "Music player", "music", new Runnable() {
            public void run() { shell.push(new LibraryScreen()); }
        });
    }

    MenuItem videoItem() {
        return new MenuItem("applications.videos", "Video player", "video", new Runnable() {
            public void run() { shell.push(new VideoLibraryScreen()); }
        });
    }

    /* ------------------------------------------------------------------ */
    /*                           entry points                             */
    /* ------------------------------------------------------------------ */

    /** Opens a sound clip from the file manager: its folder becomes the list. */
    void playAudio(String path) {
        Vector v = new Vector();
        int at = 0;
        Vector dir = Sys.list(Sys.parentOf(path));
        for (int i = 0; dir != null && i < dir.size(); i++) {
            Sys.Entry e = (Sys.Entry) dir.elementAt(i);
            if (!e.dir && FileManager.isAudio(e.ext())) {
                if (e.path.equals(path)) {
                    at = v.size();
                }
                v.addElement(e.path);
            }
        }
        if (v.isEmpty()) {
            v.addElement(path);
        }
        play(v, at);
        shell.push(player());
    }

    /** Opens a video clip from the file manager or the album. */
    void playVideo(String path) {
        shell.push(new VideoScreen(path));
    }

    /* ------------------------------------------------------------------ */
    /*                              engine                                */
    /* ------------------------------------------------------------------ */

    static int volume() {
        return Prefs.getInt(P_VOLUME, 70);
    }

    static void setVolume(int v) {
        v = Math.max(0, Math.min(100, v));
        Prefs.set(P_VOLUME, String.valueOf(v));
        Sys.mediaVolume(v);
    }

    /** tinymix routing before the first sound; harmless on the PC. */
    private void route(boolean on) {
        if (on && !routed) {
            Sys.exec(Sys.MEDIA_SH + " audio on " + Prefs.get(P_OUTPUT, "auto"), 5000);
            routed = true;
        } else if (!on && routed) {
            Sys.exec(Sys.MEDIA_SH + " audio off", 5000);
            routed = false;
        }
    }

    /** Re-applies the output setting while something plays. */
    void reroute() {
        if (routed) {
            routed = false;
            route(true);
        }
    }

    /** Starts the songs in v at position at. */
    void play(Vector v, int at) {
        playlist = v;
        index = at;
        start();
    }

    private String errorText(int rc) {
        switch (rc) {
        case -1: return "Cannot read the file";
        case -2: return "Unknown file format";
        case -3: return "Format not supported.\nJava mode plays WAV, MP3 and the camera's AVI clips.";
        case -4: return "No video decoder built in";
        default: return "Cannot play the file";
        }
    }

    private boolean start() {
        if (playlist == null || index < 0 || index >= playlist.size()) {
            return false;
        }
        String path = (String) playlist.elementAt(index);
        Sys.mediaClose();
        route(true);
        int rc = Sys.mediaOpen(path, 0, 0);
        if (rc != 0) {
            current = null;
            shell.showPopup(Popup.info(Sys.nameOf(path) + "\n" + errorText(rc), 0));
            stopTicker();
            return false;
        }
        current = path;
        duration = Sys.mediaInfo(Sys.M_DURATION);
        title = Sys.mediaTag(0);
        artist = Sys.mediaTag(1);
        album = Sys.mediaTag(2);
        if (title == null || title.length() == 0) {
            title = Sys.stripExt(Sys.nameOf(path));
        }
        Sys.mediaVolume(volume());
        if (!Sys.mediaPlay()) {
            shell.info("Cannot start playback");
            return false;
        }
        startTicker();
        return true;
    }

    private void startTicker() {
        if (ticker != null) {
            return;
        }
        ticker = shell.every(new Runnable() {
            public void run() { tick(); }
        }, 500);
    }

    private void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    private void tick() {
        if (current == null) {
            stopTicker();
            return;
        }
        int st = Sys.mediaState();
        if (st == Sys.MS_ENDED) {
            if (!next(true)) {
                stop();
            }
        } else if (st < 0) {
            shell.showPopup(Popup.info(Sys.mediaError(st), 0));
            stop();
        }
        if (shell.top() == playerScreen) {
            shell.repaint();
        }
    }

    boolean isPlaying() {
        return current != null && Sys.mediaState() == Sys.MS_PLAYING;
    }

    boolean hasTrack() {
        return current != null;
    }

    String currentTitle() {
        return current == null ? null : title;
    }

    void togglePause() {
        if (current == null) {
            return;
        }
        int st = Sys.mediaState();
        if (st == Sys.MS_PLAYING) {
            Sys.mediaPause();
        } else if (st == Sys.MS_ENDED || st == Sys.MS_STOPPED) {
            Sys.mediaSeek(0);
            Sys.mediaPlay();
            startTicker();
        } else {
            Sys.mediaPlay();
        }
    }

    void stop() {
        Sys.mediaClose();
        current = null;
        stopTicker();
        route(false);
    }

    /** Next song; auto = reached the end by itself (obeys Repeat). */
    boolean next(boolean auto) {
        if (playlist == null) {
            return false;
        }
        String rep = Prefs.get(P_REPEAT, "off");
        if (auto && rep.equals("one")) {
            Sys.mediaSeek(0);
            Sys.mediaPlay();
            return true;
        }
        if (Prefs.getBool(P_SHUFFLE, false) && playlist.size() > 1) {
            int n;
            do {
                n = Math.abs(random.nextInt()) % playlist.size();
            } while (n == index);
            index = n;
            return start();
        }
        if (index + 1 < playlist.size()) {
            index++;
            return start();
        }
        if (!auto || rep.equals("all")) {
            index = 0;
            return start();
        }
        return false;
    }

    boolean previous() {
        if (playlist == null) {
            return false;
        }
        if (Sys.mediaPos() > 3000) {
            Sys.mediaSeek(0);             // like a CD player: restart first
            return true;
        }
        index = (index + playlist.size() - 1) % playlist.size();
        return start();
    }

    void seekBy(int ms) {
        if (current == null) {
            return;
        }
        int to = Sys.mediaPos() + ms;
        Sys.mediaSeek(Math.max(0, Math.min(duration, to)));
    }

    private PlayerScreen player() {
        if (playerScreen == null) {
            playerScreen = new PlayerScreen();
        }
        return playerScreen;
    }

    /* ------------------------------------------------------------------ */
    /*                              scanning                              */
    /* ------------------------------------------------------------------ */

    /** Sound (audio=true) or video files below the usual media folders. */
    Vector scan(boolean audio) {
        Vector out = new Vector();
        String[] roots = {Sys.phoneRoot(), Sys.cardRoot()};
        String[] dirs = audio ? MUSIC_DIRS : VIDEO_DIRS;
        for (int r = 0; r < roots.length; r++) {
            if (roots[r] == null) {
                continue;
            }
            for (int d = 0; d < dirs.length && out.size() < SCAN_LIMIT; d++) {
                scanDir(roots[r] + "/" + dirs[d], audio, 3, out);
            }
            scanDir(roots[r], audio, 0, out);       // loose files in the root
        }
        if (Sys.phoneRoot() == null) {
            scanDir(Sys.HOME, audio, 2, out);      // the PC emulator
        }
        return out;
    }

    private void scanDir(String dir, boolean audio, int depth, Vector out) {
        Vector v = Sys.list(dir);
        for (int i = 0; v != null && i < v.size() && out.size() < SCAN_LIMIT; i++) {
            Sys.Entry e = (Sys.Entry) v.elementAt(i);
            if (e.dir) {
                if (depth > 0 && !e.name.startsWith(".")) {
                    scanDir(e.path, audio, depth - 1, out);
                }
            } else if (audio ? FileManager.isAudio(e.ext()) : FileManager.isVideo(e.ext())) {
                boolean dup = false;
                for (int j = 0; j < out.size(); j++) {
                    if (((Sys.Entry) out.elementAt(j)).path.equals(e.path)) {
                        dup = true;
                        break;
                    }
                }
                if (!dup) {
                    out.addElement(e);
                }
            }
        }
    }

    static boolean playable(String ext) {
        return ext.equals("mp3") || ext.equals("wav") || ext.equals("avi") || ext.equals("mp2");
    }

    /* ------------------------------------------------------------------ */
    /*                           music library                            */
    /* ------------------------------------------------------------------ */

    class LibraryScreen extends ListScreen {
        LibraryScreen() {
            super("Music player");
            numberKeys = true;
        }

        void refresh() {
            items.removeAllElements();
            if (current != null) {
                add(new Item("Now playing", "now")).value(Theme.fit(title, 90));
            }
            add(new Item("All songs", "all"));
            add(new Item("Folders", "folders"));
            add(new Item("Settings", "settings"));
        }

        String softLeft() { return "Select"; }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("now")) {
                shell.push(player());
            } else if (what.equals("all")) {
                shell.push(new SongsScreen());
            } else if (what.equals("folders")) {
                shell.menus.files.pick(FileManager.AUDIO_EXTS, new FileManager.PickListener() {
                    public void onPick(String path) {
                        playAudio(path);
                    }
                });
            } else {
                shell.push(new PlayerSettingsScreen());
            }
        }
    }

    class SongsScreen extends ListScreen {
        private Vector songs;

        SongsScreen() {
            super("All songs");
            rowH = 34;
            emptyText = "No songs found\n(Music, Download, Sounds...)";
        }

        void refresh() {
            Object keep = current() == null ? null : current().data;
            items.removeAllElements();
            if (songs == null) {
                songs = scan(true);
            }
            for (int i = 0; i < songs.size(); i++) {
                Sys.Entry e = (Sys.Entry) songs.elementAt(i);
                Item it = new Item(Sys.stripExt(e.name),
                    Sys.nameOf(Sys.parentOf(e.path)) + ", " + Sys.sizeText(e.size), e)
                    .icon(Icons.get("file_audio"));
                if (e.path.equals(current)) {
                    it.value = "playing";
                }
                add(it);
            }
            if (keep != null) {
                selectData(keep);
            }
        }

        String titleRight() {
            return items.isEmpty() ? null : (selected + 1) + "/" + items.size();
        }

        String[] optionsMenu(Item it) {
            return new String[] {"Play", "Play all from here", "Details", "Refresh"};
        }

        private Vector paths() {
            Vector v = new Vector();
            for (int i = 0; i < songs.size(); i++) {
                v.addElement(((Sys.Entry) songs.elementAt(i)).path);
            }
            return v;
        }

        void select(Item it) {
            Vector v = new Vector();
            v.addElement(((Sys.Entry) it.data).path);
            play(v, 0);
            if (current != null) {
                shell.push(player());
            }
        }

        void option(Item it, int r) {
            Sys.Entry e = (Sys.Entry) it.data;
            if (r == 0) {
                select(it);
            } else if (r == 1) {
                play(paths(), selected);
                if (current != null) {
                    shell.push(player());
                }
            } else if (r == 2) {
                shell.push(new TextViewScreen("Details",
                    "Name: " + e.name + "\nType: " + FileManager.typeName(e.ext())
                    + (playable(e.ext()) ? "" : " (not playable in Java mode)")
                    + "\nSize: " + Sys.sizeText(e.size) + "\nModified: " + Sys.dateText(e.mtime)
                    + "\nLocation: " + Sys.parentOf(e.path)));
            } else {
                songs = null;
                refresh();
                clamp();
                repaint();
            }
        }
    }

    class PlayerSettingsScreen extends ListScreen {
        PlayerSettingsScreen() {
            super("Player settings");
        }

        String softLeft() { return "Change"; }

        void refresh() {
            items.removeAllElements();
            add(new Item("Sound output", "out")).value(
                OUTPUT_LABELS[SettingsMenu.indexOf(OUTPUT_KEYS, Prefs.get(P_OUTPUT, "auto"))]);
            add(new Item("Repeat", "rep")).value(
                REPEAT_LABELS[SettingsMenu.indexOf(REPEAT_KEYS, Prefs.get(P_REPEAT, "off"))]);
            add(new Item("Shuffle", "shuf")).value(Prefs.getBool(P_SHUFFLE, false) ? "On" : "Off");
            add(new Item("Volume", "vol")).value(volume() + "%");
        }

        private void choose(String t, final String[] labels, final String[] keys, final String pref) {
            shell.showPopup(Popup.choice(t, labels, SettingsMenu.indexOf(keys, Prefs.get(pref, keys[0])),
                new Popup.Listener() {
                    public void onResult(int r) {
                        Prefs.set(pref, keys[r]);
                        if (pref.equals(P_OUTPUT)) {
                            reroute();
                        }
                        refresh();
                        repaint();
                    }
                }));
        }

        void select(Item it) {
            String what = (String) it.data;
            if (what.equals("out")) {
                choose("Sound output", OUTPUT_LABELS, OUTPUT_KEYS, P_OUTPUT);
            } else if (what.equals("rep")) {
                choose("Repeat", REPEAT_LABELS, REPEAT_KEYS, P_REPEAT);
            } else if (what.equals("shuf")) {
                choose("Shuffle", new String[] {"Off", "On"}, new String[] {"0", "1"}, P_SHUFFLE);
            } else {
                final String[] v = {"10", "20", "30", "40", "50", "60", "70", "80", "90", "100"};
                int cur = Math.max(0, Math.min(9, volume() / 10 - 1));
                shell.showPopup(Popup.choice("Volume", v, cur, new Popup.Listener() {
                    public void onResult(int r) {
                        setVolume(Integer.parseInt(v[r]));
                        refresh();
                        repaint();
                    }
                }));
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /*                            now playing                             */
    /* ------------------------------------------------------------------ */

    class PlayerScreen extends Screen {
        private long barShownAt;

        PlayerScreen() {
            super("Now playing");
        }

        String softLeft() { return "Options"; }
        String softMid() {
            if (current == null) return null;
            return Sys.mediaState() == Sys.MS_PLAYING ? "Pause" : "Play";
        }

        void paint(Graphics g, int x, int y, int w, int h) {
            g.setColor(Theme.C_BG);
            g.fillRect(x, y, w, h);
            Image icon = Icons.get("music");
            int iy = y + 10;
            if (icon != null) {
                g.drawImage(icon, x + w / 2, iy + 20, Graphics.VCENTER | Graphics.HCENTER);
            } else {
                Icons.draw(g, "music", null, x + w / 2 - 20, iy, 40, 40);
            }
            int ty = iy + 48;
            g.setColor(Theme.C_TEXT);
            if (current == null) {
                g.drawString("Nothing playing", x + w / 2, ty, Graphics.TOP | Graphics.HCENTER);
                return;
            }
            Theme.bold(g, Theme.fit(title, w - 16), x + w / 2, ty, Graphics.TOP | Graphics.HCENTER);
            ty += Theme.FONT_H + 2;
            g.setColor(Theme.C_TEXT_DIM);
            if (artist != null && artist.length() > 0) {
                g.drawString(Theme.fit(artist, w - 16), x + w / 2, ty, Graphics.TOP | Graphics.HCENTER);
                ty += Theme.FONT_H;
            }
            if (album != null && album.length() > 0) {
                g.drawString(Theme.fit(album, w - 16), x + w / 2, ty, Graphics.TOP | Graphics.HCENTER);
                ty += Theme.FONT_H;
            }
            if (playlist != null && playlist.size() > 1) {
                g.drawString((index + 1) + " / " + playlist.size(), x + w / 2, ty,
                             Graphics.TOP | Graphics.HCENTER);
            }
            ty += Theme.FONT_H + 10;
            /* progress */
            int pos = Sys.mediaPos();
            int bx = x + 16, bw = w - 32;
            g.setColor(Theme.C_SCROLL);
            g.fillRect(bx, ty, bw, 6);
            g.setColor(Theme.C_SEL);
            if (duration > 0) {
                g.fillRect(bx, ty, (int) ((long) bw * Math.min(pos, duration) / duration), 6);
            }
            g.setColor(Theme.C_TEXT);
            g.drawString(Sys.clock(pos), bx, ty + 8, Graphics.TOP | Graphics.LEFT);
            g.drawString(Sys.clock(duration), bx + bw, ty + 8, Graphics.TOP | Graphics.RIGHT);
            int st = Sys.mediaState();
            String state = st == Sys.MS_PLAYING ? "Playing" : (st == Sys.MS_PAUSED ? "Paused"
                : (st == Sys.MS_ENDED ? "Finished" : "Stopped"));
            g.setColor(Theme.C_TEXT_DIM);
            g.drawString(state, x + w / 2, ty + 8, Graphics.TOP | Graphics.HCENTER);
            ty += 8 + Theme.FONT_H + 10;
            /* volume + modes */
            g.setColor(Theme.C_TEXT);
            g.drawString("Volume", bx, ty, Graphics.TOP | Graphics.LEFT);
            int vx = bx + 50, vw = bw - 50;
            g.setColor(Theme.C_SCROLL);
            g.fillRect(vx, ty + Theme.FONT_H / 2 - 2, vw, 4);
            g.setColor(Theme.C_SEL);
            g.fillRect(vx, ty + Theme.FONT_H / 2 - 2, vw * volume() / 100, 4);
            ty += Theme.FONT_H + 6;
            String rep = Prefs.get(P_REPEAT, "off");
            String modes = (rep.equals("off") ? "" : (rep.equals("one") ? "Repeat one" : "Repeat all"))
                + (Prefs.getBool(P_SHUFFLE, false) ? (rep.equals("off") ? "Shuffle" : ", shuffle") : "");
            if (modes.length() > 0) {
                g.setColor(Theme.C_TEXT_DIM);
                g.drawString(modes, x + w / 2, ty, Graphics.TOP | Graphics.HCENTER);
            }
        }

        boolean key(int k) {
            switch (k) {
            case Keymap.SELECT:
                togglePause();
                repaint();
                return true;
            case Keymap.LEFT:
                previous();
                repaint();
                return true;
            case Keymap.RIGHT:
                if (!next(false)) {
                    shell.info("End of list");
                }
                repaint();
                return true;
            case Keymap.UP:
                setVolume(volume() + 10);
                repaint();
                return true;
            case Keymap.DOWN:
                setVolume(volume() - 10);
                repaint();
                return true;
            case Keymap.STAR: {
                int i = (SettingsMenu.indexOf(REPEAT_KEYS, Prefs.get(P_REPEAT, "off")) + 1) % 3;
                Prefs.set(P_REPEAT, REPEAT_KEYS[i]);
                shell.info(REPEAT_LABELS[i]);
                return true;
            }
            case Keymap.POUND: {
                boolean s = !Prefs.getBool(P_SHUFFLE, false);
                Prefs.setBool(P_SHUFFLE, s);
                shell.info(s ? "Shuffle on" : "Shuffle off");
                return true;
            }
            case Keymap.SOFT_L:
                options();
                return true;
            default:
                return false;
            }
        }

        void keyLong(int k) {
            if (k == Keymap.LEFT) {
                seekBy(-10000);
                repaint();
            } else if (k == Keymap.RIGHT) {
                seekBy(10000);
                repaint();
            }
        }

        void keyRepeat(int k) {
            if (k == Keymap.LEFT) {
                seekBy(-5000);
                repaint();
            } else if (k == Keymap.RIGHT) {
                seekBy(5000);
                repaint();
            } else if (k == Keymap.UP || k == Keymap.DOWN) {
                key(k);
            }
        }

        private void options() {
            final Vector o = new Vector();
            boolean playing = Sys.mediaState() == Sys.MS_PLAYING;
            o.addElement(playing ? "Pause" : "Play");
            o.addElement("Stop");
            o.addElement("Next song");
            o.addElement("Previous song");
            o.addElement("Repeat");
            o.addElement("Shuffle");
            o.addElement("Sound output");
            o.addElement("Details");
            o.addElement("Song list");
            shell.showPopup(Popup.menu("Options", o, new Popup.Listener() {
                public void onResult(int r) {
                    String what = (String) o.elementAt(r);
                    if (what.equals("Pause") || what.equals("Play")) {
                        togglePause();
                    } else if (what.equals("Stop")) {
                        stop();
                        shell.pop();
                    } else if (what.equals("Next song")) {
                        next(false);
                    } else if (what.equals("Previous song")) {
                        previous();
                    } else if (what.equals("Repeat")) {
                        shell.showPopup(Popup.choice("Repeat", REPEAT_LABELS,
                            SettingsMenu.indexOf(REPEAT_KEYS, Prefs.get(P_REPEAT, "off")),
                            new Popup.Listener() {
                                public void onResult(int i) {
                                    Prefs.set(P_REPEAT, REPEAT_KEYS[i]);
                                }
                            }));
                    } else if (what.equals("Shuffle")) {
                        key(Keymap.POUND);
                    } else if (what.equals("Sound output")) {
                        shell.showPopup(Popup.choice("Sound output", OUTPUT_LABELS,
                            SettingsMenu.indexOf(OUTPUT_KEYS, Prefs.get(P_OUTPUT, "auto")),
                            new Popup.Listener() {
                                public void onResult(int i) {
                                    Prefs.set(P_OUTPUT, OUTPUT_KEYS[i]);
                                    reroute();
                                }
                            }));
                    } else if (what.equals("Details")) {
                        details();
                    } else {
                        shell.push(new SongsScreen());
                    }
                    repaint();
                }
            }));
        }

        private void details() {
            if (current == null) {
                return;
            }
            int type = Sys.mediaInfo(Sys.M_TYPE);
            shell.push(new TextViewScreen("Details",
                "Title: " + title
                + (artist != null ? "\nArtist: " + artist : "")
                + (album != null ? "\nAlbum: " + album : "")
                + "\nFile: " + Sys.nameOf(current)
                + "\nFormat: " + (type == 2 ? "MP3" : (type == 1 ? "WAV" : "AVI"))
                + ", " + Sys.mediaInfo(Sys.M_RATE) + " Hz, "
                + (Sys.mediaInfo(Sys.M_CHANNELS) == 2 ? "stereo" : "mono")
                + ", " + Sys.mediaInfo(Sys.M_BITRATE) + " kbit/s"
                + "\nLength: " + Sys.clock(duration)
                + "\nSize: " + Sys.sizeText(Sys.size(current))
                + "\nLocation: " + Sys.parentOf(current)));
        }
    }

    /* ------------------------------------------------------------------ */
    /*                           video library                            */
    /* ------------------------------------------------------------------ */

    class VideoLibraryScreen extends ListScreen {
        private Vector clips;

        VideoLibraryScreen() {
            super("Video player");
            rowH = 34;
            emptyText = "No video clips found";
        }

        void refresh() {
            Object keep = current() == null ? null : current().data;
            items.removeAllElements();
            if (clips == null) {
                clips = scan(false);
            }
            for (int i = 0; i < clips.size(); i++) {
                Sys.Entry e = (Sys.Entry) clips.elementAt(i);
                add(new Item(Sys.stripExt(e.name),
                    Sys.dateText(e.mtime) + ", " + Sys.sizeText(e.size), e)
                    .icon(Icons.get("file_video")));
            }
            if (keep != null) {
                selectData(keep);
            }
        }

        String titleRight() {
            return items.isEmpty() ? null : (selected + 1) + "/" + items.size();
        }

        String softLeft() { return "Options"; }

        boolean key(int k) {
            if (k == Keymap.SOFT_L && current() == null) {
                shell.showPopup(Popup.menu("Options", new String[] {"Open folder", "Refresh"},
                    new Popup.Listener() {
                        public void onResult(int r) {
                            if (r == 0) {
                                browse();
                            } else {
                                clips = null;
                                refresh();
                                repaint();
                            }
                        }
                    }));
                return true;
            }
            return super.key(k);
        }

        String[] optionsMenu(Item it) {
            return new String[] {"Play", "Details", "Open folder", "Delete", "Refresh"};
        }

        private void browse() {
            shell.menus.files.pick(FileManager.VIDEO_EXTS, new FileManager.PickListener() {
                public void onPick(String path) {
                    playVideo(path);
                }
            });
        }

        void select(Item it) {
            playVideo(((Sys.Entry) it.data).path);
        }

        void option(Item it, int r) {
            Sys.Entry e = (Sys.Entry) it.data;
            if (r == 0) {
                select(it);
            } else if (r == 1) {
                shell.push(new TextViewScreen("Details",
                    "Name: " + e.name + "\nType: " + FileManager.typeName(e.ext())
                    + (e.ext().equals("avi") ? "" : " (not playable in Java mode)")
                    + "\nSize: " + Sys.sizeText(e.size) + "\nRecorded: " + Sys.dateText(e.mtime)
                    + "\nLocation: " + Sys.parentOf(e.path)));
            } else if (r == 2) {
                browse();
            } else if (r == 3) {
                clear(it);
            } else {
                clips = null;
                refresh();
                clamp();
                repaint();
            }
        }

        void clear(Item it) {
            final Sys.Entry e = (Sys.Entry) it.data;
            shell.showPopup(Popup.confirm("Delete " + e.name + "?", new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        shell.info(Sys.remove(e.path) ? "Deleted" : "Cannot delete");
                        clips = null;
                        refresh();
                        clamp();
                    }
                }
            }));
        }
    }

    /* ------------------------------------------------------------------ */
    /*                            video screen                            */
    /* ------------------------------------------------------------------ */

    class VideoScreen extends Screen {
        private static final int TICK_MS = 60;
        private static final int BAR_MS = 3000;

        private final String path;
        private int[] buf;
        private int fw, fh;
        private int maxW, maxH;
        private boolean opened;
        private String error;
        private TimerTask tick;
        private long barUntil;
        private boolean full;
        private int dur;
        private boolean hasVideo, hasAudio;

        VideoScreen(String path) {
            super(Sys.nameOf(path));
            this.path = path;
            full = Prefs.getBool(P_VIDEO_FULL, false);
            applyFull();
        }

        private void applyFull() {
            title = full ? null : Sys.nameOf(path);
        }

        boolean overlayStatus() { return full; }

        String softLeft() { return error == null ? "Options" : null; }
        String softMid() {
            if (error != null || !opened) return null;
            return Sys.mediaState() == Sys.MS_PLAYING ? "Pause" : "Play";
        }

        void onShow() {
            if (opened) {
                return;                    // back from a Details screen
            }
            // the music player shares the native player
            if (hasTrack()) {
                stop();
            }
            maxW = Theme.W;
            maxH = Theme.H - Theme.SOFT_H - (full ? 0 : Theme.STATUS_H + Theme.TITLE_H);
            buf = new int[Theme.W * (Theme.H - Theme.SOFT_H)];
            route(true);
            int rc = Sys.mediaOpen(path, maxW, maxH);
            if (rc != 0) {
                error = errorText(rc);
                route(false);
                repaint();
                return;
            }
            opened = true;
            dur = Sys.mediaInfo(Sys.M_DURATION);
            hasVideo = Sys.mediaInfo(Sys.M_HAS_VIDEO) != 0;
            hasAudio = Sys.mediaInfo(Sys.M_HAS_AUDIO) != 0;
            Sys.mediaVolume(volume());
            Sys.mediaPlay();
            showBar();
            tick = shell.every(new Runnable() {
                public void run() { frameTick(); }
            }, TICK_MS);
        }

        void onHide() {
            // only when really leaving: Details pushes over us and returns
            if (shell.top() != this && !(shell.top() instanceof TextViewScreen)) {
                close();
            }
        }

        private void close() {
            if (tick != null) {
                tick.cancel();
                tick = null;
            }
            if (opened) {
                Sys.mediaClose();
                opened = false;
            }
            route(false);
            buf = null;
        }

        void back() {
            close();
            shell.pop();
        }

        private void showBar() {
            barUntil = System.currentTimeMillis() + BAR_MS;
        }

        private void frameTick() {
            if (!opened || buf == null) {
                return;
            }
            int r = Sys.mediaFrame(buf);
            boolean changed = false;
            if (r != 0) {
                fw = r >> 16;
                fh = r & 0xffff;
                changed = true;
            }
            int st = Sys.mediaState();
            if (st < 0) {
                error = Sys.mediaError(st);
                changed = true;
            } else if (st == Sys.MS_ENDED) {
                barUntil = Long.MAX_VALUE;
                changed = true;
            }
            long now = System.currentTimeMillis();
            if (now < barUntil || barUntil - now < TICK_MS * 2) {
                changed = true;             // bar visible: keep the time fresh
            }
            if (changed) {
                repaint();
            }
        }

        void paint(Graphics g, int x, int y, int w, int h) {
            g.setColor(0x000000);
            g.fillRect(x, y, w, h);
            if (error != null) {
                g.setColor(0xffffff);
                String[] lines = Theme.wrap(error, w - 16);
                for (int i = 0; i < lines.length; i++) {
                    g.drawString(lines[i], x + w / 2, y + h / 2 - Theme.FONT_H + i * Theme.FONT_H,
                                 Graphics.TOP | Graphics.HCENTER);
                }
                return;
            }
            if (fw > 0 && fh > 0 && buf != null) {
                g.drawRGB(buf, 0, fw, x + (w - fw) / 2, y + (h - fh) / 2, fw, fh, false);
            } else if (!hasVideo) {
                g.setColor(0xffffff);
                g.drawString("Sound only", x + w / 2, y + h / 2 - Theme.FONT_H,
                             Graphics.TOP | Graphics.HCENTER);
            }
            int st = Sys.mediaState();
            boolean bar = System.currentTimeMillis() < barUntil || st != Sys.MS_PLAYING;
            if (bar) {
                int by = y + h - 24;
                g.setColor(0x000000);
                g.fillRect(x, by, w, 24);
                int pos = Sys.mediaPos();
                int bx = x + 8, bw = w - 16;
                g.setColor(0x505860);
                g.fillRect(bx, by + 4, bw, 4);
                g.setColor(0xffffff);
                if (dur > 0) {
                    g.fillRect(bx, by + 4, (int) ((long) bw * Math.min(pos, dur) / dur), 4);
                }
                g.drawString(Sys.clock(pos) + " / " + Sys.clock(dur), bx, by + 9,
                             Graphics.TOP | Graphics.LEFT);
                String s = st == Sys.MS_PAUSED ? "Paused" : (st == Sys.MS_ENDED ? "End" : "");
                if (!hasAudio) {
                    s = (s.length() > 0 ? s + ", " : "") + "silent";
                }
                g.drawString(s, bx + bw, by + 9, Graphics.TOP | Graphics.RIGHT);
            }
        }

        private void toggle() {
            int st = Sys.mediaState();
            if (st == Sys.MS_PLAYING) {
                Sys.mediaPause();
            } else if (st == Sys.MS_ENDED || st == Sys.MS_STOPPED) {
                Sys.mediaSeek(0);
                Sys.mediaPlay();
                barUntil = 0;
                showBar();
            } else {
                Sys.mediaPlay();
                showBar();
            }
            repaint();
        }

        private void seek(int ms) {
            if (!opened) {
                return;
            }
            int to = Math.max(0, Math.min(dur, Sys.mediaPos() + ms));
            Sys.mediaSeek(to);
            if (Sys.mediaState() == Sys.MS_ENDED) {
                Sys.mediaPlay();
            }
            showBar();
            repaint();
        }

        boolean key(int k) {
            if (error != null) {
                return false;
            }
            switch (k) {
            case Keymap.SELECT:
                toggle();
                return true;
            case Keymap.LEFT:
                seek(-5000);
                return true;
            case Keymap.RIGHT:
                seek(5000);
                return true;
            case Keymap.UP:
                setVolume(volume() + 10);
                shell.info("Volume " + volume() + "%");
                return true;
            case Keymap.DOWN:
                setVolume(volume() - 10);
                shell.info("Volume " + volume() + "%");
                return true;
            case Keymap.SOFT_L:
                options();
                return true;
            default:
                return false;
            }
        }

        void keyRepeat(int k) {
            if (k == Keymap.LEFT || k == Keymap.RIGHT) {
                key(k);
            }
        }

        private void options() {
            final Vector o = new Vector();
            boolean playing = Sys.mediaState() == Sys.MS_PLAYING;
            o.addElement(playing ? "Pause" : "Play");
            o.addElement("Restart");
            o.addElement(full ? "Normal screen" : "Full screen");
            o.addElement("Sound output");
            o.addElement("Details");
            o.addElement("Delete");
            shell.showPopup(Popup.menu("Options", o, new Popup.Listener() {
                public void onResult(int r) {
                    String what = (String) o.elementAt(r);
                    if (what.equals("Pause") || what.equals("Play")) {
                        toggle();
                    } else if (what.equals("Restart")) {
                        Sys.mediaSeek(0);
                        Sys.mediaPlay();
                        showBar();
                    } else if (what.startsWith("Full") || what.startsWith("Normal")) {
                        full = !full;
                        Prefs.setBool(P_VIDEO_FULL, full);
                        applyFull();
                        // the frame size follows the content area: reopen
                        int pos = Sys.mediaPos();
                        close();
                        onShow();
                        Sys.mediaSeek(pos);
                    } else if (what.equals("Sound output")) {
                        shell.showPopup(Popup.choice("Sound output", OUTPUT_LABELS,
                            SettingsMenu.indexOf(OUTPUT_KEYS, Prefs.get(P_OUTPUT, "auto")),
                            new Popup.Listener() {
                                public void onResult(int i) {
                                    Prefs.set(P_OUTPUT, OUTPUT_KEYS[i]);
                                    reroute();
                                }
                            }));
                    } else if (what.equals("Details")) {
                        shell.push(new TextViewScreen("Details",
                            "Name: " + Sys.nameOf(path)
                            + "\nPicture: " + (hasVideo ? Sys.mediaInfo(Sys.M_WIDTH) + "x"
                                + Sys.mediaInfo(Sys.M_HEIGHT) + ", " + (Sys.mediaInfo(Sys.M_FPS100) / 100)
                                + " fps, " + Sys.mediaInfo(Sys.M_FRAMES) + " frames (Motion JPEG)" : "none")
                            + "\nSound: " + (hasAudio ? Sys.mediaInfo(Sys.M_RATE) + " Hz "
                                + (Sys.mediaInfo(Sys.M_CHANNELS) == 2 ? "stereo" : "mono") : "none")
                            + "\nLength: " + Sys.clock(dur)
                            + "\nSkipped frames: " + Sys.mediaInfo(Sys.M_DROPPED)
                            + "\nSize: " + Sys.sizeText(Sys.size(path))
                            + "\nLocation: " + Sys.parentOf(path)));
                    } else {
                        shell.showPopup(Popup.confirm("Delete " + Sys.nameOf(path) + "?",
                            new Popup.Listener() {
                                public void onResult(int r2) {
                                    if (r2 == 1) {
                                        close();
                                        shell.info(Sys.remove(path) ? "Deleted" : "Cannot delete");
                                        shell.pop();
                                    }
                                }
                            }));
                    }
                    repaint();
                }
            }));
        }
    }
}
