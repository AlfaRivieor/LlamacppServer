package org.mark.llamacpp.win;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.PointerInfo;
import java.awt.Rectangle;
import java.awt.SystemTray;
import java.awt.Toolkit;
import java.awt.TrayIcon;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.awt.geom.AffineTransform;
import java.io.IOException;
import java.net.URL;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.imageio.ImageIO;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

public class WindowsTray {

    private static final WindowsTray INSTANCE = new WindowsTray();

    public static WindowsTray getInstance() {
        return INSTANCE;
    }

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final Map<String, JMenuItem> menuItems = new ConcurrentHashMap<>();

    private volatile TrayIcon trayIcon;
    private volatile JWindow popupHost;
    private volatile JPopupMenu popupMenu;
    private volatile Runnable defaultAction;

    private WindowsTray() {
    }

    public boolean isSupported() {
        return SystemTray.isSupported();
    }

    public boolean isStarted() {
        return started.get();
    }

    public void start(String tooltip) throws AWTException {
        if (started.get()) {
            return;
        }
        if (!SystemTray.isSupported()) {
            throw new IllegalStateException("System tray is not supported");
        }
        runOnEdt(() -> startInternal(tooltip));
    }

    public void stop() {
        runOnEdt(this::stopInternal);
    }

    public void setDefaultAction(Runnable action) {
        this.defaultAction = action;
    }

    public String addButton(String text, Runnable onClick) {
        String id = UUID.randomUUID().toString();
        addButton(id, text, onClick);
        return id;
    }

    public void addButton(String id, String text, Runnable onClick) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(onClick, "onClick");
        runOnEdt(() -> addButtonInternal(id, text, onClick));
    }

    public void removeButton(String id) {
        Objects.requireNonNull(id, "id");
        runOnEdt(() -> removeButtonInternal(id));
    }

    public void clearButtons() {
        runOnEdt(this::clearButtonsInternal);
    }

    public void addSeparator() {
        runOnEdt(() -> {
            ensureSwingInitialized();
            popupMenu.addSeparator();
        });
    }

    public void displayInfoMessage(String title, String message) {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(message, "message");
        TrayIcon icon = this.trayIcon;
        if (icon == null) {
            return;
        }
        icon.displayMessage(title, message, TrayIcon.MessageType.INFO);
    }

    private void addButtonInternal(String id, String text, Runnable onClick) {
        ensureSwingInitialized();
        JMenuItem item = new JMenuItem(text);
        item.addActionListener(e -> onClick.run());
        JMenuItem previous = menuItems.put(id, item);
        if (previous != null) {
            popupMenu.remove(previous);
        }
        popupMenu.add(item);
        popupMenu.revalidate();
        popupMenu.repaint();
    }

    private void removeButtonInternal(String id) {
        ensureSwingInitialized();
        JMenuItem item = menuItems.remove(id);
        if (item == null) {
            return;
        }
        popupMenu.remove(item);
        popupMenu.revalidate();
        popupMenu.repaint();
    }

    private void clearButtonsInternal() {
        ensureSwingInitialized();
        popupMenu.removeAll();
        menuItems.clear();
        popupMenu.revalidate();
        popupMenu.repaint();
    }

    private void startInternal(String tooltip) {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        ensureSwingInitialized();

        Image iconImage;
        try {
            iconImage = loadTrayImage("/icon/icon.png");
        } catch (IOException e) {
            started.set(false);
            throw new IllegalStateException("Failed to load tray icon: /icon/icon.png", e);
        }

        TrayIcon newTrayIcon = new TrayIcon(iconImage, tooltip);
        newTrayIcon.setImageAutoSize(true);
        newTrayIcon.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                handleMouseEvent(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                handleMouseEvent(e);
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && e.getClickCount() >= 2) {
                    Runnable action = defaultAction;
                    if (action != null) {
                        SwingUtilities.invokeLater(action);
                    }
                }
            }

            private void handleMouseEvent(MouseEvent e) {
                if (e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3) {
                    Point p = getPopupAnchorPoint(e);
                    showPopupAt(p.x, p.y);
                }
            }
        });

        try {
            SystemTray.getSystemTray().add(newTrayIcon);
            this.trayIcon = newTrayIcon;
        } catch (AWTException e) {
            started.set(false);
            throw new RuntimeException(e);
        }
    }

    private void stopInternal() {
        TrayIcon icon = this.trayIcon;
        if (icon != null && SystemTray.isSupported()) {
            SystemTray.getSystemTray().remove(icon);
        }
        this.trayIcon = null;
        this.started.set(false);

        JWindow host = this.popupHost;
        if (host != null) {
            host.setVisible(false);
            host.dispose();
        }
        this.popupHost = null;
        this.popupMenu = null;
        this.menuItems.clear();
    }

    private void ensureSwingInitialized() {
        if (popupMenu != null && popupHost != null) {
            return;
        }
        popupMenu = new JPopupMenu();

        JWindow host = new JWindow((Window) null);
        host.setAlwaysOnTop(true);
        host.setFocusableWindowState(true);
        host.setAutoRequestFocus(true);
        host.setType(Window.Type.POPUP);
        host.getContentPane().setLayout(new BorderLayout());
        host.setSize(1, 1);

        host.addWindowFocusListener(new WindowAdapter() {
            @Override
            public void windowLostFocus(WindowEvent e) {
                host.setVisible(false);
            }
        });

        popupMenu.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                host.setVisible(false);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
                host.setVisible(false);
            }
        });

        this.popupHost = host;
    }

    private void showPopupAt(int screenX, int screenY) {
        ensureSwingInitialized();
        JWindow host = this.popupHost;
        JPopupMenu menu = this.popupMenu;
        if (host == null || menu == null) {
            return;
        }

        Dimension menuSize = menu.getPreferredSize();
        Point location = calculatePopupLocation(screenX, screenY, menuSize);
        host.setLocation(location);
        host.setVisible(true);
        host.toFront();
        menu.show(host.getContentPane(), 0, 0);
        menu.requestFocusInWindow();
    }

    private static Point getPopupAnchorPoint(MouseEvent e) {
        int ex = e.getXOnScreen();
        int ey = e.getYOnScreen();

        PointerInfo pointerInfo = MouseInfo.getPointerInfo();
        Point mousePoint = pointerInfo == null ? null : pointerInfo.getLocation();
        if (mousePoint == null) {
            return new Point(ex, ey);
        }

        GraphicsConfiguration gc = findGraphicsConfiguration(mousePoint.x, mousePoint.y);
        AffineTransform tx = gc.getDefaultTransform();
        double sx = tx.getScaleX();
        double sy = tx.getScaleY();
        if (sx <= 0 || sy <= 0) {
            return mousePoint;
        }

        Point eventAsUser = new Point((int) Math.round(ex / sx), (int) Math.round(ey / sy));
        double dUser = mousePoint.distance(eventAsUser);
        double dRaw = mousePoint.distance(ex, ey);

        return dUser <= dRaw ? eventAsUser : new Point(ex, ey);
    }

    private static Point calculatePopupLocation(int screenX, int screenY, Dimension popupSize) {
        int width = popupSize == null ? 0 : popupSize.width;
        int height = popupSize == null ? 0 : popupSize.height;

        GraphicsConfiguration gc = findGraphicsConfiguration(screenX, screenY);
        Rectangle bounds = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);

        int usableX = bounds.x + insets.left;
        int usableY = bounds.y + insets.top;
        int usableW = bounds.width - insets.left - insets.right;
        int usableH = bounds.height - insets.top - insets.bottom;

        int x = screenX;
        int y = screenY;

        if (width > 0) {
            x = Math.min(x, usableX + usableW - width);
        }
        if (height > 0) {
            y = Math.min(y, usableY + usableH - height);
        }

        x = Math.max(x, usableX);
        y = Math.max(y, usableY);

        return new Point(x, y);
    }

    private static GraphicsConfiguration findGraphicsConfiguration(int screenX, int screenY) {
        Point p = new Point(screenX, screenY);
        GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
        for (GraphicsDevice device : ge.getScreenDevices()) {
            GraphicsConfiguration gc = device.getDefaultConfiguration();
            if (gc.getBounds().contains(p)) {
                return gc;
            }
        }
        return ge.getDefaultScreenDevice().getDefaultConfiguration();
    }

    private Image loadTrayImage(String resourcePath) throws IOException {
        URL url = WindowsTray.class.getResource(resourcePath);
        if (url == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        BufferedImage image = ImageIO.read(url);
        Dimension traySize = SystemTray.getSystemTray().getTrayIconSize();
        if (traySize.width <= 0 || traySize.height <= 0) {
            return image;
        }
        return image.getScaledInstance(traySize.width, traySize.height, Image.SCALE_SMOOTH);
    }

    private static void runOnEdt(Runnable task) {
        if (EventQueue.isDispatchThread()) {
            task.run();
            return;
        }
        try {
            EventQueue.invokeAndWait(task);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
