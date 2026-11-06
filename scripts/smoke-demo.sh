#!/usr/bin/env bash
# 演示环境冒烟测试（T-060）：对已启动的 `docker compose up` 环境，经前端 :3000 代理走一遍演示闭环。
#
#   docker compose up -d --build --wait
#   scripts/smoke-demo.sh                 # BASE_URL 默认 http://localhost:3000
#
# 覆盖：health UP、首页可访问、demo profile 的 /api/dev/simulation、匿名 401、demo1 登录、模板列表、
# 提交任务 → SUCCEEDED → 本人可取结果图 / 匿名 401、指定 GENERATING 失败 → FAILED。
# 注意：会以 demo1 身份创建 2 个任务；结束时（含失败退出）把模拟配置恢复为运行前的值。
# 依赖：bash、curl、jq。
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:3000}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SAMPLES="$ROOT/samples"
WORK="$(mktemp -d)"
JAR="$WORK/cookies"
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

restore_sim() {
  if [[ -n "$ORIGINAL_SIM" ]]; then
    curl -sS -o /dev/null -X PUT -H 'Content-Type: application/json' \
      --data "$ORIGINAL_SIM" "$BASE_URL/api/dev/simulation" || true
  fi
  rm -rf "$WORK"
}
trap restore_sim EXIT

set_sim() {
  req 200 -X PUT -H 'Content-Type: application/json' --data "$1" "$BASE_URL/api/dev/simulation"
}

# 提交 demo-postcard（2 张 JPEG），输出 taskId
submit_postcard() {
  req 202 -b "$JAR" -X POST "$BASE_URL/api/tasks" \
    -F templateId=demo-postcard \
    -F "files=@$SAMPLES/sample-1.jpg;type=image/jpeg" \
    -F "files=@$SAMPLES/sample-2.jpg;type=image/jpeg"
  jq -er '.taskId' "$WORK/body"
}

# 轮询直到终态（最多约 30 s），输出最终 Task JSON
wait_terminal() {
  local id="$1" status
  for _ in $(seq 1 60); do
    req 200 -b "$JAR" "$BASE_URL/api/tasks/$id"
    status="$(jq -r '.status' "$WORK/body")"
    if [[ "$status" == "SUCCEEDED" || "$status" == "FAILED" ]]; then
      cat "$WORK/body"
      return 0
    fi
    sleep 0.5
  done
  fail "任务 $id 30 s 内未到达终态（最后状态 $status）"
}

# 1. 服务可用
req 200 "$BASE_URL/actuator/health"
jq -e '.status == "UP"' "$WORK/body" >/dev/null || fail "health 不是 UP"
req 200 "$BASE_URL/"
ok "health UP，首页 200"

# 2. demo profile 打开了模拟配置接口；缩短耗时以加快冒烟
req 200 "$BASE_URL/api/dev/simulation"
ORIGINAL_SIM="$(cat "$WORK/body")"
set_sim '{"assetCheckMs":200,"generatingMs":200,"resultOrganizingMs":200,"failStage":null,"failReason":null}'
ok "/api/dev/simulation 可读写（demo profile）"

# 3. 未登录访问用户数据 → 401
req 401 "$BASE_URL/api/tasks"
ok "匿名 GET /api/tasks → 401"

# 4. 演示账户登录 + 模板列表
req 200 -c "$JAR" -H 'Content-Type: application/json' \
  --data '{"username":"demo1","password":"xiaoying123"}' "$BASE_URL/api/auth/login"
req 200 -b "$JAR" "$BASE_URL/api/auth/me"
jq -e '.username == "demo1"' "$WORK/body" >/dev/null || fail "me 不是 demo1"
req 200 "$BASE_URL/api/templates"
jq -e '[.. | objects | select(.templateId? == "demo-postcard")] | length == 1' "$WORK/body" >/dev/null \
  || fail "模板列表中没有 demo-postcard"
ok "demo1 登录，模板列表含 demo-postcard"

# 5. 成功路径：受理 → SUCCEEDED → 结果图本人可取、匿名 401
id="$(submit_postcard)"
task="$(wait_terminal "$id")"
jq -e '.status == "SUCCEEDED" and .stage == "RESULT_ORGANIZING" and (.resultUrl | type == "string")' <<<"$task" >/dev/null \
  || fail "任务 $id 未成功：$task"
result_url="$(jq -r '.resultUrl' <<<"$task")"
req 200 -b "$JAR" "$BASE_URL$result_url"
[[ -s "$WORK/body" ]] || fail "结果图为空"
req 401 "$BASE_URL$result_url"
ok "任务 $id SUCCEEDED，结果图本人 200 / 匿名 401"

# 6. 失败路径：指定 GENERATING 失败
set_sim '{"assetCheckMs":200,"generatingMs":200,"resultOrganizingMs":200,"failStage":"GENERATING","failReason":null}'
id="$(submit_postcard)"
task="$(wait_terminal "$id")"
jq -e '.status == "FAILED" and .failedStage == "GENERATING" and .resultUrl == null and (.failureReason | length > 0)' <<<"$task" >/dev/null \
  || fail "任务 $id 未按指定阶段失败：$task"
ok "任务 $id FAILED @ GENERATING"

# 7. 列表中能看到刚才的任务
req 200 -b "$JAR" "$BASE_URL/api/tasks?templateId=demo-postcard"
jq -e --arg id "$id" '[.. | objects | select(.taskId? == $id)] | length == 1' "$WORK/body" >/dev/null \
  || fail "任务列表中没有任务 $id"
ok "任务列表包含新任务"

echo "演示环境冒烟测试通过"
