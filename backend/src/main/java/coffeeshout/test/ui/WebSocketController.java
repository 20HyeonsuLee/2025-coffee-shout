package coffeeshout.test.ui;

import coffeeshout.test.application.dto.LoadConfigRequest;
import coffeeshout.test.application.LoadSimulator;
import coffeeshout.test.config.LoadConfig;
import coffeeshout.test.application.dto.InboundRequest;
import coffeeshout.test.application.ResponseService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Controller
@RequiredArgsConstructor
public class WebSocketController {

    private final ResponseService responseService;
    private final LoadSimulator loadSimulator;
    private final LoadConfig loadConfig;

    @MessageMapping(value = "/test/load/request")
    public void load(@Payload InboundRequest request) {
        loadSimulator.simulate(loadConfig.getLoadType(), loadConfig.getLoadDurationMs());
    }

    @PostMapping("/test/load/config")
    public ResponseEntity<Void> configureLoad(@RequestBody LoadConfigRequest request) {
        responseService.setTps(request.outboundTps());
        loadConfig.configure(request.loadType(), request.loadDurationMs());
        return ResponseEntity.ok().build();
    }

    @PostMapping(path = "/test/load/response/start")
    public ResponseEntity<Boolean> loadStart() {
        responseService.start();
        return ResponseEntity.ok(true);
    }

    @PostMapping(path = "/test/load/response/stop")
    public ResponseEntity<Boolean> loadStop() {
        responseService.stop();
        return ResponseEntity.ok(true);
    }
}
