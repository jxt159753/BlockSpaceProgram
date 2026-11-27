# 候选维度池原型

原型通过数据包注册候选维度和 `ship_flight`。专用服首次启动时，把已加载且有飞行参数的候选按 ID 排序，再用世界种子打乱，从中取出启用名单写入 `world/data/bsp_candidate_dimensions_experiment.dat`。以后启动从这份 `SavedData` 读取名单。 [设计决策 #67](https://github.com/jxt159753/BlockSpaceProgram/issues/67)

## 专用服实测

Minecraft 1.21.1、NeoForge 21.1.267、Java 21.0.11。ModDevGradle 专用开发服运行于 Windows 11，CPU 为 Intel Core Ultra 9 275HX，JVM 参数为 `-Xms2G -Xmx2G`。六组各用一个新存档，种子均为 67。每组预热 30 秒，采集 5 分钟，共 6,000 tick。无人连接，采样结束时各组候选维度的已加载区块合计为 0。

| Sable | 候选数 | MSPT 中位数（ms） | MSPT P95（ms） | 已用 Java 堆（MB） | 启动（s） | 自动保存（ms） | 候选维度文件数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 未安装 | 0 | 0.2134 | 0.3445 | 197.53 | 24.924 | 23 | 0 |
| 未安装 | 8 | 0.2154 | 0.3243 | 200.26 | 22.054 | 24 | 16 |
| 未安装 | 16 | 0.2537 | 0.3360 | 203.41 | 28.720 | 27 | 32 |
| 2.0.6 | 0 | 0.5691 | 0.9801 | 261.47 | 28.705 | 43 | 0 |
| 2.0.6 | 8 | 0.9511 | 1.4694 | 265.04 | 33.931 | 52 | 24 |
| 2.0.6 | 16 | 1.3400 | 2.9678 | 269.50 | 30.701 | 66 | 48 |

8 个闲置维度使 MSPT 中位数和 Java 堆分别增加 0.0020 ms、2.74 MB（无 Sable），以及 0.3820 ms、3.57 MB（Sable 2.0.6）。两组都低于 0.5 ms 和 50 MB 的初定线。Sable 组在 16 个闲置维度时，MSPT 中位数比 0 维度组高 0.771 ms。

启动和自动保存每组各记录一次。8 维度组的自动保存耗时分别增加 1 ms 和 9 ms。各闲置维度生成 `data/neoforge_data_attachments.dat` 和 `data/raids.dat`。Sable 另写入 `data/sable_sub_level_force_load_tickets.dat`。候选维度中没有 `.mca` 区块区域文件。

### 存档与数据包

有无 Sable 的两组重启测试结果一致。

| 验证项 | 观测结果 |
| --- | --- |
| 启用名单 | 种子 67 从 8 个候选中选中 `candidate_01`、`candidate_02`、`candidate_04`。保存后重启，名单保留。将启用数从 3 改成 5 后再次重启，读取的仍是这 3 个 ID。 |
| 数据包覆盖 | 将 `candidate_01` 的平坦层改为金块。重启后，已生成区块的 `(0, -64, 0)` 保留石头，新生成区块的 `(2048, -64, 0)` 是金块。 |
| 移除维度定义 | 移出 `candidate_08.json` 后重启，已加载维度仍有 `candidate_08`，控制台可以在其中执行命令。停服后 `level.dat` 的 `WorldGenSettings.dimensions` 仍有该维度。 |
| 移除引用的噪声设置 | 再移出 `persisted.json` 后，两组服务器均能启动。候选数从 8 降到 7，控制台返回 `Unknown dimension 'bsp_candidate_dimensions:candidate_08'`。停服保存后，`level.dat` 已移除该维度的记录。 |

缺少噪声设置时，NeoForge 在解码维度记录时报告 `Failed to decode key`。对应原因是 `Failed to get element ResourceKey[minecraft:worldgen/noise_settings / bsp_candidate_dimensions:persisted]`。

含 Sable 的测试服启动时还输出了 `Unknown block: create:flywheel`。本次没有加载 Create 机构，所有 Sable 场景均完成运行。

## 复测

需要 JDK 21 和 Python 3。命令从仓库根目录运行，Windows PowerShell 将 `./gradlew` 替换为 `.\gradlew.bat`。各测试使用 `runs/candidateDimensions/<名称>/` 下的新存档，服务器绑定 `127.0.0.1:25667`。同一时间只运行一个测试实例。需要 Sable 的各组在准备命令末尾加上 `--sable <Sable 2.0.6 的 NeoForge JAR 路径>`，并使用新的名称。若首次启动提示 EULA，按服务器提示处理后重启。 [Sable 2.0.6](https://modrinth.com/mod/sable/version/fg9dTRz9)

### 闲置维度

```sh
python experiments/candidate-dimensions/prepare.py plain-8 8
./gradlew -PcandidateDimensions -PcandidateDimensionsRun=plain-8 -PcandidateDimensionsSatelliteCount=0 -PcandidateDimensionsBenchmark runCandidateDimensions
```

将名称和数量换成 `plain-0 0`、`plain-16 16` 分别运行，再用 Sable 重复三组。保持无人连接，且不在控制台加载候选维度的区块。原型预热 30 秒，采样 5 分钟后自动停服。

- `logs/debug.log` 中的 `Candidate dimensions ready` 记录从 JVM 启动到专用服就绪的耗时和已注册维度。
- `Candidate dimensions sample` 记录每 tick 执行耗时的中位数和 P95，以及采样结束后执行一次完整 GC 的已用 Java 堆。P95 取排序后第 `ceil(0.95 × 样本数)` 个样本。
- `Autosave started` 和 `Autosave finished` 的日志时间差是当次自动保存的耗时。停服后检查 `world/dimensions/bsp_candidate_dimensions/` 下的文件。

### 已有存档

```sh
./gradlew createMinecraftArtifacts
python experiments/candidate-dimensions/prepare.py plain-save 8 --terrain
./gradlew -PcandidateDimensions -PcandidateDimensionsRun=plain-save runCandidateDimensions
```

1. 查看日志的 `registered`、`eligible` 和 `enabled`。新存档使用种子 67，启用数默认为 3。
2. 在服务器控制台加载测试区块。待区块生成后，第二条命令应返回 `Test passed`。执行 `save-all flush`，再执行 `stop`。

   ```mcfunction
   execute in bsp_candidate_dimensions:candidate_01 run forceload add 0 0
   execute in bsp_candidate_dimensions:candidate_01 if block 0 -64 0 minecraft:stone
   ```

3. 在 `world/datapacks/candidate-dimensions/data/bsp_candidate_dimensions/dimension/candidate_01.json` 中，把平坦层唯一的方块从 `minecraft:stone` 改为 `minecraft:gold_block`，再用原命令启动。核对 `enabled` 和第一次一致。待第二个区块生成后，用下面的命令核对旧区块和新区块。执行 `stop`。

   ```mcfunction
   execute in bsp_candidate_dimensions:candidate_01 run forceload add 2048 0
   execute in bsp_candidate_dimensions:candidate_01 if block 0 -64 0 minecraft:stone
   execute in bsp_candidate_dimensions:candidate_01 if block 2048 -64 0 minecraft:gold_block
   ```

4. 启动命令加上 `-PcandidateDimensionsSatelliteCount=5` 后重启。核对 `requested=5`，以及 `enabled` 仍是原来的 3 个 ID。执行 `stop`。
5. 将同一数据包的 `dimension/candidate_08.json` 移出数据包。重启后检查 `registered`，并在控制台执行 `execute in bsp_candidate_dimensions:candidate_08 run seed`。执行 `stop`。
6. 再将 `worldgen/noise_settings/persisted.json` 移出数据包后重启，核对启动日志和 `candidate_08` 是否仍在 `registered`。执行 `stop` 后检查 `level.dat` 的 `WorldGenSettings.dimensions`。用装有 Sable 的新存档重复整组。
