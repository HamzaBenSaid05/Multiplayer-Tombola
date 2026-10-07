
package Project_Tombola.network;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** * Discovery * * <p> * Helper utilities for hub discovery on the local network. * The hub periodically announces itself via UDP; clients can listen for * these announcements to automatically locate the hub. When discovery * is not possible, clients can fall back to a configured host/port. * </p> * * <p>Public constants: * <ul> *   <li>{@link #PORT} - UDP port used for discovery broadcasts.</li> *   <li>{@link #TAG}  - discovery tag string used as the first token of the announcement.</li> * </ul> * </p> * * <p>Provided utilities: * <ul> *   <li>{@link #encodeHub(int)} - build the payload that the hub sends in discovery datagrams.</li> *   <li>{@link #targets()} - enumerate target addresses to which the hub should send discovery packets: *       global broadcast, per-interface broadcast addresses and localhost.</li> * </ul> * </p> * * <p>All messages use UTF-8 encoding via {@link StandardCharsets#UTF_8} and the project's {@link Protocol} * helper to format the line.</p> */
public final class Discovery {
    /** UDP port used by the hub to announce its presence.
     *
     * The value is taken from {@link HubConfig#PORT} so it can be
     * configured via the environment (TOMBOLA_DISCOVERY_PORT) instead of being
     * hardcoded here.
     */
    public static final int PORT = HubConfig.PORT;

    /** Tag used in discovery payloads to identify a Tombola hub. */
    public static final String TAG = "TOMBOLA_HUB";

    // Prevent instantiation - utility class.
    private Discovery() {}

    /**     * Encode a hub announcement payload for the given TCP port.     *     * <p>The returned byte array contains a single protocol line with the discovery     * tag followed by the hub's TCP port. The receiver can parse this line using     * {@link Protocol#parse(String)} to extract the TCP port.</p>     *     * @param tcpPort the TCP port number where the hub accepts client connections     * @return UTF-8 encoded bytes representing the discovery line     */
    public static byte[] encodeHub(int tcpPort) {
        return Protocol.line(TAG, String.valueOf(tcpPort)).getBytes(StandardCharsets.UTF_8);
    }

    /**     * Compute a list of InetAddress targets where discovery announcements should be sent.     *     * <p>The method collects:     * <ol>     *   <li>The global broadcast address 255.255.255.255 (if resolvable),</li>     *   <li>The loopback address (localhost),</li>     *   <li>The broadcast address for every network interface that is up and not loopback     *       (if the interface provides a broadcast address).</li>     * </ol>     * The result preserves insertion order and removes duplicates by using a {@link LinkedHashSet}.</p>     *     * <p>Network errors are ignored (caught and swallowed) because discovery is a best-effort     * mechanism; callers should still be prepared to fall back to a configured host if discovery     * does not yield a hub address.</p>     *     * @return a list of candidate InetAddress targets for discovery packets (may be empty)     */
    public static List<InetAddress> targets() {
        Set<InetAddress> set = new LinkedHashSet<>();
        try { set.add(InetAddress.getByName("255.255.255.255")); } catch (Exception ignored) {}
        set.add(InetAddress.getLoopbackAddress());
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    if (ia.getBroadcast() != null) set.add(ia.getBroadcast());
                }
            }
        } catch (Exception ignored) {}
        return new ArrayList<>(set);
    }
}