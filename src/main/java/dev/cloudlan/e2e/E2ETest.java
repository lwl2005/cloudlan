package dev.cloudlan.e2e;

import dev.cloudlan.bridge.BridgeServer;
import dev.cloudlan.relay.JoinRelay;
import dev.cloudlan.tunnel.CloudflaredDownloader;
import dev.cloudlan.tunnel.CloudflaredProcess;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * End-to-end self test of the full CloudLAN data path, without Minecraft:
 *
 * <pre>
 *   test TCP client → JoinRelay (local) → wss://…trycloudflare.com →
 *   Cloudflare edge → cloudflared → BridgeServer (local) → echo TCP server
 * </pre>
 *
 * Validates that a Cloudflare quick tunnel really carries arbitrary binary
 * streams (Minecraft's protocol) in both directions, end to end.
 *
 * <p>Usage: {@code gradlew e2e -Pcloudflared=<path-to-cloudflared>}</p>
 */
public final class E2ETest {
	private static final SecureRandom RNG = new SecureRandom();
	private static int failures = 0;

	public static void main(String[] args) throws Exception {
		Path workDir = Path.of("e2e-run");
		Files.createDirectories(workDir);

		// --- 0) locate or download cloudflared -----------------------------
		Path binary;
		if (args.length > 0 && !args[0].isBlank()) {
			binary = Path.of(args[0]);
			System.out.println("[e2e] using cloudflared: " + binary.toAbsolutePath());
		} else {
			binary = CloudflaredDownloader.ensureBinary(workDir, new CloudflaredDownloader.ProgressSink() {
				@Override
				public void progress(long received, long total) {
					if (total > 0) {
						System.out.printf("[e2e] downloading cloudflared %d%%%n", received * 100 / total);
					}
				}

				@Override
				public void status(String message) {
					System.out.println("[e2e] " + message);
				}
			});
		}

		// cloudflared needs the SRV record below for edge discovery — check first
		preflightSrvCheck();

		// --- 1) fake "Minecraft server": TCP echo on an ephemeral port ------
		try (ServerSocket echoServer = new ServerSocket(0, 32, InetAddress.getLoopbackAddress())) {
			int echoPort = echoServer.getLocalPort();
			Thread echoThread = new Thread(() -> echoLoop(echoServer), "e2e-echo");
			echoThread.setDaemon(true);
			echoThread.start();
			System.out.println("[e2e] echo server on 127.0.0.1:" + echoPort);

			// --- 2) host-side bridge: WS server → echo server ---------------
			BridgeServer bridge = new BridgeServer(0, "127.0.0.1", echoPort);
			bridge.start();
			if (!bridge.awaitStarted(10_000)) {
				fail("bridge did not start");
				System.exit(1);
			}
			System.out.println("[e2e] bridge on 127.0.0.1:" + bridge.getPort());

			// --- 3) cloudflared quick tunnel → domain ------------------------
			AtomicReference<String> domainRef = new AtomicReference<>();
			CloudflaredProcess tunnel = new CloudflaredProcess(binary, bridge.getPort(), line ->
					System.out.println("[cf] " + line));
			Thread starter = new Thread(() -> {
				try {
					tunnel.start();
					domainRef.set(tunnel.domain());
				} catch (Exception e) {
					System.out.println("[e2e] tunnel start failed: " + e);
				}
			}, "e2e-tunnel");
			starter.setDaemon(true);
			starter.start();
			starter.join(120_000);
			String domain = domainRef.get();
			if (domain == null) {
				fail("no trycloudflare domain obtained");
				System.exit(1);
			}
			System.out.println("[e2e] tunnel domain: " + domain);

			// wait for the fresh hostname to become resolvable (propagation)
			String bareHost = domain.replaceFirst("^https://", "");
			if (!waitForDns(bareHost, 60_000)) {
				fail("tunnel hostname never resolved: " + bareHost);
				System.exit(1);
			}
			System.out.println("[e2e] tunnel hostname resolves: " + bareHost);

			// --- 4) joiner-side relay ----------------------------------------
			int relayPort = JoinRelay.Registry.ensure(bareHost, 443);
			if (relayPort <= 0) {
				fail("join relay failed to start");
				System.exit(1);
			}
			System.out.println("[e2e] relay on 127.0.0.1:" + relayPort);

			// --- 5) tests through the full path ------------------------------
			runTest("big stream", () -> bigStreamTest(relayPort, 4 * 1024 * 1024));
			runTest("latency", () -> latencyTest(relayPort, 20));
			runTest("concurrent connections", () -> secondConnectionTest(relayPort));

			// --- 6) teardown --------------------------------------------------
			JoinRelay.Registry.closeAll();
			tunnel.stop();
			bridge.stop(1000);
		}

		if (failures == 0) {
			System.out.println("[e2e] ALL TESTS PASSED");
			System.exit(0);
		} else {
			System.out.println("[e2e] " + failures + " TEST(S) FAILED");
			System.exit(1);
		}
	}

	@FunctionalInterface
	private interface CheckedRunnable {
		void run() throws Exception;
	}

	/** Runs one test case, converting any failure into a reported failure. */
	private static void runTest(String name, CheckedRunnable test) {
		try {
			test.run();
		} catch (Throwable t) {
			fail(name + ": " + t);
		}
	}

	/**
	 * cloudflared locates its edge via {@code SRV _v2-origintunneld._tcp.argotunnel.com}.
	 * Some ISP/resolver DNS chains drop SRV queries, which kills the tunnel
	 * before it starts. Detect that up front so the report is unambiguous.
	 */
	private static void preflightSrvCheck() {
		System.out.println("[e2e] pre-flight: checking SRV _v2-origintunneld._tcp.argotunnel.com ...");
		try {
			Process p = new ProcessBuilder("nslookup", "-type=SRV", "_v2-origintunneld._tcp.argotunnel.com")
					.redirectErrorStream(true)
					.start();
			String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			p.waitFor(15, TimeUnit.SECONDS);
			if (out.contains("SRV") && !out.contains("No Service")) {
				System.out.println("[e2e] pre-flight SRV OK");
			} else {
				System.out.println("[e2e] WARN: local DNS cannot answer the SRV query cloudflared needs.");
				System.out.println("[e2e] WARN: tunnel will fail; fix by setting this machine's DNS to 223.5.5.5 first.");
				fail("local DNS does not serve SRV records (set DNS to 223.5.5.5 and re-run)");
			}
		} catch (Exception e) {
			System.out.println("[e2e] pre-flight SRV check skipped: " + e);
		}
	}

	/**
	 * A freshly created quick-tunnel hostname may take a few seconds to become
	 * resolvable; poll until it does so the relay doesn't burn retries on it.
	 */
	private static boolean waitForDns(String host, long timeoutMs) {
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			try {
				InetAddress.getAllByName(host);
				return true;
			} catch (Exception e) {
				try {
					Thread.sleep(2_000);
				} catch (InterruptedException ie) {
					Thread.currentThread().interrupt();
					return false;
				}
			}
		}
		return false;
	}

	private static void echoLoop(ServerSocket server) {
		while (true) {
			Socket client;
			try {
				client = server.accept();
			} catch (Exception e) {
				return;
			}
			Thread t = new Thread(() -> {
				try (Socket s = client) {
					s.setTcpNoDelay(true);
					InputStream in = s.getInputStream();
					OutputStream out = s.getOutputStream();
					byte[] buf = new byte[32 * 1024];
					int n;
					while ((n = in.read(buf)) >= 0) {
						out.write(buf, 0, n);
						out.flush();
					}
				} catch (Exception ignored) {
				}
			}, "e2e-echo-conn");
			t.setDaemon(true);
			t.start();
		}
	}

	/** Sends {@code size} random bytes and verifies the echoed stream byte-for-byte. */
	private static void bigStreamTest(int relayPort, int size) throws Exception {
		byte[] payload = new byte[size];
		RNG.nextBytes(payload);
		long t0 = System.nanoTime();
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), relayPort)) {
			s.setTcpNoDelay(true);
			s.setSoTimeout(60_000);
			OutputStream out = s.getOutputStream();
			InputStream in = s.getInputStream();

			CountDownLatch readerDone = new CountDownLatch(1);
			byte[] received = new byte[size];
			Thread reader = new Thread(() -> {
				try {
					int off = 0;
					while (off < size) {
						int n = in.read(received, off, size - off);
						if (n < 0) {
							break;
						}
						off += n;
					}
					if (off != size) {
						System.out.println("[e2e] big stream: short read, got " + off + " of " + size);
					}
				} catch (Exception e) {
					System.out.println("[e2e] big stream read error: " + e);
				} finally {
					readerDone.countDown();
				}
			}, "e2e-reader");
			reader.setDaemon(true);
			reader.start();

			// write in 32 KiB chunks, like the bridge does
			for (int off = 0; off < size; off += 32 * 1024) {
				int len = Math.min(32 * 1024, size - off);
				out.write(payload, off, len);
			}
			out.flush();
			if (!readerDone.await(90, TimeUnit.SECONDS)) {
				fail("big stream timed out");
				return;
			}
			long ms = (System.nanoTime() - t0) / 1_000_000;
			if (Arrays.equals(payload, received)) {
				System.out.printf("[e2e] PASS big stream: %d bytes round-trip in %d ms (%.1f KB/s)%n",
						size, ms, ms > 0 ? (size / 1024.0) / (ms / 1000.0) : -1);
			} else {
				for (int i = 0; i < size; i++) {
					if (payload[i] != received[i]) {
						fail("big stream mismatch at byte " + i);
						break;
					}
				}
			}
		}
	}

	/** Small message round-trips to measure added tunnel latency. */
	private static void latencyTest(int relayPort, int rounds) throws Exception {
		try (Socket s = new Socket(InetAddress.getLoopbackAddress(), relayPort)) {
			s.setSoTimeout(30_000);
			OutputStream out = s.getOutputStream();
			InputStream in = s.getInputStream();
			long total = 0;
			for (int i = 0; i < rounds; i++) {
				byte[] msg = ("ping-" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8);
				long t0 = System.nanoTime();
				out.write(msg);
				out.flush();
				byte[] buf = new byte[msg.length];
				int off = 0;
				while (off < msg.length) {
					int n = in.read(buf, off, msg.length - off);
					if (n < 0) {
						throw new IllegalStateException("connection closed during latency test");
					}
					off += n;
				}
				total += (System.nanoTime() - t0) / 1_000_000;
			}
			System.out.printf("[e2e] PASS latency: avg %d ms over %d rounds (through Cloudflare)%n",
					total / rounds, rounds);
		}
	}

	/** A second concurrent connection must work independently of the first. */
	private static void secondConnectionTest(int relayPort) throws Exception {
		try (Socket s1 = new Socket(InetAddress.getLoopbackAddress(), relayPort);
			 Socket s2 = new Socket(InetAddress.getLoopbackAddress(), relayPort)) {
			s1.setSoTimeout(30_000);
			s2.setSoTimeout(30_000);
			roundTrip(s1.getOutputStream(), s1.getInputStream(), "conn-A".getBytes());
			roundTrip(s2.getOutputStream(), s2.getInputStream(), "conn-B".getBytes());
			roundTrip(s1.getOutputStream(), s1.getInputStream(), "conn-A-again".getBytes());
			System.out.println("[e2e] PASS concurrent connections");
		}
	}

	private static void roundTrip(OutputStream out, InputStream in, byte[] msg) throws Exception {
		out.write(msg);
		out.flush();
		byte[] buf = new byte[msg.length];
		int off = 0;
		while (off < msg.length) {
			int n = in.read(buf, off, msg.length - off);
			if (n < 0) {
				throw new IllegalStateException("connection closed");
			}
			off += n;
		}
		if (!Arrays.equals(msg, buf)) {
			throw new IllegalStateException("echo mismatch: " + new String(msg));
		}
	}

	private static void fail(String message) {
		failures++;
		System.out.println("[e2e] FAIL " + message);
	}
}
