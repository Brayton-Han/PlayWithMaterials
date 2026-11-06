"use client";

/**
 * 任务跟踪调度器（T-051；architecture §5.4；F-10 规则 2 ~ 6，F-09 异常情况）。
 *
 * - 跟踪对象：scope 内未结束（PENDING / PROCESSING）且未被判定为"不存在或无权访问"的任务，
 *   从 taskStore 实时派生；任务进入终态、离开 scope 或 404 后移出；没有可跟踪任务时不设定时器（零请求）。
 * - 节奏：单个 setTimeout 链，定时器总是指向"最早到期"的任务。每个任务的下一次查询在**上一次请求结束后**
 *   才开始计时（成功 {@link TRACK_INTERVAL_MS}，失败按 {@link backoffDelay} 指数退避），
 *   所以同一任务同一时刻最多一个在途请求，一个慢请求也不会拖住其他任务。
 * - 结果：200 → `upsertFromServer` + `markQueryOk`（单调合并由 store 保证）；
 *   网络错误 / 4xx / 5xx → `markQueryError`，只改 query 字段，不改任务状态（R-07.2）；
 *   404 → `markQueryError(notFound)` 并停止自动跟踪；401 → 全局处理器已切换会话，响应按 epoch 丢弃。
 *   手动 `refetch` / `refetchAll` 不受退避限制，404 的任务也可以手动重查。
 * - 停止：卸载（离开页面）时 abort 全部在途请求、清除定时器、退订 store。
 *   会话切换（登录 / 退出 / 401）时 AuthProvider 已 abortAllRequests + 清空 store + epoch 递增；
 *   这里在写入前再校验请求发起时的 epoch，迟到的响应直接丢弃。
 *   一个 epoch 只对应一个用户的一次会话（features/auth/session.ts），所以 epoch 校验同时覆盖了 userId 校验。
 */
import { useCallback, useEffect, useRef } from "react";

import { getSessionEpoch, isCurrentEpoch } from "@/features/auth/session";
import { isAbortError, isApiError, NETWORK_ERROR_MESSAGE } from "@/lib/api/client";
import { getTask } from "@/lib/api/endpoints/tasks";

import { isActive, selectScope, taskStore, type TaskEntry, type TaskQueryError, type TaskScope } from "./taskStore";

/** 正常查询间隔（F-10 规则 3：1.5 ~ 2 s；architecture §5.4：2 s）。 */
export const TRACK_INTERVAL_MS = 2000;
/** 连续查询失败时的最长退避间隔。 */
export const MAX_BACKOFF_MS = 30_000;

/** 连续失败 n 次后的下次查询延迟：0 → 2 s，1 → 4 s，2 → 8 s，3 → 16 s，之后 30 s。 */
export function backoffDelay(failures: number): number {
  if (failures <= 0) return TRACK_INTERVAL_MS;
  return Math.min(TRACK_INTERVAL_MS * 2 ** failures, MAX_BACKOFF_MS);
}

/** 把查询失败转换为 store 的 {@link TaskQueryError}。 */
export function toQueryError(error: unknown): TaskQueryError {
  if (!isApiError(error)) return { kind: "network", message: NETWORK_ERROR_MESSAGE };
  if (error.kind === "network") return { kind: "network", message: error.message };
  if (error.status === 404) return { kind: "notFound", status: 404, message: error.message };
  return { kind: "http", status: error.status, message: error.message };
}

/** 需要自动跟踪：未结束，且最近一次查询没有返回 404。 */
export function isTrackable(entry: TaskEntry): boolean {
  return isActive(entry.task) && entry.query.error?.kind !== "notFound";
}

export interface TaskTracker {
  /** 立即查询一个任务（"重新查询"按钮）；该任务已有在途请求时忽略。 */
  refetch: (taskId: string) => void;
  /** 立即查询 scope 内全部未结束或查询异常的任务（刷新按钮）。 */
  refetchAll: () => void;
  /** 停止跟踪：abort 全部在途请求、清除定时器、退订 store。之后的调用都是空操作。 */
  stop: () => void;
}

interface Plan {
  dueAt: number;
  failures: number;
}

/** 跟踪调度器本体，不依赖 React（便于单测，也方便 P1 换成批量接口）。 */
export function startTaskTracker(scope: TaskScope): TaskTracker {
  let epoch = getSessionEpoch();
  let stopped = false;
  let timer: ReturnType<typeof setTimeout> | null = null;
  /** 自动跟踪中的任务：下次查询时间与连续失败次数。 */
  const plans = new Map<string, Plan>();
  /** 在途请求（自动与手动共用），每个任务最多一个。 */
  const inFlight = new Map<string, AbortController>();

  const clearTimer = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };

  const abortAll = () => {
    const controllers = [...inFlight.values()];
    inFlight.clear();
    for (const controller of controllers) controller.abort();
  };

  /** 定时器指向最早到期、且没有在途请求的任务；没有这样的任务就不设定时器。 */
  function arm() {
    clearTimer();
    let next = Infinity;
    for (const [taskId, plan] of plans) {
      if (!inFlight.has(taskId) && plan.dueAt < next) next = plan.dueAt;
    }
    if (next !== Infinity) timer = setTimeout(tick, Math.max(0, next - Date.now()));
  }

  /** 按 store 当前内容同步跟踪集合，然后重新设定时器。store 每次变化都会调用。 */
  function reconcile() {
    if (stopped) return;
    if (!isCurrentEpoch(epoch)) {
      // 会话已切换：前一会话的请求与计划全部作废（store 已由 AuthProvider 清空）
      abortAll();
      plans.clear();
      epoch = getSessionEpoch();
    }
    const trackable = new Set<string>();
    for (const entry of selectScope(taskStore.getState().entries, scope)) {
      if (isTrackable(entry)) trackable.add(entry.task.taskId);
    }
    for (const taskId of [...plans.keys()]) {
      if (!trackable.has(taskId)) plans.delete(taskId);
    }
    const now = Date.now();
    for (const taskId of trackable) {
      // 新进入跟踪的任务（刚提交 / 刚从历史加载）已是最新状态，间隔一轮后再查
      if (!plans.has(taskId)) plans.set(taskId, { dueAt: now + TRACK_INTERVAL_MS, failures: 0 });
    }
    arm();
  }

  function tick() {
    timer = null;
    if (stopped) return;
    const now = Date.now();
    const due = [...plans].filter(([taskId, plan]) => plan.dueAt <= now && !inFlight.has(taskId));
    for (const [taskId] of due) request(taskId);
    reconcile();
  }

  function reschedule(taskId: string, failures: number) {
    const plan = plans.get(taskId);
    if (plan) {
      plan.failures = failures;
      plan.dueAt = Date.now() + backoffDelay(failures);
    }
  }

  function request(taskId: string) {
    if (stopped || inFlight.has(taskId) || !isCurrentEpoch(epoch)) return;
    if (!taskStore.getState().entries[taskId]) return;
    const requestEpoch = epoch;
    const controller = new AbortController();
    inFlight.set(taskId, controller);
    const live = () => !stopped && !controller.signal.aborted && isCurrentEpoch(requestEpoch);
    const settle = () => {
      if (inFlight.get(taskId) === controller) inFlight.delete(taskId);
    };

    taskStore.getState().markQueryLoading(taskId, requestEpoch);
    getTask(taskId, controller.signal).then(
      (task) => {
        settle();
        if (!live()) return;
        reschedule(taskId, 0);
        const store = taskStore.getState();
        store.upsertFromServer(task, requestEpoch);
        store.markQueryOk(taskId, requestEpoch);
        reconcile();
      },
      (error: unknown) => {
        settle();
        if (!live()) return;
        if (isAbortError(error)) {
          // 会话级取消但 epoch 尚未变化（退出请求进行中）：不算查询失败，按正常间隔再查
          reschedule(taskId, plans.get(taskId)?.failures ?? 0);
          reconcile();
          return;
        }
        const queryError = toQueryError(error);
        reschedule(taskId, (plans.get(taskId)?.failures ?? 0) + 1);
        // 404 后 isTrackable 为 false，reconcile 会把它移出跟踪
        taskStore.getState().markQueryError(taskId, queryError, requestEpoch);
        reconcile();
      },
    );
  }

  const unsubscribe = taskStore.subscribe(reconcile);
  reconcile();

  return {
    refetch(taskId) {
      if (stopped) return;
      reconcile();
      request(taskId);
      arm();
    },
    refetchAll() {
      if (stopped) return;
      reconcile();
      for (const entry of selectScope(taskStore.getState().entries, scope)) {
        if (isTrackable(entry) || entry.query.status === "error") request(entry.task.taskId);
      }
      arm();
    },
    stop() {
      if (stopped) return;
      stopped = true;
      unsubscribe();
      clearTimer();
      abortAll();
      plans.clear();
    },
  };
}

export interface UseTaskTrackerResult {
  refetch: (taskId: string) => void;
  refetchAll: () => void;
}

/**
 * 在当前页面自动跟踪 scope 内的未结束任务（详情页：当前模板；"我的记录"页：全部）。
 * 离开页面即停止（OQ-09 余项临时默认：离开这两个页面不跟踪）。返回的函数引用稳定。
 */
export function useTaskTracker(scope: TaskScope): UseTaskTrackerResult {
  const templateId = scope.kind === "template" ? scope.templateId : null;
  const trackerRef = useRef<TaskTracker | null>(null);

  useEffect(() => {
    const tracker = startTaskTracker(templateId === null ? { kind: "all" } : { kind: "template", templateId });
    trackerRef.current = tracker;
    return () => {
      tracker.stop();
      if (trackerRef.current === tracker) trackerRef.current = null;
    };
  }, [templateId]);

  const refetch = useCallback((taskId: string) => trackerRef.current?.refetch(taskId), []);
  const refetchAll = useCallback(() => trackerRef.current?.refetchAll(), []);
  return { refetch, refetchAll };
}
