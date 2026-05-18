package com.qingshanyuluo.baton.config;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(BatonProperties.class)
public class BatonConfiguration {

    @Bean
    public WebClient webClient(BatonProperties properties) {
        Duration connectTimeout = properties.failover().connectTimeout();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeout.toMillis())
                .responseTimeout(properties.failover().readTimeout());
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
