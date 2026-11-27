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

## 轨道地图与机动

- 按 **M** 打开地图。点击飞船或使用「飞船」按钮切换控制对象。点击天体、按住 **Shift** 点击另一艘飞船，或用「目标」按钮轮换目标。右键拖拽旋转视角，滚轮缩放。
- 点击选中飞船的青色轨道线放置或移动节点。横向拖动右侧三条 Δv 数值可调节顺向、法向和径向外分量。按住 **Shift** 精调，按住 **Ctrl** 加快。顺向沿瞬时速度，法向垂直于轨道面，径向外在轨道面内垂直于顺向。金色虚线显示机动后的一小时预测轨迹。
- 目标面板显示未来一小时内的最近距离、到达倒计时和相对速度。已放节点时从该节点开始搜索。天体距离按中心计算，预测进入卫星影响球时标记为交会。若轨迹与目标表面相交，面板和预览线显示首次表面接触。预测使用与服务端一致的拼接圆锥曲线模型。
- 对同向共面的两条圆轨道，面板显示当前相位角和霍曼出发相位角。「霍曼转移」会把一次出发机动放在下一个窗口。M3 的母星 10 km 圆轨道到卫星轨道，出发 Δv 约为 **208.7 m/s**。此节点用来预览转移段和交会。 [NASA 霍曼转移说明](https://science.nasa.gov/learn/basics-of-space-flight/chapter14-1/)
- 单人存档房主和服务器管理员可提交或取消点火。每艘船可提交一个节点，节点时刻以轨道秒保存。界面用当前级的真空推力、有效供油与船重计算所需燃料和等效点火时长。到达节点时服务端以一次瞬时机动更新根数，并扣除供油组中各油箱的燃料。权限细化依赖飞船所有者记录。 [NASA 火箭方程](https://www1.grc.nasa.gov/beginners-guide-to-aeronautics/ideal-rocket-equation/) · [飞船所有者 #42](https://github.com/jxt159753/BlockSpaceProgram/issues/42)
