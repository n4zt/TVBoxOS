#!/usr/bin/env bash
#
# 在上游源码（CI 运行时 clone 出来的 TVBoxOSC/）里注入应用内更新检查。
#
# 用法（工作目录必须是源码仓库根）：
#   apply-patch.sh <update_json_raw_url> <update_json_cdn_url>
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

if [ ! -f "$HOME_ACT" ]; then
  echo "[patch] 找不到 $HOME_ACT，工作目录不对？当前：$(pwd)" >&2
  exit 1
fi

echo "[patch] 1/4 注入 UpdateChecker.java"
mkdir -p "$UTIL_DIR"
cp -f "$PATCH_DIR/UpdateChecker.java" "$UTIL_DIR/UpdateChecker.java"
# 占位符替换（用 | 作分隔符，避免 URL 里的 / 干扰）
if [ -n "$UPDATE_JSON_RAW" ]; then
  sed -i "s|__UPDATE_JSON_RAW__|${UPDATE_JSON_RAW}|g" "$UTIL_DIR/UpdateChecker.java"
fi
if [ -n "$UPDATE_JSON_CDN" ]; then
  sed -i "s|__UPDATE_JSON_CDN__|${UPDATE_JSON_CDN}|g" "$UTIL_DIR/UpdateChecker.java"
fi
# 清掉残留占位符，避免把非法字符串编进 APK
sed -i "s|__UPDATE_JSON_RAW__||g; s|__UPDATE_JSON_CDN__||g" "$UTIL_DIR/UpdateChecker.java"
grep -n 'UPDATE_JSON_RAW\|UPDATE_JSON_CDN' "$UTIL_DIR/UpdateChecker.java" || true

echo "[patch] 2/4 为 6 个 flavor 生成版本标识资源"
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

echo "[patch] 3/4 注入 import"
if ! grep -q 'com\.github\.tvbox\.osc\.util\.UpdateChecker' "$HOME_ACT"; then
  sed -i 's#^import me\.jessyan\.autosize\.utils\.AutoSizeUtils;$#import me.jessyan.autosize.utils.AutoSizeUtils;\nimport com.github.tvbox.osc.util.UpdateChecker;#' "$HOME_ACT"
  echo "[patch] 已插入 import"
else
  echo "[patch] import 已存在，跳过"
fi

echo "[patch] 4/4 在 init() 末尾追加 UpdateChecker.check(this)"
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

echo "[patch] 校验结果"
grep -n 'UpdateChecker' "$HOME_ACT"
if ! grep -q 'UpdateChecker.check(this);' "$HOME_ACT"; then
  echo "[patch] 注入失败：HomeActivity 里没有找到调用" >&2
  exit 1
fi
echo "[patch] 完成"
