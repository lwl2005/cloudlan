package dev.cloudlan.e2e;

import dev.cloudlan.bridge.BridgeServer;
import dev.cloudlan.tunnel.CloudflaredDownloader;
import dev.cloudlan.tunnel.CloudflaredProcess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Drives the host side of CloudLAN against an ordinary dedicated Minecraft
 * server, for automated join-side verification without any GUI interaction:
 *
 * <pre>
 *   bridge(WS, loopback) → 127.0.0.1:25565 (dedicated server)
 *   cloudflared quick tunnel → wss://xxxx.trycloudflare.com → bridge
 * </pre>
 *
 * The tunnel domain is written to {@code e2e-run/host-domain.txt} once ready.
 * The process keeps running until killed.
 *
 * <p>Usage: {@code gradle hostDriver [-PgamePort=25565]}</p>
 */
public final class HostDriver {
	public static void main(String[] args) throws Exception {
		int gamePort = args.length > 0 ? Integer.parseInt(args[0]) : 25565;
		Path workDir = Path.of("e2e-run");
		Files.createDirectories(workDir);
		Path domainFile = workDir.resolve("host-domain.txt");

		// 1) bridge: websocket → dedicated server
		BridgeServer bridge = new BridgeServer(0, "127.0.0.1", gamePort);
		bridge.start();
		if (!bridge.awaitStarted(10_000)) {
			System.out.println("[host] bridge failed to start");
			System.exit(1);
		}
		System.out.println("[host] bridge on 127.0.0.1:" + bridge.getPort() + " -> " + gamePort);

		// 2) cloudflared (reuses the e2e-downloaded binary when present)
		Path binary = Files.isRegularFile(workDir.resolve(CloudflaredProcess.binaryName()))
				? workDir.resolve(CloudflaredProcess.binaryName())
				: CloudflaredDownloader.ensureBinary(workDir, null);

		AtomicReference<String> domain = new AtomicReference<>();
		CloudflaredProcess tunnel = new CloudflaredProcess(binary, bridge.getPort(),
				line -> System.out.println("[cf] " + line));
		Thread starter = new Thread(() -> {
			try {
				tunnel.start();
				domain.set(tunnel.domain());
			} catch (Exception e) {
				System.out.println("[host] tunnel failed: " + e);
			}
		}, "host-tunnel");
		starter.setDaemon(true);
		starter.start();
		starter.join(120_000);

		if (domain.get() == null) {
			System.out.println("[host] NO DOMAIN");
			System.exit(1);
		}
		Files.writeString(domainFile, domain.get());
		System.out.println("[host] HOST_READY " + domain.get());

		// keep the process (and tunnel) alive until killed
		Thread.currentThread().join();
	}
}
