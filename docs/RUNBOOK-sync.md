# 同步服务运维手册

## 服务

- 部署在「同步服务器」（Ubuntu 24.04 arm64），systemd 单元 `vela-sync`，只绑 `127.0.0.1:18731`。
- 代码目录 `/opt/vela-sync`（root 所有，755），数据目录 `/var/lib/vela-sync`（`vela-sync` 用户，700），库文件 `sync.db`。
- 依赖只用 apt：`python3-cryptography`。
- 单元沙盒与 `MemoryMax=256M` 照 `server/sync/vela-sync.service` 模板；仅端口与代码路径改成上面的值。
- 更新代码：把 `server/sync/` 的 `app.py schema.sql backup.py rotate_epoch.py healthcheck.py smoke.sh` 装到 `/opt/vela-sync`（root 所有），然后 `systemctl restart vela-sync`。

## 隧道

- 单独的 cloudflared 单元 `cloudflared-vela-sync`，隧道名 `vela-sync`，与同机其他隧道互不相干。
- 配置 `/etc/cloudflared/vela-sync.yml`（ingress 指向 `http://127.0.0.1:18731`，其余 404）；凭据 `/etc/cloudflared/vela-sync.json`，root 所有、0600，不入库。
- DNS 的 CNAME 已指向该隧道。注意：本机 `~/.cloudflared/config.yml` 默认钉着另一条隧道，`route dns` 必须传隧道 UUID 而不是名字，创建后用 `dig CNAME` 核对。
- Cloudflare 一侧：服务自己回 `Cache-Control: no-store`，实测 `cf-cache-status: DYNAMIC`；仍建议在该域名上加缓存绕过规则，不开 logpush。
- Cloudflare 会拒绝客户端自带 `CF-Connecting-IP` 的请求（403），公网冒烟不要带这个头。

## 备份

- `vela-sync-backup.timer` 每天 03:17（UTC，随机延后至多 5 分钟）触发 `vela-sync-backup.service`，调用 `backup.py`（sqlite 在线备份），保留 7 天。
- 备份目录 `/var/backups/vela-sync`（700），文件 0600，属主 `vela-sync`。以 `vela-sync` 身份运行是有意的：root 打开 WAL 库可能生出 root 属主的 `-wal/-shm`，会让服务读不了；代价是 `backup.py` 会在 stderr 提示目录非 root 属主，可忽略。
- 手动备份：`sudo systemctl start vela-sync-backup.service`，然后 `sudo ls -l /var/backups/vela-sync`。
- 容量检查：`sudo -u vela-sync python3 /opt/vela-sync/healthcheck.py --db /var/lib/vela-sync/sync.db`（退出码 0 正常，1 达到 70% 阈值，2 读不了）。

## epoch 轮换

恢复快照后、对外服务之前执行；旧 epoch 的客户端会停止写入。

1. `sudo systemctl stop vela-sync`
2. 把快照复制为 `/var/lib/vela-sync/sync.db`（属主 `vela-sync`、0600），删除旧的 `sync.db-wal`、`sync.db-shm`。
3. `sudo -u vela-sync python3 /opt/vela-sync/rotate_epoch.py --db /var/lib/vela-sync/sync.db`
4. `sudo systemctl start vela-sync`，跑一遍冒烟。

## 日志

- 服务：`journalctl -u vela-sync -n 100 --no-pager`；隧道：`journalctl -u cloudflared-vela-sync -n 100 --no-pager`；备份：`journalctl -u vela-sync-backup`。
- 日志不含 blob 完整 id（只有前 8 位）。journald 保留期是主机全局设置，该主机还承载其他服务，所以没有安装 `journald-vela-sync.conf`。

## 冒烟

- 本机回环：在服务器上 `cd /opt/vela-sync && bash smoke.sh`（自起临时实例，不碰线上库）。
- 对线上：把 `smoke.sh` 改成指向已部署的地址并换一个专用 blob id；删除后该 id 留下墓碑（再读返回 410），库里会多一行，无法彻底清除。

## 回滚

- 停服务：`sudo systemctl disable --now cloudflared-vela-sync`（先断公网），再 `sudo systemctl disable --now vela-sync vela-sync-backup.timer`。
- 数据回退：按上面「epoch 轮换」的步骤恢复某个备份。
- 完全移除：删除 `/etc/systemd/system/` 下 `vela-sync*`、`cloudflared-vela-sync.service` 并 `daemon-reload`；删 `/opt/vela-sync`、`/var/lib/vela-sync`、`/var/backups/vela-sync`、`/etc/cloudflared/vela-sync.*`；`userdel vela-sync`；在本机 `cloudflared tunnel delete vela-sync`，并删除对应 DNS 记录。
