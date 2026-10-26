import type { ProductOption, Sku, Stock } from "@/lib/types";

export type Selection = Record<string, string>;

/** SKU khớp đúng lựa chọn (đã chọn đủ mọi option). SPU không có option → SKU duy nhất. */
export function findSku(skus: Sku[], options: ProductOption[], selection: Selection): Sku | null {
  if (options.length === 0) return skus[0] ?? null;
  if (options.some((o) => !selection[o.name])) return null;
  return skus.find((s) => options.every((o) => s.attributes[o.name] === selection[o.name])) ?? null;
}

/**
 * Trạng thái một giá trị option khi các option KHÁC giữ nguyên lựa chọn hiện tại:
 *   "none"     — không có biến thể nào (tổ hợp không tồn tại) → khóa nút
 *   "soldout"  — có biến thể nhưng hết hàng → vẫn chọn được để xem, không mua được
 *   "ok"       — mua được
 */
export function valueState(
  skus: Sku[],
  options: ProductOption[],
  selection: Selection,
  option: string,
  value: string,
  stock: Map<string, Stock>,
): "none" | "soldout" | "ok" {
  const candidates = skus.filter(
    (s) =>
      s.attributes[option] === value &&
      options.every((o) => o.name === option || !selection[o.name] || s.attributes[o.name] === selection[o.name]),
  );
  if (candidates.length === 0) return "none";
  return candidates.some((s) => (stock.get(s.id)?.available ?? 0) > 0) ? "ok" : "soldout";
}

/** Lựa chọn mặc định: biến thể còn hàng đầu tiên, nếu không có thì biến thể đầu tiên. */
export function defaultSelection(skus: Sku[], stock: Map<string, Stock>): Selection {
  const sku = skus.find((s) => (stock.get(s.id)?.available ?? 0) > 0) ?? skus[0];
  return sku ? { ...sku.attributes } : {};
}
