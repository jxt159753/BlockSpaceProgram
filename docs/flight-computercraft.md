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
| `getTelemetry()` | 返回下表中的当前飞行数据 |
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
| `thrust`、`availableThrust` | 实际合推力、当前级有油可用时的最大合推力，单位为 N |
| `gravity`、`thrustToWeight` | 当地重力加速度（m/s²）、实际推重比。重力为零时推重比返回 0 |
| `throttle`、`axes` | 仲裁后的节流与姿态输入。姿态表键为 `pitch`、`yaw`、`roll` |
| `stage`、`attitudeMode`、`throttleMode`、`landed` | 当前级（从 1 开始，待分级为 0）、模式 ID、着陆状态。节流模式 ID 为 `manual`、`direct`、`vertical_speed`、`thrust_to_weight` |
| `planet` | 当前天体的 `seaLevel`、`radius`、`surfaceGravity`、`seaLevelDensity`、`atmosphereTop`、`scaleHeight`。维度没有配置时为 `nil` |

电脑收到 `bsp_ship_fuel_depleted`、`bsp_ship_landed` 或 `bsp_ship_stage` 时，可用 `os.pullEvent()` 读取事件。参数依次为事件名、外设连接名、飞船 UUID 和当前级。燃料事件表示当前级的发动机因供油组耗尽而熄火。

驾驶员按住某一轴的键时，该轴由驾驶员控制。按 X 关闭节流并清除自动节流设定值。电脑关机、断开线路、终端拆除或所在区块卸载后，飞船在下一次物理计算中关闭节流，并保持当时的姿态。开机后脚本可以重新设置目标。终端绑定随存档保存，控制权与设定值在重启后重置。

每艘船同时接受一台电脑的设定值。其他已连接的电脑可查询数据和接收事件。在终端继续加载的条件下，Sable 会为获得控制权的船保留区块加载票。每维度默认最多 8 艘，可在 `config/blockspaceprogram-server.toml` 的 `[flight]` 下调整 `maxRemoteShips`。释放控制或断开连接会移除这张票。 [性能预算 #22](https://github.com/jxt159753/BlockSpaceProgram/issues/22)

Java 调用方可在服务端从 `AssembledShipEntity.flightControl()` 获取飞控接口，并订阅 `ShipFlightEvent`。接口使用 Java 的零基分级和与 Lua 相同的物理单位。
