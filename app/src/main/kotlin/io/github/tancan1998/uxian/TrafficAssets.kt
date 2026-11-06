package io.github.tancan1998.uxian

/**
 * 前端资源常量。**由 tools/embed_assets.py 自动生成，不要手改**。
 *
 * 源文件：app/src/main/assets/{traffic.html, traffic.js, api_doc.json}
 */
object TrafficAssets {

    /** 抓包报告模板，__DATA__ / __JS__ 是占位符 */
    @JvmField val HTML: String = """<!doctype html>
<html lang="zh-CN" data-theme="dark">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="dark light">
<title>U闲 · 抓包分析</title>
<style>
:root{
  --bg:#0b0c0e; --bg2:#141619; --bg3:#1b1e23; --bg4:#22262c;
  --line:#282c33; --line2:#343a43;
  --fg:#e9ecf1; --fg2:#9aa3b0; --fg3:#646d7b;
  --accent:#FEE512; --ok:#3ddc97; --warn:#fbbf24; --bad:#ff6b6b;
  --kt:#a78bfa; --java:#4cc2ff; --h5:#ff9f45; --weex:#f472b6; --fl:#54c5f8;
  --sdk:#9aa3b0;
  --r:14px; --r2:10px;
  --mono:ui-monospace,SFMono-Regular,"SF Mono",Menlo,Consolas,monospace;
  --sans:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Noto Sans CJK SC","Microsoft YaHei",sans-serif;
}
html[data-theme="light"]{
  --bg:#f6f7f9; --bg2:#fff; --bg3:#f0f2f5; --bg4:#e6e9ee;
  --line:#e2e6ec; --line2:#d0d6de;
  --fg:#12151a; --fg2:#5a636f; --fg3:#8d96a3;
  --accent:#d9a800;
}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{height:100%;margin:0;overflow:hidden}
body{
  background:var(--bg);color:var(--fg);font-family:var(--sans);
  font-size:14px;line-height:1.5;overscroll-behavior:none;
  display:flex;flex-direction:column;
}
#app{display:flex;flex-direction:column;height:100%;min-height:0}

/* ---------- 顶栏 ---------- */
header{
  flex:0 0 auto;padding:14px 16px 10px;background:var(--bg);
  border-bottom:1px solid var(--line);position:relative;z-index:5;
}
.hd-top{display:flex;align-items:baseline;gap:10px}
.hd-title{font-size:17px;font-weight:650;letter-spacing:.3px}
.hd-title em{font-style:normal;color:var(--accent)}
.hd-stat{margin-left:auto;font-size:11.5px;color:var(--fg3);font-variant-numeric:tabular-nums}
.hd-sub{margin-top:3px;font-size:11.5px;color:var(--fg3);font-variant-numeric:tabular-nums}

.search{position:relative;margin-top:11px}
.search input{
  width:100%;height:38px;padding:0 34px 0 34px;border-radius:var(--r2);
  border:1px solid var(--line);background:var(--bg3);color:var(--fg);
  font-size:13.5px;font-family:var(--sans);outline:none;
  transition:border-color .15s,background .15s;
}
.search input:focus{border-color:var(--accent);background:var(--bg4)}
.search input::placeholder{color:var(--fg3)}
.search .ico{position:absolute;left:11px;top:50%;transform:translateY(-50%);color:var(--fg3);font-size:13px;pointer-events:none}
.search .clr{
  position:absolute;right:8px;top:50%;transform:translateY(-50%);
  width:22px;height:22px;border:0;border-radius:50%;background:var(--line2);
  color:var(--fg2);font-size:13px;line-height:1;cursor:pointer;display:none;
  align-items:center;justify-content:center;
}
.search.has .clr{display:flex}

/* ---------- 筛选 ---------- */
.filters{
  flex:0 0 auto;display:flex;gap:6px;overflow-x:auto;padding:9px 16px 10px;
  background:var(--bg);border-bottom:1px solid var(--line);
  scrollbar-width:none;
}
.filters::-webkit-scrollbar{display:none}
.chip{
  flex:0 0 auto;height:26px;padding:0 11px;border-radius:13px;border:1px solid var(--line2);
  background:transparent;color:var(--fg2);font-size:12px;font-family:var(--sans);
  cursor:pointer;white-space:nowrap;transition:all .14s;display:flex;align-items:center;gap:5px;
}
.chip:hover{background:var(--bg3);color:var(--fg)}
.chip.on{background:var(--accent);border-color:var(--accent);color:#000;font-weight:600}
.chip .n{font-size:10.5px;opacity:.65;font-variant-numeric:tabular-nums}
.chip.on .n{opacity:.7}

/* ---------- 列表 ---------- */
main{flex:1 1 auto;min-height:0;overflow-y:auto;overflow-x:hidden;-webkit-overflow-scrolling:touch;padding:8px 10px 40px}
main::-webkit-scrollbar{width:5px}
main::-webkit-scrollbar-thumb{background:var(--line2);border-radius:3px}

.row{
  display:flex;gap:10px;align-items:flex-start;
  padding:11px 12px;margin-bottom:6px;border-radius:var(--r);
  background:var(--bg2);border:1px solid var(--line);cursor:pointer;
  transition:border-color .14s,background .14s,transform .1s;
}
.row:hover{border-color:var(--line2);background:var(--bg3)}
.row:active{transform:scale(.994)}
.row.sel{border-color:var(--accent);background:var(--bg3)}
.row-badge{
  flex:0 0 auto;width:26px;height:26px;border-radius:8px;display:flex;
  align-items:center;justify-content:center;font-size:12px;font-weight:700;
  background:var(--bg4);color:var(--fg2);font-family:var(--mono);
}
.row-body{flex:1 1 auto;min-width:0}
.row-title{font-size:14px;font-weight:560;line-height:1.35;display:flex;align-items:center;gap:6px;flex-wrap:wrap}
.row-api{
  font-family:var(--mono);font-size:11px;color:var(--fg3);margin-top:3px;
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap;
}
.row-tags{display:flex;gap:4px;flex-wrap:wrap;margin-top:7px}
.row-meta{
  flex:0 0 auto;text-align:right;font-size:11px;color:var(--fg3);
  font-variant-numeric:tabular-nums;line-height:1.45;padding-top:1px;
}
.row-meta b{color:var(--fg2);font-weight:550}

.pill{
  height:18px;padding:0 6px;border-radius:5px;font-size:10.5px;font-weight:600;
  display:inline-flex;align-items:center;line-height:1;letter-spacing:.2px;
  border:1px solid transparent;white-space:nowrap;
}
.pill.kt{color:var(--kt);background:rgba(167,139,250,.13);border-color:rgba(167,139,250,.3)}
.pill.java{color:var(--java);background:rgba(76,194,255,.13);border-color:rgba(76,194,255,.3)}
.pill.h5{color:var(--h5);background:rgba(255,159,69,.13);border-color:rgba(255,159,69,.3)}
.pill.weex{color:var(--weex);background:rgba(244,114,182,.13);border-color:rgba(244,114,182,.3)}
.pill.fl{color:var(--fl);background:rgba(84,197,248,.13);border-color:rgba(84,197,248,.3)}
.pill.sdk{color:#9aa3b0;background:rgba(154,163,176,.12);border-color:rgba(154,163,176,.28)}
.pill.yes{color:var(--ok);background:rgba(61,220,151,.12);border-color:rgba(61,220,151,.3)}
.pill.no{color:var(--bad);background:rgba(255,107,107,.12);border-color:rgba(255,107,107,.3)}
.pill.unk{color:var(--fg3);background:var(--bg4);border-color:var(--line2)}
.pill.cat{color:var(--fg2);background:var(--bg4);border-color:var(--line2);font-weight:500}

.empty{padding:60px 20px;text-align:center;color:var(--fg3);font-size:13px}
.empty b{display:block;font-size:15px;color:var(--fg2);margin-bottom:6px;font-weight:600}

/* ---------- 详情抽屉 ---------- */
#sheet{
  position:fixed;inset:0;z-index:50;background:var(--bg);
  display:flex;flex-direction:column;
  transform:translateY(100%);transition:transform .26s cubic-bezier(.32,.72,0,1);
  will-change:transform;
}
#sheet.open{transform:translateY(0)}
.sh-hd{
  flex:0 0 auto;padding:12px 14px 0;border-bottom:1px solid var(--line);background:var(--bg);
}
.sh-top{display:flex;align-items:center;gap:10px}
.back{
  flex:0 0 auto;width:30px;height:30px;border-radius:9px;border:1px solid var(--line2);
  background:var(--bg3);color:var(--fg);font-size:15px;cursor:pointer;
  display:flex;align-items:center;justify-content:center;
}
.back:active{background:var(--bg4)}
.sh-title{flex:1 1 auto;min-width:0;font-size:15px;font-weight:600;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sh-api{font-family:var(--mono);font-size:11px;color:var(--fg3);margin-top:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.sh-tags{display:flex;gap:4px;flex-wrap:wrap;margin-top:9px}
.tabs{display:flex;gap:2px;margin-top:11px}
.tab{
  padding:9px 13px;border:0;background:transparent;color:var(--fg3);
  font-size:13px;font-family:var(--sans);cursor:pointer;position:relative;
  border-radius:8px 8px 0 0;font-weight:500;
}
.tab.on{color:var(--fg);font-weight:600}
.tab.on::after{content:"";position:absolute;left:13px;right:13px;bottom:-1px;height:2px;background:var(--accent);border-radius:2px}
.sh-body{flex:1 1 auto;min-height:0;overflow:auto;-webkit-overflow-scrolling:touch;padding:12px 14px 60px}
.sh-body::-webkit-scrollbar{width:5px}
.sh-body::-webkit-scrollbar-thumb{background:var(--line2);border-radius:3px}

.note{
  font-size:12px;color:var(--fg2);background:var(--bg3);border:1px solid var(--line);
  border-left:3px solid var(--accent);border-radius:0 var(--r2) var(--r2) 0;
  padding:9px 11px;margin-bottom:12px;line-height:1.55;
}
.note.warn{border-left-color:var(--bad)}
.chain{font-family:var(--mono);font-size:11px;color:var(--fg2);line-height:1.65;
  background:var(--bg3);border:1px solid var(--line);border-radius:var(--r2);
  padding:9px 11px;margin-bottom:12px;word-break:break-all}
.chain b{display:block;font-family:var(--sans);font-size:11px;color:var(--fg3);
  font-weight:600;margin-bottom:4px;letter-spacing:.2px}
.chain i{font-style:normal;color:var(--accent)}
.chain-en{margin-top:6px;padding-top:6px;border-top:1px dashed var(--line2);
  font-size:10.5px;color:var(--fg3);word-break:break-all}

/* ---------- JSON 树 ---------- */
.tree{font-family:var(--mono);font-size:12.5px;line-height:1.65}
.tree details{margin:0}
.tree summary{
  list-style:none;cursor:pointer;display:flex;align-items:baseline;gap:6px;
  padding:1.5px 0;border-radius:5px;
}
.tree summary::-webkit-details-marker{display:none}
.tree summary:hover{background:var(--bg3)}
.tw{display:inline-block;width:11px;color:var(--fg3);font-size:9px;transition:transform .12s;flex:0 0 11px}
details[open]>summary>.tw{transform:rotate(90deg)}
.k{color:#8fd3ff;word-break:break-all}
html[data-theme="light"] .k{color:#0369a1}
.colon{color:var(--fg3)}
.s{color:#c3e88d;word-break:break-all;white-space:pre-wrap}
html[data-theme="light"] .s{color:#2e7d32}
.n{color:#f78c6c}
.b{color:#c792ea}
.nul{color:var(--fg3);font-style:italic}
.idx{color:var(--fg3);font-size:11px}
.cnt{color:var(--fg3);font-size:11px;margin-left:2px}
.fn{color:var(--fg3);font-size:11px;margin-left:6px;font-family:var(--sans);opacity:.85;font-style:normal}
.kids{padding-left:15px;border-left:1px solid var(--line);margin-left:5px}
.more{
  margin:4px 0 4px 16px;padding:3px 9px;border-radius:6px;border:1px dashed var(--line2);
  background:transparent;color:var(--fg2);font-size:11.5px;cursor:pointer;font-family:var(--sans);
}
.more:hover{background:var(--bg3);color:var(--fg)}
.expand{color:var(--accent);cursor:pointer;font-size:11px;margin-left:5px;font-family:var(--sans);border:0;background:none;padding:0}
.big{color:var(--warn);font-size:11px;margin-left:6px;font-family:var(--sans)}

/* ---------- 对比 ---------- */
#cmp{
  position:fixed;inset:0;z-index:60;background:var(--bg);display:none;
  flex-direction:column;
}
#cmp.open{display:flex}
.cmp-grid{flex:1 1 auto;min-height:0;display:flex;flex-direction:column;overflow:hidden}
.cmp-title{padding:11px 14px 0;font-size:14px;font-weight:600}
.cmp-title .cmp-api{display:block;font-family:var(--mono);font-size:10.5px;color:var(--fg3);
  font-weight:400;margin-top:3px;word-break:break-all}
.cmp-side{display:flex;gap:6px;padding:9px 14px 0}
.sbtn{
  height:27px;padding:0 13px;border-radius:14px;border:1px solid var(--line2);
  background:var(--bg3);color:var(--fg2);font-size:12px;font-family:var(--sans);cursor:pointer;
}
.sbtn.on{background:var(--accent);border-color:var(--accent);color:#000;font-weight:650}
.cmp-hdrow{display:flex;flex:0 0 auto;border-bottom:1px solid var(--line)}
.cmp-col{flex:1 1 0;min-width:0}
.cmp-hd{padding:9px 12px;background:var(--bg2);font-size:11.5px;border-right:1px solid var(--line)}
.cmp-col:last-child .cmp-hd{border-right:0}
.cmp-hd b{display:block;font-size:12.5px;margin-bottom:2px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.cmp-hd span{font-family:var(--mono);color:var(--fg3);font-size:10.5px;word-break:break-all}
.cmp-body{flex:1 1 auto;min-height:0;overflow:auto;padding:10px 12px 60px;font-size:12px}
.cmp-body::-webkit-scrollbar{width:5px}
.cmp-body::-webkit-scrollbar-thumb{background:var(--line2);border-radius:3px}
.cmp-stat{display:flex;gap:8px;margin-bottom:10px;flex-wrap:wrap}
.cmp-stat .st{font-size:11px;color:var(--fg3);background:var(--bg3);border:1px solid var(--line);
  border-radius:7px;padding:4px 9px}
.cmp-stat .st b{color:var(--fg);font-size:12.5px;margin-right:3px;font-variant-numeric:tabular-nums}
.cmp-stat .st.diff b{color:var(--warn)}
.cmp-stat .st.a b{color:var(--ok)}
.cmp-stat .st.b b{color:var(--bad)}
.df{padding:5px 0;border-bottom:1px solid var(--line)}
.df:last-child{border-bottom:0}
.df-k{font-family:var(--mono);font-size:11.5px;word-break:break-all}
.df-v{display:flex;gap:8px;margin-top:2px}
.df-v span{flex:1 1 0;min-width:0;font-family:var(--mono);font-size:11px;
  padding:3px 6px;border-radius:5px;word-break:break-all;max-height:3.4em;overflow:hidden}
.df-v .va{background:rgba(61,220,151,.08);color:#9fe8c6}
.df-v .vb{background:rgba(255,107,107,.08);color:#ffb3b3}
.d-only .df-k{color:var(--ok)}
.d-new .df-k{color:var(--bad)}
.d-diff .df-k{color:var(--warn)}
.d-both{opacity:.55}
.legend{display:flex;gap:12px;padding:7px 14px;border-top:1px solid var(--line);font-size:11px;color:var(--fg3);background:var(--bg2)}
.legend i{font-style:normal;display:inline-flex;align-items:center;gap:4px}
.legend i::before{content:"";width:7px;height:7px;border-radius:2px;background:currentColor}

.fab{
  position:fixed;right:16px;bottom:calc(20px + env(safe-area-inset-bottom));z-index:40;
  height:42px;padding:0 18px;border-radius:21px;border:0;background:var(--accent);color:#000;
  font-size:13.5px;font-weight:650;font-family:var(--sans);cursor:pointer;
  box-shadow:0 6px 22px rgba(254,229,18,.28);display:none;align-items:center;gap:7px;
}
.fab.on{display:flex}
.fab.ready{box-shadow:0 6px 26px rgba(254,229,18,.5)}
.fab:active{transform:scale(.96)}
.toast{
  position:fixed;left:50%;bottom:calc(78px + env(safe-area-inset-bottom));transform:translateX(-50%) translateY(10px);
  background:var(--bg4);border:1px solid var(--line2);color:var(--fg);
  padding:9px 15px;border-radius:10px;font-size:12.5px;z-index:99;opacity:0;
  transition:opacity .2s,transform .2s;pointer-events:none;max-width:86vw;
}

/* ---------- 工具栏 ---------- */
/* ---------- 实例复选框（同一接口的多次请求之间对比） ---------- */
.icb{
  flex:0 0 auto;width:17px;height:17px;border-radius:5px;border:1.5px solid var(--line2);
  position:relative;transition:all .13s;
}
.icb.on{background:var(--accent);border-color:var(--accent)}
.icb.on::after{
  content:"";position:absolute;left:4px;top:0px;width:4px;height:9px;
  border:solid #000;border-width:0 2px 2px 0;transform:rotate(45deg);
}
.instrow.picked{border-color:var(--accent);background:var(--bg3)}
.instrow.dim{opacity:.3;pointer-events:none}

/* ---------- 折叠按钮 ---------- */
.fold{
  flex:0 0 auto;width:24px;height:24px;border-radius:7px;border:1px solid var(--line2);
  background:var(--bg3);color:var(--fg3);font-size:11px;cursor:pointer;
  display:flex;align-items:center;justify-content:center;transition:all .14s;
  margin-top:1px;padding:0;
}
.fold:hover{background:var(--bg4);color:var(--fg)}
.fold span{transition:transform .18s;display:block}
.fold.open span{transform:rotate(180deg)}
.fold.open{background:var(--accent);border-color:var(--accent);color:#000}

/* ---------- 抓包实例列表 ---------- */
/* 实例列表：左边缘必须和接口行对齐。
   接口行是 padding 12px + 26px 徽章 + 10px 间距 → 正文从 48px 开始。
   实例行用同样的 12px 内边距 + 26px 的 #n 列 + 10px 间距，
   这样 #n 正好压在徽章下面，时间/大小与接口标题成一列。 */
.insts{display:none;margin:0 0 6px}
.insts.show{display:block}
/* 类名用 instrow，不能用 inst —— 接口行里已经有个 .pill.inst（「6 次」标签），
   同名会导致选择器抓错元素（实测踩过，量出来位置完全不对）。 */
.instrow{
  display:flex;align-items:center;gap:10px;padding:7px 12px;margin-top:4px;
  border-radius:9px;background:var(--bg2);border:1px solid var(--line);
  font-size:12px;cursor:pointer;transition:all .13s;
}
.instrow:hover{border-color:var(--accent);background:var(--bg3)}
.inst-n{
  flex:0 0 26px;width:26px;text-align:left;
  font-family:var(--mono);color:var(--accent);font-weight:650;font-size:11px;
  font-variant-numeric:tabular-nums;
}
.inst-t{font-family:var(--mono);color:var(--fg2);font-variant-numeric:tabular-nums;
  flex:0 0 auto}
.inst-s{color:var(--fg3);font-size:11px;margin-left:auto;white-space:nowrap}
.inst-go{color:var(--fg3);font-size:11px;flex:0 0 auto}
.inst-empty{padding:8px 10px;color:var(--fg3);font-size:12px}

/* ---------- 关联浮窗 ---------- */
#relSheet{
  position:fixed;inset:0;z-index:70;background:var(--bg);
  display:flex;flex-direction:column;
  transform:translateY(100%);transition:transform .24s cubic-bezier(.32,.72,0,1);
}
#relSheet.open{transform:translateY(0)}
.relrow{
  display:flex;align-items:center;gap:11px;padding:11px 12px;margin-bottom:6px;
  border-radius:var(--r);background:var(--bg2);border:1px solid var(--line);
  cursor:pointer;transition:all .13s;
}
.relrow:hover{border-color:var(--accent);background:var(--bg3)}
.rel-ico{
  flex:0 0 auto;width:26px;height:26px;border-radius:8px;display:flex;
  align-items:center;justify-content:center;font-size:13px;font-weight:700;
}
.rel-ico.out{background:rgba(76,194,255,.14);color:var(--java)}
.rel-ico.in{background:rgba(61,220,151,.14);color:var(--ok)}
.rel-body{flex:1 1 auto;min-width:0}
.rel-name{font-size:13.5px;font-weight:560;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.rel-api{font-family:var(--mono);font-size:10.5px;color:var(--fg3);
  overflow:hidden;text-overflow:ellipsis;white-space:nowrap;margin-top:2px}
.rel-meta{display:flex;gap:6px;align-items:center;margin-top:6px;flex-wrap:wrap}
.rel-field{font-family:var(--mono);font-size:10.5px;color:var(--fg3);word-break:break-all}
.rel-go{color:var(--fg3);font-size:16px;flex:0 0 auto}

.pill.rel{color:#8fd3ff;background:rgba(143,211,255,.12);border-color:rgba(143,211,255,.3)}
.pill.inst{color:var(--fg2);background:var(--bg4);border-color:var(--line2)}
.pill.relbtn{
  cursor:pointer;color:#8fd3ff;background:rgba(143,211,255,.12);
  border-color:rgba(143,211,255,.3);font-family:var(--sans);
}
.pill.relbtn:hover{background:rgba(143,211,255,.22)}
pre.raw{white-space:pre-wrap;word-break:break-all;font-size:12px;line-height:1.6;
  font-family:var(--mono);color:var(--fg2)}
.fk{color:#8fd3ff}
.toast.on{opacity:1;transform:translateX(-50%) translateY(0)}

/* ---------- 桌面端：限宽居中，避免一行拉得过长 ---------- */
@media (min-width:760px){
  header,.filters{padding-left:max(24px,calc((100vw - 980px)/2));padding-right:max(24px,calc((100vw - 980px)/2))}
  main{padding-left:max(16px,calc((100vw - 980px)/2));padding-right:max(16px,calc((100vw - 980px)/2))}
  body{font-size:14.5px}
  .row{padding:13px 16px;margin-bottom:7px}
  .row:hover{transform:translateX(2px)}
  .row:hover .row-title{color:var(--accent)}
  .sh-hd,.sh-body{padding-left:max(24px,calc((100vw - 900px)/2));padding-right:max(24px,calc((100vw - 900px)/2))}
  .tabs{padding-left:max(0px,calc((100vw - 900px)/2 - 13px))}
  .fab{right:max(24px,calc((100vw - 980px)/2))}
  .search input{height:40px;font-size:14px}
  #relSheet .sh-hd,#relSheet .sh-body{padding-left:max(24px,calc((100vw - 900px)/2));padding-right:max(24px,calc((100vw - 900px)/2))}
  .tree{font-size:13px}
  .cmp-hdrow,.cmp-body{padding-left:max(24px,calc((100vw - 980px)/2));padding-right:max(24px,calc((100vw - 980px)/2))}
  .legend{padding-left:max(24px,calc((100vw - 980px)/2))}
}
/* 超宽屏：列表两栏，信息密度更高 */
@media (min-width:1280px){
  main{display:grid;grid-template-columns:1fr 1fr;gap:0 8px;align-content:start}
  .row{margin-bottom:7px}
  .empty{grid-column:1/-1}
}
</style>
</head>
<body>
<div id="app">
  <header>
    <div class="hd-top">
      <div class="hd-title">U闲 · <em>抓包</em></div>
      <div class="hd-stat" id="stat"></div>
    </div>
    <div class="hd-sub" id="sub"></div>
    <div class="search" id="searchWrap">
      <span class="ico">⌕</span>
      <input id="q" type="search" placeholder="搜接口、意图、字段值…" autocomplete="off" spellcheck="false">
      <button class="clr" id="clr" aria-label="清除">✕</button>
    </div>
  </header>
  <div class="filters" id="filters"></div>
  <main id="list"></main>
</div>
<button class="fab" id="fab">对比</button>
<div id="relSheet">
  <div class="sh-hd">
    <div class="sh-top">
      <button class="back" id="relBack">‹</button>
      <div class="sh-title" id="relTitle">关联接口</div>
    </div>
  </div>
  <div class="sh-body" id="relList"></div>
</div>
<div id="sheet">
  <div class="sh-hd">
    <div class="sh-top">
      <button class="back" id="back">‹</button>
      <div style="flex:1 1 auto;min-width:0">
        <div class="sh-title" id="shTitle"></div>
        <div class="sh-api" id="shApi"></div>
      </div>
    </div>
    <div class="sh-tags" id="shTags"></div>
    <div class="tabs" id="tabs">
      <button class="tab on" data-t="req">请求参数</button>
      <button class="tab" data-t="hdr">请求头</button>
      <button class="tab" data-t="resp">响应</button>
    </div>
  </div>
  <div class="sh-body" id="shBody"></div>
</div>
<div id="cmp">
  <div class="sh-hd">
    <div class="sh-top">
      <button class="back" id="cmpBack">‹</button>
      <div class="sh-title">结构对比</div>
    </div>
  </div>
  <div class="cmp-grid" id="cmpGrid"></div>
  <div class="legend">
    <i style="color:var(--ok)">仅左侧有</i>
    <i style="color:var(--bad)">仅右侧有</i>
    <i style="color:var(--fg2)">两边都有</i>
  </div>
</div>
<div class="toast" id="toast"></div>
<script id="D" type="application/json">__DATA__</script>
<script>
__JS__
</script>
</body>
</html>
"""

    /** 报告的前端脚本 */
    @JvmField val JS: String = """/* U闲 抓包分析器 —— 纯前端，无依赖。
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

  var ${'$'} = function (id) { return document.getElementById(id); };
  var listEl = ${'$'}("list"), filtersEl = ${'$'}("filters"), qEl = ${'$'}("q");
  var sheetEl = ${'$'}("sheet"), shBody = ${'$'}("shBody"), tabsEl = ${'$'}("tabs");

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
    var t = ${'$'}("toast");
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
    var fab = ${'$'}("fab");
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

  ${'$'}("fab").onclick = function () {
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
    var grid = ${'$'}("cmpGrid");
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
    var holder = ${'$'}("dfList");
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
    ${'$'}("cmp").classList.add("open");
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
    ${'$'}("shTitle").textContent = r.intent || r.api;
    ${'$'}("shApi").textContent = r.api + (r.ver ? "  v" + r.ver : "");
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
      ${'$'}("shTitle").textContent = prev.intent || prev.api;
      ${'$'}("shApi").textContent = prev.api + (prev.ver ? "  v" + prev.ver : "");
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
    ${'$'}("shTitle").textContent = (r.intent || r.api) + " · 第 " + (k + 1) + " 次";
    ${'$'}("shApi").textContent = r.api + "  " + fmtTime(inst.ts);
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
    ${'$'}("shTags").innerHTML = h;
    var rb = ${'$'}("relBtn");
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
    var box = ${'$'}("relList");
    var list = r.rels || [];
    ${'$'}("relTitle").textContent = "与「" + (r.intent || r.api) + "」关联的接口";
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
    ${'$'}("relSheet").classList.add("open");
  }

  ${'$'}("relList").addEventListener("click", function (e) {
    var row = e.target.closest && e.target.closest(".relrow");
    if (!row) return;
    var api = row.getAttribute("data-api");
    var target = ROWS.filter(function (x) { return x.api === api; })[0];
    if (!target) { toast("这个接口不在本次抓包里"); return; }
    ${'$'}("relSheet").classList.remove("open");
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
      if (${'$'}("relSheet").classList.contains("open")) {
        ${'$'}("relSheet").classList.remove("open");
        return true;
      }
      if (${'$'}("cmp").classList.contains("open")) {
        ${'$'}("cmp").classList.remove("open");
        return true;
      }
      if (sheetEl.classList.contains("open")) {
        detailStack = [];
        sheetEl.classList.remove("open");
        return true;
      }
      return false;
    }
    if (top === "rel") ${'$'}("relSheet").classList.remove("open");
    else if (top === "compare") ${'$'}("cmp").classList.remove("open");
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

  ${'$'}("back").onclick = goBack;
  ${'$'}("cmpBack").onclick = goBack;
  ${'$'}("relBack").onclick = goBack;

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
    ${'$'}("searchWrap").classList.toggle("has", !!v);
    clearTimeout(qT);
    qT = setTimeout(function () {
      state.q = v;
      render(true);
    }, 130);
  });
  ${'$'}("clr").onclick = function () {
    qEl.value = ""; state.q = "";
    ${'$'}("searchWrap").classList.remove("has");
    render(true);
  };

  /* ---------------- 启动 ---------------- */

  ${'$'}("stat").textContent = ROWS.length + " 个接口";
  ${'$'}("sub").textContent = (DATA.meta && DATA.meta.sub) || "";
  buildFilters();
  render(true);
})();
"""

    /** 接口词典（意图/字段说明/分类） */
    @JvmField val API_DOC: String = """{"apiDoc":{"mtop.taobao.idlehome.home.nextfresh":{"intent":"首页推荐信息流","cat":"信息流","layer":"kt","note":"首页主信息流。sections 是卡片数组，每张卡片的 data 里含商品标题、价格、卖家、省份。也是本项目过滤推广的唯一挂点（宿主直接读这个 JSONObject）。","respFields":{"sections":"卡片数组，每项 data 是一张卡","feedsCount":"本次返回的卡片数","homeTopList":"金刚位（顶部 20 个圆形入口）","nextPage":"下一页游标"}},"mtop.taobao.idlehome.home.circle.list":{"intent":"首页顶部 tab 列表","cat":"信息流","layer":"kt","note":"headList 是顶部 4 个大 tab（首页/集市/圈子/回收），circleList 是横向滚动的 27 个圈子分类。","respFields":{"headList":"顶部主 tab","circleList":"圈子分类列表","selectedCircleId":"当前选中的圈子 id"}},"mtop.taobao.idlemessage.session.sync":{"intent":"消息会话列表同步","cat":"消息","layer":"kt","note":"消息页的主接口。sessions 每项的 session.sessionType 决定会话类型：1=真人私聊，3/10/23/24/25/62/63=各类官方号。","respFields":{"sessions":"会话数组","hasMore":"是否还有更多"}},"mtop.idle.user.page.my.adapter":{"intent":"「我的」页顶部结构","cat":"我的","layer":"kt","note":"决定「我的」页头部卡片内容（头像/鱼力值/设置/客服）。U闲 的入口就是注入在这里。","respFields":{"base":"头部卡片数据","ability":"该页能力开关"}},"mtop.taobao.idlecommerce.splash.ads":{"intent":"开屏广告（同步）","cat":"广告","layer":"java","note":"返回 ads/splashAdList。被 U闲 清空后 SDK 拿不到素材就不展示。","respFields":{"ads":"广告列表","splashAdList":"开屏广告列表"}},"mtop.taobao.idlecommerce.splash.async.ads":{"intent":"开屏广告（异步预拉）","cat":"广告","layer":"h5","note":"和同步接口一样被清空；异步那条走 H5 容器。"},"mtop.taobao.idlemtopsearch.search":{"intent":"搜索结果（主接口）","cat":"搜索","layer":"fl","note":"搜索页由 Flutter 渲染（libapp.so 54MB），Java 层拿不到结果，所以这个接口的响应改了也不会反映到页面。从外部重发会被服务端拒（FAIL_BIZ_COMMON_PARAM_ILLEGAL）—— 依赖宿主私有上下文。"},"mtop.taobao.idlemtopsearch.search.discover":{"intent":"分类推荐流（搜索发现页）","cat":"搜索","layer":"fl","note":"参数极简，外部可调通，返回 61KB。"},"mtop.taobao.idlemtopsearch.search.suggest":{"intent":"搜索联想词","cat":"搜索","layer":"kt"},"mtop.taobao.idle.item.resell.channel":{"intent":"回收频道商品列表","cat":"回收","layer":"h5","note":"「卖闲置/回收」页的数据源，走 H5 容器（referer 是 h5.m.goofish.com）。"},"mtop.taobao.idle.user.strategy.list":{"intent":"首页运营位策略","cat":"运营","layer":"kt","note":"决定首页的闲鱼币入口氛围（BIZ_IDLE_COIN_ENTRANCE_2）和底部发布球（BIZ_PUBLISH_BALL）。"},"mtop.taobao.idle.publish.button.list":{"intent":"发布页按钮配置","cat":"发布","layer":"h5","note":"publishButtonList 是发布页顶部那排入口（旧书回收等 11 个）。"},"mtop.taobao.idle.rule.config":{"intent":"客户端过滤规则下发","cat":"配置","layer":"java","note":"服务端下发的字段裁剪规则（filterMtopInfo），告诉客户端哪些字段不用解析。"},"mtop.idle.delivery.data.query":{"intent":"闲鱼币权益列表","cat":"闲鱼币","layer":"h5","note":"data 是 38 条可兑换权益（红包/优惠券），含价格与库存。"},"mtop.taobao.idle.treasure.layout.info":{"intent":"闲鱼币商城布局","cat":"闲鱼币","layer":"h5"},"mtop.taobao.idle.treasure.hunt.map.init":{"intent":"闲鱼币寻宝地图","cat":"闲鱼币","layer":"h5"},"mtop.taobao.idle.homehint.list":{"intent":"首页红点提示","cat":"信息流","layer":"kt","note":"决定首页哪些 tab 挂红点（followHints/idleCoinHints/...）。"},"mtop.taobao.idle.home.config":{"intent":"首页功能灰度","cat":"配置","layer":"kt","note":"拍立淘（picSearchGray）、海鲜市场（seafoodGray）等功能的灰度开关。"},"mtop.taobao.idle.ab.config.get":{"intent":"AB 实验分桶","cat":"配置","layer":"kt"},"mtop.taobao.idle.bootstrap.config.get":{"intent":"启动引导配置","cat":"配置","layer":"kt"},"mtop.taobao.idle.user.check":{"intent":"用户状态校验","cat":"账号","layer":"h5"},"mtop.taobao.idle.user.action.strategy":{"intent":"用户行为策略","cat":"配置","layer":"kt"},"mtop.taobao.idle.user.strategy.report":{"intent":"用户策略埋点上报","cat":"埋点","layer":"h5","note":"上报设备/版本/位置等画像信息，4KB 请求体。"},"mtop.taobao.idlehome.user.operator.tracker":{"intent":"首页运营埋点配置","cat":"埋点","layer":"kt"},"mtop.taobao.idle.ut.log.report":{"intent":"UT 埋点上报","cat":"埋点","layer":"kt"},"mtop.taobao.idle.ut.log.config.get":{"intent":"UT 埋点采样配置","cat":"埋点","layer":"kt"},"mtop.taobao.search.highway.upload":{"intent":"搜索行为埋点上传","cat":"埋点","layer":"kt","note":"一次启动打了 7 次，是最高频的埋点。"},"mtop.taobao.idle.device.report":{"intent":"设备信息上报","cat":"埋点","layer":"h5","note":"含 androidId / userAgent / aliOaid 等设备指纹。"},"mtop.client.mudp.update":{"intent":"热更新包检查","cat":"更新","layer":"kt"},"mtop.alibaba.emas.publish.update.resource.get":{"intent":"资源包更新检查","cat":"更新","layer":"kt"},"mtop.taobao.edgecomputer.query":{"intent":"端计算模型配置","cat":"配置","layer":"kt","note":"下发端侧计算（IBFS 等）的模型与埋点规则。"},"mtop.taobao.idle.message.fci.js.fetch":{"intent":"消息页 JS 脚本下发","cat":"配置","layer":"kt","note":"urlMap 是 FCI 小程序脚本的 CDN 地址与 md5。"},"mtop.taobao.idlemessage.face.emoji.load":{"intent":"表情包列表","cat":"消息","layer":"h5"},"mtop.taobao.idlemessage.face.categories.load":{"intent":"表情包分类","cat":"消息","layer":"h5"},"mtop.taobao.idlemessage.profile.notice.list":{"intent":"消息页公告","cat":"消息","layer":"h5"},"mtop.taobao.idlemessage.super.top.session.query":{"intent":"置顶会话查询","cat":"消息","layer":"h5"},"mtop.taobao.idlemessage.receive.classify.query":{"intent":"消息分类查询","cat":"消息","layer":"h5"},"mtop.taobao.idlemessage.user.query.top.resource":{"intent":"消息页顶部资源位","cat":"消息","layer":"h5"},"mtop.idle.interact.notice.show":{"intent":"互动弹窗（我的页）","cat":"运营","layer":"h5"},"mtop.idle.idleadv.scene.restore":{"intent":"广告场景恢复","cat":"广告","layer":"kt"},"mtop.taobao.idle.gamecenter.degrade.list":{"intent":"游戏中心降级名单","cat":"配置","layer":"h5"},"mtop.taobao.idle.fci.coupon.tags":{"intent":"用户品类偏好标签","cat":"画像","layer":"kt","note":"firstPrefer 是用户品类偏好权重，purchaseRecord 是购买记录。"},"mtop.taobao.idle.fci.auge.crowd.check":{"intent":"人群圈选校验（开屏问卷）","cat":"画像","layer":"kt"},"mtop.taobao.idle.fci.home.survey.population.check":{"intent":"首页问卷人群校验","cat":"画像","layer":"kt"},"mtop.taobao.idle.fci.recommend.reason.population.check":{"intent":"推荐理由人群校验","cat":"画像","layer":"kt","note":"userProfile 里直接给出用户的决策因子列表。"},"mtop.taobao.idle.fci.model.download":{"intent":"端侧模型下载","cat":"配置","layer":"kt"},"mtop.taobao.idle.ai.apps.buyer.agent.gray":{"intent":"AI 买家助手灰度","cat":"配置","layer":"h5"},"mtop.taobao.idle.ai.apps.abtest.buyer.assistant.hit.check":{"intent":"AI 助手实验命中","cat":"配置","layer":"kt"},"mtop.taobao.heracles.inno.interactive.voice.token.query":{"intent":"语音交互 token","cat":"配置","layer":"h5"},"mtop.taobao.idle.item.resell.channel.ab.config":{"intent":"回收频道实验配置","cat":"回收","layer":"h5"},"mtop.taobao.idle.item.resell.channel.tablist":{"intent":"回收频道 tab 列表","cat":"回收","layer":"h5"},"mtop.idle.play.behavior.relation.query":{"intent":"活动参与状态查询","cat":"闲鱼币","layer":"h5"},"com.taobao.idle.get.config":{"intent":"客户端通用配置","cat":"配置","layer":"kt","note":"发布/同城/擦亮等功能的开关与提示文案。"},"com.taobao.idle.get.nav.config":{"intent":"Activity 导航映射","cat":"配置","layer":"kt","note":"类名;key;spm 三元组，外部深链跳转靠它解析。"},"com.taobao.idle.host.authorize":{"intent":"H5 容器白名单","cat":"配置","layer":"kt","note":"trustedUrlList / blacklist / schemeBlacklist —— 决定哪些域名能在闲鱼内置浏览器打开。"},"com.taobao.idle.user.init":{"intent":"用户初始化","cat":"账号","layer":"kt"},"mtop.common.getTimestamp":{"intent":"服务端时间戳","cat":"基础","layer":"kt"},"mtop.taobao.idlemtopsearch.search.shade":{"intent":"搜索页骨架屏配置","cat":"搜索","layer":"h5"},"mtop.idle.circle.user.circle.item.card.query":{"intent":"鱼塘圈子卡片查询","cat":"圈子","layer":"kt","note":"鱼塘页里每个圈子卡片的详情数据。"},"mtop.taobao.idlehome.home.tab.list":{"intent":"首页 tab 配置","cat":"信息流","layer":"kt"},"mtop.taobao.idle.item.detail.query":{"intent":"商品详情主接口","cat":"商品","layer":"fl","note":"详情页由 Flutter 渲染，Java 层拿到的结果不直接驱动页面。"},"mtop.taobao.idlemessage.session.update":{"intent":"会话更新同步","cat":"消息","layer":"kt"},"mtop.taobao.idle.publish.draft.list":{"intent":"草稿箱列表","cat":"发布","layer":"kt"},"mtop.taobao.idle.user.profile.get":{"intent":"用户资料查询","cat":"账号","layer":"kt"},"mtop.taobao.idle.awesome.detail":{"intent":"商品详情（新版）","cat":"商品","layer":"fl"},"mtop.taobao.idle.awesome.detail.unit":{"intent":"商品详情分块数据","cat":"商品","layer":"fl","note":"详情页按区块（unit）分批返回，Java 层能拿到但页面由 Flutter 渲染。"},"mtop.taobao.idle.item.wind.recommend.list":{"intent":"商品推荐列表","cat":"商品","layer":"kt"},"mtop.taobao.idle.item.resell.channel.item.list":{"intent":"回收频道商品明细","cat":"回收","layer":"h5"},"mtop.taobao.idlehome.home.recommend":{"intent":"首页推荐（旧版）","cat":"信息流","layer":"kt"},"mtop.taobao.idle.search.hint":{"intent":"搜索提示词","cat":"搜索","layer":"kt"},"mtop.taobao.idle.user.setting.get":{"intent":"用户设置查询","cat":"账号","layer":"kt"},"mtop.taobao.idle.msg.unread.count":{"intent":"未读数统计","cat":"消息","layer":"kt"},"mtop.taobao.idle.circle.list":{"intent":"鱼塘列表","cat":"圈子","layer":"kt"},"mtop.taobao.idle.live.list":{"intent":"直播列表","cat":"直播","layer":"kt"},"mtop.taobao.paramservice.request":{"intent":"参数服务请求","cat":"配置","layer":"kt","note":"通用参数服务：客户端把要取的配置项打包发上去，服务端按 key 返回。具体内容看请求体里的 key。"},"mtop.taobao.idle.forest.animal.feed":{"intent":"森林喂养","cat":"游戏","layer":"h5","note":"喂养动物消耗能量。feedId 来自 animal.list 的 result[].feedInfo.feedId。canFeedTime=0 表示可喂；响应 record.feedPercent 涨约5%。1.0 版本。","respFields":{"canFeedTime":"0=可喂，非0疑似冷却","record":"喂养后动物状态（同 animal.list 结构）"},"reqFields":{"feedId":"要喂养的动物记录 id，来自 animal.list 的 result[].feedInfo.feedId","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.forest.quiz.info":{"intent":"森林绿色答题-取题","cat":"游戏","layer":"h5","note":"响应直接带 correctIndex（答案下标），配合 cube.task.trigger(activityId=103786) 完成答题，答完 energy.list 出 lvsekepu-XY 气泡可领 10 能量。1.0 版本。","respFields":{"questionId":"题目id","correctIndex":"正确答案下标（服务端直接给）","options":"选项数组"},"reqFields":{"fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.cube.task.trigger":{"intent":"cube 任务触发","cat":"任务","layer":"h5","note":"cube 引擎：触发后 idleTaskStatus=AWARD，再用 joy.community.activity.task.award 领奖。idempotentKey=当前毫秒时间戳。taskInstanceId 来自 oliver.show(activityId)。","respFields":{"idleTaskStatus":"AWARD=触发成功待领取","awardInfoDTOMap":"各奖励档状态"},"reqFields":{"activityId":"活动 id（答题=103786，装扮=102178），来自 oliver.show 的入参","taskInstanceId":"任务实例 id，来自 oliver.show 响应里对应任务的 taskInstanceId","idempotentKey":"幂等键，取当前毫秒时间戳，每次调用现取","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.joy.community.activity.task.award":{"intent":"joy 活动任务领奖（多日挑战 / 答题）","cat":"任务","layer":"kt","note":"与 oliver 领奖是**两套参数约定**，别混：joy 用 activityId + taskId(数字) + awardIndexList(字符串)，oliver 用 deliveryId(数字) + sceneId。status=2（已领）时再调**不会**返回幂等提示，而是 FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD（「领奖失败，请稍后再试~」）—— 必须先在本地按 status 筛。","reqFields":{"activityId":"活动 id，来自 challenge.info 的 extend.activityId（字符串）","taskId":"任务实例 id，来自 challenge.info 的 extend.taskInstanceId（数字）","awardIndexList":"档位序号字符串，如 \"1\"；多档可逗号拼接","asac":"风控参数，从抓包库取（body 或 header 都可能带）","fishAdCode":"城市行政编码，环境常量","gps":"经纬度，环境常量"},"respFields":{"failIndexList":"领取失败的档位序号数组，空数组=全部成功","idleJoyTaskDTO.awardInfoDTOMap":"已领档位的详情（status=2 表示已领）","idleJoyTaskDTO.currentProcessNum":"当前已攒次数"}},"mtop.taobao.idle.task.queryolivertasklist":{"intent":"任务中心列表(oliver)","cat":"任务","layer":"h5","note":"sceneId=496 是寻宝地图骰子任务。idleTaskStatus: ACCEPTED未完成→AWARD待领取→COMPLETE已领。deliveryId 领奖时用；materialDTO.actionType=VISIT 的任务需跳 H5 完成。","respFields":{"taskList[].deliveryId":"领奖参数","taskList[].idleTaskStatus":"任务状态","extend.diceTaskCountDown":"骰子倒计时任务"},"reqFields":{"sceneId":"场景 id，496=寻宝地图骰子任务","brand":"机型品牌，实测 oneplus，判断不严格","osVersion":"系统版本号字符串，同上","platform":"固定 Android","isWidgetInstalled":"是否装了桌面小组件，布尔","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.task.getolivertaskbenefit":{"intent":"oliver 任务领奖","cat":"任务","layer":"h5","note":"AWARD 状态的 oliver 任务用此接口领取，成功后 idleTaskStatus 变 COMPLETE。","respFields":{"taskDTO.idleTaskStatus":"COMPLETE=已领取"},"reqFields":{"deliveryId":"任务投递 id，来自 queryolivertasklist 的 taskList[].deliveryId","sceneId":"同列表查询的 sceneId（496）","asac":"风控参数，从抓包原样取","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.treasure.hunt.map.roll":{"intent":"寻宝地图掷骰子","cat":"游戏","layer":"h5","note":"闲鱼币大地图核心。locationBefore 用 game.refresh 的 userInfo.location（防重放）；mapVersion 用 init 的 11；outId=毫秒时间戳；version 固定 3。diceType: FORWARD_STEP 普通 / SURFACE12_DICE 十二面骰（需 diceId=prop.list 的道具id）。","respFields":{"contentList":"路径上每个奖励格（contentType=coin, rewardNum）","addPoint":"本次所得积分"},"reqFields":{"diceType":"FORWARD_STEP=普通步进 / SURFACE12_DICE=十二面骰（需同时给 diceId）","locationBefore":"掷之前的格位，必须等于 game.refresh 的 userInfo.location（防重放）","mapVersion":"地图版本，来自 map.init 的 mapResource[].mapVersion（当前 \"11\"）","outId":"幂等键，当前毫秒时间戳","version":"协议版本，固定 3","diceId":"（仅十二面骰）道具实例 id，来自 play.prop.list 的 propInfos[].id","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.treasure.hunt.map.game.refresh":{"intent":"寻宝地图状态刷新","cat":"游戏","layer":"h5","note":"拿 userInfo：location 当前格位（roll 的 locationBefore 来源）、diceNum 剩余骰子（0 则先做任务）、coinNum 闲鱼币余额。","respFields":{"refreshGameData.userInfo":"location/diceNum/coinNum/mapCode/remainStep"},"reqFields":{"fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.idle.play.signin.cash.main":{"intent":"现金签到主状态","cat":"签到","layer":"h5","note":"金币拆红包。currentBonus=true 有可拆红包；timeTask.time 倒计时；signCashNum/threshold 现金进度。","respFields":{"currentBonus":"有可拆红包","signCashNum":"已攒现金","signCashThreshold":"提现门槛","timeTask.time":"下一个红包倒计时秒"},"reqFields":{"asac":"风控参数，从抓包原样取","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.idle.play.signin.cash.open":{"intent":"现金红包拆红包","cat":"活动","layer":"kt","note":"必须带 asac（缺了报 FAIL_SYS_BIZPARAM_MISSED「服务竟然出错」）。asac 在本接口的 **body 和 header 里都有**，是抓包库里最稳的取样点 —— 现金/任务/挑战三处都靠它。","reqFields":{"asac":"风控令牌，本接口 body 里带，可作为取样源","fishAdCode":"城市行政编码，环境常量","gps":"经纬度，环境常量"},"respFields":{"amount":"本次拆到的金额（分）"}},"mtop.taobao.pentaprism.scene.trigger":{"intent":"五棱镜任务触发","cat":"任务","layer":"h5","note":"场景任务触发。fromToken/implId 来自 pentaprism.scene.query；implId 格式 other_<period>_<idx>_<deliveryId>_<n>。","respFields":{"model.progress.status":"COMPLETE=完成","model.rewards":"奖励明细"},"reqFields":{"activityId":"活动 id（实测 100104）","deliveryId":"任务投递 id，来自 pentaprism.scene.query 响应","fromToken":"场景令牌，来自 pentaprism.scene.query 响应","implId":"实现 id，格式 other_<period>_<idx>_<deliveryId>_<n>；trigger 用尾号 _0","awardIndex":"奖励档位，固定 1","samplingRate":"采样率，实测 0","sceneId":"场景 id（实测 7066）","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.pentaprism.scene.award":{"intent":"五棱镜任务领奖","cat":"任务","layer":"h5","note":"trigger 成功后调用，同参数。rewards[].errorCode=SENT 表示发放成功。","respFields":{"rewards[].errorCode":"SENT=已发放"}},"mtop.taobao.idle.circle.point.sign.in":{"intent":"圈子签到","cat":"签到","layer":"h5","note":"circleId 从 circle.main.tab / circle.main.page 拿。+5 成长值，幂等。","respFields":{"signStatus":"SIGNED=已签","awardedGrowth":"本次成长值","totalGrowth":"总成长值"},"reqFields":{"circleId":"圈子 id，来自 circle.main.tab / circle.main.page","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.circle.benefit.exchange":{"intent":"圈子权益兑换","cat":"任务","layer":"h5","note":"用成长值兑换权益（头像挂件等）。benefitId 从 circle.benefit.detail 拿。","respFields":{"success":"true=兑换成功","exchangedFlowNo":"兑换流水号"},"reqFields":{"benefitId":"要兑换的权益 id，来自 circle.benefit.detail","circleId":"圈子 id，同上","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.play.prop.list":{"intent":"道具背包","cat":"游戏","layer":"h5","note":"bizCode=coinMap 查寻宝地图道具（如十二面骰 SURFACE12_DICE），propInfos[].id 是使用时的道具实例id。","respFields":{"propInfos[].id":"道具实例id（prop.use 用）","propInfos[].configId":"道具配置id"},"reqFields":{"bizCode":"业务编码，coinMap=寻宝地图道具背包","pageNumber":"页码，固定 1","pageSize":"每页数量，50 够用","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.taobao.idle.play.prop.use":{"intent":"使用道具","cat":"游戏","layer":"h5","note":"bizCode=cycleCoinMapV2（寻宝场景）。响应 attributes.benefitResult 直接发权益。","respFields":{"attributes.benefitResult":"发放的权益明细"},"reqFields":{"bizCode":"使用场景，cycleCoinMapV2=寻宝地图（注意与 list 的 coinMap 不同）","configId":"道具配置 id，来自 prop.list 的 propInfos[].configId","id":"道具实例 id，来自 prop.list 的 propInfos[].id","outId":"幂等键，当前毫秒时间戳","asac":"风控参数，从抓包原样取","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.idle.interact.cycle.challenge.info":{"intent":"环游挑战进度","cat":"游戏","layer":"h5","note":"寻宝地图的累计挑战：currentCycle 当前圈数，tierList 奖励阶梯（REACHED/CHALLENGING）。","respFields":{"tierList":"奖励阶梯","currentCycle":"当前圈数"}},"mtop.taobao.idle.forest.animal.exit":{"intent":"森林动物放归","cat":"游戏","layer":"h5","note":"operateId 疑似 格位#行#列（p#22#5），规则待确认。","respFields":{"isSuccess":"操作结果"},"reqFields":{"operateId":"格位编号，形如 p#22#5（规则待确认）","fishAdCode":"城市行政编码，环境常量（330102=杭州上城），建议带","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"}},"mtop.idle.multi.day.challenge.info":{"intent":"多日任务挑战进度","cat":"任务","layer":"kt","note":"activityId=104293 的「做任务攒次数」活动。subProgressInfos[].status: 0=未达成 1=可领 2=已领；awardThreshold 是档位（3/6/10 次各 100/300/500 闲鱼币）。领奖走 joy.community.activity.task.award。","reqFields":{"fishAdCode":"城市行政编码，环境常量（330102=杭州上城）","gps":"经纬度 \"lat,lng\"，环境常量，不校验精度"},"respFields":{"idleTaskDTO.extend.activityId":"活动 id，领奖时当 activityId 传（实测 104293）","idleTaskDTO.extend.taskInstanceId":"任务实例 id，领奖时当 taskId 传","idleTaskDTO.idleJoyTaskProgressDTO.subProgressInfos[].index":"档位序号，领奖时当 awardIndexList 传","idleTaskDTO.idleJoyTaskProgressDTO.subProgressInfos[].status":"0=未达成 1=可领 2=已领","idleTaskDTO.idleJoyTaskProgressDTO.subProgressInfos[].awardThreshold":"达成需要的次数（3/6/10）"}},"mtop.idle.challenge.idleCoinSign.info":{"intent":"闲鱼币签到状态（轻量）","cat":"任务","layer":"kt","note":"只返回 {\"failedFlag\":false}，信息量极少；签到状态仍以 play.sign.in.home 的 signInAwarded 为准。列表页会顺手调一次。","reqFields":{"fishAdCode":"城市行政编码，环境常量","gps":"经纬度，环境常量"},"respFields":{"failedFlag":"查询是否失败；false=正常"}}},"fieldDoc":{"sections":"首页卡片数组","feedsCount":"卡片数量","homeTopList":"金刚位区块","widgets":"金刚位行","widgetDO":"金刚位数据对象","channelDOList":"金刚位入口列表","arg1":"埋点动作名","idleAdsTaskId":"运营埋点 ID（不是广告标记）","bizType":"业务类型（item/resell/house/bid）","cardTypeValue":"卡片类型","targetUrl":"跳转地址","targetPage":"目标页（detail = 商品详情）","itemId":"商品 ID","titleSpan":"标题富文本","richTitle":"标题富文本数组","priceInfo":"价格富文本","priceUnitInfos":"价格片段（¥ / 数值分开给）","soldPrice":"成交价（纯数值）","detailParams":"详情页预置参数","recProvince":"推荐来源省份","userNick":"卖家昵称","sellerId":"卖家 ID","userId":"用户 ID","favorCount":"想要人数","hotPoint":"热度提示文案","fishTags":"闲鱼标签（成色/规格）","tagList":"标签数组","matchType":"召回方式（pi2i_recall 等）","traceId":"链路追踪 ID","pvid":"页面浏览 ID","spm":"埋点位置编码","serverTime":"服务端时间","nextPage":"下一页游标","hasMore":"是否还有更多","sessions":"会话数组","sessionType":"会话类型（1=真人私聊）","sessionId":"会话 ID","summary":"摘要","unread":"未读数","sortIndex":"排序索引","itemInfo":"商品信息","mainPic":"商品主图","attributeMap":"扩展属性","apiName":"接口名","apiVersioin":"接口版本（服务端字段就是这么拼的）","originJson":"是否要求原始 JSON","needDecryptKeys":"需要解密的字段","serverDecryptKeys":"服务端解密字段","ret":"返回码（SUCCESS 才算成功）","v":"协议版本","data":"业务数据","result":"结果","success":"是否成功","nav":"Activity 导航映射表","trustedUrlList":"可信域名白名单","blacklist":"黑名单域名","schemeBlacklist":"scheme 黑名单","isvUrlList":"第三方 appKey 列表","filterMtopInfo":"服务端下发的字段裁剪规则","ruleConfig":"规则配置","benefitDetailList":"权益明细列表","couponTotal":"券总量","benefitType":"权益类型","worthAmountStr":"抵扣金额","priceNewStr":"兑换价","firstPrefer":"品类偏好权重","purchaseRecord":"购买记录","userProfile":"用户决策因子画像","strategies":"策略列表","renderMap":"渲染映射","chain":"调用链（内 → 外）","caller":"调用方","layer":"调用层（Kotlin / Java / H5 / Flutter / SDK）","replay":"能否从外部重放","reqHeaders":"请求头","hits":"同一内容出现次数","respLen":"响应字节数","ver":"接口版本","needSession":"是否需要登录态","needEcode":"是否需要 ecode","restoreStage":"广告场景恢复阶段（appLaunch = 启动时）","stageMatch":"场景是否命中","trackParams":"埋点参数","commonBucketId":"通用实验分桶 ID","ex1BucketId":"实验分桶 1","ex2BucketId":"实验分桶 2","degradeUnionReg":"是否降级联合注册","enableUnionReg":"是否启用联合注册","funHitABList":"命中的功能实验列表","myTabNewAlert":"「我的」页是否有新提示","picSearchGray":"拍立淘灰度开关","seafoodGray":"海鲜市场灰度开关","isHit":"是否命中该灰度","seaVersion":"海鲜市场版本","bucketId":"实验分桶 ID","disableChat":"是否禁用聊天","disableSameCity":"是否禁用同城","itemCycle":"商品在线周期说明文案","publishDraftDefaultCondition":"发帖默认可见范围","alipay":"是否启用支付宝","ability":"页面能力开关","base":"头部卡片数据","container":"容器数据","utArgs":"埋点参数","avatar":"头像","setting":"设置入口","cs":"鱼力值","service":"帮助与客服","snsNick":"闲鱼昵称","score":"鱼力值分数","url":"跳转地址","icon":"图标地址","title":"标题","guidePublishType":"发布引导类型","ignoreImageEditor":"是否忽略图片编辑器","publishButtonList":"发布页入口按钮列表","publishDXTemplateList":"发布页动态模板列表","publishType":"发布类型","stBucket":"实验分桶标记","uniqNick":"唯一昵称","badgeImageUrl":"角标图片地址","activeTime":"生效时间","position":"位置标识","index":"序号","trackEvent":"埋点事件名","tagName":"标签名","tagId":"标签 ID","launchUrl":"启动地址","thirdPartyList":"第三方列表","whitelist":"白名单","listPreFilter":"列表预过滤配置","resDataFilter":"要裁掉的字段","hasUpdate":"是否有更新","updateInfo":"更新信息","patchVersion":"补丁版本","dexpatchVersion":"dex 补丁版本","updateTypes":"更新类型","diagnoseSceneConfigModelList":"诊断场景配置","models":"模型列表","inTargetCrowd":"是否在目标人群中","bucket":"人群分桶","cateName":"类目名","cateId":"类目 ID","scene":"场景标识","socModel":"芯片型号","secondPrefer":"次级品类偏好","tags":"标签列表","channelCatName":"频道类目名","times":"次数","mapCode":"地图编码","shoppingMallVO":"商城数据","categoryId":"类目 ID","description":"说明文案","exchangedNumStr":"已兑换数量","totalBenefitNum":"权益总数量","innerCost":"内部成本","benefitUrl":"权益跳转地址","startTimeStamp":"开始时间戳","endTimeStamp":"结束时间戳","mainImgUrl":"主图地址","extra":"额外信息","nextPageFlag":"是否还有下一页","mapResource":"地图资源","dailyReward":"每日奖励","buffTags":"加成标签","abResult":"实验分桶结果","displayTreasure":"是否展示寻宝","displayFishCircle":"是否展示鱼塘","todayNum":"今日数量","tomorrowNum":"明日数量","dailyStepNum":"每日步数","userInfo":"用户信息","mapSequence":"地图序列","token":"访问令牌","expireTime":"过期时间","urlMap":"脚本地址映射","uploadErrorLog":"是否上传错误日志","enableByteCode":"是否启用字节码","md5":"文件摘要","fci":"前端计算引擎（FCI）","resultList":"结果列表","sessionList":"会话列表","followHints":"关注红点","idleCoinHints":"闲鱼币红点","idleLiveHints":"直播红点","idleSeafoodHints":"海鲜市场红点","iconUrl":"图标地址","iconWidth":"图标宽度","iconHeight":"图标高度","args":"埋点参数","trackName":"埋点名称","page":"页面标识","strategyList":"策略列表","paramMap":"参数映射","login":"是否已登录","acceptedMyBank":"是否开通网商银行","playboy":"特殊标记","items":"条目列表","itemList":"商品列表","currentSubSceneList":"当前子场景列表","sceneList":"场景列表","totalAmt":"总金额","totalNumber":"总数量","bizAuthText":"认证引导文案","bizAuthStatus":"认证状态","bizAuthImageUrl":"认证引导图","dislikeBtnShow":"是否展示不喜欢按钮","hasNext":"是否还有下一页","resellSearchVersion":"回收搜索版本","tabList":"tab 列表","tabName":"tab 名称","sceneId":"场景 ID","itemCount":"商品数","sceneName":"场景名称","callFrom":"调用来源","pageNumber":"页码","pageSize":"每页数量","mainSceneId":"主场景 ID","subSceneId":"子场景 ID","parameterStr":"参数串","version":"版本","channel":"渠道","dataSourceId":"数据源 ID","pageResourceId":"页面资源 ID","extraInfo":"额外信息","currentCity":"当前城市","weather":"天气","timestamp":"时间戳","t":"服务端时间（毫秒）","code":"返回码","msg":"返回信息","message":"消息","error":"错误","type":"类型","status":"状态","value":"值","list":"列表","count":"数量","name":"名称","desc":"描述","id":"标识","uid":"用户标识","nick":"昵称","price":"价格","originalPrice":"原价","quantity":"数量","stock":"库存","imageUrl":"图片地址","imgUrl":"图片地址","link":"链接","target":"跳转目标","pageName":"页面名","unreadCount":"未读数","lastMessage":"最后一条消息","userAvatar":"用户头像","memberFlags":"成员标记","ownerInfo":"卖家信息","fishNick":"闲鱼昵称","logo":"头像地址","hasFishNick":"是否有闲鱼昵称","redReminder":"红点提醒","itemPicture":"商品图片","jumpUrl":"跳转地址","prefix":"前缀","paramservice":"参数服务"}}"""

    /** 重放实测结论：api -> {replay, why}，由 tools/probe_replay.sh 产出 */
    @JvmField val API_PROBE: String = """{"com.taobao.idle.get.config":{"replay":"yes","why":"OK 292B"},"com.taobao.idle.get.nav.config":{"replay":"yes","why":"OK 626B"},"com.taobao.idle.host.authorize":{"replay":"yes","why":"OK 6785B"},"com.taobao.idle.location.update":{"replay":"yes","why":"OK 124B"},"com.taobao.idle.user.alipay":{"replay":"yes","why":"OK 85B"},"com.taobao.idle.user.init":{"replay":"yes","why":"SUCCESS"},"mtop.alibaba.emas.publish.update.resource.get":{"replay":"no","why":"IllegalStateException: ret=2 retType=null msg=platform is null respApi=mtop.alib"},"mtop.alibaba.idle.ad.account.airecharge.hastask":{"replay":"yes","why":"OK 55B"},"mtop.alibaba.idle.ad.account.card.v2":{"replay":"yes","why":"OK 301B"},"mtop.alibaba.idle.isv.permission.query":{"replay":"yes","why":"OK 401B"},"mtop.alibaba.idle.mach.guide.items":{"replay":"yes","why":"OK 85B"},"mtop.alibaba.idle.play.groupChat.entrance.query":{"replay":"yes","why":"OK 25B"},"mtop.alibaba.idle.recycle.quote.list":{"replay":"yes","why":"OK 1362B"},"mtop.alibaba.idle.shop.open.status.query":{"replay":"yes","why":"OK 77B"},"mtop.alibaba.idle.user.medal.info.query":{"replay":"yes","why":"OK 401B"},"mtop.alibaba.mbis.address.list.withconfig":{"replay":"yes","why":"OK 1799B"},"mtop.client.mudp.update":{"replay":"yes","why":"OK 599B"},"mtop.common.getTimestamp":{"replay":"yes","why":"OK 21B"},"mtop.gaia.nodejs.gaia.idle.data.gw.v2.activity.get":{"replay":"yes","why":"OK 401B"},"mtop.gaia.nodejs.gaia.idle.data.gw.v2.index.get":{"replay":"yes","why":"OK 401B"},"mtop.idle.ad.expose":{"replay":"yes","why":"OK 18B"},"mtop.idle.challenge.idleCoinSign.info":{"replay":"yes","why":"OK 39B"},"mtop.idle.circle.user.circle.item.card.query":{"replay":"yes","why":"OK 94B"},"mtop.idle.delivery.data.query":{"replay":"yes","why":"OK 21355B"},"mtop.idle.idleadv.app.launch.report":{"replay":"yes","why":"OK 28B"},"mtop.idle.idleadv.code.restore":{"replay":"yes","why":"OK 25B"},"mtop.idle.idleadv.scene.restore":{"replay":"yes","why":"OK 37B"},"mtop.idle.idleitem.draft.list":{"replay":"yes","why":"OK 62B"},"mtop.idle.idleitem.publish.preget":{"replay":"yes","why":"OK 401B"},"mtop.idle.interact.cycle.challenge.info":{"replay":"yes","why":"OK 301B"},"mtop.idle.interact.mac.user.launch":{"replay":"yes","why":"OK 54B"},"mtop.idle.interact.notice.show":{"replay":"yes","why":"OK 13B"},"mtop.idle.interact.time.exchange.info":{"replay":"no","why":"FAIL_BIZ_NOT_TARGET_USER"},"mtop.idle.item.publish.service.cards.list":{"replay":"no","why":"ANDROID_SYS_PARAM_TOO_LONG"},"mtop.idle.message.head.info":{"replay":"yes","why":"OK 401B"},"mtop.idle.mini.game.postpurchase.view.query":{"replay":"no","why":"FAIL_SYS_INVALID_HTTP_METHOD"},"mtop.idle.multi.day.challenge.info":{"replay":"yes","why":"OK 401B"},"mtop.idle.play.behavior.relation.query":{"replay":"no","why":"FAIL_BIZ_RESULT_NULL"},"mtop.idle.play.inbox.fetch":{"replay":"yes","why":"OK 16B"},"mtop.idle.promotions.activity.seckill.common.info.query":{"replay":"yes","why":"OK 1964B"},"mtop.idle.promotions.coupon.channel.czzx.item.query":{"replay":"yes","why":"OK 1779B"},"mtop.idle.recycle.customer.ab.execute":{"replay":"yes","why":"OK 117B"},"mtop.idle.recycle.customer.auction.record":{"replay":"yes","why":"OK 401B"},"mtop.idle.recycle.customer.coupon.issue":{"replay":"yes","why":"OK 20B"},"mtop.idle.recycle.customer.coupon.max.get":{"replay":"yes","why":"OK 465B"},"mtop.idle.recycle.customer.coupon.pendant":{"replay":"yes","why":"OK 378B"},"mtop.idle.recycle.customer.coupon.pendant2":{"replay":"yes","why":"OK 358B"},"mtop.idle.recycle.customer.home.page.entrance":{"replay":"yes","why":"OK 1934B"},"mtop.idle.recycle.customer.quote.atmosphere.marquee":{"replay":"yes","why":"OK 763B"},"mtop.idle.recycle.customer.quote.devicespuquote":{"replay":"yes","why":"OK 934B"},"mtop.idle.recycle.customer.quote.get":{"replay":"yes","why":"OK 1645B"},"mtop.idle.recycle.customer.quote.getquotelist":{"replay":"yes","why":"OK 1392B"},"mtop.idle.recycle.customer.quote.submit":{"replay":"yes","why":"OK 71B"},"mtop.idle.recycle.customer.ship.address.check":{"replay":"yes","why":"OK 46B"},"mtop.idle.recycle.customer.ship.time.slot.get":{"replay":"yes","why":"OK 1977B"},"mtop.idle.recycle.customer.tborder.list":{"replay":"yes","why":"OK 1911B"},"mtop.idle.trade.message.headinfo":{"replay":"yes","why":"OK 401B"},"mtop.idle.trade.other.order.entry":{"replay":"yes","why":"OK 311B"},"mtop.idle.user.asset.items":{"replay":"yes","why":"OK 1889B"},"mtop.idle.user.asset.tab":{"replay":"yes","why":"OK 751B"},"mtop.idle.user.page.credit":{"replay":"yes","why":"OK 401B"},"mtop.idle.user.page.head":{"replay":"yes","why":"OK 401B"},"mtop.idle.user.page.more":{"replay":"yes","why":"OK 401B"},"mtop.idle.user.page.my.adapter":{"replay":"yes","why":"OK 29338B"},"mtop.idle.user.page.profile":{"replay":"yes","why":"OK 98B"},"mtop.idle.user.page.setting":{"replay":"yes","why":"OK 1715B"},"mtop.idle.user.setting.get":{"replay":"yes","why":"OK 18B"},"mtop.idle.user.setting.sync":{"replay":"yes","why":"OK 17B"},"mtop.idle.user.shop.act.query":{"replay":"yes","why":"OK 1411B"},"mtop.idle.user.simple":{"replay":"yes","why":"OK 52B"},"mtop.idle.user.timeline.list":{"replay":"yes","why":"OK 1723B"},"mtop.idle.x.message.session.arouse.query":{"replay":"yes","why":"OK 401B"},"mtop.taobao.baichuan.smb.get":{"replay":"yes","why":"OK 1945B"},"mtop.taobao.edgecomputer.query":{"replay":"no","why":"FAIL_BIZ_INVALID_QUERY_PARAM"},"mtop.taobao.heracles.inno.interactive.voice.token.query":{"replay":"yes","why":"OK 189B"},"mtop.taobao.idle.ab.config.get":{"replay":"yes","why":"OK 146B"},"mtop.taobao.idle.activity.sell.seller.recommend":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.ad.activity.detail.rendering":{"replay":"no","why":"NO_APPLY_ACTIVITY_PERMISSION"},"mtop.taobao.idle.ad.activity.diagnosis.query":{"replay":"yes","why":"OK 30B"},"mtop.taobao.idle.ad.automation.rule.get":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.ad.customer.diagnose.query":{"replay":"yes","why":"OK 31B"},"mtop.taobao.idle.ad.game.recycle.product.detail":{"replay":"yes","why":"OK 94B"},"mtop.taobao.idle.ad.home":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.ad.home.activity":{"replay":"yes","why":"OK 226B"},"mtop.taobao.idle.ad.item.activity.rendering":{"replay":"yes","why":"OK 81B"},"mtop.taobao.idle.ad.lead.hub.product.detail":{"replay":"yes","why":"OK 34B"},"mtop.taobao.idle.ad.local.car.activity.detail":{"replay":"yes","why":"OK 184B"},"mtop.taobao.idle.ad.member.data.query":{"replay":"yes","why":"OK 301B"},"mtop.taobao.idle.ad.message.unread.info":{"replay":"yes","why":"OK 20B"},"mtop.taobao.idle.ad.new.simple.order.detail":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.ad.new.simple.order.retain.check":{"replay":"yes","why":"OK 75B"},"mtop.taobao.idle.ad.promotion.guid":{"replay":"yes","why":"OK 30B"},"mtop.taobao.idle.ad.protocol.query":{"replay":"yes","why":"OK 215B"},"mtop.taobao.idle.ai.apps.abtest.buyer.assistant.hit.check":{"replay":"yes","why":"OK 80B"},"mtop.taobao.idle.ai.apps.buyer.agent.gray":{"replay":"yes","why":"OK 19B"},"mtop.taobao.idle.ai.apps.buyer.task.behavior.report":{"replay":"no","why":"ANDROID_SYS_PARAM_TOO_LONG"},"mtop.taobao.idle.ai.apps.search":{"replay":"yes","why":"OK 92B"},"mtop.taobao.idle.attention.list":{"replay":"yes","why":"OK 1371B"},"mtop.taobao.idle.auge.crowd.hit":{"replay":"yes","why":"OK 90B"},"mtop.taobao.idle.auge.full.show":{"replay":"yes","why":"OK 105B"},"mtop.taobao.idle.awesome.detail.unit":{"replay":"yes","why":"OK 1985B"},"mtop.taobao.idle.bill.crowd.query":{"replay":"yes","why":"OK 862B"},"mtop.taobao.idle.bootstrap.config.get":{"replay":"yes","why":"OK 127B"},"mtop.taobao.idle.bubble.show":{"replay":"yes","why":"OK 76B"},"mtop.taobao.idle.cco.hall.home":{"replay":"yes","why":"OK 1817B"},"mtop.taobao.idle.cco.hall.service.progress.list":{"replay":"yes","why":"OK 45B"},"mtop.taobao.idle.circle.1ton.recommend":{"replay":"yes","why":"OK 60B"},"mtop.taobao.idle.circle.benefit.detail":{"replay":"yes","why":"OK 872B"},"mtop.taobao.idle.circle.benefit.exchange":{"replay":"yes","why":"OK 72B"},"mtop.taobao.idle.circle.fans.join":{"replay":"yes","why":"OK 300B"},"mtop.taobao.idle.circle.level.home":{"replay":"yes","why":"OK 1849B"},"mtop.taobao.idle.circle.main.page":{"replay":"yes","why":"OK 1973B"},"mtop.taobao.idle.circle.main.tab":{"replay":"yes","why":"OK 1949B"},"mtop.taobao.idle.circle.point.sign.in":{"replay":"yes","why":"OK 75B"},"mtop.taobao.idle.circle.switch.list":{"replay":"yes","why":"OK 1581B"},"mtop.taobao.idle.comment.list":{"replay":"yes","why":"OK 1865B"},"mtop.taobao.idle.detail.preget":{"replay":"yes","why":"OK 196B"},"mtop.taobao.idle.device.report":{"replay":"yes","why":"OK 17B"},"mtop.taobao.idle.division.all.get":{"replay":"yes","why":"OK 1067B"},"mtop.taobao.idle.division.by.gps":{"replay":"yes","why":"OK 224B"},"mtop.taobao.idle.division.getbyid":{"replay":"yes","why":"OK 173B"},"mtop.taobao.idle.favor.item.list":{"replay":"yes","why":"OK 1939B"},"mtop.taobao.idle.fci.auge.crowd.check":{"replay":"yes","why":"OK 81B"},"mtop.taobao.idle.fci.coupon.tags":{"replay":"yes","why":"OK 562B"},"mtop.taobao.idle.fci.home.survey.population.check":{"replay":"yes","why":"OK 81B"},"mtop.taobao.idle.fci.model.download":{"replay":"yes","why":"OK 69B"},"mtop.taobao.idle.fci.recommend.reason.population.check":{"replay":"yes","why":"OK 313B"},"mtop.taobao.idle.forest.animal.benefit.list":{"replay":"yes","why":"OK 1953B"},"mtop.taobao.idle.forest.animal.benefit.material":{"replay":"yes","why":"OK 301B"},"mtop.taobao.idle.forest.animal.list":{"replay":"yes","why":"OK 301B"},"mtop.taobao.idle.forest.animal.open":{"replay":"no","why":"FAIL_BIZ_SAME_ANIMAL"},"mtop.taobao.idle.forest.energy.issue":{"replay":"no","why":"FAIL_BIZ_SERVER_ERROR"},"mtop.taobao.idle.forest.energy.list":{"replay":"yes","why":"OK 50B"},"mtop.taobao.idle.forest.feed.task.extra":{"replay":"yes","why":"OK 46B"},"mtop.taobao.idle.forest.head.extra":{"replay":"yes","why":"OK 1953B"},"mtop.taobao.idle.forest.shoppingmall.extra":{"replay":"yes","why":"OK 156B"},"mtop.taobao.idle.fun.follow.widget.fatigue":{"replay":"yes","why":"OK 119B"},"mtop.taobao.idle.fun.personal.collection.count":{"replay":"yes","why":"OK 469B"},"mtop.taobao.idle.fun.post.search":{"replay":"yes","why":"OK 1985B"},"mtop.taobao.idle.gamecenter.degrade.list":{"replay":"yes","why":"OK 42B"},"mtop.taobao.idle.home.config":{"replay":"yes","why":"OK 131B"},"mtop.taobao.idle.homehint.list":{"replay":"yes","why":"OK 333B"},"mtop.taobao.idle.house.query.subscribe.info":{"replay":"yes","why":"OK 120B"},"mtop.taobao.idle.house.query.subscribe.template":{"replay":"yes","why":"OK 1755B"},"mtop.taobao.idle.house.sec.pattern.words":{"replay":"yes","why":"OK 29B"},"mtop.taobao.idle.item.buy.feeds":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.item.management.query":{"replay":"yes","why":"OK 383B"},"mtop.taobao.idle.item.predict.loadmore":{"replay":"yes","why":"OK 366B"},"mtop.taobao.idle.item.recommend.list":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.item.recommend.tab":{"replay":"yes","why":"OK 1337B"},"mtop.taobao.idle.item.resell.channel":{"replay":"yes","why":"OK 40853B"},"mtop.taobao.idle.item.resell.channel.ab.config":{"replay":"yes","why":"OK 16B"},"mtop.taobao.idle.item.resell.channel.tablist":{"replay":"yes","why":"OK 83B"},"mtop.taobao.idle.item.user.question.query":{"replay":"yes","why":"OK 84B"},"mtop.taobao.idle.joy.community.activity.task.award":{"replay":"no","why":"FAIL_BIZ_ACTIVITY_TASK_CANNOT_AWARD"},"mtop.taobao.idle.kgraph.property.recommend":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.local.config":{"replay":"yes","why":"OK 1919B"},"mtop.taobao.idle.local.home":{"replay":"yes","why":"OK 1953B"},"mtop.taobao.idle.local.home.feeds":{"replay":"yes","why":"OK 1819B"},"mtop.taobao.idle.local.home.top":{"replay":"yes","why":"OK 1945B"},"mtop.taobao.idle.local.near.by.corner.info":{"replay":"yes","why":"OK 119B"},"mtop.taobao.idle.local.poi.get":{"replay":"yes","why":"OK 261B"},"mtop.taobao.idle.mach.advertise.batch.output":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.mach.advertise.output":{"replay":"yes","why":"OK 288B"},"mtop.taobao.idle.main.search.config":{"replay":"yes","why":"OK 89B"},"mtop.taobao.idle.merchant.business.qualification.entry.query":{"replay":"yes","why":"OK 625B"},"mtop.taobao.idle.message.fci.js.fetch":{"replay":"yes","why":"OK 2233B"},"mtop.taobao.idle.mini.game.channel.atmosphere.query":{"replay":"yes","why":"OK 498B"},"mtop.taobao.idle.mini.game.coin.delivery.query":{"replay":"yes","why":"OK 141B"},"mtop.taobao.idle.mini.game.coin.exchange.enable.query":{"replay":"yes","why":"OK 72B"},"mtop.taobao.idle.mini.game.delivery.query":{"replay":"yes","why":"OK 1391B"},"mtop.taobao.idle.mini.game.new.user.task.query":{"replay":"yes","why":"OK 301B"},"mtop.taobao.idle.mini.game.point.withdraw.query":{"replay":"yes","why":"OK 330B"},"mtop.taobao.idle.mini.game.recent.visit.query":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.mini.game.runtime.info":{"replay":"no","why":"INVALID_PARAM"},"mtop.taobao.idle.mini.game.service.subscription.query":{"replay":"yes","why":"OK 35B"},"mtop.taobao.idle.mini.game.sign.in.query":{"replay":"yes","why":"OK 1354B"},"mtop.taobao.idle.mini.game.task.center.query":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.mini.game.task.channel.revisit.complete":{"replay":"yes","why":"OK 18B"},"mtop.taobao.idle.oliver.batch.issue":{"replay":"no","why":"FAIL_BIZ_NO_ENOUGH_BALANCE_ERROR"},"mtop.taobao.idle.oliver.benefit.record":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.oliver.show":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.play.prop.list":{"replay":"yes","why":"OK 52B"},"mtop.taobao.idle.play.prop.use":{"replay":"no","why":"FAIL_BIZ_PLAY_IDEMPOTENT_ERROR"},"mtop.taobao.idle.play.sign.in":{"replay":"no","why":"FAIL_BIZ_PLAY_SIGNED_IN"},"mtop.taobao.idle.play.sign.in.home":{"replay":"yes","why":"OK 579B"},"mtop.taobao.idle.playboy.recommend":{"replay":"yes","why":"OK 1959B"},"mtop.taobao.idle.point.pointbank.shoppingmall":{"replay":"yes","why":"OK 301B"},"mtop.taobao.idle.point.pointbank.shoppingmall.benefitdetail":{"replay":"no","why":"FAIL_BIZ_SERVER_ERROR"},"mtop.taobao.idle.publish.button.list":{"replay":"yes","why":"OK 4601B"},"mtop.taobao.idle.publish.guide.list":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.rate.list":{"replay":"yes","why":"OK 1909B"},"mtop.taobao.idle.recycle.nextSpuNav.get":{"replay":"yes","why":"OK 1985B"},"mtop.taobao.idle.rtc.config.get":{"replay":"yes","why":"OK 74B"},"mtop.taobao.idle.rule.config":{"replay":"yes","why":"OK 2317B"},"mtop.taobao.idle.subscribe.get":{"replay":"yes","why":"OK 37B"},"mtop.taobao.idle.task.getolivertaskbenefit":{"replay":"yes","why":"OK 1070B"},"mtop.taobao.idle.task.getolivertaskdetail":{"replay":"yes","why":"OK 301B"},"mtop.taobao.idle.task.queryolivertasklist":{"replay":"yes","why":"OK 1921B"},"mtop.taobao.idle.task.uploadOliverTaskTrigger":{"replay":"no","why":"FAIL_BIZ_COMMON_PARAM_ILLEGAL"},"mtop.taobao.idle.ticket.channel.feed.query":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idle.trade.bought.get":{"replay":"yes","why":"OK 106B"},"mtop.taobao.idle.trade.recycle.coupon.tips":{"replay":"yes","why":"OK 1476B"},"mtop.taobao.idle.trade.recycle.sold.get":{"replay":"yes","why":"OK 1647B"},"mtop.taobao.idle.treasure.hunt.map.game.refresh":{"replay":"yes","why":"OK 301B"},"mtop.taobao.idle.treasure.hunt.map.init":{"replay":"no","why":"FAIL_BIZ_PARAMETER_ERROR"},"mtop.taobao.idle.treasure.hunt.map.roll":{"replay":"no","why":"FAIL_BIZ_PARAMETER_ERROR"},"mtop.taobao.idle.treasure.layout.info":{"replay":"yes","why":"OK 8201B"},"mtop.taobao.idle.user.action.strategy":{"replay":"yes","why":"OK 44B"},"mtop.taobao.idle.user.check":{"replay":"yes","why":"OK 78B"},"mtop.taobao.idle.user.info.get":{"replay":"yes","why":"OK 1789B"},"mtop.taobao.idle.user.pulloff.items":{"replay":"yes","why":"OK 105B"},"mtop.taobao.idle.user.strategy.list":{"replay":"yes","why":"OK 10369B"},"mtop.taobao.idle.user.strategy.report":{"replay":"yes","why":"SUCCESS"},"mtop.taobao.idle.user.widget.show":{"replay":"yes","why":"OK 1885B"},"mtop.taobao.idle.user.widget.wear":{"replay":"yes","why":"OK 110B"},"mtop.taobao.idle.ut.log.config.get":{"replay":"yes","why":"OK 834B"},"mtop.taobao.idle.ut.log.report":{"replay":"no","why":"FAIL_SYS_INVALID_HTTP_METHOD"},"mtop.taobao.idle.xyh.item.list":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idlecommerce.splash.ads":{"replay":"yes","why":"OK 28B"},"mtop.taobao.idlecommerce.splash.async.ads":{"replay":"yes","why":"OK 28B"},"mtop.taobao.idlehome.home.circle.list":{"replay":"yes","why":"OK 68114B"},"mtop.taobao.idlehome.home.nextfresh":{"replay":"yes","why":"OK 652788B"},"mtop.taobao.idlehome.magic.home.page.list":{"replay":"yes","why":"OK 1965B"},"mtop.taobao.idlehome.user.operator.tracker":{"replay":"yes","why":"OK 4454B"},"mtop.taobao.idleitem.badwords.prepubcheck":{"replay":"yes","why":"OK 212B"},"mtop.taobao.idleitem.prepublish.check":{"replay":"yes","why":"OK 130B"},"mtop.taobao.idlemessage.channel.notice.query":{"replay":"yes","why":"OK 14B"},"mtop.taobao.idlemessage.common.session.event.report":{"replay":"yes","why":"OK 35B"},"mtop.taobao.idlemessage.extra.message.query":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idlemessage.face.categories.load":{"replay":"yes","why":"OK 30B"},"mtop.taobao.idlemessage.face.emoji.load":{"replay":"yes","why":"OK 47991B"},"mtop.taobao.idlemessage.instant.log":{"replay":"no","why":"FAIL_SYS_INVALID_HTTP_METHOD"},"mtop.taobao.idlemessage.message.sync":{"replay":"yes","why":"OK 1921B"},"mtop.taobao.idlemessage.profile.notice.list":{"replay":"yes","why":"OK 1120B"},"mtop.taobao.idlemessage.receive.classify.query":{"replay":"yes","why":"OK 22B"},"mtop.taobao.idlemessage.relation.message.read":{"replay":"no","why":"FAIL_BIZ_MSG_NOT_FOUND"},"mtop.taobao.idlemessage.riskmsg.get":{"replay":"yes","why":"OK 104B"},"mtop.taobao.idlemessage.session.extension.query":{"replay":"yes","why":"OK 77B"},"mtop.taobao.idlemessage.session.service.headinfo":{"replay":"yes","why":"OK 409B"},"mtop.taobao.idlemessage.session.sync":{"replay":"yes","why":"OK 13421B"},"mtop.taobao.idlemessage.squad.tag.query":{"replay":"yes","why":"OK 19B"},"mtop.taobao.idlemessage.super.top.session.query":{"replay":"yes","why":"OK 69B"},"mtop.taobao.idlemessage.user.query":{"replay":"yes","why":"OK 378B"},"mtop.taobao.idlemessage.user.query.top.resource":{"replay":"yes","why":"SUCCESS"},"mtop.taobao.idlemtopsearch.detailpage.search.shade":{"replay":"yes","why":"OK 108B"},"mtop.taobao.idlemtopsearch.item.search.activate":{"replay":"yes","why":"OK 359B"},"mtop.taobao.idlemtopsearch.market.dealprice":{"replay":"no","why":"FAIL_BIZ_interceptor_unknow_err"},"mtop.taobao.idlemtopsearch.search":{"replay":"no","why":"FAIL_BIZ_COMMON_PARAM_ILLEGAL"},"mtop.taobao.idlemtopsearch.search.activate.tablist":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idlemtopsearch.search.discover":{"replay":"yes","why":"OK 401B"},"mtop.taobao.idlemtopsearch.search.shade":{"replay":"yes","why":"OK 434B"},"mtop.taobao.idlemtopsearch.search.suggest":{"replay":"yes","why":"OK 182B"},"mtop.taobao.idlemtopsearch.user.search":{"replay":"no","why":"FAIL_BIZ_COMMON_SYSTEM_ERROR2"},"mtop.taobao.idlemtopsearch.userfollow.query":{"replay":"yes","why":"OK 136B"},"mtop.taobao.idlemtopsearch.userpicfollow.query":{"replay":"yes","why":"OK 51B"},"mtop.taobao.idlemtopsearch.windvane.query":{"replay":"yes","why":"OK 1901B"},"mtop.taobao.idletem.publish.cpv.change.strategy":{"replay":"no","why":"ANDROID_SYS_PARAM_TOO_LONG"},"mtop.taobao.paramservice.request":{"replay":"yes","why":"OK 31B"},"mtop.taobao.pentaprism.scene.trigger":{"replay":"yes","why":"OK 127B"},"mtop.taobao.search.highway.upload":{"replay":"no","why":"FAIL_SYS_SERVICE_TIMEOUT"},"mtop.xianyu.item.track.query":{"replay":"yes","why":"OK 1901B"}}"""

    /** 调用链类名 → 中文说明 */
    @JvmField val CALLER_DOC: String = """{"_comment":"调用链里出现的类名 → 中文说明。导出报告时用它把英文类名翻成中文，前端显示为「中文名（英文类名）」。","classes":{"MtopExtSDKHandler":"mtop 扩展 SDK 处理器","BaseHandler":"远端对象基础处理器","EasyMtopSDK":"轻量 mtop 调用器","MtopSend":"请求发送器","MtopLauncher":"mtop 启动器","Sync":"数据同步器","RulesManager":"规则管理器","FBDocument":"Flybird H5 文档","HighwayMtopUtil":"埋点上报工具","MRTConfigRequest":"端侧模型配置请求","MNNCV":"端侧视觉模型（MNN-CV）","MRTTaobaoAdapter":"端侧模型淘系适配器","MRTConfiguration":"端侧模型配置中心","OrangeConfigListenerStub":"橙心配置变更监听","OrangeConfigImpl":"橙心配置实现","DataSender":"DAI 数据上报器","MtopBuilder":"mtop 请求构建器","MtopBusiness":"mtop 业务请求入口","MtopRequest":"mtop 请求对象","MtopResponse":"mtop 响应对象","HookBridge":"Vector 方法桥","Vector_":"Vector 框架入口","MtopHooks":"U闲 抓包钩子","CallerTrace":"U闲 调用栈分析","TrafficStore":"U闲 抓包存储","TrafficExport":"U闲 报告导出"},"layers":{"kt":"Kotlin","java":"Java","h5":"H5","weex":"Weex","fl":"Flutter","sdk":"SDK","native":"Native"},"replay":{"yes":"可重放","no":"不可重放","unk":"待验证"}}"""

    /** 词典对象（classes / layers / replay），避免每处都重复解析 */
    @JvmField val LABELS: org.json.JSONObject = org.json.JSONObject(CALLER_DOC)
}
