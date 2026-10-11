package com.dsc.medipartner.infra.mq;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.MessageRecoverer;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 拓扑与可靠性配置：{@code medi.mq.type=rabbit} 时生效。
 *
 * <p><b>阶段 5 新增：死信队列 + 消费重试</b>（契约 §7.2 末条）。
 *
 * <p>改造前的两个问题：
 * <ol>
 *   <li><b>失败即丢</b>：消费抛异常时消息直接丢失且无痕迹。虽然业务层会把
 *       {@code doc.status}/{@code ai_report.status} 落成 FAILED 供人工重投，
 *       但「消息去哪了」在排障时无从查证。</li>
 *   <li><b>重试靠人工</b>：瞬时故障（如模型服务抖动）本可自动重试 2 次就恢复，
 *       却要等到人工发现 FAILED 记录。</li>
 * </ol>
 *
 * <p>现在的链路：正常队列 → （消费失败按策略重试）→ 仍失败则路由到死信队列
 * {@code *.dlq} 留存。<b>死信队列只存不自动消费</b>——积压本身就是告警信号，
 * 由运维在管理台查看并决定重投，避免「死信被自动重试又把系统打挂」。
 *
 * <p>⚠ 消费端保持<b>不重回原队列</b>（{@code defaultRequeueRejected=false}）：
 * 原实现刻意如此，因为失败已在业务侧落库，重回队列会让毒消息无限循环
 * 并阻塞后面所有正常消息。
 */
@Configuration
@ConditionalOnProperty(name = "medi.mq.type", havingValue = "rabbit")
public class RabbitMqConfig {

    public static final String EXCHANGE = "medi.knowledge.exchange";
    public static final String QUEUE = "medi.knowledge.parse.queue";
    public static final String ROUTING_KEY = "doc.parse";
    public static final String REPORT_QUEUE = "medi.ai.report.queue";
    public static final String REPORT_ROUTING_KEY = "report.generate";

    /** 死信交换机与队列（阶段 5） */
    public static final String DLX_EXCHANGE = "medi.dlx.exchange";
    public static final String PARSE_DLQ = "medi.knowledge.parse.dlq";
    public static final String REPORT_DLQ = "medi.ai.report.dlq";
    public static final String PARSE_DLQ_ROUTING_KEY = "doc.parse.dlq";
    public static final String REPORT_DLQ_ROUTING_KEY = "report.generate.dlq";

    /** 消费失败自动重试次数（不含首次） */
    private static final int CONSUMER_MAX_ATTEMPTS = 3;
    /** 重试间隔（毫秒）：给下游瞬时故障留出恢复时间，又不至于让消息积压太久 */
    private static final long CONSUMER_RETRY_INTERVAL_MS = 2000L;

    // ---------------- 业务拓扑 ----------------

    @Bean
    public TopicExchange knowledgeExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    /**
     * 文档解析队列：绑定死信交换机。
     *
     * <p>失败消息被拒（reject）后由 broker 自动路由到 {@link #DLX_EXCHANGE}，
     * 无需消费端手工转发——手工转发一旦忘记，消息就真的丢了。
     */
    @Bean
    public Queue knowledgeParseQueue() {
        return QueueBuilder.durable(QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(PARSE_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding knowledgeParseBinding() {
        return BindingBuilder.bind(knowledgeParseQueue()).to(knowledgeExchange()).with(ROUTING_KEY);
    }

    @Bean
    public Queue aiReportQueue() {
        return QueueBuilder.durable(REPORT_QUEUE)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(REPORT_DLQ_ROUTING_KEY)
                .build();
    }

    @Bean
    public Binding aiReportBinding() {
        return BindingBuilder.bind(aiReportQueue()).to(knowledgeExchange()).with(REPORT_ROUTING_KEY);
    }

    // ---------------- 死信拓扑 ----------------

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue parseDeadLetterQueue() {
        return QueueBuilder.durable(PARSE_DLQ).build();
    }

    @Bean
    public Binding parseDeadLetterBinding() {
        return BindingBuilder.bind(parseDeadLetterQueue())
                .to(deadLetterExchange()).with(PARSE_DLQ_ROUTING_KEY);
    }

    @Bean
    public Queue reportDeadLetterQueue() {
        return QueueBuilder.durable(REPORT_DLQ).build();
    }

    @Bean
    public Binding reportDeadLetterBinding() {
        return BindingBuilder.bind(reportDeadLetterQueue())
                .to(deadLetterExchange()).with(REPORT_DLQ_ROUTING_KEY);
    }

    // ---------------- 消息转换与重试策略 ----------------

    @Bean
    public MessageConverter jacksonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 消费失败重试耗尽后的处理：拒绝且不重回队列。
     *
     * <p>不重回队列的原因：作业层已把失败落库（{@code doc.status=FAILED} /
     * {@code ai_report.status=FAILED} + {@code retry_count}），
     * 重回队列只会让毒消息无限循环并阻塞后续消息；
     * 拒绝后 broker 会依据队列的 DLX 配置把它送进死信队列留存。
     */
    @Bean
    public MessageRecoverer messageRecoverer() {
        return new RejectAndDontRequeueRecoverer();
    }

    /**
     * 监听容器工厂：开启有状态重试（瞬时故障自愈）并接入死信恢复器。
     *
     * <p>用 Spring Boot 的 {@link SimpleRabbitListenerContainerFactoryConfigurer}
     * 定制默认工厂，而不是手工 new 一个——手工构造会丢掉 Boot 的连接池、
     * 并发与序列化等既有默认配置，容易出现「生产环境并发数变成默认 1」这类问题。
     *
     * <p><b>为什么用无状态重试</b>：重试在<b>同一次投递内</b>完成，次数与退避
     * （2s 起、指数放大、上限 8s）完全可控，重试耗尽后由 {@code recoverer} 拒绝消息、
     * 再由 broker 依据队列的 DLX 配置投入死信队列。
     *
     * <p>有状态重试（{@code stateful}）会把消息放回队列稍后再投，虽然更省消费者线程，
     * 但它依赖消息头里的重试计数与 {@code MessageKeyGenerator}，
     * 在「同一 docId 被人工重投」时可能出现计数串味。本项目单条消息处理耗时可控
     * （几十秒级），无状态重试的语义更直白、更易排查。
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter jacksonMessageConverter) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);

        factory.setMessageConverter(jacksonMessageConverter);
        // 失败不重回原队列（理由见 messageRecoverer 注释）
        factory.setDefaultRequeueRejected(false);
        // 有状态重试：重试间隔与最大次数走 Spring Boot 的
        // spring.rabbitmq.listener.simple.retry.* 配置，便于运维按环境调整
        factory.setAdviceChain(org.springframework.amqp.rabbit.config.RetryInterceptorBuilder
                .stateless()
                .maxAttempts(CONSUMER_MAX_ATTEMPTS)
                .backOffOptions(CONSUMER_RETRY_INTERVAL_MS, 2.0, CONSUMER_RETRY_INTERVAL_MS * 4)
                .recoverer(messageRecoverer())
                .build());
        return factory;
    }

    /**
     * 队列信息汇总，供启动日志与运维排查使用。
     *
     * <p>集中定义队列名可避免「文档写了一个名字、代码里是另一个」——
     * 上线后对着管理台找不到队列，是最常见的低级排障耗时点。
     */
    public static String topologySummary() {
        return String.join("; ",
                "exchange=" + EXCHANGE,
                "parseQueue=" + QUEUE,
                "reportQueue=" + REPORT_QUEUE,
                "dlx=" + DLX_EXCHANGE,
                "parseDlq=" + PARSE_DLQ,
                "reportDlq=" + REPORT_DLQ);
    }
}
