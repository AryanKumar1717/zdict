package dev.zdict.spring;

import dev.zdict.core.DictionaryRegistry;
import dev.zdict.core.DictionaryTrainerService;
import dev.zdict.core.PayloadScrubber;
import dev.zdict.core.SampleStore;
import dev.zdict.core.ZdictCircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ZdictAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ZdictAutoConfiguration.class));

    @TempDir
    Path tempDir;

    @Test
    void disabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ZstdDictFilter.class);
            assertThat(context).doesNotHaveBean(DictionaryRegistry.class);
            assertThat(context).doesNotHaveBean(SampleStore.class);
            assertThat(context).doesNotHaveBean(DictionaryTrainerService.class);
            assertThat(context).doesNotHaveBean(ZdictProperties.class);
        });
    }

    @Test
    void enabledWithMinimalProperties() {
        contextRunner
                .withPropertyValues(
                        "zdict.enabled=true",
                        "zdict.paths=/api/products",
                        "zdict.data-dir=" + tempDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ZdictProperties.class);
                    assertThat(context).hasSingleBean(ZstdDictFilter.class);
                    assertThat(context).hasSingleBean(DictionaryRegistry.class);
                    assertThat(context).hasSingleBean(SampleStore.class);
                    assertThat(context).hasSingleBean(DictionaryTrainerService.class);
                    assertThat(context).hasSingleBean(ZdictCircuitBreaker.class);
                    assertThat(context).hasSingleBean(PayloadScrubber.class);
                    assertThat(context).hasSingleBean(DictionaryController.class);
                    assertThat(context).hasSingleBean(ZdictTrainController.class);

                    // Dashboard is disabled by default
                    assertThat(context).doesNotHaveBean(ZdictDashboardController.class);
                });
    }

    @Test
    void userBeanOverrideWorks() {
        contextRunner
                .withUserConfiguration(CustomBeanConfig.class)
                .withPropertyValues(
                        "zdict.enabled=true",
                        "zdict.paths=/api/products",
                        "zdict.data-dir=" + tempDir.toAbsolutePath()
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ZdictCircuitBreaker.class);
                    ZdictCircuitBreaker cb = context.getBean(ZdictCircuitBreaker.class);
                    assertThat(cb).isSameAs(CustomBeanConfig.CUSTOM_BREAKER);
                });
    }

    @Test
    void dashboardEnabledWhenPropertySet() {
        contextRunner
                .withPropertyValues(
                        "zdict.enabled=true",
                        "zdict.paths=/api/products",
                        "zdict.data-dir=" + tempDir.toAbsolutePath(),
                        "zdict.dashboard.enabled=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ZdictDashboardController.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomBeanConfig {
        static final ZdictCircuitBreaker CUSTOM_BREAKER = new ZdictCircuitBreaker();

        @Bean
        public ZdictCircuitBreaker customCircuitBreaker() {
            return CUSTOM_BREAKER;
        }
    }
}
