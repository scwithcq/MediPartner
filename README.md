# MediPartner 医伴（后端）

基于 Spring Boot 3 的陪诊服务平台后端，采用模块化单体架构。

## 技术栈

Java 17、Spring Boot 3.5、Spring Security + JWT、MyBatis-Plus、MySQL 8、Flyway、Spring StateMachine。

## 本地启动

1. 启动依赖中间件：

```bash
docker compose up -d
```

2. 启动应用：

```bash
mvn spring-boot:run
```

应用默认跑在 `http://localhost:8080`，接口文档见 `http://localhost:8080/swagger-ui.html`。

## 开发环境登录（模拟微信登录）

当前阶段未接入真实微信，`/api/auth/wechat/login` 用 `code` 直接映射到一个模拟 openid，便于本地调试：

| code | 角色 | 说明 |
| --- | --- | --- |
| `admin` | 管理员 | 种子数据 |
| `user1` | 求助者 | 种子数据，余额 500 |
| `worker1` | 陪诊师 | 种子数据，信用分 100 |

登录返回的 `token` 放入请求头 `Authorization: Bearer <token>`。

## 模块划分

- `common` 通用横切能力（统一响应、异常、安全、配置）
- `module.user` 用户与角色
- `module.order` 订单、状态机、抢单
- `module.wallet` 钱包账户与资金流水
- `infra` 基础设施（消息队列、对象存储、定时任务，后续阶段接入）

更多约定见 `docs/后端核心开发手册.md`。
