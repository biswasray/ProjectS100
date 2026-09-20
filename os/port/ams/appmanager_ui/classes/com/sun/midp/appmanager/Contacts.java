/*
 * S100 shell - Contacts: RMS-backed phone book and its screens.
 *
 *   Contacts > Names           list, type a key to jump to that letter,
 *                              Options: Call, Send message, Edit, Delete
 *   Contacts > Add new         name, then number
 *   Contacts > Delete all
 */

package com.sun.midp.appmanager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.Vector;
import javax.microedition.rms.RecordEnumeration;
import javax.microedition.rms.RecordStore;

class Contacts {
    static final String STORE = "s100_contacts";
    static final int MAX_NAME = 40;
    static final int MAX_NUMBER = 40;
    private static final String[] KEY_LETTERS = {
        "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"
    };

    static class Contact {
        int id;
        String name;
        String number;
    }

    private final Shell shell;
    private Vector cache;

    Contacts(Shell shell) {
        this.shell = shell;
    }

    /* ---------------- storage ---------------- */

    synchronized Vector all() {
        if (cache != null) {
            return cache;
        }
        Vector v = new Vector();
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            RecordEnumeration e = rs.enumerateRecords(null, null, false);
            while (e.hasNextElement()) {
                int id = e.nextRecordId();
                byte[] d = rs.getRecord(id);
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(d));
                Contact c = new Contact();
                c.id = id;
                c.name = in.readUTF();
                c.number = in.readUTF();
                insertSorted(v, c);
            }
            e.destroy();
        } catch (Exception ex) {
            // empty book
        } finally {
            Prefs.close(rs);
        }
        cache = v;
        return v;
    }

    private static void insertSorted(Vector v, Contact c) {
        String k = c.name.toLowerCase();
        int i = 0;
        while (i < v.size() && ((Contact) v.elementAt(i)).name.toLowerCase().compareTo(k) < 0) {
            i++;
        }
        v.insertElementAt(c, i);
    }

    private static byte[] encode(Contact c) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeUTF(c.name);
        out.writeUTF(c.number);
        return bos.toByteArray();
    }

    synchronized boolean save(Contact c) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            byte[] d = encode(c);
            if (c.id == 0) {
                c.id = rs.addRecord(d, 0, d.length);
            } else {
                rs.setRecord(c.id, d, 0, d.length);
            }
            cache = null;
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            Prefs.close(rs);
        }
    }

    synchronized void delete(Contact c) {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(STORE, true);
            rs.deleteRecord(c.id);
        } catch (Exception ex) {
            // ignore
        } finally {
            Prefs.close(rs);
            cache = null;
        }
    }

    synchronized void deleteAll() {
        try {
            RecordStore.deleteRecordStore(STORE);
        } catch (Exception ex) {
            // ignore
        }
        cache = null;
    }

    Contact findByNumber(String number) {
        if (number == null || number.length() == 0) {
            return null;
        }
        Vector v = all();
        for (int i = 0; i < v.size(); i++) {
            Contact c = (Contact) v.elementAt(i);
            if (c.number.equals(number)) {
                return c;
            }
        }
        return null;
    }

    /** Name for a number if known, else the number. */
    String display(String number) {
        Contact c = findByNumber(number);
        return (c == null) ? number : c.name;
    }

    /* ---------------- menu ---------------- */

    MenuItem[] items() {
        return new MenuItem[] {
            new MenuItem("contacts.names", "Names", null, new Runnable() {
                public void run() { shell.push(new NamesScreen(null)); }
            }),
            new MenuItem("contacts.add", "Add new contact", null, new Runnable() {
                public void run() { addNew(null, null); }
            }),
            new MenuItem("contacts.memory", "Memory status", null, new Runnable() {
                public void run() {
                    shell.showPopup(Popup.info("Contacts: " + all().size(), 0));
                }
            }),
            new MenuItem("contacts.deleteall", "Delete all contacts", null, new Runnable() {
                public void run() {
                    shell.showPopup(Popup.confirm("Delete all contacts?", new Popup.Listener() {
                        public void onResult(int r) {
                            if (r == 1) {
                                deleteAll();
                                shell.info("Contacts deleted");
                            }
                        }
                    }));
                }
            }),
        };
    }

    /** Name then number; either may be pre-filled (dialer: number known). */
    void addNew(final String name, final String number) {
        shell.push(new TextInputScreen("Name:", name, TextInputScreen.TEXT, MAX_NAME, "OK",
            new TextInputScreen.Listener() {
                public void onText(final String n) {
                    if (n.trim().length() == 0) {
                        return;
                    }
                    shell.push(new TextInputScreen("Number:", number, TextInputScreen.NUMBER,
                        MAX_NUMBER, "Save", new TextInputScreen.Listener() {
                            public void onText(String num) {
                                Contact c = new Contact();
                                c.name = n.trim();
                                c.number = num.trim();
                                shell.info(save(c) ? "Saved" : "Could not save");
                            }
                        }));
                }
            }));
    }

    /** "Add to contact": choose an existing name, set its number. */
    void pickForNumber(final String number) {
        if (all().isEmpty()) {
            addNew(null, number);
            return;
        }
        shell.push(new NamesScreen(number));
    }

    /* ---------------- screens ---------------- */

    /** The Names list; with pickNumber != null it is a picker for that number. */
    class NamesScreen extends ListScreen {
        private final String pickNumber;

        NamesScreen(String pickNumber) {
            super(pickNumber == null ? "Names" : "Add number to");
            this.pickNumber = pickNumber;
            emptyText = "No contacts";
            rowH = 34;
        }

        void refresh() {
            items.removeAllElements();
            Vector v = all();
            for (int i = 0; i < v.size(); i++) {
                Contact c = (Contact) v.elementAt(i);
                add(new Item(c.name, c.number, c));
            }
        }

        String softLeft() {
            return current() == null ? null : (pickNumber != null ? "Select" : "Options");
        }

        String[] optionsMenu(Item it) {
            if (pickNumber != null) {
                return null;
            }
            return new String[] {"Call", "Send message", "Edit name", "Edit number", "Delete"};
        }

        void select(Item it) {
            final Contact c = (Contact) it.data;
            if (pickNumber != null) {
                c.number = pickNumber;
                save(c);
                shell.pop();
                shell.info("Saved");
                return;
            }
            shell.showPopup(Popup.info(c.name + "\n" + c.number, 0));
        }

        void option(Item it, int r) {
            final Contact c = (Contact) it.data;
            switch (r) {
            case 0:
                shell.push(new DialerScreen(c.number));
                break;
            case 1:
                shell.menus.messaging.compose(c.number, null);
                break;
            case 2:
                shell.push(new TextInputScreen("Name:", c.name, TextInputScreen.TEXT, MAX_NAME,
                    "Save", new TextInputScreen.Listener() {
                        public void onText(String t) {
                            if (t.trim().length() > 0) {
                                c.name = t.trim();
                                save(c);
                                shell.info("Saved");
                            }
                        }
                    }));
                break;
            case 3:
                shell.push(new TextInputScreen("Number:", c.number, TextInputScreen.NUMBER,
                    MAX_NUMBER, "Save", new TextInputScreen.Listener() {
                        public void onText(String t) {
                            c.number = t.trim();
                            save(c);
                            shell.info("Saved");
                        }
                    }));
                break;
            case 4:
                clear(it);
                break;
            }
        }

        void clear(Item it) {
            final Contact c = (Contact) it.data;
            shell.showPopup(Popup.confirm("Delete " + c.name + "?", new Popup.Listener() {
                public void onResult(int r) {
                    if (r == 1) {
                        delete(c);
                        refresh();
                        clamp();
                        shell.info("Deleted");
                    }
                }
            }));
        }

        /** Keypad letters jump to the first matching name (Nokia search). */
        boolean key(int k) {
            if (Keymap.isDigit(k) && k >= '2') {
                String letters = KEY_LETTERS[k - '2'];
                for (int i = 0; i < items.size(); i++) {
                    String n = ((Item) items.elementAt(i)).label.toLowerCase();
                    if (n.length() > 0 && letters.indexOf(n.charAt(0)) >= 0) {
                        selected = i;
                        repaint();
                        break;
                    }
                }
                return true;
            }
            if (k == Keymap.SEND && current() != null) {
                shell.push(new DialerScreen(((Contact) current().data).number));
                return true;
            }
            return super.key(k);
        }
    }
}
