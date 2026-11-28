# RCS 推力分配原型

原型在 Sable 轨道局部物理区比较 A 投影法和 B 带界最小二乘法。Gradle 收到 `-PrcsAllocation` 时，将 `integration.patch` 应用到构建目录里的源码副本。两组使用同一飞船布局、挂点和飞控。 [设计决策 #138](https://github.com/jxt159753/BlockSpaceProgram/issues/138)

## 原型规则

- 喷口开度范围为 0–1。B 将力误差和力矩误差带入同一目标函数。力矩误差按 0.5 m 的力臂折算，再加入一个与燃料流量成正比的小惩罚项。反作用轮在额定扭矩内补足缺口，纯转动由轮单独完成时耗油为零。
- B 从投影结果开始迭代，每次最多 128 步。A 按平移力和剩余姿态力矩的方向分别投影，反作用轮只响应原姿态指令。每艘船每服务器刻分配一次开度，Sable 的物理子步应用该刻的开度。
- 每刻按当前质心计算喷口力臂。燃料从喷口所在的分离段扣除。配置耗电的喷口同时使用全船电量。RCS 燃料、电量和作用时间与主发动机经过同一次推进剂结算。
- 试验飞船有 12 个额定 350 N、比冲 220 s 的化学喷口。方块、零件模板、装配台读数和可重绑飞行键属于正式实现的验收。

## 实测

基线 `73526b8`。本机使用 Intel Core Ultra 9 275HX、Java 21、Minecraft 1.21.1、NeoForge 21.1.267 和 Sable 2.0.6，专用服和客户端均安装 CC:Tweaked 1.120.2。

### JUnit 算例

6 项 JUnit 4 算例通过。

- 对称布置的 8 个喷口产生约 700 N 的平移力，A 和 B 的合力矩均为 0。
- 质心上方 1 m 处只有一个 1,000 N 喷口时，反作用轮让 B 在约 1,000 N 平移力下保持零合力矩。移除轮后，A 仍输出 1,000 N 和 −1,000 N·m，B 输出约 200 N 和 −200 N·m。
- 共面排布做 −1,000 N·m 的纯转动时，B 的平移力在 0.25 N 算例容差内，A 附带约 111.11 N 的平移力。共线布置收到不可达的平移和转动指令时，两者开度均为 0。
- 推力饱和时，测试中的单轴指令保持方向。两轴上限分别为 800 N 和 300 N 时，两种原型对 `(2000, 2000)` N 指令都输出 `(800, 300)` N，偏离 24.44°。保持同向的最大合力对应 `(300, 300)` N。正式实现采用可达时保方向的规则。
- B 在反作用轮可以单独完成转动时耗油为 0。

### 分配耗时

每种排布用变化的力指令预热 4,000 次，再采样 1,600 次。下表的耗时只包含开度和轮扭矩的分配。

| 喷口数 | A 中位数 | A P95 | B 中位数 | B P95 |
| ---: | ---: | ---: | ---: | ---: |
| 8 | 1.1 µs | 1.5 µs | 20.0 µs | 36.6 µs |
| 16 | 2.0 µs | 6.6 µs | 37.5 µs | 72.2 µs |
| 32 | 1.8 µs | 3.3 µs | 75.2 µs | 118.8 µs |
| 64 | 4.7 µs | 20.3 µs | 146.3 µs | 208.5 µs |

服务端保持 1 名客户端在线。空场景和 10 艘有动力飞船的场景各预热 60 刻并采样 160 刻。每艘船使用 64 个喷口执行 B 平移。

| 场景 | 每刻中位数 | 每刻 P95 |
| --- | ---: | ---: |
| 空场景整刻 | 1.97 ms | 5.50 ms |
| 10 艘船开启 RCS 的整刻 | 6.06 ms | 17.58 ms |
| 10 艘船的分配耗时之和 | 1.41 ms | 1.98 ms |

### 轨道局部物理区对接

两艘船的端口起始间距为 5 m。追踪船使用同一个速度控制器和姿态保持模式。用时取服务器刻，耗油取捕获前后的两船燃料总量差。姿态偏差每服务器刻采样一次。

| 方法 | 对接用时 | 耗油 | 最大姿态偏差 |
| --- | ---: | ---: | ---: |
| A 投影 | 13.60 s | 0.4897 kg | 0.720° |
| B 有界求解 | 13.65 s | 0.4959 kg | 0.000° |

两组均触发普通对接捕获，飞船合为一个四节点设计。B 的用时和耗油接近 A，姿态偏差降至本次采样的记录精度内。

### 质心变化

同一布局由满油 650 kg 降至 1% 油量后，初始质心 Y 从 0.4545 m 移到 0.8492 m。两组都在手动姿态模式下令 B 沿 +X 平移 10 s。

| 初始油量 | +X 位移 | 结束角速度 | 最大角速度 |
| ---: | ---: | ---: | ---: |
| 650 kg | 5.555 m | 1.62 × 10⁻⁶ rad/s | 1.62 × 10⁻⁶ rad/s |
| 6.5 kg | 7.852 m | 4.86 × 10⁻⁶ rad/s | 4.86 × 10⁻⁶ rad/s |

### CC:Tweaked

实际电脑运行 `cc-startup.lua`，通过飞行终端开启 RCS 并设置 `setTranslation(0.35, 0, 0)`。遥测返回 `rcs=true` 和 `translation=(0.35, 0, 0)`。飞船沿 +X 移动 0.1359 m，采样速度为 0.1726 m/s，耗油 0.1760 kg。脚本也验证了越界输入会被拒绝，关闭开关后遥测变为关闭且平移输入归零。

### 按键

客户端的原版、Sable 与模组默认键位表中，上下左右、PageUp、PageDown 和 O 均无重复项。G 已绑定世界内装配的连接模式切换，原版也用 F3+G 显示区块边界。

无 Sable 的 Minecraft 1.21.1、NeoForge 21.1.267 专用服完成启动并生成静态飞船。常规 `gradlew.bat build` 通过。

## 决定

维护者选定 B/A/A/A/A。使用有界求解、单向喷口、按分离段供油、六个独立平移键和可配置的反作用轮。RCS 开关默认用 O。斜向推力不足且方向可达时，沿指令方向降低推力。

## 复测

在仓库根目录运行构建与 JUnit 算例。

```powershell
.\gradlew.bat build
.\gradlew.bat -PrcsAllocation test --tests '*RcsAllocationTest'
```

为 `runs/rcsAllocation/trial/server/` 和 `runs/rcsAllocation/trial/client/` 准备 `mods/`。两端都安装 Sable 2.0.6 和 CC:Tweaked 1.120.2。测试服接受 Minecraft EULA 后，在 `server.properties` 里使用以下设置。

```properties
level-type=minecraft\:flat
level-seed=138
generate-structures=false
gamemode=spectator
difficulty=peaceful
online-mode=false
server-ip=127.0.0.1
server-port=25838
spawn-protection=0
view-distance=4
simulation-distance=3
```

启动专用服并等待日志出现 `Done`。另开终端连接客户端。新建客户端测试目录可以先在 `options.txt` 写入 `onboardAccessibility:false`，让客户端自动连接。

```powershell
.\gradlew.bat -PrcsAllocation -PrcsAllocationVerify runRcsAllocationServer
.\gradlew.bat -PrcsAllocation '-PrcsAllocationJoin=127.0.0.1:25838' runRcsAllocationClient
```

服务端按顺序运行 A、B 对接，满油和近空油平移，CC 脚本和每刻耗时采样。服务端记录以 `RCS` 开头的结果，客户端记录 `RCS KEY`。结果分别位于各自测试目录的 `logs/latest.log`。试验结束后专用服自动关闭。

无 Sable 验证使用独立的 `runs/rcsAllocation/no-sable/server/`。沿用上述配置并换成本机端口 25839，保持 `mods/` 为空。

```powershell
.\gradlew.bat -PrcsAllocation -PrcsAllocationRun=no-sable -PrcsAllocationVerify runRcsAllocationServer
```
