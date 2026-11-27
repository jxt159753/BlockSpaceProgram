# 天体地表颜色图原型

服务端从已加载天体维度的区块生成器取得 `BiomeSource` 和 `RandomState`。后台线程按 #4 的等距圆柱投影采样格心。地图用生物群系 ID 调色板和每格一个字节的索引表示。SHA-256 覆盖格式版本、分辨率、调色板和网格。 [设计决策 #89](https://github.com/jxt159753/BlockSpaceProgram/issues/89)

玩家登录时收到当前清单。每张图完成后，服务端再次发送清单。客户端按维度请求缺少的图，服务端核对清单条目，同一连接每个维度最多发一张。客户端用同步的注册表给海洋和河流取水色，为寒冷且有降水的群系取雪白，其余取群系草色。着色器用天体的本初子午线和北极方向确定纹理坐标。地图到达前使用程序化配色。内置卫星只有一个生物群系，传输这张网格用于测量，渲染使用月面程序化配色。

## 专用服实测

Minecraft 1.21.1、NeoForge 21.1.267、Java 21.0.11、Sable 2.0.6。测试机运行 Windows 11，CPU 为 Intel Core Ultra 9 275HX，服务端 JVM 参数为 `-Xms2G -Xmx2G`。主世界为默认生成器，种子为 89。无客户端连接时采集基准。

### 采样、编码和服务端刻耗时

表中主世界在 Y=128 采样。后台耗时包含调色板编码、SHA-256 和 GZIP。MSPT 采集于生成窗口，P95 取排序后第 `ceil(0.95 × 样本数)` 项。压缩前后字节数均为地图数据，不含载荷外层字段。

| 维度 | 分辨率 | 首轮采样（ms） | 首轮后台耗时（ms） | 后台耗时中位数（ms） | 轮数 | Tick 样本 | MSPT 中位数（ms） | MSPT P95（ms） | 原始字节 | GZIP 字节 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 母星 | 1024 × 512 | 12,295.785 | 12,404.912 | 12,400.321 | 3 | 729 | 0.752 | 1.083 | 525,544 | 303,353 |
| 母星 | 2048 × 1024 | 49,798.591 | 50,327.077 | 47,030.845 | 3 | 2,818 | 0.644 | 0.894 | 2,098,408 | 1,049,548 |
| 卫星 | 512 × 256 | 2.206 | 5.596 | 2.806 | 701 | 40 | 0.617 | 0.979 | 131,104 | 193 |

生成前空闲窗口采了 40 tick，MSPT 中位数为 0.919 ms，P95 为 1.168 ms。单张卫星图生成用时短于一个 tick，表中卫星 MSPT 来自连续测量 2 秒的窗口。两种母星分辨率分别重复三次，卫星重复 701 次，各组哈希一致。

两个后台线程同时从同一个生成器重新生成 1024 × 512 图。两线程和基准的 SHA-256 均为 `5b13494546c60d3269bf0e3d9054a683b15f20b5ea9e7cc211f27570307ee666`。测试服一直在推进游戏刻，采样期间未观察到线程异常。结论覆盖此处测试的原版 `MultiNoiseBiomeSource` 和 `FixedBiomeSource`。

### 采样高度

1024 × 512 图中共 524,288 格。洞穴计数包括繁茂洞穴、溶洞和深暗之域。

| Y | 洞穴格 | 比例 |
| ---: | ---: | ---: |
| -32 | 68,708 | 13.1050% |
| 32 | 60,604 | 11.5593% |
| 63 | 20,962 | 3.9982% |
| 96 | 5,322 | 1.0151% |
| 128 | 840 | 0.1602% |
| 192 | 23 | 0.0044% |
| 256 | 0 | 0.0000% |

母星原型默认改用 Y=256。该高度在本轮扫描的候选高度中最先达到零洞穴格。2048 × 1024 图复测也为零，共 50 种生物群系。Sable 专用服该轮后台耗时 27.296 s，原始数据 2,098,359 字节，压缩后 1,047,705 字节。无 Sable 专用服生成了相同哈希的图。

### 登录和分包

地图已生成时，Sable 专用服上的本机客户端从进入 Play 阶段到收到母星图用时 283.1 ms，到贴图上传完成用时 357.9 ms。卫星图在 358.6 ms 到达。客户端校验了两张图的哈希。此轮母星图来自 Y=128，GZIP 数据为 1,049,548 字节。

分别关闭客户端和专用服的原型开关，两种组合都成功进入游戏。可选载荷经过协商后发送。

高熵测试图的 GZIP 数据为 2,098,793 字节。默认启用 Minecraft 网络压缩时，该图发送到客户端前触发 `Varint21LengthFieldPrepender` 的长度错误。报错时外层压缩帧为 2,099,137 字节，高于 3 字节 VarInt 能表示的 2,097,151 字节。NeoForge 的 `GenericPacketSplitter` 在管线有网络压缩器时按压缩前长度决定是否分包。

在无 Sable 专用服上将 `network-compression-threshold` 改为 `-1` 后，客户端成功重组同一个载荷，并验证 SHA-256 为 `7c094840f9452d6023aaf8172736a8731b07adee5cbfb720e84b97a320dae216`。实现时若要传送更大的图，建议让各片编码后的大小低于 1 MiB，以便沿用服务器的网络压缩设置。

母星格宽约为 368 m（1024 × 512）和 184 m（2048 × 1024）。选择分辨率时可对照生成等待时间、带宽和海岸线需要保留的细节。建议在确定正式分辨率后一并评估服务端持久缓存，尤其是 2048 × 1024 的启动生成成本。

### 地形和入轨画面对照

两套专用服均用 Y=256 生成 2048 × 1024 网格。玩家在 Y=3,000 旁观俯视，开启原型的地形对照模式。截图中央是已加载的真实区块，外围是球面贴图。四个地点的地表群系均由服务端 `execute if biome` 核对。格宽约 184 m，色块边缘是采样分辨率下的海岸位置。 [Sable 截图](evidence/terrain-sable.png) · [无 Sable 截图](evidence/terrain-plain.png)

| 地点 | X | Z | 色图格 | 地表核对 |
| --- | ---: | ---: | --- | --- |
| 海洋 | -288 | 64 | `minecraft:cold_ocean` | `#minecraft:is_ocean` |
| 沙漠 | -1920 | -224 | `minecraft:desert` | `minecraft:desert` |
| 雪原 | 256 | 32 | `minecraft:snowy_plains` | `minecraft:snowy_plains` |
| 丛林 | -1933 | -644 | `minecraft:jungle` | `#minecraft:is_jungle` |

入轨复测在 Sable 专用服进行。测试飞船质心置于 `(-191.5, 6053, 0.5)`，驾驶员入座后以 70% 节流点火。飞船越过 Y=6,063 时，服务端记录驾驶员随船进入 `blockspaceprogram:orbital_space`。切换前后的球面上可辨认同一个海湾和相邻海岸。两帧的相机方向有转动，圆圈标出同一段近岸海湾。 [入轨前后截图](evidence/orbit-handoff.png)

开启 Iris 1.8.14-beta.1 和 Complementary Reimagined r5.5.1，在 Y=18,000 确认 `AFTER_LEVEL` 路径显示地表颜色图。 [Iris 截图](evidence/iris-globe.jpg)

## 复测

需要 JDK 21。命令从仓库根目录运行。Windows 使用 `.\gradlew.bat`。

1. 在 `runs/surfaceColorMap/seed89/server/` 新建专用服目录，按本机约定处理 EULA。设置 `level-type=minecraft:normal`、`level-seed=89`、`server-ip=127.0.0.1`。Sable 组在服务端和客户端各自的 `mods/` 安装 Sable 2.0.6。 [Sable 2.0.6](https://modrinth.com/mod/sable/version/fg9dTRz9)
2. 启动服务端，等待日志出现两条 `Surface map generated`。要复现表中的尺寸和计时，加上 `-PsurfaceColorMapY=128`，待服务器空闲后在控制台执行 `bsp_surface_color_map benchmark`。用 `-PsurfaceColorMapWidth=1024` 可切换登录同步的母星图。

   ```sh
   ./gradlew -PsurfaceColorMap -PsurfaceColorMapRun=seed89 runSurfaceColorMapServer
   ```

3. 启动客户端。画面对照时加上 `-PsurfaceColorMapCompareTerrain`。该开关把远裁剪面和高空地形雾推到 4,096 m，并在原版区块可见性计算中沿纵向纳入已加载的区块。原版 32 区块视距的远裁剪面为 2,048 m，Y=3,000 处需开启对照模式才能看到下方地形。

   ```sh
   ./gradlew -PsurfaceColorMap -PsurfaceColorMapRun=seed89 -PsurfaceColorMapJoin=127.0.0.1:25565 -PsurfaceColorMapCompareTerrain runSurfaceColorMapClient
   ```

4. 查阅客户端日志的 `Surface map manifest` 和 `Surface map received`。其中 `login_ms` 从客户端的 Play 登录事件计时，`ready_ms` 计到贴图处理完成。分包诊断由测试玩家执行 `/bsp_surface_color_map split`。启用默认网络压缩时，该诊断可能让发起命令的测试玩家断线。关闭网络压缩的对照在 `server.properties` 设置 `network-compression-threshold=-1` 后重启。
5. 色图格的生物群系可用 `/bsp_surface_color_map cell <x> <z>` 查询。到达对应地点后，以 `/execute positioned <x> 0 <z> positioned over world_surface if biome ~ ~-1 ~ <生物群系或标签>` 核对脚下群系。海洋用 `#minecraft:is_ocean` 核对。
6. 入轨对照时，用带驾驶座和发动机的测试飞船。在 Sable 服上用 `/bsp_ship position <飞船UUID> -191.5 6053 0.5 0 0 0` 将已入座的飞船放到海岸上空，从舱内俯视取图，然后增推越过交接高度。等轨道局部物理区的天空显示后再截图。 [飞船调试命令](../../docs/flight.md#调试命令)

### 构建

```sh
./gradlew build
./gradlew -PsurfaceColorMap test --tests '*BiomeGridTest'
```
