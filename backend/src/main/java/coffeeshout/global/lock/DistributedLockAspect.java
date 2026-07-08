package coffeeshout.global.lock;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * {@link DistributedLock}이 붙은 메서드를 Redisson {@link RLock}으로 감싼다.
 *
 * <p>Micrometer Observation API로 락 acquire + hold 구간을 기록한다.
 * 이 Observation은 메트릭(타이머)과 트레이스(span)를 동시에 생성해
 * Grafana/Jaeger 등에서 하나의 trace 안에서 lock 점유 시간을 확인할 수 있다.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@Slf4j
public class DistributedLockAspect {

    private static final String LOCK_PREFIX = "lock:";

    private final RedissonClient redissonClient;
    private final ObservationRegistry observationRegistry;

    public DistributedLockAspect(final RedissonClient redissonClient, final ObservationRegistry observationRegistry) {
        this.redissonClient = redissonClient;
        this.observationRegistry = observationRegistry;
    }

    @Around("@annotation(coffeeshout.global.lock.DistributedLock)")
    public Object intercept(final ProceedingJoinPoint pjp) throws Throwable {
        final MethodSignature signature = (MethodSignature) pjp.getSignature();
        final Method method = getMostSpecificMethod(pjp, signature);
        final DistributedLock annotation = AnnotationUtils.findAnnotation(method, DistributedLock.class);
        if (annotation == null) {
            throw new IllegalStateException("@DistributedLock annotation을 찾을 수 없습니다: " + method);
        }

        final String methodName = method.getName();
        final String lockKey = resolveLockKey(annotation.key(), method, pjp.getArgs());
        final RLock lock = redissonClient.getLock(lockKey);

        final Observation acquireObs = Observation.createNotStarted("distributed.lock.acquire", observationRegistry)
                .lowCardinalityKeyValue("method", methodName)
                .start();
        final boolean locked;
        try {
            locked = tryLock(lock, annotation);
        } finally {
            acquireObs.stop();
        }
        if (!locked) {
            log.warn("분산 락 획득 실패: method={}, key={}, waitSeconds={}",
                    methodName, lockKey, annotation.waitSeconds());
            throw new IllegalStateException("분산 락 획득에 실패했습니다: " + methodName);
        }

        final Observation holdObs = Observation.createNotStarted("distributed.lock.hold", observationRegistry)
                .lowCardinalityKeyValue("method", methodName)
                .start();
        try {
            return pjp.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            holdObs.stop();
        }
    }

    private Method getMostSpecificMethod(final ProceedingJoinPoint pjp, final MethodSignature signature) {
        if (pjp.getTarget() == null) {
            return signature.getMethod();
        }
        return AopUtils.getMostSpecificMethod(signature.getMethod(), pjp.getTarget().getClass());
    }

    private boolean tryLock(final RLock lock, final DistributedLock annotation) {
        try {
            if (annotation.leaseSeconds() > 0) {
                return lock.tryLock(annotation.waitSeconds(), annotation.leaseSeconds(), TimeUnit.SECONDS);
            }
            return lock.tryLock(annotation.waitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("분산 락 획득 중 인터럽트가 발생했습니다.", e);
        }
    }

    private String resolveLockKey(final String keyTemplate, final Method method, final Object[] args) {
        final Object resolved = SpelTemplateResolver.resolve(keyTemplate, method, args);
        return LOCK_PREFIX + (resolved == null ? "" : resolved.toString());
    }
}
