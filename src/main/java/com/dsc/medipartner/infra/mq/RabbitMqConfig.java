package com.dsc.medipartner.infra.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑：medi.mq.type=rabbit 时生效（docker compose up -d rabbitmq 后切换）。
 */
@Configuration
@ConditionalOnProperty(name = "medi.mq.type", havingValue = "rabbit")
public class RabbitMqConfig {

    public static final String EXCHANGE = "medi.knowledge.exchange";
    public static final String QUEUE = "medi.knowledge.parse.queue";
    public static final String ROUTING_KEY = "doc.parse";

    @Bean
    public TopicExchange knowledgeExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    public Queue knowledgeParseQueue() {
        return new Queue(QUEUE, true);
    }

    @Bean
    public Binding knowledgeParseBinding() {
        return BindingBuilder.bind(knowledgeParseQueue()).to(knowledgeExchange()).with(ROUTING_KEY);
    }

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
