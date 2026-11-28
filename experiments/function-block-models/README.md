# 功能方块模型原型

同一个试验喷管分别使用原版 JSON 和 NeoForge OBJ。两个方块共用贴图、朝向和零件属性。模型由 7 个长方体组成，喷口沿 `facing` 伸出 0.5 格。只有安装座贴着方块边界的那一面参与邻面剔除。 [Issue #113](https://github.com/jxt159753/BlockSpaceProgram/issues/113)

## 决定

- 本模组自带功能方块。正式方块默认使用 Blockbench 导出的原版 JSON。复杂曲面可用 NeoForge OBJ，使用前对照三档缩放、邻面剔除、Iris 和零件网格顶点数。
- 正式方块的模型留在已占用的格子内。半格外伸在三档缩放下均与紧贴的零件相交。
- 正式模型的作者源文件放仓库根目录的 `models/`，原型使用自己的 `models/`。运行时资源放在各自的资源目录。首批模型和部件划分由 #118 决定。 [Issue #118](https://github.com/jxt159753/BlockSpaceProgram/issues/118)

## 实测

Windows 11、Java 21.0.11、GeForce RTX 5070 Laptop GPU（621.01 驱动）。客户端连接 Minecraft 1.21.1、NeoForge 21.1.267 专用服。飞行端加装 Sable 2.0.6。Iris 客户端使用 Iris 1.8.14-beta.1、Sodium 0.8.13 和 Complementary Reimagined r5.5.1。

- 两种喷管都能通过烘焙台制成发动机零件。三档在背包、装配台和 Sable 飞船上均显示。四分之一档仍可凭铜色喷口辨认，背包图标只能看到粗轮廓。 [零件物品](evidence/inventory.png) · [装配台预览](evidence/assembly.jpg) · [飞行](evidence/flight.jpg)
- Iris 光影实际启用。两版装配预览和飞行模型均无缺面、错误贴图或反向光照。原版和 Iris 的网格计数相同，两个模型在六个轴向的顶点法线分布也相同。 [原始计数](evidence/measurements.txt)
- 平滑石头贴住安装座时，剔除该安装座面。外伸部分保持完整。平滑石头侧贴时，两个喷管均保留全部 36 个四边形。六个朝向都保留朝向相反一侧的安装座剔除面。 [相邻方块](evidence/culling.jpg)
- 装配台允许同档玻璃贴住喷管底面。两个零件的占用盒刚好相切。模型伸入玻璃的深度依次为 0.5、0.25、0.125 格。装配判定容许两个零件相切，因此任何外伸都可能在这种装法下穿插。 [三档装配穿插](evidence/placement.jpg)

每张装配台和飞行图左侧是 JSON，右侧是 OBJ。邻面图从左到右是独立 JSON、独立 OBJ、上方贴石的 JSON、上方贴石的 OBJ。飞行测试用夹具生成蓝图和相同的飞船布局，以 45% 油门爬升。三档质心都上升约 1.17 格，燃料从 200 kg 降至约 197.82 kg。

| 从客户端零件网格读取 | 原版 JSON | NeoForge OBJ |
| --- | ---: | ---: |
| 单个喷管四边形 | 36 | 36 |
| 单个喷管顶点 | 144 | 144 |
| 紧贴上方实心块的喷管顶点 | 140 | 140 |
| 紧贴侧方实心块的喷管顶点 | 144 | 144 |
| 飞船上的八个独立喷管顶点 | 1,152 | 1,152 |
| 整船顶点，含 136 个根零件顶点 | 1,288 | 1,288 |

整船计数按各零件实例求和。同种喷管的八个零件共用一个 144 顶点的缓存网格。数据适用于这款同形喷管。采用更复杂的曲面时，另外测量实际面数。

## 模型文件

- `models/nozzle.bbmodel` 是 Blockbench 5.2.2 工程，内嵌试验贴图。在 Java 方块项目里导出 `json_nozzle.json`。
- `models/nozzle.obj` 保留四边形和法线，导出时把模型比例设为 16。Blockbench 以 X、Z 的中心为原点，导出后把每个顶点的 X、Z 分别加 0.5 格。模型的 Y 坐标保持不变。把材质名改为 `nozzle`，用资源目录中的 `nozzle.mtl` 绑定同一张贴图。
- `resources/assets/bsp_function_block_models/` 放运行时资源。实验的资源任务会复制 `models/nozzle.obj` 供 NeoForge 读取。发布 JAR 只打包主源集。

## 复测步骤

1. 用 `-PfunctionBlockModels` 打开原型。先启动专用服，再启动客户端。创建本机测试服时，在 `runs/functionBlockModels/plain/server/server.properties` 设置 `server-ip=127.0.0.1` 和 `online-mode=false`。用 `op NozzleTest` 授予测试客户端命令权限。

   ```powershell
   .\gradlew.bat -PfunctionBlockModels runFunctionBlockModelsServer
   .\gradlew.bat -PfunctionBlockModels -PfunctionBlockModelsJoin=127.0.0.1:25565 runFunctionBlockModelsClient
   ```

2. 在两座烘焙台正上方分别放置 `bsp_function_block_models:json_nozzle[facing=down]` 和 `bsp_function_block_models:obj_nozzle[facing=down]`。手持铜锭右击烘焙台。每种喷管各做三份，手持零件潜行右击空气调整为 `1.0`、`0.5` 和 `0.25`。从左到右摆 JSON 三件，再摆 OBJ 三件，查看背包图标。
3. 从左到右摆一口炼药锅、两块橡木木板和一口炼药锅，再烘焙成指令舱。原型将橡木木板加入 `command_seats`。打开装配台，把两种喷管用表面模式接到指令舱底面，选择喷管的上表面作为连接面。每档换一次成对喷管，用右键拖到能看到喷口的角度。用同档玻璃零件贴到两种喷管底面，查看玻璃与喷口相交的部分。
4. 另做一组测试件，分别烘焙独立喷管、上方紧贴平滑石头的喷管、侧面紧贴平滑石头的喷管。原尺寸放在世界中或装入飞船，对比喷口和安装座。计数时可在 `PartMeshRenderer.bake` 检查不透明网格的顶点数。四个顶点组成一个四边形。为测八喷管飞船，烘焙一排八块橡木木板作根零件，底面装八个同格式的独立喷管，然后对另一种格式重复。
5. 给客户端和专用服都装上 Sable 2.0.6，再用 `-PfunctionBlockModelsRun=sable` 启动独立测试目录。保存装有成对喷管的蓝图。发射后坐入指令位，增加油门，观察另一客户端看到的飞船。用同样方法测三档。Iris 对照使用带 Iris 和 Sodium 的客户端，打开光影包后重复背包、装配台和飞行截图。

发布构建可用 `jar tf build/libs/blockspaceprogram-0.1.0.jar` 检查。主 JAR 中应无 `bsp_function_block_models`、`.bbmodel` 和 `.obj` 条目。
