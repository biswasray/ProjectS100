/*
 * S100 shell - the AppManagerUI implementation the AMS talks to.
 *
 * Replaces the reference Form-based "Java MIDlets" list. AppManagerPeer
 * constructs it exactly like the reference class (same constructors),
 * feeds it suite/MIDlet events through the AppManagerUI interface, and
 * asks it for the main Displayable: our full-screen Shell canvas with the
 * idle screen at the bottom of its stack. Everything the Applications
 * menu can do to a suite is a method here that forwards to
 * ApplicationManager / AppManagerPeer.
 */

package com.sun.midp.appmanager;

import java.util.Vector;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import com.sun.midp.configurator.Constants;
import com.sun.midp.i18n.Resource;
import com.sun.midp.i18n.ResourceConstants;
import com.sun.midp.main.MIDletProxy;
import com.sun.midp.main.MIDletSuiteUtils;
import com.sun.midp.midlet.MIDletSuite;
import com.sun.midp.midletsuite.MIDletInfo;
import com.sun.midp.midletsuite.MIDletSuiteStorage;

class AppManagerUIImpl implements AppManagerUI, CommandListener {
    private final ApplicationManager manager;
    private final AppManagerPeer appManager;
    private final Display display;
    private final DisplayError displayError;
    private final Shell shell;
    /** Every RunningMIDletSuiteInfo the peer appended, internal ones included. */
    private final Vector suites = new Vector();
    private final MIDletSuiteStorage storage;
    private final Command backCmd =
        new Command(Resource.getString(ResourceConstants.BACK), Command.BACK, 1);

    private void init() {
        // nothing beyond field initialisation; kept for symmetry with the reference
    }

    AppManagerUIImpl(ApplicationManager manager, AppManagerPeer appManager,
                     Display display, DisplayError displayError, boolean foldersOn) {
        this.manager = manager;
        this.appManager = appManager;
        this.display = display;
        this.displayError = displayError;
        storage = MIDletSuiteStorage.getMIDletSuiteStorage();
        shell = new Shell(display, this);
        init();
        // first start: the splash, then the idle screen
        display.setCurrent(new SplashScreen(display, shell));
    }

    AppManagerUIImpl(ApplicationManager manager, AppManagerPeer appManager,
                     Display display, DisplayError displayError, boolean foldersOn,
                     boolean askUserIfLaunchMidlet) {
        this.manager = manager;
        this.appManager = appManager;
        this.display = display;
        this.displayError = displayError;
        storage = MIDletSuiteStorage.getMIDletSuiteStorage();
        shell = new Shell(display, this);
        init();
        display.setCurrent(shell);
        if (askUserIfLaunchMidlet) {
            askUserIfLaunchMidlet();
        }
    }

    /* ================= what the menus need ================= */

    Vector allSuites() {
        return suites;
    }

    Vector userSuites() {
        Vector v = new Vector();
        for (int i = 0; i < suites.size(); i++) {
            RunningMIDletSuiteInfo si = (RunningMIDletSuiteInfo) suites.elementAt(i);
            if (!si.isInternal()) {
                v.addElement(si);
            }
        }
        return v;
    }

    int userSuiteCount() {
        return userSuites().size();
    }

    boolean hasCaManager() {
        return appManager.caManagerIncluded();
    }

    boolean hasComponentManager() {
        return appManager.compManagerIncluded();
    }

    void ensureNoInternalMIDletsRunning() {
        appManager.ensureNoInternalMIDletsRunning();
    }

    void shutdown() {
        manager.shutDown();
    }

    void install() {
        shell.whenReleased(new Runnable() {
            public void run() { manager.installSuite(); }
        });
    }

    /**
     * Installs a suite from a URL (file:///path/x.jad or .jar) with the
     * GraphicalInstaller, exactly like the discovery MIDlet does for a
     * downloaded link; the "start it now?" question follows as usual.
     */
    void installFrom(final String url, final String label) {
        shell.whenReleased(new Runnable() {
            public void run() {
                try {
                    MIDletSuiteUtils.executeWithArgs(MIDletSuite.INTERNAL_SUITE_ID,
                        AppManagerPeer.INSTALLER, label, "I", url, label);
                } catch (Throwable t) {
                    displayError.showErrorAlert(label, t, null, null, shell);
                }
            }
        });
    }

    void launchCaManager() {
        shell.whenReleased(new Runnable() {
            public void run() { manager.launchCaManager(); }
        });
    }

    void launchComponentManager() {
        shell.whenReleased(new Runnable() {
            public void run() { manager.launchComponentManager(); }
        });
    }

    /** Open a suite: foreground if running, launch if single, choose if several. */
    void open(RunningMIDletSuiteInfo si) {
        if (si == null) {
            return;
        }
        if (si.isInternal()) {
            if (AppManagerPeer.DISCOVERY_APP.equals(si.midletToRun)) {
                install();
            } else if (AppManagerPeer.CA_MANAGER.equals(si.midletToRun)) {
                launchCaManager();
            } else if (AppManagerPeer.COMP_MANAGER.equals(si.midletToRun)) {
                launchComponentManager();
            } else if (AppManagerPeer.ODT_AGENT.equals(si.midletToRun)) {
                shell.whenReleased(new Runnable() {
                    public void run() { manager.launchODTAgent(); }
                });
            }
            return;
        }
        if (!si.enabled && !si.hasRunningMidlet()) {
            shell.showPopup(Popup.info(si.displayName + " is disabled.\nSee Application settings.", 0));
            return;
        }
        if (si.hasSingleMidlet()) {
            launch(si, si.midletToRun);
        } else {
            shell.push(shell.menus.apps.new MidletChooser(si));
        }
    }

    void launch(final RunningMIDletSuiteInfo si, final String className) {
        shell.whenReleased(new Runnable() {
            public void run() {
                try {
                    if (si.getProxyFor(className) != null) {
                        manager.moveToForeground(si, className);
                    } else {
                        manager.launchSuite(si, className);
                    }
                } catch (Throwable t) {
                    displayError.showErrorAlert(si.displayName, t, null, null, shell);
                }
            }
        });
    }

    void foreground(final MIDletProxy p) {
        final RunningMIDletSuiteInfo si = suiteOf(p);
        if (si != null) {
            shell.whenReleased(new Runnable() {
                public void run() { manager.moveToForeground(si, p.getClassName()); }
            });
        }
    }

    void end(MIDletProxy p) {
        RunningMIDletSuiteInfo si = suiteOf(p);
        if (si != null) {
            manager.exitMidlet(si, p.getClassName());
        }
    }

    private RunningMIDletSuiteInfo suiteOf(MIDletProxy p) {
        for (int i = 0; i < suites.size(); i++) {
            RunningMIDletSuiteInfo si = (RunningMIDletSuiteInfo) suites.elementAt(i);
            if (si.hasProxy(p)) {
                return si;
            }
        }
        return null;
    }

    void details(RunningMIDletSuiteInfo si) {
        try {
            AppInfo info = new AppInfo(si.suiteId);
            info.addCommand(backCmd);
            info.setCommandListener(this);
            display.setCurrent(info);
        } catch (Throwable t) {
            displayError.showErrorAlert(si.displayName, t, null, null, shell);
        }
    }

    void update(RunningMIDletSuiteInfo si) {
        appManager.updateSuite(si);
    }

    void appSettings(RunningMIDletSuiteInfo si) {
        try {
            appManager.showAppSettings(si.suiteId, shell);
        } catch (Throwable t) {
            displayError.showErrorAlert(si.displayName, t, null, null, shell);
        }
    }

    void remove(RunningMIDletSuiteInfo si) {
        try {
            appManager.removeSuite(si);
        } catch (Throwable t) {
            displayError.showErrorAlert(si.displayName, t, null, null, shell);
        }
    }

    /** {name, className} pairs of a suite's MIDlets, in MIDlet-n order. */
    Vector midletsOf(RunningMIDletSuiteInfo si) {
        Vector v = new Vector();
        MIDletSuite suite = null;
        try {
            suite = storage.getMIDletSuite(si.suiteId, false);
            if (suite != null) {
                for (int n = 1; n < 100; n++) {
                    String attr = suite.getProperty("MIDlet-" + n);
                    if (attr == null || attr.length() == 0) {
                        break;
                    }
                    MIDletInfo mi = new MIDletInfo(attr);
                    v.addElement(new String[] {mi.name, mi.classname});
                }
            }
        } catch (Throwable t) {
            displayError.showErrorAlert(si.displayName, t, null, null, shell);
        } finally {
            if (suite != null) {
                suite.close();
            }
        }
        return v;
    }

    /** Back from AppInfo. */
    public void commandAction(Command c, Displayable d) {
        shell.show();
    }

    private void refreshTop() {
        Screen s = shell.top();
        if (s instanceof ListScreen) {
            ((ListScreen) s).refresh();
            ((ListScreen) s).clamp();
        }
        shell.repaint();
    }

    /**
     * The set of installed suites (or one of their names/icons) changed:
     * rebuild the main menu, then whatever list is on top.
     */
    private void suitesChanged() {
        shell.menus.appsChanged();
        refreshTop();
    }

    private void askUserIfLaunchMidlet() {
        if (!appManager.getAndResetRunMIDletQuestionFlag()) {
            return;
        }
        final RunningMIDletSuiteInfo msi = appManager.getLastInstalledMidletItem();
        if (msi == null) {
            return;
        }
        shell.show();
        shell.showPopup(Popup.confirm("Installed: " + msi.displayName + "\nStart it now?",
            new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        open(msi);
                    }
                }
            }));
    }

    /* ================= AppManagerUI ================= */

    public void itemAppended(RunningMIDletSuiteInfo suiteInfo) {
        suites.addElement(suiteInfo);
        suitesChanged();
    }

    public void itemRemoved(RunningMIDletSuiteInfo suiteInfo) {
        suites.removeElement(suiteInfo);
        suitesChanged();
    }

    public void notifyInternalMidletStarted(MIDletProxy midlet) {
        // nothing to do
    }

    public void notifyMidletStarted(RunningMIDletSuiteInfo si, String className) {
        refreshTop();
    }

    public void notifyMidletStateChanged(RunningMIDletSuiteInfo si, MIDletProxy midlet) {
        shell.repaint();
    }

    public void notifyInternalMidletExited(MIDletProxy midlet) {
        // nothing to do
    }

    public void notifyMidletExited(RunningMIDletSuiteInfo si, String midletClassName) {
        // the MIDlet is gone: make sure our canvas is what the user sees
        shell.show();
        refreshTop();
    }

    public void notifySuiteInstalled(RunningMIDletSuiteInfo si) {
        int launchMode = com.sun.midp.main.Configuration.getIntProperty(
            "LaunchJustInstalledMidlet", -1);
        if (launchMode == 0) {
            return;
        }
        if (launchMode == 1) {
            open(si);
            return;
        }
        askUserIfLaunchMidlet();
    }

    public void notifySuiteInstalledExt(RunningMIDletSuiteInfo si) {
        Screen s = shell.top();
        if (s instanceof ListScreen) {
            ((ListScreen) s).selectData(si);
            shell.repaint();
        }
    }

    public void notifySuiteExited(RunningMIDletSuiteInfo suiteInfo) {
        // no per-suite selector state to drop
    }

    public void notifyMIDletSelectorExited(RunningMIDletSuiteInfo suiteInfo) {
        exitMidletSelector(suiteInfo);
    }

    public void notifySuiteRemovedExt(RunningMIDletSuiteInfo si) {
        // nothing to do
    }

    public void notifyMIDletSuiteEnabled(RunningMIDletSuiteInfo si) {
        suitesChanged();
    }

    public void notifyMIDletSuiteIconChaged(RunningMIDletSuiteInfo si) {
        suitesChanged();
    }

    public void notifyMidletStartError(int suiteId, String className, int errorCode,
                                       String errorDetails) {
        String errorMsg;
        switch (errorCode) {
        case Constants.MIDLET_SUITE_NOT_FOUND:
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MIDLETSUITELDR_MIDLETSUITE_NOTFOUND);
            break;
        case Constants.MIDLET_CLASS_NOT_FOUND:
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MIDLETSUITELDR_CANT_LAUNCH_MISSING_CLASS);
            break;
        case Constants.MIDLET_INSTANTIATION_EXCEPTION:
        case Constants.MIDLET_ILLEGAL_ACCESS_EXCEPTION:
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MIDLETSUITELDR_CANT_LAUNCH_ILL_OPERATION);
            break;
        case Constants.MIDLET_OUT_OF_MEM_ERROR:
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MIDLETSUITELDR_QUIT_OUT_OF_MEMORY);
            break;
        case Constants.MIDLET_RESOURCE_LIMIT:
        case Constants.MIDLET_ISOLATE_RESOURCE_LIMIT:
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MIDLETSUITELDR_RESOURCE_LIMIT_ERROR);
            break;
        case Constants.MIDLET_ISOLATE_CONSTRUCTOR_FAILED:
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MIDLETSUITELDR_CANT_EXE_NEXT_MIDLET);
            break;
        case Constants.MIDLET_SUITE_DISABLED:
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MIDLETSUITELDR_MIDLETSUITE_DISABLED);
            break;
        case Constants.MIDLET_INSTALLER_RUNNING: {
            String[] values = new String[1];
            values[0] = className;
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MGR_UPDATE_IS_RUNNING, values);
            break;
        }
        default:
            errorMsg = Resource.getString(
                ResourceConstants.AMS_MIDLETSUITELDR_UNEXPECTEDLY_QUIT);
        }
        if (errorDetails != null) {
            errorMsg += "\n\n" + errorDetails;
        }
        displayError.showErrorAlert(null, null,
                                    Resource.getString(ResourceConstants.EXCEPTION),
                                    errorMsg, shell);
    }

    public void notifyMIDletSuiteStateChanged(RunningMIDletSuiteInfo si,
                                              RunningMIDletSuiteInfo newSi) {
        // the peer copies newSi into si right after this call (new name,
        // icon...): pick the changes up once it is done
        display.callSerially(new Runnable() {
            public void run() { suitesChanged(); }
        });
    }

    public void setCurrentItem(RunningMIDletSuiteInfo item) {
        notifySuiteInstalledExt(item);
    }

    public RunningMIDletSuiteInfo getSelectedMIDletSuiteInfo() {
        Screen s = shell.top();
        if (s instanceof ListScreen) {
            ListScreen.Item it = ((ListScreen) s).current();
            if (it != null && it.data instanceof RunningMIDletSuiteInfo) {
                return (RunningMIDletSuiteInfo) it.data;
            }
        }
        return null;
    }

    public void showMidletSwitcher(boolean onlyFromLaunchedList) {
        shell.show();
        if (!(shell.top() instanceof AppsMenu.RunningScreen)) {
            boolean anyRunning = false;
            for (int i = 0; i < suites.size() && !anyRunning; i++) {
                anyRunning = ((RunningMIDletSuiteInfo) suites.elementAt(i)).hasRunningMidlet();
            }
            if (anyRunning || !onlyFromLaunchedList) {
                shell.push(shell.menus.apps.new RunningScreen());
            }
        }
    }

    public void showMidletSelector(RunningMIDletSuiteInfo msiToRun) {
        shell.show();
        if (msiToRun != null) {
            shell.push(shell.menus.apps.new MidletChooser(msiToRun));
        }
    }

    public void exitMidletSelector(RunningMIDletSuiteInfo msi) {
        if (shell.top() instanceof AppsMenu.MidletChooser) {
            shell.pop();
        }
    }

    public void cleanUp() {
        // nothing to release
    }

    public Displayable getMainDisplayable() {
        return shell;
    }
}
