package coffeeshout.test.config;

import coffeeshout.test.application.LoadType;
import org.springframework.stereotype.Component;

@Component
public class LoadConfig {

    private LoadType loadType = LoadType.NONE;
    private int loadDurationMs = 0;

    public void configure(LoadType loadType, int loadDurationMs) {
        this.loadType = loadType;
        this.loadDurationMs = loadDurationMs;
    }

    public LoadType getLoadType() {
        return loadType;
    }

    public int getLoadDurationMs() {
        return loadDurationMs;
    }
}
