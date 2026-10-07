
package Project_Tombola.server;

import Project_Tombola.network.Discovery;
import Project_Tombola.network.HubConfig;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/** * TombolaServer * * <p> * Central hub/server for the multiplayer Tombola game. The server: * <ul> *   <li>accepts TCP client connections on the configured hub port,</li> *   <li>maintains a registry of waiting and running game sessions,</li> *   <li>provides a small optional GUI for logging and status display (can run headless),</li> *   <li>broadcasts periodic UDP discovery packets so local clients can auto-discover the hub.</li> * </ul> * </p> * * <p>Configuration: * - The hub TCP port and other network values are read from {@link HubConfig}. * - Discovery uses {@link Discovery} utilities to compute broadcast targets and the discovery port. * </p> * * <p>Concurrency: * - Uses an {@link ExecutorService} (cached thread pool) to run client handlers and game sessions. * - Session registry is stored in a thread-safe {@link ConcurrentHashMap}. * - Connected clients are tracked in a concurrent {@link java.util.Set} created by {@link ConcurrentHashMap#newKeySet()}. * </p> */
public final class TombolaServer {
    /** Optional text area used by the GUI to display log messages. May be null in headless mode. */
    private JTextArea logArea;

    /** Optional status label used by the GUI to display connected/active session counts. */
    private JLabel statusLabel;

    /** Thread pool used to run client handlers and game sessions. */
    private final ExecutorService pool = Executors.newCachedThreadPool();

    /** Map of active sessions by session id. Thread-safe for concurrent access. */
    private final Map<Integer, GameSession> sessions = new ConcurrentHashMap<>();

    /** Set of currently connected client handlers. Thread-safe. */
    private final Set<ClientHandler> connected = ConcurrentHashMap.newKeySet();

    /** Generator for session ids. */
    private final AtomicInteger nextSessionId = new AtomicInteger(1);

    /**     * Build a simple Swing GUI for the server.     *     * This GUI is optional; the server can run headless (for example on a VPS).     * When available it shows a status line and a scrolling log area.     */
    private void buildGui() {
        JFrame frame = new JFrame("Tombola - Server");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(720, 480);
        frame.setLocationRelativeTo(null);

        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 14));

        statusLabel = new JLabel(" Started...");
        statusLabel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

        frame.add(statusLabel, BorderLayout.NORTH);
        frame.add(new JScrollPane(logArea), BorderLayout.CENTER);
        frame.setVisible(true);
    }

    /**     * Log a message to both stdout and the optional GUI log area.     *     * The method is safe to call from any thread; GUI updates are scheduled on     * the Swing Event Dispatch Thread via {@link SwingUtilities#invokeLater(Runnable)}.     *     * @param text message to log     */
    public void log(String text) {
        System.out.println(text);
        JTextArea area = logArea;
        if (area != null) SwingUtilities.invokeLater(() -> area.append(text + "\n"));
    }

    /**     * Refresh the GUI status label showing connected players and active sessions.     *     * When the GUI is not present the method is a no-op (statusLabel will be null).     */
    public void updateStatus() {
        String text = " Players: " + connected.size() + "   |   Active Sessions: " + sessions.size();
        JLabel label = statusLabel;
        if (label != null) SwingUtilities.invokeLater(() -> label.setText(text));
    }

    // --- session registry management ---

    /**     * Create and register a new {@link GameSession}.     *     * The session id is generated atomically. The created session is stored in     * the sessions map and the server status is updated.     *     * @param name human-readable session name     * @param expected number of expected players that will start the session     * @param delaySeconds seconds between extractions (per-turn delay)     * @return the created GameSession instance     */
    public GameSession createSession(String name, int expected, int delaySeconds) {
        GameSession s = new GameSession(nextSessionId.getAndIncrement(), name, expected, delaySeconds * 1000, this);
        sessions.put(s.getId(), s);
        log("New session \"" + name + "\" (" + expected + " players, " + delaySeconds + "s/draw).");
        updateStatus();
        return s;
    }

    /**     * Find a session by id.     *     * @param id session id     * @return the GameSession or null if not found     */
    public GameSession findSession(int id) { return sessions.get(id); }

    /**     * Remove a session from the registry.     *     * If the session existed and was removed the status label is updated.     *     * @param s session to remove     */
    public void removeSession(GameSession s) {
        if (sessions.remove(s.getId()) != null) updateStatus();
    }

    /**     * Ask the internal thread pool to start the provided session.     *     * The session implements {@link Runnable} and will be submitted to the pool.     *     * @param s session to start     */
    public void startGame(GameSession s) { pool.submit(s); }

    /**     * Produce a compact representation of waiting sessions for the lobby.     *     * The format matches the client/hub protocol: id,name,joined,expected entries separated by ';'.     * Only sessions that are still waiting for players are included.     *     * @return semicolon-separated session list string     */
    public String sessionList() {
        return sessions.values().stream()
                .filter(GameSession::isWaiting)
                .sorted(Comparator.comparingInt(GameSession::getId))
                .map(s -> s.getId() + "," + s.getName() + "," + s.getJoined() + "," + s.getExpected())
                .collect(Collectors.joining(";"));
    }

    /**     * Notify the server that a client handler has gone (disconnected).     *     * The handler is removed from the connected set and the GUI status is updated.     *     * @param handler the client handler that disconnected     */
    public void clientGone(ClientHandler handler) {
        connected.remove(handler);
        updateStatus();
    }

    // --- networking: TCP listener and UDP announcer ---

    /**     * Start the server: open the TCP ServerSocket, start the UDP announcer     * and accept incoming client connections in a loop.     *     * @throws IOException on socket errors (e.g. port already in use)     */
    private void start() throws IOException {
        // Create a listening ServerSocket on the configured hub port.
        ServerSocket serverSocket = new ServerSocket(HubConfig.PORT);
        log("Server started on port " + HubConfig.PORT + ".");
        log("Local clients can find the hub automatically; remote clients use the address in HubConfig.");
        startAnnouncer();
        updateStatus();

        // Accept loop: for each accepted socket build a ClientHandler and submit it to the pool.
        while (true) {
            Socket socket = serverSocket.accept();
            try {
                ClientHandler handler = new ClientHandler(socket, this);
                connected.add(handler);
                updateStatus();
                pool.submit(handler);
            } catch (IOException e) {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }
    }

    /**     * Start a background daemon thread that periodically sends UDP discovery     * packets so local clients can auto-discover the hub.     *     * Behavior:     * - Creates a DatagramSocket (broadcast enabled),     * - Encodes the hub announcement payload using {@link Discovery#encodeHub(int)},     * - Sends the datagram to all addresses returned by {@link Discovery#targets()} and     *   to the discovery port {@link Discovery#PORT},     * - Repeats once per second.     *     * Network exceptions are swallowed because discovery is best-effort.     */
    private void startAnnouncer() {
        Thread t = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket()) {
                ds.setBroadcast(true);
                byte[] data = Discovery.encodeHub(HubConfig.PORT);
                while (true) {
                    for (InetAddress target : Discovery.targets()) {
                        try {
                            ds.send(new DatagramPacket(data, data.length, target, Discovery.PORT));
                        } catch (IOException ignored) {}
                    }
                    Thread.sleep(1000);
                }
            } catch (Exception ignored) {}
        }, "TombolaAnnouncer");
        t.setDaemon(true);
        t.start();
    }

    /**     * Application entry point.     *     * If a graphical environment is available the server GUI is built on the EDT.     * Then the server start sequence is executed.     *     * Any exception during startup is logged and (when GUI is available) shown in a dialog.     *     * @param args command-line arguments (ignored)     */
    public static void main(String[] args) {
        TombolaServer server = new TombolaServer();
        try {
            if (!GraphicsEnvironment.isHeadless()) SwingUtilities.invokeAndWait(server::buildGui);
            server.start();
        } catch (Exception e) {
            server.log("Server error: " + e.getMessage());
            if (!GraphicsEnvironment.isHeadless()) {
                JOptionPane.showMessageDialog(null,
                        "Unable to start the server (port " + HubConfig.PORT + " already in use?)\n" + e.getMessage(),
                        "Tombola - Server", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}