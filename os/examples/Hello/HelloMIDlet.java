import javax.microedition.lcdui.Canvas;
import javax.microedition.lcdui.Command;
import javax.microedition.lcdui.CommandListener;
import javax.microedition.lcdui.Display;
import javax.microedition.lcdui.Displayable;
import javax.microedition.lcdui.Font;
import javax.microedition.lcdui.Graphics;
import javax.microedition.midlet.MIDlet;

/**
 * Smoke-test MIDlet for the JioPhone port: draws the screen size, a colour
 * ramp, and the last key pressed (name + MIDP key code), so both the
 * framebuffer path and the key map can be checked on the LCD.
 */
public class HelloMIDlet extends MIDlet implements CommandListener {
    private final Command exit = new Command("Exit", Command.EXIT, 1);
    private Display display;
    private TestCanvas canvas;

    protected void startApp() {
        if (display == null) {
            display = Display.getDisplay(this);
            canvas = new TestCanvas();
            canvas.addCommand(exit);
            canvas.setCommandListener(this);
        }
        display.setCurrent(canvas);
    }

    protected void pauseApp() {
    }

    protected void destroyApp(boolean unconditional) {
    }

    public void commandAction(Command c, Displayable d) {
        if (c == exit) {
            destroyApp(true);
            notifyDestroyed();
        }
    }

    class TestCanvas extends Canvas {
        private String last = "(press a key)";
        private int presses = 0;

        protected void paint(Graphics g) {
            int w = getWidth(), h = getHeight();
            g.setColor(0xFFFFFF);
            g.fillRect(0, 0, w, h);
            // colour ramp: tells red/blue swaps and bit depth apart at a glance
            for (int i = 0; i < w; i++) {
                int r = 255 * i / w;
                g.setColor(r, 0, 255 - r);
                g.drawLine(i, 0, i, 24);
            }
            g.setColor(0x00A000);
            g.fillRect(0, 24, w, 8);
            g.setColor(0x000000);
            g.setFont(Font.getFont(Font.FACE_PROPORTIONAL, Font.STYLE_BOLD, Font.SIZE_MEDIUM));
            g.drawString("phoneME on JioPhone", w / 2, 40, Graphics.TOP | Graphics.HCENTER);
            g.setFont(Font.getDefaultFont());
            g.drawString("canvas " + w + "x" + h, 4, 64, Graphics.TOP | Graphics.LEFT);
            g.drawString("free " + Runtime.getRuntime().freeMemory() / 1024 + "K of "
                    + Runtime.getRuntime().totalMemory() / 1024 + "K", 4, 80, Graphics.TOP | Graphics.LEFT);
            g.drawString("last key:", 4, 110, Graphics.TOP | Graphics.LEFT);
            g.setColor(0xB00000);
            g.drawString(last, 4, 126, Graphics.TOP | Graphics.LEFT);
            g.setColor(0x000000);
            g.drawString("presses: " + presses, 4, 150, Graphics.TOP | Graphics.LEFT);
            g.drawString("Exit = right soft key", 4, h - 20, Graphics.TOP | Graphics.LEFT);
        }

        protected void keyPressed(int code) {
            presses++;
            String name;
            try {
                name = getKeyName(code);
            } catch (IllegalArgumentException e) {
                name = "?";
            }
            int action = -1;
            try {
                action = getGameAction(code);
            } catch (IllegalArgumentException e) {
                // not a game key
            }
            last = name + " code=" + code + " action=" + action;
            repaint();
        }
    }
}
