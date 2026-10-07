package Project_Tombola.network;

/**
 * HubConfig
 *
 * <p>Holds configuration values used to locate and connect to the Tombola hub.
 * All values are read from environment variables at class initialization time,
 * allowing deployment-time configuration without changing code.</p>
 *
 * <p>Environment variables used:
 * <ul>
 *   <li><b>TOMBOLA_IP</b> - host or IP address of the hub (stored in {@link #HOST}).</li>
 *   <li><b>TOMBOLA_PORT</b> - TCP port on which the hub accepts client connections
 *       (parsed as an integer and stored in {@link #PORT}).</li>
 * </ul>
 * </p>
 *
 * <p>Notes:
 * <ul>
 *   <li>Both variables are read via {@link System#getenv(String)} when this class is loaded.</li>
 *   <li>If TOMBOLA_PORT is not set or cannot be parsed as an integer, a {@link NumberFormatException}
 *       will be thrown during class initialization. If TOMBOLA_IP is not set, {@link #HOST} will be {@code null}.</li>
 *   <li>Because these fields are static finals, their values are fixed for the lifetime of the JVM process.</li>
 * </ul>
 * </p>
 */
public final class HubConfig {
    /**
     * Hostname or IP address of the hub.
     *
     * Source: environment variable TOMBOLA_IP.
     * May be {@code null} when the environment variable is not defined.
     */
    public static final String HOST = System.getenv("TOMBOLA_IP");

    /**
     * TCP port used to connect to the hub.
     *
     * Source: environment variable TOMBOLA_PORT.
     * This field is initialized by parsing the environment variable to an int:
     * Integer.parseInt(System.getenv("TOMBOLA_PORT")).
     *
     * Caution: if TOMBOLA_PORT is not set or contains a non-numeric value, class
     * initialization will throw a {@link NumberFormatException} and the class will
     * fail to load. Make sure the environment provides a valid integer string.
     */
    public static final int PORT = Integer.parseInt(System.getenv("TOMBOLA_PORT"));

    /**
     * Private constructor to prevent instantiation of this utility/config class.
     */
    private HubConfig() {}
}