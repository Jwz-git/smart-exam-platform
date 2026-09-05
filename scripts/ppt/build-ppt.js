/**
 * 答辩 PPT 生成器
 *
 * 设计意图：所有幻灯片先被描述成一组与渲染后端无关的绘制原语（rect / text / img），
 * 再由两个后端分别输出。之所以不直接调 pptxgenjs：本机没有 LibreOffice，无法把 .pptx
 * 渲染成图片做视觉检查，因此额外输出一份坐标完全一致的 HTML 镜像用于截图核对。
 * 两个后端读同一份 ops，HTML 里看到的位置就是 .pptx 里的位置。
 *
 * 用法：npm install && node build-ppt.js
 * 输出：docs/答辩PPT.pptx  与  preview/index.html（预览页不进仓库）
 */
'use strict';

const fs = require('fs');
const path = require('path');
const PptxGenJS = require('pptxgenjs');

const REPO = path.resolve(__dirname, '..', '..');
const IMG = path.join(REPO, 'docs', 'images');
const OUT_PPTX = path.join(REPO, 'docs', '答辩PPT.pptx');
const OUT_PREVIEW = path.join(__dirname, 'preview');

/* ── 待填信息：拿到组号与成员名单后只改这一段，然后重新 node build-ppt.js ───────── */
const META = {
  groupNo: '第 ×× 组',                 // TODO 组号
  members: [
    { name: '【成员姓名 / 学号】', role: '系统设计、全部代码实现、自动化测试与浏览器走查' },
    { name: '【成员姓名 / 学号】', role: '项目报告撰写（背景、需求、设计、演示、心得六部分）' },
    { name: '【成员姓名 / 学号】', role: '【待填】' },
  ],
  course: '软件开发实践 2 · 2025~2026 学年短学期',
  teacher: '指导教师：杨艳',
  date: '2026 年 9 月',
};

/* ── 版式与配色 ──────────────────────────────────────────────────────────────── */
const W = 13.333, H = 7.5;            // LAYOUT_WIDE
const M = 0.62;                        // 页边距
const CW = W - M * 2;                  // 内容宽度 12.093
const FONT = '微软雅黑';

// 配色取「墨蓝试卷 + 批阅笔橙红」：深蓝主导，冰蓝辅助，橙红只用在关键数字上
const C = {
  inkDeep: '12193A', ink: '1E2761', inkMid: '3A4685',
  ice: 'CADCFC', iceDeep: 'A9C4EE', iceSoft: 'EEF3FD',
  paper: 'FFFFFF', text: '1B2340', muted: '606A8C', line: 'DCE3F2',
  accent: 'E4572E', accentSoft: 'FBE6DE', ok: '1F8A70',
};

const slides = [];                     // [{ dark, ops:[], notes }]
const warnings = [];
let S = null;
function slide(dark = false) { S = { dark, ops: [], notes: '' }; slides.push(S); return S; }
function notes(t) { S.notes = t; }
function push(op) { S.ops.push(op); return op; }

/* ── 文本宽度估算（中文按 1 em、西文按字形粗估），用于自动检查是否溢出 ─────────── */
function textWidth(s, size) {
  let u = 0;
  for (const ch of String(s)) {
    const c = ch.codePointAt(0);
    if (c > 0x2e7f) u += 1.0;                            // CJK 与全角标点
    else if (ch === ' ') u += 0.28;
    else if ('iljtI.,:;!|\'"()[]'.includes(ch)) u += 0.31;
    else if (ch >= 'A' && ch <= 'Z') u += 0.70;
    else if (ch >= '0' && ch <= '9') u += 0.56;
    else if ('mwMW%—→'.includes(ch)) u += 0.92;
    else u += 0.53;
  }
  return u * size / 72;
}

/* ── 绘制原语 ───────────────────────────────────────────────────────────────── */
function rect(o) { return push(Object.assign({ k: 'rect', radius: 0 }, o)); }

// 视觉母题：统一圆角卡片 + 极淡的深蓝投影，全篇复用，不用色条或边框条
function card(o) {
  return push(Object.assign({ k: 'rect', radius: 0.1, fill: C.paper, shadow: true }, o));
}

function text(o) {
  const t = Object.assign({ k: 'text', size: 14, color: C.text, lh: 1.42, valign: 'top' }, o);
  const wpx = textWidth(t.text, t.size);
  const lines = Math.max(1, Math.ceil(wpx / t.w - 0.001));
  const need = lines * t.size * t.lh / 72;
  if (need > t.h + 0.03) {
    warnings.push(`第 ${slides.length} 页 溢出：${String(t.text).slice(0, 26)}… 需 ${need.toFixed(2)}" / 有 ${t.h.toFixed(2)}"（估 ${lines} 行）`);
  }
  return push(t);
}

function img(o) { return push(Object.assign({ k: 'img' }, o)); }

/* ── 复合构件 ───────────────────────────────────────────────────────────────── */
// 圆角标签：本篇的重复视觉元素，用于章节号、分组名、要点编号
function chip(o) {
  const size = o.size || 11;
  const padX = o.padX == null ? 0.16 : o.padX;
  const h = o.h || size * 2.1 / 72;
  const w = o.w || textWidth(o.text, size) + padX * 2;
  rect({ x: o.x, y: o.y, w, h, radius: h / 2, fill: o.fill || C.ice, shadow: false });
  text({ x: o.x, y: o.y, w, h, text: o.text, size, bold: o.bold !== false, color: o.color || C.ink, align: 'center', valign: 'middle' });
  return w;
}

// 页头：小标签 + 短标题 + 说明行。中文标题保持 4—8 字，长限定语一律降到说明行
function header(o) {
  const dark = S.dark;
  if (o.kicker) chip({ x: M, y: 0.42, text: o.kicker, size: 11, fill: dark ? C.inkMid : C.ice, color: dark ? C.ice : C.ink });
  text({ x: M, y: 0.76, w: CW * 0.72, h: 0.62, text: o.title, size: o.size || 32, bold: true, color: dark ? C.paper : C.ink, valign: 'middle' });
  if (o.sub) text({ x: M, y: 1.4, w: CW, h: 0.3, text: o.sub, size: 12.5, color: dark ? C.iceDeep : C.muted, valign: 'middle' });
  if (o.right) text({ x: M + CW * 0.55, y: 0.86, w: CW * 0.45, h: 0.42, text: o.right, size: 12.5, color: dark ? C.iceDeep : C.muted, align: 'right', valign: 'middle' });
  return o.sub ? 1.86 : 1.56;
}

// 页脚：只放页码与出处，不跨页画横条
function footer(o) {
  const dark = S.dark;
  if (o && o.note) text({ x: M, y: 7.02, w: CW - 1.0, h: 0.3, text: o.note, size: 9.5, color: dark ? C.inkMid : C.muted, valign: 'middle' });
  text({ x: W - M - 0.9, y: 7.02, w: 0.9, h: 0.3, text: String(slides.length).padStart(2, '0'), size: 9.5, color: dark ? C.inkMid : C.muted, align: 'right', valign: 'middle' });
}

// 大数字：用于「运行结果分析」与「测试与验证」两页的关键结论
function stat(o) {
  card({ x: o.x, y: o.y, w: o.w, h: o.h, fill: o.fill || C.iceSoft, shadow: false });
  text({ x: o.x + 0.18, y: o.y + 0.22, w: o.w - 0.36, h: 0.62, text: o.value, size: o.vsize || 34, bold: true, color: o.vcolor || C.ink, valign: 'middle' });
  text({ x: o.x + 0.18, y: o.y + 0.86, w: o.w - 0.36, h: o.h - 1.02, text: o.label, size: 11.5, color: C.muted, lh: 1.4 });
}

// 编号卡片：目录、设计决策、心得体会三页共用
function numCard(o) {
  const dark = S.dark;
  card({ x: o.x, y: o.y, w: o.w, h: o.h, fill: o.fill || (dark ? C.ink : C.paper), shadow: !dark });
  chip({ x: o.x + 0.22, y: o.y + 0.24, text: o.no, size: 10.5, fill: dark ? C.inkMid : C.ice, color: dark ? C.ice : C.ink, padX: 0.13 });
  text({ x: o.x + 0.22, y: o.y + 0.62, w: o.w - 0.44, h: o.th || 0.34, text: o.title, size: o.tsize || 15.5, bold: true, color: dark ? C.paper : C.ink });
  text({ x: o.x + 0.22, y: o.y + 0.62 + (o.th || 0.34) + 0.08, w: o.w - 0.44, h: o.h - 1.06 - ((o.th || 0.34) - 0.34), text: o.body, size: o.bsize || 11.5, color: dark ? C.iceDeep : C.muted, lh: 1.5 });
}

// 要点行：圆角标签 + 一行说明，替代默认项目符号
function pointRow(o) {
  const dark = S.dark;
  chip({ x: o.x, y: o.y + 0.02, text: o.tag, size: o.tagSize || 10.5, fill: o.tagFill || (dark ? C.inkMid : C.accentSoft), color: o.tagColor || (dark ? C.ice : C.accent), padX: 0.13 });
  const tw = (o.tagW != null ? o.tagW : textWidth(o.tag, o.tagSize || 10.5) + 0.26) + 0.16;
  text({ x: o.x + tw, y: o.y, w: o.w - tw, h: o.h, text: o.text, size: o.size || 12.5, color: dark ? C.ice : C.text, lh: 1.45 });
}

/* ══ 第 1 页 · 封面 ═══════════════════════════════════════════════════════════ */
slide(true);
// 母题铺垫：右上角 2×2 选项卡，B 为标准答案——直接点题「客观题自动判分」
{
  const gx = 10.2, gy = 1.62, s = 1.02, g = 0.15;
  ['A', 'B', 'C', 'D'].forEach((k, i) => {
    const x = gx + (i % 2) * (s + g), y = gy + Math.floor(i / 2) * (s + g);
    const hit = k === 'B';
    rect({ x, y, w: s, h: s, radius: 0.14, fill: hit ? C.accent : C.ink, shadow: false });
    text({ x, y, w: s, h: s, text: hit ? 'B ✓' : k, size: 17, bold: true, color: hit ? C.paper : C.inkMid, align: 'center', valign: 'middle' });
  });
}
chip({ x: M, y: 1.5, text: META.course + ' · 项目答辩', size: 11.5, fill: C.inkMid, color: C.ice });
text({ x: M, y: 2.02, w: 9.1, h: 0.92, text: '智能在线题库与组卷系统', size: 40, bold: true, color: C.paper, valign: 'middle' });
text({ x: M, y: 3.02, w: 8.6, h: 0.4, text: '基于 Java 的 Web 应用程序设计 · 前后端分离', size: 17, color: C.ice, valign: 'middle' });
card({ x: M, y: 3.66, w: 8.9, h: 1.0, fill: C.ink, shadow: false });
text({
  x: M + 0.2, y: 3.78, w: 8.5, h: 0.76, size: 13.5, color: C.ice, lh: 1.5,
  text: '教师维护题库 → 创建并发布试卷 → 学生在线答题 → 系统自动判客观题 → 教师批阅主观题 → 确认公布并查看成绩与名次',
});
card({ x: M, y: 5.12, w: 2.5, h: 1.52, fill: C.ink, shadow: false });
text({ x: M + 0.2, y: 5.3, w: 2.1, h: 0.28, text: '组号', size: 10.5, color: C.iceDeep });
text({ x: M + 0.2, y: 5.66, w: 2.1, h: 0.5, text: META.groupNo, size: 20, bold: true, color: C.paper, valign: 'middle' });
card({ x: M + 2.7, y: 5.12, w: 6.2, h: 1.52, fill: C.ink, shadow: false });
text({ x: M + 2.9, y: 5.3, w: 5.8, h: 0.28, text: '小组成员与分工', size: 10.5, color: C.iceDeep });
META.members.forEach((m, i) => {
  text({ x: M + 2.9, y: 5.64 + i * 0.32, w: 2.3, h: 0.3, text: m.name, size: 10.5, bold: true, color: C.paper, valign: 'middle' });
  text({ x: M + 5.24, y: 5.64 + i * 0.32, w: 3.46, h: 0.3, text: m.role, size: 10, color: C.iceDeep, valign: 'middle' });
});
card({ x: 9.72, y: 5.12, w: 2.99, h: 1.52, fill: C.ink, shadow: false });
[META.teacher, '汇报日期：' + META.date, '技术路线：Vue 3 + Spring Boot 3'].forEach((t, i) => {
  text({ x: 9.92, y: 5.34 + i * 0.36, w: 2.6, h: 0.32, text: t, size: 10.5, color: i === 2 ? C.iceDeep : C.ice, valign: 'middle' });
});
notes('自我介绍与选题说明。一句话说清系统做什么：教师出题组卷，学生在线考试，系统判客观题，教师批主观题，公布后学生看成绩与名次。右上角 2×2 选项卡是本次汇报的视觉线索——B 为标准答案，对应客观题自动判分。');

/* ══ 第 2 页 · 汇报大纲 ══════════════════════════════════════════════════════ */
slide();
{
  const top = header({ kicker: '汇报大纲', title: '六个部分', sub: '按课程对项目报告的要求组织，每部分对应报告正文的一节，数字均可在仓库中复核' });
  const cw = (CW - 0.68) / 3, ch = 1.95;
  const items = [
    ['01', '项目背景', '纸质测验的成本结构，与三条不可妥协的工程约束'],
    ['02', '组内分工', '成员职责、工作量分布，以及质量如何被复核'],
    ['03', '需求分析', '三类角色、14 个功能模块、一条核心业务流程'],
    ['04', '设计文档', '系统架构、界面原型、47 个 API、11 张业务表'],
    ['05', '系统演示', '真实 MySQL 上走查留存的 39 张截图与运行结果'],
    ['06', '心得体会', '四个踩过的坑，以及如实列出的十项已知边界'],
  ];
  items.forEach(([no, t, b], i) => {
    numCard({
      x: M + (i % 3) * (cw + 0.34), y: top + Math.floor(i / 3) * (ch + 0.3),
      w: cw, h: ch, no, title: t, body: b,
    });
  });
  card({ x: M, y: 6.3, w: CW, h: 0.6, fill: C.iceSoft, shadow: false });
  text({
    x: M + 0.2, y: 6.3, w: CW - 0.4, h: 0.6, size: 11.5, color: C.ink, valign: 'middle',
    text: '课程评价要点：文档质量 · 设计方案质量 · 分工合理性 · 创新性 · 对程序运行结果的分析 · 项目实施思考（后两项由第 16、18 页专门回答）',
  });
  footer();
}
notes('用三十秒交代汇报结构，说明六个部分与课程要求一一对应，并提示最后两个评价要点有专门的页面回答。');
