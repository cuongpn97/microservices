const vnd = new Intl.NumberFormat("vi-VN");

export function formatVnd(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return `${vnd.format(value)} ₫`; // non-breaking space: không để "₫" rơi xuống dòng
}

/** Khoảng giá của SPU: "199.000 ₫" hoặc "199.000 ₫ – 219.000 ₫". */
export function formatPriceRange(min: number | null | undefined, max: number | null | undefined): string {
  if (min === null || min === undefined) return "Chưa có giá";
  if (max === null || max === undefined || max === min) return formatVnd(min);
  return `${formatVnd(min)} – ${formatVnd(max)}`;
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleTimeString("vi-VN", { hour12: false });
}

export function formatDate(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleDateString("vi-VN");
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("vi-VN", { hour12: false });
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : "—";
}

/** Gợi ý cho các SKU demo (UUID cố định trong seed) — giúp người học biết bấm vào đâu để thấy từng kịch bản. */
export const SKU_HINTS: Record<string, string> = {
  "33333333-3333-3333-3333-333333333333": "Chỉ còn 2 — đặt 3 để thấy Saga thất bại vì hết hàng",
  "44444444-4444-4444-4444-444444444444": "25 triệu > credit 10 triệu — thấy Saga bồi hoàn",
  "11111111-1111-1111-1111-000000000003": "Biến thể hết hàng (tồn kho 0) — nút mua bị khóa",
};

/** URL của backoffice (trang vận hành: nhật ký Saga, DLQ...). Biến NEXT_PUBLIC_* được bake vào bundle trình duyệt. */
export const BACKOFFICE_URL = process.env.NEXT_PUBLIC_BACKOFFICE_URL ?? "http://localhost:3001";
