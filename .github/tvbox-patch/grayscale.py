#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
把 TVBox 的配色整体改成灰黑白（灰阶）。

由 .github/tvbox-patch/apply-patch.sh 在 CI 构建时调用，工作目录是源码仓库根。

三条规则，按顺序生效：
  1) OVERRIDES：语义色手工指定（聚焦/选中必须最亮、文字必须够亮，否则灰度化后没法用）
  2) 其余所有 hex 色值：按**亮度等价**转灰（R=G=B=round(0.299R+0.587G+0.114B)），
     保留 alpha。这样色相消失但明暗关系不变，且不存在漏网之鱼。
  3) CSS 函数：hsla() 把饱和度直接置 0；rgb()/rgba() 三个通道取同一个亮度值。

另外对 9 个 Java 文件做**定点字面量替换**（都是"选中态/高亮/状态徽标"这类语义色）。
每条替换都声明期望次数；对不上就打印告警 —— 上游改了这些行时能在 CI 日志里看见，
而不是静默失效。

不做的事：
  * **不对 Java 源码跑 hex 批量替换**。UA.java 里有一堆 User-Agent 字符串
    （形如 0x6700143B、0x2f650000），批量替换会把它们改坏。
  * 不动 player / quickjs / pyramid 模块的资源（实测其中没有任何硬编码颜色）。
"""

import io
import os
import re
import sys

RES_DIRS = ['app/src/main/res', 'player/src/main/res', 'quickjs/src/main/res', 'pyramid/src/main/res']
JAVA = 'app/src/main/java/com/github/tvbox/osc'

# ---------------------------------------------------------------- 1) 语义覆盖
# 灰度化最容易踩的坑：把"聚焦态/选中态"也按亮度换成中灰，遥控器操作就分不清选中项了。
# 所以下面这些按用途指定，不参与自动转换。
OVERRIDES = {
    # 聚焦 / 选中 / 激活：纯白，最强对比
    '#1890ff': '#ffffff', '#cc1890ff': '#ccffffff', '#331890ff': '#33ffffff',
    '#02f8e1': '#ffffff', '#cc02f8e1': '#ccffffff',
    '#ff5f00': '#ffffff',                       # 筛选按钮的激活态
    # 主题三件套（AppTheme 用）
    '#6200ee': '#ededed', '#3700b3': '#bdbdbd', '#03dac5': '#9e9e9e',
    # 深色背景上的文字/滑块：浅灰，保证可读
    '#ff4081': '#e6e6e6',                        # 进度条描边与滑块
    '#07c160': '#e8e8e8',                        # style.css 主按钮底色（微信绿）
    '#06ae56': '#dcdcdc',
    '#10aeff': '#e0e0e0', '#1196ff': '#e0e0e0', '#1485ee': '#e0e0e0',
    '#0075e2': '#dcdcdc', '#007dbb': '#dcdcdc',
    # ⚠️ 下面这行曾经写成 '#e0e0e0e'（7 位），aapt2 直接报 "Invalid <color>" 编译失败。
    #    现在 validate_overrides() 会在跑之前先拦一遍这种错字。
    '#cbf46a': '#e0e0e0', '#90ca09': '#dcdcdc', '#ffce7900': '#e0e0e0',
    '#00ff0a': '#dcdcdc', '#00ff00': '#dcdcdc',
    '#bb8e00': '#d0d0d0', '#4f8400': '#c8c8c8', '#74a800': '#c8c8c8', '#2e8800': '#a8a8a8',
    '#95ec69': '#d8d8d8', '#91d300': '#d0d0d0',
}

# ---------------------------------------------------------------- 2) Java 定点替换
# (相对路径, 旧字面量, 新字面量, 期望出现次数)
JAVA_RULES = [
    # 选中态文字：青 -> 白（这是用户最常看到的"选中"反馈）
    ('ui/adapter/ButtonAdapter.java', 'item.setTextColor(0xff02f8e1);',
     'item.setTextColor(0xffffffff);', 1),
    ('ui/adapter/SelectDialogAdapter.java', 'view.setTextColor(0xff02f8e1);',
     'view.setTextColor(0xffffffff);', 1),
    # EPG 节目名/时间：蓝 -> 浅灰（深色底上文字要够亮）
    ('ui/adapter/MyEpgAdapter.java', 'Color.rgb(0, 153, 255)', 'Color.rgb(224, 224, 224)', 2),
    # 直播 EPG 的状态徽标：「直播中」亮底黑字、「回看」暗底白字、「回看中」中灰底白字
    ('ui/adapter/LiveEpgAdapter.java',
     'shiyi.setBackgroundColor(Color.YELLOW);\n            shiyi.setText("直播中");\n'
     '            shiyi.setTextColor(Color.RED);',
     'shiyi.setBackgroundColor(Color.LTGRAY);\n            shiyi.setText("直播中");\n'
     '            shiyi.setTextColor(Color.BLACK);', 1),
    ('ui/adapter/LiveEpgAdapter.java',
     'shiyi.setBackgroundColor(Color.BLUE);', 'shiyi.setBackgroundColor(Color.DKGRAY);', 1),
    ('ui/adapter/LiveEpgAdapter.java',
     'shiyi.setTextColor(Color.RED);\n                shiyi.setBackgroundColor(Color.rgb(12, 255, 0));',
     'shiyi.setTextColor(Color.WHITE);\n                shiyi.setBackgroundColor(Color.GRAY);', 1),
    ('ui/adapter/LiveEpgAdapter.java',
     'shiyi.setBackgroundColor(Color.YELLOW);\n                    shiyi.setText("直播中");\n'
     '                    shiyi.setTextColor(Color.RED);',
     'shiyi.setBackgroundColor(Color.LTGRAY);\n                    shiyi.setText("直播中");\n'
     '                    shiyi.setTextColor(Color.BLACK);', 1),
    # 歌词：当前行绿 -> 白；未唱到行黄 -> 中灰
    ('subtitle/widget/SimpleSubtitleView.java',
     'line.lyricCurrent ? Color.GREEN : Color.YELLOW',
     'line.lyricCurrent ? Color.WHITE : Color.LTGRAY', 1),
    # 音频波形（字节跳动红）
    ('ui/tv/widget/AudioWaveView.java', 'paint.setColor(Color.RED);',
     'paint.setColor(Color.DKGRAY);', 1),
    # 播放器进度条描边（与 OVERRIDES 里的 #ff4081 呼应，这里是 parseColor 形式）
    ('player/controller/VodController.java', 'Color.parseColor("#FF4081")',
     'Color.parseColor("#E6E6E6")', 1),
    # 窗口背景：忽略 config 里的 wallpaper，固定用灰阶 app_bg
    #   （配置里带 wallpaper 时，App 会下载它当背景 —— 那样光改 drawable 是白改。
    #     BaseActivity 没有 import ApiConfig，所以这里直接绕过 wallpaper 分支，
    #     不碰它的配置读取，改动面最小、也不可能编译不过。）
    ('base/BaseActivity.java',
     '        if (!force && globalWp != null)\n            getWindow().setBackgroundDrawable(globalWp);',
     '        // [灰度定制] 忽略 config 里的 wallpaper，窗口背景固定用灰阶 app_bg',
     1),
    ('base/BaseActivity.java',
     '        if (globalWp != null)\n            getWindow().setBackgroundDrawable(globalWp);\n'
     '        else\n            getWindow().setBackgroundDrawableResource(R.drawable.app_bg);',
     '        // [灰度定制] 不用 wallpaper（哪怕本机缓存过），直接给灰阶背景\n'
     '        getWindow().setBackgroundDrawableResource(R.drawable.app_bg);',
     1),
]

# 弹幕调色板：16 色 → 16 级灰阶（保留亮度差，弹幕在视频上更易读）
DANMU_GREY = ['#ffffff', '#f2f2f2', '#e6e6e6', '#d9d9d9', '#cccccc', '#bfbfbf', '#b3b3b3',
              '#a6a6a6', '#999999', '#8c8c8c', '#808080', '#737373', '#666666', '#595959',
              '#4d4d4d', '#404040']


def lum(r, g, b):
    """sRGB 亮度（整数运算，与设计工具的 Rec.601 加权一致）"""
    return int(round(0.299 * r + 0.587 * g + 0.114 * b))


def grey_hex(h):
    """#rgb / #rrggbb / #aarrggbb → 等亮度灰，alpha 原样保留。

    ⚠️ Android 资源用的是 **#AARRGGBB**（alpha 在前），例如 `#66000000` 是 40% 的黑。
    千万别按 CSS 的 #RRGGBBAA 去解析，否则 alpha 会被挪到末尾：
    `#99000000` 会被算成 `#2e2e2e00`（变成全透明，界面直接花掉）。
    本项目的 style.css 里不含 8 位 hex（只有 hsla/rgba/6 位/3 位），所以不必分格式处理。
    """
    s = h[1:].lower()
    if len(s) == 3:
        r, g, b = (int(c * 2, 16) for c in s)
        a = ''
    elif len(s) == 6:
        r, g, b = (int(s[i:i + 2], 16) for i in (0, 2, 4))
        a = ''
    elif len(s) == 8:
        a, rgb = s[0:2], s[2:8]
        r, g, b = (int(rgb[i:i + 2], 16) for i in (0, 2, 4))
    else:
        return h  # 例如 CSS 的 #rgba（本项目未出现）；遇到就原样放过，不瞎猜
    y = lum(r, g, b)
    if a:
        # Android 是 #AARRGGBB：alpha 必须写在**前面**。
        # 写成 #RRGGBBAA 的话 `#cc181e28` 会变成 `#1d1d1dcc`，被读成 alpha=1d 的彩色。
        return '#%s%02x%02x%02x' % (a, y, y, y)
    return '#%02x%02x%02x' % (y, y, y)


HEX_RE = re.compile(r'#[0-9a-fA-F]{3,8}\b')
# hsla / rgb 必须**整段匹配再整体重建**。
# 只匹配前两个参数再拼字符串，会产出 5 个参数的 hsla(...) —— 那是非法 CSS，WebView 会把这条
# 声明整个丢弃（界面直接掉色），而且每跑一次就多追加一段，完全不幂等。
HSLA_RE = re.compile(
    r'hsla\(\s*([-+0-9.]+)\s*,\s*([-+0-9.]+)%\s*,\s*([-+0-9.]+)%\s*,\s*([-+0-9.]+)\s*\)', re.I)
RGB_RE = re.compile(r'rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)')
CSS_ARG_RE = re.compile(r'\b(hsla?|rgba?)\(([^()]*)\)', re.I)
VALID_HEX_RE = re.compile(r'^#(?:[0-9a-fA-F]{3}|[0-9a-fA-F]{4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$')


def validate_overrides():
    """跑之前先自检 OVERRIDES 表：键值都必须是合法色值，且目标值本身得是灰的。

    这道闸门是被真实事故逼出来的：表里写过 '#e0e0e0e'（**7 位**）——
    aapt2 只接受 3/4/6/8 位，于是 colors.xml 报 "Invalid <color>"，
    整个 :app:mergeXxxReleaseResources 失败，一轮 8 分钟的构建白跑。
    （同一批错字在 style.css 里的那 3 处更阴：CSS 会静默忽略，颜色留着不变。）
    """
    bad = []
    for k, v in OVERRIDES.items():
        if not VALID_HEX_RE.match(k):
            bad.append('键 %s 不是合法色值' % k)
        if not VALID_HEX_RE.match(v):
            bad.append('值 %s -> %s：%s 不是合法色值（位数只能 3/4/6/8）' % (k, v, v))
            continue
        s = v[1:].lower()
        rgb = s[2:] if len(s) == 8 else s
        r, g, b = int(rgb[0:2], 16), int(rgb[2:4], 16), int(rgb[4:6], 16)
        if max(r, g, b) - min(r, g, b) > 6:
            bad.append('值 %s -> %s：目标色不是灰' % (k, v))
    # 大小写重复的键等于没写（查找前会转小写），顺手报出来
    seen = {}
    for k in OVERRIDES:
        lk = k.lower()
        if lk in seen:
            bad.append('键 %s 与 %s 大小写重复，后者永远匹配不上' % (k, seen[lk]))
        seen[lk] = k
    if bad:
        raise SystemExit('[gray] OVERRIDES 表有问题，已中止：\n  - ' + '\n  - '.join(bad))
    print('[gray] OVERRIDES 自检通过（%d 条，键值合法且目标色均为灰）' % len(OVERRIDES))


def convert_text(text):
    stats = {'hex': 0, 'override': 0, 'hsla': 0, 'rgba': 0}

    def hex_sub(m):
        raw = m.group(0)
        low = raw.lower()
        if low in OVERRIDES:
            out = OVERRIDES[low]
            stats['override'] += 1
        else:
            out = grey_hex(low)
            stats['hex'] += 1
        # 只在这里断言：自己**生成**的色值必须是合法的（3/4/6/8 位）。
        # 千万别改成"事后扫全文件找非法 hex"——res 里有大量 XML 数字字符实体
        # （android:text="&#x51C6;&#x5907;…" 这种），会把它们误判成色值而误杀。
        if not VALID_HEX_RE.match(out):
            raise SystemExit('[gray] 生成了非法色值 %s（来源 %s）——位数只能 3/4/6/8' % (out, raw))
        return out

    text = HEX_RE.sub(hex_sub, text)

    # hsla(h, s%, l%, a) -> hsla(0, 0%, l%, a)：色相与饱和度归零，保留明度与透明度
    def hsla_sub(m):
        stats['hsla'] += 1
        return 'hsla(0, 0%%, %s%%, %s)' % (m.group(3), m.group(4))

    text = HSLA_RE.sub(hsla_sub, text)

    # rgb(r,g,b) / rgba(r,g,b,a) -> 三个通道取同一亮度值，alpha 原样保留
    def rgb_sub(m):
        stats['rgba'] += 1
        y = lum(int(m.group(1)), int(m.group(2)), int(m.group(3)))
        name = m.group(0)[:m.group(0).index('(')]
        return '%s(%d, %d, %d' % (name, y, y, y)

    text = RGB_RE.sub(rgb_sub, text)
    return text, stats


def check_css(text):
    """转换后自检：hsla 必须 4 个参数、rgb 3 个、rgba 3~4 个。
    参数个数不对的声明会被 WebView 直接丢弃（表现为「部分控件掉色」），必须挡住。"""
    bad = []
    for m in CSS_ARG_RE.finditer(text):
        fn = m.group(1).lower()
        n = len([x for x in re.split(r'\s*,\s*', m.group(2).strip()) if x != ''])
        if fn == 'hsla' and n != 4:
            bad.append('hsla(%s)' % m.group(2).strip())
        elif fn == 'rgb' and n != 3:
            bad.append('rgb(%s)' % m.group(2).strip())
        elif fn == 'rgba' and n not in (3, 4):
            bad.append('rgba(%s)' % m.group(2).strip())
    return bad


def main():
    validate_overrides()
    changed = []
    css_bad = []
    total = {'hex': 0, 'override': 0, 'hsla': 0, 'rgba': 0}

    for base in RES_DIRS:
        for root, _dirs, files in os.walk(base):
            for fn in files:
                if not fn.endswith(('.xml', 'css')):
                    continue
                p = os.path.join(root, fn)
                with io.open(p, encoding='utf-8') as fh:
                    old = fh.read()
                new, st = convert_text(old)
                if new != old:
                    with io.open(p, 'w', encoding='utf-8', newline='\n') as fh:
                        fh.write(new)
                    changed.append((p, st))
                    for k in total:
                        total[k] += st[k]
                if fn.endswith('.css'):
                    # CSS 的颜色函数参数个数必须合法，否则整条声明会被 WebView 丢弃
                    for bad in check_css(new):
                        css_bad.append('%s: %s' % (os.path.basename(p), bad))

    print('[gray] 资源文件 %d 个被改写：hex %d 处（其中语义覆盖 %d）、hsla %d 处、rgb(a) %d 处'
          % (len(changed), total['hex'], total['override'], total['hsla'], total['rgba']))
    for p, st in changed[:6]:
        print('       %s  %s' % (p, st))
    if len(changed) > 6:
        print('       …… 其余 %d 个文件省略' % (len(changed) - 6))
    if css_bad:
        print('[gray] ⚠️ CSS 颜色函数参数个数异常 %d 处（这些声明会被 WebView 丢弃）：' % len(css_bad))
        for b in css_bad[:6]:
            print('        ' + b)
    else:
        print('[gray] CSS 参数自检通过')

    # ---------------- Java 定点替换 ----------------
    miss = []
    hits = 0
    already = 0
    for rel, old, new, want in JAVA_RULES:
        p = os.path.join(JAVA, rel)
        try:
            with io.open(p, encoding='utf-8') as fh:
                src = fh.read()
        except IOError:
            miss.append('%s（文件不存在）' % rel)
            continue
        got = src.count(old)
        if got == 0 and src.count(new) == want:
            # 已经改过了（重复执行时走这里，不算异常）
            already += 1
            continue
        if got != want:
            miss.append('%s：期望 %d 处，实际 %d 处 → %s' % (rel, want, got, old.strip()[:60]))
            continue
        with io.open(p, 'w', encoding='utf-8', newline='\n') as fh:
            fh.write(src.replace(old, new))
        hits += got
        print('[gray] %s：%s' % (rel, old.strip()[:60]))
    if miss:
        print('[gray] ⚠️ 以下替换没对上（上游可能改了这些行，请复核）：')
        for m in miss:
            print('       - %s' % m)
    else:
        print('[gray] Java 定点替换 %d 处全部命中%s'
              % (hits, ('，另有 %d 处此前已应用过' % already) if already else ''))

    # ---------------- 弹幕调色板 ----------------
    p = os.path.join(JAVA, 'util/DanmuHelper.java')
    try:
        with io.open(p, encoding='utf-8') as fh:
            src = fh.read()
        m = re.search(r'(private static final String\[\] PALETTE = new String\[\]\{\s*\n)(.*?)(\s*\n\s*\};)',
                      src, re.S)
        if m:
            if all(c in m.group(2) for c in DANMU_GREY):
                print('[gray] 弹幕调色板：此前已应用过')
            else:
                body = '\n' + ',\n'.join('            "%s"' % c for c in DANMU_GREY) + '        '
                src = src[:m.start(2)] + body + src[m.end(2):]
                with io.open(p, 'w', encoding='utf-8', newline='\n') as fh:
                    fh.write(src)
                print('[gray] 弹幕调色板 → %d 级灰阶' % len(DANMU_GREY))
        else:
            print('[gray] ⚠️ 没找到弹幕 PALETTE 定义，跳过（上游结构变了？）')
    except IOError:
        print('[gray] ⚠️ DanmuHelper.java 不存在，跳过')

    # ---------------- 图片占位随机色 ----------------
    p = os.path.join(JAVA, 'util/ImgUtil.java')
    try:
        with io.open(p, encoding='utf-8') as fh:
            src = fh.read()
        old = 'return Color.argb(255, random.nextInt(256), random.nextInt(256), random.nextInt(256));'
        marker = 'int v = 64 + random.nextInt(160);'
        new = ('// 灰度化：随机灰阶，避免出现彩色占位块\n'
               '        ' + marker + '\n'
               '        return Color.argb(255, v, v, v);')
        if old in src:
            with io.open(p, 'w', encoding='utf-8', newline='\n') as fh:
                fh.write(src.replace(old, new))
            print('[gray] ImgUtil 随机占位色 → 随机灰阶')
        elif marker in src:
            print('[gray] ImgUtil 随机占位色：此前已应用过')
        else:
            print('[gray] ⚠️ 没找到 ImgUtil 的随机色那一行，跳过')
    except IOError:
        print('[gray] ⚠️ ImgUtil.java 不存在，跳过')

    return 0


if __name__ == '__main__':
    sys.exit(main())