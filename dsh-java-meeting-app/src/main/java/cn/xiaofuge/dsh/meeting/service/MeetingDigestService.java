package cn.xiaofuge.dsh.meeting.service;

import cn.xiaofuge.dsh.meeting.config.MeetingDigestProperties;
import cn.xiaofuge.dsh.meeting.model.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 会议纪要蒸馏器：粘贴/保存转录文本 → 决议、待办、分歧、话题切分、跟进清单。
 * 基于本地规则提取，不臆造转录中未提及的内容。
 */
@Service
public class MeetingDigestService {
    private static final Pattern SPEAKER_LINE = Pattern.compile(
            "^\\s*(?:\\[)?(\\d{1,2}:\\d{2}(?::\\d{2})?)?(?:\\])?\\s*([\\w\\u4e00-\\u9fa5·]{1,12})\\s*[:：]\\s*(.+)$");
    private static final Pattern ACTION = Pattern.compile(
            "(?:由|让|请|安排)\\s*([\\w\\u4e00-\\u9fa5·]{2,8}?)(?=负责|跟进|完成|处理|落地|输出|评审|上线|修复|准备|编写|写)"
            + "|([\\w\\u4e00-\\u9fa5·]{2,8})(?:负责|跟进)"
            + "|我来?(写|准备|输出|完成|处理)(.{0,30})");
    private static final Pattern DEADLINE = Pattern.compile("(周[一二三四五六日天]|下周|本月|\\d{1,2}月\\d{1,2}[日号]|\\d+天[以内后]?|周五前|月底前)");
    private static final Pattern DECISION = Pattern.compile(
            "(?:决定|敲定|同意小流量|拍板|结论是|一致同意|最终决定)\\s*([^，,。；]{2,60})");
    private static final Pattern DECISION_LOOSE = Pattern.compile(
            "(?:排期确定|方案确定|确定.{0,6}上线)\\s*([^，,。；]{0,60})");
    private static final Pattern DISAGREE = Pattern.compile(
            "(?:不同意|有分歧|质疑|反对|顾虑|担心|存疑|风险是|但是|不过)");

    private final MeetingDigestProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, Map<String, Object>> meetings = new LinkedHashMap<>();

    public MeetingDigestService(MeetingDigestProperties properties) {
        this.properties = properties;
        load();
    }

    /** 摄入转录文本，存为会议记录，返回 id。 */
    public synchronized Map<String, Object> ingest(String title, String text) {
        if (text == null || text.trim().length() < 30) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "转录文本太短（至少 30 字）");
        }
        String id = "mtg-" + Long.toString(System.currentTimeMillis(), 36);
        Map<String, Object> meeting = new LinkedHashMap<>();
        meeting.put("id", id);
        meeting.put("title", title == null || title.isBlank() ? "未命名会议" : title.trim());
        meeting.put("chars", text.length());
        meeting.put("ingestedAt", Instant.now().toString());
        meeting.put("text", text);
        meetings.put(id, meeting);
        persist();
        Map<String, Object> view = new LinkedHashMap<>(meeting);
        view.remove("text");
        return view;
    }

    public synchronized List<Map<String, Object>> list() {
        return meetings.values().stream().map(m -> {
            Map<String, Object> view = new LinkedHashMap<>(m);
            view.remove("text");
            return view;
        }).collect(Collectors.toList());
    }

    public synchronized void delete(String id) {
        if (meetings.remove(id) == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "会议不存在");
        }
        persist();
    }

    private String textOf(String id) {
        Map<String, Object> meeting = meetings.get(id);
        if (meeting == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "会议不存在：" + id);
        }
        return String.valueOf(meeting.get("text"));
    }

    /** 待办提取：从发言行中找"谁 + 做什么 + 什么时候"。 */
    public Map<String, Object> extractActions(String id) {
        String text = textOf(id);
        List<Map<String, Object>> actions = new ArrayList<>();
        for (String line : text.split("\\n+")) {
            Matcher speaker = SPEAKER_LINE.matcher(line);
            String speakerName = speaker.find() ? speaker.group(2) : "";
            String body = speaker.find() ? speaker.group(3) : line;
            Matcher m = ACTION.matcher(body);
            while (m.find()) {
                String owner = m.group(1) != null ? m.group(1)
                        : m.group(2) != null ? m.group(2)
                        : speakerName;
                String verb = m.group(1) != null ? "跟进" : m.group(2) != null ? "负责" : m.group(3);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("owner", owner);
                item.put("verb", verb);
                Matcher deadline = DEADLINE.matcher(body);
                item.put("deadline", deadline.find() ? deadline.group(1) : "未明确");
                item.put("context", truncate(body.trim(), 120));
                item.put("mentionedBy", speakerName);
                // 去重：同一 owner+context 不重复记
                boolean dup = actions.stream().anyMatch(a ->
                        String.valueOf(a.get("owner")).equals(owner)
                                && String.valueOf(a.get("context")).equals(item.get("context")));
                if (!dup) actions.add(item);
                if (actions.size() >= 30) break;
            }
            if (actions.size() >= 30) break;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actionCount", actions.size());
        result.put("actions", actions);
        return result;
    }

    /** 决议提取。 */
    public Map<String, Object> extractDecisions(String id) {
        String text = textOf(id);
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (String line : text.split("\\n+")) {
            Matcher m = DECISION.matcher(line);
            while (m.find()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("decision", truncate(m.group(1).trim(), 120));
                Matcher speaker = SPEAKER_LINE.matcher(line);
                item.put("by", speaker.find() ? speaker.group(2) : "");
                decisions.add(item);
            }
            Matcher loose = DECISION_LOOSE.matcher(line);
            while (loose.find()) {
                String content = loose.group(1).isBlank() ? line.replaceAll("^.*?(排期确定|方案确定)", "").trim() : loose.group(1).trim();
                if (content.isBlank()) continue;
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("decision", truncate(content, 120));
                Matcher speaker = SPEAKER_LINE.matcher(line);
                item.put("by", speaker.find() ? speaker.group(2) : "");
                boolean dup = decisions.stream().anyMatch(d ->
                        String.valueOf(d.get("decision")).equals(item.get("decision")));
                if (!dup) decisions.add(item);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("decisionCount", decisions.size());
        result.put("decisions", decisions);
        return result;
    }

    /** 话题切分：按发言切换/关键词密度分段。 */
    public Map<String, Object> splitTopics(String id) {
        String text = textOf(id);
        String[] lines = text.split("\\n+");
        List<Map<String, Object>> topics = new ArrayList<>();
        List<String> current = new ArrayList<>();
        String currentSpeaker = "";
        for (String line : lines) {
            Matcher speaker = SPEAKER_LINE.matcher(line);
            String name = speaker.find() ? speaker.group(2) : "";
            if (!name.isBlank() && !name.equals(currentSpeaker) && current.size() >= 3) {
                topics.add(topic(currentSpeaker, current));
                current = new ArrayList<>();
            }
            if (!name.isBlank()) currentSpeaker = name;
            current.add(line);
        }
        if (!current.isEmpty()) topics.add(topic(currentSpeaker, current));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("topicCount", topics.size());
        result.put("topics", topics);
        return result;
    }

    /** 跟进清单：待办 + 分歧点 + 未决问题，按负责人聚合。 */
    public Map<String, Object> followups(String id) {
        Map<String, Object> actionsData = extractActions(id);
        String text = textOf(id);
        List<Map<String, Object>> concerns = new ArrayList<>();
        for (String line : text.split("\\n+")) {
            Matcher m = DISAGREE.matcher(line);
            if (m.find()) {
                Matcher speaker = SPEAKER_LINE.matcher(line);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("type", "concern");
                item.put("by", speaker.find() ? speaker.group(2) : "");
                item.put("text", truncate(line.trim(), 150));
                concerns.add(item);
            }
        }
        Map<String, Integer> byOwner = new HashMap<>();
        for (Object raw : (List<?>) actionsData.get("actions")) {
            Map<String, Object> action = (Map<String, Object>) raw;
            String owner = String.valueOf(action.get("owner"));
            byOwner.merge(owner, 1, Integer::sum);
        }
        List<Map<String, Object>> ownerList = byOwner.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .map(e -> {
                    Map<String, Object> item = new LinkedHashMap<String, Object>();
                    item.put("owner", e.getKey());
                    item.put("pendingActions", e.getValue());
                    return item;
                })
                .collect(Collectors.toList());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actions", actionsData.get("actionCount"));
        result.put("concerns", concerns);
        result.put("concernCount", concerns.size());
        result.put("byOwner", ownerList);
        return result;
    }

    // ---------- persistence ----------

    private Map<String, Object> topic(String speaker, List<String> lines) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("leadSpeaker", speaker);
        item.put("lines", lines.size());
        String joined = String.join(" ", lines);
        item.put("preview", truncate(joined.replaceAll("\\s+", " ").trim(), 100));
        return item;
    }

    private void persist() {
        try {
            Path file = Path.of(properties.dataDirectory(), "meetings.json");
            Files.createDirectories(file.getParent());
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), meetings.values());
        } catch (IOException e) {
            throw new IllegalStateException("无法保存会议记录", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void load() {
        try {
            Path file = Path.of(properties.dataDirectory(), "meetings.json");
            if (!Files.exists(file)) return;
            List<Map<String, Object>> values = objectMapper.readValue(file.toFile(),
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            values.forEach(m -> meetings.put(String.valueOf(m.get("id")), m));
        } catch (Exception ignored) {
        }
    }

    private String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
