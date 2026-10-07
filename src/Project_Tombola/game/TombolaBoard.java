
package Project_Tombola.game;

import java.util.*;

/** * TombolaBoard * * <p>Represents a single player's tombola card (3 rows × 5 columns). * The board stores the numbers assigned to each cell and tracks which * numbers have been marked (i.e. have been drawn).</p> * * <p>Responsibilities: * <ul> *   <li>Create a randomized board (default constructor) or build one from a 2D int array</li> *   <li>Mark numbers when they are drawn</li> *   <li>Query counts of marked numbers and compute the next prize state</li> *   <li>Serialize / parse the board to/from a whitespace-separated string</li> * </ul> * </p> * * <p>Thread-safety: marking is guarded by {@code synchronized} on {@link #mark(int)} to * avoid races when multiple threads attempt to mark the board concurrently. All other * operations are read-only or operate on immutable primitives/arrays and are safe to * call from multiple threads provided external synchronization if necessary.</p> */
public final class TombolaBoard {
    /** Number of rows on a tombola card (3). */
    public static final int ROWS = 3;

    /** Number of columns on a tombola card (5). */
    public static final int COLS = 5;

    /** Total number of cells on a card (ROWS * COLS = 15). */
    public static final int SIZE = ROWS * COLS;

    /**     * 2D array containing the numbers placed on the board.     * Indexing: numbers[row][col].     */
    private final int[][] numbers = new int[ROWS][COLS];

    /**     * 2D boolean array tracking which cells have been marked.     * A value of true indicates the number at the same coordinates has been drawn.     * Indexing: marked[row][col].     */
    private final boolean[][] marked = new boolean[ROWS][COLS];

    /**     * Default constructor.     *     * Builds a randomized tombola card by sampling the first SIZE distinct numbers     * from a shuffled pool of values 1..90 and filling the board row-major.     */
    public TombolaBoard() {
        List<Integer> pool = new ArrayList<>();
        for (int i = 1; i <= 90; i++) pool.add(i);
        Collections.shuffle(pool);
        for (int r = 0, k = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++, k++) {
                numbers[r][c] = pool.get(k);
            }
        }
    }

    /**     * Construct a board from a pre-provided 2D integer array.     *     * The provided array must have dimensions ROWS x COLS; otherwise an     * IllegalArgumentException is thrown (message currently in Italian).     *     * @param source 2D int array with exactly {@link #ROWS} rows and {@link #COLS} columns     * @throws IllegalArgumentException if source dimensions are invalid     */
    public TombolaBoard(int[][] source) {
        if (source.length != ROWS) throw new IllegalArgumentException("Row not valid");
        for (int r = 0; r < ROWS; r++) {
            if (source[r].length != COLS) throw new IllegalArgumentException("Column not valid");
            System.arraycopy(source[r], 0, numbers[r], 0, COLS);
        }
    }

    /**     * Mark the given number on the board if present.     *     * This method searches the entire grid for cells equal to {@code number}.     * All matching cells are marked (set to true). The method returns {@code true}     * if at least one cell was marked, {@code false} otherwise.     *     * The method is synchronized to allow safe concurrent calls from multiple threads.     *     * @param number the drawn number to mark     * @return true if the number was present on the board and marked; false otherwise     */
    public synchronized boolean mark(int number) {
        boolean found = false;
        for (int r = 0; r < ROWS; r++) {
            for (int c = 0; c < COLS; c++) {
                if (numbers[r][c] == number) {
                    marked[r][c] = true;
                    found = true;
                }
            }
        }
        return found;
    }

    /**     * Return the number at the given row and column.     *     * Note: callers must ensure indices are in range [0, ROWS) and [0, COLS).     *     * @param r row index (0-based)     * @param c column index (0-based)     * @return the number at the specified cell     */
    public int get(int r, int c) { return numbers[r][c]; }

    /**     * Check whether the cell at (r, c) is marked.     *     * @param r row index (0-based)     * @param c column index (0-based)     * @return true if the cell has been marked, false otherwise     */
    public boolean isMarked(int r, int c) { return marked[r][c]; }

    /**     * Count how many cells are marked in the specified row.     *     * @param row target row index (0-based)     * @return number of marked cells in the given row (0..COLS)     */
    public int markedInRow(int row) {
        int count = 0;
        for (int c = 0; c < COLS; c++) if (marked[row][c]) count++;
        return count;
    }

    /**     * Count the total number of marked cells on the board.     *     * @return total marked cells (0..SIZE)     */
    public int totalMarked() {
        int count = 0;
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                if (marked[r][c]) count++;
        return count;
    }

    /**     * Determine the next prize the player is eligible for based on current marks.     *     * Prize determination rules (matching the game's semantics):     * <ul>     *   <li>If all SIZE cells are marked => {@link Prize#TOMBOLA}</li>     *   <li>Otherwise compute the maximum number of marks in any single row and     *       return the corresponding prize:</li>     *   <ul>     *     <li>=5 => {@link Prize#CINQUINA}</li>     *     <li>=4 => {@link Prize#QUATERNA}</li>     *     <li>=3 => {@link Prize#TERNA}</li>     *     <li>=2 => {@link Prize#AMBO}</li>     *     <li>&lt;2 => {@link Prize#NONE}</li>     *   </ul>     * </ul>     *     * @return the next {@link Prize} based on current marks     */
    public Prize getNextPrize() {
        if (totalMarked() == SIZE) return Prize.TOMBOLA;
        int max = 0;
        for (int r = 0; r < ROWS; r++) max = Math.max(max, markedInRow(r));
        if (max >= 5) return Prize.CINQUINA;
        if (max >= 4) return Prize.QUATERNA;
        if (max >= 3) return Prize.TERNA;
        if (max >= 2) return Prize.AMBO;
        return Prize.NONE;
    }

    /**     * Serialize the board numbers to a single whitespace-separated string.     *     * The serialization order is row-major: row0col0 row0col1 ... row2col4.     * This representation is compact and used by the network protocol to send     * a board between client and server.     *     * @return serialized representation of the board numbers (no trailing whitespace)     */
    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                sb.append(numbers[r][c]).append(' ');
        return sb.toString().trim();
    }

    /**     * Parse a serialized board string and construct a {@link TombolaBoard} from it.     *     * Expected input: a whitespace-separated list of exactly {@link #SIZE} integers.     * If the token count is different, an IllegalArgumentException is thrown     * (message currently in Italian).     *     * @param value whitespace-separated numbers representing a board     * @return a new TombolaBoard instance with numbers initialized from {@code value}     * @throws IllegalArgumentException if the token count does not equal SIZE     * @throws NumberFormatException if any token cannot be parsed as an integer     */
    public static TombolaBoard parse(String value) {
        String[] tokens = value.trim().split("\\s+");
        if (tokens.length != SIZE) throw new IllegalArgumentException("Invalid board");
        int[][] data = new int[ROWS][COLS];
        int k = 0;
        for (int r = 0; r < ROWS; r++)
            for (int c = 0; c < COLS; c++)
                data[r][c] = Integer.parseInt(tokens[k++]);
        return new TombolaBoard(data);
    }
}