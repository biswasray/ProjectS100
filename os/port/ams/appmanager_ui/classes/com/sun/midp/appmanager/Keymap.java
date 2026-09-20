/*
 * S100 shell - the keypad from the UI's point of view.
 *
 * Two layers of key mapping exist on the phone:
 *
 *   evdev code  --(keymap.txt / fb_keymapping.c)-->  MIDP key code
 *   MIDP code   --(this class)-->                    logical shell key
 *
 * The MIDP codes the linux_fb port produces are fixed (keymap_input.h):
 * -1..-5 navigation, -6/-7 soft keys, -8 clear, -10 send, -11 end,
 * -12 power and the ASCII digits / '*' / '#'. This class normalises them
 * (power == end, backspace == clear), names them, and owns the idle-screen
 * shortcuts (Settings > My shortcuts) which map the navigation keys to
 * menu items on the home screen.
 */

package com.sun.midp.appmanager;

final class Keymap {
    static final int NONE = 0;
    static final int UP = -1;
    static final int DOWN = -2;
    static final int LEFT = -3;
    static final int RIGHT = -4;
    static final int SELECT = -5;
    static final int SOFT_L = -6;
    static final int SOFT_R = -7;
    static final int CLEAR = -8;
    static final int SEND = -10;
    static final int END = -11;
    static final int STAR = '*';
    static final int POUND = '#';

    /** Long press threshold in ms (Nokia: ~0.8 s). */
    static final int LONG_PRESS_MS = 700;

    /** Idle shortcut slots, in the order they are offered in Settings. */
    static final int[] SHORTCUT_KEYS = {UP, DOWN, LEFT, RIGHT};
    static final String[] SHORTCUT_NAMES = {"Up key", "Down key", "Left key", "Right key"};
    /** Default assignment, menu item ids from MenuManager. */
    static final String[] SHORTCUT_DEFAULTS = {
        "log.dialled", "contacts.names", "messaging.compose", "organiser.calculator"
    };

    private Keymap() { }

    /** MIDP key code -> logical key; NONE for anything the shell ignores. */
    static int map(int code) {
        switch (code) {
        case UP: case DOWN: case LEFT: case RIGHT: case SELECT:
        case SOFT_L: case SOFT_R: case CLEAR: case SEND: case END:
        case STAR: case POUND:
            return code;
        case -12:                 // KEYMAP_KEY_POWER
            return END;
        case 8:                   // KEYMAP_KEY_BACKSPACE
            return CLEAR;
        case 10: case 13:         // enter on a keyboard (emulator)
            return SELECT;
        default:
            if (code >= '0' && code <= '9') {
                return code;
            }
            return NONE;
        }
    }

    static boolean isDigit(int key) {
        return key >= '0' && key <= '9';
    }

    /** True for keys that type into the dialer from the idle screen. */
    static boolean isDialKey(int key) {
        return isDigit(key) || key == STAR || key == POUND;
    }

    static String name(int key) {
        switch (key) {
        case UP: return "Up";
        case DOWN: return "Down";
        case LEFT: return "Left";
        case RIGHT: return "Right";
        case SELECT: return "Select";
        case SOFT_L: return "Left soft key";
        case SOFT_R: return "Right soft key";
        case CLEAR: return "Clear";
        case SEND: return "Call";
        case END: return "End";
        case NONE: return "-";
        default: return String.valueOf((char) key);
        }
    }

    /** Menu item id bound to an idle-screen navigation key. */
    static String shortcut(int key) {
        for (int i = 0; i < SHORTCUT_KEYS.length; i++) {
            if (SHORTCUT_KEYS[i] == key) {
                return Prefs.get("shortcut." + i, SHORTCUT_DEFAULTS[i]);
            }
        }
        return null;
    }

    static void setShortcut(int slot, String menuId) {
        Prefs.set("shortcut." + slot, menuId);
    }
}
