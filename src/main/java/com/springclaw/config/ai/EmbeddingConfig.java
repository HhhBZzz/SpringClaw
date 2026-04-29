package com.springclaw.config.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.redis.RedisVectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.time.Duration;

/**
 * 独立 embedding / vector store 配置。
 *
 * 设计说明：
 * 1. 不依赖 Spring AI 自动推断 embedding provider，避免和聊天 provider 配置耦合。
 * 2. 向量记忆显式走 Redis Stack，metadata 字段固定下来，保证 filterExpression 可用。
 */
@Configuration
@ConditionalOnProperty(prefix = "springclaw.embedding", name = "enabled", havingValue = "true")
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);

    @Bean
    @ConditionalOnMissingBean(EmbeddingModel.class)
    public EmbeddingModel openClawEmbeddingModel(SpringClawEmbeddingProperties properties,
                                                 ObjectProvider<RestClient.Builder> restClientBuilderProvider) {
        if (!StringUtils.hasText(properties.getApiKey())) {
            throw new IllegalStateException("Embedding 已启用，但未配置 SPRINGCLAW_EMBEDDING_API_KEY");
        }

        OpenAiApi.Builder apiBuilder = OpenAiApi.builder()
                .baseUrl(properties.getBaseUrl())
                .embeddingsPath(properties.getEmbeddingsPath())
                .apiKey(properties.getApiKey());

        RestClient.Builder restClientBuilder = restClientBuilderProvider.getIfAvailable();
        if (restClientBuilder != null) {
            apiBuilder.restClientBuilder(restClientBuilder);
        }

        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model(properties.getModel())
                .build();
        options.setDimensions(properties.getDimensions());

        log.info("Embedding 已启用: baseUrl={}, path={}, model={}, dimensions={}",
                properties.getBaseUrl(), properties.getEmbeddingsPath(),
                properties.getModel(), properties.getDimensions());
        return new OpenAiEmbeddingModel(apiBuilder.build(), MetadataMode.NONE, options);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(JedisPooled.class)
    public JedisPooled openClawEmbeddingJedisPooled(RedisProperties redisProperties) {
        DefaultJedisClientConfig.Builder clientConfig = DefaultJedisClientConfig.builder()
                .database(redisProperties.getDatabase())
                .ssl(redisProperties.getSsl() != null && redisProperties.getSsl().isEnabled());

        Duration timeout = redisProperties.getTimeout();
        if (timeout != null && !timeout.isNegative() && !timeout.isZero()) {
            int timeoutMillis = (int) timeout.toMillis();
            clientConfig.connectionTimeoutMillis(timeoutMillis);
            clientConfig.socketTimeoutMillis(timeoutMillis);
        }
        if (StringUtils.hasText(redisProperties.getUsername())) {
            clientConfig.user(redisProperties.getUsername());
        }
        if (StringUtils.hasText(redisProperties.getPassword())) {
            clientConfig.password(redisProperties.getPassword());
        }

        return new JedisPooled(
                new HostAndPort(redisProperties.getHost(), redisProperties.getPort()),
                clientConfig.build()
        );
    }

    @Bean
    @ConditionalOnMissingBean(VectorStore.class)
    public VectorStore openClawRedisVectorStore(EmbeddingModel embeddingModel,
                                                JedisPooled jedisPooled,
                                                @org.springframework.beans.factory.annotation.Value("${spring.ai.vectorstore.redis.index-name:springclaw-memory}") String indexName,
                                                @org.springframework.beans.factory.annotation.Value("${spring.ai.vectorstore.redis.prefix:springclaw:}") String prefix,
                                                @org.springframework.beans.factory.annotation.Value("${spring.ai.vectorstore.redis.initialize-schema:false}") boolean initializeSchema) {
        RedisVectorStore vectorStore = RedisVectorStore.builder(jedisPooled, embeddingModel)
                .indexName(indexName)
                .prefix(prefix)
                .initializeSchema(initializeSchema)
                .metadataFields(
                        RedisVectorStore.MetadataField.tag("sessionKey"),
                        RedisVectorStore.MetadataField.tag("channel"),
                        RedisVectorStore.MetadataField.tag("userId"),
                        RedisVectorStore.MetadataField.tag("role"),
                        RedisVectorStore.MetadataField.numeric("timestamp")
                )
                .build();

        log.info("Redis 向量记忆已启用: indexName={}, prefix={}, initializeSchema={}",
                indexName, prefix, initializeSchema);
        return vectorStore;
    }
}
