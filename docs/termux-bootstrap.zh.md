# 内嵌 Termux bootstrap

[English](termux-bootstrap.md) | [简体中文](termux-bootstrap.zh.md)

官方 Termux bootstrap 归档是为 `/data/data/com.termux/files/usr` 编译的，无法迁移到
RikkaHub 的应用沙箱内。因此应用会明确拒绝下载官方 bootstrap。

两个 bootstrap 归档都必须针对目标构建变体实际使用的 Android
application id 和 prefix 来构建：

- release：包名 `excp.rikkahub`，prefix `/data/data/excp.rikkahub/files/termux/usr`
- debug：包名 `excp.rikkahub.debug`，prefix `/data/data/excp.rikkahub.debug/files/termux/usr`

## 构建归档

需要 Linux + Docker。截至 2026 年 7 月，bootstrap 构建器的改动尚未完全合入上游，
因此要从 agnostic-apollo 的 `termux-packages` 仓库的 `infra-improvs` 分支构建，
而不是上游的 `termux/termux-packages`：

```bash
git clone --depth 1 --branch infra-improvs \
  https://github.com/agnostic-apollo/termux-packages.git
cd termux-packages
```

构建 release bootstrap 时，编辑 `scripts/properties.sh`：

```bash
TERMUX_APP__PACKAGE_NAME="excp.rikkahub"
TERMUX__ROOTFS_SUBDIR="files/termux"
```

派生路径必须输出为：

```text
TERMUX__ROOTFS=/data/data/excp.rikkahub/files/termux
TERMUX__HOME=/data/data/excp.rikkahub/files/termux/home
TERMUX__PREFIX=/data/data/excp.rikkahub/files/termux/usr
```

然后只构建本应用支持的 ABI：

```bash
./scripts/run-docker.sh ./clean.sh
./scripts/run-docker.sh ./scripts/build-bootstraps.sh \
  --architectures aarch64,x86_64 --no-build-unneeded-subpackages \
  2>&1 | tee build-bootstrap.log
sha256sum bootstrap-aarch64.zip bootstrap-x86_64.zip
```

`infra-improvs` 构建器默认执行清理，不再接受旧的 `-f` 参数。
只有在修正了临时性的下载或打包失败之后，才用 `--no-clean` 续跑。

debug APK 需要用包名 `excp.rikkahub.debug` 在干净的构建树上重新构建一次。release
归档不能给 debug APK 复用，因为应用数据目录的绝对路径是编译进 Termux 包里的。

本地构建的归档只包含 `build-bootstraps.sh` 自带的包。要让 `apt`/`pkg` 能安装或升级
其它任意包，这些包也必须按相同的包名/prefix 构建，并发布到自定义 APT 仓库。官方
Termux 镜像里的包是为 `com.termux` 编译的，绝不能配成兜底源。

`apt` 使用的包仓库必须包含按相同包名和 prefix 构建的包。通过 Gradle 属性配置应用构建：

```properties
termuxBootstrapUrlTemplate=https://example.invalid/bootstrap/{package}/bootstrap-{arch}.zip
termuxBootstrapAarch64Sha256=<64 位小写十六进制>
termuxBootstrapX8664Sha256=<64 位小写十六进制>
```

`{package}` 和 `{arch}` 在运行时替换。支持的架构值为 `aarch64` 和 `x86_64`。
不设置这些属性会显式报错并禁用 bootstrap 安装，而不是去装不兼容的官方归档。

CI 构建也可以把变体专属的归档放在 `app/src/<variant>/assets/termux/bootstrap-{arch}.zip`。
安装器优先使用打包进 APK 的归档，只有资源不存在时才回退到配置的 URL。打包归档同样
强制校验 SHA-256，防止构建时误打包了错误变体。

在 Android 10 及以上，target API 29+ 的应用不能直接 `execve()` 应用数据目录下的可写
文件。因此 RikkaHub 通过第一个可用的可执行 64 位 Android 链接器启动 bootstrap shell——
依次尝试 `/system/bin/linker64`、`/apex/com.android.runtime/bin/linker64`、
`/system/bin/bootstrap/linker64`——并预加载 bootstrap 自带的
`libtermux-exec-linker-ld-preload.so`；termux-exec 会对子命令应用同样的系统链接器路由。
Android 8 和 9 使用直连的 termux-exec 变体。

安装在应用启动和工具调用之间是串行化的。安装器先创建 HOME，再发布 PREFIX，然后执行
必需的 bootstrap 第二阶段，接着依次校验 `pkg`、`apt`、termux-exec 子进程启动和
`dpkg --audit`，全部通过后才写入就绪标记。完整但中途失败的安装会原地修复：恢复 HOME、
TMPDIR 和应用管理的环境文件，而不替换 PREFIX 或 HOME。因此已安装的包、配置、脚本和
项目都能跨应用重启和 APK 升级保留。如果保存的工作目录属于另一个构建变体或已不存在，
会回退到当前变体持久的 Termux HOME，而不是把一个无效 cwd 传给 `ProcessBuilder`。
