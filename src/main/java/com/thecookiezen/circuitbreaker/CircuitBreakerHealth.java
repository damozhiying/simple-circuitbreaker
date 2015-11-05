package com.thecookiezen.circuitbreaker;

import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixEventType;
import com.netflix.hystrix.HystrixMetrics;
import com.netflix.hystrix.strategy.HystrixPlugins;
import com.netflix.hystrix.strategy.eventnotifier.HystrixEventNotifier;
import com.netflix.hystrix.util.HystrixRollingNumber;
import com.netflix.hystrix.util.HystrixRollingNumberEvent;

import java.util.concurrent.atomic.AtomicLong;

public class CircuitBreakerHealth extends HystrixMetrics {
    private final HystrixCommandKey key;
    private final CircuitBreakerHealthConfiguration config;
    private final HystrixEventNotifier eventNotifier;
    private volatile CircuitBreakerHealth.HealthCounts healthCountsSnapshot = new CircuitBreakerHealth.HealthCounts(0L, 0L, 0);
    private volatile AtomicLong lastHealthCountsSnapshot = new AtomicLong(System.currentTimeMillis());

    public static CircuitBreakerHealth getInstance(CircuitBreakerHealthConfiguration config) {
        final HystrixCommandKey key = HystrixCommandKey.Factory.asKey(config.getKey());
        return new CircuitBreakerHealth(key, config, HystrixPlugins.getInstance().getEventNotifier());
    }

    CircuitBreakerHealth(HystrixCommandKey key, CircuitBreakerHealthConfiguration config, HystrixEventNotifier eventNotifier) {
        super(new HystrixRollingNumber(config::getRollingStatisticalWindowInMilliseconds, config::getRollingStatisticalWindowBuckets));
        this.key = key;
        this.config = config;
        this.eventNotifier = eventNotifier;
    }

    void resetCounter() {
        this.counter.reset();
        this.lastHealthCountsSnapshot.set(System.currentTimeMillis());
        this.healthCountsSnapshot = new CircuitBreakerHealth.HealthCounts(0L, 0L, 0);
    }

    void markSuccess() {
        this.eventNotifier.markEvent(HystrixEventType.SUCCESS, this.key);
        this.counter.increment(HystrixRollingNumberEvent.SUCCESS);
    }

    void markFailure() {
        this.eventNotifier.markEvent(HystrixEventType.FAILURE, this.key);
        this.counter.increment(HystrixRollingNumberEvent.FAILURE);
    }

    public boolean shouldOpen() {
        HealthCounts health = getHealthCounts();
        if (health.getTotalRequests() < config.getRequestVolumeThreshold()) {
            return false;
        }

        return health.getErrorPercentage() >= config.getErrorThresholdPercentage();
    }

    public CircuitBreakerHealth.HealthCounts getHealthCounts() {
        long lastTime = this.lastHealthCountsSnapshot.get();
        long currentTime = System.currentTimeMillis();
        if ((currentTime - lastTime >= config.getHealthSnapshotIntervalInMilliseconds()
                || this.healthCountsSnapshot == null) && this.lastHealthCountsSnapshot.compareAndSet(lastTime, currentTime)) {
            long success = this.counter.getRollingSum(HystrixRollingNumberEvent.SUCCESS);
            long failure = this.counter.getRollingSum(HystrixRollingNumberEvent.FAILURE);
            long totalCount = failure + success;
            int errorPercentage = 0;
            if (totalCount > 0L) {
                errorPercentage = (int) ((double) failure / (double) totalCount * 100.0D);
            }

            this.healthCountsSnapshot = new CircuitBreakerHealth.HealthCounts(totalCount, failure, errorPercentage);
        }

        return this.healthCountsSnapshot;
    }

    public static class HealthCounts {
        private final long totalCount;
        private final long errorCount;
        private final int errorPercentage;

        HealthCounts(long total, long error, int errorPercentage) {
            this.totalCount = total;
            this.errorCount = error;
            this.errorPercentage = errorPercentage;
        }

        public long getTotalRequests() {
            return this.totalCount;
        }

        public long getErrorCount() {
            return this.errorCount;
        }

        public int getErrorPercentage() {
            return this.errorPercentage;
        }
    }
}
