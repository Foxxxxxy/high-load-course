package ru.quipy.payments.config

import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory
import org.springframework.boot.web.embedded.jetty.JettyServerCustomizer
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.annotation.PostConstruct
import org.slf4j.LoggerFactory

@Configuration
class JettyConfig {

    private val logger = LoggerFactory.getLogger(JettyConfig::class.java)

    @Bean
    fun jettyCustomizer(): JettyServletWebServerFactory {
        val factory = JettyServletWebServerFactory()

        val connectionCustomizer = JettyServerCustomizer {
            (it.connectors[0].getConnectionFactory("h2c") as HTTP2CServerConnectionFactory).maxConcurrentStreams = 10_000_000;
        }

        factory.serverCustomizers.add(connectionCustomizer)

        return factory
    }

    @PostConstruct
    fun init() {
        logger.info("Jetty confing bean initialized")
    }
}