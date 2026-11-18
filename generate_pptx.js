// ============================================================
// RemindMe 评审汇报 PPT 生成脚本 v2
// 修复：图标对齐、文字重叠
// ============================================================

const PptxGenJS = require('pptxgenjs');
const pres = new PptxGenJS();

// ============================================================
// SLIDE DIMENSIONS - LAYOUT_16x9
// ============================================================
pres.layout = 'LAYOUT_16x9';
const SLIDE_W = 10;
const SLIDE_H = 5.625;
const MARGIN = 0.6;
const CONTENT_X = MARGIN;
const CONTENT_Y = MARGIN;
const CONTENT_W = SLIDE_W - 2 * MARGIN;
const CONTENT_H = SLIDE_H - 2 * MARGIN;
const CENTER_X = SLIDE_W / 2;
const CENTER_Y = SLIDE_H / 2;

// ============================================================
// COLOR PALETTE
// ============================================================
const COLORS = {
    creamBg: 'FFF5F5',
    creamCard: 'FFFFFF',
    peach: 'FFB5A7',
    peachLight: 'FFCAC0',
    peachSoft: 'FFDFD7',
    peachPale: 'FFF0EC',
    pinkAccent: 'FF8FA3',
    brownDeep: '5C4033',
    brownMid: '8B7355',
    brownLight: 'B59D85',
    brownPale: 'F5E6E0',
    success: '8ED4A6',
    danger: 'FF7B89',
    warning: 'FFB347',
    conflictBg: 'FFF8E7',
    conflictBorder: 'FFE4B5',
    conflictText: '8B4513',
};

const FONTS = {
    title: 'Microsoft YaHei',
    body: 'Microsoft YaHei',
};

// ============================================================
// IMAGE ASSETS (from project report) - 使用绝对路径
// ============================================================
const IMG_DIR = 'd:/RemindMe/RemindMe/项目相关文档/赛道二+加菲猫的千层面+项目报告文档/项目报告附图';
const IMAGES = {
    statusFlow: IMG_DIR + '/8-4.png',       // 状态流展开
    memoryPage: IMG_DIR + '/8-13.png',      // 助手记忆页
    weekView: IMG_DIR + '/8-10.png',        // 周视图
    conflictCard: IMG_DIR + '/8-7.png',     // 冲突卡片
    chatHome: IMG_DIR + '/无冲突-提醒已创建.png',  // 无冲突成功页
    courseConfig: IMG_DIR + '/8-12.png',    // 课表时间配置卡
};

// ============================================================
// CONTAINER SYSTEM
// ============================================================
function parseImageDimensions(path) {
    const match = path.match(/_(\d+)x(\d+)\.(png|jpg|jpeg|gif|webp)$/i);
    if (match) return { width: parseInt(match[1]), height: parseInt(match[2]) };
    return null;
}
function calculateScaledImageOpts(opts) {
    const { path, w: targetW, h: targetH, x = 0, y = 0, ...rest } = opts;
    if (!path || !targetW || !targetH) return opts;
    const dims = parseImageDimensions(path);
    if (!dims) return opts;
    const imgAspect = dims.width / dims.height;
    const targetAspect = targetW / targetH;
    let scaledW, scaledH, offsetX = 0, offsetY = 0;
    if (imgAspect > targetAspect) {
        scaledW = targetW;
        scaledH = targetW / imgAspect;
        offsetY = (targetH - scaledH) / 2;
    } else {
        scaledH = targetH;
        scaledW = targetH * imgAspect;
        offsetX = (targetW - scaledW) / 2;
    }
    return { path, x: x + offsetX, y: y + offsetY, w: scaledW, h: scaledH, ...rest };
}
function createVirtualNode(type, data, parentX = 0, parentY = 0) {
    const opts = data.opts || {};
    const node = {
        type, data,
        absX: parentX + (opts.x || 0),
        absY: parentY + (opts.y || 0),
        w: opts.w || 0, h: opts.h || 0,
        children: []
    };
    node.addShape = function(shapeType, opts = {}) {
        const child = createVirtualNode('shape', { shapeType, opts }, node.absX, node.absY);
        node.children.push(child);
        return child;
    };
    node.addText = function(text, opts = {}) {
        const safeOpts = { fit: "shrink", ...opts };
        const child = createVirtualNode('text', { text, opts: safeOpts }, node.absX, node.absY);
        node.children.push(child);
        return child;
    };
    node.addImage = function(opts = {}) {
        const scaledOpts = calculateScaledImageOpts(opts);
        const child = createVirtualNode('image', { opts: scaledOpts }, node.absX, node.absY);
        node.children.push(child);
        return child;
    };
    node.addTable = function(tableData, opts = {}) {
        const child = createVirtualNode('table', { tableData, opts }, node.absX, node.absY);
        node.children.push(child);
        return child;
    };
    return node;
}
function flattenNode(node, realSlide, pres) {
    const absOpts = { ...node.data.opts, x: node.absX, y: node.absY };
    if (node.type === 'shape') realSlide.addShape(node.data.shapeType, absOpts);
    else if (node.type === 'text') realSlide.addText(node.data.text, absOpts);
    else if (node.type === 'image') realSlide.addImage(absOpts);
    else if (node.type === 'table') realSlide.addTable(node.data.tableData, absOpts);
    node.children.forEach(child => flattenNode(child, realSlide, pres));
}
const originalAddSlide = pres.addSlide.bind(pres);
pres.addSlide = function(options) {
    const realSlide = originalAddSlide(options);
    const virtualSlide = {
        children: [],
        _realSlide: realSlide,
        set background(val) { realSlide.background = val; },
        get background() { return realSlide.background; },
        addShape: function(shapeType, opts = {}) {
            const node = createVirtualNode('shape', { shapeType, opts }, 0, 0);
            this.children.push(node);
            return node;
        },
        addText: function(text, opts = {}) {
            const safeOpts = { fit: "shrink", ...opts };
            const node = createVirtualNode('text', { text, opts: safeOpts }, 0, 0);
            this.children.push(node);
            return node;
        },
        addImage: function(opts = {}) {
            const scaledOpts = calculateScaledImageOpts(opts);
            const node = createVirtualNode('image', { opts: scaledOpts }, 0, 0);
            this.children.push(node);
            return node;
        },
        addTable: function(tableData, opts = {}) {
            const node = createVirtualNode('table', { tableData, opts }, 0, 0);
            this.children.push(node);
            return node;
        },
        addChart: function(chartType, data, opts = {}) {
            realSlide.addChart(chartType, data, opts);
        },
        render: function() {
            this.children.forEach(child => flattenNode(child, realSlide, pres));
        }
    };
    return virtualSlide;
};

// ============================================================
// HELPER FUNCTIONS
// ============================================================

// 圆角卡片
function addCard(slide, x, y, w, h, opts = {}) {
    return slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
        x, y, w, h,
        fill: { color: opts.fill || COLORS.creamCard },
        line: { color: opts.line || COLORS.peachPale, width: opts.lineWidth || 2 },
        rectRadius: opts.radius || 0.12,
        shadow: opts.shadow === false ? undefined : {
            type: 'outer', color: COLORS.peach, blur: 18, offset: 3, angle: 45, opacity: 0.12
        },
    });
}

// 图标圆 + emoji（完全对齐）
function addIconCircle(slide, cx, cy, size, emoji, opts = {}) {
    const x = cx - size / 2;
    const y = cy - size / 2;
    // 圆形背景
    slide.addShape(pres.shapes.OVAL, {
        x, y, w: size, h: size,
        fill: { color: opts.bgColor || COLORS.peachPale },
        line: { color: opts.lineColor || opts.bgColor || COLORS.peachPale, width: opts.lineWidth || 0 },
    });
    // 图标文字 - 与圆完全同位置同尺寸，居中对齐
    slide.addText(emoji, {
        x, y, w: size, h: size,
        fontSize: opts.fontSize || Math.round(size * 32),
        align: 'center',
        valign: 'middle',
        fontFace: 'Segoe UI Emoji',
    });
}

// 截图占位（或真实图片）
function addPlaceholder(slide, x, y, w, h, label, realImage) {
    if (realImage) {
        // 真实图片 - 带圆角卡片边框
        const card = slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
            x, y, w, h,
            fill: { color: COLORS.creamCard },
            line: { color: COLORS.peachLight, width: 2 },
            rectRadius: 0.08,
        });
        card.addImage({
            path: realImage, x: 0.06, y: 0.06, w: w - 0.12, h: h - 0.12,
        });
        return card;
    }
    // 占位符
    const ph = slide.addShape(pres.shapes.ROUNDED_RECTANGLE, {
        x, y, w, h,
        fill: { color: COLORS.peachPale },
        line: { color: COLORS.peachLight, width: 2, dashType: 'dash' },
        rectRadius: 0.08,
    });
    ph.addText(label, {
        x: 0.2, y: h/2 - 0.35, w: w - 0.4, h: 0.5,
        fontSize: 15,
        color: COLORS.brownMid,
        align: 'center',
        valign: 'middle',
        fontFace: FONTS.body,
        bold: true,
    });
    ph.addText('[ 截图占位，替换为真实图片 ]', {
        x: 0.2, y: h/2 + 0.15, w: w - 0.4, h: 0.35,
        fontSize: 11,
        color: COLORS.brownLight,
        align: 'center',
        valign: 'middle',
        fontFace: FONTS.body,
    });
    return ph;
}

// 底部金句
function addQuote(slide, text) {
    slide.addText(text, {
        x: CONTENT_X, y: SLIDE_H - 0.7, w: CONTENT_W, h: 0.4,
        fontSize: 20,
        bold: true,
        color: COLORS.brownDeep,
        align: 'center',
        valign: 'middle',
        fontFace: FONTS.title,
    });
}

// 装饰圆点
function addDecor(slide) {
    // 左上
    slide.addShape(pres.shapes.OVAL, {
        x: 0.25, y: 0.2, w: 0.3, h: 0.3,
        fill: { color: COLORS.peachSoft }, line: { color: COLORS.peachSoft, width: 0 },
    });
    slide.addShape(pres.shapes.OVAL, {
        x: 0.5, y: 0.4, w: 0.16, h: 0.16,
        fill: { color: COLORS.peachPale }, line: { color: COLORS.peachPale, width: 0 },
    });
    // 右下
    slide.addShape(pres.shapes.OVAL, {
        x: SLIDE_W - 0.55, y: SLIDE_H - 0.5, w: 0.3, h: 0.3,
        fill: { color: COLORS.peachSoft }, line: { color: COLORS.peachSoft, width: 0 },
    });
    slide.addShape(pres.shapes.OVAL, {
        x: SLIDE_W - 0.35, y: SLIDE_H - 0.3, w: 0.15, h: 0.15,
        fill: { color: COLORS.peachPale }, line: { color: COLORS.peachPale, width: 0 },
    });
}

// ============================================================
// SLIDE 1: 封面
// ============================================================
function createSlide1() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    const cardW = 7, cardH = 3.5;
    const card = addCard(slide, CENTER_X - cardW/2, CENTER_Y - cardH/2 - 0.15, cardW, cardH, { radius: 0.2 });

    card.addText('RemindMe', {
        x: 0, y: 0.55, w: cardW, h: 1.1,
        fontSize: 52, bold: true, color: COLORS.peach,
        align: 'center', valign: 'middle',
        fontFace: FONTS.title, charSpacing: 6,
    });
    card.addText('校园生活提醒助手', {
        x: 0, y: 1.65, w: cardW, h: 0.55,
        fontSize: 26, color: COLORS.brownDeep,
        align: 'center', valign: 'middle',
        fontFace: FONTS.title, charSpacing: 3,
    });
    card.addText('对话即操作 —— 你只管说，它替你记', {
        x: 0, y: 2.3, w: cardW, h: 0.45,
        fontSize: 17, color: COLORS.brownMid,
        align: 'center', valign: 'middle',
        fontFace: FONTS.body,
    });
    card.addShape(pres.shapes.RECTANGLE, {
        x: cardW/2 - 1, y: 2.9, w: 2, h: 0.035,
        fill: { color: COLORS.peachLight }, line: { color: COLORS.peachLight, width: 0 },
    });

    slide.addText('团队名称：加菲猫的千层面    |    赛道：智能体开发 · 智慧生活', {
        x: CONTENT_X, y: SLIDE_H - 0.6, w: CONTENT_W, h: 0.35,
        fontSize: 13, color: COLORS.brownMid,
        align: 'center', fontFace: FONTS.body,
    });

    slide.render();
}

// ============================================================
// SLIDE 2: 大学生的琐事
// ============================================================
function createSlide2() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    slide.addText('大学生的琐事，比想象中多', {
        x: CONTENT_X, y: CONTENT_Y + 0.05, w: CONTENT_W, h: 0.65,
        fontSize: 34, bold: true, color: COLORS.brownDeep,
        align: 'left', fontFace: FONTS.title, charSpacing: 2,
    });

    const cardW = (CONTENT_W - 0.6) / 3;
    const cardY = CONTENT_Y + 0.85;
    const cardH = 3.1;
    const iconSize = 0.6;
    const iconCx = 0.3 + iconSize / 2;
    const iconCy = 0.35 + iconSize / 2;

    // 卡片1
    const c1 = addCard(slide, CONTENT_X, cardY, cardW, cardH);
    addIconCircle(c1, iconCx, iconCy, iconSize, '📝');
    c1.addText('每天要记住的事', {
        x: 0.3, y: 1.1, w: cardW - 0.6, h: 0.4,
        fontSize: 19, bold: true, color: COLORS.brownDeep,
        fontFace: FONTS.title,
    });
    c1.addText('上课、开会、交作业\n取快递、还书、聚餐\n比赛截止、志愿活动…', {
        x: 0.3, y: 1.65, w: cardW - 0.6, h: 1.3,
        fontSize: 14, color: COLORS.brownMid,
        fontFace: FONTS.body, lineSpacing: 22, valign: 'top',
        autoFit: false, fit: 'none',
    });

    // 卡片2
    const c2x = CONTENT_X + cardW + 0.3;
    const c2 = addCard(slide, c2x, cardY, cardW, cardH);
    addIconCircle(c2, iconCx, iconCy, iconSize, '📨');
    c2.addText('通知的形态', {
        x: 0.3, y: 1.1, w: cardW - 0.6, h: 0.4,
        fontSize: 19, bold: true, color: COLORS.brownDeep,
        fontFace: FONTS.title,
    });
    c2.addText('群消息、公众号、邮件\n文件、口头通知\n散在各处，长短不一', {
        x: 0.3, y: 1.65, w: cardW - 0.6, h: 1.3,
        fontSize: 14, color: COLORS.brownMid,
        fontFace: FONTS.body, lineSpacing: 22, valign: 'top',
        autoFit: false, fit: 'none',
    });

    // 卡片3
    const c3x = CONTENT_X + (cardW + 0.3) * 2;
    const c3 = addCard(slide, c3x, cardY, cardW, cardH);
    addIconCircle(c3, iconCx, iconCy, iconSize, '😮‍💨');
    c3.addText('结果', {
        x: 0.3, y: 1.1, w: cardW - 0.6, h: 0.4,
        fontSize: 19, bold: true, color: COLORS.brownDeep,
        fontFace: FONTS.title,
    });
    c3.addText([
        { text: '看到了，但没接住', options: { fontSize: 16, color: COLORS.danger, bold: true, breakLine: true } },
        { text: '记了，但记错了', options: { fontSize: 14, color: COLORS.brownMid, breakLine: true } },
        { text: '设了，但设错了', options: { fontSize: 14, color: COLORS.brownMid } },
    ], {
        x: 0.3, y: 1.6, w: cardW - 0.6, h: 1.4,
        fontFace: FONTS.body, lineSpacing: 20, valign: 'top',
    });

    addQuote(slide, '不是不认真，是工具不对。');
    slide.render();
}

// ============================================================
// SLIDE 3: 做一个会判断的提醒
// ============================================================
function createSlide3() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    slide.addText('所以，我们想做一个会判断的提醒', {
        x: CONTENT_X, y: CONTENT_Y + 0.05, w: CONTENT_W, h: 0.65,
        fontSize: 34, bold: true, color: COLORS.brownDeep,
        align: 'left', fontFace: FONTS.title, charSpacing: 2,
    });

    const steps = [
        { icon: '👀', title: '观察', desc: '琐事多，通知长，容易漏' },
        { icon: '🤔', title: '判断', desc: '日历要你自己填，不够' },
        { icon: '💡', title: '思路', desc: '让它听懂你，替你判断' },
        { icon: '✨', title: '结论', desc: '不是做日历，是做 Agent', highlight: true },
    ];

    const stepW = (CONTENT_W - 0.9) / 4;  // 减去3个箭头
    const stepH = 2.7;
    const stepY = CONTENT_Y + 1.0;
    const iconSize = 0.65;

    steps.forEach((step, i) => {
        const x = CONTENT_X + i * (stepW + 0.3);
        const card = addCard(slide, x, stepY, stepW, stepH, {
            fill: step.highlight ? COLORS.peachSoft : COLORS.creamCard,
            line: step.highlight ? COLORS.peach : COLORS.peachPale,
            lineWidth: step.highlight ? 3 : 2,
        });

        // 图标圆 - 水平居中
        addIconCircle(card, stepW / 2, 0.35 + iconSize/2, iconSize, step.icon, {
            bgColor: step.highlight ? COLORS.peach : COLORS.peachPale,
        });

        card.addText(step.title, {
            x: 0, y: 1.15, w: stepW, h: 0.45,
            fontSize: step.highlight ? 24 : 20,
            bold: true, color: COLORS.brownDeep,
            align: 'center', fontFace: FONTS.title,
        });

        card.addText(step.desc, {
            x: 0.2, y: 1.75, w: stepW - 0.4, h: 0.85,
            fontSize: 14, color: COLORS.brownMid,
            align: 'center', fontFace: FONTS.body,
            lineSpacing: 20, valign: 'top',
        });

        // 箭头
        if (i < steps.length - 1) {
            slide.addText('→', {
                x: x + stepW + 0.02, y: stepY + stepH/2 - 0.2, w: 0.26, h: 0.4,
                fontSize: 26, bold: true, color: COLORS.peach,
                align: 'center', valign: 'middle',
                fontFace: FONTS.body,
            });
        }
    });

    addQuote(slide, '不是做一个日历，是做一个 Agent。');
    slide.render();
}

// ============================================================
// SLIDE 4: 一份通知，它替你拆
// ============================================================
function createSlide4() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    slide.addText('一份通知，它替你拆', {
        x: CONTENT_X, y: CONTENT_Y + 0.05, w: CONTENT_W, h: 0.65,
        fontSize: 34, bold: true, color: COLORS.brownDeep,
        align: 'left', fontFace: FONTS.title, charSpacing: 2,
    });

    // 左侧通知原文
    const leftW = 4.3;
    const leftH = 3.5;
    const leftY = CONTENT_Y + 0.85;
    const left = addCard(slide, CONTENT_X, leftY, leftW, leftH);

    left.addText('志愿者通知原文', {
        x: 0.25, y: 0.2, w: leftW - 0.5, h: 0.35,
        fontSize: 17, bold: true, color: COLORS.brownDeep,
        fontFace: FONTS.title,
    });

    const noticeLines = [
        { t: '关于招募办公室物资搬运志愿者的通知', b: true, s: 13 },
        { t: '', s: 6 },
        { t: '1. 工作内容：搬运物资，新饭三楼→学生活动中心四楼', s: 12 },
        { t: '2. 集合时间：本周五下午14:00', s: 12, hl: true },
        { t: '3. 集合地点：新饭三楼校团委办公室门口', s: 12, hl: true },
        { t: '4. 招募人数：6人', s: 12, dim: true },
        { t: '5. 注意事项：准时到场，注意安全，可登记志愿时长…', s: 12, dim: true },
    ];

    let yOff = 0.65;
    noticeLines.forEach(line => {
        const h = line.s <= 8 ? 0.12 : 0.36;
        left.addText(line.t, {
            x: 0.25, y: yOff, w: leftW - 0.5, h: h,
            fontSize: line.s || 12,
            bold: line.b || false,
            color: line.dim ? COLORS.brownLight : (line.hl ? COLORS.brownDeep : COLORS.brownMid),
            fontFace: FONTS.body, valign: 'middle',
        });
        yOff += h;
    });

    // 右侧提取结果
    const rightX = CONTENT_X + leftW + 0.4;
    const rightW = CONTENT_W - leftW - 0.4;
    const rightH = 2.9;
    const rightY = CONTENT_Y + 1.1;
    const right = addCard(slide, rightX, rightY, rightW, rightH, {
        fill: COLORS.peachPale, line: COLORS.peach, lineWidth: 3,
    });

    right.addText('✨ 提取结果', {
        x: 0, y: 0.2, w: rightW, h: 0.4,
        fontSize: 19, bold: true, color: COLORS.brownDeep,
        align: 'center', fontFace: FONTS.title,
    });

    const items = [
        { icon: '🕒', text: '本周五 14:00' },
        { icon: '📦', text: '搬运物资' },
        { icon: '📍', text: '新饭三楼校团委办公室门口' },
    ];

    items.forEach((item, i) => {
        const iy = 0.75 + i * 0.7;
        // 圆角条
        right.addShape(pres.shapes.ROUNDED_RECTANGLE, {
            x: 0.25, y: iy, w: rightW - 0.5, h: 0.55,
            fill: { color: COLORS.creamCard },
            line: { color: COLORS.peachLight, width: 1.5 },
            rectRadius: 0.08,
        });
        // 图标 + 文字
        right.addText(item.icon + '   ' + item.text, {
            x: 0.4, y: iy, w: rightW - 0.8, h: 0.55,
            fontSize: i === 2 ? 16 : 18,
            bold: true, color: COLORS.brownDeep,
            valign: 'middle', fontFace: FONTS.body,
        });
    });

    addQuote(slide, '你不需要自己拆通知，粘贴给它就行。');
    slide.render();
}

// ============================================================
// SLIDE 5: 它一步步把事情做完
// ============================================================
function createSlide5() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    slide.addText('它一步步把事情做完', {
        x: CONTENT_X, y: CONTENT_Y + 0.05, w: CONTENT_W, h: 0.65,
        fontSize: 34, bold: true, color: COLORS.brownDeep,
        align: 'left', fontFace: FONTS.title, charSpacing: 2,
    });

    // 左侧截图
    const imgW = 4.8;
    const imgH = 3.6;
    const imgY = CONTENT_Y + 0.85;
    addPlaceholder(slide, CONTENT_X, imgY, imgW, imgH, '对话页 · 状态流展开版', IMAGES.statusFlow);

    // 右侧三行标注
    const rightX = CONTENT_X + imgW + 0.4;
    const rightW = CONTENT_W - imgW - 0.4;
    const annos = [
        { icon: '🧠', title: '它在想', desc: '理解需求，规划执行步骤' },
        { icon: '🔧', title: '它在做', desc: '调用工具，创建提醒，检测冲突' },
        { icon: '✨', title: '它做完了', desc: '返回结果，推送给你' },
    ];

    const iconSize = 0.55;
    annos.forEach((a, i) => {
        const ay = CONTENT_Y + 1.1 + i * 1.05;
        // 图标圆
        addIconCircle(slide, rightX + iconSize/2, ay + iconSize/2, iconSize, a.icon);
        // 标题
        slide.addText(a.title, {
            x: rightX + iconSize + 0.2, y: ay - 0.02, w: rightW - iconSize - 0.2, h: 0.38,
            fontSize: 20, bold: true, color: COLORS.brownDeep,
            fontFace: FONTS.title,
        });
        // 描述
        slide.addText(a.desc, {
            x: rightX + iconSize + 0.2, y: ay + 0.35, w: rightW - iconSize - 0.2, h: 0.35,
            fontSize: 14, color: COLORS.brownMid,
            fontFace: FONTS.body,
        });
    });

    addQuote(slide, '你看到的不是"已保存"，是它一步一步把事情做完。');
    slide.render();
}

// ============================================================
// SLIDE 6: 它不只记事，它认识你
// ============================================================
function createSlide6() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    slide.addText('它不只记事，它认识你', {
        x: CONTENT_X, y: CONTENT_Y + 0.05, w: CONTENT_W, h: 0.65,
        fontSize: 34, bold: true, color: COLORS.brownDeep,
        align: 'left', fontFace: FONTS.title, charSpacing: 2,
    });

    // 主图
    const imgW = 5.8;
    const imgH = 3.5;
    addPlaceholder(slide, CONTENT_X, CONTENT_Y + 0.85, imgW, imgH, '助手记忆页（11 个分类）', IMAGES.memoryPage);

    // 右侧
    const rightX = CONTENT_X + imgW + 0.3;
    const rightW = CONTENT_W - imgW - 0.3;

    // 卡片1：它记什么
    const n1 = addCard(slide, rightX, CONTENT_Y + 0.95, rightW, 1.8);
    n1.addText('它记什么', {
        x: 0.2, y: 0.12, w: rightW - 0.4, h: 0.35,
        fontSize: 17, bold: true, color: COLORS.brownDeep,
        fontFace: FONTS.title,
    });
    const cats = ['个人信息','活动事务','重要日期','计划目标','考试安排','个人习惯','健康状况','饮食偏好','兴趣爱好','语言风格','其他'];
    const catText = cats.map(c => ({ text: '#' + c + '  ', options: { fontSize: 11, color: COLORS.brownMid } }));
    n1.addText(catText, {
        x: 0.2, y: 0.52, w: rightW - 0.4, h: 1.2,
        fontFace: FONTS.body, lineSpacing: 18, valign: 'top',
    });

    // 卡片2：你能改吗
    const n2 = addCard(slide, rightX, CONTENT_Y + 2.9, rightW, 1.3);
    n2.addText('你能改吗', {
        x: 0.2, y: 0.12, w: rightW - 0.4, h: 0.32,
        fontSize: 16, bold: true, color: COLORS.brownDeep,
        fontFace: FONTS.title,
    });
    n2.addText([
        { text: '✅  勾选、删除', options: { fontSize: 12, color: COLORS.brownMid, breakLine: true } },
        { text: '✅  编辑备注', options: { fontSize: 12, color: COLORS.brownMid, breakLine: true } },
        { text: '✅  导出 JSON / TXT', options: { fontSize: 12, color: COLORS.brownMid } },
    ], {
        x: 0.2, y: 0.48, w: rightW - 0.4, h: 0.75,
        fontFace: FONTS.body, lineSpacing: 16, valign: 'top',
    });

    addQuote(slide, '9 条记忆，11 类理解。');
    slide.render();
}

// ============================================================
// SLIDE 7: 不用填模板，它自己认
// ============================================================
function createSlide7() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    slide.addText('不用填模板，它自己认', {
        x: CONTENT_X, y: CONTENT_Y + 0.05, w: CONTENT_W, h: 0.65,
        fontSize: 34, bold: true, color: COLORS.brownDeep,
        align: 'left', fontFace: FONTS.title, charSpacing: 2,
    });

    const cardW = (CONTENT_W - 0.6) / 2;
    const cardH = 3.2;
    const cardY = CONTENT_Y + 0.9;

    // 左侧：别人的做法
    const left = addCard(slide, CONTENT_X, cardY, cardW, cardH, {
        fill: COLORS.brownPale, line: COLORS.brownLight,
    });
    left.addText('别人的做法', {
        x: 0, y: 0.25, w: cardW, h: 0.45,
        fontSize: 21, bold: true, color: COLORS.brownMid,
        align: 'center', fontFace: FONTS.title,
    });
    const leftSteps = ['填指定模板', '适配学校', '上传'];
    leftSteps.forEach((s, i) => {
        const sy = 0.9 + i * 0.7;
        left.addShape(pres.shapes.ROUNDED_RECTANGLE, {
            x: 0.4, y: sy, w: cardW - 0.8, h: 0.5,
            fill: { color: COLORS.creamCard },
            line: { color: COLORS.brownLight, width: 1.5 },
            rectRadius: 0.08,
        });
        left.addText(`${i+1}. ${s}`, {
            x: 0.4, y: sy, w: cardW - 0.8, h: 0.5,
            fontSize: 15, color: COLORS.brownMid,
            align: 'center', valign: 'middle', fontFace: FONTS.body,
        });
    });
    left.addText('你得先按它的规矩整理', {
        x: 0, y: cardH - 0.5, w: cardW, h: 0.35,
        fontSize: 13, color: COLORS.brownLight,
        align: 'center', italic: true, fontFace: FONTS.body,
    });

    // VS 圆
    const vsSize = 0.7;
    slide.addShape(pres.shapes.OVAL, {
        x: CENTER_X - vsSize/2, y: cardY + cardH/2 - vsSize/2, w: vsSize, h: vsSize,
        fill: { color: COLORS.peach }, line: { color: COLORS.peach, width: 0 },
    });
    slide.addText('VS', {
        x: CENTER_X - vsSize/2, y: cardY + cardH/2 - vsSize/2, w: vsSize, h: vsSize,
        fontSize: 18, bold: true, color: 'FFFFFF',
        align: 'center', valign: 'middle', fontFace: FONTS.title,
    });

    // 右侧：RemindMe
    const right = addCard(slide, CONTENT_X + cardW + 0.6, cardY, cardW, cardH, {
        fill: COLORS.peachPale, line: COLORS.peach, lineWidth: 3,
    });
    right.addText('RemindMe', {
        x: 0, y: 0.25, w: cardW, h: 0.45,
        fontSize: 21, bold: true, color: COLORS.peach,
        align: 'center', fontFace: FONTS.title,
    });
    const rightSteps = ['课表文件直接传', '它自己识别', '弹一张时间配置卡'];
    rightSteps.forEach((s, i) => {
        const sy = 0.9 + i * 0.7;
        right.addShape(pres.shapes.ROUNDED_RECTANGLE, {
            x: 0.4, y: sy, w: cardW - 0.8, h: 0.5,
            fill: { color: COLORS.creamCard },
            line: { color: COLORS.peachLight, width: 2 },
            rectRadius: 0.08,
        });
        right.addText(`${i+1}. ${s}`, {
            x: 0.4, y: sy, w: cardW - 0.8, h: 0.5,
            fontSize: 15, bold: true, color: COLORS.brownDeep,
            align: 'center', valign: 'middle', fontFace: FONTS.body,
        });
    });
    right.addText('你不用整理，它替你整理', {
        x: 0, y: cardH - 0.5, w: cardW, h: 0.35,
        fontSize: 13, color: COLORS.brownDeep,
        align: 'center', italic: true, fontFace: FONTS.body,
    });

    slide.addText('支持 Excel、PDF、图片三种格式', {
        x: CONTENT_X, y: SLIDE_H - 1.1, w: CONTENT_W, h: 0.3,
        fontSize: 12, color: COLORS.brownLight,
        align: 'center', fontFace: FONTS.body,
    });

    addQuote(slide, '不是你适应它，是它适应你。');
    slide.render();
}

// ============================================================
// SLIDE 8: 该拦的拦，该过的过
// ============================================================
function createSlide8() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    slide.addText('该拦的拦，该过的过', {
        x: CONTENT_X, y: CONTENT_Y + 0.05, w: CONTENT_W, h: 0.65,
        fontSize: 34, bold: true, color: COLORS.brownDeep,
        align: 'left', fontFace: FONTS.title, charSpacing: 2,
    });

    const cardW = (CONTENT_W - 0.4) / 2;
    const cardH = 3.3;
    const cardY = CONTENT_Y + 0.9;

    // 左侧：无冲突
    const left = addCard(slide, CONTENT_X, cardY, cardW, cardH, {
        fill: COLORS.creamCard, line: COLORS.success, lineWidth: 2.5,
    });
    const lIconSize = 0.45;
    addIconCircle(left, 0.3 + lIconSize/2, 0.3 + lIconSize/2, lIconSize, '✅', {
        bgColor: COLORS.success,
    });
    left.addText('没冲突，直接过', {
        x: 0.9, y: 0.27, w: cardW - 1.1, h: 0.5,
        fontSize: 19, bold: true, color: COLORS.brownDeep,
        valign: 'middle', fontFace: FONTS.title,
    });

    // 截图 - 无冲突（对话首页）
    const ph = left.addShape(pres.shapes.ROUNDED_RECTANGLE, {
        x: 0.25, y: 0.9, w: cardW - 0.5, h: 2.15,
        fill: { color: 'F0FFF4' },
        line: { color: COLORS.success, width: 1.5 },
        rectRadius: 0.08,
    });
    ph.addImage({
        path: IMAGES.chatHome,
        x: 0.06, y: 0.06, w: cardW - 0.5 - 0.12, h: 2.15 - 0.12,
    });

    // 右侧：有冲突
    const right = addCard(slide, CONTENT_X + cardW + 0.4, cardY, cardW, cardH, {
        fill: COLORS.conflictBg, line: COLORS.warning, lineWidth: 2.5,
    });
    addIconCircle(right, 0.3 + lIconSize/2, 0.3 + lIconSize/2, lIconSize, '⚠️', {
        bgColor: COLORS.warning,
    });
    right.addText('有冲突，先问你', {
        x: 0.9, y: 0.27, w: cardW - 1.1, h: 0.5,
        fontSize: 19, bold: true, color: COLORS.conflictText,
        valign: 'middle', fontFace: FONTS.title,
    });

    // 截图 - 冲突卡片
    const ph2 = right.addShape(pres.shapes.ROUNDED_RECTANGLE, {
        x: 0.25, y: 0.9, w: cardW - 0.5, h: 2.15,
        fill: { color: 'FFFCF0' },
        line: { color: COLORS.warning, width: 1.5 },
        rectRadius: 0.08,
    });
    ph2.addImage({
        path: IMAGES.conflictCard,
        x: 0.06, y: 0.06, w: cardW - 0.5 - 0.12, h: 2.15 - 0.12,
    });

    addQuote(slide, '不是每一步都拦你，是该拦的时候才拦。');
    slide.render();
}

// ============================================================
// SLIDE 9: 所有提醒，落在这一张表上
// ============================================================
function createSlide9() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    slide.addText('所有提醒，落在这一张表上', {
        x: CONTENT_X, y: CONTENT_Y + 0.05, w: CONTENT_W, h: 0.65,
        fontSize: 34, bold: true, color: COLORS.brownDeep,
        align: 'left', fontFace: FONTS.title, charSpacing: 2,
    });

    // 主图
    const imgW = 6.0;
    const imgH = 3.5;
    addPlaceholder(slide, CONTENT_X, CONTENT_Y + 0.85, imgW, imgH, '日程页 · 周视图', IMAGES.weekView);

    // 右侧标注
    const rightX = CONTENT_X + imgW + 0.3;
    const rightW = CONTENT_W - imgW - 0.3;
    const labels = [
        { icon: '📅', title: '周视图', desc: '一周安排一目了然' },
        { icon: '🟣', title: '课程来源（COURSE）', desc: '自动导入，自动归类' },
        { icon: '✅', title: '推送状态', desc: '已发 / 未发 清晰可见' },
        { icon: '🌸', title: '经期预测', desc: '下次预计 10-24' },
    ];

    const iconSize = 0.4;
    labels.forEach((l, i) => {
        const ly = CONTENT_Y + 1.0 + i * 0.78;
        addIconCircle(slide, rightX + iconSize/2, ly + iconSize/2, iconSize, l.icon, {
            bgColor: COLORS.peachPale,
        });
        slide.addText(l.title, {
            x: rightX + iconSize + 0.15, y: ly - 0.02, w: rightW - iconSize - 0.15, h: 0.32,
            fontSize: 15, bold: true, color: COLORS.brownDeep,
            fontFace: FONTS.body,
        });
        slide.addText(l.desc, {
            x: rightX + iconSize + 0.15, y: ly + 0.3, w: rightW - iconSize - 0.15, h: 0.28,
            fontSize: 12, color: COLORS.brownMid,
            fontFace: FONTS.body,
        });
    });

    addQuote(slide, '不是创建完就完了，它帮你把日子过明白。');
    slide.render();
}

// ============================================================
// SLIDE 10: 结尾
// ============================================================
function createSlide10() {
    const slide = pres.addSlide();
    slide.background = { color: COLORS.creamBg };
    addDecor(slide);

    const cardW = 7.5, cardH = 3.4;
    const card = addCard(slide, CENTER_X - cardW/2, CENTER_Y - cardH/2 - 0.1, cardW, cardH, {
        radius: 0.22,
    });

    card.addText('RemindMe', {
        x: 0, y: 0.45, w: cardW, h: 0.8,
        fontSize: 42, bold: true, color: COLORS.peach,
        align: 'center', valign: 'middle',
        fontFace: FONTS.title, charSpacing: 4,
    });
    card.addText('你不需要记住所有事。', {
        x: 0, y: 1.35, w: cardW, h: 0.5,
        fontSize: 25, color: COLORS.brownDeep,
        align: 'center', valign: 'middle',
        fontFace: FONTS.title,
    });
    card.addText('RemindMe 替你记住，你享受生活就好。', {
        x: 0, y: 1.9, w: cardW, h: 0.5,
        fontSize: 20, color: COLORS.brownMid,
        align: 'center', valign: 'middle',
        fontFace: FONTS.body,
    });
    card.addShape(pres.shapes.RECTANGLE, {
        x: cardW/2 - 1, y: 2.6, w: 2, h: 0.03,
        fill: { color: COLORS.peachLight }, line: { color: COLORS.peachLight, width: 0 },
    });
    card.addText('谢谢各位老师', {
        x: 0, y: 2.75, w: cardW, h: 0.45,
        fontSize: 19, bold: true, color: COLORS.peach,
        align: 'center', valign: 'middle',
        fontFace: FONTS.title, charSpacing: 2,
    });

    slide.render();
}

// ============================================================
// GENERATE
// ============================================================
console.log('Generating RemindMe presentation v2...');

createSlide1();  console.log('  Slide 1 ✓');
createSlide2();  console.log('  Slide 2 ✓');
createSlide3();  console.log('  Slide 3 ✓');
createSlide4();  console.log('  Slide 4 ✓');
createSlide5();  console.log('  Slide 5 ✓');
createSlide6();  console.log('  Slide 6 ✓');
createSlide7();  console.log('  Slide 7 ✓');
createSlide8();  console.log('  Slide 8 ✓');
createSlide9();  console.log('  Slide 9 ✓');
createSlide10(); console.log('  Slide 10 ✓');

pres.writeFile({ fileName: 'RemindMe_v4.pptx' })
    .then(() => console.log('\n✅ Done: RemindMe_评审汇报.pptx'))
    .catch(err => console.error('Error:', err));
