package dev.cloudlan.bridge;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Host-side bridge: a local WebSocket server (loopback only) that cloudflared
 * points at. Every WebSocket connection carries exactly one Minecraft TCP
 * connection to the locally published world.
 *
 * <p>Byte flow: remote player ⇄ Cloudflare edge ⇄ cloudflared ⇄ this server ⇄
 * {@code 127.0.0.1:gamePort} (the integrated server's LAN port).</p>
 *
 * <p>Intentionally free of Minecraft classes so the E2E self test can drive it.</p>
 */
public final class BridgeServer extends WebSocketServer {
	private static final Logger LOGGER = LoggerFactory.getLogger("cloudlan/bridge");

	private final String gameHost;
	private final int gamePort;
	private final AtomicInteger connections = new AtomicInteger();
	private final Map<WebSocket, Socket> sockets = new ConcurrentHashMap<>();
	private final java.util.concurrent.CountDownLatch startLatch = new java.util.concurrent.CountDownLatch(1);

	/** Maximum WebSocket payload per frame; MC's TCP stream is byte-oriented so frames may be split freely. */
	static final int MAX_FRAME = 32 * 1024;

	public BridgeServer(int listenPort, String gameHost, int gamePort) {
		super(new InetSocketAddress("127.0.0.1", listenPort));
		this.gameHost = gameHost;
		this.gamePort = gamePort;
		setReuseAddr(true);
	}

	public int activeConnections() {
		return connections.get();
	}

	@Override
	public void onStart() {
		setConnectionLostTimeout(30);
		LOGGER.info("Bridge listening on 127.0.0.1:{} → {}:{}", getPort(), gameHost, gamePort);
		startLatch.countDown();
	}

	public boolean awaitStarted(long millis) throws InterruptedException {
		return startLatch.await(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
	}

	@Override
	public void onOpen(WebSocket conn, ClientHandshake handshake) {
		Socket tcp = new Socket();
		try {
			tcp.connect(new InetSocketAddress(gameHost, gamePort), 5_000);
			tcp.setTcpNoDelay(true);
		} catch (IOException e) {
			LOGGER.warn("Cannot reach game server {}:{}: {}", gameHost, gamePort, e.toString());
			closeQuietly(tcp);
			conn.close(1013, "game unreachable");
			return;
		}
		sockets.put(conn, tcp);
		connections.incrementAndGet();
		LOGGER.info("Player tunnel opened ({} active)", connections.get());

		Thread pump = new Thread(() -> pumpTcpToWs(conn, tcp), "cloudlan-bridge-tcp2ws");
		pump.setDaemon(true);
		pump.start();
	}

	private static void pumpTcpToWs(WebSocket conn, Socket tcp) {
		byte[] buf = new byte[MAX_FRAME];
		try {
			InputStream in = tcp.getInputStream();
			int n;
			while ((n = in.read(buf)) >= 0) {
				if (!conn.isOpen()) {
					break;
				}
				// copy: send() may queue the array asynchronously
				byte[] chunk = new byte[n];
				System.arraycopy(buf, 0, chunk, 0, n);
				conn.send(chunk);
			}
		} catch (IOException ignored) {
			// TCP side closed or reset — expected on disconnect.
		} finally {
			closeQuietly(tcp);
			if (conn.isOpen()) {
				conn.close();
			}
		}
	}

	@Override
	public void onMessage(WebSocket conn, ByteBuffer message) {
		Socket tcp = sockets.get(conn);
		if (tcp == null) {
			return;
		}
		try {
			OutputStream out = tcp.getOutputStream();
			// ByteBuffer position/limit describe the payload slice.
			byte[] data = new byte[message.remaining()];
			message.get(data);
			out.write(data);
			out.flush();
		} catch (IOException e) {
			LOGGER.debug("Write to game failed: {}", e.toString());
			closeQuietly(tcp);
			conn.close();
		}
	}

	@Override
	public void onMessage(WebSocket conn, String message) {
		// Minecraft traffic is binary only; ignore stray text frames.
	}

	@Override
	public void onClose(WebSocket conn, int code, String reason, boolean remote) {
		Socket tcp = sockets.remove(conn);
		closeQuietly(tcp);
		connections.decrementAndGet();
		LOGGER.info("Player tunnel closed ({} active)", connections.get());
	}

	@Override
	public void onError(WebSocket conn, Exception ex) {
		LOGGER.debug("Bridge error: {}", ex.toString());
		if (conn != null) {
			Socket tcp = sockets.remove(conn);
			closeQuietly(tcp);
		}
	}

	@Override
	public void stop(int timeout) throws InterruptedException {
		for (Socket tcp : sockets.values()) {
			closeQuietly(tcp);
		}
		sockets.clear();
		super.stop(timeout);
	}

	private static void closeQuietly(Socket s) {
		if (s == null) {
			return;
		}
		try {
			s.close();
		} catch (IOException ignored) {
		}
	}

	/** Binds an ephemeral loopback port and returns it, or -1 when unavailable. */
	public static int findFreePort() {
		try (ServerSocket ss = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
			return ss.getLocalPort();
		} catch (IOException e) {
			return -1;
		}
	}
}
