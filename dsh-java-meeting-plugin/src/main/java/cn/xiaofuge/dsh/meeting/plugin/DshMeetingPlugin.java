package cn.xiaofuge.dsh.meeting.plugin;

import cn.xiaofuge.deepseek.harness.domain.model.entity.AbstractTool;
import cn.xiaofuge.deepseek.harness.domain.model.entity.ToolDefinition;
import cn.xiaofuge.deepseek.harness.domain.spi.AbstractHarnessPlugin;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginContext;
import cn.xiaofuge.deepseek.harness.domain.spi.PluginHookResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DshMeetingPlugin extends AbstractHarnessPlugin {
    public static final String PLUGIN_ID = "dsh-java-meeting-plugin";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public DshMeetingPlugin() {
        super(PLUGIN_ID);
    }

    @Override
    public List<ToolDefinition> tools() {
        return List.of(
                new IngestTool(),
                new ActionsTool(),
                new DecisionsTool(),
                new TopicsTool(),
                new FollowupsTool()
        );
    }

    @Override
    public void configure(PluginContext context) {
        super.configure(context);
        context.registerSystemPrompt("meeting-digest-capabilities", 20, """
                ## DSH Meeting Digest Plugin
                - 提取类工具只读；唯一写入工具是 mtg_ingest_text（用户明确要求保存新转录时才调用）。
                - meetingId 由对话上下文给出（形如 mtg-xxxxxx）；未知时提示用户先在页面粘贴转录文本。
                - 禁止臆造转录中不存在的待办/决议/截止时间；未提及的一律标"未明确"。
                - 生成跟进清单用 mtg_followup_list（含分歧点与按人聚合）。
                - 不外发纪要内容到任何第三方。
                """);
        context.registerHook("PRE_TOOL_USE", (toolName, payloadJson) -> {
            if (toolName != null && toolName.startsWith("plugin__" + PLUGIN_ID + "__mtg_")) {
                return PluginHookResult.context("DSH Meeting Digest audit: transcript stays local.");
            }
            return null;
        });
    }

    private String post(String path, String json, Map<String, Object> args) {
        String adminUrl = baseUrl(args);
        return send(HttpRequest.newBuilder(URI.create(adminUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build());
    }

    private String baseUrl(Map<String, Object> args) {
        Object override = args == null ? null : args.get("adminBaseUrl");
        return override == null || String.valueOf(override).isBlank()
                ? System.getenv().getOrDefault("DSH_MEETING_ADMIN_URL", "http://127.0.0.1:8104")
                : String.valueOf(override);
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                return failJson(response.statusCode(), response.body());
            }
            return response.body();
        } catch (Exception e) {
            return failJson(0, e.getMessage());
        }
    }

    private String failJson(int status, String message) {
        return "{\"error\":true,\"status\":" + status + ",\"message\":\"" + json(message) + "\"}";
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String idJson(Map<String, Object> args, String key) {
        return "{\"id\":\"" + json(str(args, key)) + "\"}";
    }

    private String str(Map<String, Object> args, String key) {
        Object value = args == null ? null : args.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private class IngestTool extends AbstractTool {
        @Override public String name() { return "mtg_ingest_text"; }
        @Override public String description() { return "摄入一段会议转录文本并保存，返回会议 ID。仅当用户明确提供转录文本并要求保存时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("title", stringSchema("会议标题"))
                    .prop("text", stringSchema("转录文本全文（至少 30 字）"))
                    .prop("adminBaseUrl", stringSchema("可选，Meeting Digest 后台地址，默认 http://127.0.0.1:8104"))
                    .required("title", "text")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return false; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            String body = "{\"title\":\"" + json(str(args, "title")) + "\",\"text\":\"" + json(str(args, "text")) + "\"}";
            return ok(post("/api/meetings", body, args));
        }
    }

    private class ActionsTool extends AbstractTool {
        @Override public String name() { return "mtg_extract_actions"; }
        @Override public String description() { return "从已保存的会议转录中提取待办：谁(owner)、做什么(verb)、截止时间(deadline，未明确则标注)。生成任务清单前必须调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("meetingId", stringSchema("会议 ID（形如 mtg-xxxxxx）"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8104"))
                    .required("meetingId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/meetings/" + json(str(args, "meetingId")) + "/actions", "{}", args));
        }
    }

    private class DecisionsTool extends AbstractTool {
        @Override public String name() { return "mtg_extract_decisions"; }
        @Override public String description() { return "从转录中提取决议（决定/敲定/同意等表述）。总结会议成果时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("meetingId", stringSchema("会议 ID"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8104"))
                    .required("meetingId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/meetings/" + json(str(args, "meetingId")) + "/decisions", "{}", args));
        }
    }

    private class TopicsTool extends AbstractTool {
        @Override public String name() { return "mtg_split_topics"; }
        @Override public String description() { return "按发言人切换把转录切分为话题段，返回每段主导发言人与预览。梳理会议结构时调用。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("meetingId", stringSchema("会议 ID"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8104"))
                    .required("meetingId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/meetings/" + json(str(args, "meetingId")) + "/topics", "{}", args));
        }
    }

    private class FollowupsTool extends AbstractTool {
        @Override public String name() { return "mtg_followup_list"; }
        @Override public String description() { return "生成跟进清单：待办数、分歧/顾虑点、按负责人聚合的待办数。会后第一件事就是调用本工具。"; }
        @Override public Map<String, Object> parameters() {
            return objectSchema()
                    .prop("meetingId", stringSchema("会议 ID"))
                    .prop("adminBaseUrl", stringSchema("可选，后台地址，默认 http://127.0.0.1:8104"))
                    .required("meetingId")
                    .build();
        }
        @Override public boolean isConcurrencySafe(Object args) { return true; }
        @Override protected java.util.concurrent.CompletableFuture<cn.xiaofuge.deepseek.harness.domain.model.entity.ToolExecutionResult>
        run(Map<String, Object> args, cn.xiaofuge.deepseek.harness.domain.model.entity.ToolRunContext ctx) {
            return ok(post("/api/meetings/" + json(str(args, "meetingId")) + "/followups", "{}", args));
        }
    }
}
