package eu.cookiekeeper.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Async infrastructure. A single small executor dedicated to best-effort transactional email
 * delivery (auth + billing) — deliberately separate from any request-serving pool so a slow
 * mail provider can only ever back up this queue, never HTTP threads.
 */
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean(EMAIL_EXECUTOR)
    fun emailExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 2
            queueCapacity = EMAIL_QUEUE_CAPACITY
            setThreadNamePrefix("mail-")
        }

    companion object {
        const val EMAIL_EXECUTOR = "emailExecutor"
        private const val EMAIL_QUEUE_CAPACITY = 200
    }
}
