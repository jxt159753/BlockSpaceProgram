# BlockSpaceProgram

BlockSpaceProgram 是 [BlockSpaceProgram 整合包](https://github.com/jxt159753/BlockSpaceProgram-Modpack)的自制模组。整合包受《坎巴拉太空计划》启发，让玩家把方块结构制成零件，组装飞船，并在天体附近的飞行模拟与轨道传播之间切换。工业、物理、任务和地形交给整合包里的现有模组，本模组补齐它们覆盖不到的部分：零件烘焙、飞船装配与飞行、轨道层、再入和天空渲染。 [项目路线图](https://github.com/jxt159753/BlockSpaceProgram/issues/24) · [整合包仓库](https://github.com/jxt159753/BlockSpaceProgram-Modpack)

近地飞行使用 Sable 解算整船刚体。玩家可从发射台登船，手动驾驶或切换内置飞控。安装 CC:Tweaked 后，地面电脑可以通过飞控终端驾驶无人飞船。飞船与机械动力（Create）机构共用 Sable 的碰撞层，原型中的活塞平台曾将测试刚体托高 8 格。 [飞船驾驶](docs/flight.md) · [CC:Tweaked 飞控外设](docs/flight-computercraft.md) · [飞行物理决策记录](experiments/physics-drop/README.md)

方块的零件属性、喷管和座椅可通过数据包配置，修改后执行 `/reload` 生效。 [零件属性数据包](docs/part-properties.md)

整合包可以用 KubeJS 订阅入轨、着陆、分级分离和影响球变更事件。 [KubeJS 飞行事件](docs/flight-kubejs.md)

用装配台在 3D 界面接合零件、编排分级并查看每级 Δv。保存蓝图后，把完整的零件套件带到发射台生成飞船。服务器可以按玩家或 FTB Teams 队伍设置设施等级和制造上限。 [装配与发射](docs/vab.md) · [设施等级与上限](docs/facility.md)

新存档可以选择卫星间距和轨道形状。天空和轨道地图随该存档的卫星位置推进。 [天体与新存档](docs/orbit.md#天体与新存档) · [生成原型复测](experiments/star-system/README.md)

服务器管理员可以开启只读网页，让玩家从浏览器查看轨道、飞船和已提交的机动节点。 [网页轨道预览](docs/web-orbit-preview.md)

## 构建

安装 64 位 JDK 21，然后在项目根目录执行以下命令。首次构建会下载 Gradle、NeoForge、Minecraft、Sable、CC:Tweaked 和 FTB Teams 的编译依赖。

```sh
./gradlew build
```

Windows PowerShell 使用 `.\gradlew.bat build`。JAR 位于 `build/libs/`，可放入 Minecraft 1.21.1 实例的 `mods/` 目录。实例需使用 NeoForge 21.1.267 或同系列更新版本。每次推送后也可从对应的 Actions 运行下载 `blockspaceprogram-jar` 构建产物。

## 开发运行

将项目作为 Gradle 项目导入 IDE，或在项目根目录运行下列任务。Windows PowerShell 将 `./gradlew` 替换为 `.\gradlew.bat`。

| 命令 | 用途 |
| --- | --- |
| `./gradlew runClient` | 启动客户端，在主菜单的「模组」列表查看 BlockSpaceProgram |
| `./gradlew runServer` | 在 `runs/server/` 启动无 GUI 的开发服务器，本机可用 `localhost:25565` 连接 |
| `./gradlew runData` | 运行数据生成器，生成的资源写入 `src/generated/resources/` |

候选维度池的专用服实测和复测步骤见原型记录。 [候选维度池原型](experiments/candidate-dimensions/README.md)

天体地表颜色图的采样、传输和画面对照见原型记录。 [天体地表颜色图原型](experiments/surface-color-map/README.md)

化学推进与电推进的 Δv 算例、Sable 推力和长时间点火实测见原型记录。 [推进剂原型](experiments/propellant-electric/README.md)

喷管的 JSON 与 OBJ 模型对照、截图和顶点计数见原型记录。 [功能方块模型原型](experiments/function-block-models/README.md)

成品发动机和多方块喷管的试制、装配与验收记录见原型说明。 [发动机原型](experiments/engine-templates/README.md)

用 `/bsp_bench` 复测零件渲染和飞船压力场景。 [性能基准命令](docs/benchmark.md)

Mod ID 为 `blockspaceprogram`，Java 包名为 `io.github.jxt159753.blockspaceprogram`。工程基于 [NeoForge 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)。

## 许可证

项目使用 MIT 许可证，并保留 NeoForge MDK 的许可证声明。烘焙、缩放和静态放置需要 Minecraft 和 NeoForge。驾驶飞船需要在服务端和客户端安装可选依赖 Sable 2.0.6。整合包内其他模组的许可证在整合包仓库记录。引入其他模组的代码前需确认其许可证、再分发条件和著佐权条款是否与 MIT 兼容。 [项目许可证](LICENSE) · [MDK 许可证](TEMPLATE_LICENSE.txt) · [Sable 2.0.6](https://modrinth.com/mod/sable/version/fg9dTRz9)
