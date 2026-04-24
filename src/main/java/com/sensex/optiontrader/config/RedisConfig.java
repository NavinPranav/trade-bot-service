package com.sensex.optiontrader.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sensex.optiontrader.model.dto.response.PredictionResponse;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.*;
import java.time.Duration;

@Configuration
public class RedisConfig {

    /**
     * Same shape as before: Spring Boot {@link ObjectMapper}, no Jackson default typing.
     * Used for {@link RedisTemplate} and for {@code marketData} / {@code optionsChain} caches so
     * {@code List<Map<String,Object>>} and maps stay plain for ML.
     */
    @Bean
    @Qualifier("redisCacheValueSerializer")
    public GenericJackson2JsonRedisSerializer redisCacheValueSerializer(ObjectMapper objectMapper) {
        return new GenericJackson2JsonRedisSerializer(objectMapper);
    }

    /**
     * Only for the {@code predictions} cache: typed serializer so round-trips always yield
     * {@link PredictionResponse}. Default-typing {@link GenericJackson2JsonRedisSerializer} was
     * brittle (500 on cache hit after tab switches) when combined with this DTO's shape.
     */
    @Bean
    @Qualifier("predictionCacheValueSerializer")
    public Jackson2JsonRedisSerializer<PredictionResponse> predictionCacheValueSerializer(ObjectMapper objectMapper) {
        return new Jackson2JsonRedisSerializer<>(objectMapper, PredictionResponse.class);
    }

    @Bean
    public RedisTemplate<String, Object> redisTemplate(
            RedisConnectionFactory f,
            @Qualifier("redisCacheValueSerializer") GenericJackson2JsonRedisSerializer redisCacheValueSerializer) {
        var t = new RedisTemplate<String, Object>();
        t.setConnectionFactory(f);
        t.setKeySerializer(new StringRedisSerializer());
        t.setValueSerializer(redisCacheValueSerializer);
        t.setHashKeySerializer(new StringRedisSerializer());
        t.setHashValueSerializer(redisCacheValueSerializer);
        return t;
    }

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory f,
            @Qualifier("redisCacheValueSerializer") GenericJackson2JsonRedisSerializer redisCacheValueSerializer,
            @Qualifier("predictionCacheValueSerializer") Jackson2JsonRedisSerializer<PredictionResponse> predictionCacheValueSerializer) {
        var defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(redisCacheValueSerializer));
        var predictionsConfig = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(5))
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(predictionCacheValueSerializer));
        return RedisCacheManager.builder(f)
                .cacheDefaults(defaultConfig)
                .withCacheConfiguration("predictions", predictionsConfig)
                .withCacheConfiguration("optionsChain", defaultConfig.entryTtl(Duration.ofMinutes(3)))
                .withCacheConfiguration("marketData", defaultConfig.entryTtl(Duration.ofMinutes(15)))
                .build();
    }

    @Bean
    public RedisMessageListenerContainer redisListenerContainer(RedisConnectionFactory f) {
        var c = new RedisMessageListenerContainer();
        c.setConnectionFactory(f);
        return c;
    }
}
