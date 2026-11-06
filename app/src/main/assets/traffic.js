/* U闲 抓包分析器 —— 纯前端，无依赖。
 *
 * 设计约束：
 *   1. 页面永远等于视窗高度，滚动只发生在列表 / 详情内部；
 *   2. 大响应绝不全量渲染：JSON 树默认两层，子节点分批；
 *   3. 超过 2MB 的正文不解析，只给原文预览；
 *   4. 所有「返回」都要能被系统返回键接管（history + popstate）。
 */
(function () {
  "use strict";

  var DATA = JSON.parse(document.getElementById("D").textContent);
  var ROWS = DATA.rows || [];
  var FIELD_DOC = DATA.fieldDoc || {};
  var API_DOC = DATA.apiDoc || {};
  var CLASS_DOC = DATA.classDoc || {};
  var LAYER_LABEL = DATA.layerLabel || {};
  var REPLAY_LABEL = DATA.replayLabel || {};

  var $ = function (id) { return document.getElementById(id); };
  var listEl = $("list"), filtersEl = $("filters"), qEl = $("q");
  var sheetEl = $("sheet"), shBody = $("shBody"), tabsEl = $("tabs");

  /* ---------------- 工具 ---------------- */

  function esc(s) {
    return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) {
      return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c];
    });
  }

  function fmtBytes(n) {
    if (!n) return "—";
    if (n < 1024) return n + "B";
    if (n < 1048576) return (n / 1024).toFixed(n < 10240 ? 1 : 0) + "KB";
    return (n / 1048576).toFixed(1) + "MB";
  }

  function fmtTime(ts) {
    if (!ts) return "";
    var d = new Date(ts), p = function (v) { return v < 10 ? "0" + v : "" + v; };
    return p(d.getHours()) + ":" + p(d.getMinutes()) + ":" + p(d.getSeconds());
  }

  var toastT = 0;
  function toast(msg) {
    var t = $("toast");
    t.textContent = msg;
    t.classList.add("on");
    clearTimeout(toastT);
    toastT = setTimeout(function () { t.classList.remove("on"); }, 1700);
  }

  function layerLabel(k) {
    return LAYER_LABEL[k] || { kt: "Kotlin", java: "Java", h5: "H5", weex: "Weex",
      fl: "Flutter", sdk: "SDK", native: "Native" }[k] || k;
  }

  function replayLabel(k) {
    return REPLAY_LABEL[k] || { yes: "可重放", no: "不可重放", unk: "待验证" }[k] || k;
  }

  /* ---------------- 解析正文 ---------------- */
  function parsed(body) {
    // 注意：实例数据里可能只有 req 没有 resp（或反过来），
    // 那种情况下 body 是 undefined，或者 body.text 是 undefined。
    // 之前只判了 body == null，导致 showCompare 里炸 "reading 'length'"。
    if (body == null || typeof body.text !== "string") return { kind: "none" };
    if (body.__p) return body.__p;
    var out;
    if (body.text.length > 2 * 1024 * 1024) {
      out = { kind: "huge", len: body.text.length };
    } else {
      var t = body.text.trim();
      if (t.charAt(0) === "{" || t.charAt(0) === "[") {
        try { out = { kind: "json", v: JSON.parse(t) }; }
        catch (e) { out = { kind: "text", v: body.text }; }
      } else {
        out = { kind: "text", v: body.text };
      }
    }
    body.__p = out;
    return out;
  }

  /* ---------------- JSON 树（懒渲染） ---------------- */
  var CHUNK = 40;
  var uid = 0;
  var pending = {};

  function isObj(v) { return v !== null && typeof v === "object"; }

  function lastSeg(p) {
    var i = p.lastIndexOf(".");
    return i < 0 ? p : p.slice(i + 1);
  }

  function leafHtml(v, keyPath) {
    var doc = FIELD_DOC[keyPath] || FIELD_DOC[lastSeg(keyPath)];
    var fn = doc ? '<span class="fn">' + esc(doc) + "</span>" : "";
    if (v === null) return '<span class="nul">null</span>' + fn;
    if (typeof v === "boolean") return '<span class="b">' + v + "</span>" + fn;
    if (typeof v === "number") return '<span class="n">' + v + "</span>" + fn;
    var s = String(v);
    var shown = s.length > 400 ? s.slice(0, 400) : s;
    var h = '<span class="s">"' + esc(shown) + (s.length > 400 ? "…" : "") + '"</span>';
    if (s.length > 400) {
      h += '<button class="expand" data-full="' + esc(s.slice(0, 8000)) + '">展开 ' + s.length + " 字</button>";
    }
    return h + fn;
  }

  function nodeHtml(key, v, path, depth) {
    var keyHtml = key === null ? "" :
      '<span class="k">' + esc(key) + '</span><span class="colon">: </span>';
    if (!isObj(v)) {
      return "<div>" + keyHtml + leafHtml(v, path) + "</div>";
    }
    var arr = Array.isArray(v);
    var n = arr ? v.length : Object.keys(v).length;
    if (n === 0) {
      return "<div>" + keyHtml + '<span class="cnt">' + (arr ? "[]" : "{}") + "</span></div>";
    }
    // 默认展开两层：第一层是根，第二层才是字段名
    var open = depth < 2 ? " open" : "";
    var id = "d" + (++uid);
    pending[id] = { v: v, path: path, depth: depth + 1 };
    return '<details' + open + ' data-path="' + esc(path) + '"><summary>' +
      '<span class="tw">▶</span>' + keyHtml +
      '<span class="cnt">' + (arr ? "[" + n + "]" : "{" + n + "}") + "</span></summary>" +
      '<div class="kids" data-kids="' + id + '" data-depth="' + (depth + 1) + '"></div></details>';
  }

  function fillKids(id) {
    var box = document.querySelector('[data-kids="' + id + '"]');
    var info = pending[id];
    if (!box || !info || box.__done) return;
    box.__done = true;
    renderInto(box, info.v, info.path, info.depth);
  }

  function renderInto(box, v, path, depth, from) {
    from = from || 0;
    var arr = Array.isArray(v);
    var keys = arr ? null : Object.keys(v);
    var total = arr ? v.length : keys.length;
    var end = Math.min(total, from + CHUNK);
    var html = "";
    for (var i = from; i < end; i++) {
      if (arr) {
        html += nodeHtml(null, v[i], path + "[" + i + "]", depth);
      } else {
        var k = keys[i];
        html += nodeHtml(k, v[k], path ? path + "." + k : k, depth);
      }
    }
    var tmp = document.createElement("div");
    tmp.innerHTML = html;
    while (tmp.firstChild) box.appendChild(tmp.firstChild);
    var old = box.querySelector(".more");
    if (old) old.remove();
    if (end < total) {
      var b = document.createElement("button");
      b.className = "more";
      b.textContent = "还有 " + (total - end) + " 项，继续展开";
      b.onclick = function (e) {
        e.stopPropagation();
        b.remove();
        renderInto(box, v, path, depth, end);
      };
      box.appendChild(b);
    }
  }

  document.addEventListener("toggle", function (e) {
    var d = e.target;
    if (!d || d.tagName !== "DETAILS" || !d.open) return;
    var kids = d.querySelector("[data-kids]");
    if (kids) fillKids(kids.getAttribute("data-kids"));
  }, true);

  document.addEventListener("click", function (e) {
    var b = e.target.closest && e.target.closest(".expand");
    if (!b) return;
    e.stopPropagation();
    var full = b.getAttribute("data-full");
    var span = b.previousElementSibling;
    if (span) span.textContent = '"' + full + '"';
    b.remove();
  });

  function expandOpen(root) {
    var guard = 0;
    while (guard++ < 200) {
      var todo = [];
      Array.prototype.forEach.call(root.querySelectorAll("[data-kids]"), function (k) {
        if (k.__done) return;
        var d = k.closest("details");
        if (d && d.open) todo.push(k.getAttribute("data-kids"));
      });
      if (!todo.length) break;
      todo.forEach(fillKids);
    }
  }

  /* ---------------- 筛选与搜索 ---------------- */

  var state = {
    q: "",
    layers: {},
    replay: {},
  };

  function countBy(fn) {
    var m = {};
    ROWS.forEach(function (r) {
      var k = fn(r);
      if (k == null) return;
      m[k] = (m[k] || 0) + 1;
    });
    return m;
  }

  function buildFilters() {
    var layers = countBy(function (r) { return r.layer; });
    var rep = { yes: 0, no: 0, unk: 0 };
    ROWS.forEach(function (r) { rep[r.replay] = (rep[r.replay] || 0) + 1; });

    var h = "";
    h += chip("layer", "全部", ROWS.length, true);
    Object.keys(layers).sort(function (a, b) { return layers[b] - layers[a]; })
      .forEach(function (k) {
        h += chip("layer:" + k, layerLabel(k), layers[k], false, "layer", k);
      });
    h += '<span style="flex:0 0 8px"></span>';
    h += chip("replay:yes", replayLabel("yes"), rep.yes || 0, false, "replay", "yes");
    h += chip("replay:no", replayLabel("no"), rep.no || 0, false, "replay", "no");
    h += chip("replay:unk", replayLabel("unk"), rep.unk || 0, false, "replay", "unk");
    filtersEl.innerHTML = h;
  }

  function chip(key, label, n, on, group, val) {
    var cls = "chip" + (on ? " on" : "");
    var data = group ? ' data-g="' + group + '" data-v="' + esc(val) + '"' : ' data-g=""';
    return '<button class="' + cls + '"' + data + '>' + esc(label) +
      ' <span class="n">' + n + "</span></button>";
  }

  filtersEl.addEventListener("click", function (e) {
    var b = e.target.closest(".chip");
    if (!b) return;
    var g = b.getAttribute("data-g"), v = b.getAttribute("data-v");
    if (!g) { state.layers = {}; state.replay = {}; }
    else if (g === "layer") {
      if (state.layers[v]) delete state.layers[v]; else state.layers[v] = 1;
    } else {
      if (state.replay[v]) delete state.replay[v]; else state.replay[v] = 1;
    }
    syncChips();
    render(true);
  });

  function syncChips() {
    var anyLayer = Object.keys(state.layers).length > 0;
    var anyRep = Object.keys(state.replay).length > 0;
    Array.prototype.forEach.call(filtersEl.children, function (b) {
      if (b.tagName !== "BUTTON") return;
      var g = b.getAttribute("data-g"), v = b.getAttribute("data-v");
      var on;
      if (!g) on = !anyLayer && !anyRep;
      else if (g === "layer") on = !!state.layers[v];
      else on = !!state.replay[v];
      b.classList.toggle("on", on);
    });
  }

  function haystack(r) {
    if (r.__h) return r.__h;
    var parts = [r.api, r.intent || "", r.cat || "", r.layer || "", r.ver || ""];
    if (r.req && r.req.text) parts.push(r.req.text.slice(0, 20000));
    if (r.resp && r.resp.text) parts.push(r.resp.text.slice(0, 20000));
    r.__h = parts.join("\n").toLowerCase();
    return r.__h;
  }

  function visible() {
    var q = state.q.toLowerCase().trim();
    var anyLayer = Object.keys(state.layers).length > 0;
    var anyRep = Object.keys(state.replay).length > 0;
    return ROWS.filter(function (r) {
      if (anyLayer && !state.layers[r.layer]) return false;
      if (anyRep && !state.replay[r.replay]) return false;
      if (q && haystack(r).indexOf(q) < 0) return false;
      return true;
    });
  }

  /* ---------------- 列表渲染 ---------------- */

  var RENDER_CAP = 120;
  var cur = [], shown = 0;

  function render(reset) {
    cur = visible();
    if (reset !== false) { listEl.innerHTML = ""; shown = 0; }
    if (!cur.length) {
      listEl.innerHTML = '<div class="empty"><b>没有匹配的接口</b>换个关键词，或点上面的「全部」</div>';
      return;
    }
    appendMore();
  }

  function appendMore() {
    var end = Math.min(cur.length, shown + RENDER_CAP);
    var h = "";
    for (var i = shown; i < end; i++) h += rowHtml(cur[i], i);
    var tmp = document.createElement("div");
    tmp.innerHTML = h;
    while (tmp.firstChild) listEl.appendChild(tmp.firstChild);
    shown = end;
  }

  function rowHtml(r, i) {
    var sel = "";
    var repCls = r.replay === "yes" ? "yes" : r.replay === "no" ? "no" : "unk";
    var nInst = (r.instances && r.instances.length) || 0;
    var nRel = (r.rels && r.rels.length) || 0;
    var tags = '<span class="pill ' + r.layer + '">' + layerLabel(r.layer) + "</span>" +
      '<span class="pill ' + repCls + '">' + replayLabel(r.replay) + "</span>" +
      (r.cat ? '<span class="pill cat">' + esc(r.cat) + "</span>" : "") +
      (nRel ? '<span class="pill rel" title="有关联接口">⇄ ' + nRel + "</span>" : "") +
      (nInst > 1 ? '<span class="pill inst" title="抓包条数">' + nInst + " 次</span>" : "");
    var fold = nInst > 1
      ? '<button class="fold" data-api="' + esc(r.api) + '" title="展开抓包列表">' +
        '<span>▾</span></button>'
      : "";
    return '<div class="row-wrap" data-api="' + esc(r.api) + '">' +
      '<div class="row' + sel + '" data-i="' + i + '">' +
      '<div class="row-badge">' + (i + 1) + "</div>" +
      '<div class="row-body">' +
      '<div class="row-title">' + esc(r.intent || r.api) + "</div>" +
      '<div class="row-api">' + esc(r.api) + "</div>" +
      '<div class="row-tags">' + tags + "</div></div>" +
      '<div class="row-meta"><b>' + fmtBytes(r.respLen) + "</b><br>" +
      (r.hits > 1 ? "×" + r.hits : fmtTime(r.ts)) + "</div>" +
      fold +
      "</div>" +
      '<div class="insts" data-for="' + esc(r.api) + '"></div>' +
      "</div>";
  }

  listEl.addEventListener("scroll", function () {
    if (shown < cur.length && listEl.scrollTop + listEl.clientHeight > listEl.scrollHeight - 400) {
      appendMore();
    }
  });

  // 行点击：对比模式下是勾选，否则打开详情
  listEl.addEventListener("click", function (e) {
    var fold = e.target.closest && e.target.closest(".fold");
    if (fold) {
      e.stopPropagation();
      toggleFold(fold);
      return;
    }
    var row = e.target.closest(".row");
    if (!row) return;
    var r = cur[+row.getAttribute("data-i")];
    if (!r) return;
    openDetail(r);
  });

  /* ---------------- 折叠：展开抓包列表 ---------------- */
  // 每个接口右侧的 ▾ 按钮，展开后列出这几次抓包的实例。
  // 再点一次收起。展开的实例可以单独点开看正文。

  function toggleFold(btn) {
    var api = btn.getAttribute("data-api");
    var box = listEl.querySelector('.insts[data-for="' + cssEsc(api) + '"]');
    if (!box) return;
    var open = btn.classList.toggle("open");
    // 收起时退出该接口的对比态
    if (!open) {
      if (cmp.api === api) exitCmp();
      box.innerHTML = "";
      box.classList.remove("show");
      return;
    }
    box.classList.add("show");
    renderInsts(api, false);
    // 展开后悬浮按钮出现
    if (!cmp.api) { cmp.api = api; refreshFab(); }
  }

  /** 渲染某个接口的实例列表（对比态时带复选框） */
  function renderInsts(api, keepCmp) {
    var box = listEl.querySelector('.insts[data-for="' + cssEsc(api) + '"]');
    if (!box) return;
    var r = ROWS.filter(function (x) { return x.api === api; })[0];
    var list = (r && r.instances) || [];
    if (!list.length) {
      box.innerHTML = '<div class="inst-empty">没有抓到多次请求</div>';
      return;
    }
    var active = cmp.on && cmp.api === api;
    var full = active && cmp.sel.length >= 2;
    var h = "";
    list.forEach(function (it, k) {
      var picked = active && cmp.sel.indexOf(k) >= 0;
      var cb = active ? '<div class="icb' + (picked ? " on" : "") + '"' +
        ((full && !picked) ? ' style="visibility:hidden"' : "") + "></div>" : "";
      h += '<div class="instrow' + (picked ? " picked" : "") +
        ((full && !picked) ? " dim" : "") + '" data-api="' + esc(api) + '" data-k="' + k + '">' +
        cb +
        '<span class="inst-n">#' + (it.i || k + 1) + "</span>" +
        '<span class="inst-t">' + fmtTime(it.ts) + "</span>" +
        '<span class="inst-s">请求 ' + fmtBytes(it.reqLen) + " · 响应 " + fmtBytes(it.respLen) + "</span>" +
        '<span class="inst-go">查看 ›</span></div>';
    });
    box.innerHTML = h;
  }

  function cssEsc(s) { return String(s).replace(/"/g, '\\"'); }

  // 实例点击：对比态下是勾选，否则打开该次抓包的详情
  listEl.addEventListener("click", function (e) {
    var it = e.target.closest && e.target.closest(".instrow");
    if (!it) return;
    e.stopPropagation();
    var api = it.getAttribute("data-api");
    var k = +it.getAttribute("data-k");
    if (cmp.on && cmp.api === api) { toggleInstSel(k); return; }
    var r = ROWS.filter(function (x) { return x.api === api; })[0];
    if (!r || !r.instances) return;
    openInstance(r, r.instances[k], k);
  });

  /* ---------------- 对比 ---------------- */
  /* 对比的是**同一个接口的不同请求**，不是不同接口。
   *
   * 场景：`home.nextfresh` 抓了 12 次，想看看第 1 次和第 7 次的响应差在哪
   * （比如翻页前后字段变化、灰度开关切换）。所以对比模式只在一个接口
   * 展开的实例列表里生效。
   *
   * 交互（用户明确要求）：
   *   点 ▾ 展开实例 → 出现悬浮按钮「对比」→ 点它进入勾选态 →
   *   实例左侧出现复选框 → 选两个 → 其余复选框隐藏、按钮变「对比 2」→
   *   点击执行 → 再点返回回到列表。
   */

  var cmpSide = "resp";   // 对比哪一侧：resp / req

  var cmp = {
    api: null,     // 当前在哪个接口的实例列表里对比
    on: false,     // 是否处于勾选态
    sel: [],       // 已选的实例序号
  };

  function enterCmp(api) {
    cmp.api = api;
    cmp.on = true;
    cmp.sel = [];
    renderInsts(api, true);
    refreshFab();
  }

  function exitCmp() {
    var api = cmp.api;
    cmp.on = false;
    cmp.sel = [];
    cmp.api = null;
    if (api) renderInsts(api, true);
    refreshFab();
  }

  /** 悬浮按钮：只在「展开了某个接口的实例」时出现 */
  function refreshFab() {
    var fab = $("fab");
    if (!cmp.api) {
      fab.classList.remove("on");
      return;
    }
    fab.classList.add("on");
    if (!cmp.on) {
      fab.textContent = "对比";
      fab.classList.remove("ready");
    } else if (cmp.sel.length === 2) {
      fab.textContent = "对比 2";
      fab.classList.add("ready");
    } else {
      fab.textContent = "退出对比";
      fab.classList.remove("ready");
    }
  }

  $("fab").onclick = function () {
    if (!cmp.api) return;
    if (!cmp.on) { enterCmp(cmp.api); return; }
    if (cmp.sel.length === 2) { doCompare(); return; }
    exitCmp();
  };

  function toggleInstSel(k) {
    var i = cmp.sel.indexOf(k);
    if (i >= 0) cmp.sel.splice(i, 1);
    else {
      if (cmp.sel.length >= 2) { toast("最多选两个"); return; }
      cmp.sel.push(k);
    }
    // 选满两个后：其他复选框隐藏，行变暗
    var full = cmp.sel.length >= 2;
    Array.prototype.forEach.call(
      listEl.querySelectorAll('.insts[data-for="' + cssEsc(cmp.api) + '"] .instrow'),
      function (el) {
        var idx = +el.getAttribute("data-k");
        var picked = cmp.sel.indexOf(idx) >= 0;
        el.classList.toggle("picked", picked);
        el.classList.toggle("dim", full && !picked);
        var cb = el.querySelector(".icb");
        if (cb) {
          cb.classList.toggle("on", picked);
          cb.style.visibility = (full && !picked) ? "hidden" : "";
        }
      },
    );
    refreshFab();
  }

  function doCompare() {
    var r = ROWS.filter(function (x) { return x.api === cmp.api; })[0];
    if (!r || !r.instances || !r.instances.length) {
      toast("这个接口没有可对比的抓包实例");
      return;
    }
    if (cmp.sel.length !== 2) {
      toast("先选两个实例");
      return;
    }
    var a = r.instances[cmp.sel[0]];
    var b = r.instances[cmp.sel[1]];
    if (!a || !b) {
      toast("实例序号对不上（" + cmp.sel.join(",") + " / 共 " +
        r.instances.length + " 条）");
      return;
    }
    showCompare(r, a, b, cmp.sel[0], cmp.sel[1]);
  }

  function flat(v, prefix, out, depth) {
    if (depth > 6) { out[prefix] = "…"; return out; }
    if (!isObj(v)) { out[prefix] = typeof v === "string" ? v.slice(0, 80) : v; return out; }
    var arr = Array.isArray(v);
    if (arr) {
      out[prefix + " (数组 " + v.length + ")"] = null;
      for (var i = 0; i < Math.min(v.length, 3); i++) {
        flat(v[i], prefix + "[" + i + "]", out, depth + 1);
      }
      return out;
    }
    var ks = Object.keys(v);
    for (var j = 0; j < ks.length; j++) {
      flat(v[ks[j]], prefix ? prefix + "." + ks[j] : ks[j], out, depth + 1);
    }
    return out;
  }

  /**
   * 对比同一个接口的两次请求。
   *
   * @param r  接口（提供意图名）
   * @param a  实例 A
   * @param b  实例 B
   * @param ka 实例 A 的序号（用于标题）
   * @param kb 实例 B 的序号
   *
   * 默认对比**响应**（那是「这次和上次差在哪」最常看的东西）；
   * 顶部可以切到「请求」—— 想看翻页参数、灰度开关的变化时用。
   */
  function showCompare(r, a, b, ka, kb) {
    var side = cmpSide || "resp";
    var va = side === "req" ? a.req : a.resp;
    var vb = side === "req" ? b.req : b.resp;
    var pa = parsed(va);
    var pb = parsed(vb);
    var fa = pa.kind === "json" ? flat(pa.v, "", {}, 0) : {};
    var fb = pb.kind === "json" ? flat(pb.v, "", {}, 0) : {};
    var keys = Object.keys(fa).concat(Object.keys(fb).filter(function (k) { return !(k in fa); }));
    var stats = { both: 0, onlyA: 0, onlyB: 0, diff: 0 };
    keys.forEach(function (k) {
      var inA = k in fa, inB = k in fb;
      if (inA && inB) {
        stats.both++;
        if (String(fa[k] == null ? "" : fa[k]) !== String(fb[k] == null ? "" : fb[k])) stats.diff++;
      } else if (inA) stats.onlyA++;
      else stats.onlyB++;
    });

    var hd = function (it, k) {
      var len = side === "req" ? it.reqLen : it.respLen;
      return '<div class="cmp-hd"><b>第 ' + (k + 1) + " 次</b><span>" + fmtTime(it.ts) +
        " · " + (side === "req" ? "请求 " : "响应 ") + fmtBytes(len) + "</span></div>";
    };
    var grid = $("cmpGrid");
    grid.innerHTML =
      '<div class="cmp-title">' + esc(r.intent || r.api) +
      '<span class="cmp-api">' + esc(r.api) + "</span></div>" +
      '<div class="cmp-side">' +
      '<button class="sbtn' + (side === "resp" ? " on" : "") + '" data-s="resp">响应</button>' +
      '<button class="sbtn' + (side === "req" ? " on" : "") + '" data-s="req">请求</button>' +
      "</div>" +
      '<div class="cmp-hdrow">' +
      '<div class="cmp-col">' + hd(a, ka) + "</div>" +
      '<div class="cmp-col">' + hd(b, kb) + "</div></div>";

    var body = document.createElement("div");
    body.className = "cmp-body";
    body.innerHTML =
      '<div class="cmp-stat">' +
      '<span class="st"><b>' + stats.both + "</b> 共同字段</span>" +
      '<span class="st diff"><b>' + stats.diff + "</b> 值不同</span>" +
      '<span class="st a"><b>' + stats.onlyA + "</b> 仅左</span>" +
      '<span class="st b"><b>' + stats.onlyB + "</b> 仅右</span>" +
      "</div>" +
      (pa.kind !== "json" || pb.kind !== "json"
        ? '<div class="note warn">有一边不是 JSON，无法做结构对比</div>' : "") +
      (stats.onlyA + stats.onlyB + stats.diff === 0 && pa.kind === "json" && pb.kind === "json"
        ? '<div class="note">两次请求的结构完全相同</div>' : "") +
      '<div id="dfList"></div>';
    grid.appendChild(body);

    var items = [];
    keys.forEach(function (k) {
      var inA = k in fa, inB = k in fb;
      var cls, mark, va = "", vb = "";
      if (inA && inB) {
        var sa = String(fa[k] == null ? "" : fa[k]);
        var sb = String(fb[k] == null ? "" : fb[k]);
        cls = sa === sb ? "d-both" : "d-diff";
        mark = sa === sb ? " " : "≠";
        va = sa; vb = sb;
      } else if (inA) { cls = "d-only"; mark = "−"; va = String(fa[k]); }
      else { cls = "d-new"; mark = "+"; vb = String(fb[k]); }
      items.push({ cls: cls, mark: mark, k: k, va: va, vb: vb });
    });

    var shownN = 0, DF_CHUNK = 60;
    var holder = $("dfList");
    function moreDf() {
      var end = Math.min(items.length, shownN + DF_CHUNK);
      var html = "";
      for (var i = shownN; i < end; i++) {
        var it = items[i];
        html += '<div class="df ' + it.cls + '"><div class="df-k">' + it.mark + " " + esc(it.k) + "</div>";
        if (it.va || it.vb) {
          html += '<div class="df-v">' +
            (it.va ? '<span class="va">' + esc(it.va.slice(0, 120)) + "</span>" : "") +
            (it.vb ? '<span class="vb">' + esc(it.vb.slice(0, 120)) + "</span>" : "") +
            "</div>";
        }
        html += "</div>";
      }
      var tmp = document.createElement("div");
      tmp.innerHTML = html;
      while (tmp.firstChild) holder.appendChild(tmp.firstChild);
      shownN = end;
      var old = holder.querySelector(".more");
      if (old) old.remove();
      if (shownN < items.length) {
        var btn = document.createElement("button");
        btn.className = "more";
        btn.textContent = "还有 " + (items.length - shownN) + " 个字段";
        btn.onclick = moreDf;
        holder.appendChild(btn);
      }
    }
    moreDf();
    body.onscroll = function () {
      if (shownN < items.length &&
          body.scrollTop + body.clientHeight > body.scrollHeight - 300) moreDf();
    };

    // 切换 响应 / 请求
    Array.prototype.forEach.call(grid.querySelectorAll(".sbtn"), function (btn) {
      btn.onclick = function () {
        cmpSide = btn.getAttribute("data-s");
        showCompare(r, a, b, ka, kb);
      };
    });

    pushView("compare");
    $("cmp").classList.add("open");
  }

  /* ---------------- 详情 ---------------- */

  var curRow = null, curInst = null, curTab = "req";

  /**
   * 详情层自己的历史：从「关联接口」跳转时，上一个接口要记住，
   * 这样按返回能回到它，而不是直接关掉整个详情层。
   *
   * 之前只有一层 viewStack，跳转后返回会把 sheet 整个关掉，
   * 用户得重新点一遍原接口 —— 实测发现的 bug。
   */
  var detailStack = [];

  function openDetail(r, fromRel, skipPush) {
    // 从关联跳转：记住当前接口，返回时恢复
    if (fromRel && curRow) detailStack.push(curRow);
    else if (!fromRel) detailStack = [];
    curRow = r;
    curInst = null;
    $("shTitle").textContent = r.intent || r.api;
    $("shApi").textContent = r.api + (r.ver ? "  v" + r.ver : "");
    paintTags(r, null);
    paint();
    // 每次打开详情都压一个 state（包括从关联跳转）。
    // 这样 history 深度和 detailStack 始终一一对应：
    //   返回 → popstate → popView → backDetail 弹出 detailStack 里的上一个；
    //   最后一个 detail 弹完，backDetail 返回 false 但 state 已被消费，
    //   再按返回就是浏览器默认的「退出页面」。
    if (!skipPush) pushView("detail");
    sheetEl.classList.add("open");
  }

  /** 返回上一层详情；没有上一层就关掉详情层 */
  function backDetail() {
    var prev = detailStack.pop();
    if (prev) {
      curRow = prev;
      curInst = null;
      $("shTitle").textContent = prev.intent || prev.api;
      $("shApi").textContent = prev.api + (prev.ver ? "  v" + prev.ver : "");
      paintTags(prev, null);
      paint();
      return true;
    }
    sheetEl.classList.remove("open");
    return false;
  }

  function openInstance(r, inst, k) {
    detailStack = [];
    curRow = r;
    curInst = inst;
    $("shTitle").textContent = (r.intent || r.api) + " · 第 " + (k + 1) + " 次";
    $("shApi").textContent = r.api + "  " + fmtTime(inst.ts);
    paintTags(r, inst);
    paint();
    if (!skipPush) pushView("detail");
    sheetEl.classList.add("open");
  }

  function paintTags(r, inst) {
    var repCls = r.replay === "yes" ? "yes" : r.replay === "no" ? "no" : "unk";
    var nRel = (r.rels && r.rels.length) || 0;
    var h = '<span class="pill ' + r.layer + '">' + layerLabel(r.layer) + "</span>" +
      '<span class="pill ' + repCls + '">' + replayLabel(r.replay) + "</span>" +
      (r.cat ? '<span class="pill cat">' + esc(r.cat) + "</span>" : "") +
      '<span class="pill unk">' + fmtBytes(inst ? inst.respLen : r.respLen) + "</span>";
    if (nRel) {
      h += '<button class="pill relbtn" id="relBtn">⇄ 关联 ' + nRel + " 个接口</button>";
    }
    if (!inst && (r.instances && r.instances.length > 1)) {
      h += '<span class="pill inst">共 ' + r.instances.length + " 次</span>";
    }
    $("shTags").innerHTML = h;
    var rb = $("relBtn");
    if (rb) rb.onclick = function (e) { e.stopPropagation(); openRelSheet(r); };
  }

  function paint() {
    var r = curRow;
    if (!r) return;
    Array.prototype.forEach.call(tabsEl.children, function (b) {
      b.classList.toggle("on", b.getAttribute("data-t") === curTab);
    });
    var src = curInst || r;
    var h = "";
    var doc = API_DOC[r.api];
    if (r.chain) {
      var cn = r.chainCn || "";
      var en = r.chain || "";
      h += '<div class="chain"><b>调用链（内 → 外）</b>' +
        esc(cn || en).replace(/ → /g, ' <i>→</i> ') +
        (cn && en ? '<div class="chain-en">' + esc(en) + "</div>" : "") +
        "</div>";
    }
    if (doc && doc.note) {
      h += '<div class="note">' + esc(doc.note) + "</div>";
    }
    if (curTab === "hdr") {
      h += bodyBlock(r.reqHeaders, "请求头", null);
    } else if (curTab === "req") {
      h += bodyBlock(src.req || r.req, "请求体", doc && doc.reqFields);
    } else {
      h += bodyBlock(src.resp || r.resp, "响应", doc && doc.respFields);
    }
    shBody.innerHTML = h;
    shBody.scrollTop = 0;
    expandOpen(shBody);
  }

  function bodyBlock(b, label, fields) {
    if (!b || b.text == null) return '<div class="empty">没有' + label + "</div>";
    var p = parsed(b);
    if (p.kind === "huge") {
      return '<div class="note warn">正文 ' + fmtBytes(p.len) +
        "，超过 2MB，不做结构解析（防止卡死）。</div>" +
        '<pre class="raw">' + esc(b.text.slice(0, 20000)) + "…</pre>";
    }
    if (p.kind === "text") {
      return '<pre class="raw">' + esc(b.text.slice(0, 20000)) +
        (b.text.length > 20000 ? "…" : "") + "</pre>";
    }
    if (p.kind === "none") return '<div class="empty">没有' + label + "</div>";
    var h = '<div class="tree">';
    if (fields) {
      var keys = Object.keys(fields);
      if (keys.length) {
        h += '<div class="note" style="border-left-color:var(--ok)"><b>本接口已知字段</b><br>' +
          keys.map(function (k) {
            return '<code class="fk">' + esc(k) + "</code> " + esc(fields[k]);
          }).join("<br>") + "</div>";
      }
    }
    h += nodeHtml(null, p.v, "", 0) + "</div>";
    return h;
  }

  tabsEl.addEventListener("click", function (e) {
    var b = e.target.closest(".tab");
    if (!b) return;
    curTab = b.getAttribute("data-t");
    paint();
  });

  /* ---------------- 关联接口浮窗 ---------------- */

  function openRelSheet(r) {
    var box = $("relList");
    var list = r.rels || [];
    $("relTitle").textContent = "与「" + (r.intent || r.api) + "」关联的接口";
    if (!list.length) {
      box.innerHTML = '<div class="empty">没有发现关联</div>';
    } else {
      var h = "";
      list.forEach(function (o, i) {
        var out = o.dir !== "in";
        var other = out ? o.to : o.from;
        var or = ROWS.filter(function (x) { return x.api === other; })[0];
        var nm = or ? (or.intent || other) : other;
        h += '<div class="relrow" data-api="' + esc(other) + '" data-i="' + i + '">' +
          '<div class="rel-ico ' + (out ? "out" : "in") + '">' + (out ? "→" : "←") + "</div>" +
          '<div class="rel-body">' +
          '<div class="rel-name">' + esc(nm) + "</div>" +
          '<div class="rel-api">' + esc(other) + "</div>" +
          '<div class="rel-meta">' +
          '<span class="pill ' + (o.score >= 60 ? "yes" : "unk") + '">可信度 ' + o.score + "</span>" +
          '<span class="rel-field">' + esc(o.field) + " = " + esc(o.evidence || "") + "</span>" +
          "</div></div>" +
          '<div class="rel-go">›</div></div>';
      });
      box.innerHTML = h;
    }
    pushView("rel");
    $("relSheet").classList.add("open");
  }

  $("relList").addEventListener("click", function (e) {
    var row = e.target.closest && e.target.closest(".relrow");
    if (!row) return;
    var api = row.getAttribute("data-api");
    var target = ROWS.filter(function (x) { return x.api === api; })[0];
    if (!target) { toast("这个接口不在本次抓包里"); return; }
    $("relSheet").classList.remove("open");
    // 关键：把栈顶的 "rel" **替换**成 "detail"，而不是再压一层。
    // 否则栈里会残留一个已关闭的 rel 层，用户按返回时它先被 pop 掉
    // 却什么也没关（浮窗早就关了），表现为「点返回没反应」。
    if (viewStack[viewStack.length - 1] === "rel") viewStack.pop();
    openDetail(target, true, true);
    // history 同步：用 replaceState 把当前这条 rel 记录改写成 detail
    try { history.replaceState({ v: "detail" }, ""); } catch (err) { }
  });

  /* ---------------- 返回栈 ---------------- */
  /* 用 history 接管系统返回键：每打开一层 push 一个 state，popstate 时关掉栈顶。
   *
   * 踩过的坑（两个都让页面直接卡死）：
   *   1. 栈空时又调 history.back() → 无限递归 → tab 崩掉。
   *   2. 详情层跳转不压栈，导致 history 深度和内部栈不一致，
   *      返回次数对不上，最后一层永远关不掉。
   *
   * 现在的规矩：**每一层打开都压一次 state，关层只由 popstate 触发**。
   * 界面上的「‹」按钮走 history.back()，不直接改 DOM —— 保证只有一条路径。
   */

  var viewStack = [];
  var popping = false;   // 防止 popstate 里再触发 history 操作
  var lastPop = 0;       // 上次 popstate 的时间，用于合并连击

  function pushView(name) {
    viewStack.push(name);
    try { history.pushState({ v: name }, ""); } catch (e) { }
  }

  /** 关掉栈顶那一层。返回 false 表示已经没层了。 */
  function popView() {
    var top = viewStack.pop();
    if (!top) {
      // 栈空了但界面还有层没关（占位 state 已耗尽）：
      // 直接把最上面那层关掉，别再要求用户点一次没反应的返回。
      if ($("relSheet").classList.contains("open")) {
        $("relSheet").classList.remove("open");
        return true;
      }
      if ($("cmp").classList.contains("open")) {
        $("cmp").classList.remove("open");
        return true;
      }
      if (sheetEl.classList.contains("open")) {
        detailStack = [];
        sheetEl.classList.remove("open");
        return true;
      }
      return false;
    }
    if (top === "rel") $("relSheet").classList.remove("open");
    else if (top === "compare") $("cmp").classList.remove("open");
    else if (top === "detail") backDetail();
    return true;
  }

  window.addEventListener("popstate", function () {
    if (popping) return;
    popping = true;
    try {
      // 栈空时什么都不做：所有层都关了，再返回就该退出页面，
      // 那是浏览器的默认行为，我们不要插手（插手就是死循环）。
      popView();
      lastPop = Date.now();
    } finally {
      popping = false;
    }
  });

  function goBack() {
    if (!viewStack.length) return;
    // 连点保护：popstate 处理有延迟，短时间内重复触发会被浏览器合并，
    // 表现为「点两下只退一层」。80ms 内忽略重复点击。
    if (Date.now() - lastPop < 80) return;
    history.back();
  }

  $("back").onclick = goBack;
  $("cmpBack").onclick = goBack;
  $("relBack").onclick = goBack;

  try { history.replaceState({ v: "root" }, ""); } catch (e) { }

  // 调试钩子：只在 file:// 或本地打开时挂，正式上报也可以留着（无副作用）
  window.__xy = {
    stack: function () { return viewStack.slice(); },
    depth: function () { return history.length; },
    state: function () { return history.state; },
  };

  /* ---------------- 搜索框 ---------------- */

  var qT = 0;
  qEl.addEventListener("input", function () {
    var v = qEl.value;
    $("searchWrap").classList.toggle("has", !!v);
    clearTimeout(qT);
    qT = setTimeout(function () {
      state.q = v;
      render(true);
    }, 130);
  });
  $("clr").onclick = function () {
    qEl.value = ""; state.q = "";
    $("searchWrap").classList.remove("has");
    render(true);
  };

  /* ---------------- 启动 ---------------- */

  $("stat").textContent = ROWS.length + " 个接口";
  $("sub").textContent = (DATA.meta && DATA.meta.sub) || "";
  buildFilters();
  render(true);
})();
