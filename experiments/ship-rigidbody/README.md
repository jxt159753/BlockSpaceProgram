# 整船刚体原型

暂定方案 A，由一个 Sable 子层级承载整船。原型接收已变换到船体坐标系的零件包围盒，以 1/4 格为单位切入方块格。每轴有 10 种非空区间，组合成 1000 个隐形碰撞格状态。多个零件占用同一格时，取格内子盒的包围盒，因此凹角近似限制在该格内。 [设计决策 #33](https://github.com/jxt159753/BlockSpaceProgram/issues/33)

Sable 2.0.6 的公开物性接口按方块汇总整船数据，整船质量、质心和惯量的直接写入位于内部 Rapier 接口。原型将各零件的质量均摊给它覆盖的碰撞格，同格累加。每格质心取格内碰撞盒中心，惯量按均匀长方体计算，再由 Sable 汇总。 [公开物理接口](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/common/src/main/java/dev/ryanhcode/sable/api/physics/PhysicsPipeline.java) · [方块物性汇总](https://github.com/ryanhcode/sable/blob/ad8b4d377741fbd923f4d863b2cf9e462daff9a6/common/src/main/java/dev/ryanhcode/sable/api/physics/mass/MassTracker.java)

## 专用服实测

Minecraft 1.21.1、NeoForge 21.1.267、Sable 2.0.6、Create 6.0.10。主世界用默认 Sable 重力，原型的虚空维度用 `dimension_physics` 数据将重力设为零。

| 验证项 | 观测结果 |
| --- | --- |
| 创建子层级和自定义方块 | 方块定义包含 1000 个状态。代码创建子层级，写入并读回一个每轴从 0.25 到 0.75 的隐形碰撞格。另检查了负坐标切格和同格包围盒合并。 |
| 地形和 Create 平台碰撞 | 石台顶面 Y=81，半格载荷落稳时质心 Y=81.24719。Create 活塞运行到行程 4 格时，载荷质心 Y=85.19114。活塞到顶并静置后质心 Y=89.24719，净升高 8 格。 |
| 质量、质心和惯量 | 质量 2 和 6 的两个零件分配到三个格，格质量依次为 1、4、3。整船质量为 8，船体坐标中的质心为 (0.75, 0.5, 0.5)，惯量对角元约为 (0.8333, 4.8333, 4.3333)。通过预期质心施加线冲量 4，速度增加 0.5 且未引入转动。三轴角冲量得到预期角速度 (0.2, 0.7, 0.3)。 |
| 按维度关闭重力 | 同时释放刚体，主世界样本前 10 tick 下落 1.10237 格，虚空维度样本到第 80 tick 仍保持初始位置。 |
| 重建时交接位姿和速度 | 移去一个样本零件并重建，质心偏移 0.875 格。船体原点的位置误差约为 `9.58e-7` 格，线速度误差约为 `1.37e-8` 格/秒，旋转和角速度误差均小于 `1e-4`。下一 tick 的位置和姿态也在预测值的 `1e-4` 容差内。 |

重建时用同一船体原点计算新质心的世界坐标，再以 `v + ω × Δr` 求出原刚体在新质心处的速度，并继承角速度。

## 复测

将 Sable 2.0.6 和 Create 6.0.10 的 NeoForge JAR 放进 `runs/shipRigidBody/mods/`。 [Sable 下载](https://modrinth.com/mod/sable/version/fg9dTRz9) · [Create 下载](https://modrinth.com/mod/create/version/UjX6dr61)

在 `runs/shipRigidBody/server.properties` 中设置新的超平坦存档。以下配置可直接用于本机开发服。

```properties
level-name=ship-prototype
level-type=minecraft:flat
generator-settings={"biome":"minecraft:plains","layers":[{"height":1,"block":"minecraft:bedrock"}],"structure_overrides":[]}
server-ip=127.0.0.1
online-mode=false
```

从仓库根目录运行 `./gradlew -PshipRigidBody runShipRigidBody`。Windows PowerShell 使用 `.\gradlew.bat -PshipRigidBody runShipRigidBody`。

在专用服控制台加载两个维度的测试区块。

```mcfunction
forceload add 8 24
execute in blockspaceprogram:ship_test_void run forceload add 8 24
```

区块就绪后搭建石台和活塞，启动试验。原型会在载荷落稳后放置马达。

```mcfunction
fill 8 71 24 8 78 24 create:piston_extension_pole[facing=up]
setblock 8 79 24 create:mechanical_piston[facing=up,axis_along_first=true]
setblock 8 80 24 minecraft:stone
setblock 12 80 24 minecraft:stone
bspship verify
```

约 420 tick 后查看控制台或 `runs/shipRigidBody/logs/latest.log`。全部通过时打印 `SHIP PASS all checks`。再次复测需准备初始未伸出的活塞和空出的马达位置。

## 零件碰撞通道

使用同一运行配置，在无重力测试维度中造两道石墙。内侧平面分别位于 X=8 和 X=9，通道宽 1 格，沿 Z=24 至 Z=28 延伸。`bsppartcollision` 依次取同一件烘焙零件的原尺寸和 1/4 尺寸，从 Z=20 居中向正 Z 方向释放。

```mcfunction
execute in blockspaceprogram:ship_test_void run forceload add 8 24
execute in blockspaceprogram:ship_test_void run fill 7 79 24 7 82 27 minecraft:stone
execute in blockspaceprogram:ship_test_void run fill 9 79 24 9 82 27 minecraft:stone
bsppartcollision
```

| 比例 | 模型和碰撞体宽度（格） | 碰撞格数 | 烘焙质量与 Sable 读数（kg） | 60 tick 后的零件原点 Z |
| --- | ---: | ---: | ---: | ---: |
| 原尺寸 | 4 | 4 | 800 | 23.000，停在入口 |
| 1/4 | 1 | 1 | 800 | 32.001，通过开口 |

成功时专用服打印 `PART COLLISION PASS one-block opening`。若要在客户端观察，先切为旁观者并进入测试维度，在两轮试验开始前定位到 `8.5 86 18`，朝向 `0°`、俯角 `42°`。可按 F8 查看碰撞格。

## 后续接入

碰撞格由零件图生成。原型的物性修正存在内存中，正式接入时需要随零件图恢复。Sable 默认会按方块连通性拆分子层级，飞船接入时需要确保零件图决定何时拆分。此次交接实测覆盖服务器物理，零件模型和客户端插值随 #10 接入，分离和对接的完整动量交接随 #12、#35 验证。
