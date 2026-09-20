/*
 * S100 shell - the number entry that opens when a digit is pressed on
 * the idle screen. There is no telephony backend yet (rild is KaiOS's),
 * so "Call" logs the number as dialled and says so; Save and Send
 * message hand the number to Contacts / Messaging.
 */

package com.sun.midp.appmanager;

import javax.microedition.lcdui.Graphics;

class DialerScreen extends Screen {
    private final StringBuffer number = new StringBuffer();

    DialerScreen(String initial) {
        super(null);
        if (initial != null) {
            number.append(initial);
        }
    }

    boolean overlayStatus() { return true; }
    String softLeft() { return "Options"; }
    String softMid() { return "Call"; }
    String softRight() { return "Clear"; }

    String number() {
        return number.toString();
    }

    void paint(Graphics g, int x, int y, int w, int h) {
        g.drawImage(shell.home().wallpaperImage(w, h), x, y, Graphics.TOP | Graphics.LEFT);
        int boxY = y + 70;
        g.setColor(Theme.C_BG);
        g.fillRoundRect(x + 8, boxY, w - 16, 44, 8, 8);
        g.setColor(Theme.C_FRAME);
        g.drawRoundRect(x + 8, boxY, w - 17, 43, 8, 8);
        g.setColor(Theme.C_TEXT);
        String s = number.toString();
        int maxChars = (w - 32) / Theme.CH_W;
        if (s.length() > maxChars) {
            s = s.substring(s.length() - maxChars);
        }
        Theme.bold(g, s, x + w - 16, boxY + (44 - Theme.FONT_H) / 2, Graphics.TOP | Graphics.RIGHT);
        // cursor
        int cx = x + w - 15;
        g.fillRect(cx, boxY + 14, 2, Theme.FONT_H + 2);
        Contacts.Contact c = shell.menus.contacts.findByNumber(number.toString());
        if (c != null) {
            Theme.shadowText(g, c.name, x + w / 2, boxY + 52, Graphics.TOP | Graphics.HCENTER,
                             Theme.C_HOME_TEXT, Theme.C_HOME_SHADOW);
        }
    }

    private void call() {
        String n = number.toString();
        if (n.length() == 0) {
            return;
        }
        shell.menus.log.add(CallLog.DIALLED, n);
        shell.showPopup(Popup.info("Calling " + n + "\u2026\n\nNo telephony on this build:\n"
                                   + "the call cannot be placed.", 0));
    }

    boolean key(int k) {
        switch (k) {
        case Keymap.SEND:
        case Keymap.SELECT:
            call();
            return true;
        case Keymap.SOFT_R:
        case Keymap.CLEAR:
            if (number.length() > 0) {
                number.setLength(number.length() - 1);
            }
            if (number.length() == 0) {
                shell.pop();
            } else {
                repaint();
            }
            return true;
        case Keymap.SOFT_L:
            options();
            return true;
        default:
            if (Keymap.isDialKey(k) && number.length() < 40) {
                number.append((char) k);
                repaint();
                return true;
            }
            return false;
        }
    }

    void keyLong(int k) {
        if ((k == '0' || k == Keymap.STAR) && number.length() > 0) {
            number.setCharAt(number.length() - 1, '+');
            repaint();
        } else if (k == Keymap.SOFT_R || k == Keymap.CLEAR) {
            shell.pop();
        }
    }

    private void options() {
        final String n = number.toString();
        String[] opts = {"Call", "Save", "Send message", "Add to contact"};
        shell.showPopup(Popup.menu("Options", opts, new Popup.Listener() {
            public void onResult(int r) {
                switch (r) {
                case 0:
                    call();
                    break;
                case 1:
                    shell.menus.contacts.addNew(null, n);
                    break;
                case 2:
                    shell.menus.messaging.compose(n, null);
                    break;
                case 3:
                    shell.menus.contacts.pickForNumber(n);
                    break;
                }
            }
        }));
    }
}
