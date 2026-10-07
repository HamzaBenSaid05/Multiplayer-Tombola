package Project_Tombola.server;

import Project_Tombola.game.Prize;
import Project_Tombola.network.Protocol;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * GameSession
 *
 * <p>Represents a single game session (lobby + running game) managed by the hub.
 * A {@code GameSession} collects {@link ClientHandler} players, waits until the expected
 * number of players join, then runs the drawing loop which:
 * <ol>
 *   <li>draws numbers at a configured interval,</li>
 *   <li>notifies clients of drawn numbers,</li>
 *   <li>checks and awards prizes (AMBO, TERNA, QUATERNA, CINQUINA, TOMBOLA) to the first
 *       players that achieve them, and</li>
 *   <li>ends the session on TOMBOLA or when all numbers are drawn.</li>
 * </ol>
 * </p>
 *
 * <p>Threading and concurrency:
 * - A session is Runnable: the server starts it on a dedicated thread when the session is ready.
 * - Player list uses {@link CopyOnWriteArrayList} to allow safe concurrent iteration while
 *   players may be added/removed by other threads.</p>
 */
public final class GameSession implements Runnable {
    /**
     * Small pause (ms) between sending a number and evaluating prizes, to let clients
     * receive NUMBER messages and update their state before prize detection.
     */
    private static final int PRIZE_DELAY_MS = 300;

    /** Unique session id assigned by the server. */
    private final int id;

    /** Human-readable session name. */
    private final String name;

    /** Expected number of players for this session (session starts when reached). */
    private final int expected;

    /** Delay (ms) between consecutive drawn numbers during the running game. */
    private final int delayMs;

    /** Reference to the central server (used for logging, session management). */
    private final TombolaServer server;

    /**
     * Thread-safe list of players currently in the session.
     * CopyOnWriteArrayList is used because iteration happens frequently while
     * modifications are relatively infrequent.
     */
    private final List<ClientHandler> players = new CopyOnWriteArrayList<>();

    /** Track which numbers (1..90) have already been drawn in this session. */
    private final boolean[] drawn = new boolean[91];

    /** Random number generator used to pick the next number to draw. */
    private final Random random = new Random();

    /**
     * Whether the session is still waiting for players (true) or the game has started (false).
     * Volatile because it may be read from different threads.
     */
    private volatile boolean waiting = true;

    /** Next numeric player id to assign when a player joins (starts from 1). */
    private int nextPlayerId = 1;

    /**
     * Create a new GameSession.
     *
     * @param id session id
     * @param name session name
     * @param expected number of expected players
     * @param delayMs milliseconds between successive draws during the game
     * @param server reference to the owning TombolaServer
     */
    public GameSession(int id, String name, int expected, int delayMs, TombolaServer server) {
        this.id = id;
        this.name = name;
        this.expected = expected;
        this.delayMs = delayMs;
        this.server = server;
    }

    /** Return the session id. */
    public int getId() { return id; }

    /** Return the session name. */
    public String getName() { return name; }

    /** Return the expected number of players for this session. */
    public int getExpected() { return expected; }

    /** Return how many players are currently joined. */
    public int getJoined() { return players.size(); }

    /** Whether the session is waiting for players (not started). */
    public boolean isWaiting() { return waiting; }

    /**
     * Add a player to this session.
     *
     * <p>This method is synchronized to ensure that joins are atomic relative to
     * the waiting/players.size checks. It assigns a numeric player id, ensures a
     * unique display name for the session, informs the player with a WELCOME and BOARD
     * packet, and broadcasts the updated players list. If the addition reaches the
     * expected count, the session transitions from waiting to running and the server
     * is asked to start the game (server.startGame(this)).</p>
     *
     * @param handler ClientHandler representing the connecting player
     * @param requestedName player-provided desired name (may be sanitized/modified)
     * @return true if the join succeeded, false if the session is full or already started
     */
    public synchronized boolean join(ClientHandler handler, String requestedName) {
        if (!waiting || players.size() >= expected) return false;

        int pid = nextPlayerId++;
        String unique = uniqueName(requestedName, pid);
        handler.enterSession(this, pid, unique);
        players.add(handler);

        // Notify the newly joined player and broadcast updated players info
        handler.send(Protocol.line(Protocol.WELCOME, String.valueOf(pid), unique));
        handler.sendBoard();
        broadcast(Protocol.line(Protocol.PLAYERS, String.valueOf(expected), playerNames()));
        server.log("[" + name + "] " + unique + " has joined (" + players.size() + "/" + expected + ").");

        // If we reached expected players, start the game
        if (players.size() == expected) {
            waiting = false;
            server.startGame(this);
        }
        return true;
    }

    /**
     * Remove a player from the session.
     *
     * <p>If the session is still waiting for players this method will either remove the
     * session entirely (if it became empty) or broadcast the updated players list.</p>
     *
     * @param handler player to remove
     */
    public synchronized void remove(ClientHandler handler) {
        if (!players.remove(handler)) return;
        server.log("[" + name + "] " + handler.getName() + " left the session.");
        if (waiting) {
            if (players.isEmpty()) server.removeSession(this);
            else broadcast(Protocol.line(Protocol.PLAYERS, String.valueOf(expected), playerNames()));
        }
    }

    /**
     * Run the actual game loop.
     *
     * <p>This method is executed on a dedicated thread started by the server.
     * It performs the following steps:
     * <ol>
     *   <li>broadcast START</li>
     *   <li>for each turn until 90 numbers or early termination:
     *     <ol>
     *       <li>draw a new number (unique),</li>
     *       <li>mark the number on every player's board and send NUMBER messages,</li>
     *       <li>wait briefly (PRIZE_DELAY_MS) and then compute any newly achieved prizes,</li>
     *       <li>award prizes to the first player(s) who reached them and broadcast WINNER,</li>
     *       <li>if TOMBOLA awarded, broadcast GAME_OVER and terminate the loop,</li>
     *       <li>otherwise sleep for the configured per-turn delay and continue.</li>
     *     </ol>
     *   </li>
     *   <li>on termination detach all players and remove the session from the server.</li>
     * </ol>
     * </p>
     */
    @Override
    public void run() {
        try {
            broadcast(Protocol.START);
            server.log("[" + name + "] Game started.");
            // Keep track of prizes already awarded so the same prize is not awarded twice
            Set<Prize> awarded = EnumSet.noneOf(Prize.class);

            for (int turn = 1; turn <= 90 && !players.isEmpty(); turn++) {
                int number = drawNumber();
                if (number == -1) break;

                server.log("[" + name + "] Turn " + turn + " -> " + number);
                // Mark number on each player's board and notify them
                for (ClientHandler p : players) {
                    p.getBoard().mark(number);
                    p.send(Protocol.line(Protocol.NUMBER, String.valueOf(number), String.valueOf(turn)));
                }

                // Small pause to allow clients to process NUMBER and update internal board state
                Thread.sleep(PRIZE_DELAY_MS);

                // Detect newly achieved prizes this turn (only unawarded prizes)
                // Map prize -> list of players who achieved it this turn
                Map<Prize, List<ClientHandler>> won = new EnumMap<>(Prize.class);
                for (ClientHandler p : players) {
                    Prize before = p.getPrize();
                    Prize now = p.checkPrize();
                    if (now != before && now != Prize.NONE && !awarded.contains(now)) {
                        won.computeIfAbsent(now, k -> new ArrayList<>()).add(p);
                    }
                }

                boolean gameOver = false;
                // Award prizes: notify winners individually and broadcast the WINNER message
                for (Map.Entry<Prize, List<ClientHandler>> entry : won.entrySet()) {
                    Prize prize = entry.getKey();
                    List<ClientHandler> winners = entry.getValue();
                    awarded.add(prize);

                    String ids = winners.stream().map(w -> String.valueOf(w.getPlayerId()))
                            .collect(Collectors.joining(","));
                    String names = winners.stream().map(ClientHandler::getName)
                            .collect(Collectors.joining(","));

                    // Notify each winner privately
                    for (ClientHandler w : winners) w.send(Protocol.line(Protocol.PRIZE, prize.name()));
                    // Notify all players of the winner(s) for this prize
                    broadcast(Protocol.line(Protocol.WINNER, ids, names, prize.name()));
                    server.log("[" + name + "] " + prize.getDisplayName().toUpperCase() + ": " + names);
                    if (prize == Prize.TOMBOLA) gameOver = true;
                }

                // If tombola was awarded, end the game
                if (gameOver) {
                    broadcast(Protocol.GAME_OVER);
                    Thread.sleep(500); // brief pause before cleanup
                    break;
                }
                // Wait the configured inter-draw delay before next turn
                Thread.sleep(delayMs);
            }
        } catch (InterruptedException ignored) {
            // Thread interruption is used for shutdown; fall through to cleanup.
        } finally {
            // Detach players (they remain connected to the hub but are no longer in a session)
            for (ClientHandler p : players) p.detach();
            players.clear();
            server.removeSession(this);
            server.log("[" + name + "] Session ended.");
        }
    }

    /**
     * Draw a random number that has not yet been drawn in this session.
     *
     * @return drawn number in 1..90 or -1 if no numbers remain
     */
    private int drawNumber() {
        List<Integer> left = new ArrayList<>();
        for (int i = 1; i <= 90; i++) if (!drawn[i]) left.add(i);
        if (left.isEmpty()) return -1;
        int n = left.get(random.nextInt(left.size()));
        drawn[n] = true;
        return n;
    }

    /**
     * Generate a unique player display name based on the requested name.
     *
     * - Sanitizes the requested name using {@link ClientHandler#clean(String, String)} with
     *   a fallback name ("Giocatore " + pid).
     * - Truncates the base to 20 characters.
     * - If the base name is already taken by another player, appends a numeric suffix
     *   "(2)", "(3)" ... until uniqueness is achieved (case-insensitive).
     *
     * @param requested requested player name
     * @param pid numeric player id used to build fallback when name is empty
     * @return guaranteed-unique display name for this session
     */
    private String uniqueName(String requested, int pid) {
        String base = ClientHandler.clean(requested, "Giocatore " + pid);
        if (base.length() > 20) base = base.substring(0, 20);
        String candidate = base;
        int suffix = 2;
        while (nameTaken(candidate)) candidate = base + " (" + suffix++ + ")";
        return candidate;
    }

    /**
     * Check if the given candidate name is already taken by a player in this session.
     * Comparison is case-insensitive.
     *
     * @param candidate candidate name
     * @return true if another player uses the same name, false otherwise
     */
    private boolean nameTaken(String candidate) {
        for (ClientHandler p : players) if (p.getName().equalsIgnoreCase(candidate)) return true;
        return false;
    }

    /**
     * Produce a comma-separated list of player names currently in the session.
     *
     * @return comma-separated player names (empty string if no players)
     */
    private String playerNames() {
        return players.stream().map(ClientHandler::getName).collect(Collectors.joining(","));
    }

    /**
     * Broadcast a protocol message to all players currently in the session.
     *
     * @param message a single-line protocol message
     */
    private void broadcast(String message) {
        for (ClientHandler p : players) p.send(message);
    }
}