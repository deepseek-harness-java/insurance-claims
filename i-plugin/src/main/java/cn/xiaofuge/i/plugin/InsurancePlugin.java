package cn.xiaofuge.i.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** AI 保险理赔助手插件：把 insurance-claims REST API 注册为 DSH Agent 工具 */
public class InsurancePlugin extends AbstractHarnessPlugin {

    public static final String PLUGIN_ID = "insurance-copilot";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3)).build();

    public InsurancePlugin() { super(PLUGIN_ID); }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new PolicyListTool(),
                new ClaimCheckTool(),
                new ClaimFileTool(),
                new ClaimAdjustTool(),
                new StatsTool());
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("insurance-capabilities", 20, """
                ## AI 保险理赔助手（保险理赔 · 2026-09-24）
                - 用户问"我的保单/保险" → policy_list（可按投保人姓名过滤）
                - 用户想理赔/问能不能赔 → claim_check 先行（policyId + amount + reason；
                  报告保单有效性、免赔额、赔付比例、预估赔付与所需材料，无效或超限先整改）
                - 用户确认后提交 → claim_file（policyId/amount/reason；提交前必须把核验结论与预估赔付复述请用户确认）
                - 用户是理赔审核员 → claim_adjust（claimId + pass true/false + note；拒绝必须给理由；也可 askDocs=true 仅要求补材料）
                - 用户问"理赔情况/赔付统计" → stats
                - 各险种规则（免赔额/赔付比例/单次上限）：医疗险 100 元 90%，重疾险 0 元 100%，意外险 0 元 80%，车险 500 元 85%，家财险 200 元 70%
                - 回答要求：
                  1) 核验结果逐条列出问题与整改建议，不通过的坚决不能提交
                  2) 提交后必须报理赔单号（C 前缀）、预估赔付、所需材料清单
                  3) 审核通过必报赔付金额（免赔额与比例计算过程），拒绝必须说明依据
                  4) 数据来自工具返回，禁止编造保单与赔付金额
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__")) {
                return PluginHookResult.context("audit: insurance tool call.");
            }
            return null;
        });
    }

    private String get(String path, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path)).GET().build());
    }

    private String post(String path, String jsonBody, Map<String, Object> args) {
        return send(HttpRequest.newBuilder(URI.create(baseUrl(args) + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8)).build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("appBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("INSURANCE_APP_BASE_URL", "http://127.0.0.1:18097")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) return "{\"error\":true,\"status\":" + resp.statusCode() + "}";
            return resp.body();
        } catch (Exception e) {
            return "{\"error\":true,\"message\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }

    private String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private String json(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    private class PolicyListTool extends AbstractTool {
        @Override public String name() { return "policy_list"; }
        @Override public String description() {
            return "保单列表：保单号/投保人/险种/保费/生效与到期日/状态/本年已赔，可按投保人过滤。"
                    + "何时必须调用：查保单、理赔前确认保障、问缴费情况。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("holder", stringSchema("可选：投保人姓名关键字"))
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String h = str(args, "holder");
            return ok(get("/api/policies" + (h.isBlank() ? "" : "?holder=" + java.net.URLEncoder.encode(h, StandardCharsets.UTF_8)), args));
        }
    }

    private class ClaimCheckTool extends AbstractTool {
        @Override public String name() { return "claim_check"; }
        @Override public String description() {
            return "理赔事前核验：policyId + amount + reason。返回保单有效性、免赔额、赔付比例、预估赔付、所需材料与问题清单。提交前必须调用。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("policyId", stringSchema("保单号，如 P2001"))
                    .prop("amount", stringSchema("申请理赔金额（元）"))
                    .prop("reason", stringSchema("出险事由简述"))
                    .required("policyId", "amount", "reason")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"policyId\":\"" + json(str(args, "policyId"))
                    + "\",\"amount\":" + str(args, "amount")
                    + ",\"reason\":\"" + json(str(args, "reason")) + "\"}";
            return ok(post("/api/check", body, args));
        }
    }

    private class ClaimFileTool extends AbstractTool {
        @Override public String name() { return "claim_file"; }
        @Override public String description() {
            return "提交理赔申请：policyId/amount/reason 必填。必须先 claim_check 通过并经用户确认后才能调用。返回理赔单号与预估赔付。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("policyId", stringSchema("保单号，如 P2001"))
                    .prop("amount", stringSchema("申请理赔金额（元）"))
                    .prop("reason", stringSchema("出险事由简述"))
                    .required("policyId", "amount", "reason")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String body = "{\"policyId\":\"" + json(str(args, "policyId"))
                    + "\",\"amount\":" + str(args, "amount")
                    + ",\"reason\":\"" + json(str(args, "reason")) + "\"}";
            return ok(post("/api/claim", body, args));
        }
    }

    private class ClaimAdjustTool extends AbstractTool {
        @Override public String name() { return "claim_adjust"; }
        @Override public String description() {
            return "理赔审核：claimId + pass(true/false) + note。通过则按免赔额与比例计算赔付；拒绝必须给理由；askDocs=true 表示仅要求补充材料。仅理赔审核员可用。";
        }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("claimId", stringSchema("理赔单号，如 C5001"))
                    .prop("pass", stringSchema("true=通过赔付 / false=拒赔或转补材料"))
                    .prop("note", stringSchema("审核意见（拒赔时必填理由）"))
                    .prop("askDocs", stringSchema("可选：true 表示仅要求补充材料而非拒赔"))
                    .required("claimId", "pass")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            String claimId = str(args, "claimId");
            if ("true".equalsIgnoreCase(str(args, "askDocs"))) {
                String body = "{\"claimId\":\"" + json(claimId) + "\",\"docs\":\"" + json(str(args, "note")) + "\"}";
                return ok(post("/api/docs", body, args));
            }
            String body = "{\"claimId\":\"" + json(claimId)
                    + "\",\"pass\":\"" + json(str(args, "pass"))
                    + "\",\"note\":\"" + json(str(args, "note")) + "\"}";
            return ok(post("/api/adjust", body, args));
        }
    }

    private class StatsTool extends AbstractTool {
        @Override public String name() { return "stats"; }
        @Override public String description() {
            return "理赔统计：案件总数/待审核/材料补齐/已赔付金额/待审责任额/分险种赔付情况/处理建议。"
                    + "何时必须调用：问理赔整体情况、问赔付支出。";
        }
        @Override public Map<String, Object> parameters() { return objectSchema().build(); }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected CompletableFuture<ToolExecutionResult> run(Map<String, Object> args, ToolRunContext ctx) {
            return ok(get("/api/stats", args));
        }
    }
}
