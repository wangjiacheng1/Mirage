# 17 · 项目结构与 Git 规范

> 本文是**唯一**的目录结构权威。新增文件前先看这里该放哪。
> 最后更新：2026-10-19（M1 启动）

---

## 1. 目录结构

```
/Users/wangjiacheng48/LLM-project/Mirage/
│
├── README.md                  项目简介（对外第一印象）
├── AGENTS.md                  🔴 给 AI 协作者看的项目铁律，每轮对话必读
├── .gitignore
│
├── docs/                      ✅ 入库 —— 所有设计文档
│   ├── 00-当前状态.md          🔴 进度看板，每轮更新
│   ├── 01 ~ 17-*.md           设计文档正文
│   └── adr/                   架构决策记录（ADR-001 ~ 012）
│
├── app/                       ✅ 入库 —— KMP 工程根（Android Studio 从这里打开）
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle.properties
│   ├── gradlew / gradlew.bat          🔴 必须入库
│   ├── gradle/
│   │   ├── libs.versions.toml         依赖版本集中管理
│   │   └── wrapper/                   🔴 必须入库（含 gradle-wrapper.jar 二进制）
│   │       ├── gradle-wrapper.jar         Gradle 启动器
│   │       └── gradle-wrapper.properties  钉死 Gradle 8.9，含事故记录
│   │
│   ├── shared/                🔴 引擎（护城河都在这）
│   │   └── src/
│   │       ├── commonMain/kotlin/com/mirage/engine/
│   │       │   ├── state/     G1~G8 守卫、结算回执     ← M1 当前
│   │       │   ├── card/      ST 角色卡解析 / 导出     ← M1 下一块
│   │       │   ├── prompt/    上下文组装、分卷压缩
│   │       │   └── llm/       多厂商 API 适配、SSE
│   │       ├── commonTest/    引擎单测（不需要模拟器）
│   │       ├── androidMain/   Android 平台实现
│   │       └── jvmMain/       JVM 平台实现（跑单测用）
│   │
│   └── androidApp/            只有 UI 和平台壳，不许写业务规则
│       └── src/main/kotlin/com/mirage/android/
│
├── tools/                     ✅ 入库 —— Python 探针脚本（M0 协议验证）
│   ├── m0_probe.py ~ m0_probe3.py
│   ├── m0_dualchannel.py
│   └── m0_fixture.py
│
├── refs/                      ❌ 不入库 —— 参考资料
│   ├── dsh-tavern/            第三方开源项目（171M，自带 .git）
│   ├── cards/                 🆕 优秀 ST 角色卡参考库（见 §3）
│   └── screenshots/           竞品截图
│
└── out/ out2/ out3/ out4/     ❌ 不入库 —— M0 实测产物（含真实响应内容）
```

### 为什么把 `dsh-tavern/` 移进 `refs/`

| 理由 | 说明 |
|---|---|
| 避免嵌套 Git 仓库 | 它自带 `.git`。放在我们仓库根下，`git add` 会把它当 submodule 处理或直接报警告，是新手最容易踩的坑之一 |
| 避免 IDE 索引 171M | Android Studio / Gradle 会扫描工程根。171M 的 Node 项目会显著拖慢索引和搜索 |
| 一条 gitignore 规则覆盖全部第三方资料 | `refs/` 一行搞定，不用为每个新增参考项补规则 —— **漏一条就可能把别人的代码传上去** |

### 为什么 `app/` 是工程根，而不是把 Gradle 文件放仓库根

仓库里有文档、Python 脚本、参考资料，它们都不属于 Android 工程。
如果把 `settings.gradle.kts` 放仓库根，Android Studio 打开后会把 `docs/`、`tools/`、`refs/`
全纳入工程视图，搜索和索引都变脏。**用 Android Studio 打开 `app/` 目录即可。**

---

## 2. Git 上传策略

### 2.1 首次初始化（按顺序执行，不要跳步）

```bash
cd /Users/wangjiacheng48/LLM-project/Mirage

# ① 先确认 dsh-tavern 已移入 refs/
ls refs/dsh-tavern/package.json   # 应该能看到

# ② 初始化
git init
git branch -M main

# ③ 🔴 关键一步：先干跑，确认忽略规则生效
git add -A -n | grep -E "refs/|out[0-9]?/|\.DS_Store|__pycache__" 
#    ↑ 这条命令应该【没有任何输出】。有输出就说明 .gitignore 没盖住，停下来修。

# ④ 再确认一次总文件数量级（应该是几十个，不是几千个）
git add -A -n | wc -l

# ⑤ 确认无误后真提交
git add -A
git commit -m "chore: 项目初始化 —— 文档体系 + M0 探针 + KMP 骨架"
```

> 🔴 **永远不要**第一次就 `git add . && git commit`。
> 这个项目的仓库里有 API Key 碰过的测试产物、第三方 171M 代码、可能含成人内容的角色卡。
> 一旦误提交并推送，Git 历史里会永久留痕，清理需要 rewrite history。

### 2.2 远程仓库

| 项 | 决定 | 理由 |
|---|---|---|
| 平台 | GitHub | 后续要做 Release 分发 APK，Actions 也够用 |
| 可见性 | **先 Private** | 项目定位和竞品策略都写在 docs 里，早期不必公开；想开源随时可以转 Public，反过来不行 |
| 分支 | `main` + `feat/*` | 个人项目不需要 develop/release 那套。功能在 `feat/xxx` 做完合回 main |

### 2.3 提交粒度

抄 dsh-tavern 的 `AGENTS.md`（它的做法很适合小团队）：

> 每完成一个比较大的功能点或修复，立即单独提交一次；提交只包含该功能点或修复，避免混入无关改动。琐碎改动（调文案、挪按钮）不必单独提交。

提交信息用 Conventional Commits：

```
feat(state): 实现 G1~G8 状态守卫
fix(card): 修复 ST 卡 initvar 中文键名含点号时路径解析错误
docs: 补充 dsh-tavern 源码研究结论
chore: 升级 Ktor 到 3.0.3
test(state): 补充敌人生命上限占位值的回归用例
```

scope 用模块名（`state` / `card` / `prompt` / `llm` / `ui`），方便日后按模块翻历史。

### 2.4 🔴 Key 防线（三层，缺一不可）

| 层 | 措施 | 状态 |
|---|---|---|
| 1 | `.gitignore` 排除 `.env` / `*.key` / `local.properties` / `out*/` / `refs/` | ✅ 已配 |
| 2 | 提交前干跑检查（§2.1 步骤 ③） | 需每次执行 |
| 3 | pre-commit hook 扫描疑似 Key | ⬜ 待做（见下） |

第三层建议在首次提交后立刻加上：

```bash
cat > .git/hooks/pre-commit << 'EOF'
#!/bin/sh
# 扫描暂存区里疑似 API Key 的字符串，命中就阻止提交
if git diff --cached | grep -nE 'sk-[A-Za-z0-9_-]{16,}|sk-or-v1-[A-Za-z0-9]{16,}'; then
  echo ""
  echo "🔴 暂存区里有疑似 API Key，已阻止提交。"
  echo "   确认是误报时用 git commit --no-verify 跳过。"
  exit 1
fi
EOF
chmod +x .git/hooks/pre-commit
```

> 注意 hook 存在 `.git/` 里，**不会随仓库分发**。换机器要重新装。

---

## 3. `refs/cards/` —— ST 角色卡参考库

### 用途

收集优秀的 SillyTavern 角色卡，用于：
1. **兼容性测试**：我们的导入器能不能吃下真实世界的各种卡；
2. **设计参考**：看高手怎么写世界书、怎么设计 MVU 状态结构；
3. **回归 fixture 的来源**。

### 规则

| 规则 | 说明 |
|---|---|
| ❌ 整目录不入库 | 第三方版权 + 可能含成人内容。`.gitignore` 里 `refs/` 已覆盖 |
| ✅ 提炼后的 fixture 可入库 | 从卡里抽出**结构片段**（schema、路径表），去掉人设正文和图片，放到 `app/shared/src/commonTest/resources/fixtures/` |
| 📝 来源要记 | 每张卡配一个同名 `.source.txt` 记下从哪拿的，将来万一要联系作者授权能找到 |

建议子目录：

```
refs/cards/
├── mvu/          带 MVU / 状态系统的卡（最高价值，我们的核心场景）
├── worldbook/    世界书用得深的卡（测召回与注入）
├── regex/        正则用得多的卡（测我们的正则引擎兼容度）
└── plain/        纯对话卡（测最简路径不被过度设计拖累）
```

### 优先找什么样的卡

按对我们的价值排序：

1. **带 `<initvar>` + `$meta.strictSet` 的 MVU 卡** —— 直接验证 G1~G8；
2. **世界书条目 > 20 条的卡** —— 验证召回策略和上下文预算；
3. **用了 ValueWithDescription（`[值, "描述"]` 二元组）的卡** —— 这是 MVU 的特殊结构，我们的解析器必须支持；
4. **正则里写了 JS 的卡** —— 验证 ADR-011（我们不执行 JS）的降级表现够不够体面。

---

## 4. 工作副本同步

AI 协作时在沙箱内有一份工作副本，真实项目目录是
`/Users/wangjiacheng48/LLM-project/Mirage/`。每轮结束同步：

```bash
cp -R docs tools app AGENTS.md README.md .gitignore /Users/wangjiacheng48/LLM-project/Mirage/
```

注意 `refs/` 和 `out*/` **不参与同步**（前者太大，后者只在真实目录里产生）。
