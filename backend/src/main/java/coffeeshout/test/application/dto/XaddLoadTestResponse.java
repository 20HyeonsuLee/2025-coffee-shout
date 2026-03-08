package coffeeshout.test.application.dto;

public record XaddLoadTestResponse(
        int requestedTps,
        int durationSeconds,
        String status
) {
}
