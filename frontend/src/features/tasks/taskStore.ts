/**
 * 任务状态 store（T-050；architecture §5.4、§7.2；F-09 规则 3 / 5，F-10 规则 1 / 5，F-11 规则 4）。
 *
 * - 按 taskId 归一化：`entries[taskId] = { task, query }`。
 *   `task` 是服务端事实（只由服务端响应写入）；`query` 是前端查询请求的状态，二者互不覆盖：
 *   查询失败只改 `query`，绝不把任务改成 FAILED（R-07.2）。
 * - 单调合并（{@link mergeTask}）：终态不会被非终态覆盖；`updatedAt` 更旧的响应被丢弃；
 *   `updatedAt` 相同时按进度（PENDING < PROCESSING·阶段 < 终态）拒绝回退。
 * - 绑定会话：每次写入都带上请求发起时的 session epoch（features/auth/session.ts）。
 *   epoch 在每次登录 / 注册 / 退出 / 会话失效时递增，因此一个 epoch 只对应一个用户的一次会话；
 *   epoch 不是当前值的写入一律丢弃（F-10 规则 5）。切换会话时 AuthProvider 调用 {@link onUserScopeReset}
 *   注册的 `reset()` 清空 entries。
 * - 纯函数（`apply*` / `select*`）单独导出，便于单测；Zustand store 只是对它们的薄封装，
 *   React 之外（跟踪调度器 T-051）也能直接读写。
 */
import { useStore } from "zustand";
import { createStore } from "zustand/vanilla";
import type { Task, TaskStatus } from "@/lib/api/endpoints/tasks";
import { isCurrentEpoch, onUserScopeReset } from "@/features/auth/session";

export type TaskQueryStatus = "idle" | "loading" | "error";

/**
 * 查询错误（只描述"这次查询为什么没拿到状态"，与任务本身是否失败无关）。
 * - network：没拿到 HTTP 响应；http：4xx / 5xx（status 为状态码）；
 * - notFound：404，任务不存在或无权访问（F-09 异常情况），调度器据此停止跟踪。
 */
export interface TaskQueryError {
  kind: "network" | "http" | "notFound";
  status?: number;
  message: string;
}

export interface TaskQueryState {
  status: TaskQueryStatus;
  error?: TaskQueryError;
  /** 最近一次查询成功的时间（epoch ms） */
  lastSuccessAt?: number;
}

export interface TaskEntry {
  task: Task;
  query: TaskQueryState;
}

export type TaskEntries = Readonly<Record<string, TaskEntry>>;

/** 跟踪 / 派生范围：详情页 = 当前模板，"我的记录"页 = 全部。 */
export type TaskScope = { kind: "template"; templateId: string } | { kind: "all" };

const TERMINAL: ReadonlySet<TaskStatus> = new Set(["SUCCEEDED", "FAILED"]);
const STAGE_RANK = { ASSET_CHECK: 0, GENERATING: 1, RESULT_ORGANIZING: 2 } as const;
const EMPTY: TaskEntries = Object.freeze({});
const IDLE: TaskQueryState = Object.freeze({ status: "idle" });

export function isTerminal(status: TaskStatus): boolean {
  return TERMINAL.has(status);
}

export function isActive(task: Task): boolean {
  return !isTerminal(task.status);
}

/** 进度序：PENDING(0) < PROCESSING·ASSET_CHECK(1) < ·GENERATING(2) < ·RESULT_ORGANIZING(3) < 终态(4)。 */
function progressRank(task: Task): number {
  if (isTerminal(task.status)) return 4;
  if (task.status === "PENDING") return 0;
  return 1 + (task.stage ? STAGE_RANK[task.stage] : 0);
}

/** ISO 时间 → epoch ms；无法解析时为 NaN。 */
function parseTime(value: string): number {
  return Date.parse(value);
}

/**
 * 决定是否用 incoming 替换 current（同一 taskId）。返回 true 表示接受。
 * 1. 本地已是终态、incoming 不是终态 → 拒绝（F-09 规则 5）；
 * 2. 两者 updatedAt 都可解析：incoming 更旧 → 拒绝，更新 → 接受；
 * 3. updatedAt 相同或无法比较：进度不回退时接受（相同内容的重复响应也接受，结果不变）。
 */
export function shouldAccept(current: Task, incoming: Task): boolean {
  if (isTerminal(current.status) && !isTerminal(incoming.status)) return false;
  const a = parseTime(current.updatedAt);
  const b = parseTime(incoming.updatedAt);
  if (!Number.isNaN(a) && !Number.isNaN(b) && a !== b) return b > a;
  return progressRank(incoming) >= progressRank(current);
}

/** 合并若干服务端任务；没有任何变化时返回原对象（避免无意义的重渲染）。 */
export function applyUpsert(entries: TaskEntries, tasks: readonly Task[]): TaskEntries {
  let next: Record<string, TaskEntry> | null = null;
  for (const task of tasks) {
    const source = next ?? entries;
    const current = source[task.taskId];
    if (current) {
      if (current.task === task || !shouldAccept(current.task, task)) continue;
      next ??= { ...entries };
      next[task.taskId] = { task, query: current.query };
    } else {
      next ??= { ...entries };
      next[task.taskId] = { task, query: IDLE };
    }
  }
  return next ?? entries;
}

/** 只改某个任务的 query 字段；任务不存在时不变（不会凭空创建条目）。 */
export function applyQuery(
  entries: TaskEntries,
  taskId: string,
  update: (query: TaskQueryState) => TaskQueryState,
): TaskEntries {
  const current = entries[taskId];
  if (!current) return entries;
  return { ...entries, [taskId]: { task: current.task, query: update(current.query) } };
}

export function queryLoading(query: TaskQueryState): TaskQueryState {
  return { status: "loading", error: query.error, lastSuccessAt: query.lastSuccessAt };
}

export function queryError(query: TaskQueryState, error: TaskQueryError): TaskQueryState {
  return { status: "error", error, lastSuccessAt: query.lastSuccessAt };
}

export function queryOk(_query: TaskQueryState, at: number): TaskQueryState {
  return { status: "idle", lastSuccessAt: at };
}

/** 创建时间倒序，同一时间按 taskId 数值倒序（与后端 `created_at DESC, id DESC` 一致，OQ-09 临时默认）。 */
function compareNewestFirst(a: TaskEntry, b: TaskEntry): number {
  const ta = parseTime(a.task.createdAt);
  const tb = parseTime(b.task.createdAt);
  if (ta !== tb && !Number.isNaN(ta) && !Number.isNaN(tb)) return tb - ta;
  // taskId 是不带前导零的十进制字符串（可能超过 Number 精度）：先比长度再比字典序即数值序
  const ia = a.task.taskId;
  const ib = b.task.taskId;
  if (ia.length !== ib.length) return ib.length - ia.length;
  return ia < ib ? 1 : ia > ib ? -1 : 0;
}

function inScope(entry: TaskEntry, scope: TaskScope): boolean {
  return scope.kind === "all" || entry.task.templateId === scope.templateId;
}

function scopeKey(scope: TaskScope): string {
  return scope.kind === "all" ? "all" : `template:${scope.templateId}`;
}

// 派生列表按 entries 引用缓存：entries 不变时返回同一个数组，满足 useSyncExternalStore 对快照稳定性的要求。
const listCache = new WeakMap<TaskEntries, Map<string, readonly TaskEntry[]>>();

function cached(entries: TaskEntries, key: string, compute: () => readonly TaskEntry[]): readonly TaskEntry[] {
  let byKey = listCache.get(entries);
  if (!byKey) {
    byKey = new Map();
    listCache.set(entries, byKey);
  }
  let list = byKey.get(key);
  if (!list) {
    list = Object.freeze(compute());
    byKey.set(key, list);
  }
  return list;
}

/** scope 内的任务，创建时间倒序。 */
export function selectScope(entries: TaskEntries, scope: TaskScope): readonly TaskEntry[] {
  return cached(entries, `scope|${scopeKey(scope)}`, () =>
    Object.values(entries)
      .filter((entry) => inScope(entry, scope))
      .sort(compareNewestFirst),
  );
}

/** 当前模板的任务（详情页 History）。 */
export function selectByTemplate(entries: TaskEntries, templateId: string): readonly TaskEntry[] {
  return selectScope(entries, { kind: "template", templateId });
}

/** 全部任务（"我的记录"页）。 */
export function selectAll(entries: TaskEntries): readonly TaskEntry[] {
  return selectScope(entries, { kind: "all" });
}

/** scope 内未结束（PENDING / PROCESSING）的任务，供跟踪调度器使用。 */
export function selectActive(entries: TaskEntries, scope: TaskScope): readonly TaskEntry[] {
  return cached(entries, `active|${scopeKey(scope)}`, () =>
    selectScope(entries, scope).filter((entry) => isActive(entry.task)),
  );
}

// ---------- Zustand store ----------

export interface TaskStoreState {
  entries: TaskEntries;
  /**
   * 写入一个服务端任务（提交 202、单个查询 200）。`epoch` 为请求发起时的 session epoch，
   * 不是当前 epoch 时丢弃。返回是否写入了新数据。
   */
  upsertFromServer: (task: Task, epoch: number) => boolean;
  /** 批量写入（历史列表）；规则同 {@link TaskStoreState.upsertFromServer}。 */
  upsertMany: (tasks: readonly Task[], epoch: number) => boolean;
  markQueryLoading: (taskId: string, epoch: number) => void;
  /** 只改 query 字段，不改 task（查询异常 ≠ 任务失败）。 */
  markQueryError: (taskId: string, error: TaskQueryError, epoch: number) => void;
  markQueryOk: (taskId: string, epoch: number, at?: number) => void;
  /** 清空全部任务（退出登录 / 切换用户）。 */
  reset: () => void;
}

export function createTaskStore() {
  return createStore<TaskStoreState>()((set, get) => {
    const write = (epoch: number, compute: (entries: TaskEntries) => TaskEntries): boolean => {
      if (!isCurrentEpoch(epoch)) return false;
      const current = get().entries;
      const next = compute(current);
      if (next === current) return false;
      set({ entries: next });
      return true;
    };
    return {
      entries: EMPTY,
      upsertFromServer: (task, epoch) => write(epoch, (entries) => applyUpsert(entries, [task])),
      upsertMany: (tasks, epoch) => write(epoch, (entries) => applyUpsert(entries, tasks)),
      markQueryLoading: (taskId, epoch) => {
        write(epoch, (entries) => applyQuery(entries, taskId, queryLoading));
      },
      markQueryError: (taskId, error, epoch) => {
        write(epoch, (entries) => applyQuery(entries, taskId, (query) => queryError(query, error)));
      },
      markQueryOk: (taskId, epoch, at = Date.now()) => {
        write(epoch, (entries) => applyQuery(entries, taskId, (query) => queryOk(query, at)));
      },
      reset: () => {
        if (get().entries !== EMPTY) set({ entries: EMPTY });
      },
    };
  });
}

export type TaskStore = ReturnType<typeof createTaskStore>;

/** 应用内唯一的任务 store；切换会话时由 AuthProvider 统一清空。 */
export const taskStore: TaskStore = createTaskStore();
onUserScopeReset(() => taskStore.getState().reset());

/** React 中订阅 taskStore；selector 应返回稳定引用（如上面的 select* 函数）。 */
export function useTaskStore<T>(selector: (state: TaskStoreState) => T): T {
  return useStore(taskStore, selector);
}
