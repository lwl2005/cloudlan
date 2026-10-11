# CloudLAN 一键联机（Minecraft 1.20.1 / Fabric）

把你的**单人世界**变成一个朋友可以直接加入的地址：在游戏里按一个键打开窗口，点“开启联机”，模组会自动

1. 把当前单人世界开放到本地端口（默认 25565）；
2. 启动一个本地 WebSocket ↔ TCP 桥；
3. 启动 [cloudflared](https://github.com/cloudflare/cloudflared) **免费临时隧道**（无需注册 Cloudflare 账号）；
4. 生成一个 `https://xxxx-xxxx.trycloudflare.com/` 的临时域名，点“复制域名”发给朋友。

朋友**在多人游戏里直接添加这个域名**（粘贴完整网址也行），即可看到你的世界状态并进入游戏。

## 重要：Cloudflare 免费隧道的限制（必读）

Cloudflare 的免费临时隧道只承载 **HTTP/WebSocket** 流量，不支持原版 Minecraft 的裸 TCP 直连（那需要付费的 Spectrum）。所以本模组的做法是：

- **双方都要安装本模组**。朋友的模组会在他本机把「到域名的 WebSocket 连接」桥接成「到游戏的 TCP 连接」，对游戏本身完全透明——多人游戏界面、进服流程和原版一模一样。
- 域名是**临时的**：每次点“开启联机”都会生成新域名，Cloudflare 重启隧道后也会变。发最新的给朋友即可。
- 流量经由 Cloudflare 全球网络中转，延迟比直连高（一般多几十毫秒），休闲联机完全够用，不适合高强度 PVP。
- 免费隧道没有 SLA，长时间挂机偶发断线属正常现象；断线后重新“开启联机”把新域名发给朋友即可。

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/use/installer/)（Minecraft 1.20.1）。
2. 把本模组 jar 和 [Fabric API](https://modrinth.com/mod/fabric-api)（1.20.1 版本）放进 `.minecraft/mods/`。
3. 启动游戏，**不需要任何额外下载**：cloudflared 已内置在 jar 里，首次“开启联机”时自动释放到 `.minecraft/cloudlan/`（约 50 MB），之后完全离线可用。Linux / macOS 用户首次会联网补下对应平台的 cloudflared（GitHub 源自动回退镜像）。

## 使用

### 房主（开房的人）

1. 进入一个**单人世界**；
2. 按 **O 键**（可在 按键设置 → CloudLAN 中修改）打开 CloudLAN 窗口；
3. 点 **开启联机**，等状态变成“联机中”；
4. 点 **复制域名**，把 `https://xxxx-xxxx.trycloudflare.com/` 发给朋友；
5. 朋友进服后窗口会显示连接数；点 **停止联机** 或退出世界即关闭。

### 加入方（朋友）

1. 同样安装本模组（和 Fabric API）；
2. 多人游戏 → 添加服务器，地址栏**直接粘贴房主发来的域名**（完整网址、裸域名都可以）；
3. 刷新即可看到房主世界的状态（在线人数、延迟），点进去就能玩。

不需要配置端口转发、不需要公网 IP、不需要部署服务器——域名本身就是“内网通路”。

## 配置文件

`config/cloudlan.properties`：

| 键 | 默认值 | 说明 |
| --- | --- | --- |
| `gamePort` | `25565` | 世界开放的本地端口。被占用导致开启失败时改这个 |
| `cloudflaredPath` | （空） | cloudflared 可执行文件路径；留空则自动下载到 `.minecraft/cloudlan/` |

## 常见问题

**Q: 点“开启联机”报“请把系统 DNS 改为 223.5.5.5”/ 日志里 cloudflared 报 SRV 解析失败？**
cloudflared 需要查询 DNS 的 **SRV 记录**（`_v2-origintunneld._tcp.argotunnel.com`）来定位隧道入口，部分运营商 DNS / 路由器不返回 SRV 记录，隧道就起不来。修复（管理员 PowerShell 执行，阿里公共 DNS，免费且支持 SRV）：

```powershell
# 给所有在用网卡设置 DNS（阿里公共 DNS）
Get-NetAdapter | Where-Object Status -eq Up | ForEach-Object {
  Set-DnsClientServerAddress -InterfaceIndex $_.InterfaceIndex -ServerAddresses 223.5.5.5,223.6.6.6 }
# 验证：应返回 region1/region2.v2.argotunnel.com 7844
Resolve-DnsName -Type SRV _v2-origintunneld._tcp.argotunnel.com
```

恢复为自动获取：`Set-DnsClientServerAddress -InterfaceIndex <序号> -ResetServerAddresses`。

**Q: 点“开启联机”报“端口被占用”？**
本机 25565 被其它程序（比如开着的 MC 服务端）占用。改 `gamePort` 为其它端口（如 `25566`）。

**Q: 朋友添加域名后显示“未知主机”？**
99% 是朋友没装本模组或没装 Fabric API。确认双方都是 Fabric 1.20.1 且模组版本一致。

**Q: cloudflared 还需要下载吗？**
Windows x64 不需要——已内置在 jar 里，首次“开启联机”自动释放（约 4 秒）。仅 Linux / macOS（或释放失败时）才回退到网络下载，多源自动切换；也可以手动下载后在 `config/cloudlan.properties` 填 `cloudflaredPath`。

**Q: 域名之前能用，现在连不上了？**
临时隧道的域名会变。让房主重新“开启联机”并发新域名。

**Q: 能同时多个朋友进吗？**
可以，每个加入方各自建立独立连接，人数受你世界“开放到局域网”时的上限限制（默认 8 人，可在服务器设置里调整 max players）。

## 技术原理（简图）

```
朋友的游戏 ⇄ 朋友本机中继(WebSocket客户端) ⇄ Cloudflare边缘网络
        ⇄ 房主的 cloudflared ⇄ 房主本机WS↔TCP桥 ⇄ 房主的单人世界(本地端口)
```

两端的桥接都在本机回环地址上运行，不对外暴露任何端口；对外唯一的入口就是 Cloudflare 的临时域名。

## 开发与自测

```bash
# 构建（需要 JDK 25 运行 Gradle，产物兼容 Java 17+）
gradle build        # 产物在 build/libs/cloudlan-1.0.0.jar

# 端到端自测：不启动 Minecraft，真实走一遍
#   本地TCP → WS桥 → cloudflared免费隧道 → Cloudflare → 中继 → 回显校验
gradle e2e -Pcloudflared=<cloudflared可执行文件路径>
```

自测通过会输出 `ALL TESTS PASSED`，验证 4MB 随机字节流经真实公网隧道后逐字节一致、并发连接正常。

## 许可

MIT
