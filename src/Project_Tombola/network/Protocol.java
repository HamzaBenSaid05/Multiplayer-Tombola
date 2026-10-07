package Project_Tombola.network;

/**
 * Protocol
 *
 * <p>Defines the text-based protocol used between clients and the hub.
 * Messages are single-line strings where tokens are separated by the pipe
 * character '|' (vertical bar). The first token is the command name and
 * subsequent tokens are command-specific arguments.</p>
 *
 * <p>This class centralizes the command names as constants and provides
 * two utility methods:
 * <ul>
 *   <li>{@link #line(String, String...)} - build a protocol line from a command and arguments</li>
 *   <li>{@link #parse(String)} - split an incoming line into tokens</li>
 * </ul>
 * The protocol intentionally replaces any '|' characters in arguments with '/'
 * when building a line to avoid breaking tokenization.</p>
 *
 * <p>Command directions and brief formats (comments show expected token layout):
 * <ul>
 *   <li>client -> hub:
 *     <ul>
 *       <li>{@link #LIST}           - LIST</li>
 *       <li>{@link #CREATE}         - CREATE|sessionName|players|seconds|playerName</li>
 *       <li>{@link #JOIN}           - JOIN|sessionId|playerName</li>
 *       <li>{@link #LEAVE}          - LEAVE</li>
 *     </ul>
 *   </li>
 *   <li>hub -> client:
 *     <ul>
 *       <li>{@link #SESSIONS}       - SESSIONS|id,name,joined,expected;id,name,...</li>
 *       <li>{@link #WELCOME}        - WELCOME|id|name</li>
 *       <li>{@link #PLAYERS}        - PLAYERS|expected|name1,name2</li>
 *       <li>{@link #BOARD}          - BOARD|serializedBoard</li>
 *       <li>{@link #START}          - START</li>
 *       <li>{@link #NUMBER}         - NUMBER|number|turn</li>
 *       <li>{@link #PRIZE}          - PRIZE|prize (sent only to the winning player)</li>
 *       <li>{@link #WINNER}         - WINNER|id1,id2|name1,name2|prize (broadcast to all)</li>
 *       <li>{@link #GAME_OVER}      - GAME_OVER</li>
 *       <li>{@link #ERROR}          - ERROR|message</li>
 *     </ul>
 *   </li>
 * </ul>
 * </p>
 */
public final class Protocol {
    private Protocol() {}

    // client -> hub
    /** Request the list of available sessions. */
    public static final String LIST = "LIST";

    /**
     * Create a new session.
     * Format: CREATE|sessionName|players|seconds|playerName
     * Note: fields that may contain '|' are sanitized by {@link #line(String, String...)}.
     */
    public static final String CREATE = "CREATE";

    /**
     * Join an existing session.
     * Format: JOIN|sessionId|playerName
     */
    public static final String JOIN = "JOIN";

    /** Leave the current session. */
    public static final String LEAVE = "LEAVE";

    // hub -> client
    /**
     * Server response containing the sessions list.
     * Format: SESSIONS|id,name,joined,expected;id,name,...
     */
    public static final String SESSIONS = "SESSIONS";

    /** Sent to a client when it successfully joins or creates a session. Format: WELCOME|id|name */
    public static final String WELCOME = "WELCOME";     // WELCOME|id|nome

    /** Inform clients about players count/names. Format: PLAYERS|expected|name1,name2 */
    public static final String PLAYERS = "PLAYERS";

    /** Sent to deliver the serialized board to a client. Format: BOARD|serializedBoard */
    public static final String BOARD = "BOARD";

    /** Notification that the game has started. */
    public static final String START = "START";

    /** Announce a drawn number. Format: NUMBER|number|turn */
    public static final String NUMBER = "NUMBER";

    /** Notify a player that they won a prize. Format: PRIZE|prize */
    public static final String PRIZE = "PRIZE";

    /** Announce the winner(s) and prize to all clients. Format: WINNER|id1,id2|name1,name2|prize */
    public static final String WINNER = "WINNER";

    /** Notify that the game is over. */
    public static final String GAME_OVER = "GAME_OVER";

    /** Error message. Format: ERROR|text */
    public static final String ERROR = "ERROR";

    /**
     * Build a protocol line from a command and optional arguments.
     *
     * Rules:
     * - The returned string begins with the command token.
     * - Each additional argument is appended prefixed by '|' (pipe).
     * - Any '|' characters inside arguments are replaced with '/' to avoid breaking tokenization.
     *
     * Example:
     * Protocol.line(Protocol.CREATE, "MySession", "3", "5", "Alice")
     * -> "CREATE|MySession|3|5|Alice"
     *
     * @param command the protocol command token (must not be null)
     * @param args optional argument strings (null elements will produce "null")
     * @return a single-line protocol message ready to send over the wire
     */
    public static String line(String command, String... args) {
        StringBuilder sb = new StringBuilder(command);
        for (String arg : args) sb.append('|').append(arg.replace("|", "/"));
        return sb.toString();
    }

    /**
     * Parse an incoming protocol line into tokens.
     *
     * Behavior:
     * - Splits the line on '|' characters.
     * - The split limit parameter (-1) preserves trailing empty tokens.
     *
     * Example:
     * parse("NUMBER|42|3") returns ["NUMBER", "42", "3"]
     *
     * Note: arguments that originally contained '|' were previously replaced with '/'
     * by {@link #line(String, String...)}, so this parse method does not reconstruct
     * those characters.
     *
     * @param line the incoming protocol line (must not be null)
     * @return an array of tokens (command + arguments)
     */
    public static String[] parse(String line) {
        return line.split("\\|", -1);
    }
}