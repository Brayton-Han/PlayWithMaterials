/** URL 中的分类参数（architecture §6：`/?category=`）。 */
export const CATEGORY_QUERY_KEY = "category";

/**
 * 列表页最近一次确认的分类筛选，供详情页"返回"回到原分类（F-02 验收 4 建议）。
 *
 * 只存在内存中：站内跳转时保留；直接打开或刷新详情页时为 null，"返回"回到"全部"。
 * 服务端渲染与首次水合时两端都是 null，不会产生水合差异。
 */
let lastCategory: string | null = null;

export function rememberListCategory(categoryId: string | null): void {
  lastCategory = categoryId;
}

/** 返回列表页的链接：带上最近一次的分类筛选。 */
export function listPageHref(): string {
  return lastCategory ? `/?${new URLSearchParams({ [CATEGORY_QUERY_KEY]: lastCategory }).toString()}` : "/";
}
