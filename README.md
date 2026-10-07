# Tombola Multiplayer

A multiplayer client/server implementation of the Italian Tombola game developed during my high-school programming studies.

The project consists of separate client and server applications communicating through TCP sockets. The server manages connected players, creates and manages game sessions, generates and broadcasts drawn numbers, distributes game cards, and determines winning combinations such as Ambo, Terna, Quaterna, Cinquina, and Tombola.

The application uses multithreading to handle multiple clients concurrently, with a dedicated handler for each connection. A UDP-based discovery system allows clients on the same local network to automatically discover available Tombola sessions.

The client features a graphical **lobby** where players can choose their name, view available sessions and their current player count, and join or leave a game. During a match, the client displays the Tombola card, a live 1–90 number board, the latest drawn number, and real-time game notifications.

The server supports configurable drawing intervals, multiple simultaneous winners, and starting new games without restarting the application.

The project can be packaged as standalone Windows applications using `jpackage`, allowing the client and server to be distributed with an embedded Java runtime.
