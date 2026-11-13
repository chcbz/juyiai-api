# JuyiAI API — 公开源码配置

本仓由当前后端源码脱敏导入，保留模块结构与 Java 包名。开发分支为 `develop`。
根仓：[chcbz/juyiai](https://github.com/chcbz/juyiai)。

- 原有加密服务凭证改为环境变量引用，不附带解密口令或任何生产证书。
- `.env.example` 列出配置中引用的变量；它是清单，不会自动被 Spring 加载。由进程环境、容器配置或私有外置 Spring 配置提供真实值。不要提交填写后的文件。
- 数据库、中间件 URL、账号及 OAuth client 等须按自己的环境配置，不能照搬历史生产说明。
- 默认不启用开发 profile 的 TLS；如需 TLS，提供 `SERVER_SSL_ENABLED`、`SERVER_SSL_KEY_STORE` 和 `SERVER_SSL_KEY_STORE_PASSWORD`。生产建议由部署者明确配置入口 TLS。
- 示例支付代码从 `STRIPE_API_KEY` 环境变量读取密钥；测试数据中的服务凭证已替换为虚构值。
- Gradle 使用现有 `cn.jia.jia-dependency-management` 插件和版本目录，公开环境依赖可用性尚未完成验证。迁移不代表已经可以独立一键构建。
- 在集成工作区执行 Gradle 时遵循根仓 `AGENTS.md` 与 `ops/orchestration/README.md`，不在生产安装目录编译。

此次没有部署或修改正在运行的服务配置。升级生产之前应另行准备外置配置、测试与受控发布。

好汉模型与差异化托管价格采用[数据库配置](docs/hero-model-hosting-configuration.md)，108人初始化候选在`docs/examples/hero-hosting.sql`。需在获授权的目标库显式维护，不自动加载/迁移/启用收费；成本和Runtime实际模型可交付性须在启用前核验。
