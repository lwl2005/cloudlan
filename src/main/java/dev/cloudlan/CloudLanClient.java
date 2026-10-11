package dev.cloudlan;

import dev.cloudlan.config.CloudLanConfig;
import dev.cloudlan.gui.CloudLanScreen;
import dev.cloudlan.host.TunnelHost;
import dev.cloudlan.relay.JoinRelay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CloudLAN client entrypoint. Registers the keybind that opens the host window
 * and keeps tunnel state in sync with the game lifecycle.
 */
public final class CloudLanClient implements ClientModInitializer {
	public static final String MOD_ID = "cloudlan";
	public static final Logger LOGGER = LoggerFactory.getLogger("cloudlan");

	private static KeyMapping openKey;

	@Override
	public void onInitializeClient() {
		CloudLanConfig.load(net.fabricmc.loader.api.FabricLoader.getInstance()
				.getConfigDir());

		openKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.cloudlan.open",
				GLFW.GLFW_KEY_O,
				"key.categories.cloudlan"
		));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			while (openKey.consumeClick()) {
				if (client.screen == null) {
					client.setScreen(new CloudLanScreen());
				}
			}
		});

		// Tear everything down when leaving a world or quitting the game.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			TunnelHost.get().stop();
			JoinRelay.Registry.closeAll();
		});
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			TunnelHost.get().stopAll();
			JoinRelay.Registry.closeAll();
		});

		LOGGER.info("CloudLAN initialized (keybind: O)");
	}
}
