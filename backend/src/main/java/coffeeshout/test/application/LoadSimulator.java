package coffeeshout.test.application;

import org.springframework.stereotype.Component;

@Component
public class LoadSimulator {

    public void simulate(LoadType loadType, int durationMs) {
        switch (loadType) {
            case CPU_BOUND -> simulateCpuBoundWork(durationMs);
            case IO_BOUND -> simulateIoBoundWork(durationMs);
            case NONE -> {} // 아무것도 하지 않음
        }
    }

    private void simulateCpuBoundWork(int durationMs) {
        long startTime = System.currentTimeMillis();
        long targetTime = startTime + durationMs;

        double result = 0;
        while (System.currentTimeMillis() < targetTime) {
            result += Math.sqrt(Math.random() * 1000);
        }
    }

    private void simulateIoBoundWork(int durationMs) {
        try {
            Thread.sleep(durationMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
