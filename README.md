# BlockSpaceProgram

BlockSpaceProgram 是一个受《坎巴拉太空计划》启发的 Minecraft 整合包，基于 Minecraft 1.21.1 和 NeoForge。玩家把方块结构制成零件，组装飞船，并在天体附近的飞行模拟与轨道传播之间切换。工业、物理、任务和地形优先采用现有模组，现有模组覆盖不到的部分由本仓库的自制模组补齐。 [项目路线图](https://github.com/jxt159753/BlockSpaceProgram/issues/24)

## 模组构成

| 模组 | 在整合包中的作用 | 状态 |
| --- | --- | --- |
| [Create 6.0.10](https://modrinth.com/mod/create/version/UjX6dr61) | 工业与星球基地的移动机构 | 已选定 |
| [Sable 2.0.6](https://modrinth.com/mod/sable/version/fg9dTRz9) | 近地飞行的刚体物理，与 Create 机构发生碰撞 | 已选定 [#3](https://github.com/jxt159753/BlockSpaceProgram/issues/3) |
| [Exotic Worlds](https://github.com/anyttng/exotic_worlds) | 母星主世界东西无缝环绕 | 评估中 [#17](https://github.com/jxt159753/BlockSpaceProgram/issues/17) |
| FTB Quests、KubeJS | 科技树与合同 | 计划中 [#20](https://github.com/jxt159753/BlockSpaceProgram/issues/20) |
| BlockSpaceProgram（本仓库 `src/`） | 零件烘焙、飞船装配与飞行、轨道层、天空渲染等缺口 | 开发中 |

Issues 用两个标签区分实现方式。「整合包」靠选择模组、编写配置、KubeJS 脚本和数据包完成，「自制模组」是现有模组没有覆盖、需要写代码补齐的缺口。 [整合包 Issues](https://github.com/jxt159753/BlockSpaceProgram/issues?q=is%3Aissue+label%3A%E6%95%B4%E5%90%88%E5%8C%85) · [自制模组 Issues](https://github.com/jxt159753/BlockSpaceProgram/issues?q=is%3Aissue+label%3A%E8%87%AA%E5%88%B6%E6%A8%A1%E7%BB%84)

近地飞行选用 Sable，让飞船能与星球上的机械动力（Create）移动机构发生碰撞。实测中，Create 活塞平台把一个自由放置的 Sable 刚体托高了 8 格。整船飞行将在 #10 接入。[飞行物理决策记录](experiments/physics-drop/README.md) · [飞船实体与飞行物理 #10](https://github.com/jxt159753/BlockSpaceProgram/issues/10)

方块的零件属性、喷管和座椅可通过数据包配置，修改后执行 `/reload` 生效。 [零件属性数据包](docs/part-properties.md)

## 整合包

整合包尚未发布。模组清单、版本锁定、客户端包与服务端包的生成方式在 #27 中跟进。 [整合包清单、打包与发布 #27](https://github.com/jxt159753/BlockSpaceProgram/issues/27)

## 自制模组

### 构建

安装 64 位 JDK 21，然后在项目根目录执行以下命令。首次构建会下载 Gradle、NeoForge 和 Minecraft 开发所需的依赖。

```sh
./gradlew build
```

Windows PowerShell 使用 `.\gradlew.bat build`。JAR 位于 `build/libs/`，可放入 Minecraft 1.21.1 实例的 `mods/` 目录。实例需使用 NeoForge 21.1.267 或同系列更新版本。每次推送后也可从对应的 Actions 运行下载 `blockspaceprogram-jar` 构建产物。

### 开发运行

将项目作为 Gradle 项目导入 IDE，或在项目根目录运行下列任务。Windows PowerShell 将 `./gradlew` 替换为 `.\gradlew.bat`。

| 命令 | 用途 |
| --- | --- |
| `./gradlew runClient` | 启动客户端，在主菜单的「模组」列表查看 BlockSpaceProgram |
| `./gradlew runServer` | 在 `runs/server/` 启动无 GUI 的开发服务器，本机可用 `localhost:25565` 连接 |
| `./gradlew runData` | 运行数据生成器，生成的资源写入 `src/generated/resources/` |

Mod ID 为 `blockspaceprogram`，Java 包名为 `io.github.jxt159753.blockspaceprogram`。工程基于 [NeoForge 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)。

## 许可证

本仓库的自制模组代码使用 MIT 许可证，并保留 NeoForge MDK 的许可证声明。自制模组目前只依赖 Minecraft 和 NeoForge，飞船模块接入时再声明对 Sable 的依赖。整合包内的第三方模组各自沿用原许可证，例如 Sable 使用 PolyForm Shield 1.0.0。整合包清单计划只引用各模组的官方下载，不在仓库或发布包中重新分发第三方 JAR。自制模组引入其他模组的代码前，仍需确认其许可证、再分发条件和著佐权条款是否与 MIT 兼容。 [项目许可证](LICENSE) · [MDK 许可证](TEMPLATE_LICENSE.txt)
