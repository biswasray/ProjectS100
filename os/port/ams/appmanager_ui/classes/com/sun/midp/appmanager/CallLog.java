/*
 * S100 shell - Log: missed / received / dialled numbers (RMS).
 *
 * Only "dialled" ever gets entries today (the dialer adds them); missed
 * and received wait for a telephony backend but the screens are there.
 */

package com.sun.midp.appmanager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;
import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

class CallLog {
    static final String STORE = "s100_calllog";
    static final int MISSED = 0;
    static final int RECEIVED = 1;
    static final int DIALLED = 2;
    static final String[] TYPE_NAMES = {"Missed calls", "Received calls", "Dialled numbers"};
    static final int MAX_PER_TYPE = 20;

    static class Entry {
        int id;
        int type;
        String number;
        long time;
    }

    private final Shell shell;

    CallLog(Shell shell) {
        this.shell = shell;
    }

    /* ---------------- storage ---------------- */

    /** Entries of one type, newest first. */
    Vector list(int type) {
        Vector v = new Vector();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            RecordEnumeration e = rs.enumerateRecords(null, null, false);
            while (e.hasNextElement()) {
                int id = e.nextRecordId();
                Entry en = decode(id, rs.getRecord(id));
                if (en.type != type) {
                    continue;
                }
                int i = 0;
                while (i < v.size() && ((Entry) v.elementAt(i)).time > en.time) {
                    i++;
                }
                v.insertElementAt(en, i);
            }
            e.destroy();
        } catch (Exception ex) {
            // empty
        } finally {
            Prefs.close(rs);
        }
        return v;
    }

    private static Entry decode(int id, byte[] d) throws java.io.IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
        Entry e = new Entry();
        e.id = id;
        e.type = in.readByte();
        e.number = in.readUTF();
        e.time = in.readLong();
        return e;
    }

    void add(int type, String number) {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(type);
            out.writeUTF(number);
            out.writeLong(System.currentTimeMillis());
            byte[] d = bos.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            rs.addRecord(d, 0, d.length);
        } catch (Exception ex) {
            // ignore
        } finally {
            Prefs.close(rs);
        }
        // keep the list short
        Vector v = list(type);
        for (int i = MAX_PER_TYPE; i < v.size(); i++) {
            delete((Entry) v.elementAt(i));
        }
    }

    void delete(Entry e) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            rs.deleteRecord(e.id);
        } catch (Exception ex) {
            // ignore
        } finally {
            Prefs.close(rs);
        }
    }

    void clear(int type) {
        Vector v = list(type);
        for (int i = 0; i < v.size(); i++) {
            delete((Entry) v.elementAt(i));
        }
    }

    void clearAll() {
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (Exception ex) {
            // ignore
        }
    }

    /* ---------------- menu ---------------- */

    MenuItem[] items() {
        return new MenuItem[] {
            new MenuItem("log.missed", TYPE_NAMES[MISSED], null, new Runnable() {
                public void run() { shell.push(new LogScreen(MISSED)); }
            }),
            new MenuItem("log.received", TYPE_NAMES[RECEIVED], null, new Runnable() {
                public void run() { shell.push(new LogScreen(RECEIVED)); }
            }),
            new MenuItem("log.dialled", TYPE_NAMES[DIALLED], null, new Runnable() {
                public void run() { shell.push(new LogScreen(DIALLED)); }
            }),
            new MenuItem("log.clear", "Clear log lists", null, new Runnable() {
                public void run() {
                    String[] opts = {"All", TYPE_NAMES[0], TYPE_NAMES[1], TYPE_NAMES[2]};
                    shell.showPopup(Popup.menu("Clear", opts, new Popup.Listener() {
                        public void onResult(int r) {
                            if (r == 0) {
                                clearAll();
                            } else {
                                clear(r - 1);
                            }
                            shell.info("Log cleared");
                        }
                    }));
                }
            }),
        };
    }

    /* ---------------- screen ---------------- */

    class LogScreen extends ListScreen {
        private final int type;

        LogScreen(int type) {
            super(TYPE_NAMES[type]);
            this.type = type;
            emptyText = "No calls";
            rowH = 34;
        }

        void refresh() {
            items.removeAllElements();
            Vector v = list(type);
            for (int i = 0; i < v.size(); i++) {
                Entry e = (Entry) v.elementAt(i);
                add(new Item(shell.menus.contacts.display(e.number), Clock.stamp(e.time), e));
            }
        }

        String[] optionsMenu(Item it) {
            return new String[] {"Call", "Send message", "Save number", "Delete"};
        }

        void select(Item it) {
            shell.push(new DialerScreen(((Entry) it.data).number));
        }

        void option(Item it, int r) {
            final Entry e = (Entry) it.data;
            switch (r) {
            case 0:
                select(it);
                break;
            case 1:
                shell.menus.messaging.compose(e.number, null);
                break;
            case 2:
                shell.menus.contacts.addNew(null, e.number);
                break;
            case 3:
                delete(e);
                refresh();
                clamp();
                repaint();
                break;
            }
        }

        boolean key(int k) {
            if (k == Keymap.SEND && current() != null) {
                select(current());
                return true;
            }
            return super.key(k);
        }
    }
}
