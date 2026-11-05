import type { Category } from "@/lib/api/endpoints/catalog";

interface CategoryTabsProps {
  categories: readonly Category[];
  /** 当前选中的分类；null = "全部" */
  selected: string | null;
  onSelect: (categoryId: string | null) => void;
}

/** 分类标签行："全部"（虚拟，OQ-03 余项临时默认）+ 后端返回的一级分类。 */
export function CategoryTabs({ categories, selected, onSelect }: CategoryTabsProps) {
  const options: { id: string | null; name: string }[] = [
    { id: null, name: "全部" },
    ...categories.map((c) => ({ id: c.categoryId, name: c.name })),
  ];

  return (
    <nav aria-label="模板分类">
      <ul className="flex flex-wrap gap-2">
        {options.map((option) => {
          const active = option.id === selected;
          return (
            <li key={option.id ?? "__all__"}>
              <button
                type="button"
                aria-pressed={active}
                onClick={() => {
                  if (!active) onSelect(option.id);
                }}
                className={`rounded-full border px-4 py-1.5 text-sm focus-visible:ring-2 focus-visible:ring-zinc-400 focus-visible:outline-none ${
                  active
                    ? "border-zinc-900 bg-zinc-900 text-white dark:border-zinc-100 dark:bg-zinc-100 dark:text-zinc-900"
                    : "border-zinc-300 hover:bg-zinc-100 dark:border-zinc-700 dark:hover:bg-zinc-800"
                }`}
              >
                {option.name}
              </button>
            </li>
          );
        })}
      </ul>
    </nav>
  );
}
