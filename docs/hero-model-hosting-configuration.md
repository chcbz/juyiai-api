# 好汉模型与托管价格数据库配置

2026-11-13按用户最新决定：**数据库是唯一模型绑定/模型托管价格配置来源**，取代前一外置properties候选。Java和启动配置不再维护108人映射或8档价格；模型/价格变更提交后，下一次读取生效，无需重启或重新构建。

## 表及责任

| 表 | 用途 |
| --- | --- |
| `agent_persona_model` | 每tenant/client/personaCode对应一个modelId，可维护当前绑定 |
| `economy_hosting_model_plan` | 每tenant/client/modelId指向当前平台套餐planId/version，可维护当前价格指针 |
| `economy_hosting_rent_plan`（已有） | 金额、租期、币种的不可变版本，不更新/删除已发布行 |

两个新表有复合主键及同scope外键，无CHECK、无新增触发器。原套餐不可变保护保留。模型ID没有Java名单；系统宋江仍不可个人租用。不存在把目标模型当作执行观察或放宽授权的路径。

使用既有JdbcTemplate存储适配器进行限定tenant/client的SQL连接；模型绑定与价格指针在同一查询读取，不使用启动时缓存。目录和批量Runtime投影每scope一次查询，不逐好汉扫描数据库。单实例详情按其scope读取。Scope来自原可信上下文，不接受请求覆盖。

## 维护与迁移

1. 固定真实目标数据库与scope，按实际授权保存快照/备份；本次没有修改生产库。
2. 显式执行API资源`db/agent-persona-model-hosting.sql`，前提是原hosting-rent表及不可变保护已经安装。**应用启动不自动建表/迁移/初始化数据**。缺表是部署前置条件缺失，不在运行时猜测旧配置或吞掉数据库错误。
3. `docs/examples/hero-hosting.sql`包含108绑定、8个模型价格指针及8个套餐版本的**初始化候选**。在同一连接显式设置`@tenant_id`、`@client_id`后，仅对尚无此配置的作用域执行。没有默认生产scope、没有覆盖UPSERT；不使用mysql --force，遇任一错误ROLLBACK，不提交半套配置。重复执行不是调价入口。
4. 调价时在一个事务中插入未使用的套餐版本，再更新该模型的当前指针。可在UPDATE中带旧版本条件进行并发比较，影响行数必须为1，否则ROLLBACK后重新读取。新版本的planId仍为既有平台套餐类型，币种WEN、amount/period为正整数、status为ACTIVE。提交后新报价读取新版本；原版本和旧引用保留。
5. 换模型时更新该scope的persona→model行，目标模型须已有关联套餐；外键禁止悬空或跨scope引用。回退当前配置只切回已存在的旧指针/绑定，不回滚用户钱包/已确认报价/租约。

既有`agent.hosting-rent.enabled`和原预览scope/收费授权继续生效；自部署金额仍沿原配置不变。统一平台旧金额只供既有维护迁移入口，不作为数据库缺价的兜底。文件`persona-models/model-plans`已删除消费路径，不再是第二套价源；部署时移除旧文件导入。

## 失败边界与历史保护

- 新平台报价数据库无绑定/无价：503/HOSTING_RENT_NOT_CONFIGURED，不兜底统一价，也不创建免费套餐。
- 配置指向非平台套餐、金额/周期/版本非正数、非WEN/非ACTIVE：当前报价409/HOSTING_RENT_PLAN_VERSION_CONFLICT，不预占或扣款。资金规则在报价应用服务负责；FK只保障关系完整性，不复制业务CHECK。
- 配置查询固定选中的版本，再沿原plan→binding→quote锁序读取不可变套餐。并发调价时已开始的报价可固定旧版本，后续请求读取新版本；不把配置指针锁引入旧锁序。
- 旧报价重放、原租约手动/自动续期仍使用原套餐版本及金额，不由当前配置重定价。自部署、技能费、承办费不变。
- DTO中的configuredModel和platformHostingAmountWen/PlanVersion/PeriodSeconds继续是可空字符串，表示数据库目标/新报价参考，不覆盖实际executionModel或已有租约价格。

## 验证与未完成边界

`HostingPersonaModelDatabaseMySqlTest`在隔离MySQL验证实际DDL、108人初始化、数据库改价/换模即时读取、跨scope/悬空引用拒绝和原套餐不可变；`HostingRentApplicationServiceTest`覆盖SETUP/原邀请报价、缺价/错价、自部署和原租约；公共目录投影/交接/迁移定向回归及validateLayering按handoff记录。

这仍不下发Runtime模型。初始化价格保留此前成本比例草案，启用前须核对渠道单价和实际模型可交付性；数据库目标配置不等于实际运行该模型。未部署、未执行生产迁移或扣费，本轮不增加动态管理后台。
