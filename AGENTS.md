# BlockSpaceProgram 项目要求

本文件是所有参与者共同遵守的项目要求，包括 Codex、Claude 和其他编程助手。Claude 另读 `CLAUDE.md` 中的补充说明。

## 项目定位

BlockSpaceProgram 是 [BlockSpaceProgram 整合包](https://github.com/jxt159753/BlockSpaceProgram-Modpack)的自制模组，整合包受《坎巴拉太空计划》启发。工业、物理、任务和地形优先交给整合包里的现有模组，本模组只补齐它们覆盖不到的部分：零件烘焙、飞船装配与飞行、轨道层、再入和天空渲染。 [路线图 #24](https://github.com/jxt159753/BlockSpaceProgram/issues/24)

- 目标环境为 Minecraft 1.21.1、NeoForge 21.1.267、Java 21，构建用 ModDevGradle。
- Sable 2.0.6 是可选依赖，负责近地刚体物理。不装 Sable 时模组仍须正常加载，发射台生成静态飞船。
- 科技树、合同和任务在整合包仓库用 FTB Quests 和 KubeJS 编写。本模组只提供事件和数据接口。
- 做新功能前先确认现有模组能否覆盖。能覆盖的交给整合包，不在本模组里重写。

## 分工

| 角色 | 负责 | 不做 |
| --- | --- | --- |
| Claude | 写 issue 和设计决策，设计架构，维护路线图和本文件，按需审查 PR | 默认不写 `src/` 下的实现代码 |
| Codex | 按 issue 编写代码、测试、原型和玩家文档，提交 PR | 不自行决定 issue 未覆盖的架构问题 |
| 维护者 | 拍板设计决策，合并 PR | |

一般流程：issue → `codex/issue-<编号>-<英文短名>` 分支 → PR → 维护者合并。带 `设计决策` 标签的 issue 先由 Codex 做原型验证，维护者拍板后把结论写进 issue 的「决定」一节。

## 架构约束

### 包结构与依赖方向

| 包 | 内容 |
| --- | --- |
| `part` | 零件数据、烘焙台、连接接口 |
| `collision` | 零件碰撞体与碰撞格 |
| `assembly` | 装配台、世界内组装、蓝图、飞船实体、飞行、着陆、分级、推进计算 |
| `orbit` | 天体、开普勒传播、轨道存档、机动节点、飞船与轨道层的交接 |
| `sky` | 天空数据同步 |
| `client` | 界面、渲染、按键、客户端状态 |

包之间只允许依赖表中位置更靠前的包。所有包都可以引用主类 `BlockSpaceProgram` 中的注册对象。

- 轨道层执行机动时需要读取飞船的推进数据，因此 `orbit` 位于 `assembly` 之后。
- 客户端代码只放在 `client` 包。其他包中只有注册网络包的 `*Networking` 类可以引用 `client`，引用限于客户端处理入口。
- 轨道力学、Δv 和投影这类纯计算不引用 Minecraft 类型，以便在 `src/test` 中直接写单元测试。

### 与整合包的边界

- 本仓库的开发和验收不依赖整合包。只装本模组就能运行，Sable 和 Create 按需加装。验收写在这样的环境里，不写整合包实例。
- issue 的依赖只能从整合包指向本仓库。整合包 issue 可以依赖本仓库的 issue，本仓库的 issue 不依赖整合包 issue。整合包需要新接口时，在本仓库另开 issue。
- 本模组代码引用的内容由本模组自带，例如卫星维度 `blockspaceprogram:moon`、天体参数和零件属性。整合包用同 ID 的数据包或配置覆盖默认值，例如替换卫星地形（jxt159753/BlockSpaceProgram-Modpack#2）。
- 在整合包实例上的复测写在整合包仓库的 issue 里。

### 可选依赖

- Sable 的类只在专门的类中引用，目前是 `SableShipFlight`、`SableCollisionCellBlock`、`ShipLandingGear`、`ShipCollisionBody` 和 `SableCollisionDebugRenderer`。其他代码先用 `ModList.get().isLoaded("sable")` 判断，再调用这些类。
- 其他模组的联动放在 `compat/<模组 ID>` 包中，入口同样用 `isLoaded` 守住，不拆成单独的 JAR。已规划的有 CC:Tweaked（#42、#44）和 Iris（#51、#52）。
- 新增依赖（包括 `compileOnly`）属于架构决策，须先在 issue 中定下。引入其他模组的代码前须确认其许可证与 MIT 兼容。

### 注册、附属与存档兼容

- 本体内容在 `BlockSpaceProgram.java` 中集中注册。
- 将来可能拆成附属的功能从一开始就使用独立的包和自己的注册类，本体代码不引用它们。目前只有服务器网页轨道预览（#43）。
- 生涯模式的内容写在整合包仓库。本模组只在烘焙台、装配台和发射台中提供可配置的上限检查（#21）。
- 本体通过自定义 NeoForge 事件对外提供接口，例如入轨、着陆、分级分离和 SOI 变更（#20）。KubeJS 联动和附属都基于这一套事件。
- 注册 ID 和存档字段确定后不改名。数据组件、`SavedData` 和网络包的格式变化须能读取旧数据，新字段要有默认值。

### 数据驱动

- 玩家或整合包作者可能要调整的数值放在数据包或配置文件里，不写死在代码中。
- 零件属性用数据映射 `data_maps/block/part_properties.json`，天体参数放在 `data/<命名空间>/ship_flight/<维度路径>.json`，修改后可用 `/reload` 生效。
- 服务器管理员调整的参数放在 `blockspaceprogram-server.toml`（`ModConfigSpec`）。

### 多语言

- 玩家可见的文字使用语言键，同时写入 `lang/en_us.json` 和 `lang/zh_cn.json`。`en_us` 是缺键时的回退语言，必须完整。
- 语言键沿用现有格式 `<功能>.blockspaceprogram.<名称>`，例如 `assembly.blockspaceprogram.save`、`flight.blockspaceprogram.status`。注册对象的键由注册 ID 决定，例如 `block.blockspaceprogram.launch_pad`。
- 服务端发给玩家的文字用 `Component.translatable` 构造，原样发给客户端，由每个客户端按自己的语言显示。包括聊天消息、动作栏、命令反馈，以及物品和实体的自定义名称。
- 不在服务端用 `getString()` 或 `Language.getInstance()` 把要给玩家看的文字解析成字符串。专用服只加载 `en_us`，其他玩家会看到英文。单人游戏里解析出的是房主的语言，所以这类错误在单人游戏里看不出来。
- 数据包中玩家可见的字段用文本组件（`ComponentSerialization.CODEC`），不用纯字符串。本模组自带的数据写成 `{"translate": "<键>", "fallback": "<英文>"}`，键写进语言文件。整合包作者也可以直接写字符串。
- 将来可能拆成附属的功能（目前是 #43），注册对象以外的语言键使用独立的功能前缀，不与本体共用，例如 `web_preview.blockspaceprogram.*`。拆分时把这些键整段移到附属的语言文件，键名不变。

## 开发规范

### 分支与提交

- 从最新的 `main` 拉出 `codex/issue-<编号>-<英文短名>` 分支，一个 issue 对应一个分支和一个 PR。
- 提交信息和 PR 标题用中文，以动词开头概括改动，例如「实现飞船分级分离与残骸管理」。
- 不提交 `runs/`、`build/` 和 `.claude/settings.local.json`。

### 实现范围

- 只做 issue 中列出的任务，不顺手重构无关代码。
- issue 没写清的小细节，选最简单的方案，并在 PR 的「改动」中写明。
- 遇到以下架构问题时，先在 issue 中留言，确认后再实现：新增包或依赖，改动存档或网络格式，违反上面的依赖方向，以及会影响其他 issue 的接口。

### 代码

- 代码、注释和配置项说明用英文。
- 玩家可见的文字按上文「多语言」一节处理。

### 验证

- `./gradlew build` 必须通过（Windows 用 `.\gradlew.bat build`）。CI 在每次推送时运行同样的构建。
- 纯逻辑写 JUnit 4 测试，放在 `src/test` 下对应的包中。
- 游戏内行为在专用服上实测，并写明版本：Minecraft 1.21.1、NeoForge 21.1.267、Sable 2.0.6，涉及 Create 机构时加上 Create 6.0.10。
- 涉及 Sable 的功能，还要在不装 Sable 的专用服上确认模组能加载，功能按设计退化。
- 涉及存档的改动，须完整重启服务器后再核对一遍。
- 客户端画面如果只通过了编译、没有进游戏看过，在 PR 中注明「待复测」。

### PR 与 issue 更新

PR 正文使用以下结构：

```markdown
Closes #<编号>

## 改动

- 玩家可见的行为和主要实现方式

## 验收

- [x] 逐条照抄 issue 的验收项，写明实测结果和数值
- [ ] 未完成或未实测的项保持未勾选，并说明原因

## 验证

- 实测环境、场景和构建结果
```

- 提交 PR 前，在 issue 中勾选已完成的任务和验收项，并追加「验收记录」一节，写明实测环境、结果和 PR 链接。
- 不要自己合并 PR，由维护者合并。

### 原型

- 设计决策所需的原型放在 `experiments/<名称>/` 中，包含 `README.md` 和一个 Gradle 脚本，在 `build.gradle` 中用 Gradle 属性开关，参考现有的 `shipRigidBody`。
- 原型的 README 写明实测环境、各验证项的观测结果和复测步骤。

### 文档

- 玩家可见的行为变化须同步更新 `docs/` 下对应的文档。新增的文档要在 `README.md` 中加上链接。

## 写作风格

issue、PR 和文档遵守以下风格。

- 用中文书写。代码标识符、文件路径、命令和 JSON 键用反引号。
- 用短句，一句只说一件事，不写空话和宣传语。
- 写具体的数值、版本和环境，例如「下降速度约 0.83 m/s」「Sable 2.0.6 专用服」。
- 推断和实测分开写，未验证的内容标明「尚未验证」。
- 链接放在段末，多个链接用「 · 」分隔。引用整合包仓库的 issue 写全称，例如 `jxt159753/BlockSpaceProgram-Modpack#3`。
- 引用第三方源码时，链接到固定的提交，不链接分支。
