# KubeJS 飞行事件

在 Minecraft 1.21.1 的服务端安装 KubeJS。把脚本放在 `kubejs/server_scripts/`，启动服务器或执行 `/reload`。用 `NativeEvents.onEvent` 订阅 BlockSpaceProgram 发布到 NeoForge 游戏事件总线的事件。

| 事件类 | `kind()` | 触发时机 |
| --- | --- | --- |
| `assembly.ShipFlightEvent` | `FUEL_DEPLETED` | 正在供油的燃料组耗尽 |
| `assembly.ShipFlightEvent` | `ELECTRIC_DEPLETED` | 工作中的电推进耗尽全船电量时发出一次。恢复供电后再次耗尽时会再发出 |
| `assembly.ShipFlightEvent` | `LANDED` | 飞船离地后重新获得碰撞支撑，速度降到着陆阈值。停在地面上的飞船只发一次 |
| `assembly.ShipFlightEvent` | `STAGE_CHANGED` | 飞船成功执行分级。有船体分开时，`separatedShipIds()` 包含其余船体的 UUID。单纯切换级序时列表为空 |
| `orbit.ShipOrbitEvent` | `ENTERED_ORBIT` | 升空交接完成。无人飞船已写入轨道存档，载人飞船已进入轨道局部物理区 |
| `orbit.ShipOrbitEvent` | `LEFT_ORBIT` | 下降交接完成。飞船已在目标维度恢复实体 |
| `orbit.ShipOrbitEvent` | `SOI_CHANGED` | 轨道传播切换中心天体，或局部物理区切换共享参考天体。一次传播越过多个边界时依次发出事件 |

`ShipFlightEvent.shipId()` 返回船只 UUID，`ownerId()` 返回归属玩家的 UUID，`dimensionId()` 返回所在维度。Java 调用方也可以通过 `ship()` 取得飞船实体。`stage()` 是保留船体当前级的零基编号，待分级时为 `-1`。

在 `ShipFlightEvent` 中可用 `event.ship().flightControl().telemetry()` 读取推力。`thrust()` 是最近一次物理步施加的合推力，`availableThrust()` 是已点火、完好且有供能的发动机在满节流时的合推力。两者均取船体坐标中推力矢量和的大小，单位为 N。分级事件中可用 `availableThrust()` 读取保留船体分离后的可用推力。

两个事件类都提供 `event.fuel()` 和 `event.electric()`，分别返回事件发生时的剩余燃料（kg）和电量（J）。分级事件报告保留船体的读数。

`ShipOrbitEvent.shipId()` 和 `ownerId()` 返回相同用途的 UUID，无需加载船体实体。`center()` 返回当前天体 ID，下降交接时返回目标维度 ID。`orbit().elements()` 返回交接或切换时的轨道根数，下降交接时为离开前的轨道。轨道局部物理区的 SOI 切换和下降事件报告共享参考轨道。`orbit().elements().epochSeconds()` 是从轨道时钟起点计的秒数。`previousCenter()` 在 SOI 事件中返回原天体 ID。旧存档或无主飞船的归属 UUID 可能为 `null`。

入轨事件表示成功切入轨道层。合同若要求稳定的束缚轨道，可以用偏心率和近点距离继续判断。调试指令直接设定轨道、轨道地图预测以及轨道层内部的实体加载均不发出入轨事件。

下面的脚本在服务端记录燃料与电量耗尽、着陆、分级分离、轨道交接和影响球切换。科研、合同进度和团队归属由整合包脚本处理。

```js
const BspShipFlightEvent = Java.loadClass('io.github.jxt159753.blockspaceprogram.assembly.ShipFlightEvent')
const BspShipOrbitEvent = Java.loadClass('io.github.jxt159753.blockspaceprogram.orbit.ShipOrbitEvent')

NativeEvents.onEvent(BspShipFlightEvent, event => {
  const kind = String(event.kind())
  const id = event.shipId()
  const owner = event.ownerId()
  const fragments = event.separatedShipIds()

  if (kind === 'FUEL_DEPLETED' || kind === 'ELECTRIC_DEPLETED') {
    console.info(`[BSP] ${kind} ship=${id} fuel=${event.fuel()} electric=${event.electric()}`)
  } else if (kind === 'LANDED') {
    console.info(`[BSP] LANDED ship=${id} owner=${owner} dimension=${event.dimensionId()}`)
  } else if (kind === 'STAGE_CHANGED' && fragments.size() > 0) {
    console.info(`[BSP] STAGE_SEPARATED ship=${id} owner=${owner} stage=${event.stage()} fragments=${fragments}`)
  }
})

NativeEvents.onEvent(BspShipOrbitEvent, event => {
  const kind = String(event.kind())
  const previous = event.previousCenter()
  const orbit = event.orbit()
  console.info(`[BSP] ${kind} ship=${event.shipId()} owner=${event.ownerId()} from=${previous} to=${event.center()} epoch=${orbit.elements().epochSeconds()}`)
})
```

Java 模组也可以订阅同一组事件。安装 Sable 后可以复测飞行交接、着陆和分级。 [飞船驾驶](flight.md) · [轨道数据](orbit.md)
