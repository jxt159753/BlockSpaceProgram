# 拼装发动机

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

## 数据映射

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
