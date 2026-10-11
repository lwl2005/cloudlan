package dev.cloudlan.gui;

import dev.cloudlan.config.CloudLanConfig;
import dev.cloudlan.host.TunnelHost;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The in-game host window. Shows the tunnel state, the shareable
 * *.trycloudflare.com address (with a copy button) and recent log lines.
 */
public final class CloudLanScreen extends Screen {
	private Button startButton;
	private Button stopButton;
	private Button copyButton;

	public CloudLanScreen() {
		super(Component.literal("CloudLAN 联机"));
	}

	@Override
	protected void init() {
		int cx = this.width / 2;
		int y = this.height / 2 + 40;

		this.startButton = this.addRenderableWidget(Button.builder(
						Component.literal("开启联机"), b -> TunnelHost.get().start())
				.bounds(cx - 102, y, 100, 20).build());
		this.stopButton = this.addRenderableWidget(Button.builder(
						Component.literal("停止联机"), b -> TunnelHost.get().stop())
				.bounds(cx + 2, y, 100, 20).build());
		this.copyButton = this.addRenderableWidget(Button.builder(
						Component.literal("复制域名"), b -> copyDomain())
				.bounds(cx - 50, y + 24, 100, 20).build());

		this.addRenderableWidget(Button.builder(
						Component.literal("关闭"), b -> this.onClose())
				.bounds(cx - 50, this.height - 28, 100, 20).build());
	}

	private void copyDomain() {
		String d = TunnelHost.get().domain();
		if (!d.isEmpty()) {
			this.minecraft.keyboardHandler.setClipboard("https://" + d + "/");
		}
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
		this.renderBackground(g);
		g.drawCenteredString(this.font, this.title, this.width / 2, 14, 0xFFFFFF);

		TunnelHost host = TunnelHost.get();
		int cx = this.width / 2;

		// status line
		String status = switch (host.state()) {
			case IDLE -> "状态: 未开启";
			case PREPARING -> "状态: 准备中（开放世界/下载组件）...";
			case STARTING_TUNNEL -> "状态: 正在启动 Cloudflare 隧道...";
			case RUNNING -> "状态: 联机中";
			case ERROR -> "状态: 出错 — " + host.error();
		};
		g.drawCenteredString(this.font, status, cx, this.height / 2 - 60,
				host.state() == TunnelHost.State.ERROR ? 0xFF5555 : 0x55FF55);

		// domain
		String domain = host.domain();
		if (!domain.isEmpty()) {
			g.drawCenteredString(this.font,
					Component.literal("把下面的地址发给朋友，他们在“多人游戏”里直接添加即可:"),
					cx, this.height / 2 - 30, 0xFFFF55);
			g.drawCenteredString(this.font, Component.literal("https://" + domain + "/"),
					cx, this.height / 2 - 16, 0x55FFFF);
			g.drawCenteredString(this.font,
					Component.literal("端口: " + host.publishedPort() + "  |  好友也需安装本模组"),
					cx, this.height / 2 - 2, 0xAAAAAA);
		} else {
			g.drawCenteredString(this.font,
					Component.literal("点“开启联机”后会生成一个临时免费域名（每次开启可能不同）"),
					cx, this.height / 2 - 30, 0xAAAAAA);
		}

		// log tail
		List<String> log = host.recentLog();
		int maxLines = Math.max(0, (this.height / 2 + 24 - (this.height / 2 + 10)) / 10);
		int start = Math.max(0, log.size() - Math.min(log.size(), 6));
		int ly = this.height / 2 + 74;
		for (int i = start; i < log.size(); i++) {
			String line = log.get(i);
			if (line.length() > 90) {
				line = "…" + line.substring(line.length() - 89);
			}
			g.drawCenteredString(this.font, Component.literal(line), cx, ly, 0x888888);
			ly += 10;
		}

		// live button state
		if (this.startButton != null) {
			this.startButton.active = host.state() == TunnelHost.State.IDLE || host.state() == TunnelHost.State.ERROR;
			this.stopButton.active = !this.startButton.active;
			this.copyButton.active = !domain.isEmpty();
		}

		super.render(g, mouseX, mouseY, delta);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
