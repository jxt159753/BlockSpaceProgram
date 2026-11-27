# 天体系统生成原型

用主世界的 `LevelEvent.Load` 验证新档判定、旧档初始化和存档重开。生成器及相关测试已纳入本体。 [设计决定 #53](https://github.com/jxt159753/BlockSpaceProgram/issues/53)

## 原型实测

Windows、Java 21、Minecraft 1.21.1、NeoForge 21.1.267、Sable 2.0.6 专用服。

| 验证项 | 观测结果 |
| --- | --- |
| 六种档位组合各抽 1,000 个种子 | 6,000 个样本全部通过影响球校验。霍曼时间取整后均在各档位范围内。 |
| 用世界种子 `90000000000053` 建立近／自然档 | 加载主世界时初始化标记为 false。有效种子为 `-3468122787463884361`，半长轴约 228.57 km，历元平近点角约 3.53667 rad，等效霍曼时间约 16.08 分钟。 |
| 改成远／规整并指定种子 `15` 后重启 | 初始化标记为 true。保存的档位、种子和全部轨道根数与首次启动一致。 |
| 启动缺少天体系统记录的旧档副本 | 初始化标记为 true，得到中／规整档，半长轴 360 km，历元平近点角 0 rad。 |

## 复测

运行 `./gradlew test --tests '*StarSystemTest'` 可复查抽样和固定种子的根数。Windows PowerShell 使用 `.\gradlew.bat`。

在 `runs/starSystem/server.properties` 中选择未使用的 `level-name`，设 `level-seed=90000000000053`。将 Sable 2.0.6 放入 `runs/starSystem/mods/`。在 `runs/starSystem/defaultconfigs/blockspaceprogram-server.toml` 中设置 `near`、`natural`，然后用 `./gradlew -PstarSystem runStarSystem` 启动专用服。已有全局配置时，在 `runs/starSystem/config/blockspaceprogram-server.toml` 设置档位。 [配置键与查看命令](../../docs/orbit.md#天体与新存档)

控制台执行 `bsp_star_system` 记录根数，并用 `stop` 停服。修改配置和种子后再次启动同一个存档，根数应一致。将未升级的旧档复制到测试服务器并通过 `level-name` 打开，可核对 M3 参数及原有飞船。
