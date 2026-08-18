package com.llmcascade.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.JedisPooled;

@Configuration
public class RedisConfig {

    @Bean
    public JedisPooled jedisPooled(@Value("${redis.url}") String redisUrl) {
        return new JedisPooled(redisUrl);
    }
}

