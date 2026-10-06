package org.gmra.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WebClientConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(15000);

        return builder
                .requestFactory(factory)
                .defaultHeader("Accept", "application/json, text/plain, text/html, */*")
                .defaultHeader("User-Agent", "GMRA-System/1.0 (Global Seismic Monitoring and Risk Assessment System)")
                .build();
    }
}