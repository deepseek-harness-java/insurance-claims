# insurance-claims · AI 理赔助手台（DSH Java Native 插件场景案例 P58）

> 基于 **deepseek-harness-java（DSH）Java Native 插件机制** 的保险理赔场景案例：5 类险种规则（免赔额/赔付比例/单次与年度上限/等待期/材料清单）+ 理赔事前核验（保单有效性 + 赔付测算 + 整改建议）+ 理赔提交（二次确认 + 单号生成）+ 审核赔付（按免赔与比例计算并计入保单本年已赔）/拒赔/补材料 + 分险种赔付统计与积压分析，通过 `insurance-copilot` 插件接入 AI 助手，支持自然语言查保单、核验测算、提交理赔、审核、看统计。

![总览](docs/images/01-overview.png)

## 一、项目组成

| 模块 | 说明 |
|------|------|
| `i-app` | Spring Boot 3.2 应用（端口 **18097**），保险理赔 REST API 与前端页面 |
| `i-plugin` | DSH Java Native 插件（`insurance-copilot`），打包 5 个 AI 工具 |

业务数据：5 份保单（医疗险/重疾险/车险/意外险/家财险各 1，含过期保单 1 份）、5 类险种规则（医疗险免赔 100 赔 90%、重疾险免赔 0 赔 100%、意外险免赔 0 赔 80%、车险免赔 500 赔 85%、家财险免赔 200 赔 70%）、5 笔初始理赔案件（待审核/已赔付/材料补齐中/已拒赔）。

## 二、插件工具（5 个）

| 工具 | 说明 |
|------|------|
| `policy_list` | 保单列表：保单号/投保人/险种/保费/生效与到期日/状态/本年已赔，可按投保人过滤 |
| `claim_check` | 理赔事前核验：保单有效性/免赔额/赔付比例/单次与年度上限，返回预估赔付与所需材料 |
| `claim_file` | 提交理赔：核验通过后生成理赔单号（C 前缀），状态「待审核」 |
| `claim_adjust` | 审核：通过按免赔与比例计算赔付；拒绝必须给理由；可仅要求补材料（askDocs） |
| `stats` | 理赔统计：案件总数/待审核/材料补齐/已赔付金额/待审责任额/分险种赔付/处理建议 |

## 三、REST API

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/policies?holder=` | 保单列表 |
| POST | `/api/check` | 理赔核验 `{policyId,amount,reason}` |
| POST | `/api/claim` | 提交理赔 `{policyId,amount,reason}` |
| POST | `/api/adjust` | 审核 `{claimId,pass,note}` |
| POST | `/api/docs` | 要求补材料 `{claimId,docs}` |
| GET | `/api/claims?status=&holder=` | 理赔案件列表 |
| GET | `/api/stats` | 理赔统计 |
| POST | `/api/assistant/stream` | AI 助手 SSE（透传 DSH） |

## 四、快速开始

```bash
mvn clean package -DskipTests
java -Dserver.port=18097 -jar i-app/target/i-app-1.0.0-SNAPSHOT.jar

bash install_plugin.sh i-plugin/target/i-plugin-1.0.0-SNAPSHOT.jar \
  insurance-copilot 1.0.0-SNAPSHOT i-plugin-1.0.0-SNAPSHOT.jar "AI 保险理赔助手"

open http://127.0.0.1:18097/
```

## 五、端到端验证

```bash
bash agent_stream.sh 127.0.0.1:8090 insurance-copilot "帮我看看王先生 P2001 医疗险，阑尾炎手术住院花了 8600，能赔多少？"
bash agent_stream.sh 127.0.0.1:8090 insurance-copilot "确认提交这份理赔申请"
bash agent_stream.sh 127.0.0.1:8090 insurance-copilot "C5001 审核通过，备注：材料齐全，符合理赔条件"
bash agent_stream.sh 127.0.0.1:8090 insurance-copilot "陈先生的 P2005 家财险水淹理赔为什么被拒了？他的保单现在还能用吗？"
bash agent_stream.sh 127.0.0.1:8090 insurance-copilot "今年理赔整体情况怎么样？各险种赔付了多少？有积压案件吗？"
```

5 个工具全部验证通过。验证截图：

| 截图 | 内容 |
|------|------|
| ![AI 核验测算](docs/images/02-ai-check.png) | AI 核验 P2001 医疗险：免赔 100 → 计赔 8500 × 90% = 预估赔付 ¥7650，附材料清单 |
| ![AI 提交理赔](docs/images/03-ai-file.png) | AI 二次确认后提交成功，报理赔单号 C5002 与所需材料 |
| ![AI 赔付统计](docs/images/04-ai-stats.png) | AI 报年度赔付 ¥18530、分险种明细与积压处理建议 |

## 六、技术要点

- **险种规则机**：`RULES` 按险种配置（等待期/免赔额/赔付比例/单次上限/年度上限/材料清单）；`check()` 流程：保单存在性 → 保障期有效 → 免赔后金额 × 比例 → 单次/年度上限校验，输出预估赔付与问题清单。
- **赔付公式**：`payout = min((amount − deductible) × payoutRate, perLimit)`，四舍五入到分；审核通过即计入保单 `paidThisYear`，年度累计超上限在核验与审核两处兜底。
- **状态机**：待审核 →（通过）已赔付 / （拒赔）已拒赔 / （补材料）材料补齐中；`file()` 强制先过 `check()`，拒赔必须填理由。
- **超时修复**：SSE 代理遇长回答触发 `AsyncRequestTimeoutException`（默认 30s），配置 `spring.mvc.async.request-timeout: 180s` 解决 503/IOException: closed。
- **结论约束**：核验必须给出赔付计算过程（花费−免赔→×比例）；提交与审核必报单号；拒赔必须说明依据（过期/等待期/超限/材料缺失）；数据全部来自工具返回。

## 七、目录结构

```
insurance-claims/
├── pom.xml                  # 父 pom（maven.compiler.parameters=true）
├── i-app/                   # Spring Boot 应用 (18097)
│   └── src/main/java/cn/xiaofuge/i/app/
│       ├── InsuranceApplication.java
│       ├── IStore.java        # 险种规则/保单/理赔/核验/审核/统计
│       ├── IController.java   # REST API
│       └── AssistantController.java # SSE 透传 DSH
├── i-plugin/                # DSH 插件 (insurance-copilot)
│   └── src/main/
│       ├── java/.../InsurancePlugin.java  # 5 工具
│       └── resources/META-INF/       # plugin.yaml + SPI
└── docs/
    ├── 使用说明.md
    └── images/              # 验证截图 ×4
```
