package ar.com.biopus.abismocam;

import org.junit.Test;
import static org.junit.Assert.*;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class OscTest {
    @Test public void matchesOscWireFormat() {
        assertArrayEquals(new byte[] {'/', 'g', 'o', 0, ',', 'i', 0, 0, 0, 0, 0, 1}, Osc.encode("/go", 1));
        assertArrayEquals(new byte[] {'/', 'g', 'o', 0, ',', 'i', 0, 0, 0, 0, 0, 0}, Osc.encode("/go", 0));
    }
    @Test public void alignsStringsThatAlreadyHaveFourBytes() {
        byte[] bytes = Osc.encode("/abc", -42);
        assertEquals(16, bytes.length);
        assertEquals(',', bytes[8]);
        assertEquals(-42, ByteBuffer.wrap(bytes, 12, 4).order(ByteOrder.BIG_ENDIAN).getInt());
        assertArrayEquals(new byte[] {0, 0, 0, 0}, Arrays.copyOfRange(bytes, 4, 8));
    }
    @Test public void rejectsMalformedConfiguration() {
        for (String address : new String[] {"go", "/", "/a b", "/a\u0000b", "/cámara", "/foo/*"})
            assertThrows(IllegalArgumentException.class, () -> Osc.encode(address, 1));
        for (String ip : new String[] {"1.2.3", "256.1.1.1", "1.2.3.-1", "localhost", "1.2.3.4.5"})
            assertThrows(IllegalArgumentException.class, () -> Osc.ipv4(ip));
        assertThrows(IllegalArgumentException.class, () -> new Osc.Destination("", "127.0.0.1", 0, true));
        assertThrows(IllegalArgumentException.class, () -> new Osc.Destination("", "127.0.0.1", 65536, true));
    }
    @Test public void holdSnapshotsRouteAndPreventsDuplicatePressOrRelease() {
        List<String> received = new ArrayList<>();
        Osc.Hold hold = new Osc.Hold((destinations, path, value) -> received.add(destinations.get(0).ip + path + "=" + value));
        List<Osc.Destination> targets = new ArrayList<>();
        targets.add(new Osc.Destination("first", "192.168.1.20", 9000, true));
        assertTrue(hold.press(targets, "/camara/disparo"));
        targets.clear();
        targets.add(new Osc.Destination("changed", "192.168.1.21", 8000, true));
        assertFalse(hold.press(targets, "/changed"));
        hold.release(); hold.release();
        assertEquals(Arrays.asList("192.168.1.20/camara/disparo=1", "192.168.1.20/camara/disparo=0"), received);
        assertFalse(hold.isPressed());
    }
    @Test public void noPressWithoutEnabledDestinations() {
        Osc.Hold hold = new Osc.Hold((d, p, v) -> fail("No debe enviar"));
        assertFalse(hold.press(Collections.emptyList(), "/go"));
        assertFalse(hold.press(Collections.singletonList(new Osc.Destination("off", "127.0.0.1", 9000, false)), "/go"));
        hold.release();
    }
    @Test public void sendsPressAndReleaseToMultipleRealUdpReceivers() throws Exception {
        try (DatagramSocket first = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket second = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"));
             DatagramSocket disabled = new DatagramSocket(0, InetAddress.getByName("127.0.0.1"))) {
            first.setSoTimeout(1500); second.setSoTimeout(1500); disabled.setSoTimeout(150);
            List<Osc.Destination> destinations = Arrays.asList(
                new Osc.Destination("first", "127.0.0.1", first.getLocalPort(), true),
                new Osc.Destination("second", "127.0.0.1", second.getLocalPort(), true),
                new Osc.Destination("disabled", "127.0.0.1", disabled.getLocalPort(), false));
            Osc.Hold hold = new Osc.Hold((d, p, v) -> {
                Osc.Result result = Osc.send(d, p, v);
                assertEquals(2, result.sent); assertTrue(result.errors.isEmpty());
            });
            hold.press(destinations, "/camara/disparo"); hold.release();
            for (DatagramSocket receiver : Arrays.asList(first, second)) {
                assertEquals(1, receiveInt(receiver)); assertEquals(0, receiveInt(receiver));
            }
            assertThrows(java.net.SocketTimeoutException.class, () -> receiveInt(disabled));
        }
    }
    private int receiveInt(DatagramSocket socket) throws Exception {
        DatagramPacket packet = new DatagramPacket(new byte[1024], 1024);
        socket.receive(packet);
        assertEquals(24, packet.getLength()); // /camara/disparo + padding + ,i + int32
        assertEquals(',', packet.getData()[16]);
        return ByteBuffer.wrap(packet.getData(), packet.getLength() - 4, 4).getInt();
    }
}
