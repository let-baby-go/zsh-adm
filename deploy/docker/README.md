# Docker 源码职责

`Admin.Dockerfile` 构建 Java 管理服务及管理前端，须显式固定 `JAVA_IMAGE`。`AdminFront.Dockerfile` 与 `admin-loopback-front.mjs` 是可选本机 HTTP 前门；后台保持 HTTPS 证书验证，不能作为公网 TLS 模板。

`AdminBuild.Dockerfile` 是多阶段源码构建配方（Maven 编译 JAR、Node 构建 `web/dist`），供 docker compose 使用，与 `Admin.Dockerfile` 的运行时约定一致；它不替代候选交付的预构建产物镜像。

Agent、工具、文件和本机会话在员工 Desktop 运行。后台部署参数与验证见 [后台交付](../ADMIN-SERVER-CANDIDATE.md)。

## 用 docker compose 启动

默认栈（H2 文件库，单容器，适合本地开发与验收）：

```sh
cp .env.example .env   # 填写管理员账号、两个密钥；改端口时同步改 WORKDSH_ADMIN_WEB_ORIGIN
docker compose up --build -d
```

浏览器访问 `http://127.0.0.1:18890/`。首次启动必须提供 `WORKDSH_BOOTSTRAP_ADMIN_EMAIL` 与至少 12 位的 `WORKDSH_BOOTSTRAP_ADMIN_PASSWORD`；`organizations` 表非空后该引导不再执行。H2 数据保存在命名卷 `workdsh-state`。

PostgreSQL 验收栈（叠加 override）：

```sh
docker compose -f docker-compose.yml -f docker-compose.postgres.yml up --build -d
```

首次初始化由 `deploy/postgres/docker/init-schema.sh` 完成：创建最小权限角色 `workdsh_app`、按 `deploy/postgres/migrations` 应用全部迁移（当前为 V001–V003、V006–V009）并写入 `workdsh_schema_history`（含 SHA256 校验）、执行 `runtime-grants.sql`。应用使用 `workdsh_app` 连接，不使用维护账号，且不自动 DDL。初始化只发生在 `workdsh-pgdata` 卷为空时；使用同样的 `-f` 参数执行 `docker compose -f docker-compose.yml -f docker-compose.postgres.yml down -v` 会删除全部数据并允许重新初始化。

## 部署注意

- 生产环境必须把 `JAVA_BUILD_IMAGE`、`NODE_IMAGE`、`JAVA_IMAGE`（以及叠加栈的 `POSTGRES_IMAGE`）固定为 digest；默认 tag 只用于本地验收。
- compose 默认仅绑定 `127.0.0.1`，且不终止 TLS，不能直接作为公网入口。对外暴露需自行配置反向代理、证书与访问控制；Desktop 成员 Bearer API 必须走受信任的 HTTPS 入口。
- 凭据只放在 `.env` 或服务器 secret 中，不下发浏览器、不写入镜像。`.env` 已在 `.gitignore` 内。
- `WORKDSH_ADMIN_WEB_ORIGIN` 必须与浏览器实际访问地址完全一致，否则登录等写请求会被 Origin 校验拒绝。
- 模型回答可能长时间没有数据（非流式请求整段生成完才返回、推理模型首字很慢），反向代理和云负载均衡需按下文「反向代理」配置，否则长回答会被代理截断、卡住或以 413 拒绝。
- 会话正文默认存主库。设置 `WORKDSH_BODY_STORE=s3` 及 `WORKDSH_BODY_S3_*` 后写入 S3 兼容对象存储；已有正文需设 `WORKDSH_BODY_STORE_MIGRATE=true` 启动一次迁移（后台分批执行，可中断重跑）。对象存储的生命周期规则不要早于组织设置的正文保留天数。
- 正文保留天数在管理后台「企业设置」按组织配置（默认永久保留），每日北京时间 03:30 清理到期正文，可用 `WORKDSH_MAINTENANCE_CRON` / `WORKDSH_MAINTENANCE_ZONE` 调整。
- 切换数据库后需重新初始化：PostgreSQL 迁移链只用于新验收库，不接管已有未版本化数据库，也不迁移旧测试数据。

## 反向代理

公网入口通常是 nginx（或云负载均衡）终止 TLS 后转发到 `127.0.0.1:18890`。模型网关 `/api/model-gateway/` 有四点要求，只调大超时并不够：

| 要求 | 原因 | nginx 配置 |
| --- | --- | --- |
| 读超时不小于网关自身时限 | nginx 的读超时是两次读取之间的最长间隔。非流式请求在回答生成完之前没有任何字节，推理模型可能长时间不出字。网关等待上游开始响应最长 `WORKDSH_GATEWAY_RESPONSE_TIMEOUT`，流式中途最长静默 `WORKDSH_GATEWAY_STREAM_IDLE_TIMEOUT`（默认均为 5 分钟）。代理读超时应不小于两者中较大者并留余量，这样超时由网关判定，调用方收到带错误码的 JSON，而不是代理的 HTML 504 | `proxy_read_timeout 360s;` |
| 不缓冲、不压缩 | 缓冲或 gzip 会把流式回答攒到缓冲区满或回答结束才发出，客户端表现为卡住后一次性吐出 | `proxy_buffering off;` `gzip off;`（网关的转发响应已带 `X-Accel-Buffering: no`，nginx 会对这些响应自动关闭缓冲；显式配置作为兜底） |
| 请求体上限足够 | nginx 默认 `client_max_body_size` 为 1 MB；网关允许 4 MB 请求体（长上下文、图片），协作材料上限约 5 MB 文件（Base64 后更大） | `client_max_body_size 16m;` |
| HTTP/1.1 长连接到后台 | 保证分块传输正常 | `proxy_http_version 1.1;` `proxy_set_header Connection "";` |

单次响应的总时长另由后台 `WORKDSH_GATEWAY_MAX_RESPONSE_DURATION`（默认 30 分钟）限制。

## 并发与连接池

| 资源 | 默认 | 配置 | 说明 |
| --- | --- | --- | --- |
| 线程模型 | 虚拟线程 | `WORKDSH_VIRTUAL_THREADS` | 需 Java 21。请求处理与流式输出都在虚拟线程上，等待上游模型时不占用平台线程，大量慢速模型调用不会挤占管理后台和其他接口。只在排查问题时临时关闭；关闭后退回平台线程池，并发能力明显下降 |
| 同时进行的模型调用 | 2000 | `WORKDSH_GATEWAY_MAX_CONCURRENT_REQUESTS` | 流式与非流式合计、全部组织共享。从发出上游请求到响应写完占一个名额。达到上限时新请求立即收到 503 `gateway_busy`，不排队，也不向供应商发请求。估算：同时在跑的成员 × 每人并发 agent 数，留出余量；注意进程文件句柄上限（每个调用约占 2 个连接，建议 `ulimit -n` ≥ 上限 × 3） |
| 网关配置缓存 | 10 秒 | `WORKDSH_GATEWAY_CACHE_TTL` | 内部 Key→组织、模型接口配置缓存在内存，转发不再查库；供应商 Key 仍以密文缓存、每次调用时解密。在本实例保存配置或更换内部 Key 立即生效；多实例部署时，其他实例最长在该时长后生效（更换内部 Key 后，旧 Key 在其他实例上最多还能用这么久）。设为 0 则每次查库 |
| 数据库连接 | 10 | `WORKDSH_DB_POOL_SIZE` | 只在单条查询期间占用，模型转发和流式输出不占连接；一般无需调大。PostgreSQL/MySQL 需确认数据库端 `max_connections` 足够容纳所有实例的连接 |
| 获取连接超时 | 30 秒 | `WORKDSH_DB_CONNECTION_TIMEOUT_MS` | 连接被占满时请求最多等待这么久，超时报错 |
| SQLite | 1 个连接 | 固定 | 单写者，不受上面两项影响 |

参考配置（证书路径、域名按实际替换；`WORKDSH_ADMIN_WEB_ORIGIN` 设为 `https://admin.example.com`）：

```nginx
server {
    listen 443 ssl;
    server_name admin.example.com;
    ssl_certificate     /etc/nginx/certs/admin.example.com.crt;
    ssl_certificate_key /etc/nginx/certs/admin.example.com.key;

    client_max_body_size 16m;

    location / {
        proxy_pass http://127.0.0.1:18890;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_read_timeout 60s;
    }

    # 模型网关：长时间无数据、流式逐块转发
    location /api/model-gateway/ {
        proxy_pass http://127.0.0.1:18890;
        proxy_http_version 1.1;
        proxy_set_header Connection "";
        proxy_set_header Host $host;
        proxy_buffering off;
        proxy_cache off;
        gzip off;
        proxy_read_timeout 360s;
        proxy_send_timeout 360s;
    }
}
```

nginx 前面如果还有云负载均衡（阿里云 SLB、AWS ALB 等），其空闲超时默认通常为 60 秒，需同样调到不小于上表的读超时，否则长回答在负载均衡处被断开。调整 `WORKDSH_GATEWAY_RESPONSE_TIMEOUT` 或 `WORKDSH_GATEWAY_STREAM_IDLE_TIMEOUT` 时，代理与负载均衡的超时要一起调整。
