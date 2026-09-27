package cn.xiaofuge.dsh.meeting.controller;

import cn.xiaofuge.dsh.meeting.config.MeetingDigestProperties;
import cn.xiaofuge.dsh.meeting.model.ApiException;
import cn.xiaofuge.dsh.meeting.service.HarnessAiStreamService;
import cn.xiaofuge.dsh.meeting.service.MeetingDigestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
public class MeetingDigestController {
    private final MeetingDigestService digestService;
    private final HarnessAiStreamService harnessAiStreamService;
    private final MeetingDigestProperties properties;

    public MeetingDigestController(MeetingDigestService digestService,
                                   HarnessAiStreamService harnessAiStreamService,
                                   MeetingDigestProperties properties) {
        this.digestService = digestService;
        this.harnessAiStreamService = harnessAiStreamService;
        this.properties = properties;
    }

    public record IngestRequest(String title, String text) {
    }

    @GetMapping("/api/meetings")
    public List<Map<String, Object>> list() {
        return digestService.list();
    }

    @PostMapping("/api/meetings")
    public Map<String, Object> ingest(@RequestBody IngestRequest request) {
        return digestService.ingest(request.title(), request.text());
    }

    @DeleteMapping("/api/meetings/{id}")
    public void delete(@PathVariable String id) {
        digestService.delete(id);
    }

    @PostMapping("/api/meetings/{id}/actions")
    public Map<String, Object> actions(@PathVariable String id) {
        return digestService.extractActions(id);
    }

    @PostMapping("/api/meetings/{id}/decisions")
    public Map<String, Object> decisions(@PathVariable String id) {
        return digestService.extractDecisions(id);
    }

    @PostMapping("/api/meetings/{id}/topics")
    public Map<String, Object> topics(@PathVariable String id) {
        return digestService.splitTopics(id);
    }

    @PostMapping("/api/meetings/{id}/followups")
    public Map<String, Object> followups(@PathVariable String id) {
        return digestService.followups(id);
    }

    @PostMapping(value = "/api/assistant/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistant(@RequestBody Map<String, String> request) {
        String agentId = request.getOrDefault("agentId", "");
        String message = request.getOrDefault("message", "");
        String meetingId = request.getOrDefault("meetingId", "");
        if (agentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "agentId 不能为空");
        }
        if (message.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "message 不能为空");
        }
        return harnessAiStreamService.stream(agentId, buildPrompt(meetingId, message));
    }

    private String buildPrompt(String meetingId, String message) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("当前环境：\n");
        prompt.append("- Meeting Digest（会议纪要蒸馏器）\n");
        if (!meetingId.isBlank()) {
            prompt.append("- 当前会议 ID：").append(meetingId).append("\n");
        }
        prompt.append("- 你可以使用以下会议工具获取真实数据（回答涉及待办、决议、话题、跟进的问题时，必须先调用工具查证，禁止臆造转录中不存在的待办）：\n");
        prompt.append("  - plugin__dsh-java-meeting-plugin__mtg_extract_actions(meetingId)：待办（谁/做什么/何时）\n");
        prompt.append("  - plugin__dsh-java-meeting-plugin__mtg_extract_decisions(meetingId)：决议清单\n");
        prompt.append("  - plugin__dsh-java-meeting-plugin__mtg_split_topics(meetingId)：话题切分\n");
        prompt.append("  - plugin__dsh-java-meeting-plugin__mtg_followup_list(meetingId)：跟进清单（待办+分歧+按人聚合）\n");
        prompt.append("  - plugin__dsh-java-meeting-plugin__mtg_ingest_text(title, text)：摄入新转录文本\n");
        prompt.append("\n用户需求：").append(message);
        prompt.append("\n\n回答要求：\n");
        prompt.append("- 使用中文，Markdown 格式，结论先行。\n");
        prompt.append("- 只引用转录中真实提到的内容；未提及截止时间就写'未明确'，禁止编造。\n");
        prompt.append("- 引用工具结果时给出关键数字与结论，不要原样转储大段 JSON。\n");
        prompt.append("- 本产品不外发纪要内容。\n");
        return prompt.toString();
    }
}
