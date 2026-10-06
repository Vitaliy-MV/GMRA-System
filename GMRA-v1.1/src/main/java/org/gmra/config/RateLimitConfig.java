package org.gmra.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.gmra.exception.RateLimitException;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.concurrent.TimeUnit;

@Configuration
public class RateLimitConfig implements WebMvcConfigurer {

    // IP and number of requests for the last minute
    private final Cache<String, Integer> requestCounts = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.SECONDS) 
            .build();

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Apply protection only to API endpoints
        registry.addInterceptor(new RateLimitInterceptor())
                .addPathPatterns("/api/**"); 
    }

    private class RateLimitInterceptor implements HandlerInterceptor {
        private static final int MAX_REQUESTS_PER_SECONDS = 5;

        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
            String clientIp = getClientIp(request);
            Integer requests = requestCounts.get(clientIp, k -> 0);
            if (requests != null && requests >= MAX_REQUESTS_PER_SECONDS) {
                throw new RateLimitException("Err 429. Request limit 5 per second.");
            }
            requestCounts.put(clientIp, requests == null ? 1 : requests + 1);
            return true;
        }

        private String getClientIp(HttpServletRequest request) {
            String ipAddress = request.getHeader("X-Forwarded-For");
            if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress))
                ipAddress = request.getHeader("Proxy-Client-IP");
            if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress))
                ipAddress = request.getHeader("WL-Proxy-Client-IP");
            if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress))
                ipAddress = request.getRemoteAddr();
            if (ipAddress != null && ipAddress.contains(",")) {
                return ipAddress.split(",")[0].trim();
            }
            return ipAddress;
        }
    }
}