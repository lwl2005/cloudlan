package dev.cloudlan.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Simple properties-based config stored at {@code config/cloudlan.properties}.
 * Uses {@link Properties} instead of a JSON library to stay dependency-free.
 *
 * <p>Keys:</p>
 * <ul>
 *   <li>{@code gamePort} — local port the singleplayer world is published on (default 25565)</li>
 *   <li>{@code cloudflaredPath} — optional explicit path to the cloudflared binary</li>
 * </ul>
 */
public final class CloudLanConfig {
	private static final Logger LOGGER = LoggerFactory.getLogger("cloudlan/config");

	public static final int DEFAULT_GAME_PORT = 25565;

	private static Path configFile;
	private static int gamePort = DEFAULT_GAME_PORT;
	private static String cloudflaredPath = "";

	private CloudLanConfig() {
	}

	public static void load(Path configDir) {
		configFile = configDir.resolve("cloudlan.properties");
		if (!Files.isRegularFile(configFile)) {
			save();
			return;
		}
		Properties props = new Properties();
		try (InputStream in = Files.newInputStream(configFile)) {
			props.load(in);
			gamePort = clampPort(Integer.parseInt(props.getProperty("gamePort", String.valueOf(DEFAULT_GAME_PORT))));
			cloudflaredPath = props.getProperty("cloudflaredPath", "").trim();
		} catch (Exception e) {
			LOGGER.warn("Failed to read {}: {}", configFile, e.toString());
		}
	}

	public static void save() {
		if (configFile == null) {
			return;
		}
		Properties props = new Properties();
		props.setProperty("gamePort", String.valueOf(gamePort));
		props.setProperty("cloudflaredPath", cloudflaredPath);
		try {
			Files.createDirectories(configFile.getParent());
			try (OutputStream out = Files.newOutputStream(configFile)) {
				props.store(out, "CloudLAN configuration");
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to write {}: {}", configFile, e.toString());
		}
	}

	public static int getGamePort() {
		return gamePort;
	}

	public static void setGamePort(int port) {
		gamePort = clampPort(port);
		save();
	}

	public static String getCloudflaredPath() {
		return cloudflaredPath;
	}

	public static void setCloudflaredPath(String path) {
		cloudflaredPath = path == null ? "" : path.trim();
		save();
	}

	private static int clampPort(int port) {
		if (port < 1 || port > 65535) {
			return DEFAULT_GAME_PORT;
		}
		return port;
	}
}
