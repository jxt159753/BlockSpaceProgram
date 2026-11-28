# 推进剂原型

原型测试化学与电推进同向、满节流、共用一个燃料组的情况。轨道机动另用一台电推进测试持续点火。Gradle 属性 `propellantElectric` 启用实验命令。 [设计决策 #116](https://github.com/jxt159753/BlockSpaceProgram/issues/116)

## 混合级算例

干质量 1,000 kg，燃料 100 kg，电量 250 MJ。化学发动机为 20,000 N、真空比冲 250 s。电推进为 4,000 N、真空比冲 2,000 s、耗电 50 MW。取 `g₀ = 9.80665 m/s²`。两台发动机同时点火时的初始推重比为 2.225。

按 `推力 ÷ (g₀ × 比冲)` 计算质量流量。电推进工作 5 s 后耗尽电量，随后的燃料只供化学发动机。每段用该段的总推力和总流量积分，Δv 等于 `总推力 ÷ 总流量 × ln(段初质量 ÷ 段末质量)`。方案 B 的纯电段质量恒定，Δv 等于 `推力 ÷ 质量 × 时间`。

| 问题 2 方案 | 前 5 s 耗油（kg） | 前 5 s 的 Δv（m/s） | 后续化学段 Δv（m/s） | 合计 Δv（m/s） | 总燃烧时间（s） |
| --- | ---: | ---: | ---: | ---: | ---: |
| A，电推进同时耗油和用电 | 41.808365 | 111.218126 | 138.669578 | 249.887704 | 12.133313 |
| B，电推进只用电 | 40.788649 | 111.164919 | 141.030961 | 252.195880 | 12.258313 |

另用 1,000 kg 无燃料飞船验证方案 B。4,000 N 电推进工作 5 s，给出 20 m/s 的 Δv。方案 A 由正式推进计算的 `VacuumPropulsionTest` 验证。方案 B 的算例保留在 `PropellantBurnTest` 中。两组测试随默认构建执行。

## Sable 专用服实测

Minecraft 1.21.1、NeoForge 21.1.267、Sable 2.0.6、Java 21。测试飞船在无重力的 `blockspaceprogram:orbital_space` 中复用 `ShipCollisionBody`。用冲量前后的原生刚体速度差反推每 tick 的平均推力，电量设为 11.5 MJ，于第 5 tick 内耗尽。

| 采样 | 实测平均推力（N） | 本 tick 电推进工作（s） | 剩余电量（J） | 剩余燃料（kg） |
| --- | ---: | ---: | ---: | ---: |
| 第 4 tick，两台发动机满推力 | 23,999.997 | 0.050 | 1,500,000 | 98.327665 |
| 第 5 tick，电量耗尽 | 22,399.997 | 0.030 | 0 | 97.913661 |
| 第 6 tick，化学发动机继续 | 20,000.003 | 0.000 | 0 | 97.505774 |

## 轨道层长时间点火

在同一专用服内使用独立的 `OrbitalSavedData` 时钟。飞船初始圆轨道半径为 120 km，干质量 400 kg，燃料 4 kg，电池装满 36.069 MJ。20 N、2,000 s 比冲的电推进以 300 kW 工作。机动于轨道时钟 2.025 s 点火，沿顺行方向逐步施加冲量。每个轨道 tick 为 0.05 s，起止所在 tick 按实际点火时长计算。常速每服务端 tick 前进 1 个轨道 tick，加速前进 100 个。

| 时钟倍率 | 完成时的服务端 tick | 点火时长（s） | 熄火时刻（s） | 累计推进 Δv（m/s） | 剩余电量（J） | 剩余燃料（kg） |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 倍 | 2,446 | 120.230 | 122.255 | 5.952883 | 0 | 3.877400 |
| 100 倍 | 25 | 120.230 | 122.255 | 5.952883 | 0 | 3.877400 |

两次均消耗 36.069 MJ。熄火时的位置约为 `(102091.087, 63310.536, 0)` m，速度约为 `(-285.894, 467.040, 0)` m/s。日志中的位置和速度在两次试验中完全一致。

## 复测

从仓库根目录运行算例，再用 `runPropellantElectric` 启动实验专用服。将 Sable 2.0.6 NeoForge JAR 放到 `runs/propellantElectric/mods/`，在同一目录准备 `eula.txt`。 [Sable 2.0.6](https://modrinth.com/mod/sable/version/fg9dTRz9)

```powershell
.\gradlew.bat test --tests '*VacuumPropulsionTest' --tests '*PropellantBurnTest'
.\gradlew.bat -PpropellantElectric runPropellantElectric
```

Linux 或 macOS 使用 `./gradlew`。专用服加载完毕后，在控制台输入 `bsp_propellant_experiment`。查看 `runs/propellantElectric/logs/latest.log` 中的 `PROPELLANT SABLE` 和 `PROPELLANT ORBIT` 记录。常速试验约 2 分钟，全部断言通过时打印 `PROPELLANT PASS`。

移除 Sable 后，Minecraft 1.21.1、NeoForge 21.1.267 的专用服正常启动。执行同一命令，控制台提示实验需要 Sable。

## 正式接入范围

已选用 A/A/A。燃料留在各自的分离段，电推进同时消耗燃料和电力。分离前全船共享电力，分离件带走自己的电池剩余量。正式接入时，私人单人存档里按轨道数据传播的无人飞船可在时间加速下持续点火。 [决定 #116](https://github.com/jxt159753/BlockSpaceProgram/issues/116#决定)

| 范围 | 需同步的内容 |
| --- | --- |
| 零件数据映射 | `data/blockspaceprogram/data_maps/block/part_properties.json` 保留 `fuel_capacity` 的 ID 到 kg 映射，内置燃料 ID 改为 `blockspaceprogram:fuel`。`electric_capacity` 继续用 J，新增 `electric_power` 表示满推力功率，单位 W，默认 `0.0`。更新 `docs/part-properties.md` |
| 零件和蓝图 | `part_data.properties` 记录烘焙后的耗电属性，`ship_design` 中的零件同步带上它。`BakedPart.STREAM_CODEC` 的属性编码随 `BlockPartProperties.CODEC` 更新，影响物品同步和 `assembly_state` 发出的装配图。混合发动机汇总按 #118 的结论接入 |
| 飞行与轨道存档 | `AssembledShipEntity` 现有 `Fuel` 为按节点排列的剩余 kg 列表。增加等长的剩余 J 列表，例如 `Electric`。新发射按容量填充。碰撞、重建、分离、对接及入轨和下降交接都要传递两项。轨道存档 `blockspaceprogram_orbits` 的 `ships[].ship_data` 使用同一格式。若长点火跨存档，还需持久化开始时刻和机动进度 |
| 装配与飞行读数 | `StagePerformance` 按燃料或电量耗尽重新估计 Δv 和燃烧时间，推重比按点火时可用发动机计算。飞船实体同步数据新增剩余 J，可用于 `ShipFlightClient`，逐零件的推进剂存档留在服务端 |
| 轨道预估与网络包 | `OrbitalShipPropulsion`、`VacuumPropulsion` 要带剩余电量及各供油组的耗电与推力信息。`orbital_ship` 经 `OrbitalShipView` 把预估所需的数据传给轨道地图。`orbital_maneuver` 仍发送时间和目标 Δv。若区分电量不足，`orbital_maneuver_feedback` 的结果也要更新 |
| CC:Tweaked 和 KubeJS | 飞行和轨道共用的 `ShipFlightControl.Telemetry` 增加剩余 J，`FlightPeripheral.getTelemetry()` 同步该读数。KubeJS 能从飞行事件和轨道交接事件读到燃料和电量快照。`FUEL_DEPLETED` 仍表示燃料耗尽。同一艘船电力耗尽时，化学发动机可继续运行 |

正式存档格式、界面、装配跨级估算和分离后的电量继承有待接入时验收。原型的加速时钟用于验证资源结算，正式时间加速目前在计划机动前退出。
