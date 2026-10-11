package dev.cloudlan.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Manages a cloudflared "quick tunnel" process (no Cloudflare account needed).
 * cloudflared exposes {@code http://127.0.0.1:bridgePort} under a random
 * {@code https://*.trycloudflare.com} address which we parse from its output.
 *
 * <p>This class has no Minecraft dependencies so the E2E self test can drive it.</p>
 */
public final class CloudflaredProcess {
	private static final Logger LOGGER = LoggerFactory.getLogger("cloudlan/tunnel");
	private static final Pattern TUNNEL_URL = Pattern.compile("https://[a-z0-9\\-]+\\.trycloudflare\\.com");
	private static final String SRV_HINT = "本机 DNS 无法查询 SRV 记录（cloudflared 连不上隧道入口）。"
			+ "请把系统 DNS 改为 223.5.5.5 后重试：设置 → 网络和 Internet → 你的网络 → DNS 服务器 → 编辑，手动填 223.5.5.5（备用 223.6.6.6）";

	private final Path binary;
	private final int bridgePort;
	private final LogSink sink;

	private Process process;
	private Thread reader;
	private volatile String domain;
	private volatile String lastError;
	private final AtomicBoolean stopped = new AtomicBoolean(false);

	public interface LogSink {
		void log(String line);
	}

	public CloudflaredProcess(Path binary, int bridgePort, LogSink sink) {
		this.binary = binary;
		this.bridgePort = bridgePort;
		this.sink = sink == null ? line -> {} : sink;
	}

	public String domain() {
		return domain;
	}

	public boolean isRunning() {
		return process != null && process.isAlive();
	}

	/**
	 * Starts cloudflared and blocks until the trycloudflare address is known
	 * (or fails). Called from a background thread, never from the render thread.
	 */
	public void start() throws IOException {
		if (!Files.isExecutable(binary) && !Files.isRegularFile(binary)) {
			throw new IOException("cloudflared 不存在: " + binary);
		}
		List<String> cmd = List.of(
				binary.toAbsolutePath().toString(),
				"tunnel",
				"--url", "http://127.0.0.1:" + bridgePort,
				"--no-autoupdate"
		);
		ProcessBuilder pb = new ProcessBuilder(cmd)
				.redirectErrorStream(true)
				.redirectOutput(ProcessBuilder.Redirect.PIPE);
		Process p = pb.start();
		this.process = p;

		CountDownLatch urlSeen = new CountDownLatch(1);
		AtomicBoolean exited = new AtomicBoolean(false);
		final boolean[] srvFailure = {false};

		reader = new Thread(() -> {
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					if (line.isBlank()) {
						continue;
					}
					String clean = stripAnsi(line);
					sink.log(clean);
					if (clean.contains("Could not lookup srv records")
							|| clean.contains("the DNS query failed")) {
						srvFailure[0] = true;
					}
					Matcher m = TUNNEL_URL.matcher(clean);
					if (m.find() && domain == null) {
						domain = m.group();
						LOGGER.info("Tunnel address acquired: {}", domain);
						urlSeen.countDown();
					}
				}
			} catch (IOException e) {
				if (!stopped.get()) {
					lastError = "读取 cloudflared 输出失败: " + e.getMessage();
					LOGGER.warn("cloudflared output reader failed", e);
				}
			} finally {
				exited.set(true);
				urlSeen.countDown();
			}
		}, "cloudlan-cloudflared-log");
		reader.setDaemon(true);
		reader.start();

		try {
			if (!urlSeen.await(90, TimeUnit.SECONDS)) {
				stop();
				if (srvFailure[0]) {
					throw new IOException(SRV_HINT);
				}
				throw new IOException("等待 cloudflared 返回域名超时（90 秒）");
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			stop();
			throw new IOException("启动被中断");
		}

		if (domain == null) {
			stop();
			String detail = lastError != null ? lastError
					: (exited.get() ? "cloudflared 进程已退出（code=" + p.exitValue() + "）" : "未能从输出中解析到域名");
			if (srvFailure[0]) {
				detail = SRV_HINT;
			}
			throw new IOException("cloudflared 启动失败: " + detail);
		}
	}

	/** Stops the process; safe to call multiple times. */
	public void stop() {
		if (!stopped.compareAndSet(false, true)) {
			return;
		}
		Process p = this.process;
		if (p == null) {
			return;
		}
		p.destroy();
		try {
			if (!p.waitFor(3, TimeUnit.SECONDS)) {
				p.destroyForcibly();
				p.waitFor(3, TimeUnit.SECONDS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			p.destroyForcibly();
		}
	}

	static String stripAnsi(String s) {
		return s.replaceAll("\\u001B\\[[;\\d]*m", "");
	}

	/** Returns the conventional binary file name for the current platform. */
	public static String binaryName() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return os.contains("win") ? "cloudflared.exe" : "cloudflared";
	}
}
