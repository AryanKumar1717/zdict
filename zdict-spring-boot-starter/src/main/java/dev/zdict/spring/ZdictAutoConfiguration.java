package dev.zdict.spring;

import dev.zdict.core.*;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.nio.file.Path;
import java.time.Clock;

/**
 * Spring Boot auto-configuration for zdict.
 *
 * <p>Activated when {@code zdict.enabled=true}. All beans are declared with
 * {@code @ConditionalOnMissingBean} so applications can provide custom implementations.
 */
@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.MetricsAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration"
})
@ConditionalOnProperty(prefix = "zdict", name = "enabled", havingValue = "true", matchIfMissing = false)
@EnableConfigurationProperties(ZdictProperties.class)
public class ZdictAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public ZdictCircuitBreaker zdictCircuitBreaker(ObjectProvider<Clock> clockProvider) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new ZdictCircuitBreaker(clock, 60_000);
    }

    @Bean
    @ConditionalOnMissingBean
    public PayloadScrubber payloadScrubber(ZdictProperties props) {
        return new PayloadScrubber(props.getDenylistedFields());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public DictionaryRegistry dictionaryRegistry(ZdictProperties props, ObjectProvider<Clock> clockProvider) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        DictionaryRegistry registry = new DictionaryRegistry(Path.of(props.getDataDir()), null, clock);
        registry.init();
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public SampleStore sampleStore(ZdictProperties props, PayloadScrubber scrubber) {
        return new SampleStore(props, scrubber);
    }

    @Bean
    @ConditionalOnMissingBean
    public RatioTracker ratioTracker(DictionaryRegistry registry,
                                    ZdictProperties props,
                                    ObjectProvider<Clock> clockProvider,
                                    ObjectProvider<ZdictMetricsListener> metricsListenerProvider) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        ZdictMetricsListener listener = metricsListenerProvider.getIfAvailable();
        return new RatioTracker(registry, props, clock, listener);
    }

    @Bean
    @ConditionalOnMissingBean
    public EventLog eventLog(ObjectProvider<Clock> clockProvider, ObjectProvider<ZdictMetricsListener> metricsListenerProvider) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        ZdictMetricsListener listener = metricsListenerProvider.getIfAvailable();
        return new EventLog(clock, listener);
    }

    @Bean
    @ConditionalOnMissingBean
    public DictionaryTrainerService dictionaryTrainerService(SampleStore sampleStore,
                                                             DictionaryRegistry registry,
                                                             ZdictProperties props,
                                                             EventLog eventLog) {
        return new DictionaryTrainerService(sampleStore, registry, props, eventLog);
    }

    @Bean
    @ConditionalOnMissingBean
    public AutoTrainerService autoTrainerService(ZdictProperties props,
                                                 SampleStore sampleStore,
                                                 DictionaryRegistry registry,
                                                 DictionaryTrainerService trainerService,
                                                 RatioTracker ratioTracker,
                                                 ObjectProvider<Clock> clockProvider,
                                                 EventLog eventLog) {
        Clock clock = clockProvider.getIfAvailable(Clock::systemUTC);
        return new AutoTrainerService(props, sampleStore, registry, trainerService, ratioTracker, clock, eventLog);
    }

    @Bean
    @ConditionalOnMissingBean
    public ZstdDictFilter zstdDictFilter(ZdictProperties props,
                                        ZdictCircuitBreaker circuitBreaker,
                                        ObjectProvider<ZdictMetrics> metricsProvider,
                                        SampleStore sampleStore,
                                        DictionaryRegistry registry,
                                        RatioTracker ratioTracker) {
        ZdictMetrics metrics = metricsProvider.getIfAvailable();
        return new ZstdDictFilter(props, circuitBreaker, metrics, sampleStore, registry, ratioTracker);
    }

    @Bean
    @ConditionalOnMissingBean
    public DictionaryController dictionaryController(DictionaryRegistry registry) {
        return new DictionaryController(registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public ZdictTrainController zdictTrainController(DictionaryTrainerService trainerService,
                                                     DictionaryRegistry registry,
                                                     RatioTracker ratioTracker,
                                                     SampleStore sampleStore,
                                                     AutoTrainerService autoTrainer,
                                                     EventLog eventLog,
                                                     ZdictProperties props) {
        return new ZdictTrainController(trainerService, registry, ratioTracker, sampleStore, autoTrainer, eventLog, props);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(MeterRegistry.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(MeterRegistry.class)
    public ZdictMetrics zdictMetrics(MeterRegistry meterRegistry, ZdictCircuitBreaker circuitBreaker) {
        return new ZdictMetrics(meterRegistry, circuitBreaker);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    public static class ActuatorConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public ZdictEndpoint zdictEndpoint(ZstdDictFilter filter, ZdictCircuitBreaker circuitBreaker) {
            return new ZdictEndpoint(filter, circuitBreaker);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "zdict.dashboard", name = "enabled", havingValue = "true", matchIfMissing = false)
    public static class DashboardConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public ZdictDashboardController zdictDashboardController(ZdictProperties properties) {
            return new ZdictDashboardController(properties);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "zdict.auto-learning", name = "enabled", havingValue = "true", matchIfMissing = false)
    @EnableScheduling
    public static class AutoTrainerSchedulerConfiguration {
        @Bean
        @ConditionalOnMissingBean
        public AutoTrainerScheduler autoTrainerScheduler(AutoTrainerService autoTrainerService) {
            return new AutoTrainerScheduler(autoTrainerService);
        }
    }

    public static class AutoTrainerScheduler {
        private final AutoTrainerService autoTrainerService;

        public AutoTrainerScheduler(AutoTrainerService autoTrainerService) {
            this.autoTrainerService = autoTrainerService;
        }

        @Scheduled(fixedDelayString = "${zdict.auto-learning.interval:60000}")
        public void run() {
            autoTrainerService.checkAndTrain();
        }
    }
}
