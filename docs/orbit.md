# 轨道数据

所有维度共用主世界存档中的轨道时钟和飞船记录，文件为 `data/blockspaceprogram_orbits.dat`。服务端每游戏刻推进 0.05 轨道秒，20 TPS 时与现实时间同速。天体与飞船用同一时刻求值，位置单位为米，速度单位为米每秒。惯性坐标系使用 XY 参考平面和 +Z 北向，角度用弧度。 [时间方案 #2](https://github.com/jxt159753/BlockSpaceProgram/issues/2)

## M3 天体

| 标识 | 半径（m） | 引力参数 μ（m³/s²） | 父天体 |
| --- | ---: | ---: | --- |
| `minecraft:overworld` | 60,000 | 3.528 × 10¹⁰ | 无 |
| `blockspaceprogram:moon` | 12,000 | 2.304 × 10⁸ | `minecraft:overworld` |

卫星的半长轴为 360,000 m，偏心率、倾角、升交点经度、近地点幅角和历元平近点角均为 0，历元为轨道时钟 0 秒。母星海拔 10 km 的圆轨道周期约 10.3 分钟，霍曼转移至卫星轨道半径约需 27.8 分钟。 [星球尺度 #4](https://github.com/jxt159753/BlockSpaceProgram/issues/4)

## 调用方式

- `OrbitElements.stateAt` 按时间计算位置与速度，`fromState` 从同刻的位置与速度求根数。根数以半通径和偏心率表达轨道大小，并提供半长轴、近点和远点。椭圆、抛物线和双曲线使用同一数据结构。 [JPL 开普勒方程](https://ssd.jpl.nasa.gov/planets/approx_pos.html)
- `OrbitalSystem.M3` 提供天体参数、同刻的天体位置与速度，以及拼接圆锥曲线传播。影响球半径取 `a × (μ卫星 / μ父天体)^(2/5)`。进出影响球时使用同一惯性系中的位置和速度拟合新圆锥曲线。服务端只检查轨道范围可能与某个影响球边界相交的飞船。
- `OrbitalSavedData.get(server)` 提供权威时刻和记录。交接方可用 `put` 存入飞船，再从 `ship` 读取船体 NBT、船体到惯性系的姿态四元数和惯性系角速度（rad/s）。仅在目标实体成功接管后调用 `remove`。维度和局部物理区的实体交接由 #13 和 #34 接入。 [维度交接 #13](https://github.com/jxt159753/BlockSpaceProgram/issues/13) · [局部物理区 #34](https://github.com/jxt159753/BlockSpaceProgram/issues/34)
- 客户端可用 `ClientOrbitalState` 读取服务端时刻和按 UUID 索引的轨道。登录时收到整份列表，之后接收轨道变更和删除，以及每轨道秒一次的时钟更新。地图可用共享的 `OrbitalSystem.M3` 做同刻求值和未来轨道预演。 [地图 #15](https://github.com/jxt159753/BlockSpaceProgram/issues/15)
