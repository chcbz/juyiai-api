# 好汉模型与托管价格外置配置

2026-11-13候选，未部署。具体模型ID、人物映射与金额不在Java中枚举；本仓`docs/examples/hero-hosting.properties`为108人外置配置候选，不打入运行时默认资源、不自动启用。

## 维护入口

复用Spring已有外部配置。将候选复制到运维管理的应用目录外配置位置，以现有`spring.config.import`引入（与已有导入合并，不覆盖其他配置）。部署者确认模型可用性/实际渠道成本和Runtime模型一致性之后，按原授权流程启用；本次不执行安装或重启。现有机制在启动时读取，**配置更新需要受控重启，但不需要改Java或重新构建JAR**；没有新增热更新或管理后台。

- `agent.hosting-rent.persona-models[角色稳定键]`：目标模型ID，精确匹配。
- `agent.hosting-rent.model-plans[完整模型ID].amount-wen`：每租期平台托管文金额。
- `agent.hosting-rent.model-plans[完整模型ID].plan-version`：已有平台套餐命名空间下的不可变价格版本，各模型互不重复、不得与原统一套餐版本重叠。
- 继续使用既有`period-seconds`、`enabled`、统一租费及自部署配置；外置候选不修改这些设置。当前产品租期为30天，候选按此计算；原统一金额仍需显式提供，用于兼容既有机制，不作为缺失模型映射的兜底。
- Spring Map的带点模型ID必须使用方括号key，如`model-plans[vendor.model]`。新增模型只改配置，无模型品牌/代际白名单。

## 价格变更与资金边界

继续使用原平台planId及不同planVersion，不制造按模型枚举的套餐类型，也不改变平台/自部署的移除与交接识别。修改金额或周期须分配**未使用的新版本**；不可修改历史套餐快照。版本的唯一性范围为原平台套餐命名空间，不是每模型各自从1开始。模型改名/删除不删除已落库版本。

新邀请/重新请回按角色配置选择模型价格；两张映射都为空时保留旧统一定价行为。一旦配置差异化，未映射角色新邀请报503/HOSTING_RENT_NOT_CONFIGURED，不能静默收统一价。配置模型缺价、非正整数、同命名空间重复版本会拒绝该启动配置：风险为错价或污染不可变历史；由Properties责任层校验，不复制到Web/Runtime。已有plan版本与配置的金额/周期冲突仍报原409，不覆盖数据库。

旧报价重放继续使用原plan/version；手动续期从原租约版本读历史套餐，并比对金额/周期，不读取新价重定价；自动续期继续沿原租约。身份/ACL、幂等、事务、技能费、自部署费和承办费不变。维护迁移入口仍只使用旧统一方案，**不批量迁移存量租约到新模型价**。

## 展示与模型执行

目录DTO增加可空字符串`configuredModel`、`platformHostingAmountWen`、`platformHostingPlanVersion`、`platformHostingPeriodSeconds`。均来自外置配置，无配置则空，系统宋江仍不可个人租用。字段展示目标及新邀请参考价，不代表当前租约或实际执行模型；不覆盖私有executionModel。现有Web报价仍消费服务端报价结果，本轮不修改UI。

本切片仅完成外置绑定、价格读取和元数据投影，**不下发Runtime实际模型**。不能凭模型配置宣称按该模型履约，更不能未经模型一致性验收就上线差异化收费。价格示例保留草案值；公开成本来源及渠道单价仍需复核，不是实际采购账单。

## 定向验证

`AgentHostingRentConfigurationTest`：外部绑定、带点未知模型、重复版本及缺价；`HostingRentApplicationServiceTest`：只改配置即变更报价、缺映射不兜底、旧版本续期、原报价/自部署及事务；`AgentPersonaPublicScopeTest`：目标元数据不伪造实际模型。配合原交接/迁移回归和validateLayering。实际结果见根handoff，不把测试数据当生产价格。
