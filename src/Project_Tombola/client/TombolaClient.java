
package Project_Tombola.client;

import Project_Tombola.game.Prize;
import Project_Tombola.game.TombolaBoard;
import Project_Tombola.network.Discovery;
import Project_Tombola.network.HubConfig;
import Project_Tombola.network.Protocol;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.List;

/** * TombolaClient * * <p> * Swing-based client application for a multiplayer "Tombola" (Bingo-like) game. * This class builds the UI (lobby + game screens), manages a persistent TCP * connection to a hub server and interprets protocol messages coming from the hub. * It keeps the UI synchronized with the server state and sends user commands back * (create session, join session, leave, etc.). * </p> * * Responsibilities: * - Discover hub on the local network (via UDP discovery) or fallback to configured host. * - Maintain a persistent TCP connection to the hub and reconnect on failure. * - Poll for available game sessions while in the lobby. * - Render the player's tombola board and the global "drawn numbers" board. * - Show non-blocking prize popups to winning players. * * Note: UI text is set in the code (some strings are in Italian as part of the UI), * but all documentation and comments in this file are in English. */
public final class TombolaClient {
    // UI color constants used to style cells and drawn numbers
    private static final Color CELL_EMPTY = new Color(235, 235, 235);
    private static final Color CELL_MARKED = new Color(120, 210, 140);
    private static final Color TAB_EMPTY = new Color(245, 245, 245);
    private static final Color TAB_DRAWN = new Color(255, 224, 130);
    private static final Color TAB_LAST = new Color(255, 140, 60);

    /**     * Represents a waiting session as listed by the hub.     *     * Immutable small data holder used by the lobby session list.     */
    private static final class Session {
        final int id;
        final String name;
        final int joined;
        final int expected;

        Session(int id, String name, int joined, int expected) {
            this.id = id;
            this.name = name;
            this.joined = joined;
            this.expected = expected;
        }

        @Override public String toString() {
            return name + "   (" + joined + "/" + expected + " players)";
        }
    }

    // Main frame and layout
    private JFrame frame;
    private final CardLayout cards = new CardLayout();
    private final JPanel root = new JPanel(cards);

    // --- Lobby (initial screen) components ---
    private JTextField nameField;
    private final DefaultListModel<Session> sessionModel = new DefaultListModel<>();
    private JList<Session> sessionList;
    private JButton joinButton;
    private JButton createButton;
    private JLabel lobbyStatus;

    // --- Game screen components ---
    private JLabel playerLabel, numberLabel, prizeLabel, lastPrizeLabel, statusLabel, playersLabel;
    private JLabel[][] cells;
    private JLabel[] drawnLabels;

    // Networking and state
    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile boolean inGame;
    private volatile boolean gameOver;
    private TombolaBoard board;
    private int lastNumber;

    /**     * Application entry point. Launches the Swing UI on the EDT.     *     * @param args ignored     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TombolaClient().show());
    }

    /**     * Build the main window with both lobby and game cards and start background threads.     *     * The method sets up the frame, adds the lobby and game panels and starts:     * - a connection thread that maintains the TCP socket to the hub     * - a polling thread that periodically requests the sessions list when not in a game     */
    public void show() {
        frame = new JFrame("Tombola Multiplayer");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(820, 780);
        frame.setLocationRelativeTo(null);

        root.add(buildLobby(), "lobby");
        root.add(buildGame(), "game");
        frame.add(root);
        frame.setVisible(true);

        startConnectionThread();
        startPollingThread();
    }

    // =====================================================================
    //  LOBBY SCREEN
    // =====================================================================

    /**     * Build the lobby UI panel.     *     * UI elements:     * - title and name input     * - scrollable list of available sessions     * - create / join buttons and a status label     *     * @return constructed lobby panel     */
    private JPanel buildLobby() {
        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBorder(BorderFactory.createEmptyBorder(20, 30, 20, 30));

        JLabel title = new JLabel("Tombola", SwingConstants.CENTER);
        title.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 36));

        nameField = new JTextField(System.getProperty("user.name", "Giocatore"), 16);
        nameField.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        nameField.addKeyListener(new KeyAdapter() {
            @Override public void keyReleased(KeyEvent e) { updateButtons(); }
        });
        JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        namePanel.add(new JLabel("Your name:"));
        namePanel.add(nameField);

        JPanel top = new JPanel(new GridLayout(2, 1, 4, 4));
        top.add(title);
        top.add(namePanel);

        sessionList = new JList<>(sessionModel);
        sessionList.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 18));
        sessionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        sessionList.addListSelectionListener(e -> updateButtons());
        sessionList.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && joinButton.isEnabled()) joinSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(sessionList);
        scroll.setBorder(BorderFactory.createTitledBorder("Available sessions"));

        lobbyStatus = new JLabel("Searching for server...");
        createButton = new JButton("Create session");
        createButton.setEnabled(false);
        createButton.addActionListener(e -> createSession());
        joinButton = new JButton("Join session");
        joinButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        joinButton.setEnabled(false);
        joinButton.addActionListener(e -> joinSelected());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.add(createButton);
        buttons.add(joinButton);

        JPanel bottom = new JPanel(new BorderLayout(8, 8));
        bottom.add(lobbyStatus, BorderLayout.CENTER);
        bottom.add(buttons, BorderLayout.EAST);

        p.add(top, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    /**     * Return the sanitized player name from the nameField.     *     * The protocol uses characters as separators; those are removed here.     *     * @return trimmed and sanitized player name (may be empty)     */
    private String playerName() {
        return nameField.getText().trim().replaceAll("[|,;]", "");
    }

    /**     * Update the enabled/disabled state of lobby action buttons.     *     * Buttons depend on:     * - whether there is an active outgoing writer (connected to hub)     * - whether the user entered a non-empty name     * - whether a selected session has available seats     */
    private void updateButtons() {
        boolean online = out != null;
        boolean hasName = !playerName().isEmpty();
        Session s = sessionList.getSelectedValue();
        createButton.setEnabled(online && hasName);
        joinButton.setEnabled(online && hasName && s != null && s.joined < s.expected);
    }

    /**     * Show a dialog to create a new session and send CREATE to the hub when confirmed.     *     * The dialog collects: session name, number of players, seconds per draw (delay).     * Inputs are sanitized before sending.     */
    private void createSession() {
        String name = playerName();
        if (name.isEmpty()) return;

        JTextField sessionField = new JTextField("Tombola of " + name, 16);
        JSpinner players = new JSpinner(new SpinnerNumberModel(2, 1, 20, 1));
        JSpinner delay = new JSpinner(new SpinnerNumberModel(3, 1, 15, 1));

        JPanel form = new JPanel(new GridLayout(0, 2, 8, 8));
        form.add(new JLabel("Session name:"));
        form.add(sessionField);
        form.add(new JLabel("Number of players:"));
        form.add(players);
        form.add(new JLabel("Seconds per draw:"));
        form.add(delay);

        int choice = JOptionPane.showConfirmDialog(frame, form, "Create session",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (choice != JOptionPane.OK_OPTION) return;

        String sessionName = sessionField.getText().trim().replaceAll("[|,;]", "");
        if (sessionName.isEmpty()) sessionName = "Tombola";
        lobbyStatus.setText("Creating session...");
        send(Protocol.line(Protocol.CREATE, sessionName,
                String.valueOf(players.getValue()), String.valueOf(delay.getValue()), name));
    }

    /**     * Send a join request for the currently selected session using the player's name.     */
    private void joinSelected() {
        Session s = sessionList.getSelectedValue();
        String name = playerName();
        if (s == null || name.isEmpty()) return;
        lobbyStatus.setText("Joining \"" + s.name + "\"...");
        send(Protocol.line(Protocol.JOIN, String.valueOf(s.id), name));
    }

    /**     * Leave the current session and return to the lobby view.     *     * The method sends LEAVE, resets in-game flags, shows the lobby card and requests a fresh session list.     */
    private void leaveToLobby() {
        send(Protocol.LEAVE);
        inGame = false;
        sessionModel.clear();
        lobbyStatus.setText(out != null ? "Connected" : "Server not reachable, retrying...");
        cards.show(root, "lobby");
        send(Protocol.LIST);
        updateButtons();
    }

    /**     * Update the lobby session list model from a semicolon-separated data string.     *     * Each entry is expected as: id,name,joined,expected     * The method updates the UI only if the list actually changed to avoid flicker.     *     * @param data semicolon-separated session entries (may be empty)     */
    private void updateSessions(String data) {
        List<Session> list = new ArrayList<>();
        if (!data.isEmpty()) {
            for (String entry : data.split(";")) {
                String[] f = entry.split(",");
                if (f.length < 4) continue;
                try {
                    list.add(new Session(Integer.parseInt(f[0]), f[1],
                            Integer.parseInt(f[2]), Integer.parseInt(f[3])));
                } catch (NumberFormatException ignored) {}
            }
        }
        ui(() -> {
            boolean same = list.size() == sessionModel.size();
            for (int i = 0; same && i < list.size(); i++) {
                same = list.get(i).id == sessionModel.get(i).id
                        && list.get(i).toString().equals(sessionModel.get(i).toString());
            }
            if (!same) {
                Session selected = sessionList.getSelectedValue();
                sessionModel.clear();
                for (Session s : list) sessionModel.addElement(s);
                if (selected != null) {
                    for (int i = 0; i < list.size(); i++) {
                        if (list.get(i).id == selected.id) sessionList.setSelectedIndex(i);
                    }
                }
            }
            lobbyStatus.setText(list.isEmpty()
                    ? "Connected. No sessions available: create one!"
                    : "Select a session and click Join");
            updateButtons();
        });
    }

    // =====================================================================
    //  HUB CONNECTION (NETWORK & PROTOCOL)
    // =====================================================================

    /**     * Send a protocol line to the hub (if connected).     *     * Uses the cached PrintWriter created when the socket connected. If not connected,     * the message is dropped silently.     *     * @param message full protocol line (typically produced via Protocol.line(...))     */
    private void send(String message) {
        PrintWriter w = out;
        if (w != null) w.println(message);
    }

    /**     * Discover the hub on the local network via UDP advertisement.     *     * The method binds to Discovery.PORT and waits briefly for a datagram from the hub.     * If a discovery message is received and valid, returns the hub's address and port.     * Otherwise falls back to HubConfig.HOST and HubConfig.TCP_PORT.     *     * @return InetSocketAddress of the hub (discovered or configured fallback)     */
    private InetSocketAddress findHub() {
        try (DatagramSocket ds = new DatagramSocket(null)) {
            ds.setReuseAddress(true);
            ds.bind(new InetSocketAddress(Discovery.PORT));
            ds.setSoTimeout(1500);
            DatagramPacket p = new DatagramPacket(new byte[256], 256);
            ds.receive(p);
            String[] m = Protocol.parse(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
            if (m.length >= 2 && m[0].equals(Discovery.TAG)) {
                return new InetSocketAddress(p.getAddress(), Integer.parseInt(m[1]));
            }
        } catch (Exception ignored) {}
        return new InetSocketAddress(HubConfig.HOST, HubConfig.TCP_PORT);
    }

    /**     * Start a background thread that maintains a persistent TCP connection to the hub.     *     * Behavior:     * - Repeatedly attempts to connect to the hub (using findHub()).     * - When connected, sets up a reader loop reading protocol lines.     * - Each incoming line is parsed and dispatched to handle(...).     * - On disconnection the thread resets local state, updates the UI and retries after a delay.     *     * The thread is a daemon named "TombolaConnection".     */
    private void startConnectionThread() {
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    ui(() -> {
                        if (!inGame) lobbyStatus.setText("Searching for server...");
                    });
                    Socket sock = new Socket();
                    sock.connect(findHub(), 4000);
                    BufferedReader reader = new BufferedReader(new InputStreamReader(sock.getInputStream()));
                    socket = sock;
                    out = new PrintWriter(new BufferedWriter(new OutputStreamWriter(sock.getOutputStream())), true);
                    ui(() -> {
                        if (!inGame) lobbyStatus.setText("Connected");
                        updateButtons();
                    });
                    send(Protocol.LIST);

                    String line;
                    while ((line = reader.readLine()) != null) {
                        try {
                            handle(Protocol.parse(line));
                        } catch (RuntimeException ignored) {}
                    }
                } catch (Exception ignored) {}

                out = null;
                socket = null;
                ui(() -> {
                    if (inGame) {
                        if (!gameOver) statusLabel.setText("Disconnected from server");
                    } else {
                        sessionModel.clear();
                        lobbyStatus.setText("Server not reachable, retrying...");
                    }
                    updateButtons();
                });
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
            }
        }, "TombolaConnection");
        t.setDaemon(true);
        t.start();
    }

    /**     * Start a background thread that periodically requests the session list from the hub.     *     * While the client is not in a game, this thread sends Protocol.LIST every 1.5 seconds     * so the lobby remains up-to-date even if the hub doesn't push updates.     */
    private void startPollingThread() {
        Thread t = new Thread(() -> {
            while (true) {
                if (!inGame) send(Protocol.LIST);
                try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            }
        }, "TombolaPolling");
        t.setDaemon(true);
        t.start();
    }

    /**     * Top-level protocol message dispatcher.     *     * Routes messages between lobby handling and in-game handling. Known messages:     * - Protocol.SESSIONS: update lobby sessions     * - Protocol.ERROR: show error in lobby or in-game status line     * - Protocol.WELCOME: transition into the game UI (sets inGame = true)     *     * Unrecognized messages received while in-game are delegated to handleGame(...).     *     * @param msg parsed protocol tokens     */
    private void handle(String[] msg) {
        switch (msg[0]) {
            case Protocol.SESSIONS -> {
                if (!inGame) updateSessions(msg.length > 1 ? msg[1] : "");
            }
            case Protocol.ERROR -> {
                String text = msg.length > 1 ? msg[1] : "Error";
                ui(() -> {
                    if (inGame) statusLabel.setText("Error: " + text);
                    else lobbyStatus.setText(text);
                });
            }
            case Protocol.WELCOME -> {
                inGame = true;
                String name = msg[2];
                ui(() -> {
                    resetGame();
                    playerLabel.setText("Player: " + name);
                    cards.show(root, "game");
                });
            }
            default -> {
                if (inGame) handleGame(msg);
            }
        }
    }

    /**     * Handle in-game protocol messages.     *     * Recognized messages:     * - Protocol.PLAYERS : players count + names (updates status + players label)     * - Protocol.BOARD   : serialized player board (parses and renders)     * - Protocol.START   : game start notification     * - Protocol.NUMBER  : number drawn (update UI, mark board and tabellone)     * - Protocol.PRIZE   : prize awarded to this client (show popup)     * - Protocol.WINNER  : information about a prize winner (update last prize label)     * - Protocol.GAME_OVER: game finished (set gameOver and update status)     *     * @param msg parsed protocol tokens     */
    private void handleGame(String[] msg) {
        switch (msg[0]) {
            case Protocol.PLAYERS -> {
                String names = msg[2];
                int count = names.isEmpty() ? 0 : names.split(",").length;
                ui(() -> {
                    statusLabel.setText("Waiting for players: " + count + "/" + msg[1]);
                    playersLabel.setText("Players: " + names.replace(",", ", "));
                });
            }
            case Protocol.BOARD -> {
                TombolaBoard parsed = TombolaBoard.parse(msg[1]);
                ui(() -> {
                    board = parsed;
                    renderBoard();
                });
            }
            case Protocol.START -> ui(() -> statusLabel.setText("Game in progress"));
            case Protocol.NUMBER -> {
                int number = Integer.parseInt(msg[1]);
                ui(() -> {
                    numberLabel.setText(String.valueOf(number));
                    if (board != null) board.mark(number);
                    renderBoard();
                    markDrawn(number);
                });
            }
            case Protocol.PRIZE -> {
                Prize prize = Prize.valueOf(msg[1]);
                ui(() -> {
                    prizeLabel.setText("Your prize: " + prize.getDisplayName());
                    showPrizePopup(prize);
                });
            }
            case Protocol.WINNER -> {
                String names = msg[2].replace(",", ", ");
                Prize prize = Prize.valueOf(msg[3]);
                ui(() -> lastPrizeLabel.setText(
                        "Last prize awarded: " + prize.getDisplayName() + " - " + names));
            }
            case Protocol.GAME_OVER -> {
                gameOver = true;
                ui(() -> statusLabel.setText("Game Over"));
            }
            default -> {}
        }
    }

    // =====================================================================
    //  GAME SCREEN (UI)
    // =====================================================================

    /**     * Build the game UI panel which includes:     * - player info and status area     * - the player's 3x5 tombola board     * - the global drawn-numbers board ("tabellone") showing 1..90     *     * @return constructed game panel     */
    private JPanel buildGame() {
        JPanel p = new JPanel(new BorderLayout(12, 12));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

        playerLabel = new JLabel("Player: -");
        statusLabel = new JLabel("Waiting...");
        playersLabel = new JLabel("Players: -");
        prizeLabel = new JLabel("Your prize: None");
        lastPrizeLabel = new JLabel("Last prize awarded: -");

        JPanel info = new JPanel(new GridLayout(0, 1, 2, 2));
        info.add(playerLabel);
        info.add(statusLabel);
        info.add(playersLabel);
        info.add(prizeLabel);
        info.add(lastPrizeLabel);

        numberLabel = new JLabel("-", SwingConstants.CENTER);
        numberLabel.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 56));
        numberLabel.setBorder(BorderFactory.createTitledBorder("Last Number"));
        numberLabel.setPreferredSize(new Dimension(190, 110));

        JPanel header = new JPanel(new BorderLayout(12, 12));
        header.add(info, BorderLayout.CENTER);
        header.add(numberLabel, BorderLayout.EAST);

        JPanel boardPanel = new JPanel(new GridLayout(3, 5, 8, 8));
        boardPanel.setBorder(BorderFactory.createTitledBorder("Your Card"));
        cells = new JLabel[3][5];
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 5; c++) {
                JLabel l = new JLabel("-", SwingConstants.CENTER);
                l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
                l.setOpaque(true);
                l.setBackground(CELL_EMPTY);
                l.setBorder(BorderFactory.createLineBorder(Color.GRAY));
                cells[r][c] = l;
                boardPanel.add(l);
            }
        }

        JPanel tabellone = new JPanel(new GridLayout(9, 10, 3, 3));
        tabellone.setBorder(BorderFactory.createTitledBorder("Drawn Numbers"));
        tabellone.setPreferredSize(new Dimension(0, 230));
        drawnLabels = new JLabel[91];
        for (int n = 1; n <= 90; n++) {
            JLabel l = new JLabel(String.valueOf(n), SwingConstants.CENTER);
            l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            l.setOpaque(true);
            l.setBorder(BorderFactory.createLineBorder(new Color(210, 210, 210)));
            drawnLabels[n] = l;
            tabellone.add(l);
        }

        JButton leave = new JButton("Leave Session");
        leave.addActionListener(e -> leaveToLobby());

        JPanel south = new JPanel(new BorderLayout(6, 6));
        south.add(tabellone, BorderLayout.CENTER);
        south.add(leave, BorderLayout.SOUTH);

        p.add(header, BorderLayout.NORTH);
        p.add(boardPanel, BorderLayout.CENTER);
        p.add(south, BorderLayout.SOUTH);
        return p;
    }

    /**     * Reset local game state and UI in preparation for joining a new game or after WELCOME.     *     * Clears board, drawn numbers and resets labels to their initial states.     */
    private void resetGame() {
        board = null;
        lastNumber = 0;
        gameOver = false;
        playerLabel.setText("Player: -");
        statusLabel.setText("Waiting for other players...");
        playersLabel.setText("Players: -");
        prizeLabel.setText("Your prize: None");
        lastPrizeLabel.setText("Last prize awarded: -");
        numberLabel.setText("-");
        for (JLabel[] row : cells) {
            for (JLabel l : row) {
                l.setText("-");
                l.setBackground(CELL_EMPTY);
            }
        }
        for (int n = 1; n <= 90; n++) {
            drawnLabels[n].setBackground(TAB_EMPTY);
            drawnLabels[n].setForeground(new Color(150, 150, 150));
        }
    }

    /**     * Render the player's tombola board UI from the board model.     *     * If the board model is null this method does nothing.     */
    private void renderBoard() {
        if (board == null) return;
        for (int r = 0; r < 3; r++) {
            for (int c = 0; c < 5; c++) {
                cells[r][c].setText(String.valueOf(board.get(r, c)));
                cells[r][c].setBackground(board.isMarked(r, c) ? CELL_MARKED : CELL_EMPTY);
            }
        }
    }

    /**     * Update the tabellone (drawn numbers board) when a new number is announced.     *     * The previously-last number is colored as drawn (TAB_DRAWN), the newly announced     * number gets TAB_LAST and its foreground is set to black.     *     * @param number the newly drawn number (1..90)     */
    private void markDrawn(int number) {
        if (lastNumber != 0) drawnLabels[lastNumber].setBackground(TAB_DRAWN);
        drawnLabels[number].setBackground(TAB_LAST);
        drawnLabels[number].setForeground(Color.BLACK);
        lastNumber = number;
    }

    /**     * Show a non-blocking popup to notify the user of a prize.     *     * The popup automatically closes after 3 seconds. A special message is used for TOMBOLA.     *     * @param prize prize awarded to this client     */
    private void showPrizePopup(Prize prize) {
        String text = prize == Prize.TOMBOLA
                ? "TOMBOLA! You Have Won!"
                : "You Have Won: " + prize.getDisplayName() + "!";

        JDialog dialog = new JDialog(frame, "Tombola", false);
        JLabel label = new JLabel(text, SwingConstants.CENTER);
        label.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 30));
        label.setBorder(BorderFactory.createEmptyBorder(30, 50, 30, 50));
        dialog.add(label);
        dialog.pack();
        dialog.setLocationRelativeTo(frame);
        dialog.setVisible(true);

        javax.swing.Timer closer = new javax.swing.Timer(3000, e -> dialog.dispose());
        closer.setRepeats(false);
        closer.start();
    }

    /**     * Utility to schedule a Runnable on the Swing Event Dispatch Thread.     *     * @param r runnable to execute on the UI thread     */
    private void ui(Runnable r) {
        SwingUtilities.invokeLater(r);
    }
}