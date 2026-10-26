"use client";

import { useRef, useState } from "react";
import { gateway } from "@/lib/api";
import { formatBytes } from "@/lib/format";
import type { ProductDetail } from "@/lib/types";
import { ProductImage } from "@/components/ProductImage";
import { toast } from "@/components/Toast";

const MAX_IMAGES = 10;
const MAX_BYTES = 5 * 1024 * 1024;

/**
 * Ảnh của SPU. Upload đi qua BFF → gateway (route riêng, giới hạn 6MB) → product-service; product-service kiểm tra
 * nội dung file (JPEG/PNG/WebP), lưu vào object storage (S3) rồi mới ghi DB. Ảnh đầu tiên là ảnh đại diện.
 */
export function ImageManager({
  product,
  token,
  onChange,
}: {
  product: ProductDetail;
  token: string;
  onChange: (p: ProductDetail) => void;
}) {
  const input = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const [alts, setAlts] = useState<Record<string, string>>({});
  const images = product.images;
  const base = `/api/products/manage/${product.id}/images`;

  async function upload(files: FileList | null) {
    if (!files) return;
    let current = product;
    for (const file of Array.from(files)) {
      if (current.images.length >= MAX_IMAGES) {
        toast(`Tối đa ${MAX_IMAGES} ảnh mỗi sản phẩm`, "bad");
        break;
      }
      if (file.size > MAX_BYTES) {
        toast(`${file.name}: lớn hơn 5MB`, "bad");
        continue;
      }
      setUploading(file.name);
      const form = new FormData();
      form.append("file", file);
      const r = await gateway<ProductDetail>(base, { method: "POST", token, body: form });
      if (r.ok && r.data) {
        current = r.data;
        onChange(r.data);
      } else {
        toast(`${file.name}: ${r.error?.code} — ${r.error?.message}`, "bad");
      }
    }
    setUploading(null);
    if (input.current) input.current.value = "";
  }

  async function move(index: number, delta: number) {
    const ids = images.map((i) => i.id);
    const target = index + delta;
    if (target < 0 || target >= ids.length) return;
    [ids[index], ids[target]] = [ids[target], ids[index]];
    const r = await gateway<ProductDetail>(`${base}/order`, { method: "PUT", token, body: { imageIds: ids } });
    if (r.ok && r.data) onChange(r.data);
    else toast(`${r.error?.code}: ${r.error?.message}`, "bad");
  }

  async function saveAlt(id: string) {
    const altText = alts[id];
    if (altText === undefined) return;
    const r = await gateway<ProductDetail>(`${base}/${id}`, { method: "PUT", token, body: { altText } });
    if (r.ok && r.data) {
      onChange(r.data);
      setAlts((current) => {
        const next = { ...current };
        delete next[id];
        return next;
      });
      toast("Đã lưu mô tả ảnh", "ok");
    } else toast(`${r.error?.code}: ${r.error?.message}`, "bad");
  }

  async function remove(id: string) {
    if (!confirm("Xóa ảnh này? SKU đang dùng ảnh sẽ quay về ảnh đại diện.")) return;
    const r = await gateway<ProductDetail>(`${base}/${id}`, { method: "DELETE", token });
    if (r.ok && r.data) onChange(r.data);
    else toast(`${r.error?.code}: ${r.error?.message}`, "bad");
  }

  return (
    <section className="card space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="h2">Hình ảnh ({images.length}/{MAX_IMAGES})</h2>
          <p className="muted">JPEG, PNG hoặc WebP, tối đa 5MB. Ảnh đầu tiên là ảnh đại diện ở danh sách và kết quả tìm kiếm.</p>
        </div>
        <label className={`btn btn-primary ${images.length >= MAX_IMAGES || uploading ? "pointer-events-none opacity-50" : ""}`}>
          {uploading ? `Đang tải ${uploading}…` : "+ Tải ảnh lên"}
          <input
            ref={input}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            className="sr-only"
            onChange={(e) => void upload(e.target.files)}
          />
        </label>
      </div>

      {images.length === 0 ? (
        <p className="rounded-lg border border-dashed border-border p-6 text-center text-sm text-muted">
          Chưa có ảnh. Cửa hàng sẽ hiển thị ô giữ chỗ.
        </p>
      ) : (
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-3 lg:grid-cols-5">
          {images.map((img, i) => (
            <div key={img.id} className="space-y-2">
              <div className="relative">
                <ProductImage src={img.url} alt={img.altText} sizes="200px" />
                {i === 0 && <span className="badge absolute top-2 left-2 bg-accent text-white">Đại diện</span>}
              </div>
              <input
                aria-label="Mô tả ảnh (alt)"
                className="input text-xs"
                value={alts[img.id] ?? img.altText}
                maxLength={200}
                onChange={(e) => setAlts({ ...alts, [img.id]: e.target.value })}
                onBlur={() => void saveAlt(img.id)}
              />
              <div className="flex items-center justify-between gap-1">
                <div className="flex gap-1">
                  <button className="btn btn-sm" disabled={i === 0} aria-label="Lên trước" onClick={() => void move(i, -1)}>←</button>
                  <button className="btn btn-sm" disabled={i === images.length - 1} aria-label="Ra sau" onClick={() => void move(i, 1)}>→</button>
                </div>
                <span className="text-xs text-muted">{formatBytes(img.sizeBytes)}</span>
                <button className="btn btn-sm text-bad" onClick={() => void remove(img.id)}>Xóa</button>
              </div>
            </div>
          ))}
        </div>
      )}
    </section>
  );
}
