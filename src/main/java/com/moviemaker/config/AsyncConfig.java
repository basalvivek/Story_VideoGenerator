package com.moviemaker.config;

import java.util.concurrent.Executor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {
    @Bean(name = "generationExecutor")
    public Executor generationExecutor(AppProperties props) {
        ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
        int n = Math.max(1, props.generation().threads());
        ex.setCorePoolSize(n);
        ex.setMaxPoolSize(n);
        ex.setQueueCapacity(50);
        ex.setThreadNamePrefix("video-gen-");
        ex.initialize();
        return ex;
    }
}
