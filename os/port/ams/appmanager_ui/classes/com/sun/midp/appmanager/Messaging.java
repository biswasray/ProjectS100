/*
 * S100 shell - Messaging: compose, Inbox, Drafts, Outbox, Sent items.
 *
 * Messages live in RMS ("s100_msgs"). There is no SMS service in this
 * build (JSR-120 needs the rild glue, see os/README.md roadmap), so
 * "Send" files the message in Outbox and says why; everything else
 * (writing, folders, editing drafts) works like a phone.
 */

package com.sun.midp.appmanager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;
import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

class Messaging {
    static final String STORE = "s100_msgs";
    static final int INBOX = 0;
    static final int DRAFTS = 1;
    static final int SENT = 2;
    static final int OUTBOX = 3;
    static final String[] FOLDER_NAMES = {"Inbox", "Drafts", "Sent items", "Outbox"};
    static final int MAX_TEXT = 480;      // 3 concatenated SMS parts

    static class Message {
        int id;
        int folder;
        String address;
        String text;
        long time;
    }

    private final Shell shell;

    Messaging(Shell shell) {
        this.shell = shell;
    }

    /* ---------------- storage ---------------- */

    Vector list(int folder) {
        Vector v = new Vector();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            RecordEnumeration e = rs.enumerateRecords(null, null, false);
            while (e.hasNextElement()) {
                int id = e.nextRecordId();
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(id)));
                Message m = new Message();
                m.id = id;
                m.folder = in.readByte();
                m.address = in.readUTF();
                m.text = in.readUTF();
                m.time = in.readLong();
                if (m.folder != folder) {
                    continue;
                }
                int i = 0;
                while (i < v.size() && ((Message) v.elementAt(i)).time > m.time) {
                    i++;
                }
                v.insertElementAt(m, i);
            }
            e.destroy();
        } catch (Exception ex) {
            // empty
        } finally {
            Prefs.close(rs);
        }
        return v;
    }

    int count(int folder) {
        return list(folder).size();
    }

    boolean save(Message m) {
        RecordStore rs = null;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bos);
            out.writeByte(m.folder);
            out.writeUTF(m.address == null ? "" : m.address);
            out.writeUTF(m.text == null ? "" : m.text);
            out.writeLong(m.time == 0 ? System.currentTimeMillis() : m.time);
            byte[] d = bos.toByteArray();
            rs = RecordStore.openRecordStore(STORE, true);
            if (m.id == 0) {
                m.id = rs.addRecord(d, 0, d.length);
            } else {
                rs.setRecord(m.id, d, 0, d.length);
            }
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            Prefs.close(rs);
        }
    }

    void delete(Message m) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            rs.deleteRecord(m.id);
        } catch (Exception ex) {
            // ignore
        } finally {
            Prefs.close(rs);
        }
    }

    void deleteAll() {
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (Exception ex) {
            // ignore
        }
    }

    /* ---------------- menu ---------------- */

    MenuItem[] items() {
        return new MenuItem[] {
            new MenuItem("messaging.compose", "Create message", "compose", new Runnable() {
                public void run() { compose(null, null); }
            }),
            new MenuItem("messaging.inbox", "Inbox", "inbox", new Runnable() {
                public void run() { shell.push(new FolderScreen(INBOX)); }
            }),
            new MenuItem("messaging.drafts", "Drafts", null, new Runnable() {
                public void run() { shell.push(new FolderScreen(DRAFTS)); }
            }),
            new MenuItem("messaging.outbox", "Outbox", null, new Runnable() {
                public void run() { shell.push(new FolderScreen(OUTBOX)); }
            }),
            new MenuItem("messaging.sent", "Sent items", null, new Runnable() {
                public void run() { shell.push(new FolderScreen(SENT)); }
            }),
            new MenuItem("messaging.deleteall", "Delete messages", null, new Runnable() {
                public void run() {
                    shell.showPopup(Popup.confirm("Delete all messages?", new Popup.Listener() {
                        public void onResult(int r) {
                            if (r == 1) {
                                deleteAll();
                                shell.info("Messages deleted");
                            }
                        }
                    }));
                }
            }),
        };
    }

    /** Writes a message; `to` and `text` pre-fill (reply / edit draft). */
    void compose(String to, String text) {
        compose(to, text, null);
    }

    private void compose(final String to, String text, final Message editing) {
        shell.push(new TextInputScreen("Message:", text, TextInputScreen.TEXT, MAX_TEXT,
            "Options", new TextInputScreen.Listener() {
                public void onText(String t) { }
            }) {
            boolean key(int k) {
                if (k == Keymap.SOFT_L || k == Keymap.SELECT) {
                    composeOptions(this, to, editing);
                    return true;
                }
                return super.key(k);
            }
        });
    }

    private void composeOptions(final TextInputScreen editor, final String to,
                                final Message editing) {
        String[] opts = {"Send", "Save to Drafts", "Cancel"};
        shell.showPopup(Popup.menu("Options", opts, new Popup.Listener() {
            public void onResult(int r) {
                final String text = editor.text();
                if (r == 2) {
                    shell.pop();
                    return;
                }
                if (r == 1) {
                    Message m = (editing != null) ? editing : new Message();
                    m.folder = DRAFTS;
                    m.address = (to == null) ? "" : to;
                    m.text = text;
                    m.time = System.currentTimeMillis();
                    save(m);
                    shell.pop();
                    shell.info("Saved to Drafts");
                    return;
                }
                // Send: ask for the recipient first
                shell.push(new TextInputScreen("To:", to, TextInputScreen.NUMBER,
                    Contacts.MAX_NUMBER, "Send", new TextInputScreen.Listener() {
                        public void onText(String num) {
                            Message m = (editing != null) ? editing : new Message();
                            m.folder = OUTBOX;
                            m.address = num.trim();
                            m.text = text;
                            m.time = System.currentTimeMillis();
                            save(m);
                            shell.pop();               // the editor
                            shell.showPopup(Popup.info("No message service on this build.\n"
                                + "Message saved to Outbox.", 0));
                        }
                    }));
            }
        }));
    }

    /* ---------------- screens ---------------- */

    class FolderScreen extends ListScreen {
        private final int folder;

        FolderScreen(int folder) {
            super(FOLDER_NAMES[folder]);
            this.folder = folder;
            emptyText = "No messages";
            rowH = 34;
        }

        void refresh() {
            items.removeAllElements();
            Vector v = list(folder);
            for (int i = 0; i < v.size(); i++) {
                Message m = (Message) v.elementAt(i);
                String who = (m.address.length() == 0) ? "(no number)"
                    : shell.menus.contacts.display(m.address);
                String preview = m.text.replace('\n', ' ');
                add(new Item(who, Clock.stamp(m.time) + "  " + preview, m));
            }
        }

        String[] optionsMenu(Item it) {
            return new String[] {"Open", "Edit", "Use number", "Delete"};
        }

        void select(Item it) {
            final Message m = (Message) it.data;
            String head = (m.address.length() == 0 ? "" : "To: " + m.address + "\n")
                + Clock.stamp(m.time) + "\n\n";
            TextViewScreen view = new TextViewScreen(it.label, head + m.text) {
                void option(int r) {
                    if (r == 0) {
                        shell.pop();
                        compose(m.address, m.text, m);
                    } else {
                        delete(m);
                        shell.pop();
                        refresh();
                        clamp();
                    }
                }
            };
            shell.push(view.options(new String[] {"Edit", "Delete"}));
        }

        void option(Item it, int r) {
            final Message m = (Message) it.data;
            switch (r) {
            case 0:
                select(it);
                break;
            case 1:
                compose(m.address, m.text, m);
                break;
            case 2:
                if (m.address.length() == 0) {
                    shell.info("No number");
                } else {
                    shell.push(new DialerScreen(m.address));
                }
                break;
            case 3:
                clear(it);
                break;
            }
        }

        void clear(Item it) {
            final Message m = (Message) it.data;
            shell.showPopup(Popup.confirm("Delete message?", new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        delete(m);
                        refresh();
                        clamp();
                        repaint();
                    }
                }
            }));
        }
    }
}
