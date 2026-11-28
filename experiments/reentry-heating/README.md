# 再入加热与隔热罩原型

`-PreentryHeating` 启用再入原型并复用 `-PaeroOcclusion` 的六向投影。两个原型的接入补丁依次应用到 `build/` 中的源码副本。 [再入加热 #137](https://github.com/jxt159753/BlockSpaceProgram/issues/137) · [气动原型 #135](https://github.com/jxt159753/BlockSpaceProgram/issues/135)

## 模型

- 每个节点从 293.15 K 开始。平衡温度为 `293.15 K + reentry_heat_multiplier × v² / (2 × 1005)`。默认倍率为 2。745 m/s 和 960 m/s 对应 845.41 K 和 1,210.16 K。
- 受热速率为 `3 × 空气密度 × 空速 × 受风面积 / 节点质量`，质量包含剩余推进剂。遮挡和斜向权重沿用 #135。背风和真空节点向环境温度冷却，温差每 8 s 衰减到原先的约 37%。
- 超过耐热温度时沿用现有节点损坏流程。船体超温后从热投影中移除，后方节点开始从该方向受热。船体的质量、碰撞和阻力继续由原布局计算。
- 烘焙零件沿用烘焙时的最低非零耐热温度。船体节点在建立飞行状态时取其方块的最低非零耐热温度。节点没有配置值时用 `[reentry].default_heat_resistance`，候选值为 650 K。
- 温度只记录在内存中。分离和对接时保留对应节点的温度，读档和轨道交接时回到 293.15 K。客户端每 4 刻接收最高温度与耐热温度之比和船体坐标下的迎风方向。驾驶 HUD 显示百分比，迎风侧用原创透明贴图渲染。

试验数据包用黑曜石代替隔热罩，两者采用同一块状遮挡几何。铁质底板和试验隔热层各重 3,150 kg，便于成对比较。普通构建沿用正式模组的数据。

| 材料 | 候选耐热温度 |
| --- | ---: |
| 木板 | 460 K |
| 铁块、粗铁块、铁矿石、炼药锅 | 800 K |
| 燃烧室 | 900 K |
| 喷口段 | 1,000 K |
| 试验隔热层 | 1,600 K |
| 未配置节点 | 650 K |

## 实测结果

热轨迹基于 `73526b8`。专用服为 Minecraft 1.21.1、NeoForge 21.1.267 和 Sable 2.0.6。铁舱与带罩舱都重 5,400 kg。木质船体组重 2,610 kg。

| 工况 | 交接或放置时空速 | Sable 默认阻尼下的入气空速 | 通用阻尼为 0 时的入气空速 |
| --- | ---: | ---: | ---: |
| 主世界 10 km 圆轨离轨 | 405.36 m/s | 125.39 m/s | 425.86 m/s |
| 卫星返回模拟 | 961.87 m/s | 316.37 m/s | 970.17 m/s |

低轨交接空速使用主世界的旋转坐标。745 m/s 是平衡温度算例的输入。关闭通用阻尼时，进气前下落会因重力略微增速。

以下是关闭通用阻尼后的逐节点结果。每格为「最高温度 / 超温时长」。

| 工况 | 底板或隔热层 | 中层 | 指令舱 |
| --- | ---: | ---: | ---: |
| 低轨，铁舱 | 436.60 K / 0 s | 300.01 K / 0 s | 317.51 K / 0 s |
| 低轨，带罩 | 436.60 K / 0 s | 300.01 K / 0 s | 317.51 K / 0 s |
| 卫星返回模拟，铁舱 | 939.66 K / 5.65 s | 302.83 K / 0 s | 329.16 K / 0 s |
| 卫星返回模拟，带罩 | 939.66 K / 0 s | 302.83 K / 0 s | 329.16 K / 0 s |
| 低轨，木板船体 | 460.11 K / 0.025 s | 387.79 K / 0 s | 413.60 K / 0 s |

铁舱从卫星返回时仅底板受损。换成同尺寸耐热底板后，三个节点均无损。木板船体在轨道交接后 17.925 s 烧穿。中层沿底板法向的受风面积由 0 增至 9 m²，累计峰值从烧穿时的 298.43 K 升至 387.79 K。木板峰值只超过 460 K 阈值 0.11 K。正式实现可从 450 K 开始校准，留出约 10 K 的余量。

### 服务端耗时

两组在 `58b1f10` 上各测 10 艘 63 节点飞船。耗时取 160 刻的中位数。

| 模型 | 空场景 | 10 艘再入 | 单刻增量 |
| --- | ---: | ---: | ---: |
| 六向阻力 | 0.602 ms | 2.382 ms | 1.781 ms |
| 六向阻力和加热 | 1.129 ms | 2.045 ms | 0.915 ms |

扣除各组空场景后，启用加热的实测差值为 −0.865 ms。两组空场景本身相差 0.528 ms。本轮样本的整刻波动大于可分辨的加热开销。

### 客户端画面

使用 Iris 1.8.14-beta.1 和 Sodium 0.8.13。关闭光影或开启 BSL 10.1.3 时，同一视角均可见迎风侧火焰。取景用旁观视角。 [关闭光影](evidence/reentry-iris-off.png) · [开启 BSL](evidence/reentry-iris-bsl.png)

### 构建和无 Sable 验证

同步 `9655bb7` 的降落伞和 RCS 改动后，常规构建通过 124 项测试，启用原型的构建通过 130 项测试。Minecraft 1.21.1、NeoForge 21.1.267 无 Sable 专用服启动成功，试验返回舱为静态飞船。

维护者确认采用五项 A。正式实现采用主世界通用阻尼为 0 的条件和候选倍率 2。 [阻尼前置 #147](https://github.com/jxt159753/BlockSpaceProgram/issues/147) · [正式实现 #148](https://github.com/jxt159753/BlockSpaceProgram/issues/148)

## 复测

实测环境使用 Java 21、Minecraft 1.21.1、NeoForge 21.1.267 和 Sable 2.0.6 专用服。`-PreentryHeatingRun=<名称>` 指定 `runs/reentryHeating/<名称>/server` 和 `client` 目录。各组用独立目录和本机端口。服务端的 `mods/` 放入 Sable 2.0.6，并在接受 Minecraft EULA 后配置超平坦世界。

```properties
level-name=world
level-seed=137
level-type=minecraft\:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":124},{"block":"minecraft:dirt","height":1},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","features":false,"lakes":false}
generate-structures=false
gamemode=spectator
difficulty=peaceful
spawn-protection=0
view-distance=4
simulation-distance=3
server-ip=127.0.0.1
server-port=25737
```

在 `server/defaultconfigs/blockspaceprogram-server.toml` 设置 `[debris]` 下的 `distance = 0`。用以下命令执行 JUnit 4 和专用服轨迹测试。服务端会自动布置飞船、记录结果并停止。温度、入气空速和超温时长记录在 `logs/latest.log` 的 `REENTRY` 行。

```powershell
.\gradlew.bat build
.\gradlew.bat -PreentryHeating test --tests '*ReentryTemperatureTest' --tests '*AeroProjectionTest'
.\gradlew.bat -PreentryHeating -PreentryHeatingRun=default -PreentryHeatingTrial=trajectories runReentryHeatingServer
.\gradlew.bat -PreentryHeating -PreentryHeatingRun=calibration -PreentryHeatingTrial=trajectories -PreentryHeatingWithoutSableDrag runReentryHeatingServer
```

低轨组先用 `/bsp_ship orbit <UUID> minecraft:overworld 70000 0 0 0 0 0` 放入圆轨道。再用同一指令设置远地点 10 km、近地点 3 km 的椭圆轨道，模拟在远地点完成瞬时逆行点火。三组的轨迹经度错开 12°。返回由轨道交接完成。卫星返回组用 `/bsp_ship position <UUID> 0 5000 0 960 -60 0`，第二组在 Z 方向错开 128 m。各舱在入气时调整一次，使底板朝前，随后由 Sable 和 #135 的压心力矩控制姿态。各节点降至耐热温度以下且飞船低于海拔 500 m 时结束热测量。

两组性能试验都保留 #135 的阻力与压心力矩。用 `benchmark` 启动 10 艘各 63 节点的飞船。预热 60 刻后分别采集空场景和下落场景各 160 刻。基线额外带 `-PreentryHeatingBaseline`。

```powershell
.\gradlew.bat -PreentryHeating -PreentryHeatingRun=bench-baseline -PreentryHeatingTrial=benchmark -PreentryHeatingBaseline runReentryHeatingServer
.\gradlew.bat -PreentryHeating -PreentryHeatingRun=bench-heating -PreentryHeatingTrial=benchmark runReentryHeatingServer
.\gradlew.bat -PreentryHeating -PreentryHeatingRun=no-sable -PreentryHeatingTrial=no-sable runReentryHeatingServer
```

`no-sable` 组的 `mods/` 留空。截图用 `visual` 组，专用服将返回舱保持在 Y=175 m，并向温度计算输入约 815 m/s 的来流。测试客户端安装同版本 Sable、Iris 和 Sodium。通过 `config/iris.properties` 切换 `enableShaders`，两组用同一 BSL 光影包。以下是使用本地离线测试服的启动方式。为此将 `online-mode` 和 `enforce-secure-profile` 设为 `false`。

```powershell
.\gradlew.bat -PreentryHeating -PreentryHeatingRun=iris-off -PreentryHeatingTrial=visual runReentryHeatingServer
.\gradlew.bat -PreentryHeating -PreentryHeatingRun=iris-off '-PreentryHeatingJoin=127.0.0.1:25737' -PreentryHeatingCapture=reentry-iris-off runReentryHeatingClient
```

画面稳定后，客户端会在 `screenshots/` 写入 PNG。启动光影开启组时换一套目录和端口。
