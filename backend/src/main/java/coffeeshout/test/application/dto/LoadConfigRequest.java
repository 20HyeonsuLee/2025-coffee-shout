package coffeeshout.test.application.dto;

import coffeeshout.test.application.LoadType;

public record LoadConfigRequest(int outboundTps, LoadType loadType, int loadDurationMs) {
}
