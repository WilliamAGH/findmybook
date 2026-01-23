package com.williamcallahan.book_recommendation_engine.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.lang.NonNull;

/**
 * Configuration for asynchronous request handling in the Spring MVC framework.
 *
 * @author William Callahan
 *
 * Features:
 * - Configures thread pool for handling asynchronous HTTP requests
 * - Sets appropriate timeout values for long-running operations
 * - Optimizes thread usage with bounded queue capacity
 * - Implements custom thread naming for easier debugging
 */
@Configuration
public class AsyncConfig implements WebMvcConfigurer {

    // MVC async request timeout in milliseconds
    private static final long MVC_ASYNC_TIMEOUT_MS = 60_000L;

    // MVC thread pool configuration
    private static final int MVC_CORE_POOL_SIZE = 20;
    private static final int MVC_MAX_POOL_SIZE = 100;
    private static final int MVC_QUEUE_CAPACITY = 500;
    private static final String MVC_THREAD_NAME_PREFIX = "mvc-async-";

    // Default @Async thread pool configuration (for OutboxRelay, RecentBookViewRepository, etc.)
    private static final int ASYNC_CORE_POOL_SIZE = 4;
    private static final int ASYNC_MAX_POOL_SIZE = 16;
    private static final int ASYNC_QUEUE_CAPACITY = 200;
    private static final String ASYNC_THREAD_NAME_PREFIX = "async-";

    // Image processing thread pool configuration
    private static final int IMAGE_PROC_MIN_THREADS = 2;
    private static final int IMAGE_PROC_BURST_MULTIPLIER = 2;
    private static final int IMAGE_PROC_MIN_BURST_THREADS = 4;
    private static final int IMAGE_PROC_QUEUE_CAPACITY = 100;
    private static final String IMAGE_PROC_THREAD_NAME_PREFIX = "image-proc-";

    /**
     * Configures asynchronous request handling for Spring MVC.
     *
     * @param configurer Spring's async support configurer object
     *
     * Features:
     * - Sets default timeout for async requests
     * - Assigns custom task executor with optimized thread pool
     */
    @Override
    public void configureAsyncSupport(@NonNull AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(MVC_ASYNC_TIMEOUT_MS);
        configurer.setTaskExecutor(mvcAsyncTaskExecutor());
    }

    /**
     * Default task executor for {@code @Async} methods that don't specify an executor.
     * Spring's {@code @EnableAsync} infrastructure looks for a bean named "taskExecutor".
     *
     * <p>This provides bounded thread pooling for general async operations like:
     * <ul>
     *   <li>{@code OutboxRelay.relayEvents()} - runs every second</li>
     *   <li>{@code RecentBookViewRepository.recordView()} - runs on every book view</li>
     * </ul>
     *
     * <p>Without this bean, Spring falls back to {@code SimpleAsyncTaskExecutor} which
     * creates unbounded threads without pooling, risking thread exhaustion under load.
     *
     * @return Configured ThreadPoolTaskExecutor for general @Async methods
     */
    @Bean("taskExecutor")
    public ThreadPoolTaskExecutor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(ASYNC_CORE_POOL_SIZE);
        executor.setMaxPoolSize(ASYNC_MAX_POOL_SIZE);
        executor.setQueueCapacity(ASYNC_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(ASYNC_THREAD_NAME_PREFIX);
        executor.initialize();
        return executor;
    }

    /**
     * Creates and configures the thread pool task executor for MVC async processing.
     *
     * <p>This is separate from the default "taskExecutor" to allow different tuning
     * for MVC async requests vs. general @Async methods.
     *
     * @return Configured AsyncTaskExecutor for processing asynchronous HTTP requests
     *
     * Features:
     * - Core pool of {@value #MVC_CORE_POOL_SIZE} threads for handling typical load
     * - Maximum pool of {@value #MVC_MAX_POOL_SIZE} threads for high load periods
     * - Queue capacity of {@value #MVC_QUEUE_CAPACITY} tasks before rejecting new requests
     * - Descriptive thread naming pattern for monitoring
     */
    @Bean("mvcAsyncTaskExecutor")
    public AsyncTaskExecutor mvcAsyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(MVC_CORE_POOL_SIZE);
        executor.setMaxPoolSize(MVC_MAX_POOL_SIZE);
        executor.setQueueCapacity(MVC_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(MVC_THREAD_NAME_PREFIX);
        executor.initialize();
        return executor;
    }

    /**
     * Creates and configures a dedicated thread pool task executor for CPU-intensive image processing.
     *
     * @return Configured AsyncTaskExecutor for image processing tasks.
     *
     * Features:
     * - Core pool size based on available processors (minimum {@value #IMAGE_PROC_MIN_THREADS})
     * - Max pool size allows burst capacity for peak loads
     * - Smaller queue capacity as tasks are expected to be CPU-bound and long-running
     * - Descriptive thread naming pattern for monitoring
     */
    @Bean("imageProcessingExecutor")
    public AsyncTaskExecutor imageProcessingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int processors = Runtime.getRuntime().availableProcessors();
        executor.setCorePoolSize(computeCorePoolSize(processors));
        executor.setMaxPoolSize(computeMaxPoolSize(processors));
        executor.setQueueCapacity(IMAGE_PROC_QUEUE_CAPACITY);
        executor.setThreadNamePrefix(IMAGE_PROC_THREAD_NAME_PREFIX);
        executor.initialize();
        return executor;
    }

    /**
     * Computes core pool size: available processors or minimum, whichever is larger.
     */
    private int computeCorePoolSize(int availableProcessors) {
        return Math.max(availableProcessors, IMAGE_PROC_MIN_THREADS);
    }

    /**
     * Computes max pool size: double the processors or minimum burst threads, whichever is larger.
     */
    private int computeMaxPoolSize(int availableProcessors) {
        int burstCapacity = availableProcessors * IMAGE_PROC_BURST_MULTIPLIER;
        return Math.max(burstCapacity, IMAGE_PROC_MIN_BURST_THREADS);
    }
}
