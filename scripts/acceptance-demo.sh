#!/usr/bin/env bash
# 演示环境验收脚本（T-062 / F-17）：在 `docker compose up --build` 启动的环境上，经前端代理走完整演示闭环，
# 并验证 `docker compose down` 再 `up`（保留卷）后数据仍在。
#
#   docker compose up -d --build --wait
#   scripts/acceptance-demo.sh run      state.json   # 阶段 1：演示闭环，把要复查的数据写入 state.json
#   docker compose down && docker compose up -d --wait
#   scripts/acceptance-demo.sh persisted state.json  # 阶段 2：复查任务、素材、结果图、账户状态
#
# 阶段 1 覆盖：成功、失败、多任务（两个模板同时处理）、刷新恢复（新会话重新拉取，状态与单任务查询一致）、
# 我的记录（不带 templateId 的列表包含多个模板的任务）、用户隔离、约 15 MB 请求体经代理提交、修改密码、删除账户、
# 同一邮箱 + 新用户名再注册。
# 修改密码 / 删除账户使用本次新注册的临时账户，不改动 demo1 / demo2，演示账户可以反复使用。
# 浏览器界面需另行手动验收（见 README）。BASE_URL 默认 http://localhost:3000；依赖 bash、curl、jq、python3。
set -euo pipefail

MODE="${1:-}"
STATE="${2:-}"
[[ ( "$MODE" == run || "$MODE" == persisted ) && -n "$STATE" ]] \
  || { echo "用法：$0 run|persisted <state.json>" >&2; exit 2; }

BASE_URL="${BASE_URL:-http://localhost:3000}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLES="$ROOT/samples"
WORK="$(mktemp -d)"
ORIGINAL_SIM=""

fail() { echo "FAIL: $*" >&2; exit 1; }
ok() { echo "ok   $*"; }

# 请求并断言状态码；响应体写入 $WORK/body
req() {
  local expected="$1"; shift
  local code
  code="$(curl -sS -o "$WORK/body" -w '%{http_code}' "$@")" || fail "curl $* 无法连接"
  [[ "$code" == "$expected" ]] || fail "$* → HTTP $code（期望 $expected）：$(head -c 500 "$WORK/body")"
}

cleanup() {
  if [[ -n "$ORIGINAL_SIM" ]]; then
    curl -sS -o /dev/null -X PUT -H 'Content-Type: application/json' \
      --data "$ORIGINAL_SIM" "$BASE_URL/api/dev/simulation" || true
  fi
  rm -rf "$WORK"
}
trap cleanup EXIT

set_sim() {
  req 200 -X PUT -H 'Content-Type: application/json' --data "$1" "$BASE_URL/api/dev/simulation"
}

login() { # jar username password
  req 200 -c "$1" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg u "$2" --arg p "$3" '{username:$u,password:$p}')" "$BASE_URL/api/auth/login"
}

submit() { # jar templateId file...（相对 samples/ 或绝对路径；JPEG / PNG 由扩展名决定）；输出 taskId
  local jar="$1" tpl="$2"; shift 2
  local args=() f type
  for f in "$@"; do
    case "$f" in *.png) type=image/png ;; *) type=image/jpeg ;; esac
    [[ "$f" == /* ]] || f="$SAMPLES/$f"
    args+=(-F "files=@$f;type=$type")
  done
  req 202 -b "$jar" -X POST "$BASE_URL/api/tasks" -F "templateId=$tpl" "${args[@]}"
  jq -er '.taskId' "$WORK/body"
}

get_task() { req 200 -b "$1" "$BASE_URL/api/tasks/$2"; cat "$WORK/body"; }

wait_terminal() { # jar taskId；最多约 30 s
  local status task
  for _ in $(seq 1 60); do
    task="$(get_task "$1" "$2")"
    status="$(jq -r '.status' <<<"$task")"
    if [[ "$status" == SUCCEEDED || "$status" == FAILED ]]; then echo "$task"; return 0; fi
    sleep 0.5
  done
  fail "任务 $2 30 s 内未到达终态（最后状态 $status）"
}

# 生成约 4.9 MB 的合法 PNG（无压缩随机像素，低于 5 MB 单张上限）
big_png() { # path seed
  python3 - "$1" "$2" <<'PY'
import os, random, struct, sys, zlib
path, seed = sys.argv[1], int(sys.argv[2])
w, h = 1200, 1350
random.seed(seed)
row = lambda: b"\x00" + random.randbytes(w * 3)
raw = b"".join(row() for _ in range(h))
chunk = lambda t, d: struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d))
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) \
    + chunk(b"IDAT", zlib.compress(raw, 0)) + chunk(b"IEND", b"")
assert len(png) < 5 * 1024 * 1024, len(png)
open(path, "wb").write(png)
PY
}

assert_files() { # jar taskJson：素材与结果图本人 200、匿名 401
  local url
  for url in $(jq -r '[.inputAssets[].url, (.resultUrl // empty)] | .[]' <<<"$2"); do
    req 200 -b "$1" "$BASE_URL$url"; [[ -s "$WORK/body" ]] || fail "$url 内容为空"
    req 401 "$BASE_URL$url"
  done
}

phase_run() {
  local demo="$WORK/demo1" demo_reload="$WORK/demo1-reload" tmp="$WORK/tmp" task tasks

  req 200 "$BASE_URL/actuator/health"
  jq -e '.status == "UP"' "$WORK/body" >/dev/null || fail "health 不是 UP"
  req 200 "$BASE_URL/"
  req 200 "$BASE_URL/api/dev/simulation"
  ORIGINAL_SIM="$(cat "$WORK/body")"
  ok "health UP、首页 200、demo profile 模拟接口可用"

  login "$demo" demo1 xiaoying123
  ok "demo1 登录"

  # 1. 成功：demo-postcard（2 张 JPEG）
  set_sim '{"assetCheckMs":300,"generatingMs":300,"resultOrganizingMs":300,"failStage":null,"failReason":null}'
  local ok_id; ok_id="$(submit "$demo" demo-postcard sample-1.jpg sample-2.jpg)"
  task="$(wait_terminal "$demo" "$ok_id")"
  jq -e '.status == "SUCCEEDED" and (.resultUrl | type == "string") and (.inputAssets | length == 2)' <<<"$task" >/dev/null \
    || fail "成功任务异常：$task"
  assert_files "$demo" "$task"
  ok "成功：任务 $ok_id SUCCEEDED，素材与结果图本人 200 / 匿名 401"

  # 2. 失败：指定 RESULT_ORGANIZING 阶段失败
  set_sim '{"assetCheckMs":300,"generatingMs":300,"resultOrganizingMs":300,"failStage":"RESULT_ORGANIZING","failReason":null}'
  local fail_id; fail_id="$(submit "$demo" creative-poster sample-3.png)"
  task="$(wait_terminal "$demo" "$fail_id")"
  jq -e '.status == "FAILED" and .failedStage == "RESULT_ORGANIZING" and .resultUrl == null and (.failureReason | length > 0)' <<<"$task" >/dev/null \
    || fail "失败任务异常：$task"
  ok "失败：任务 $fail_id FAILED @ RESULT_ORGANIZING（$(jq -r .failureReason <<<"$task")）"

  # 3. 多任务 + 刷新恢复：两个模板各提交一个慢任务，处理中用新会话（相当于刷新页面）重新拉取
  set_sim '{"assetCheckMs":1500,"generatingMs":1500,"resultOrganizingMs":1500,"failStage":null,"failReason":null}'
  local a_id b_id
  a_id="$(submit "$demo" demo-postcard sample-1.jpg sample-2.jpg)"
  b_id="$(submit "$demo" festival-collage sample-1.jpg sample-2.png sample-3.jpg)"
  sleep 1
  login "$demo_reload" demo1 xiaoying123
  req 200 -b "$demo_reload" "$BASE_URL/api/tasks"
  tasks="$(cat "$WORK/body")"
  local id
  for id in "$a_id" "$b_id"; do
    jq -e --arg id "$id" '[.items[] | select(.taskId == $id and (.status == "PENDING" or .status == "PROCESSING"))] | length == 1' \
      <<<"$tasks" >/dev/null || fail "刷新后列表中任务 $id 不在进行中：$tasks"
  done
  ok "多任务：$a_id（demo-postcard）、$b_id（festival-collage）同时进行，新会话可见且未结束"
  for id in "$a_id" "$b_id"; do
    task="$(wait_terminal "$demo_reload" "$id")"
    jq -e '.status == "SUCCEEDED"' <<<"$task" >/dev/null || fail "任务 $id 未成功：$task"
  done
  ok "刷新恢复：新会话继续轮询，两个任务均 SUCCEEDED"

  # 4. 我的记录：不带 templateId 的列表包含所有模板的任务，状态与单任务查询一致；按模板过滤只含该模板
  req 200 -b "$demo" "$BASE_URL/api/tasks"
  tasks="$(cat "$WORK/body")"
  for id in "$ok_id" "$fail_id" "$a_id" "$b_id"; do
    task="$(get_task "$demo" "$id")"
    jq -e --arg id "$id" --arg st "$(jq -r .status <<<"$task")" \
      '[.items[] | select(.taskId == $id and .status == $st and (.templateTitle | length > 0))] | length == 1' \
      <<<"$tasks" >/dev/null || fail "我的记录中任务 $id 缺失或状态不一致"
  done
  req 200 -b "$demo" "$BASE_URL/api/tasks?templateId=festival-collage"
  jq -e '(.items | length > 0) and all(.items[]; .templateId == "festival-collage")' "$WORK/body" >/dev/null \
    || fail "按模板过滤结果异常"
  ok "我的记录：4 个任务（3 个模板）全部可见且状态一致；按模板过滤正确"

  # 大请求体：3 张约 4.9 MB 的 PNG（单张低于 5 MB 上限，合计约 15 MB）经 :3000 代理提交，确认 rewrites 不截断
  set_sim '{"assetCheckMs":200,"generatingMs":200,"resultOrganizingMs":200,"failStage":null,"failReason":null}'
  local i big_id
  for i in 1 2 3; do big_png "$WORK/big-$i.png" "$i"; done
  big_id="$(submit "$demo" festival-collage "$WORK/big-1.png" "$WORK/big-2.png" "$WORK/big-3.png")"
  task="$(wait_terminal "$demo" "$big_id")"
  jq -e '.status == "SUCCEEDED"' <<<"$task" >/dev/null || fail "大请求体任务未成功：$task"
  req 200 -b "$demo" "$BASE_URL$(jq -r '.inputAssets[2].url' <<<"$task")"
  [[ "$(wc -c <"$WORK/body" | tr -d ' ')" == "$(wc -c <"$WORK/big-3.png" | tr -d ' ')" ]] || fail "第 3 张素材大小与上传不一致"
  ok "大请求体：3 × $(( $(wc -c <"$WORK/big-1.png") / 1024 )) KB 经代理受理，任务 $big_id SUCCEEDED，素材完整"

  # 用户隔离：demo2 看不到 demo1 的任务
  local demo2="$WORK/demo2"
  login "$demo2" demo2 xiaoying123
  req 404 -b "$demo2" "$BASE_URL/api/tasks/$ok_id"
  req 200 -b "$demo2" "$BASE_URL/api/tasks"
  jq -e --arg id "$ok_id" '[.items[] | select(.taskId == $id)] | length == 0' "$WORK/body" >/dev/null \
    || fail "demo2 列表中出现 demo1 的任务"
  ok "用户隔离：demo2 取 demo1 的任务 → 404，列表不含"

  # 5. 修改密码（临时账户）
  local suffix user pw1 pw2 other="$WORK/other"
  suffix="$(date +%s)$RANDOM"; user="acc_${suffix: -12}"; pw1="initPass123"; pw2="changedPass456"
  req 201 -c "$tmp" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg u "$user" --arg e "$user@example.com" --arg p "$pw1" '{username:$u,email:$e,password:$p}')" \
    "$BASE_URL/api/auth/register"
  login "$other" "$user" "$pw1"
  req 400 -b "$tmp" -X PUT -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg p "$pw2" '{currentPassword:"wrongPass000",newPassword:$p}')" "$BASE_URL/api/account/password"
  jq -e '.code == "CURRENT_PASSWORD_INCORRECT"' "$WORK/body" >/dev/null || fail "当前密码错误未返回 CURRENT_PASSWORD_INCORRECT"
  req 400 -b "$tmp" -X PUT -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg c "$pw1" '{currentPassword:$c,newPassword:"short"}')" "$BASE_URL/api/account/password"
  jq -e '.code == "VALIDATION_FAILED"' "$WORK/body" >/dev/null || fail "弱密码未返回 VALIDATION_FAILED"
  req 401 -X PUT -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg c "$pw1" --arg p "$pw2" '{currentPassword:$c,newPassword:$p}')" "$BASE_URL/api/account/password"
  req 204 -b "$tmp" -X PUT -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg c "$pw1" --arg p "$pw2" '{currentPassword:$c,newPassword:$p}')" "$BASE_URL/api/account/password"
  req 200 -b "$tmp" "$BASE_URL/api/auth/me"
  req 401 -b "$other" "$BASE_URL/api/auth/me"
  req 401 -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg u "$user" --arg p "$pw1" '{username:$u,password:$p}')" "$BASE_URL/api/auth/login"
  login "$tmp" "$user" "$pw2"
  ok "修改密码（$user）：错误当前密码 400、弱密码 400、匿名 401、成功 204；当前会话保留、其他会话失效、旧密码不能登录、新密码可登录"

  # 6. 删除账户（同一临时账户，先留一个进行中的任务）
  set_sim '{"assetCheckMs":3000,"generatingMs":3000,"resultOrganizingMs":3000,"failStage":null,"failReason":null}'
  submit "$tmp" creative-poster sample-1.jpg >/dev/null
  req 400 -b "$tmp" -X DELETE -H 'Content-Type: application/json' --data '{"confirmText":"delete"}' "$BASE_URL/api/account"
  jq -e '.code == "CONFIRM_TEXT_MISMATCH"' "$WORK/body" >/dev/null || fail "确认文字错误未返回 CONFIRM_TEXT_MISMATCH"
  req 401 -X DELETE -H 'Content-Type: application/json' --data '{"confirmText":"DELETE"}' "$BASE_URL/api/account"
  req 204 -b "$tmp" -c "$tmp" -X DELETE -H 'Content-Type: application/json' --data '{"confirmText":"DELETE"}' "$BASE_URL/api/account"
  req 401 -b "$tmp" "$BASE_URL/api/auth/me"
  req 401 -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg u "$user" --arg p "$pw2" '{username:$u,password:$p}')" "$BASE_URL/api/auth/login"
  ok "删除账户（$user）：确认文字错误 400、匿名 401、成功 204；会话失效、不能再登录"

  # 同一邮箱 + 新用户名可以再注册，看不到旧账户的数据；旧用户名不能再用
  local reuser="re${user}"; reuser="${reuser:0:20}"
  req 409 -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg u "$user" --arg e "new-$user@example.com" '{username:$u,email:$e,password:"anotherPass789"}')" \
    "$BASE_URL/api/auth/register"
  req 201 -c "$WORK/reuser" -H 'Content-Type: application/json' \
    --data "$(jq -nc --arg u "$reuser" --arg e "$user@example.com" '{username:$u,email:$e,password:"anotherPass789"}')" \
    "$BASE_URL/api/auth/register"
  req 200 -b "$WORK/reuser" "$BASE_URL/api/tasks"
  jq -e '.items | length == 0' "$WORK/body" >/dev/null || fail "同邮箱新账户看到了旧数据"
  ok "同一邮箱 + 新用户名 $reuser 注册成功、任务列表为空；旧用户名注册 409"

  jq -n --arg user "$user" --arg pw "$pw2" '{deletedUser:{username:$user,password:$pw}}' >"$STATE"
  # 记录各任务当前视图，供阶段 2 比对
  local snapshot="[]"
  for id in "$ok_id" "$fail_id" "$a_id" "$b_id" "$big_id"; do
    snapshot="$(jq -c --argjson t "$(get_task "$demo" "$id")" '. + [$t]' <<<"$snapshot")"
  done
  jq --argjson s "$snapshot" '.tasks = $s' "$STATE" >"$WORK/state" && mv "$WORK/state" "$STATE"
  echo "阶段 1 通过；状态已写入 $STATE。执行 docker compose down && docker compose up -d --wait 后运行：$0 persisted $STATE"
}

phase_persisted() {
  [[ -s "$STATE" ]] || fail "$STATE 不存在，请先运行 run 阶段"
  local demo="$WORK/demo1" t id task
  req 200 "$BASE_URL/actuator/health"
  login "$demo" demo1 xiaoying123
  req 200 -b "$demo" "$BASE_URL/api/tasks"
  local list; list="$(cat "$WORK/body")"
  for t in $(jq -c '.tasks[]' "$STATE"); do
    id="$(jq -r .taskId <<<"$t")"
    task="$(get_task "$demo" "$id")"
    jq -e --argjson before "$t" '. == $before' <<<"$task" >/dev/null \
      || fail "任务 $id 与 down 前不一致：前 $t / 后 $task"
    jq -e --arg id "$id" '[.items[] | select(.taskId == $id)] | length == 1' <<<"$list" >/dev/null \
      || fail "我的记录中缺少任务 $id"
    assert_files "$demo" "$task"
  done
  ok "down → up 后 $(jq '.tasks | length' "$STATE") 个任务与之前完全一致，素材与结果图仍可取"
  req 401 -H 'Content-Type: application/json' \
    --data "$(jq -c '.deletedUser' "$STATE")" "$BASE_URL/api/auth/login"
  ok "已删除账户 $(jq -r .deletedUser.username "$STATE") 重启后仍不能登录"
  echo "阶段 2 通过：数据在 down / up 后保留"
}

"phase_$MODE"
