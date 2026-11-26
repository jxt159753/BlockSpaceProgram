# 零件属性数据包

在存档的 `datapacks/` 下创建数据包。Minecraft 1.21.1 的 `pack.mcmeta` 可以写成 `{"pack":{"pack_format":48,"description":"零件属性"}}`。

在数据包内创建 `data/blockspaceprogram/data_maps/block/part_properties.json`，用方块 ID 或带 `#` 的方块标签指定一块方块在烘焙时提供的数值。以下示例给全部木板配置质量，并给切制铜块配置功能属性。

```json
{
  "values": {
    "#minecraft:planks": {
      "mass": 45.0
    },
    "minecraft:cut_copper": {
      "mass": 75.0,
      "fuel_capacity": {
        "example:liquid_fuel": 30.0,
        "example:oxidizer": 36.0
      },
      "thrust": 12000.0,
      "vacuum_specific_impulse": 320.0,
      "sea_level_specific_impulse": 270.0,
      "drag_coefficient": 0.25,
      "heat_resistance": 900.0,
      "electric_capacity": 1500.0
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
| `drag_coefficient` | 阻力系数，无量纲 | `0.0` |
| `heat_resistance` | 耐热温度，K | `0.0` |
| `electric_capacity` | 电量容量，J | `0.0` |

所有数值须为有限非负数。燃料 ID 使用 `命名空间:名称`。未配置的非空气方块使用表中的默认值，空气的属性为零。模组内置木板、铁块和空炼药锅的属性，可用优先级更高的数据包覆盖。同一方块匹配多条条目时，后加载的记录整体覆盖先加载的记录，省略的字段取默认值。 [NeoForge Data Maps](https://docs.neoforged.net/docs/1.21.1/resources/server/datamaps/)

## 零件类型

将喷管和座椅方块的 ID 分别加入下面两个方块标签。两个标签的内置内容均为空。例如可在 `engine_nozzles.json` 写入 `{"values":["minecraft:cut_copper"]}`，让上例的切制铜块充当测试用喷管。

- `data/blockspaceprogram/tags/block/engine_nozzles.json` 中的方块使结构具备发动机类型 `ENGINE`。
- `data/blockspaceprogram/tags/block/command_seats.json` 中的方块使结构具备指令舱类型 `COMMAND_POD`。
- 有任意一种正容量燃料的方块使结构具备燃料舱类型 `FUEL_TANK`。
- 一个结构可以同时具备多种功能类型。无功能类型的结构归为 `STRUCTURAL`。

执行 `/reload` 后，服务器上的 `BlockPartProperties.get(BlockState)` 和 `PartType.classify(Iterable<BlockState>)` 会使用更新后的属性和标签。

烘焙台使用当前数据包的属性生成零件。[烘焙与拆解](part-baking.md)
