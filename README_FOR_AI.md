# README for AI

> 面向 AI 助手（及开发者）的 MAS 使用与改造指南。重点是：**脚本工坊（可视化流程）怎么用**、**如何把任意脚本/工具转换成工坊流程**。

## 1. 这是什么

**MAS（Mobile Automation System）**：在 Android 设备上本地运行的通用自动化工作流平台。

- 可视化流程编辑（脚本工坊）→ 后台虚拟屏执行 → 日志/排查
- 不依赖 PC；核心执行引擎复用 MaaCore（AGPL-3.0，识别/截图/触摸）
- **灵魂是自研的 FlowEngine（流程执行器）**：节点图 + 逻辑连线 + 突发打断

## 2. 关键路径

```
工程：/sdcard/Download/MA/MASS
工坊代码：app/src/main/java/com/yuanqian/autofarm/presentation/view/workshop/
   ├─ FlowModels.kt          # 数据模型（节点/连线/突发/项目）
   ├─ FlowEngine.kt          # 流程执行器（读 project.json 跑流程）
   ├─ FlowEditorScreen.kt    # 可视化编辑器
   ├─ FlowExportImport.kt    # 流程导入/导出（含自定义节点/模板图片）
   ├─ CustomNodeStore.kt     # 自定义节点存储（全局）
   └─ TemplateStore.kt       # 识别模板（图片）存储
运行入口：后台任务页「引用流程」或「配置绑定流程」→ 开始任务
流程文件：filesDir/workshop/<流程名>/project.json
自定义节点：filesDir/custom_nodes/*.json（全局，所有流程可用）
```

## 3. 工坊数据模型（project.json 结构）

```json
{
  "name": "流程名",
  "nodes": [ { "id": "...", "name": "节点名", "kind": "TIME", "x": 40.0, "y": 220.0, ... } ],
  "links": [ { "id": "...", "type": "YES", "fromIds": ["节点A"], "toId": "节点B" } ],
  "bursts": [ { "id": "...", "name": "突发1", "judgeNodeId": "...", "nodeIds": ["..."], "hitContinueId": "...", "missContinueId": null, "alwaysOn": true } ],
  "templates": [ { "id": "...", "name": "...", "file": "...", "width": 0, "height": 0 } ],
  "createdAt": 0
}
```

### 节点（FlowNode）字段

| 字段 | 说明 |
|---|---|
| `id` | 唯一 ID（UUID） |
| `name` | 节点名（日志/显示用，排查关键） |
| `kind` | 节点类型（见下） |
| `x`/`y` | 画布坐标 |

### 节点类型（kind）

| kind | 分类 | 用途 | 关键字段 |
|---|---|---|---|
| `INFO` | 执行 | 流程信息（首个节点，启动应用） | `appPackage`、`launchApp` |
| `TIME` | 执行 | 等待/延时 | `durationMs`（毫秒）；`untilTemplateId`+`untilTimeoutMs`=直到识别到某模板才继续 |
| `ACTION` | 执行 | 动作 | `action`（见 FlowAction） |
| `IMAGE` | 判定 | 图像识别 | `templateId`、`threshold`(0.8)、`roi`、`retryIntervalMs`、`maxRetries` |
| `LOOP` | 控制 | 循环 | `loopMode`：`times`（`durationMs`=次数）/ `until`（直到识别 `untilTemplateId`） |
| `APP_STATE` | 判定 | 应用状态 | `appStatePkg`、`appStateMode`（foreground/alive） |
| 自定义 | 按模板分类 | 跑 Shell 命令 | `customNodeId`、`customParams` |

### FlowAction（ACTION 节点的动作）

| 动作 | 字段 |
|---|---|
| `Tap` | x, y, durationMs（>0=长按） |
| `TapTemplate` | （点击模板命中位置中心） |
| `Wait` | ms |
| `Back` | times |
| `Input` | text |
| `Swipe` | fromX, fromY, toX, toY, durationMs |

### 连线（FlowLink）

| type | 语义 |
|---|---|
| `SEQUENCE` | 顺序链：无条件流转，执行完就走 |
| `YES` | 是链：判定命中/成功 → 走此线 |
| `NO` | 非链：判定未命中/失败 → 走此线 |
| `AND` / `OR` | 多开端汇聚（全部/任一），开端≥2个 |

### 连线规则（信号模型：线=带过滤的信号通道）

**线本质**：顺序/是/非都是"带二极管的顺序线"——是线只放行「真」信号，非线只放行「假」信号，顺序线放行「完成」信号。信号不匹配=信号断（下游不执行）。

| 起点节点 | 允许连的线 | 说明 |
|---|---|---|
| 执行节点（流程信息/时间/点击/滑动/返回/输入） | 只能 `SEQUENCE` | 不产生判定信号；**不能连合取/析取/循环终点**（不传信号） |
| 判定节点（图像识别/应用状态/自定义-判定、控制） | `YES`/`NO` | 命中走「是」，未命中走「非」；出线目标为汇聚/循环终点=**信号输入**（只广播不跳转） |
| 合取/析取/循环终点（控制） | `YES`/`NO`/`SEQUENCE` | 连顺序线=二极管纯汇聚；连是/非=输出判定信号 |
| 任意开端（≥2个） | 只能 `AND`/`OR` | 多开端汇聚（旧模型，建议用合取/析取节点替代） |

**终点限制**：
- 执行节点只能**一条进线**（多路汇聚必须经合取/析取衔接）
- 循环终点**最多一条进线**（多条件经合取/析取并联合并成一条）

### 合取 / 析取（信号汇聚，替代旧与/或线）

- **合取（全部满足）**：所有输入线都收到信号 → 输出「是」；任一缺失 → 输出「非」
- **析取（任一满足）**：任一输入线收到信号 → 输出「是」；全部无信号 → 输出「非」
- 非线也可作为输入条件：判定连「非线」到合取 = "该判定为假"这个条件成立（例如：A是 且 B非）

### 循环（循环起点 + 循环终点）

- **循环起点**：循环体入口标记
- **循环终点**：面板绑定循环起点（未绑定=🚫）+ 可选超时保护（超时=强制成功放行，防死循环）
- **循环判定**：终点之前、最后一个执行节点之后的判定自动成为循环判定（黄色四角标识）——判定结果通过是/非线连入循环终点（或经合取/析取合并）
- **信号方向**：判定连「是线」→终点 = 判定为真→继续循环；连「非线」→终点 = 判定为假→继续循环（线型决定"哪个结果触发继续"）
- 次数循环（旧模型）仍兼容：loopMode=times
- **循环判定不能作为突发判定**（角色互斥）

### 等待直到

TIME 节点支持「直到判定」（untilJudgeId）：等待期间反复执行指定判定，为真提前继续，超时继续。

**顺序链 ≠ 是链+非链**：顺序=无条件下一步；是/非=条件分支（只有判定/循环产生信号）。

节点执行完后：**有对应连线就走连线；没有连线就按 nodes 列表顺序取下一个**（顺序语义），最后一个结束。

### 突发（FlowBurst）

- `judgeNodeId`：突发判定节点（IMAGE，始终监听）
- `nodeIds`：命中后执行的应急区间（有序）
- `hitContinueId`：区间执行完跳转的继续节点
- `missContinueId`：未命中时跳转（null=不改变主流程）
- `alwaysOn`：true=全程监听

## 4. 自定义节点（重要）

自定义节点 = **Shell 命令模板**，全局通用（一个流程创建，所有流程可用）。

### JSON 模板格式

```json
{
  "id": "custom_123",
  "name": "自定义等待",
  "category": "EXECUTE",          // EXECUTE / JUDGE / CONTROL
  "description": "描述",
  "params": [ { "key": "ms", "label": "毫秒", "type": "number", "default": "1000" } ],
  "command": "sleep ${ms}"         // {key} 会被参数替换
}
```

- **EXECUTE**：跑命令，沿顺序线走
- **JUDGE**：命令退出码 0=成功→「是」线，非0→「非」线
- **CONTROL**：同判定，用于控制流转

### 本地新建（工坊 UI）

工具栏「🧩自定义」→ 本地新建：填名称/类型/节点代码（Shell 命令）。
参考：时间节点=`sleep 1000`；点击=`input tap 500 800`。

### 导入/导出

- 导入：🧩面板「导入文件」选 .json；或导入别人导出的流程文件夹（自动装模板）
- 导出：长按自定义节点→导出文件；**导出流程时自动附带用到的自定义节点**到 `custom_nodes/`
- 导入流程时若用到本地没有的自定义节点 → 弹窗提示"未装配"，导入对应模板后自动生效

## 5. 如何把一个脚本/工具转换成流程（方法论）

任意脚本（Python/Shell/点击器/录制的步骤）都可以转成工坊流程。步骤：

### 第 1 步：拆解为原子步骤

通读脚本，把每一步拆成原子操作：

| 脚本里的操作 | 转成节点 |
|---|---|
| 等待/延时/轮询间隔 | TIME 节点（durationMs） |
| 点击固定坐标 / 长按 | ACTION → Tap（durationMs>0 长按） |
| 滑动/拖拽 | ACTION → Swipe |
| 返回键 | ACTION → Back |
| 文本输入 | ACTION → Input |
| 识别画面/找图（模板匹配） | IMAGE 节点（选模板+阈值） |
| 判断应用前后台/存活 | APP_STATE 节点 |
| 循环 N 次 / 直到某画面出现 | LOOP 节点（times/until） |
| 启动/唤醒应用 | INFO 节点（首个，appPackage+launchApp） |
| 自定义命令（截图、OCR、特殊 shell） | 自定义节点（EXECUTE/JUDGE） |

### 第 2 步：画执行顺序（连线）

- 线性顺序 → SEQUENCE 连线（或直接靠节点列表顺序，无需连线）
- 条件分支：IMAGE 判定 → 命中连「YES」线，未命中连「NO」线
- 循环：LOOP 节点 → 循环体接「YES」/「是」线，退出接「NO」/「非」线
- 多条件汇聚 → AND / OR

### 第 3 步：识别"突发"（异常画面处理）

脚本里的"如果出现弹窗/警告/异常画面就处理"逻辑 → 转成**突发**：

- 判定节点 = 该异常画面的 IMAGE 识别
- 应急区间 = 处理动作序列
- 命中后继续 = 处理完回到的位置

### 第 4 步：识别模板图片

IMAGE 节点需要**模板图**（当前画面里的特征区域截图）：

- 工坊图像节点 →「✂抠图」导入图片 → 裁剪/像素精修 → 保存为模板
- 模板按文件夹管理（可建目录、重命名、移动）
- 注意分辨率：识别分辨率影响模板匹配（720p/1600x720/1080p）

### 第 5 步：运行验证

1. 保存流程（自动存 project.json）
2. 工坊编辑器点「▶运行」直接试跑（需服务已连接）
3. 或：后台任务页 → 引用流程（新建配置）→ 开始任务
4. 看日志排查：运行时日志 / 历史日志（会话文件）/ 错误日志
5. 连线跳转日志带节点名（`[是] 识别1 → 点击2`），突发命中会记录 `🚨突发「名称」判定命中`

### 示例：把一段点击脚本转流程

原脚本：
```
sleep 3
input tap 500 800
sleep 1
input tap 900 500
如果画面出现"结算"按钮就点它，否则重试2次
```

转成流程：
```
INFO(启动应用) → TIME(等3s) → ACTION Tap(500,800) → TIME(等1s) → ACTION Tap(900,500)
→ LOOP(次数2, until=识别"结算按钮") 
    ├─ 是线(识别到) → ACTION TapTemplate(点击结算) → 结束
    └─ 非线(未识别) → TIME(等2s重试) → 回 LOOP
突发：IMAGE(识别"网络异常弹窗") → 区间[ACTION Tap(关闭按钮)] → 命中后→继续节点
```

## 6. 运行与日志

- **运行时日志**：后台任务页日志（内存，实时）
- **历史日志**：设置 → 日志 → 历史日志（会话文件 `meow_log_flow_*.log`，工坊流程也会写会话）
- **错误日志**：设置 → 日志 → 错误日志（WARN/ERROR 级）
- 任务详情页显示该流程的**连线明细/突发明细**，并同步写入会话日志（排查用）
- 日志分类：历史日志=会话（任务/流程运行），错误日志=系统级错误

## 7. 给 AI 的开发提示

- 修改工坊逻辑：`FlowEngine.kt`（执行）、`FlowEditorScreen.kt`（编辑 UI）、`FlowModels.kt`（模型，注意 @Serializable 兼容，新增字段要有默认值）
- 新增节点类型：FlowModels 加枚举 → FlowEditorScreen 工具栏/编辑面板 → FlowEngine executeNode 加分支 → categoryLabel 分类
- 自定义节点能力：CustomNodeTemplate（JSON）→ CustomNodeStore（存储）→ FlowEngine.executeCustom
- 主题：设置 → 外观（跟随系统/白色/深色/纯深色）；页面不要硬编码深色，用 MaterialTheme 颜色
- 图标：res/mipmap-*/ic_launcher*.webp（含 monochrome 主题图标 anydpi-v33）
- 构建：`sh build_run.sh`（输出 MAS_<versionName>.apk，versionName 在 app/build.gradle.kts，versionCode 在 build_run.sh）
- 安全点：磁盘改动后及时更新 `/sdcard/Download/MAS源码快照_*.tar.gz`（此环境 git 不可靠）
