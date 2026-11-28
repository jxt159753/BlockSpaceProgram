# 网页轨道预览

浏览器可以查看母星、卫星和它的影响球、飞船轨道、近点和远点高度、周期、当前海拔及已提交的机动节点。选择飞船查看详情，切换视图中心及缩放可以查看各天体附近的轨道。

拖动时间轴，用当前轨道根数预览最多 4 小时后的位置。时间轴只影响当前页面。维度内飞船显示最近的位置样本，服务器约每秒采样一次。

## 开启

1. 先启动并关闭专用服务器一次，生成 `config/blockspaceprogram-server.toml`。
2. 将 `[webPreview]` 中的 `enabled` 改成 `true`，设置所需的可见范围，然后重新启动服务器。
3. 在服务器本机打开 `http://127.0.0.1:8765/`。页面和计算脚本随模组 JAR 发布。

| 设置 | 默认值 | 用途 |
| --- | --- | --- |
| `enabled` | `false` | 开启网页和实时推送 |
| `bindAddress` | `"127.0.0.1"` | HTTP 监听地址，默认仅本机连接 |
| `port` | `8765` | HTTP 端口 |
| `visibility` | `"PUBLIC"` | `"PUBLIC"` 只发布船主已公开的船，`"ALL"` 发布所有可定位飞船及其船名 |
| `maxConnections` | `16` | 同时接收实时推送的浏览器连接上限，范围 1–256 |

Java 21 运行时需要包含 `jdk.httpserver`。可以用服务器实际运行的 `java --list-modules` 核对。已核对 Mojang 分发的 Windows x64 Java 21.0.7 模块镜像。Prism Launcher 可以自动下载 Mojang 的运行时。 [Mojang 运行时清单](https://piston-meta.mojang.com/v1/packages/cb4394a27089d19f65d5baa6cf0482c27c3c7865/manifest.json) · [Prism Launcher Java 设置](https://prismlauncher.org/wiki/help-pages/java-settings/)

## 公开飞船

船主在游戏聊天框运行 `/bsp_web_preview <飞船 UUID> public`。输入命令后按 Tab 可选自己的已加载及在轨飞船。将末尾改成 `private` 可撤回公开标记。

公开记录随存档保存，同一艘飞船在维度与轨道之间交接时保留设置。使用 `visibility = "ALL"` 的服务器会向所有网页访客发布飞船信息。

## 远程访问

建议将模组保持监听在 `127.0.0.1`，由同机反向代理对外提供 HTTPS。需要限制访客时，在代理上配置访问控制，并覆盖包括 `/events` 在内的整个站点。实时更新使用 SSE。关闭流式响应缓冲，并将空闲读取超时设为 15 秒以上。

以下为 Nginx 的 HTTPS 站点中可使用的路由，适用于独立域名。TLS 证书和访问控制在该站点中配置。

```nginx
location / {
    proxy_pass http://127.0.0.1:8765;
    proxy_http_version 1.1;
    proxy_buffering off;
    proxy_read_timeout 60s;
}
```

## 复测传播误差

游戏与网页使用同一组轨道根数、历元及目标时刻的用例。验收比较三维惯性坐标，允许误差 0.01 m，与游戏入轨拟合的位置容差一致。八组用例覆盖圆轨道、倾斜椭圆、近抛物线、抛物线、双曲线和绕卫星轨道。

```sh
./gradlew test --tests '*OrbitElementsTest.gameMapPositionsMatchTheSharedBrowserFixtures'
node --test src/test/js/kepler.test.mjs
```
