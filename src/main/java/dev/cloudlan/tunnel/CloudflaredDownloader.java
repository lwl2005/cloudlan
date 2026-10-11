package dev.cloudlan.tunnel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Provides the cloudflared binary on first use. Preference order:
 * <ol>
 *   <li>already on disk ({@code .minecraft/cloudlan/cloudflared(.exe)})</li>
 *   <li><b>bundled resource</b> — Windows amd64 ships inside the mod jar, so
 *       no network access is needed at all;</li>
 *   <li>network download from GitHub + mirrors (other platforms, or when the
 *       bundled copy is unavailable).</li>
 * </ol>
 * No Minecraft dependencies on purpose — reused by the E2E self test.
 */
public final class CloudflaredDownloader {
	private static final Logger LOGGER = LoggerFactory.getLogger("cloudlan/download");

	/** Classpath resource of the bundled Windows amd64 binary, or {@code null}. */
	private static final String BUNDLED_RESOURCE = bundledResource();

	public interface ProgressSink {
		void progress(long received, long total);

		void status(String message);
	}

	private CloudflaredDownloader() {
	}

	public static Path ensureBinary(Path targetDir, ProgressSink sink) throws IOException {
		Files.createDirectories(targetDir);
		Path target = targetDir.resolve(CloudflaredProcess.binaryName());
		if (Files.isRegularFile(target) && Files.size(target) > 1_000_000) {
			return target;
		}
		if (BUNDLED_RESOURCE != null) {
			try (InputStream in = CloudflaredDownloader.class.getResourceAsStream(BUNDLED_RESOURCE)) {
				if (in != null) {
					LOGGER.info("Extracting bundled cloudflared from {}", BUNDLED_RESOURCE);
					if (sink != null) {
						sink.status("正在释放内置 cloudflared（无需下载）...");
					}
					Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
					target.toFile().setExecutable(true, false);
					return target;
				}
			} catch (IOException e) {
				LOGGER.warn("Failed to extract bundled cloudflared, falling back to download: {}", e.toString());
				if (sink != null) {
					sink.status("内置释放失败，改用网络下载: " + e.getMessage());
				}
				Files.deleteIfExists(target);
			}
		}
		if (sink != null) {
			sink.status("正在下载 cloudflared（约 50 MB）...");
		}
		IOException last = null;
		for (String url : candidateUrls()) {
			try {
				LOGGER.info("Downloading cloudflared from {}", url);
				download(url, target, sink);
				if (sink != null) {
					sink.status("cloudflared 下载完成");
				}
				return target;
			} catch (IOException e) {
				last = e;
				LOGGER.warn("Download from {} failed: {}", url, e.toString());
				if (sink != null) {
					sink.status("下载源失败，切换下一个: " + e.getMessage());
				}
			}
		}
		throw last != null ? last : new IOException("没有可用的 cloudflared 下载源");
	}

	/** Returns the bundled resource path for this platform, or {@code null}. */
	private static String bundledResource() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm");
		if (os.contains("win") && !arm) {
			return "/cloudlan/bin/cloudflared-windows-amd64.exe";
		}
		return null;
	}

	static List<String> candidateUrls() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean arm = arch.contains("aarch64") || arch.contains("arm");

		String asset;
		if (os.contains("win")) {
			asset = arm ? "cloudflared-windows-arm64.exe" : "cloudflared-windows-amd64.exe";
		} else if (os.contains("mac")) {
			asset = arm ? "cloudflared-darwin-arm64.tgz" : "cloudflared-darwin-amd64.tgz";
		} else {
			asset = arm ? "cloudflared-linux-arm64" : "cloudflared-linux-amd64";
		}
		String github = "https://github.com/cloudflare/cloudflared/releases/latest/download/" + asset;
		return List.of(
				github,
				"https://ghfast.top/https://github.com/cloudflare/cloudflared/releases/latest/download/" + asset,
				"https://gh-proxy.com/https://github.com/cloudflare/cloudflared/releases/latest/download/" + asset
		);
	}

	private static void download(String urlStr, Path target, ProgressSink sink) throws IOException {
		if (urlStr.endsWith(".tgz")) {
			throw new IOException("该平台需要 .tgz 请手动安装 cloudflared 并在配置中指定路径");
		}
		Path tmp = target.resolveSibling(target.getFileName() + ".part");
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setConnectTimeout(15_000);
		conn.setReadTimeout(30_000);
		conn.setInstanceFollowRedirects(true);
		conn.connect();
		int code = conn.getResponseCode();
		if (code != 200) {
			conn.disconnect();
			throw new IOException("HTTP " + code);
		}
		long total = conn.getContentLengthLong();
		long received = 0;
		long lastReport = 0;
		try (InputStream in = conn.getInputStream(); OutputStream out = Files.newOutputStream(tmp)) {
			byte[] buf = new byte[1 << 16];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				received += n;
				if (sink != null && received - lastReport >= 2 << 20) {
					lastReport = received;
					sink.progress(received, total);
				}
			}
		} finally {
			conn.disconnect();
		}
		if (received < 1_000_000) {
			Files.deleteIfExists(tmp);
			throw new IOException("下载内容过小（" + received + " 字节），疑似失败页面");
		}
		Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
		target.toFile().setExecutable(true, false);
	}
}
