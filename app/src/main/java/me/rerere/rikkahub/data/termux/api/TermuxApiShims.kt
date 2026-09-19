package me.rerere.rikkahub.data.termux.api

import android.system.Os
import android.util.Log
import java.io.File

/**
 * 把 termux-* shim 脚本写进 $PREFIX/bin。
 *
 * shim 是纯 bash 脚本：通过 bash 的 /dev/tcp 连到 [TermuxApiServer] 的
 * loopback 端口，发送一行 `<token> <command> <base64(arg)>...` 请求，
 * 再把响应的 stderr/stdout/exit code 原样透传。
 *
 * 脚本是幂等安装的：内容一致时跳过写入，app 升级导致脚本变化时自动更新。
 *
 * 注意：workspace 终端是 proot Debian rootfs（非 embedded Termux），Phase 1 不向
 * 其 /usr/local/bin 投放 shim；该终端的 env 也不含 RIKKA_API_*。
 */
object TermuxApiShims {
    private const val TAG = "TermuxApiShims"

    /** Phase 1 投放的命令集合；与 [buildTermuxApiHandlers] 保持一致。 */
    val commands: List<String> = listOf(
        "termux-notification",
        "termux-toast",
        "termux-vibrate",
        "termux-torch",
        "termux-battery-status",
    )

    /**
     * 把全部 shim 写入 `$PREFIX/bin`，返回实际写入（内容变化）的数量。
     * prefix/bin 不存在时不创建——bootstrap 未完成时调用方不应走到这里。
     */
    fun install(prefix: File): Int {
        val binDir = File(prefix, "bin")
        if (!binDir.isDirectory) {
            Log.w(TAG, "PREFIX/bin missing at ${binDir.absolutePath}, skipping shim install")
            return 0
        }
        var written = 0
        for (command in commands) {
            val target = File(binDir, command)
            val script = scriptFor(command, prefix.absolutePath)
            if (target.isFile && runCatching { target.readText() }.getOrNull() == script) {
                continue
            }
            target.writeText(script)
            runCatching { Os.chmod(target.absolutePath, 0b111_101_101) } // 0755
                .onFailure { Log.w(TAG, "chmod failed for ${target.name}: ${it.message}") }
            written++
        }
        if (written > 0) Log.i(TAG, "Installed $written termux-api shim(s) into ${binDir.absolutePath}")
        return written
    }

    internal fun scriptFor(command: String, prefixPath: String): String {
        val stdinSnippet = when (command) {
            // 官方行为：-c/--content 未给时从 stdin 读内容（3s 超时）。
            "termux-notification" -> """
has_content=0
for arg in "${'$'}@"; do
  case "${'$'}arg" in -c|--content) has_content=1;; esac
done
if [ "${'$'}has_content" -eq 0 ]; then
  content=""
  IFS= read -t 3 -r -d '' content || true
  if [ -n "${'$'}content" ]; then set -- "${'$'}@" -c "${'$'}content"; fi
fi
""".trimIndent()

            // 官方行为：无位置参数时从 stdin 读 toast 文本（3s 超时）。
            "termux-toast" -> """
text_args=()
rest=("${'$'}@")
i=0
while [ "${'$'}i" -lt "${'$'}{#rest[@]}" ]; do
  case "${'$'}{rest[${'$'}i]}" in
    -c|-b|-g) i=$((i+2));;
    -s|-h) i=$((i+1));;
    *) text_args+=("${'$'}{rest[${'$'}i]}"); i=$((i+1));;
  esac
done
if [ "${'$'}{#text_args[@]}" -eq 0 ]; then
  t=""
  IFS= read -t 3 -r -d '' t || true
  if [ -n "${'$'}t" ]; then set -- "${'$'}@" "${'$'}t"; fi
fi
""".trimIndent()

            else -> ""
        }

        return """#!$prefixPath/bin/bash
# RikkaHub embedded-Termux shim for $command.
# Forwards the request to the in-app TermuxApiServer over 127.0.0.1 TCP.
set -u
CMD=$command

if [ -z "${'$'}{RIKKA_API_PORT:-}" ] || [ -z "${'$'}{RIKKA_API_TOKEN:-}" ]; then
  echo "${'$'}CMD: RIKKA_API_HOST/RIKKA_API_PORT/RIKKA_API_TOKEN not set; run inside RikkaHub embedded Termux" >&2
  exit 1
fi
HOST=${'$'}{RIKKA_API_HOST:-127.0.0.1}
$stdinSnippet
# 每个参数独立 base64 编码，避免引号/空白/换行歧义（协议: token cmd b64arg...）。
payload=""
for arg in "${'$'}@"; do
  enc=$(printf '%s' "${'$'}arg" | base64 | tr -d '\n')
  payload="${'$'}payload${'$'}enc "
done

if ! exec {fd}<>"/dev/tcp/${'$'}HOST/${'$'}RIKKA_API_PORT"; then
  echo "${'$'}CMD: cannot connect to RikkaHub API server at ${'$'}HOST:${'$'}RIKKA_API_PORT" >&2
  exit 1
fi
printf '%s %s %s\n' "${'$'}RIKKA_API_TOKEN" "${'$'}CMD" "${'$'}payload" >&"${'$'}fd"

# 响应: "<exit> <stderr-len>\n<stderr bytes><stdout until EOF>"
header=""
IFS= read -r -u "${'$'}fd" header || { echo "${'$'}CMD: no response from API server" >&2; exit 1; }
code=${'$'}{header%% *}
errlen=${'$'}{header#* }
if [ "${'$'}errlen" -gt 0 ] 2>/dev/null; then
  dd bs="${'$'}errlen" count=1 iflag=fullblock <&"${'$'}fd" >&2 2>/dev/null
fi
cat <&"${'$'}fd"
exec {fd}>&-
case "${'$'}code" in ''|*[!0-9-]*) code=1;; esac
exit "${'$'}code"
"""
    }
}
