package dev.cloudlan.mixin;

import dev.cloudlan.relay.JoinRelay;
import dev.cloudlan.util.TunnelAddresses;
import net.minecraft.client.multiplayer.resolver.ResolvedServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.resolver.ServerNameResolver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Optional;

/**
 * The core join-side hook: whenever the game resolves a *.trycloudflare.com
 * server address (both the server-list ping and the actual join go through
 * {@code ServerNameResolver.resolveAddress}), we spin up a local relay that
 * tunnels the connection through WebSocket to the Cloudflare edge, and hand
 * the game a loopback address pointing at that relay.
 */
@Mixin(ServerNameResolver.class)
public class ServerNameResolverMixin {
	@Inject(method = "resolveAddress", at = @At("HEAD"), cancellable = true)
	private void cloudlan$redirectTunnel(ServerAddress address, CallbackInfoReturnable<Optional<ResolvedServerAddress>> cir) {
		String host = address.getHost();
		if (!TunnelAddresses.isTunnelHost(host)) {
			return;
		}
		int localPort = JoinRelay.Registry.ensure(host, address.getPort());
		if (localPort > 0) {
			cir.setReturnValue(Optional.of(ResolvedServerAddress.from(
					new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort))));
		}
		// relay failed to start: fall through to vanilla (will report unknown host)
	}
}
