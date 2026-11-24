package org.districompute.app.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Background executors: experiment orchestration never runs on HTTP request threads. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ExecutorConfiguration {

    /**
     * Submits runs and records their outcomes.
     *
     * @return the executor
     */
    @Bean(name = "executionExecutor")
    Executor executionExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("execution-");
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(10_000);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(20);
        executor.initialize();
        return executor;
    }
}
