package dev.cloudlan.host;

import dev.cloudlan.bridge.BridgeServer;
import dev.cloudlan.config.CloudLanConfig;
import dev.cloudlan.tunnel.CloudflaredDownloader;
import dev.cloudlan.tunnel.CloudflaredProcess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.GameType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Orchestrates the whole host-side flow:
 * publish the singleplayer world on a LAN port → start the local WS↔TCP bridge →
 * launch cloudflared quick tunnel → surface the *.trycloudflare.com address.
 *
 * <p>All blocking work runs on a dedicated worker thread; the GUI only reads
 * {@link #state()}, {@link #domain()} and {@link #recentLog()}.</p>
 */
public final class TunnelHost {
	private static final Logger LOGGER = LoggerFactory.getLogger("cloudlan/host");
	private static final TunnelHost INSTANCE = new TunnelHost();

	public enum State { IDLE, PREPARING, STARTING_TUNNEL, RUNNING, ERROR }

	private final AtomicReference<State> state = new AtomicReference<>(State.IDLE);
	private final AtomicReference<String> domain = new AtomicReference<>("");
	private final AtomicReference<String> error = new AtomicReference<>("");
	private final Deque<String> log = new ArrayDeque<>();
	private final List<Runnable> stateListeners = new CopyOnWriteArrayList<>();

	private volatile BridgeServer bridge;
	private volatile CloudflaredProcess tunnel;
	private volatile Thread worker;
	private volatile int publishedPort = -1;

	private TunnelHost() {
	}

	public static TunnelHost get() {
		return INSTANCE;
	}

	public State state() {
		return state.get();
	}

	public String domain() {
		return domain.get();
	}

	public String error() {
		return error.get();
	}

	public int publishedPort() {
		return publishedPort;
	}

	/** Returns a snapshot of the most recent log lines (newest last). */
	public synchronized List<String> recentLog() {
		return List.copyOf(log);
	}

	public void addStateListener(Runnable r) {
		stateListeners.add(r);
	}

	public void removeStateListener(Runnable r) {
		stateListeners.remove(r);
	}

	private void fireStateChanged() {
		for (Runnable r : stateListeners) {
			try {
				r.run();
			} catch (Exception ignored) {
			}
		}
	}

	private synchronized void appendLog(String line) {
		if (line == null || line.isBlank()) {
			return;
		}
		log.addLast(line);
		while (log.size() > 40) {
			log.pollFirst();
		}
	}

	/** Kicks off hosting on a background thread. No-op unless IDLE or ERROR. */
	public void start() {
		if (!state.compareAndSet(State.IDLE, State.PREPARING)
				&& !state.compareAndSet(State.ERROR, State.PREPARING)) {
			return;
		}
		error.set("");
		fireStateChanged();
		Thread t = new Thread(this::startBlocking, "cloudlan-host");
		t.setDaemon(true);
		worker = t;
		t.start();
	}

	private void startBlocking() {
		try {
			Minecraft mc = Minecraft.getInstance();
			IntegratedServer server = mc.getSingleplayerServer();
			if (server == null) {
				throw new IllegalStateException("请先进入一个单人世界，再开启联机");
			}

			// 1) Publish the world on the configured LAN port (server thread!).
			int port = CloudLanConfig.getGamePort();
			appendLog("正在开放世界到端口 " + port + " ...");
			CountDownLatch published = new CountDownLatch(1);
			boolean[] ok = {false};
			server.execute(() -> {
				try {
					if (!server.isPublished()) {
						GameType gt = server.getWorldData().getGameType();
						boolean cmds = server.getWorldData().getAllowCommands();
						ok[0] = server.publishServer(gt, cmds, port);
					} else {
						ok[0] = true;
					}
				} catch (Exception e) {
					LOGGER.error("publishServer failed", e);
					ok[0] = false;
				} finally {
					published.countDown();
				}
			});
			if (!published.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("开放世界超时");
			}
			if (!ok[0]) {
				throw new IllegalStateException("开放世界失败：端口 " + port + " 可能被占用（可在 config/cloudlan.properties 修改 gamePort）");
			}
			publishedPort = port;
			appendLog("世界已开放 (127.0.0.1:" + port + ")");

			// 2) Local WS↔TCP bridge that cloudflared will point at.
			setState(State.PREPARING);
			BridgeServer b = new BridgeServer(0, "127.0.0.1", port);
			b.start();
			if (!b.awaitStarted(10_000)) {
				throw new IllegalStateException("本地桥接服务启动超时");
			}
			bridge = b;
			appendLog("本地桥接已启动 (端口 " + b.getPort() + ")");

			// 3) cloudflared binary (auto-download on first use).
			Path binDir = mc.gameDirectory.toPath().resolve("cloudlan");
			String custom = CloudLanConfig.getCloudflaredPath();
			Path binary;
			if (!custom.isEmpty()) {
				binary = Path.of(custom);
				appendLog("使用指定 cloudflared: " + custom);
			} else {
				binary = CloudflaredDownloader.ensureBinary(binDir, new CloudflaredDownloader.ProgressSink() {
					@Override
					public void progress(long received, long total) {
						int pct = total > 0 ? (int) (received * 100 / total) : -1;
						appendLog(pct >= 0 ? ("下载 cloudflared: " + pct + "%") : ("已下载 " + (received >> 20) + " MB"));
						fireStateChanged();
					}

					@Override
					public void status(String message) {
						appendLog(message);
						fireStateChanged();
					}
				});
			}

			// 4) Launch the quick tunnel and wait for the domain.
			setState(State.STARTING_TUNNEL);
			appendLog("正在启动 Cloudflare 临时隧道 ...");
			CloudflaredProcess proc = new CloudflaredProcess(binary, b.getPort(), line -> {
				appendLog(line);
				fireStateChanged();
			});
			tunnel = proc;
			proc.start();
			String d = proc.domain();
			if (d == null || d.isBlank()) {
				throw new IllegalStateException("未能取得隧道域名");
			}
			domain.set(d);
			setState(State.RUNNING);
			appendLog("联机已开启: " + d);
			LOGGER.info("Hosting via {}", d);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("已中断");
		} catch (Exception e) {
			LOGGER.error("Host start failed", e);
			fail(e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	private void fail(String message) {
		error.set(message);
		state.set(State.ERROR);
		appendLog("错误: " + message);
		cleanup();
		fireStateChanged();
	}

	private void setState(State s) {
		state.set(s);
		fireStateChanged();
	}

	/** Stops hosting; safe to call at any time (including on game exit). */
	public void stop() {
		if (state.get() == State.IDLE) {
			return;
		}
		cleanup();
		state.set(State.IDLE);
		domain.set("");
		appendLog("联机已停止");
		fireStateChanged();
	}

	/** Stops hosting if it is active — used when the client shuts down. */
	public void stopAll() {
		stop();
	}

	private void cleanup() {
		CloudflaredProcess t = tunnel;
		tunnel = null;
		if (t != null) {
			t.stop();
		}
		BridgeServer b = bridge;
		bridge = null;
		if (b != null) {
			try {
				b.stop(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Exception ignored) {
			}
		}
		publishedPort = -1;
	}
}
