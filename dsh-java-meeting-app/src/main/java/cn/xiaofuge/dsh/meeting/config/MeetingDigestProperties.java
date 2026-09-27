package cn.xiaofuge.dsh.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "dsh.meeting")
public record MeetingDigestProperties(
        String dataDirectory,
        int commandTimeoutSeconds,
        int maxCommits,
        Harness harness
) {
    public record Harness(String baseUrl) {
    }
}
