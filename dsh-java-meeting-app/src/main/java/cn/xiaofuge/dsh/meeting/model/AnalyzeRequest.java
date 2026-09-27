package cn.xiaofuge.dsh.meeting.model;

import jakarta.validation.constraints.NotBlank;

public record AnalyzeRequest(
        @NotBlank String path
) {
}
