package coffeeshout.test.application.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

public record XaddLoadTestRequest(
        @Positive int tps,
        @Positive @Max(300) int durationSeconds
) {
}
