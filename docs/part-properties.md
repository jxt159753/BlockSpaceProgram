# 零件属性数据包

在存档的 `datapacks/` 下创建数据包。Minecraft 1.21.1 的 `pack.mcmeta` 可以写成 `{"pack":{"pack_format":48,"description":"零件属性"}}`。

在数据包内创建 `data/blockspaceprogram/data_maps/block/part_properties.json`，用方块 ID 或带 `#` 的方块标签指定方块属性。以下示例给全部木板配置质量，并给切制铜块配置功能属性。

```json
{
  "values": {
    "#minecraft:planks": {
      "mass": 45.0
    },
    "minecraft:cut_copper": {
      "mass": 75.0,
      "fuel_capacity": {
        "blockspaceprogram:fuel": 66.0
      },
      "thrust": 12000.0,
      "vacuum_specific_impulse": 320.0,
      "sea_level_specific_impulse": 270.0,
      "drag_coefficient": 0.25,
      "heat_resistance": 900.0,
      "electric_capacity": 1500.0,
      "electric_power": 300.0,
      "impact_resistance": 12.0
    }
  }
}
```

| 字段 | 含义 | 默认值 |
| --- | --- | --- |
| `mass` | 干质量，kg | `1.0` |
| `fuel_capacity` | 按燃料 ID 配置的容量，每种以 kg 计 | `{}` |
| `thrust` | 推力，N | `0.0` |
| `vacuum_specific_impulse` | 真空比冲，s | `0.0` |
| `sea_level_specific_impulse` | 海平面比冲，s | `0.0` |
| `drag_coefficient` | 方块迎风面的阻力系数，无量纲 | `1.0` |
| `heat_resistance` | 耐热温度，K | `0.0` |
| `electric_capacity` | 电量容量，J | `0.0` |
| `electric_power` | 满推力时的耗电功率，W | `0.0` |
| `impact_resistance` | 耐冲击速度，m/s | `8.0` |

所有数值须为有限非负数。内置燃料 ID 为 `blockspaceprogram:fuel`。当前飞行计算将容量表中的各项合计为可用燃料。未配置的非空气方块使用表中的默认值，空气的属性为零。模组内置木板、铁块、空炼药锅、燃烧室和喷管段的属性，可用优先级更高的数据包覆盖。同一方块匹配多条条目时，后加载的记录整体覆盖先加载的记录，省略的字段取默认值。 [NeoForge Data Maps](https://docs.neoforged.net/docs/1.21.1/resources/server/datamaps/)

建立飞行状态时，系统按当前数据映射读取烘焙零件与船体方块的阻力系数。生成、读档、分离和对接都会触发投影计算。修改数据包并执行 `/reload` 后，下次重建飞行状态时会应用新值。设为 0 的方块仍有迎风面积并遮挡后方。 [遮挡与压心](flight.md#气动阻力)

烘焙时将各方块的电量容量相加。没有燃烧室时，零件的 `electric_power` 取有推力方块的功率加权平均值，权重为各方块的推力。汇总功率与汇总推力一起平分给各个带标签的喷口。有燃烧室时，推力、比冲和耗电由 `engine_performance` 提供。拼装发动机的功率按台记录，功率大于零的发动机同时消耗燃料和电力。 [拼装发动机](engines.md)

零件烘焙时取组成方块中最低的耐冲击速度。旧零件和蓝图中的零件使用 8 m/s 的默认耐冲击速度。 [着陆和撞击损坏](flight.md#着陆)

## 降落伞性能

`data/blockspaceprogram/data_maps/block/parachute_performance.json` 以方块为键。每个匹配的方块提供一顶伞，挂点位于该方块中心。内置条目适用于 `blockspaceprogram:parachute`。

| 字段 | 内置值 | 含义 |
| --- | ---: | --- |
| `semi_drag_area` | 20 | 半开的阻力面积，单位 m² |
| `drag_area` | 400 | 全开的阻力面积，单位 m²，至少等于半开值 |
| `min_relative_density` | 0.02 | 半开所需的最低相对大气密度 |
| `max_deploy_speed` | 250 | 允许半开的最大空速，单位 m/s |
| `full_deploy_height` | 500 | 全开所需的最大离地高度，单位 m |
| `opening_time` | 2 | 每段展开时长，单位 s。阻力面积在这段时间内线性增加 |

烘焙时，挂点和六个参数随零件写入 `part_data`。挂点随零件缩放和旋转，阻力面积沿用烘焙时的数值。 [飞行中的开伞条件](flight.md#降落伞)

## 零件类型

将喷管和座椅方块的 ID 分别加入下面两个方块标签。两个标签的内置内容均为空。例如可在 `engine_nozzles.json` 写入 `{"values":["minecraft:cut_copper"]}`，让上例的切制铜块充当测试用喷管。

- 没有燃烧室时，`data/blockspaceprogram/tags/block/engine_nozzles.json` 中的方块可充当喷口。有汇总推力和真空比冲时，每个喷口算一台发动机，结构具备 `ENGINE` 类型。有燃烧室的零件按相连的喷管段识别发动机。
- `data/blockspaceprogram/tags/block/command_seats.json` 中的方块使结构具备指令舱类型 `COMMAND_POD`。
- `data/blockspaceprogram/tags/block/landing_legs.json` 中的方块使结构具备着陆腿类型 `LANDING_LEG`，内置标记为避雷针。
- 有 `parachute_performance` 条目的方块使结构具备降落伞类型 `PARACHUTE`。同一零件可以同时具备 `ENGINE` 和 `PARACHUTE`。
- 有任意一种正容量燃料的方块使结构具备燃料舱类型 `FUEL_TANK`。
- 一个结构可以同时具备多种功能类型。无功能类型的结构归为 `STRUCTURAL`。

执行 `/reload` 后，新烘焙的零件使用更新后的属性和标签。已烘焙的零件保留当时的属性、发动机和降落伞列表。 [烘焙与拆解](part-baking.md)
