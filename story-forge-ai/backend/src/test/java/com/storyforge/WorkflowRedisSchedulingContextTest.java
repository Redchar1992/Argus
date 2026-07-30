package com.storyforge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.reflect.Method;
import java.util.Arrays;

import com.storyforge.common.config.WorkflowProperties;
import com.storyforge.common.config.WorkflowStreamConfig;
import com.storyforge.task.consumer.WorkflowEventStreamListener;
import com.storyforge.task.consumer.WorkflowPendingEventReclaimer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

class WorkflowRedisSchedulingContextTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingTestConfiguration.class)
            .withPropertyValues(
                    "app.workflow.redis-enabled=true",
                    "app.workflow.request-stream=story:workflow:requests",
                    "app.workflow.event-stream=story:workflow:events",
                    "app.workflow.event-group=storyforge-backend",
                    "app.workflow.event-consumer=startup-test",
                    "app.workflow.poll-timeout=2s",
                    "app.workflow.batch-size=10",
                    "app.workflow.reclaim-idle=60s",
                    "app.workflow.reclaim-interval-ms=60000",
                    "app.workflow.reclaim-batch-size=20"
            );

    @Test
    void redisEnabledContextAcceptsNumericReclaimSchedule() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(WorkflowPendingEventReclaimer.class);
            assertThat(context.getBean(WorkflowProperties.class).reclaimIntervalMs())
                    .isEqualTo(60_000L);
        });
    }

    @Test
    void streamListenerContainerStartsWhenItsBeanIsInitialized() {
        Method containerFactory = Arrays.stream(WorkflowStreamConfig.class.getDeclaredMethods())
                .filter(method -> method.getName().equals("workflowEventContainer"))
                .findFirst()
                .orElseThrow();

        assertThat(containerFactory.getAnnotation(Bean.class).initMethod()).isEqualTo("start");
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @EnableConfigurationProperties(WorkflowProperties.class)
    @Import(WorkflowPendingEventReclaimer.class)
    static class SchedulingTestConfiguration {

        @Bean
        StringRedisTemplate redisTemplate() {
            return mock(StringRedisTemplate.class);
        }

        @Bean
        WorkflowEventStreamListener workflowEventStreamListener() {
            return mock(WorkflowEventStreamListener.class);
        }
    }
}
