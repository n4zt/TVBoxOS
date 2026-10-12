#!/usr/bin/env bash
#
# 在上游源码（CI 运行时 clone 出来的 TVBoxOSC/）里注入应用内更新检查。
#
# 用法（工作目录必须是源码仓库根）：
#   apply-patch.sh <update_json_raw_url> <update_json_cdn_url> [mirror_prefixes]
#     mirror_prefixes：逗号分隔的代理前缀，如 https://gh-proxy.com/,https://ghfast.top/
#                      （可选，留空表示不用代理）
#
# 幂等：重复执行不会重复注入。
#
set -euo pipefail

PATCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UTIL_DIR="app/src/main/java/com/github/tvbox/osc/util"
HOME_ACT="app/src/main/java/com/github/tvbox/osc/ui/activity/HomeActivity.java"
FLAVORS="java java32 java64 python python32 python64"

# CI 上是 python3；本机测试可用 PYTHON=... 覆盖
PYTHON_BIN="${PYTHON:-python3}"

UPDATE_JSON_RAW="${1:-}"
UPDATE_JSON_CDN="${2:-}"
UPDATE_JSON_MIRRORS="${3:-}"

# 代理前缀要进 sed 替换串，需转义 \ & | 这三个特殊字符
escape_sed() { printf '%s' "$1" | sed -e 's/[\\&|]/\\&/g'; }

# Git Bash（Windows）上 `pwd` 返回 /c/... 这种 MSYS 路径，Windows 版 python 解析不了；
# Linux runner 上没有 cygpath，原样返回即可。
nativepath() {
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$1"
  else
    printf '%s' "$1"
  fi
}

if [ ! -f "$HOME_ACT" ]; then
  echo "[patch] 找不到 $HOME_ACT，工作目录不对？当前：$(pwd)" >&2
  exit 1
fi

echo "[patch] 1/5 注入 UpdateChecker.java"
mkdir -p "$UTIL_DIR"
cp -f "$PATCH_DIR/UpdateChecker.java" "$UTIL_DIR/UpdateChecker.java"
# 占位符替换（用 | 作分隔符，避免 URL 里的 / 干扰）
if [ -n "$UPDATE_JSON_RAW" ]; then
  sed -i "s|__UPDATE_JSON_RAW__|${UPDATE_JSON_RAW}|g" "$UTIL_DIR/UpdateChecker.java"
fi
if [ -n "$UPDATE_JSON_CDN" ]; then
  sed -i "s|__UPDATE_JSON_CDN__|${UPDATE_JSON_CDN}|g" "$UTIL_DIR/UpdateChecker.java"
fi
if [ -n "$UPDATE_JSON_MIRRORS" ]; then
  sed -i "s|__UPDATE_JSON_MIRRORS__|$(escape_sed "$UPDATE_JSON_MIRRORS")|g" "$UTIL_DIR/UpdateChecker.java"
fi
# 清掉残留占位符，避免把非法字符串编进 APK
sed -i "s|__UPDATE_JSON_RAW__||g; s|__UPDATE_JSON_CDN__||g; s|__UPDATE_JSON_MIRRORS__||g" "$UTIL_DIR/UpdateChecker.java"
grep -n 'UPDATE_JSON_RAW\|UPDATE_JSON_CDN\|UPDATE_JSON_MIRRORS' "$UTIL_DIR/UpdateChecker.java" || true

echo "[patch] 2/5 为 6 个 flavor 生成版本标识资源"
for f in $FLAVORS; do
  mkdir -p "app/src/$f/res/values"
  cat > "app/src/$f/res/values/tvbox_flavor.xml" <<EOF
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="tvbox_flavor" translatable="false">$f</string>
</resources>
EOF
done
ls -1 app/src/*/res/values/tvbox_flavor.xml

echo "[patch] 3/5 注入 import"
if ! grep -q 'com\.github\.tvbox\.osc\.util\.UpdateChecker' "$HOME_ACT"; then
  sed -i 's#^import me\.jessyan\.autosize\.utils\.AutoSizeUtils;$#import me.jessyan.autosize.utils.AutoSizeUtils;\nimport com.github.tvbox.osc.util.UpdateChecker;#' "$HOME_ACT"
  echo "[patch] 已插入 import"
else
  echo "[patch] import 已存在，跳过"
fi

echo "[patch] 4/5 在 init() 末尾追加 UpdateChecker.check(this)"
"$PYTHON_BIN" - "$HOME_ACT" <<'PY'
import io, sys

path = sys.argv[1]
src = io.open(path, encoding='utf-8').read()

if 'UpdateChecker.check(' in src:
    print('[patch] 调用已存在，跳过')
    sys.exit(0)

# 锚点取 init() 内独有的那一行，再找它之后第一个 initData();（即 init() 的最后一句）
anchor = 'useCacheConfig = bundle.getBoolean("useCache", false);'
i = src.find(anchor)
if i < 0:
    raise SystemExit('[patch] 找不到锚点: ' + anchor)

j = src.find('initData();', i)
if j < 0:
    raise SystemExit('[patch] 锚点之后找不到 initData();')

j += len('initData();')
src = src[:j] + '\n        UpdateChecker.check(this);' + src[j:]
io.open(path, 'w', encoding='utf-8').write(src)
print('[patch] 已注入 UpdateChecker.check(this)')
PY

echo "[patch] 5/5 应用灰度配色（灰黑白）+ 替换窗口背景图"
# 只想保留上游配色时，构建时传 GRAYSCALE=0 跳过这一步
if [ "${GRAYSCALE:-1}" = "1" ]; then
  "$PYTHON_BIN" "$(nativepath "$PATCH_DIR/grayscale.py")"
  # 窗口背景：删掉那张 1280x720 的照片，换成灰阶 shape。
  # 必须先删同名 .jpg —— 同名资源共存会让 aapt2 报 duplicate resource 直接失败。
  if [ -f app/src/main/res/drawable/app_bg.jpg ]; then
    rm -f app/src/main/res/drawable/app_bg.jpg
    echo "[patch] 已移除原背景图 app_bg.jpg"
  fi
  cp -f "$PATCH_DIR/app_bg.xml" app/src/main/res/drawable/app_bg.xml
  ls -l app/src/main/res/drawable/app_bg.*
else
  echo "[patch] GRAYSCALE=0，跳过灰度配色与背景替换"
fi

echo "[patch] 校验结果"
grep -n 'UpdateChecker' "$HOME_ACT"
if ! grep -q 'UpdateChecker.check(this);' "$HOME_ACT"; then
  echo "[patch] 注入失败：HomeActivity 里没有找到调用" >&2
  exit 1
fi
# 灰度化的效果自检：res 里不该再有彩色残留。
# 有残留只告警不失败 —— 配色是外观问题，上游新增一种颜色写法不该把出包卡死。
# ⚠️ 这里**只查色相、不查位数**：位数由 grayscale.py 在"生成的那一刻"断言
#    （convert_text 里逐个校验自己产出的值）。千万别改成事后扫全文件的 hex ——
#    res 里有大量 XML 数字字符实体（android:text="&#x51C6;&#x5907;…"），会被误判成色值。
"$PYTHON_BIN" - <<'PY'
import io, os, re
HEX = re.compile(r'#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})\b')
bad = []
for root, _d, files in os.walk('app/src/main/res'):
    for fn in files:
        if not fn.endswith(('.xml', '.css')):
            continue
        p = os.path.join(root, fn)
        with io.open(p, encoding='utf-8', errors='ignore') as fh:
            text = fh.read()
        for m in HEX.finditer(text):
            s = m.group(1).lower()
            # Android 是 #AARRGGBB，RGB 在后 6 位（别按 CSS 的 #RRGGBBAA 解析）
            if len(s) == 8:
                s = s[2:]
            r, g, b = int(s[0:2], 16), int(s[2:4], 16), int(s[4:6], 16)
            if max(r, g, b) - min(r, g, b) > 6:
                bad.append('%s 里还有彩色 #%s' % (p, m.group(1)))
if bad:
    print('[patch] ⚠️ 灰度自检：仍有彩色残留 %d 处（不阻断构建）' % len(bad))
    for b in bad[:8]:
        print('        ' + b)
else:
    print('[patch] 灰度自检通过：res 里已无非灰色值')
PY
echo "[patch] 完成"
