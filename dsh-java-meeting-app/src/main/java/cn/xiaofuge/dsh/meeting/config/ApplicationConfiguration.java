package cn.xiaofuge.dsh.meeting.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MeetingDigestProperties.class)
public class ApplicationConfiguration {
}
