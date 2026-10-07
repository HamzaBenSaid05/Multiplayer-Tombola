
package Project_Tombola.server;

import Project_Tombola.game.*;
import Project_Tombola.network.Protocol;

import java.io.*;
import java.net.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** * ClientHandler * * <p>Handles a single client TCP connection to the hub (server). This class: * <ul> *   <li>reads incoming protocol lines from the client (LIST, CREATE, JOIN, LEAVE),</li> *   <li>executes the requested operations against the {@link TombolaServer},</li> *   <li>sends protocol responses back to the client, and</li> *   <li>maintains per-client state such as current session, assigned board and prizes.</li> * </ul> * </p> * * <p>Instances are Runnable and are intended to run on a dedicated thread per client. * They are Closeable so the server can cleanly shut down the connection. Field access * that may be used by multiple threads is declared volatile or otherwise synchronized * where appropriate (for example, {@link #send(String)} is synchronized).</p> */
public final class ClientHandler implements Runnable, Closeable {
    /** Underlying socket connected to the client. */
    private final Socket socket;

    /** Reader for incoming text lines from the client. */
    private final BufferedReader in;

    /** Writer to send text lines to the client. */
    private final PrintWriter out;

    /** Reference to the central server (hub) to perform operations like create/find sessions. */
    private final TombolaServer server;

    /** Atomic flag that tracks whether the connection is still considered active. */
    private final AtomicBoolean connected = new AtomicBoolean(true);

    // Per-client state (modified by the server/session threads and this handler)
    /** The game session this client is currently attached to, or null if not in a session. */
    private volatile GameSession session;

    /** The display name chosen by the player. */
    private volatile String name = "";

    /** Numeric player id assigned by the session when the player enters. */
    private volatile int playerId;

    /** The player's current tombola board. */
    private volatile TombolaBoard board = new TombolaBoard();

    /** The highest prize the player has achieved so far (NONE by default). */
    private volatile Prize prize = Prize.NONE;

    /**     * Construct a new ClientHandler for the provided socket and server instance.     *     * @param socket accepted socket for the client connection     * @param server reference to the {@link TombolaServer} (hub)     * @throws IOException if creating the input or output stream wrappers fails     */
    public ClientHandler(Socket socket, TombolaServer server) throws IOException {
        this.socket = socket;
        this.server = server;
        this.in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        this.out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(socket.getOutputStream())), true);
    }

    /** Return the numeric player id assigned by the session. */
    public int getPlayerId() { return playerId; }

    /** Return the player's display name. */
    public String getName() { return name; }

    /** Return the player's current {@link TombolaBoard}. */
    public TombolaBoard getBoard() { return board; }

    /** Return the player's current highest {@link Prize}. */
    public Prize getPrize() { return prize; }

    /**     * Called by a {@link GameSession} when the player enters the session.     *     * This method initializes per-player session state: session reference, id,     * player name and resets the board and prize.     *     * @param s the session the player is entering     * @param id numeric id assigned to this player inside the session     * @param playerName the display name chosen by the player     */
    void enterSession(GameSession s, int id, String playerName) {
        this.session = s;
        this.playerId = id;
        this.name = playerName;
        this.board = new TombolaBoard();
        this.prize = Prize.NONE;
    }

    /**     * Called by the session when the player is detached (session ended or player removed).     * After calling this the handler is no longer associated with a game session.     */
    void detach() { this.session = null; }

    /**     * Leave the current session (if any) and notify the session to remove this player.     *     * This method is package-private and used both by protocol handling (LEAVE) and by     * cleanup code when the connection closes.     */
    private void leaveSession() {
        GameSession s = session;
        session = null;
        if (s != null) s.remove(this);
    }

    /**     * Send a protocol message to the client.     *     * This method is synchronized to ensure that writes coming from multiple threads     * (for example session logic broadcasting messages and the handler sending errors)     * do not interleave on the PrintWriter.     *     * @param message a single-line protocol message (constructed via {@link Protocol#line})     */
    public synchronized void send(String message) {
        if (connected.get()) out.println(message);
    }

    /**     * Send the serialized board to this client using the {@link Protocol#BOARD} message.     *     * The board is serialized with {@link TombolaBoard#serialize()} and sent as a single argument.     */
    public void sendBoard() {
        send(Protocol.line(Protocol.BOARD, board.serialize()));
    }

    /**     * Check whether the player's board reached a new prize threshold.     *     * The method inspects the current board via {@link TombolaBoard#getNextPrize()} and,     * if a higher prize is detected compared to the previously stored {@code prize},     * updates the stored value.     *     * @return the updated prize (may be equal to the previous value if no new prize)     */
    public Prize checkPrize() {
        Prize detected = board.getNextPrize();
        if (detected.ordinal() > prize.ordinal()) prize = detected;
        return prize;
    }

    /**     * Main loop executed by the handler thread.     *     * The loop:     * <ol>     *   <li>reads lines from the socket reader,</li>     *   <li>parses each line using {@link Protocol#parse(String)},</li>     *   <li>dispatches handling to {@link #handle(String[])}, and</li>     *   <li>sends an ERROR response on malformed commands.</li>     * </ol>     *     * The method terminates when the client closes the connection (read returns null)     * or an IOException occurs. Finally, it leaves any session, closes resources and     * notifies the server that the client is gone.     */
    @Override
    public void run() {
        try {
            String line;
            while ((line = in.readLine()) != null) {
                try {
                    handle(Protocol.parse(line));
                } catch (RuntimeException e) {
                    // If a command is invalid, inform the client but keep the connection alive.
                    send(Protocol.line(Protocol.ERROR, "Command not valid"));
                }
            }
        } catch (IOException ignored) {
            // IO errors read or socket closed: fall-through to cleanup.
        } finally {
            leaveSession();
            close();
            server.clientGone(this);
        }
    }

    /**     * Handle a parsed protocol message.     *     * Supported commands:     * - LIST   : responds with SESSIONS and the current server session list     * - CREATE : create a new session and try to join it     * - JOIN   : join an existing session by id     * - LEAVE  : leave the current session     *     * The method validates state (for example, prevents creating or joining when     * already in a session) and sanitizes inputs using {@link #clean(String, String)}.     *     * @param msg array of tokens where msg[0] is the command and following entries are arguments     */
    private void handle(String[] msg) {
        switch (msg[0]) {
            case Protocol.LIST -> send(Protocol.line(Protocol.SESSIONS, server.sessionList()));

            case Protocol.CREATE -> {
                // Prevent creating a session when already in one
                if (session != null) { send(Protocol.line(Protocol.ERROR, "You are already in a session")); return; }
                String sessionName = clean(msg[1], "Tombola");
                int expected = clamp(Integer.parseInt(msg[2]), 1, 20);
                int delay = clamp(Integer.parseInt(msg[3]), 1, 15);
                GameSession s = server.createSession(sessionName, expected, delay);
                if (!s.join(this, msg[4])) {
                    // If join failed immediately after creation, remove the session and notify client
                    server.removeSession(s);
                    send(Protocol.line(Protocol.ERROR, "Impossible to create the session"));
                }
            }

            case Protocol.JOIN -> {
                // Prevent joining when already in a session
                if (session != null) { send(Protocol.line(Protocol.ERROR, "You are already in a session")); return; }
                GameSession s = server.findSession(Integer.parseInt(msg[1]));
                if (s == null || !s.join(this, msg[2])) {
                    send(Protocol.line(Protocol.ERROR, "Session not available"));
                }
            }

            case Protocol.LEAVE -> leaveSession();
            default -> {}
        }
    }

    /**     * Sanitize a text parameter received from the client.     *     * Removes characters that are used as protocol separators ('|', ',' and ';'),     * trims whitespace and returns a non-empty fallback if the resulting string is empty.     *     * @param text raw input text     * @param fallback value to use when the sanitized text is empty     * @return sanitized text or fallback when empty     */
    static String clean(String text, String fallback) {
        String t = text.replaceAll("[|,;]", "").trim();
        return t.isEmpty() ? fallback : t;
    }

    /**     * Clamp an integer value to a safety interval.     *     * Used to ensure requested numeric parameters (players, delay, etc.) remain in expected bounds.     *     * @param v value to clamp     * @param min minimum allowed value (inclusive)     * @param max maximum allowed value (inclusive)     * @return clamped value within [min, max]     */
    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    /**     * Close the client connection and mark the handler as disconnected.     *     * This method is idempotent and will swallow IOExceptions coming from socket.close().     */
    @Override
    public void close() {
        connected.set(false);
        try { socket.close(); } catch (IOException ignored) {}
    }
}