const vnd = new Intl.NumberFormat("vi-VN");

export function formatVnd(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return `${vnd.format(value)} ₫`; // non-breaking space: không để "₫" rơi xuống dòng
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleTimeString("vi-VN", { hour12: false });
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("vi-VN", { hour12: false });
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : "—";
}

export const PRODUCT_HINTS: Record<string, string> = {
  "11111111-1111-1111-1111-111111111111": "Còn nhiều hàng",
  "22222222-2222-2222-2222-222222222222": "Còn nhiều hàng",
  "33333333-3333-3333-3333-333333333333": "Chỉ còn 2 — đặt 3 để thấy hết hàng",
  "44444444-4444-4444-4444-444444444444": "25 triệu > credit 10 triệu — thấy bồi hoàn",
};
