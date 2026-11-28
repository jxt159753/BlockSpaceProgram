# 成品发动机与拼装喷管原型

用 #118 的 A 方案验证模板配方、零件模型、多方块喷管和装配提示。使用 Java 21、Minecraft 1.21.1 和 NeoForge 21.1.267。飞行和橙色碰撞格使用 Sable 2.0.6。 [设计决策 #118](https://github.com/jxt159753/BlockSpaceProgram/issues/118)

## 决定

维护者确认五项采用 A。

- 成品发动机由 JSON 模板定义，生成有独立显示模型的烘焙零件。装配、分级、Δv 和碰撞沿用烘焙零件的流程。
- 玩家通过 `blockspaceprogram:part_template` 工作台配方获取成品。装配台继续限制零件数，发射台继续限制满油质量。
- 同一种喷管段按相邻状态拼成 1×1、2×2 和 3×3 的喷管。烘焙保留各段的方块状态。
- 一个燃烧室驱动一组相连喷管。燃烧室提供推力，数据映射按喷管段数决定比冲。一个零件可以容纳多台发动机。
- 装配台和世界内组装会指出喷口遮挡，提示后仍可放置。

## 运行

在仓库根目录执行以下命令。Windows 使用 `gradlew.bat`。Gradle 通过 Git 将 `integration.patch` 应用到生成的 Java 源码，再加入原型的数据和资源。

```sh
./gradlew -PengineTemplates build
./gradlew -PengineTemplates runEngineTemplatesServer
./gradlew -PengineTemplates -PengineTemplatesJoin=localhost:25565 runEngineTemplatesClient
```

实例目录为 `runs/engineTemplates/server` 和 `runs/engineTemplates/client`。启动专用服需要在服务器目录配置 `eula.txt`。将 Sable 放入两端的 `mods/`。检查光影时，在客户端加入 Iris、匹配的 Sodium 和光影包。测试存档应始终使用同一套原型。

## 成品发动机

1. 用高炉、避雷针和铜锭在工作台或合成器中合成样件。配方的序列化器是 `blockspaceprogram:part_template`。用 `/recipe give <玩家> blockspaceprogram:prototype_engine` 可单独验证配方书解锁。
2. 烘焙一个同时有指令舱和燃料的模块。底层铺朝下的 3×3 堆叠接口。第二层用木板围一格空炼药锅。顶层放一个已加入 `blockspaceprogram:command_seats` 标签的方块。测试用数据包在 `data/blockspaceprogram/tags/block/command_seats.json` 中加入 `{"values":["minecraft:yellow_wool"]}`。
3. 把燃料舱放入装配台，选择成品发动机并对准燃料舱底部。两件都缩放到 0.5 或 0.25 再重做这一步。接口边长分别为 12、6、3 个 1/4 格。
4. 在成品发动机下方用表面模式贴一件小零件，查看装配台状态栏。清除遮挡件后保存蓝图，在发射台上生成飞船。按 **F8** 查看已放置零件的绿色边界和飞船的橙色碰撞格。
5. 装配世界中的零件时，让另一个零件落在喷口正前方。放置预览和放置后的动作栏会显示提示，零件仍可放置。原型沿喷口轴线检查一个未缩放格，距离随比例缩放。

模板数据放在 `data/<命名空间>/part_template/<名称>.json`。样件的尺寸为 3×2×3，干质量为 220 kg，推力为 12,000 N，真空比冲为 350 s，海平面比冲为 235 s。`display_model` 指向 `assets/<命名空间>/models/part/<名称>.json`。原版 JSON 模型的 0–16 坐标按三个轴分别映射到模板包围盒。渲染使用该模型，碰撞栅格由包围盒填充。喷管内凹部分也包含在简化碰撞里。

可编辑的 Blockbench 工程放在 `models/`，材质引用 Minecraft 自带的纹理。编辑时可在 Blockbench 中从 Minecraft 资源目录重新关联纹理。运行时 JSON 放在 `resources/`。 [功能方块模型决策 #113](https://github.com/jxt159753/BlockSpaceProgram/issues/113)

成品配方绕过烘焙台的尺寸上限，装配和发射继续应用设施上限。模板零件在烘焙台上保留原物品。模型 ID 和喷口数据随零件、蓝图和飞船保存。耗电功率作为快照字段保留，飞行继续使用本体的推进剂和点火规则。

## 拼装发动机

1. 从 BlockSpaceProgram 创造模式标签页取出燃烧室和喷管段。在烘焙台上分别用 `blockspaceprogram:nozzle_segment` 摆 1×1、2×2 和 3×3 正方形。每组正上方贴一块 `blockspaceprogram:combustion_chamber`。此原型的喷管朝下。
2. 手持铜锭右击烘焙台。喷管段的四向连接状态随结构保存，零件物品、装配和飞行共用这份几何。将每件发动机以表面模式接到有燃料的指令舱下方，分别保存并发射。
3. 每个同一高度、边相连的喷管组由正上方的一个燃烧室驱动。无燃烧室的喷管可用作装饰。两个燃烧室共用一组喷管时，烘焙台报错并保留原结构。

在 `data/blockspaceprogram/data_maps/block/nozzle_performance.json` 为燃烧室配置推力和各档比冲。样件的三个尺寸每台都是 6,000 N。达到段数阈值 1、4 和 9 时，真空比冲为 260、330 和 400 s，海平面比冲为 235、200 和 160 s。每台发动机的作用点是对应喷管段出口中心的均值。

## 双燃烧室算例

`EnginePerformanceTest` 使用两组 2×2 喷管。两台燃烧室分别提供 80,000 N 和 120,000 N，真空比冲分别为 330 s 和 390 s，海平面比冲为 200 s 和 180 s。喷口中心位于 `(1, 0, 1)` 和 `(5, 0, 1)`。合推力为 200,000 N，按总质量流量折算的真空比冲约为 363.559322 s，海平面比冲为 187.5 s。沿两台发动机共同的推力方向，合力在喷口平面通过 `(3.4, 0, 1)`。

## 实测

Windows 11、Java 21.0.11、Minecraft 1.21.1、NeoForge 21.1.267。飞行端安装 Sable 2.0.6。其余服务器测试使用本模组自带内容和一个为黄色羊毛添加指令座椅标签的数据包。

- 无 Sable 时用合成器生成成品零件，产物包含 18 个占位单元和 3×3 堆叠接口。专用服完整重启后，从容器读回了模型 ID。
- 成品发动机在装配台三档均与同档燃料舱对接。零件尺寸分别为 3×2×3、1.5×1×1.5 和 0.75×0.5×0.75 格。 [1.0](evidence/template-100-vab.png) · [0.5](evidence/template-050-vab.png) · [0.25](evidence/template-025-vab.png)
- 客户端使用 Iris 1.8.14-beta.1、Sodium 0.8.13 和 BSL v10.1.3。光影启用时，样件的安装板、铜色燃烧室和喷管可见，纹理和阴影正常。 [Iris 光影下的成品发动机](evidence/template-iris-bsl.png)
- F8 中绿色包围盒和橙色碰撞格与样件的 3×2×3 外部尺寸相符。收窄的喷管段仍位于盒内。 [放置态包围盒](evidence/template-f8-placed.png) · [Sable 碰撞格](evidence/template-f8-sable.png)
- 把小零件贴到喷口底面后，装配台增至 3 个零件，提示「喷口受阻。请检查分级。」在世界中也可放置同样的遮挡零件，动作栏显示提示。 [装配台提示](evidence/exhaust-blocked-vab.png) · [世界放置提示](evidence/exhaust-blocked-world.png)

飞行用平滑石头替换示例燃料舱中的木板，以保留升空所需的推重比。三种发动机均实际离地，初始燃料均为 100 kg。第一次测到离地时，按喷管大小顺序读取的余量约为 97.78、97.20、96.79 kg。

飞行截图使用解锁载具视角。3×3 样件的截图在测试服移除发射台支撑后拍摄。飞船保持点火，截图中的油门为 100%。

| 喷管 | 烘焙推力 | 真空比冲 / 海平面比冲 | 截图 |
| --- | --- | --- | --- |
| 1×1 | 6,000 N | 260 s / 235 s | [物品](evidence/nozzle-1x1-item.png) · [装配](evidence/nozzle-1x1-vab.png) · [飞行](evidence/nozzle-1x1-flight.png) |
| 2×2 | 6,000 N | 330 s / 200 s | [物品](evidence/nozzle-2x2-item.png) · [装配](evidence/nozzle-2x2-vab.png) · [飞行](evidence/nozzle-2x2-flight.png) |
| 3×3 | 6,000 N | 400 s / 160 s | [物品](evidence/nozzle-3x3-item.png) · [装配](evidence/nozzle-3x3-vab.png) · [飞行](evidence/nozzle-3x3-flight.png) |

`gradlew build` 和 `gradlew -PengineTemplates build` 均通过，包括双燃烧室算例。默认 JAR 排除原型运行时内容，两种 JAR 都将 Blockbench 工程留在仓库。配方书的 `/recipe give` 已实测，KubeJS 新增这种配方尚未验证。
