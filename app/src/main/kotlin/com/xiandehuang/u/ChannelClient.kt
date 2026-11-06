package com.xiandehuang.u

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import android.net.LocalSocket
import android.net.LocalSocketAddress

/**
 * 客户端：从任意进程（主进程 / 模块 App / 外部脚本经 run-as）连 channel 的 socket。
 *
 * 优先连 channel 的 socket；连不上就退回本进程直接调 MtopBridge（主进程场景）。
 */
object ChannelClient {

    private const val TAG = "xysidecar"

    /** 一次请求；连不上返回 null，由调用方决定是否回退 */
    fun request(api: String, version: String, data: String, timeoutMs: Int = 15000): JSONObject? {
        return send(
            JSONObject().put("api", api).put("version", version).put("data", data),
            timeoutMs,
        )
    }

    /** 管理命令：ping / status / reap / task.* */
    fun command(cmd: String, timeoutMs: Int = 8000): JSONObject? {
        return send(JSONObject().put("cmd", cmd), timeoutMs)
    }

    /** 发原始 JSON（任务管理等复合命令用） */
    fun sendRaw(json: String, timeoutMs: Int = 15000): JSONObject? {
        var sock: LocalSocket? = null
        try {
            sock = LocalSocket()
            sock.connect(
                LocalSocketAddress(ChannelServer.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
            )
            sock.soTimeout = timeoutMs
            val writer = OutputStreamWriter(sock.outputStream, Charsets.UTF_8)
            writer.write(json)
            writer.write("\n")
            writer.flush()
            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val line = reader.readLine() ?: return null
            return JSONObject(line)
        } catch (t: Throwable) {
            Log.i(TAG, "ChannelClient: 连不上 channel socket（$t）")
            return null
        } finally {
            try {
                sock?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    private fun send(payload: JSONObject, timeoutMs: Int): JSONObject? {
        var sock: LocalSocket? = null
        try {
            sock = LocalSocket()
            sock.connect(
                LocalSocketAddress(ChannelServer.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
            )
            sock.soTimeout = timeoutMs
            val writer = OutputStreamWriter(sock.outputStream, Charsets.UTF_8)
            writer.write(payload.toString())
            writer.write("\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(sock.inputStream, Charsets.UTF_8))
            val line = reader.readLine() ?: return null
            return JSONObject(line)
        } catch (t: Throwable) {
            Log.i(TAG, "ChannelClient: 连不上 channel socket（$t），由调用方回退")
            return null
        } finally {
            try {
                sock?.close()
            } catch (ignored: Throwable) {
            }
        }
    }

    /** channel socket 是否可用 */
    fun available(): Boolean {
        var sock: LocalSocket? = null
        return try {
            sock = LocalSocket()
            sock.connect(
                LocalSocketAddress(ChannelServer.SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT),
            )
            true
        } catch (t: Throwable) {
            false
        } finally {
            try {
                sock?.close()
            } catch (ignored: Throwable) {
            }
        }
    }
}
