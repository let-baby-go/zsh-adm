# PostgreSQL 新开发部署

通过enterprise-postgres显式启用，提供JDBC_URL/JDBC_USER/JDBC_PASSWORD。该Profile关闭自动SQL初始化与演示简单登录，缺数据库配置时启动失败。

由维护账号配置PGHOST/PGPORT/PGDATABASE/PGUSER及受限PGPASSFILE后执行python3 scripts/migrate-postgres.py。当前迁移链只用于新开发验收库，包括组织/协作、成员授权/KV/原生日志、V006多模型与transport存储及V007独立Desktop正文/回执/墓碑；不导入旧订单、旧Runtime凭据或旧模型配置。已有无版本库不会被自动接管，当前代码也不负责迁移旧测试数据。

版本号V004、V005不再使用：它们原为服务端成员运行时的V004__member_storage_units.sql和V005__member_sessions.sql，随该运行时退役（提交2459c9b）一并删除。迁移脚本按文件名顺序执行，缺号不影响新库；曾执行过V004/V005的旧库会在workdsh_schema_history中保留这两条记录及对应表，不会被自动清理。V008增加列表分页索引；后续新增迁移从V009开始，不复用V004/V005。

迁移链与schema.sql、schema-mysql.sql、schema-sqlite.sql的结构（表、列、类型、可空、主键、唯一约束、外键、二级索引）由SchemaStructureTest校验，任一份脚本单独改动都会使测试失败。

维护步骤创建独立workdsh_app角色（NOSUPERUSER、NOCREATEDB、NOCREATEROLE），使用受控secret设置密码，再执行runtime-grants.sql。应用只获得现有业务表SELECT/INSERT/UPDATE/DELETE和序列使用权限，不能修改维护拥有的版本证据或创建schema。配置及密钥放包外，不写入源码、浏览器或启动参数。

跨机器连接使用jdbc:postgresql://<certificate-host>:5432/<database>?sslmode=verify-full&sslrootcert=/run/secrets/postgres-ca.crt，证书SAN和主机名一致。数据库服务器证书、访问规则、备份与恢复由目标部署配置；本轮H2 PostgreSQL模式迁移测试不代替真实PostgreSQL、最小运行角色和TLS验收。
