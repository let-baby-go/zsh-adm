# 密钥与轮换

WorkDSH Admin 涉及五类密钥。最需要注意的是加密主密钥：它**目前没有版本号**，一旦更换，已加密的数据全部无法解密。

| 密钥 | 存放位置 | 用途 | 现在能否在线轮换 |
| --- | --- | --- | --- |
| `WORKDSH_SECRETS_ENCRYPTION_KEY` | 部署环境变量 | AES-256-GCM 加密下表「受影响数据」 | 不能，见下文 |
| `WORKDSH_SECRETS_SERVICE_KEY` | 部署环境变量，DSH 服务端适配器同时持有 | `/api/internal/**` 服务器间鉴权（请求头 `X-WorkDSH-Service-Key`） | 不能无缝轮换，两侧需同时更换 |
| 组织内部访问 Key | 管理后台「企业模型」，库中存 SHA-256 与加密副本 | 成员调用 `/api/model-gateway/**` | 能；保存后旧 Key 立即失效 |
| 供应商 API Key | 管理后台「企业模型」，库中加密 | 网关访问上游模型 | 能；在供应商处签发新 Key 后在后台替换 |
| 成员登录会话 | 库中只存令牌 SHA-256 | 管理后台与 Desktop 登录，12 小时有效 | 改密、停用即清除；过期会话每日清理 |

## 加密主密钥

密文格式为 `v1:<nonce>:<ciphertext>`，附加认证数据（AAD）绑定数据归属（组织、成员、引用名），同一份密文不能被挪到其他成员或组织下解密。密文里**没有**记录用的是哪把密钥，因此服务同一时间只能使用一把。

受影响数据（更换主密钥后均无法解密，相关接口返回 503「Authorization store operation failed」）：

| 表 | 字段 | 内容 |
| --- | --- | --- |
| `member_secrets` | `encrypted_value` | 成员授权引用（如 MCP 授权） |
| `member_secret_records` | `encrypted_value` | 成员授权记录 |
| `server_transport_records` | `encrypted_value` | 部署级传输签名记录 |
| `organization_model_providers` | `providers_json` 中各接口的 `apiKey` | 供应商 API Key |
| `organization_model_gateway_keys` | `encrypted_key` | 内部访问 Key 的可查看副本 |

`organization_model_gateway.access_hash` 是 SHA-256 摘要，不受主密钥影响，网关鉴权在更换主密钥后仍可用。

### 备份要求

数据库备份里只有密文。主密钥必须**与数据库备份分开**、在独立的密钥管理处备份；丢失主密钥等于丢失上表全部数据，无法从数据库恢复。

### 当前可行的轮换方式

后台目前不提供重新加密工具。确需更换（例如怀疑泄露）时：

1. 记录需要恢复的内容：各组织的供应商 API Key、内部访问 Key；成员授权与传输签名记录由 DSH 侧重新写入。
2. 停止服务，更换 `WORKDSH_SECRETS_ENCRYPTION_KEY`，清空上表受影响字段对应的记录（`member_secrets`、`member_secret_records`、`server_transport_records`、`organization_model_gateway_keys` 的行，以及 `organization_model_providers` 的配置）。
3. 启动服务，在「企业模型」重新填写供应商 Key 与内部访问 Key，通知成员在 Desktop 重新授权。

这会造成配置中断，应安排维护窗口。

### 后续设计：带版本号的密钥（未实现）

目标是在线轮换、无需清空数据：

- 密文改为 `v2:<keyId>:<nonce>:<ciphertext>`；现有 `v1` 密文视为 `keyId=legacy`。
- 配置改为多把密钥：`WORKDSH_SECRETS_ENCRYPTION_KEYS=<id>:<base64>,<id>:<base64>`，另设 `WORKDSH_SECRETS_ACTIVE_KEY=<id>`。加密只用活动密钥，解密按密文中的 `keyId` 选择。
- 新增后台重加密任务：分批读取仍使用旧 `keyId` 的密文，用活动密钥重新加密后写回（与正文迁移任务相同的可中断、可重跑方式）。全部完成后才能从配置中移除旧密钥。
- 启动时检查：库中仍存在某 `keyId` 的密文而配置里缺少该密钥时拒绝启动，避免误删密钥导致数据不可读。

## 服务器间服务密钥

`/api/internal/**` 只接受与 `WORKDSH_SECRETS_SERVICE_KEY` 完全一致的请求头，同时仍要求成员显式的 `Authorization: Bearer`；浏览器登录 Cookie 不能用于这些接口。错误含义：

| 状态 | 说明 |
| --- | --- |
| 503 `Service key is not configured` | 服务端未配置或少于 32 位，属部署问题 |
| 403 `Service key required` | 请求未带服务密钥 |
| 403 `Invalid service key` | 服务密钥不一致 |

目前只认一把服务密钥，轮换时 Admin 与 DSH 服务端需同时更换，期间的内部请求会失败。若需无缝轮换，可仿照上节在过渡期同时接受新旧两把密钥（未实现）。

## 组织内部访问 Key

管理员在「企业模型 → 成员访问」保存新 Key 后，旧 Key 在该实例上立即失效，正在使用旧 Key 的成员调用会收到 401。多实例部署时，其他实例因网关缓存最长在 `WORKDSH_GATEWAY_CACHE_TTL`（默认 10 秒）后才拒绝旧 Key；怀疑 Key 泄露需要立刻全部失效时，可临时把该值设为 0 或重启各实例。更换前应先通知成员准备在 DSH「自定义模型 API」中更新。查看已保存的 Key 需二次确认，并记入操作日志。
