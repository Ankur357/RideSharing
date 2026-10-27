package com.ridesharing.apigateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    /**
     * Key resolver — rate limit by authenticated user ID (from JWT header injected by JwtAuthFilter).
     * Falls back to IP address for unauthenticated requests (e.g. /register, /login).
     *
     * This means each user gets their own 10 req/s bucket — one abusive user
     * can't starve other users by exhausting a shared limit.
     */
    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            // Use X-User-Id header (injected by JwtAuthFilter after JWT validation)
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isEmpty()) {
                return Mono.just("user:" + userId);
            }
            // Fallback to IP for unauthenticated endpoints
            String ip = exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress()
                : "unknown";
            return Mono.just("ip:" + ip);
        };
    }

    /**
     * Default rate limiter:
     * - replenishRate: 10 requests/second (sustained rate)
     * - burstCapacity: 20 requests (allows short bursts, e.g. app startup)
     * - requestedTokens: 1 token per request
     *
     * When the bucket is empty, requests get HTTP 429 Too Many Requests.
     */
    @Bean
    public RedisRateLimiter defaultRateLimiter() {
        return new RedisRateLimiter(10, 20, 1);
    }

    /**
     * Stricter limiter for auth endpoints (/register, /login) to prevent brute-force attacks.
     * - replenishRate: 3 requests/second
     * - burstCapacity: 5 requests
     */
    @Bean
    public RedisRateLimiter authRateLimiter() {
        return new RedisRateLimiter(3, 5, 1);
    }
}
