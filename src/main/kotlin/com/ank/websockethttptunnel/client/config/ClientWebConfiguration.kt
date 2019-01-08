package com.ank.websockethttptunnel.client.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers

@Configuration
@ConditionalOnProperty(name = ["tunnel.client.enabled"], havingValue = "true")
class ClientWebConfiguration {

    @Bean
    fun clientRegistrationElasticScheduler(): Scheduler {
        return Schedulers.newElastic("client-registration-elastic-scheduler", 60)
    }

    @Bean
    fun clientPingElasticScheduler(): Scheduler {
        return Schedulers.newElastic("client-ping-elastic-scheduler", 60)
    }

    @Bean
    fun clientRequestElasticScheduler(): Scheduler {
        return Schedulers.newElastic("client-request-elastic-scheduler", 300)
    }
}