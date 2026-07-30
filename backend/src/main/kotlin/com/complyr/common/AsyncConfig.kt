package com.complyr.common

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

/**
 * Async infrastructure. Currently a single small executor dedicated to best-effort auth
 * email delivery — deliberately separate from any request-serving pool so a slow SMTP
 * relay can only ever back up this queue, never HTTP threads.
 */
@Configuration
@EnableAsync
class AsyncConfig {
    @Bean(AUTH_EMAIL_EXECUTOR)
    fun authEmailExecutor(): ThreadPoolTaskExecutor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 1
            maxPoolSize = 2
            queueCapacity = EMAIL_QUEUE_CAPACITY
            setThreadNamePrefix("auth-mail-")
        }

    companion object {
        const val AUTH_EMAIL_EXECUTOR = "authEmailExecutor"
        private const val EMAIL_QUEUE_CAPACITY = 200
    }
}
