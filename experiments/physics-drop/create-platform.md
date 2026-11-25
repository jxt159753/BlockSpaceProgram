# Create 活塞平台举升 Sable 刚体

Minecraft 1.21.1、NeoForge 21.1.267，专用服务器，默认重力，20 TPS。Create 6.0.10 的创造马达以 16 RPM 驱动向上的机械活塞。活塞推动一块石头，石台顶面从 Y=81 升至 Y=89。载荷是一块用 Sable 2.0.6 的 `sable spawn block` 创建的铜块，独立于活塞结构，`sable info` 显示质量为 1.0。[Create 6.0.10](https://modrinth.com/mod/create/version/UjX6dr61) · [Sable 2.0.6](https://modrinth.com/mod/sable/version/fg9dTRz9)

| 采样时机 | 活塞伸出距离（格） | 铜块质心 Y（格） |
| --- | ---: | ---: |
| 启动前，铜块静止 | 0 | 81.4972 |
| 活塞运动中 | 2.125 | 83.5669 |
| 活塞运动中 | 4.250 | 85.6919 |
| 活塞运动中 | 6.34375 | 87.7857 |
| 活塞到顶，铜块静止 | 8 | 89.4972 |

三次中途读数中，活塞的 `Running` 都为 `1b`，移动机构实体的锚点高度均为 `80 + Offset`。到顶后石台重新成为世界方块，铜块质心累计升高 8 格，最终线速度和角速度均为零。这个实验验证了地面移动机构对自由刚体的举升。

## 复测

在装有上述两个模组的全新超平坦主世界测试存档中执行。先在服务器控制台运行 `forceload add 8 24`，待区块载入后搭建一座有八根延伸杆的升降台，在台面中央生成铜块。

```mcfunction
fill 8 71 24 8 78 24 create:piston_extension_pole[facing=up]
setblock 8 79 24 create:mechanical_piston[facing=up,axis_along_first=true]
setblock 8 80 24 minecraft:stone
execute positioned 8.5 81.6 24.5 run sable spawn block minecraft:copper_block lift_test
sable forceload add @e[name=lift_test]
```

等铜块落稳后，在控制台依次读取初始高度、启动马达。

```mcfunction
sable info @e[name=lift_test]
setblock 9 79 24 create:creative_motor[facing=west]
```

保持服务器正常走时，在约 13 秒的上升过程中多次连续执行下列三条命令。分别读取活塞的 `Running` 和 `Offset`、移动机构实体的位置，以及铜块的 `Position`。手动采样时，读到的中途行程取决于命令执行时刻。

```mcfunction
data get block 8 79 24
data get entity @e[type=create:stationary_contraption,x=8,y=84,z=24,distance=..10,limit=1] Pos
sable info @e[name=lift_test]
```

到顶并静置后，执行 `execute if block 8 88 24 minecraft:stone` 验证平台的位置，再运行 `sable info @e[name=lift_test]` 读取铜块的最终高度和速度。
