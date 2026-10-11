package dev.cloudlan.util;

import java.util.Locale;
import java.util.Set;

/**
 * Helpers for deciding whether a server address belongs to a CloudLAN tunnel.
 * Kept free of Minecraft classes so the end-to-end self test can reuse it.
 */
public final class TunnelAddresses {
	/** Suffixes treated as tunnels by default; extensible via config. */
	private static volatile Set<String> extraSuffixes = Set.of();

	private TunnelAddresses() {
	}

	public static void setExtraSuffixes(Set<String> suffixes) {
		extraSuffixes = suffixes;
	}

	public static boolean isTunnelHost(String host) {
		if (host == null) {
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT).trim();
		if (h.endsWith(".trycloudflare.com") || h.equals("trycloudflare.com")) {
			return true;
		}
		for (String suffix : extraSuffixes) {
			String s = suffix.toLowerCase(Locale.ROOT).trim();
			if (!s.isEmpty() && (h.equals(s) || h.endsWith(s))) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Strips scheme, path and surrounding whitespace from a pasted address so that
	 * users can paste the full invite URL (https://xxx.trycloudflare.com/) directly.
	 */
	public static String sanitize(String raw) {
		if (raw == null) {
			return "";
		}
		String s = raw.trim();
		int scheme = s.indexOf("://");
		if (scheme >= 0) {
			s = s.substring(scheme + 3);
		}
		int slash = s.indexOf('/');
		if (slash >= 0) {
			s = s.substring(0, slash);
		}
		return s.trim();
	}

	/** Splits "host[:port]" into host (lower-case, sanitized). */
	public static String hostOf(String sanitizedAddress) {
		if (sanitizedAddress == null || sanitizedAddress.isEmpty()) {
			return "";
		}
		int colon = sanitizedAddress.lastIndexOf(':');
		// Ignore IPv6-style or port-less confusion: only treat as port when the
		// suffix is all digits (trycloudflare hosts never contain ':' otherwise).
		if (colon > 0) {
			String maybePort = sanitizedAddress.substring(colon + 1);
			if (maybePort.chars().allMatch(Character::isDigit)) {
				return sanitizedAddress.substring(0, colon).toLowerCase(Locale.ROOT);
			}
		}
		return sanitizedAddress.toLowerCase(Locale.ROOT);
	}

	/** Parses the port from a sanitized address, or {@code fallback} when absent/absurd. */
	public static int portOf(String sanitizedAddress, int fallback) {
		int colon = sanitizedAddress == null ? -1 : sanitizedAddress.lastIndexOf(':');
		if (colon > 0) {
			String maybePort = sanitizedAddress.substring(colon + 1);
			if (maybePort.chars().allMatch(Character::isDigit)) {
				try {
					int p = Integer.parseInt(maybePort);
					if (p > 0 && p <= 65535) {
						return p;
					}
				} catch (NumberFormatException ignored) {
				}
			}
		}
		return fallback;
	}
}
