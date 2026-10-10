package com.dsc.medipartner;

import com.dsc.medipartner.common.security.SecurityConfig;
import com.dsc.medipartner.module.dict.controller.CommonDictController;
import com.dsc.medipartner.module.order.controller.OrderController;
import com.dsc.medipartner.module.order.controller.WorkerOrderController;
import com.dsc.medipartner.module.order.service.OrderService;
import com.dsc.medipartner.module.order.service.PriceService;
import com.dsc.medipartner.module.user.controller.UserController;
import com.dsc.medipartner.module.user.controller.WorkerProfileController;
import com.dsc.medipartner.module.wallet.controller.WalletController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 应用启动冒烟测试（事项 3C）。
 *
 * <p><b>为什么必须有这个测试</b>：2026-10-10 真机联调时发现
 * {@code OrderServiceImpl <-> ReviewServiceImpl} 形成构造器循环依赖，
 * 应用<b>完全无法启动</b>——而当时 279 个 Mockito 纯单测<b>全部通过</b>。
 * 原因是单测都手工 {@code new} 对象，从不经过 Spring 容器，装配问题在测试阶段完全不可见。
 *
 * <p>本测试用 {@code @SpringBootTest} 真正加载整个上下文（含 Security、MyBatis-Plus、
 * 所有 @Service/@Component 的依赖注入），任何循环依赖、缺失 Bean、AOP 代理错误都会在此暴露。
 *
 * <p>关键设计：<b>不依赖数据库可用性</b>。上下文加载时 HikariCP 是惰性建连，
 * 且本测试不发起任何 SQL，因此即使 MySQL 未启动也能跑过——它只验证"装配是否正确"。
 * 需要真实 SQL 的验证见 {@code OrderFlowIntegrationTest}（用 {@code integration} profile 单独跑）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class ApplicationContextSmokeTest {

    @Autowired
    private ApplicationContext context;

    /** 上下文能加载，说明没有循环依赖、没有缺失 Bean */
    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }

    /** 容器里存在预期的启动类 Bean */
    @Test
    void applicationBeanIsRegistered() {
        assertThat(context.getBean(MediPartnerApplication.class)).isNotNull();
    }

    /**
     * 关键 Service 都能被解析。
     * 这些 Bean 的构造器串起了 Order/Review/User/Worker/Wallet/Dict 六个模块，
     * 是循环依赖最容易出现的位置。
     */
    @Test
    void coreServicesAreResolvable() {
        assertThat(context.getBean(OrderService.class)).isNotNull();
        assertThat(context.getBean(PriceService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.wallet.service.WalletService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.user.service.UserService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.user.service.WorkerService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.review.service.ReviewService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.dict.service.DictService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.dict.service.ClientConfigService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.order.service.OrderStatusLogService.class)).isNotNull();
    }

    /** 所有 Controller 都能装配（它们持有 Service，是装配链的最末端） */
    @Test
    void allControllersAreWired() {
        assertThat(context.getBean(OrderController.class)).isNotNull();
        assertThat(context.getBean(WorkerOrderController.class)).isNotNull();
        assertThat(context.getBean(UserController.class)).isNotNull();
        assertThat(context.getBean(WorkerProfileController.class)).isNotNull();
        assertThat(context.getBean(WalletController.class)).isNotNull();
        assertThat(context.getBean(CommonDictController.class)).isNotNull();
    }

    /** 安全配置生效：过滤器链 Bean 存在 */
    @Test
    void securityConfigIsPresent() {
        assertThat(context.getBean(SecurityConfig.class)).isNotNull();
        assertThat(context.getBean(org.springframework.security.web.SecurityFilterChain.class)).isNotNull();
        assertThat(context.getBean(org.springframework.security.crypto.password.PasswordEncoder.class)).isNotNull();
    }

    /** 状态机工厂可用（订单状态机是本项目的核心组件之一） */
    @Test
    void stateMachineFactoryIsPresent() {
        assertThat(context.getBean(org.springframework.statemachine.config.StateMachineFactory.class)).isNotNull();
    }

    /**
     * 附录 G.3 的基础组件都在容器里。
     * 这些是阶段 1.5 新增能力，漏注册会导致接口 500 而非启动失败，故显式断言。
     */
    @Test
    void phase15ComponentsAreRegistered() {
        assertThat(context.getBean(com.dsc.medipartner.module.order.service.ActionResolver.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.order.service.OrderAssembler.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.order.service.VirtualPhoneService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.order.service.OrderStatusLogService.class)).isNotNull();
    }

    /** AI 降级链路的关键 Bean（无 Key 时必须仍可注入，不能因缺 Key 而启动失败） */
    @Test
    void aiComponentsAreRegisteredWithoutApiKey() {
        assertThat(context.getBean(com.dsc.medipartner.module.ai.ChatModelFactory.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.ai.service.AiService.class)).isNotNull();
        assertThat(context.getBean(com.dsc.medipartner.module.ai.service.ReportService.class)).isNotNull();
    }
}
