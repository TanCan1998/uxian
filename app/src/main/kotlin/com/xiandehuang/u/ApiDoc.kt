package com.xiandehuang.u

import android.content.Context
import org.json.JSONObject

/**
 * 接口意图 / 字段说明 / 重放结论的查询层。
 *
 * 数据真源是 `assets/api_doc.json`（编译期由 tools/embed_assets.py 内嵌）：
 *   apiDoc   —— api 名 → {intent, cat, layer, note, respFields, reqFields}
 *   fieldDoc —— 字段名/路径 → 中文说明
 *
 * 这份词典和 tools/export_html.py 用的是同一个文件，改一处两边都变。
 * 重放结论来自 tools/probe_replay.sh 的真机实测（api_probe.json），
 * **不是推测** —— 没测过的接口一律返回 unk，界面上标「待验证」。
 *
 * 导入报告的词典会叠加在内存里并落盘到 filesDir/api_doc_import.json，
 * 优先级高于内置（导入的通常来自更新的版本）。
 */
object ApiDoc {

    private var apiDoc: JSONObject? = null
    private var fieldDoc: JSONObject? = null
    private var probe: HashMap<String, Pair<String, String?>>? = null
    private var classDoc: JSONObject? = null

    @JvmStatic
    fun load(ctx: Context?) {
        if (apiDoc != null) return
        synchronized(ApiDoc::class.java) {
            if (apiDoc != null) return
            val a = JSONObject()
            val f = JSONObject()
            val p = HashMap<String, Pair<String, String?>>()
            val cd = JSONObject()

            // ---- 内置词典 ----
            try {
                val src = JSONObject(TrafficAssets.API_DOC)
                copyInto(src.optJSONObject("apiDoc"), a)
                copyInto(src.optJSONObject("fieldDoc"), f)
            } catch (t: Throwable) {
                android.util.Log.w("xysidecar", "api_doc load failed: $t")
            }

            // ---- 导入过的词典（优先级更高）----
            try {
                val app = Protocol.State.app
                if (app != null) {
                    val file = java.io.File(app.filesDir, "api_doc_import.json")
                    if (file.exists()) {
                        val imp = JSONObject(file.readText(Charsets.UTF_8))
                        copyInto(imp.optJSONObject("apiDoc"), a)
                        copyInto(imp.optJSONObject("fieldDoc"), f)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("xysidecar", "api_doc_import load failed: $t")
            }

            // ---- 重放实测结论 ----
            try {
                val src = JSONObject(TrafficAssets.API_PROBE)
                val it = src.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val o = src.optJSONObject(k) ?: continue
                    val r = o.optString("replay", "")
                    if (r != "yes" && r != "no") continue
                    p[k] = Pair(r, o.optString("why", "").ifEmpty { null })
                }
            } catch (t: Throwable) {
                android.util.Log.w("xysidecar", "api_probe load failed: $t")
            }

            // ---- 类名 / 层名 / 重放标签 ----
            try {
                val src = JSONObject(TrafficAssets.CALLER_DOC)
                copyInto(src.optJSONObject("classes"), cd)
            } catch (t: Throwable) {
                android.util.Log.w("xysidecar", "caller_doc load failed: $t")
            }

            apiDoc = a
            fieldDoc = f
            probe = p
            classDoc = cd
        }
    }

    private fun copyInto(src: JSONObject?, dst: JSONObject) {
        if (src == null) return
        val it = src.keys()
        while (it.hasNext()) {
            val k = it.next()
            dst.put(k, src.opt(k))
        }
    }

    /** 重放实测结论：api → (yes|no, 说明) */
    @JvmStatic
    fun loadProbe(ctx: Context?): HashMap<String, Pair<String, String?>> {
        load(ctx)
        return probe ?: HashMap()
    }

    /** (意图, 分类)。没收录的接口按 api 名逐段翻中文兜底。 */
    @JvmStatic
    fun intentOf(api: String): Pair<String, String> {
        val o = apiDoc?.optJSONObject(api)
        val intent = o?.optString("intent", "") ?: ""
        val cat = o?.optString("cat", "") ?: ""
        return Pair(
            if (intent.isNotEmpty()) intent else guess(api),
            if (cat.isNotEmpty()) cat else "其他",
        )
    }

    @JvmStatic
    fun layerOf(api: String): String {
        val l = apiDoc?.optJSONObject(api)?.optString("layer", "") ?: ""
        return if (l.isNotEmpty()) l else "kt"
    }

    @JvmStatic
    fun noteOf(api: String): String =
        apiDoc?.optJSONObject(api)?.optString("note", "") ?: ""

    /** 给报告用的整份词典 */
    @JvmStatic
    fun docJson(): JSONObject = apiDoc ?: JSONObject()

    @JvmStatic
    fun fieldJson(): JSONObject = fieldDoc ?: JSONObject()

    /** 类名 → 中文说明。没收录返回 null（前端退回显示英文类名）。 */
    @JvmStatic
    fun classCn(shortName: String): String? {
        val d = classDoc ?: return null
        val v = d.optString(shortName, "")
        return if (v.isEmpty()) null else v
    }

    @JvmStatic
    fun classDocJson(): JSONObject = classDoc ?: JSONObject()

    /**
     * 把导入报告里的词典合并进来，**并落盘**。
     *
     * 内置词典（TrafficAssets）是编译期常量改不了，导入的追加在它之上 ——
     * 同名以导入为准，因为导入的通常来自更新的版本 / 别人的抓包。
     *
     * @return 实际新增/覆盖的条数
     */
    @JvmStatic
    fun mergeImport(apiDocIn: JSONObject?, fieldDocIn: JSONObject?): Int {
        var n = 0
        synchronized(ApiDoc::class.java) {
            val a = apiDoc ?: JSONObject()
            val f = fieldDoc ?: JSONObject()
            if (apiDocIn != null) {
                val it = apiDocIn.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    val v = apiDocIn.optJSONObject(k) ?: continue
                    a.put(k, v)
                    n++
                }
            }
            if (fieldDocIn != null) {
                val it = fieldDocIn.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    f.put(k, fieldDocIn.opt(k))
                    n++
                }
            }
            apiDoc = a
            fieldDoc = f
            try {
                val ctx = Protocol.State.app
                if (ctx != null) {
                    val out = JSONObject()
                    out.put("apiDoc", a)
                    out.put("fieldDoc", f)
                    java.io.File(ctx.filesDir, "api_doc_import.json")
                        .writeText(out.toString(), Charsets.UTF_8)
                }
            } catch (t: Throwable) {
                android.util.Log.w("xysidecar", "api_doc_import save failed: $t")
            }
        }
        return n
    }

    // ------------------------------------------------------------ 兜底翻译

    /**
     * 英文词根 → 中文。
     *
     * 用户明确要求：能有中文描述的必须加中文。所以没收录的接口不能只显示
     * 英文末段 —— 之前 `...circle.point.sign.in` 只显示 "In"，毫无意义。
     */
    private val WORDS: Array<Pair<String, String>> = arrayOf(
        "benefitdetail" to "权益详情", "devicespuquote" to "设备报价",
        "prepubcheck" to "发布前校验", "shoppingmall" to "商城",
        "headinfo" to "头部信息", "loadmore" to "加载更多",
        "hastask" to "有任务", "tablist" to "标签列表",
        "withconfig" to "带配置", "preget" to "预取",
        "treasure" to "闲鱼币寻宝", "advertise" to "广告", "promotion" to "推广",
        "recharge" to "充值", "pendant" to "挂件", "marquee" to "跑马灯",
        "template" to "模板", "subscribe" to "订阅", "badwords" to "敏感词",
        "fatigue" to "疲劳度", "revisit" to "回访", "rendering" to "渲染",
        "activate" to "激活", "division" to "行政区划", "strategy" to "策略",
        "recommend" to "推荐", "predict" to "预测", "expose" to "曝光上报",
        "entrance" to "入口", "trigger" to "触发", "complete" to "完成",
        "recycle" to "回收", "coupon" to "优惠券", "exchange" to "兑换",
        "auction" to "竞拍", "address" to "地址", "property" to "属性",
        "kgraph" to "知识图谱", "forest" to "森林", "animal" to "动物",
        "material" to "素材", "energy" to "能量", "credit" to "信用",
        "simple" to "简版", "widget" to "小组件", "output" to "产出",
        "atmosphere" to "氛围", "pattern" to "模式", "activity" to "活动",
        "dealprice" to "成交价", "session" to "会话", "relation" to "关系",
        "channel" to "频道", "refresh" to "刷新", "alipay" to "支付宝",
        "circle" to "鱼塘圈子", "record" to "记录", "benefit" to "权益",
        "quote" to "报价", "coupon" to "优惠券", "issue" to "发放",
        "local" to "本地", "feeds" to "信息流", "detail" to "详情",
        "point" to "积分", "bank" to "银行", "main" to "主页",
        "page" to "页面", "head" to "头部", "more" to "更多",
        "asset" to "资产", "sign" to "签到", "fans" to "粉丝",
        "join" to "加入", "roll" to "摇奖", "play" to "玩法",
        "prop" to "道具", "batch" to "批量", "execute" to "执行",
        "tips" to "提示", "words" to "文案", "house" to "房产",
        "order" to "订单", "entry" to "入口", "seller" to "卖家",
        "extra" to "附加信息", "fetch" to "拉取", "inbox" to "收件箱",
        "market" to "市场", "launch" to "启动", "service" to "服务",
        "message" to "消息", "read" to "已读", "item" to "商品",
        "buy" to "购买", "task" to "任务", "award" to "奖励",
        "scene" to "场景", "show" to "展示", "bubble" to "气泡",
        "user" to "用户", "home" to "首页", "top" to "顶部",
        "gps" to "定位", "game" to "游戏", "mini" to "小",
        "community" to "社区", "suggest" to "联想", "search" to "搜索",
        "query" to "查询", "get" to "获取", "set" to "设置",
        "list" to "列表", "update" to "更新", "sync" to "同步",
        "upload" to "上传", "report" to "上报", "config" to "配置",
        "check" to "校验", "info" to "信息", "data" to "数据",
        "count" to "计数", "submit" to "提交", "confirm" to "确认",
        "publish" to "发布", "draft" to "草稿", "profile" to "资料",
        "setting" to "设置", "notice" to "通知", "rule" to "规则",
        "restore" to "恢复", "trace" to "追踪", "stat" to "统计",
        "log" to "日志", "init" to "初始化", "open" to "开启",
        "close" to "关闭", "grant" to "发放", "receive" to "接收",
        "guide" to "引导", "hunt" to "寻宝", "feed" to "投喂",
        "wear" to "佩戴", "issue" to "发放", "tips" to "提示",
        // 业务代号：这些是闲鱼内部产品名，翻成中文更易读
        "oliver" to "Oliver 权益中心", "forest" to "闲鱼森林",
        "pentaprism" to "五棱镜投放", "auge" to "Auge 人群",
        "mach" to "Mach 投放", "customer" to "客服", "in" to "进入",
        "use" to "使用", "by" to "按", "sec" to "二手房",
        "tab" to "标签", "map" to "地图", "cpv" to "曝光量",
        "card" to "卡片", "crowd" to "人群", "account" to "账户",
        "pointbank" to "积分银行", "flybird" to "Flybird H5",
    )

    /** 按长度倒序，避免 "get" 抢先吃掉 "preget" */
    private val SORTED_WORDS = WORDS.sortedByDescending { it.first.length }

    /**
     * 没收录的接口：把 api 名逐段翻成中文。
     *
     * 例：`mtop.taobao.idle.circle.point.sign.in` → 「鱼塘圈子 积分 签到 进入」
     *
     * 一个词根都命中不了才退回英文原词（不硬翻）。
     * 英文原串始终保留在报告的 api 行里，方便搜代码。
     */
    private fun guess(api: String): String {
        var name = api
        for (p in arrayOf("mtop.taobao.", "mtop.alibaba.", "mtop.", "com.taobao.")) {
            if (name.startsWith(p)) {
                name = name.substring(p.length)
                break
            }
        }
        val segs = name.split('.').filter { it.isNotEmpty() }
        val cn = ArrayList<String>()
        for (seg in segs) {
            val low = seg.lowercase()
            if (low == "idle" || low == "taobao" || low == "alibaba" || low == "com") continue
            var rest = low
            var hit = false
            var guard = 0
            while (rest.isNotEmpty() && guard++ < 8) {
                var matched = false
                for (w in SORTED_WORDS) {
                    if (rest.startsWith(w.first)) {
                        cn.add(w.second)
                        rest = rest.substring(w.first.length)
                        matched = true
                        hit = true
                        break
                    }
                }
                if (!matched) break
            }
            if (!hit) cn.add(seg)
        }
        if (cn.isEmpty()) return api
        val out = ArrayList<String>()
        for (x in cn) if (out.isEmpty() || out[out.size - 1] != x) out.add(x)
        return out.joinToString(" ")
    }
}
