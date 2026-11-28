# 发动机

## 拼装发动机

燃烧室可以用一个熔炉、一个铁锭和一个铜锭无序合成。一个铁锭和一个铜锭可无序合成四个喷管段。

1. 放置燃烧室。它的喷口朝向放置它的玩家。从下方仰视放置，喷口就会朝下。
2. 对着燃烧室的喷口放第一段喷管。沿第一段的侧面继续摆放，可在同一平面内拼出 `2×2` 或 `3×3` 的喷管。各段会接合成一个喷口，推力方向与排气方向相反。
3. 用烘焙台烘焙整个结构。一个零件可以有多台发动机。每组相连的喷管只接一座燃烧室。没有配对的燃烧室和喷管段可以作为装饰。

| 喷管尺寸 | 额定推力（N） | 真空比冲（s） | 海平面比冲（s） |
| --- | ---: | ---: | ---: |
| `1×1` | 6,000 | 260 | 235 |
| `2×2` | 6,000 | 330 | 200 |
| `3×3` | 6,000 | 400 | 160 |

零件提示显示额定推力与比冲。装配台的分级卡片显示各级开始点火时的合推力方向。同一零件的发动机在同一级点火，并使用同一燃料组。在装配台和世界内组装时，喷口正前方一格内有其他零件会显示遮挡提示，放置仍会成功。检查距离随零件比例缩放。 [装配与发射](vab.md)

### 数据映射

在数据包的 `data/blockspaceprogram/data_maps/block/engine_performance.json` 里配置燃烧室。键为燃烧室的方块 ID。下面是内置配置。

```json
{
  "values": {
    "blockspaceprogram:combustion_chamber": {
      "thrust": 6000,
      "electric_power": 0,
      "tiers": [
        { "segments": 1, "vacuum_specific_impulse": 260, "sea_level_specific_impulse": 235 },
        { "segments": 4, "vacuum_specific_impulse": 330, "sea_level_specific_impulse": 200 },
        { "segments": 9, "vacuum_specific_impulse": 400, "sea_level_specific_impulse": 160 }
      ]
    }
  }
}
```

- `thrust` 是一台发动机的额定推力，单位为 N。
- `electric_power` 是满推力耗电，单位为 W，省略时为 `0.0`。
- `tiers` 按 `segments` 由小到大排列，从 `1` 开始。烘焙时取阈值不超过实际段数的最高一档。两种比冲的单位均为秒。

燃烧室和喷管段的干质量分别为 40 kg 和 10 kg，由 `part_properties` 配置。执行 `/reload` 后，新烘焙的零件使用更新后的数值。已烘焙的零件、蓝图与飞船保存烘焙时的发动机数据。 [零件属性数据包](part-properties.md)

## 成品发动机

用一个高炉、一个避雷针和一个铜锭无序合成大型发动机。合成器也能制作，创造模式下还可以从 BlockSpaceProgram 标签页取出。它占用 `3×2×3` 格，干质量为 220 kg，顶面有一个 `3×3` 堆叠接口。喷口朝下，额定推力为 12,000 N，真空和海平面比冲分别为 350 s 和 235 s。

制作成品时不受烘焙台尺寸上限限制。装配台的零件数上限和发射台的满油质量上限按常规检查。用成品右击烘焙台会看到拆解提示，物品会保留。

### 零件模板

把模板放在 `data/<命名空间>/part_template/<名称>.json`。下面是内置大型发动机的模板。

```json
{
  "name": {"translate": "part_template.blockspaceprogram.large_engine", "fallback": "Large Engine"},
  "size": [3, 2, 3],
  "connectors": [
    {"kind": "stack", "center": [1.5, 2, 1.5], "normal": "up", "width": 3, "height": 3}
  ],
  "engines": [
    {
      "outlet": {"position": [1.5, 0, 1.5], "face": "down"},
      "performance": {"thrust": 12000, "vacuum_specific_impulse": 350, "sea_level_specific_impulse": 235},
      "electric_power": 0
    }
  ],
  "properties": {"mass": 220, "drag_coefficient": 0.6, "impact_resistance": 12},
  "display_model": "blockspaceprogram:part/large_engine"
}
```

- `name` 是物品名称的文本组件。把 `translate` 对应的文字写入资源包的语言文件，`fallback` 用于缺少对应翻译时显示。
- `size` 是未缩放的 `[x, y, z]` 尺寸，每边为 1 到 9 格。原点位于包围盒的最小角，碰撞格填满整个包围盒。
- `connectors` 是接口列表，可为空。`kind` 支持 `stack`、`separation`、`docking` 和 `surface`。`center` 是接口中心，`normal` 是所在面的外法线。`width` 和 `height` 为未缩放的整格尺寸，接口须完全落在所在面上，堆叠类接口须为正方形。
- `engines` 是发动机列表，可为空。`outlet.position` 是喷口在未缩放零件坐标中的位置，须落在 `outlet.face` 指定的面上。`face` 是排气方向，推力方向与它相反。`performance.thrust` 的单位为 N，`performance.vacuum_specific_impulse` 和 `performance.sea_level_specific_impulse` 的单位为 s。`electric_power` 是满推力耗电功率，单位为 W，省略时为 0。
- `properties` 沿用 `part_properties` 的字段和默认值。`mass`、`fuel_capacity`、`electric_capacity`、`drag_coefficient`、`heat_resistance` 和 `impact_resistance` 写入零件。`thrust`、`vacuum_specific_impulse`、`sea_level_specific_impulse` 和 `electric_power` 的零件级读数从 `engines` 汇总。 [零件属性数据包](part-properties.md)
- `display_model` 可省略。省略时成品按填充包围盒的铁块结构渲染。

`normal` 和 `face` 接受 `down`、`up`、`north`、`south`、`west` 和 `east`。制作时选择接口尺寸配置允许的最大比例。

模型 ID `<命名空间>:part/<名称>` 指向 `assets/<命名空间>/models/part/<名称>.json`。用 Blockbench 导出原版方块模型，把模型坐标限制在 0–16 内。渲染时各轴独立转换为 `模型坐标 × size ÷ 16`，再应用零件比例。例如模板尺寸为 `[3, 2, 3]` 时，模型坐标 `[8, 16, 8]` 对应未缩放的 `[1.5, 2, 1.5]`。

### 合成配方

把配方放在 `data/<命名空间>/recipe/<名称>.json`。`ingredients` 使用普通无序配方的材料写法，`template` 写完整的模板 ID。也可以用 `category` 和 `group` 设置配方书分类和分组。用 `/recipe give <玩家> blockspaceprogram:large_engine` 可解锁内置配方。

```json
{
  "type": "blockspaceprogram:part_template",
  "category": "equipment",
  "template": "blockspaceprogram:large_engine",
  "ingredients": [
    {"item": "minecraft:blast_furnace"},
    {"item": "minecraft:lightning_rod"},
    {"item": "minecraft:copper_ingot"}
  ]
}
```

数据包覆盖同 ID 的模板后，执行 `/reload` 即可让新合成的零件使用新数值。已制作的零件以及包含这些零件的蓝图和飞船保存原来的数值、名称组件和模型 ID。
