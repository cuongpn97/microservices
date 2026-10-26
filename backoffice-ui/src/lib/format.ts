const vnd = new Intl.NumberFormat("vi-VN");

export function formatVnd(value: number | null | undefined): string {
  if (value === null || value === undefined) return "—";
  return `${vnd.format(value)} ₫`; // non-breaking space: không để "₫" rơi xuống dòng
}

export function formatPriceRange(min: number | null | undefined, max: number | null | undefined): string {
  if (min === null || min === undefined) return "—";
  if (max === null || max === undefined || max === min) return formatVnd(min);
  return `${formatVnd(min)} – ${formatVnd(max)}`;
}

export function formatTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleTimeString("vi-VN", { hour12: false });
}

export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "—";
  return new Date(iso).toLocaleString("vi-VN", { hour12: false });
}

export function formatBytes(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.round(bytes / 1024)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export function shortId(id: string | null | undefined): string {
  return id ? id.slice(0, 8) : "—";
}

/** "Đen" → "DEN", "Xanh lá" → "XANH-LA": phần mã SKU sinh tự động từ giá trị option. */
export function codePart(text: string): string {
  return text
    .replace(/đ/g, "d")
    .replace(/Đ/g, "D")
    .normalize("NFD")
    .replace(/\p{M}+/gu, "")
    .toUpperCase()
    .replace(/[^A-Z0-9]+/g, "-")
    .replace(/(^-+|-+$)/g, "");
}

/** URL storefront để mở trang sản phẩm như khách thấy. */
export const STOREFRONT_URL = process.env.NEXT_PUBLIC_STOREFRONT_URL ?? "http://localhost:3000";
export const SWAGGER_URL = process.env.NEXT_PUBLIC_SWAGGER_URL ?? "http://localhost:8080/swagger-ui.html";
