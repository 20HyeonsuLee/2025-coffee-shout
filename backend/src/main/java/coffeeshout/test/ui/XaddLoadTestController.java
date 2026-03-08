package coffeeshout.test.ui;

import coffeeshout.test.application.XaddLoadTestService;
import coffeeshout.test.application.dto.XaddLoadTestRequest;
import coffeeshout.test.application.dto.XaddLoadTestResponse;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/test/xadd")
@RequiredArgsConstructor
public class XaddLoadTestController {

    private final XaddLoadTestService xaddLoadTestService;

    @PostMapping("/start")
    public ResponseEntity<XaddLoadTestResponse> startLoadTest(
            @RequestBody @Valid final XaddLoadTestRequest request
    ) {
        return ResponseEntity.ok(xaddLoadTestService.startLoadTest(request));
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stopLoadTest() {
        xaddLoadTestService.stopLoadTest();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Boolean>> status() {
        return ResponseEntity.ok(Map.of("running", xaddLoadTestService.isRunning()));
    }
}
