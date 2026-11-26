# BlockSpaceProgram

BlockSpaceProgram 是一个受《坎巴拉太空计划》启发的 Minecraft 模组。项目计划让玩家把方块结构制成零件，组装飞船，并在天体附近的飞行模拟与轨道传播之间切换。当前版本提供可构建和加载的工程骨架。 [项目路线图](https://github.com/jxt159753/BlockSpaceProgram/issues/24)

近地飞行选用 Sable，让飞船能与星球上的机械动力（Create）移动机构发生碰撞。实测中，Create 活塞平台把一个自由放置的 Sable 刚体托高了 8 格。整船飞行将在 #10 接入。[飞行物理决策记录](experiments/physics-drop/README.md) · [飞船实体与飞行物理 #10](https://github.com/jxt159753/BlockSpaceProgram/issues/10)

## 构建

安装 64 位 JDK 21，然后在项目根目录执行以下命令。首次构建会下载 Gradle、NeoForge 和 Minecraft 开发所需的依赖。

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

Mod ID 为 `blockspaceprogram`，Java 包名为 `io.github.jxt159753.blockspaceprogram`。工程基于 [NeoForge 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)。

## 许可证

项目使用 MIT 许可证，并保留 NeoForge MDK 的许可证声明。当前模组的运行依赖为 Minecraft 和 NeoForge。引入其他模组的代码前需确认其许可证、再分发条件和著佐权条款是否与 MIT 兼容。 [项目许可证](LICENSE) · [MDK 许可证](TEMPLATE_LICENSE.txt)
