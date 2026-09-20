/*
 * S100 shell - Applications: the Java suites the AMS knows about.
 *
 *   Collection        installed MIDlet suites: Open, Details, Update,
 *                     Application settings, Delete
 *   Install           the graphical installer (DiscoveryApp)
 *   Running           MIDlets currently running: Foreground, End
 *   Certificates      CA manager (when built in)
 *
 * All AMS work goes through AppManagerUIImpl; this class is only UI.
 */

package com.sun.midp.appmanager;

import java.util.Vector;
import com.sun.midp.main.MIDletProxy;

class AppsMenu {
    private final Shell shell;

    AppsMenu(Shell shell) {
        this.shell = shell;
    }

    MenuItem[] items() {
        Vector v = new Vector();
        v.addElement(new MenuItem("applications.collection", "Collection", "applications",
            new Runnable() {
                public void run() { shell.push(new CollectionScreen()); }
            }));
        v.addElement(new MenuItem("applications.install", "Install application", "install",
            new Runnable() {
                public void run() { shell.ams.install(); }
            }));
        v.addElement(new MenuItem("applications.running", "Running applications", "running",
            new Runnable() {
                public void run() { shell.push(new RunningScreen()); }
            }));
        if (shell.ams.hasCaManager()) {
            v.addElement(new MenuItem("applications.certificates", "Certificates", "certificate",
                new Runnable() {
                    public void run() { shell.ams.launchCaManager(); }
                }));
        }
        if (shell.ams.hasComponentManager()) {
            v.addElement(new MenuItem("applications.components", "Components", null,
                new Runnable() {
                    public void run() { shell.ams.launchComponentManager(); }
                }));
        }
        MenuItem[] out = new MenuItem[v.size()];
        v.copyInto(out);
        return out;
    }

    /** Installed suites (the AMS list without the internal tools). */
    class CollectionScreen extends ListScreen {
        CollectionScreen() {
            super("Collection");
            emptyText = "No applications";
            rowH = 30;
        }

        void refresh() {
            items.removeAllElements();
            Vector v = shell.ams.userSuites();
            for (int i = 0; i < v.size(); i++) {
                RunningMIDletSuiteInfo si = (RunningMIDletSuiteInfo) v.elementAt(i);
                Item it = new Item(si.displayName, si).icon(si.icon);
                if (si.hasRunningMidlet()) {
                    it.value = "running";
                } else if (!si.enabled) {
                    it.value = "disabled";
                }
                add(it);
            }
        }

        void onShow() {
            shell.ams.ensureNoInternalMIDletsRunning();
            super.onShow();
        }

        String[] optionsMenu(Item it) {
            return new String[] {"Open", "Details", "Update", "Application settings", "Delete"};
        }

        void select(Item it) {
            shell.ams.open((RunningMIDletSuiteInfo) it.data);
        }

        void option(Item it, int r) {
            RunningMIDletSuiteInfo si = (RunningMIDletSuiteInfo) it.data;
            switch (r) {
            case 0: shell.ams.open(si); break;
            case 1: shell.ams.details(si); break;
            case 2: shell.ams.update(si); break;
            case 3: shell.ams.appSettings(si); break;
            case 4: clear(it); break;
            }
        }

        void clear(Item it) {
            final RunningMIDletSuiteInfo si = (RunningMIDletSuiteInfo) it.data;
            shell.showPopup(Popup.confirm("Delete " + si.displayName + "?", new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        shell.ams.remove(si);
                    }
                }
            }));
        }
    }

    /** MIDlets with a live proxy, from every suite. */
    class RunningScreen extends ListScreen {
        RunningScreen() {
            super("Running applications");
            emptyText = "No applications running";
            rowH = 30;
        }

        void refresh() {
            items.removeAllElements();
            Vector v = shell.ams.allSuites();
            for (int i = 0; i < v.size(); i++) {
                RunningMIDletSuiteInfo si = (RunningMIDletSuiteInfo) v.elementAt(i);
                MIDletProxy[] ps = si.getProxies();
                if (ps == null) {
                    continue;
                }
                for (int j = 0; j < ps.length; j++) {
                    String name = si.displayName;
                    if (!si.hasSingleMidlet() && ps[j].getDisplayName() != null) {
                        name = ps[j].getDisplayName();
                    }
                    add(new Item(name, ps[j]).icon(si.icon));
                }
            }
        }

        String softLeft() { return current() == null ? null : "Options"; }

        String[] optionsMenu(Item it) {
            return new String[] {"Foreground", "End"};
        }

        void select(Item it) {
            shell.ams.foreground((MIDletProxy) it.data);
        }

        void option(Item it, int r) {
            MIDletProxy p = (MIDletProxy) it.data;
            if (r == 0) {
                shell.ams.foreground(p);
            } else {
                shell.ams.end(p);
                refresh();
                clamp();
                repaint();
            }
        }
    }

    /** Chooser for suites with more than one MIDlet. */
    class MidletChooser extends ListScreen {
        private final RunningMIDletSuiteInfo suite;

        MidletChooser(RunningMIDletSuiteInfo suite) {
            super(suite.displayName);
            this.suite = suite;
            emptyText = "No MIDlets";
        }

        void refresh() {
            items.removeAllElements();
            Vector v = shell.ams.midletsOf(suite);
            for (int i = 0; i < v.size(); i++) {
                String[] nameClass = (String[]) v.elementAt(i);
                Item it = new Item(nameClass[0], nameClass[1]);
                if (suite.getProxyFor(nameClass[1]) != null) {
                    it.value = "running";
                }
                add(it);
            }
        }

        String softLeft() { return "Open"; }

        void select(Item it) {
            shell.ams.launch(suite, (String) it.data);
        }
    }
}
