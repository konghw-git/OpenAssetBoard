# 日盈账号与同步服务

这是「日盈」的可选自建后端：**账号认证 + 每个用户一份不透明的备份快照**。

它不解析账本，不做逐行合并，也不保存第二套数据模型。App 仍然是本地优先的：不登录时行为完全不变；登录后把现有的 `BackupFile` JSON 上传到这里，换设备或重装时再下载回来。

> 当前状态：服务端已部署在 VPS 的 `https://khw-asset.216-158-79-137.sslip.io`，Android 端的「设置 › 账号与同步」已经接入。当前实例的管理员账号是 `konghw`，初始密码在 VPS 的 `/root/assetboard-login.txt`（权限 600）。

## 设计边界

- **账号由管理员创建**。没有 `/register`，没有邮件找回；密码由管理员在服务器上重置。
- **每个用户一份快照**。同步是整份上传/下载，不是逐条增量。冲突时不合并，交给用户选。
- **服务端可读**。数据以明文 SQLite 保存，TLS 只保护传输；你作为服务器管理员能看到所有账号的数据。这是有意选择的简单性，不做端到端加密。
- **单实例**。SQLite + 进程内限流；不需要 Redis，也不支持多副本。

## 本地运行

需要 Go 1.27+。

```bash
cd server
go test ./...
go build -o bin/assetboard-server ./cmd/assetboard-server

# 建账号：密码从 stdin 读，不接受命令行参数；-db 要写在用户名前面
printf 'a-long-password\n' | ./bin/assetboard-server user add -db ./data/assetboard.db alice

# 启动
./bin/assetboard-server serve -addr 127.0.0.1:8090 -db ./data/assetboard.db
curl -s http://127.0.0.1:8090/healthz
```

`user add` 在终端里会要求输入两次密码；从管道读取时只读一次。

### 管理员命令

```text
assetboard-server serve [flags]
assetboard-server user add [flags] <username>
assetboard-server user passwd [flags] <username>
assetboard-server user list [flags]
assetboard-server user disable [flags] <username>
assetboard-server user enable [flags] <username>
assetboard-server backup [flags] -out <file>
assetboard-server version
```

> Go 的 `flag` 在遇到第一个位置参数后停止解析，所以 `<username>` 必须放在 `-db` 等参数之后。

- `user passwd` 会撤销该用户所有已登录会话。
- `user disable` 会撤销所有会话；`enable` 只是恢复登录。
- `backup` 用 SQLite `VACUUM INTO` 写一份一致副本，**拒绝覆盖已存在的文件**。

### 配置

| 环境变量 | 默认值 | 说明 |
|---|---|---|
| `ASSETBOARD_ADDR` | `:8090` | 监听地址 |
| `ASSETBOARD_DATA_DIR` | `./data` | 数据目录 |
| `ASSETBOARD_DB` | `<data-dir>/assetboard.db` | 数据库路径 |
| `ASSETBOARD_ACCESS_TTL` | `15m` | 访问令牌有效期 |
| `ASSETBOARD_REFRESH_TTL` | `720h` | 刷新令牌有效期（每次轮换重新计时） |
| `ASSETBOARD_MAX_BLOB_BYTES` | `33554432` | 单份快照上限（32 MiB） |
| `ASSETBOARD_HISTORY` | `10` | 每个用户保留的版本数（含当前） |
| `ASSETBOARD_TRUST_PROXY` | `false` | 信任 `X-Forwarded-For`；只有放在 Caddy 后面时才开 |

对应的命令行参数：`serve -addr -data-dir -db -history -trust-proxy`；管理命令只接受 `-db`。

## HTTP API

所有请求和响应都是 JSON（快照本身除外）。错误统一为：

```json
{"error":{"code":"conflict","message":"snapshot changed on the server"},"revision":2}
```

| 方法 | 路径 | 认证 | 说明 |
|---|---|---|---|
| `GET` | `/healthz` | 否 | `{"status":"ok","version":"0.1.0"}` |
| `POST` | `/v1/auth/login` | 否 | `{username,password}` → 令牌与用户 |
| `POST` | `/v1/auth/refresh` | 否 | `{refreshToken}` → 新的一对令牌 |
| `POST` | `/v1/auth/logout` | 否 | `{refreshToken}`，撤销整个令牌家族，204 |
| `POST` | `/v1/auth/password` | 是 | `{oldPassword,newPassword}`，修改密码，返回新的令牌与用户 |
| `GET` | `/v1/me` | 是 | 当前用户 |
| `GET` | `/v1/sync/meta` | 是 | 版本、哈希、格式版本、大小；没有快照时 `revision: 0` |
| `GET` | `/v1/sync` | 是 | 最新快照原文；没有时 404 |
| `PUT` | `/v1/sync` | 是 | 上传新快照，见下 |
| `GET` | `/v1/sync/revisions` | 是 | 最近版本列表，新的在前 |
| `GET` | `/v1/sync/revisions/{n}` | 是 | 指定版本原文 |

登录成功返回（修改密码成功也返回同样结构的一对新令牌）：

```json
{
  "accessToken": "…",
  "refreshToken": "…",
  "expiresIn": 900,
  "user": {"id":"…","username":"alice"}
}
```

改密码的错误码：新密码不合法为 400 `invalid_password`，新旧相同为 400 `same_password`，旧密码错误为 401 `invalid_old_password`。

### 上传与冲突

`PUT /v1/sync`：

- `If-Match: <revision>`：客户端上次看到的版本。首次上传用 `0`，只有服务器还没有快照时才成功。
- 服务器当前版本与 `If-Match` 不一致时返回 **409**，并带上当前版本；不会覆盖。
- `X-Content-Hash: <64 位小写十六进制>`：可选。客户端的内容哈希（见下）；不传时服务器对请求体做 SHA-256。
- 请求体必须是 JSON 对象，且包含合法的 `formatVersion`（1–1000）和 `schemaVersion`（1–10000）；其余内容原样保存。
- 成功返回 `{"revision": n}`，并带 `ETag` 与 `X-Revision`。

下载时的响应头：

```
ETag: "3"
X-Revision: 3
X-Content-Hash: <客户端上传的内容哈希>
X-Format-Version: 14
X-Schema-Version: 13
X-Updated-At: 1793075494
```

`GET /v1/sync` 支持 `If-None-Match`（含 `W/"n"`），命中返回 304。

### 内容哈希怎么算

客户端把 `BackupFile` 的 `exportedAt` 设为 `0`，用与备份文件相同的 `Json { encodeDefaults = true }` 配置序列化为 UTF-8，取 SHA-256 的小写十六进制。

服务端**不重算**这个值，只保存并在 `meta`/响应头里原样返回。它只用于判断“内容有没有变”，不参与鉴权；即使客户端算错，最坏结果也只是多传一次。版本号与 `If-Match` 才是并发控制的依据。

## 安全模型

- 密码：Argon2id（32 MiB / 3 轮 / 并行度 1），参数写在 PHC 字符串里，以后可以调高而不影响旧密码。
- 令牌：32 字节随机数，数据库只存 SHA-256；不是 JWT，删除一行即可撤销。
  - 访问令牌 15 分钟。
  - 刷新令牌 30 天，每次使用都轮换；**旧刷新令牌再次出现会撤销整个家族**（视为泄露）。
  - 改密码、禁用账号会撤销该用户全部会话。App 内改密码需要旧密码；成功后这次请求会拿到一对新令牌，当前设备保持登录，其他设备需要重新登录。旧密码错误按用户限流（默认 5 次 / 5 分钟）。
- 登录限流：每用户名 5 次 / 5 分钟，每 IP 20 次 / 5 分钟（进程内固定窗口）。
- 用户名 3–32 位（字母、数字、`.` `_` `-`，不区分大小写）；密码 8–256 字节。
- 服务只应在 TLS 后面运行；容器不发布端口，由 Caddy 通过 Docker 网络访问。
- 日志只记录方法、路径、状态码、耗时、IP，不记录请求体、密码或令牌。
- 数据在磁盘上是明文。请限制 VPS 的 SSH 访问、保护 `/opt/assetboard/data` 和备份目录。

## 部署到 VPS

现有环境：Caddy 在 Docker 里，网络名 `sub2api_sub2api-network`，`khw-monitor.216-158-79-137.sslip.io` 已经验证过 sslip.io + Let's Encrypt。新服务沿用同一套：

1. 准备目录，注意容器内用户是 UID 10001：

   ```bash
   sudo install -d -o 10001 -g 10001 /opt/assetboard/data
   sudo install -d -o 10001 -g 10001 /opt/assetboard/data/backups
   ```

2. 把 `server/` 放到 `/opt/assetboard`，启动：

   ```bash
   cd /opt/assetboard
   sudo docker compose -f compose.example.yaml up -d --build
   sudo docker compose -f compose.example.yaml exec -T assetboard \
       assetboard-server user add -db /data/assetboard.db alice
   ```

3. 在 `/opt/sub2api/Caddyfile` **只追加** `Caddyfile.example` 里的站点块，然后：

   ```bash
   sudo docker exec sub2api-caddy caddy validate --config /etc/caddy/Caddyfile
   sudo docker exec sub2api-caddy caddy reload   --config /etc/caddy/Caddyfile
   curl -s https://khw-asset.216-158-79-137.sslip.io/healthz
   ```

4. 不在防火墙上开放 8090；Caddy 通过 `assetboard:8090` 访问。

> 修改 Caddyfile 会碰到正在为 Sub2API 与 Monitor 服务的进程。正式操作前先备份原文件并在非高峰时段进行；`caddy validate` 通过后再 reload。

## 备份与恢复

每天一份一致副本，保留 7–30 天：

```bash
cd /opt/assetboard
sudo docker compose exec -T assetboard assetboard-server backup \
    -db /data/assetboard.db -out "/data/backups/assetboard-$(date +%F).db"
sudo cp "/opt/assetboard/data/backups/assetboard-$(date +%F).db" /var/backups/assetboard/
```

`backup` 拒绝覆盖同名文件，所以同一天重复执行会失败，这是有意的。恢复：

```bash
sudo docker compose -f /opt/assetboard/compose.example.yaml stop assetboard
sudo cp /var/backups/assetboard/assetboard-2026-10-27.db /opt/assetboard/data/assetboard.db
sudo rm -f /opt/assetboard/data/assetboard.db-wal /opt/assetboard/data/assetboard.db-shm
sudo chown 10001:10001 /opt/assetboard/data/assetboard.db
sudo docker compose -f /opt/assetboard/compose.example.yaml start assetboard
```

## 测试

```bash
go test ./...
go test -race ./...
```

覆盖的关键行为：

- 用户 A 读不到用户 B 的快照（HTTP 与 store 两层）；
- 同一 `If-Match` 的并发上传只有一个成功，另一个得到 409；
- 没有 `If-Match` 不能覆盖已有快照；
- 访问令牌过期、刷新令牌轮换、旧刷新令牌重放会撤销整个家族；
- 登出、禁用账号后所有会话失效；
- 登录限流、请求体上限、非法 JSON / 格式版本 / 内容哈希；
- 历史版本裁剪、按版本下载、`If-None-Match` 304；
- `backup` 产物可被重新打开，且拒绝覆盖已有文件。

## 尚未实现

- 逐行增量同步或自动合并（冲突必须由用户选择）。
- 端到端加密。
- Web 管理界面、邮件找回、开放注册、配额、审计日志。
- 多实例 / 外部数据库。

Android 端已接入：刷新令牌用 Android Keystore 加密保存，多档案各自使用一个本地数据库，客户端发送 `If-Match`（乐观并发）与 `X-Content-Hash`（内容变化检测）接入这里。服务端的接口与冲突语义保持不变。