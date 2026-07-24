# st_server

## 远端服务器一键部署

适用：Ubuntu / Debian 服务器，当前用户是 root 或具备 sudo 权限。

```bash
curl -fsSL https://raw.githubusercontent.com/GRQ02200059/st_server/main/scripts/bootstrap_remote.sh | bash
```

默认行为：

- 安装 `git`、`curl`、`unzip`、`openjdk-17-jdk`
- 克隆或更新仓库到 `/opt/st_server`
- 使用 Gradle wrapper 构建服务端
- 创建并启动 systemd 服务 `st_server`
- 默认监听端口 `59979`
- 默认向客户端下发服务器地址 `152.136.236.184`

自定义端口示例：

```bash
curl -fsSL https://raw.githubusercontent.com/GRQ02200059/st_server/main/scripts/bootstrap_remote.sh | STZB_PORT=60000 bash
```

自定义下发给客户端的服务器 IP：

```bash
curl -fsSL https://raw.githubusercontent.com/GRQ02200059/st_server/main/scripts/bootstrap_remote.sh | STZB_PUBLIC_HOST=152.136.236.184 bash
```

常用运维命令：

```bash
systemctl status st_server --no-pager
journalctl -u st_server -f
systemctl restart st_server
```

## Gradle 下载超时处理

仓库已将 Gradle wrapper 切到腾讯云镜像。如果服务器上已经 clone 过旧版本，在服务器仓库目录执行：

```bash
git pull
./gradlew run
```

## 客户端选服后连到 127.0.0.1

如果日志出现 `服务器列表已下发 (host=127.0.0.1:59979)`，说明服务端没有配置公网 IP。更新代码并带公网 IP 启动：

```bash
git pull
STZB_PUBLIC_HOST=152.136.236.184 ./gradlew run
```

systemd 部署则重新执行一键脚本：

```bash
curl -fsSL https://raw.githubusercontent.com/GRQ02200059/st_server/main/scripts/bootstrap_remote.sh | STZB_PUBLIC_HOST=152.136.236.184 bash
```
