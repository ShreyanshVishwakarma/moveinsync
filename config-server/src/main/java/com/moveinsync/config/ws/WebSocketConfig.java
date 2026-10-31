package com.moveinsync.config.ws;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Registers the delta push endpoint and the single-threaded broadcast executor.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ConfigWebSocketHandler handler;
    private final String path;

    public WebSocketConfig(ConfigWebSocketHandler handler,
                           @Value("${moveinsync.config.websocket.path:/ws/config}") String path) {
        this.handler = handler;
        this.path = path;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, path)
                // Tighten for production; kept open so the demo SDK can connect from a browser.
                .setAllowedOriginPatterns("*");
    }

    @Bean(name = "broadcastExecutor")
    public ThreadPoolTaskExecutor broadcastExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(10_000);
        executor.setThreadNamePrefix("config-broadcast-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
