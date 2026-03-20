package com.openclaw.config.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class RabbitMQConfig {

    @Bean
    public MessageConverter rabbitMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public DirectExchange chatExchange(@Value("${openclaw.rabbitmq.chat-exchange:chat.exchange}") String exchangeName) {
        return new DirectExchange(exchangeName, true, false);
    }

    @Bean
    public Queue chatRequestQueue(@Value("${openclaw.rabbitmq.chat-request-queue:chat.request.queue}") String queueName,
                                  @Value("${openclaw.rabbitmq.chat-exchange:chat.exchange}") String exchangeName,
                                  @Value("${openclaw.rabbitmq.dead-letter-routing-key:chat.dead}") String deadLetterRoutingKey) {
        return new Queue(queueName, true, false, false, Map.of(
                "x-dead-letter-exchange", exchangeName,
                "x-dead-letter-routing-key", deadLetterRoutingKey
        ));
    }

    @Bean
    public Queue chatResponseQueue(@Value("${openclaw.rabbitmq.chat-response-queue:chat.response.queue}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    public Queue chatDeadLetterQueue(@Value("${openclaw.rabbitmq.chat-dead-letter-queue:chat.dead-letter.queue}") String queueName) {
        return new Queue(queueName, true);
    }

    @Bean
    public Binding requestBinding(Queue chatRequestQueue,
                                  DirectExchange chatExchange,
                                  @Value("${openclaw.rabbitmq.request-routing-key:chat.request}") String routingKey) {
        return BindingBuilder.bind(chatRequestQueue).to(chatExchange).with(routingKey);
    }

    @Bean
    public Binding responseBinding(Queue chatResponseQueue,
                                   DirectExchange chatExchange,
                                   @Value("${openclaw.rabbitmq.response-routing-key:chat.response}") String routingKey) {
        return BindingBuilder.bind(chatResponseQueue).to(chatExchange).with(routingKey);
    }

    @Bean
    public Binding deadLetterBinding(Queue chatDeadLetterQueue,
                                     DirectExchange chatExchange,
                                     @Value("${openclaw.rabbitmq.dead-letter-routing-key:chat.dead}") String routingKey) {
        return BindingBuilder.bind(chatDeadLetterQueue).to(chatExchange).with(routingKey);
    }
}
