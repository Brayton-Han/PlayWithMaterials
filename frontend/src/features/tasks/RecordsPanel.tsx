"use client";

import { TaskList } from "./TaskList";
import { selectAll, useTaskStore, type TaskScope, type TaskStoreState } from "./taskStore";
import { useTaskHistory } from "./useTaskHistory";
import { useTaskTracker } from "./useTaskTracker";

const ALL: TaskScope = { kind: "all" };
const selectAllEntries = (state: TaskStoreState) => selectAll(state.entries);

/**
 * "我的记录"页内容（T-055；F-11 规则 1 ~ 4，OQ-09 余项临时默认：创建时间倒序、不分页、不筛选）：
 * 挂载时请求当前用户全部模板的历史写入 taskStore，失败时显示错误 + 重试（不显示为空）；
 * 列表复用 TaskCard，每条显示模板标题和封面，点击进入该模板详情页；
 * 自动跟踪其中未结束的任务（离开页面即停止）。与详情页共用同一个 store，同一任务在两处状态一致。
 * 只在已登录时渲染（由 RequireAuth 守卫；会话切换时随 SessionBoundary 重新挂载）。
 */
export function RecordsPanel() {
  const entries = useTaskStore(selectAllEntries);
  const { refetch } = useTaskTracker(ALL);
  const { load } = useTaskHistory(ALL);

  return <TaskList entries={entries} onRefetch={refetch} load={load} showTemplate label="我的制作记录" />;
}
