# CC:Tweaked 飞控外设

在 Minecraft 1.21.1、NeoForge 21.1.267 的客户端和服务端安装 Sable 2.0.6 与 CC:Tweaked 1.120.2。用铁锭、红石粉和玻璃板无序合成飞控终端。 [CC:Tweaked 1.120.2 NeoForge](https://modrinth.com/version/1ewzHZYg)

1. 从发射台生成飞船，或用指令舱组装世界中的飞船。执行操作的玩家成为飞船所有者。
2. 在地面放置终端并右击，从同一维度已加载的自有飞船中选一艘。界面显示船的 UUID 前 8 位与位置。旧存档里没有所有者记录的船可通过保存蓝图并重新发射来绑定一艘新船。
3. 把 CC 电脑放在终端旁，也可以用有线调制解调器连接。连接到终端的电脑可控制已绑定的飞船。多人服可用方块与线路保护来限制接入。
4. 运行 [无人爬升脚本](examples/climb.lua)。脚本将船头保持竖直，设定 20 m/s 的垂直速度，并在海拔达到 1,000 m 时关闭节流。起飞前选好发动机级，并确认推力足以克服船重和阻力。可使用文档中的两级火箭。 [两级火箭搭建步骤](vab.md#两级火箭示例)

## Lua 接口

终端的外设类型为 `bsp_flight_terminal`，可用 `peripheral.find("bsp_flight_terminal")` 或 `peripheral.wrap(side)` 获取。设定值在物理步中生效，脚本可以在轮询遥测时休眠。

| 方法 | 行为 |
| --- | --- |
| `getTelemetry()` | 返回当前飞行数据和可用的轨道遥测 |
| `setThrottle(value)` | 设置 0 到 1 的固定节流 |
| `setVerticalSpeed(mps)` | 设置目标垂直速度，负数表示下降 |
| `setThrustToWeight(ratio)` | 设置非负的目标推重比 |
| `setAxes(pitch, yaw, roll)` | 设置船体 X、Y、Z 轴输入，各值在 −1 到 1 之间，并切换为手动姿态模式 |
| `setAttitudeMode(mode)` | 接受 `manual`、`kill_rotation`、`hold`、`prograde`、`retrograde`。选择 `hold` 时记录当前姿态 |
| `holdAttitude(pitch, yaw, roll)` | 保持指定姿态，角度单位为度，按 Y、X、Z 顺序表示旋转 |
| `stage()` | 请求进入下一级。有后续级时返回 `true`。用分级事件确认已执行 |
| `releaseControl()` | 主动交还控制权，关闭节流并保持当前姿态。交还成功时返回 `true` |

| 遥测字段 | 单位或含义 |
| --- | --- |
| `ship`、`dimension` | 飞船 UUID、当前维度 ID |
| `position`、`velocity` | 质心世界坐标（m）和世界坐标系速度（m/s）。表键为 `x`、`y`、`z` |
| `altitude`、`verticalSpeed` | 相对海平面的海拔（m）、垂直速度（m/s）。无天体配置的维度以 Y=0 为海平面 |
| `attitude`、`angularVelocity` | 姿态表含 `pitch`、`yaw`、`roll`，单位为度。角速度是世界坐标系下的 `x`、`y`、`z`，单位为 rad/s |
| `mass`、`fuel` | 湿质量、全船剩余燃料质量，单位为 kg |
| `thrust`、`availableThrust` | 实际合推力、已点火且完好有油的发动机在满节流时的合推力，单位为 N。均取船体坐标中推力矢量和的大小 |
| `gravity`、`thrustToWeight` | 当地重力加速度（m/s²）、实际推重比。重力为零时推重比返回 0 |
| `throttle`、`axes` | 仲裁后的节流与姿态输入。姿态表键为 `pitch`、`yaw`、`roll` |
| `stage`、`attitudeMode`、`throttleMode`、`landed` | 当前级（从 1 开始，待分级为 0）、模式 ID、着陆状态。节流模式 ID 为 `manual`、`direct`、`vertical_speed`、`thrust_to_weight` |
| `planet` | 当前天体的 `seaLevel`、`radius`、`surfaceGravity`、`seaLevelDensity`、`atmosphereTop`、`scaleHeight`。维度没有配置时为 `nil` |

电脑收到 `bsp_ship_fuel_depleted`、`bsp_ship_landed` 或 `bsp_ship_stage` 时，可用 `os.pullEvent()` 读取事件。参数依次为事件名、外设连接名、飞船 UUID 和当前级。燃料事件表示当前级的发动机因供油组耗尽而熄火。

驾驶员按住某一轴的键时，该轴由驾驶员控制。按 X 关闭节流并清除自动节流设定值。已绑定的飞船跨维度或进入轨道层时，终端仍可查询遥测，飞控模式和设定值会随船交接。飞船按轨道数据传播时，物理飞控暂停，设定值在恢复实体后继续生效。节流、姿态和分级指令需要已加载的实体飞船。

电脑关机、断开线路、终端拆除或所在区块卸载后，飞船在下一次物理计算中关闭节流，并保持当时的姿态。船在轨道数据中时，可用 `releaseControl()` 主动交还之前取得的控制权。终端绑定随存档保存，控制权与设定值在重启后重置。

每艘实体飞船同时接受一台电脑的物理飞控设定值。在终端继续加载的条件下，Sable 会为获得控制权的船保留区块加载票。每维度默认最多 8 艘，可在 `config/blockspaceprogram-server.toml` 的 `[flight]` 下调整 `maxRemoteShips`。释放控制或断开连接会移除这张票。恢复实体时如果所在维度的加载票已用完，飞船会关闭节流并保持当前姿态。 [性能预算 #22](https://github.com/jxt159753/BlockSpaceProgram/issues/22)

Java 调用方可在服务端从 `AssembledShipEntity.flightControl()` 获取飞控接口，并订阅 `ShipFlightEvent`。接口使用 Java 的零基分级和与 Lua 相同的物理单位。

## 轨道遥测与机动

在轨时，`getTelemetry().orbit` 提供下列字段。维度内飞行时该字段为 `nil`。轨道局部物理区内的状态也可用于读取轨道，纯径向等无法换算为轨道根数的状态返回 `nil`。

| 字段 | 单位或含义 |
| --- | --- |
| `soi` | 当前影响球的天体 ID，例如 `minecraft:overworld` |
| `seconds` | 共享轨道时钟上的当前时刻，单位为秒 |
| `onRails` | 飞船按轨道数据传播时为 `true`，在轨道局部物理区内为 `false` |
| `position`、`velocity` | 相对当前天体的惯性坐标和速度，单位为 m 和 m/s，表键为 `x`、`y`、`z` |
| `periapsis`、`apoapsis`、`period` | 近点海拔、远点海拔、周期，与轨道地图单位一致。开放轨道的远点和周期为 `nil` |
| `elements` | `semiMajorAxis`、`semiLatusRectum` 为 m，`eccentricity` 无量纲，`inclination`、`longitudeOfAscendingNode`、`argumentOfPeriapsis`、`meanAnomalyAtEpoch` 为弧度，`epochSeconds` 为历元秒数。抛物线的 `semiMajorAxis` 为 `nil` |
| `maneuver` | 节点的 `seconds`、`prograde`、`normal`、`radial` 和 `queued`。没有草稿或已排队节点时为 `nil` |

飞船按轨道数据传播时，顶层的 `dimension` 为 `blockspaceprogram:orbital_space`，顶层的 `position` 和 `velocity` 也使用天体惯性坐标。此时 `verticalSpeed` 为径向速度，姿态和角速度使用惯性轴，`throttle`、`thrust` 和 `axes` 为 0。

| 方法 | 行为 |
| --- | --- |
| `addManeuverNode(seconds, prograde, normal, radial)` | 新建或替换未排队的草稿。时间为绝对轨道秒数，必须在未来 4 小时内。Δv 沿顺向、法向和径向向外，各分量单位为 m/s，范围为 −10,000 到 10,000 |
| `executeManeuverNode()` | 校验草稿并排队。模组按轨道地图的流程，在指定时刻改变轨道并消耗燃料 |
| `deleteManeuverNode()` | 删除草稿或取消已排队的节点。删除成功时返回 `true`，没有节点时返回 `false` |
| `warpTo(seconds)` | 在未开放联机的单人存档里，安排轨道时钟快进到指定时刻。目标向上取整到轨道刻。遇到点火、下降或并入物理区前恢复 1 倍。专用服与开放联机的存档会抛出 Lua 错误 |

机动节点用于 `orbit.onRails` 为 `true` 的飞船。每艘船有一个节点位置。草稿保留到执行、删除、离开轨道数据或关闭存档。Lua 和地图的已排队节点共用同一位置。发动机推力抵消时，提交机动节点会抛出 Lua 错误，已排队的节点也会在执行时取消。可在母星低轨运行轨道机动脚本，比较点火前后的远点和燃料。 [轨道机动脚本](examples/orbital-maneuver.lua)

飞船进入轨道并滑行后，地面终端即可请求快进。`warpTo` 返回后，脚本可通过 `getTelemetry().orbit.seconds` 读取时钟。命令 `/bsp_warp stop` 可手动恢复常速。 [时间加速规则](orbit.md#时间加速)

| 事件名 | `os.pullEvent()` 的后续参数 |
| --- | --- |
| `bsp_ship_entered_orbit` | 外设连接名、飞船 UUID、进入时的天体 ID |
| `bsp_ship_left_orbit` | 外设连接名、飞船 UUID、返回的维度 ID |
| `bsp_ship_soi_changed` | 外设连接名、飞船 UUID、原天体 ID、新天体 ID |

Java 调用方可在服务端用 `OrbitalFlightControl(server, shipId, ownerId)` 查询并控制已验证归属的飞船。轨道事件由 `ShipOrbitEvent` 发布。 [KubeJS 飞行事件](flight-kubejs.md)
