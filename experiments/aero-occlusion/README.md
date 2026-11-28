# 气动阻力与遮挡原型

原型验证六向迎风投影、方块阻力系数、压心力矩和侧挂整流罩。传入 `-PaeroOcclusion` 时，Gradle 将实验补丁应用到构建目录。 [设计决策 #135](https://github.com/jxt159753/BlockSpaceProgram/issues/135)

复测使用 Git 提交 `9a90935` 的源码和资源作为基线。运行前确认本地仓库包含该提交。

## 原型规则

- 飞船建立飞行状态时，将每个占用方块作为对应缩放的立方体，投影到船体坐标的六个方向。缩放、旋转、偏移按 1/4 格处理。每个投影格计 0.0625 m²，只由最前方的方块贡献阻力和所在节点的迎风面积。
- 运行时以船体坐标中空速单位向量的分量绝对值加权。阻力使用系数乘面积。压心用同一组权重合成迎风格的几何面积矩。显式配置系数为 0 的方块仍能遮挡后面的方块。
- 阻力沿空速反方向，沿用原有大气密度、阻力冲量上限和悬停估算。压心与质心的力臂产生力矩。`blockspaceprogram-server.toml` 的 `[aerodynamics].torque_multiplier` 范围是 0–1，默认 1。
- 未配置方块的试验系数为 1.0，船体和烘焙零件都从 `part_properties` 读取。生成、分离、对接和读档重建飞行状态时重算。

## 实测结果

测试基线为 `4ddb064`。本机使用 Intel Core Ultra 9 275HX、Java 21、Minecraft 1.21.1、NeoForge 21.1.267 和 Sable 2.0.6。两组使用同一超平坦地形和大气参数。

3 项 JUnit 4 算例通过。三节 3×3×3 零件沿 Y 轴叠放，±Y 迎风面积为 9 m²，±X 与 ±Z 为 27 m²。把顶层阻力系数改为 0.3 后，+Y 的阻力面积从 9 降到 2.7 m²，−Y 仍为 9 m²。侧向阻力产生的力矩把底部转向来流。偏移 1/4 格的算例也确认了零阻力方块的遮挡效果。

### 返回舱

返回舱外形为 3×3×3，质量 5,400 kg。测试数据包让从底到顶每层的单块质量分别为 350、200 和 50 kg。斜入从海拔 4,999 m 开始，速度为 `(745, -30, 0) m/s`。两组的 X 起点按各自航程布置，让末段经过同一片超平坦场地。

| 测点 | 现有阻力空速 | 六向投影与压心空速 |
| --- | ---: | ---: |
| 海拔 4,999 m | 745.60 m/s | 745.60 m/s |
| 海拔 4,000 m | 186.92 m/s | 186.92 m/s |
| 海拔 3,000 m | 108.97 m/s | 106.69 m/s |
| 海拔 2,000 m | 99.64 m/s | 93.09 m/s |
| 海拔 1,000 m | 101.49 m/s | 84.77 m/s |
| 海拔 500 m | 103.09 m/s | 77.82 m/s |
| 海拔 100 m | 104.44 m/s | 70.67 m/s |
| 地面附近 | 104.77 m/s（接触前一刻） | 68.59 m/s（插值到地面） |

大气顶以上的水平速度在 16.50 秒内由 745 降至 169.04 m/s。两组在该段的数据相同。此次专用服上的 Sable 在大气阻力生效前已经明显减速。

斜入的基线触地速度取无客户端补测中的实际接触前一刻。候选组的斜入刚体穿过地面。68.59 m/s 来自船体包围盒最低点跨过铺地高度时的速度插值，候选斜入的实际触地速度尚未验证。

侧倾场景以 90° 侧倾和 300 m/s 垂直下落开始，飞控为手动。六向模型下，底部在放手后 7.95 秒首次对准到 15° 以内。扣除进入大气前的 3.71 秒后为 4.24 秒。触地前夹角 8.44°，空速 67.94 m/s。现有模型的触地前夹角为 90°，空速为 118.82 m/s。

### 两级火箭和整流罩

火箭满载质量 547 kg，发射时机体偏转 1°，姿态保持的目标为竖直。下级耗尽后执行分离并点燃上级。最大偏离角取机体上方向和垂直方向的夹角。反作用轮按 1,500 N·m 上限记录每个物理子步。 [装配台示例](../../docs/vab.md#两级火箭示例)

| 模型 | 最高点海拔 | 最大偏离角 | 轮饱和子步 |
| --- | ---: | ---: | ---: |
| 现有阻力 | 26,076.9 m | 0.996° | 0 |
| 六向投影与压心 | 24,784.0 m | 0.996° | 0 |

系数 1.0 让该火箭的最高点降低 1,293 m（约 5.0%）。同一反作用轮可以保持姿态。

用阻力系数 0.3 的云杉木板制作两片半壳，按二重对称侧挂。全船 +Y 几何迎风面积保持 3 m²。装罩后阻力面积从 3 降到 1.6 m²，载荷迎风面积从 2 降到 0 m²。分级后阻力面积恢复为 3 m²，载荷恢复为 2 m²。分离 1.2 秒后，两片半壳中心间距为 6.10 m。

### 投影存储和服务端耗时

备选缓存对每个零件保存六组面格。每格记录烘焙坐标中的面角点和方块阻力系数。缓存读取后仍要按零件缩放、旋转和所在节点合成全船遮挡。实测对照检查了面积、阻力面积、压心和节点受风面积，也覆盖带 1/4 格偏移的旋转缩放零件。

| 样本 | 按方块重算 | 读取面格后合成 | 原始 NBT 字节数 | 缓存 NBT 字节数 | 原始 GZip 字节数 | 缓存 GZip 字节数 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 9×9×9 实心零件 | 3.04 ms | 1.63 ms | 44,817 | 52,640 | 1,904 | 3,253 |
| 63 节点飞船 | 4.94 ms | 3.09 ms | 97,873 | 130,669 | 1,991 | 2,551 |

耗时取预热后 40 次构建中位数。从内存中的 NBT 读取备选缓存。大小取同一飞船布局的独立 NBT，包含零件面格字段前后两组编码。建议在飞行状态重建时按方块计算。该 63 节点样本的缓存只省约 1.85 ms，并会把阻力系数固定为烘焙时的值。

63 节点由 36 件烘焙零件和 27 个船体节点组成。专用服执行分级后得到 36 和 27 节点的两艘船。第一次重算分别用 2.00 和 2.57 ms，共 4.57 ms。复测两艘合计 3.46 ms。

两组各连接 1 个同版本客户端。10 艘 63 节点飞船从世界 Y=3,100 m 以 15 m/s 下落。预热后各取 160 刻的服务端耗时中位数。

| 模型 | 空场景 | 10 艘下落 | 单刻增量 |
| --- | ---: | ---: | ---: |
| 现有阻力 | 0.621 ms | 1.335 ms | 0.714 ms |
| 六向投影与压心 | 0.593 ms | 1.451 ms | 0.859 ms |

候选模型复测的空场景和下落场景分别为 1.009 ms 和 1.048 ms，增量为 0.039 ms。表中为首轮对照结果。

无 Sable 的同版本专用服完成启动，同一两级火箭布局可生成静态飞船。`./gradlew build` 通过。

## 原型建议

四项采用 A。六向投影在飞行状态建立时按方块重算。建议把默认方块阻力系数设为 1.0，把压心力矩倍率设为 1，并沿用侧挂分级制作整流罩。正式实现的验收应补测斜入地面接触。

## 复测

用于采集火箭最高点的试验数据包关闭轨道交接，并沿用内置主世界重力和大气参数。

在仓库根目录运行以下命令。

```powershell
.\gradlew.bat -PaeroOcclusion test --tests '*AeroProjectionTest'
```

为 `candidate` 和 `baseline` 分别创建 `runs/aeroOcclusion/<名称>/server/`。把 Sable 2.0.6 放入各自的 `server/mods/` 和 `client/mods/`。本机专用服接受 Minecraft EULA 后，使用以下 `server.properties` 设置。对照组换一个本机端口。

```properties
level-name=world
level-seed=135
level-type=minecraft\:flat
generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:stone","height":124},{"block":"minecraft:dirt","height":1},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains","features":false,"lakes":false}
generate-structures=false
gamemode=spectator
difficulty=peaceful
spawn-protection=0
view-distance=3
simulation-distance=3
online-mode=false
server-ip=127.0.0.1
server-port=25835
```

在服务端的 `defaultconfigs/blockspaceprogram-server.toml` 设定 `[debris]` 下的 `distance = 0`，以便记录残骸离开时的运动。运行候选专用服，等 `Done` 后从另一终端启动客户端。

复测 #144 的地面接触时，在验收服的 `config/sable-server.toml` 中设置 `sub_level_substeps_per_tick = 6`。同一斜入轨迹在每刻 2 个子步的测试中穿过了地面。

```powershell
.\gradlew.bat -PaeroOcclusion -PaeroOcclusionVerify runAeroOcclusionServer
.\gradlew.bat -PaeroOcclusion '-PaeroOcclusionJoin=127.0.0.1:25835' runAeroOcclusionClient
```

对照组使用 `-PaeroOcclusionRun=baseline -PaeroOcclusionBaseline`，客户端连接对照服端口。服务端就绪 80 刻后开始布置、飞行、分级和性能测试。客户端连接可用于观察。结果写入每个服务端的 `logs/latest.log`，以 `AERO` 开头。试验结束后服务端关闭。

`AERO IMPACT` 记录近地速度突变前后的空速，`AERO TOUCH` 确认收到了 Sable 的方块接触回调。`AERO INCOMPLETE contact verification` 表示至少一个返回舱缺少接触证据。 [实现验收 #144](https://github.com/jxt159753/BlockSpaceProgram/issues/144)

无 Sable 复测使用同样的超平坦设置，在 `runs/aeroOcclusion/no-sable/server/mods/` 留空。执行以下命令检查静态飞船。

```powershell
.\gradlew.bat -PaeroOcclusion -PaeroOcclusionRun=no-sable -PaeroOcclusionVerify -PaeroOcclusionNoSable runAeroOcclusionServer
```
