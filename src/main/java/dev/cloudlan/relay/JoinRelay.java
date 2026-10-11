package dev.cloudlan.relay;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Joiner-side relay. Listens on an ephemeral loopback TCP port; Minecraft's own
 * connection logic talks plain TCP to it, and each accepted connection is
 * tunnelled through a WebSocket to {@code wss://<tunnel-host>/} (Cloudflare).
 *
 * <p>Byte flow: local Minecraft client ⇄ this relay ⇄ Cloudflare edge ⇄ host's
 * cloudflared ⇄ host bridge ⇄ host world.</p>
 *
 * <p>Free of Minecraft classes so the E2E self test can drive it.</p>
 */
public final class JoinRelay {
	private static final Logger LOGGER = LoggerFactory.getLogger("cloudlan/relay");

	private final String host;          // e.g. "xxxx-yyyy.trycloudflare.com"
	private final URI wsUri;
	private final ServerSocket server;
	private final Thread acceptThread;
	private volatile boolean running = true;
	private final List<Socket> activeTcp = new ArrayList<>();

	public JoinRelay(String tunnelHost, int port) throws IOException {
		// Accept either a bare hostname or a pasted URL like https://x.trycloudflare.com/
		this.host = dev.cloudlan.util.TunnelAddresses.sanitize(tunnelHost).toLowerCase(Locale.ROOT);
		if (this.host.isEmpty()) {
			throw new IOException("empty tunnel host");
		}
		// Cloudflare quick tunnels always terminate wss on 443 regardless of the
		// game port the user typed (the multiplayer screen defaults to 25565),
		// so pin 443 for *.trycloudflare.com and honour an explicit port elsewhere.
		int wsPort = this.host.endsWith(".trycloudflare.com") ? 443 : (port > 0 ? port : 443);
		this.wsUri = URI.create("wss://" + this.host + ":" + wsPort + "/");
		this.server = new ServerSocket(0, 16, InetAddress.getLoopbackAddress());
		this.acceptThread = new Thread(this::acceptLoop, "cloudlan-relay-accept");
		this.acceptThread.setDaemon(true);
		this.acceptThread.start();
		LOGGER.info("Relay for {} listening on 127.0.0.1:{}", this.host, getLocalPort());
	}

	public int getLocalPort() {
		return server.getLocalPort();
	}

	public String host() {
		return host;
	}

	private void acceptLoop() {
		while (running) {
			Socket tcp;
			try {
				tcp = server.accept();
			} catch (IOException e) {
				if (running) {
					LOGGER.warn("Relay accept failed: {}", e.toString());
				}
				return;
			}
			Thread t = new Thread(() -> handleConnection(tcp), "cloudlan-relay-conn");
			t.setDaemon(true);
			t.start();
		}
	}

	private void handleConnection(Socket tcp) {
		synchronized (activeTcp) {
			activeTcp.add(tcp);
		}
		try {
			tcp.setTcpNoDelay(true);
		} catch (IOException ignored) {
		}

		// Retry with backoff: a freshly created quick-tunnel hostname may need a
		// few seconds to propagate through DNS, and the network can hiccup.
		// Budget stays within Minecraft's own connect timeout (~30 s).
		Exception last = null;
		for (int attempt = 1; attempt <= 4 && running; attempt++) {
			if (tcp.isClosed()) {
				return;
			}
			try {
				if (openTunnel(tcp)) {
					return;
				}
			} catch (Exception e) {
				last = e;
				LOGGER.warn("Relay attempt {}/4 to {} failed: {}", attempt, host, e.toString());
			}
			try {
				Thread.sleep(3_000L);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		LOGGER.warn("Relay giving up on {}: {}", host, last == null ? "tunnel closed" : last.toString());
		try {
			tcp.close();
		} catch (IOException ignored) {
		}
	}

	/**
	 * Opens one WebSocket tunnel for the TCP connection. Returns true once the
	 * tunnel is up and the TCP→WS pump is running; false to signal a retry.
	 */
	private boolean openTunnel(Socket tcp) throws IOException {
		final Object writeLock = new Object();
		// flips to true once connectBlocking succeeds; gates teardown callbacks
		final boolean[] opened = {false};

		WebSocketClient ws = new WebSocketClient(wsUri) {
			@Override
			public void onOpen(ServerHandshake handshake) {
				LOGGER.info("Relay WS opened for {}", host);
			}

			@Override
			public void onMessage(String message) {
			}

			@Override
			public void onMessage(ByteBuffer message) {
				try {
					OutputStream out = tcp.getOutputStream();
					byte[] data = new byte[message.remaining()];
					message.get(data);
					synchronized (writeLock) {
						out.write(data);
						out.flush();
					}
				} catch (IOException e) {
					closeAll(this, tcp);
				}
			}

			@Override
			public void onClose(int code, String reason, boolean remote) {
				LOGGER.info("Relay WS closed: {} {}", code, reason);
				// Only tear down the TCP side after the tunnel was established;
				// pre-open failures are handled by openTunnel()'s caller.
				if (opened[0]) {
					closeAll(this, tcp);
				}
			}

			@Override
			public void onError(Exception ex) {
				LOGGER.warn("Relay WS error: {}", ex.toString());
				if (opened[0]) {
					closeAll(this, tcp);
				}
			}
		};
		// trycloudflare uses a valid public cert; keep default TLS checks.
		try {
			if (!ws.connectBlocking(15_000, java.util.concurrent.TimeUnit.MILLISECONDS)) {
				ws.close();
				return false;
			}
		} catch (Exception e) {
			closeQuietly(ws);
			throw new IOException("WebSocket connect to " + host + " failed: " + e, e);
		}
		opened[0] = true;
		// pump reads from the game client only after the tunnel is really up
		Thread pump = new Thread(() -> pumpTcpToWs(ws, tcp, writeLock), "cloudlan-relay-tcp2ws");
		pump.setDaemon(true);
		pump.start();
		return true;
	}

	private static void closeQuietly(WebSocketClient ws) {
		try {
			ws.close();
		} catch (Exception ignored) {
		}
	}

	private static void pumpTcpToWs(WebSocketClient ws, Socket tcp, Object writeLock) {
		byte[] buf = new byte[32 * 1024];
		try {
			InputStream in = tcp.getInputStream();
			int n;
			while ((n = in.read(buf)) >= 0) {
				if (!ws.isOpen()) {
					break;
				}
				byte[] chunk = new byte[n];
				System.arraycopy(buf, 0, chunk, 0, n);
				// Java-WebSocket serializes sends internally; keep ordering with reads.
				ws.send(chunk);
			}
		} catch (IOException ignored) {
		} finally {
			closeAll(ws, tcp);
		}
	}

	private static void closeAll(WebSocketClient ws, Socket tcp) {
		try {
			tcp.close();
		} catch (IOException ignored) {
		}
		try {
			if (ws.isOpen()) {
				ws.close();
			}
		} catch (Exception ignored) {
		}
	}

	public void close() {
		running = false;
		try {
			server.close();
		} catch (IOException ignored) {
		}
		synchronized (activeTcp) {
			for (Socket s : activeTcp) {
				try {
					s.close();
				} catch (IOException ignored) {
				}
			}
			activeTcp.clear();
		}
	}

	/** Global registry of relays keyed by tunnel host. */
	public static final class Registry {
		private static final Map<String, JoinRelay> RELAYS = new ConcurrentHashMap<>();

		/** Returns a running relay for the host (starting one if needed), or -1. */
		public static synchronized int ensure(String tunnelHost, int port) {
			String key = dev.cloudlan.util.TunnelAddresses.sanitize(tunnelHost).toLowerCase(Locale.ROOT);
			JoinRelay existing = RELAYS.get(key);
			if (existing != null) {
				return existing.getLocalPort();
			}
			try {
				JoinRelay relay = new JoinRelay(tunnelHost, port);
				RELAYS.put(key, relay);
				return relay.getLocalPort();
			} catch (IOException e) {
				LOGGER.error("Failed to start relay for {}: {}", tunnelHost, e.toString());
				return -1;
			}
		}

		public static synchronized void closeAll() {
			for (JoinRelay r : RELAYS.values()) {
				r.close();
			}
			RELAYS.clear();
		}
	}
}
