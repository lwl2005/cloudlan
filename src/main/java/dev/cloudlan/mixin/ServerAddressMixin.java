package dev.cloudlan.mixin;

import dev.cloudlan.util.TunnelAddresses;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets players paste the full invite URL (https://xxx.trycloudflare.com/)
 * directly into the multiplayer address box; vanilla would fail to parse it.
 */
@Mixin(ServerAddress.class)
public class ServerAddressMixin {
	@Inject(method = "parseString", at = @At("HEAD"), cancellable = true)
	private static void cloudlan$sanitizeTunnelAddress(String raw, CallbackInfoReturnable<ServerAddress> cir) {
		if (raw == null || !raw.contains("trycloudflare.com")) {
			return;
		}
		String cleaned = TunnelAddresses.sanitize(raw);
		// recursive call is safe: sanitize(cleaned) == cleaned, so we fall through
		if (!cleaned.isEmpty() && !cleaned.equals(raw)) {
			cir.setReturnValue(ServerAddress.parseString(cleaned));
		}
	}
}
