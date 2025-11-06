package coffeeshout.test.application.dto;

public record MetricsResponse(
        double inboundP95,
        double inboundP99,
        double outboundP95,
        double outboundP99,
        double businessLogicP95,
        double businessLogicP99,
        int inboundActiveThreads,
        int inboundQueueSize,
        int outboundActiveThreads,
        int outboundQueueSize,
        long totalInboundMessages,
        long totalOutboundMessages
) {
}
