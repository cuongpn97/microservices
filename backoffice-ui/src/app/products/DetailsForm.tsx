"use client";

import { useState } from "react";
import { gateway } from "@/lib/api";
import type { ApiError, ProductDetail, ProductInput } from "@/lib/types";
import { ErrorBox } from "@/components/ErrorBox";
import { Markdown } from "@/components/Markdown";
import { toast } from "@/components/Toast";

type OptionRow = { name: string; values: string };
type SpecRow = { name: string; value: string };

/**
 * Thông tin chung của SPU: tên, slug, thương hiệu, mô tả (Markdown), thông số, các trục biến thể (options).
 * SKU và ảnh có phần quản lý riêng (cần SPU đã tồn tại).
 */
export function DetailsForm({
  product,
  token,
  onSaved,
}: {
  product: ProductDetail | null;
  token: string;
  onSaved: (p: ProductDetail) => void;
}) {
  const [name, setName] = useState(product?.name ?? "");
  const [slug, setSlug] = useState(product?.slug ?? "");
  const [brand, setBrand] = useState(product?.brand ?? "");
  const [shortDescription, setShortDescription] = useState(product?.shortDescription ?? "");
  const [description, setDescription] = useState(product?.description ?? "");
  const [preview, setPreview] = useState(false);
  const [specs, setSpecs] = useState<SpecRow[]>(product?.specifications ?? []);
  const [options, setOptions] = useState<OptionRow[]>(
    (product?.options ?? []).map((o) => ({ name: o.name, values: o.values.join(", ") })),
  );
  const [active, setActive] = useState(product?.active ?? false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    const body: ProductInput = {
      name,
      // Tạo mới: để trống → server tự sinh từ tên. Sửa: gửi slug hiện tại (đổi slug làm hỏng link đã chia sẻ).
      slug: slug.trim() || undefined,
      brand,
      shortDescription,
      description,
      specifications: specs.filter((s) => s.name.trim() && s.value.trim()),
      options: options
        .filter((o) => o.name.trim())
        .map((o) => ({ name: o.name.trim(), values: o.values.split(",").map((v) => v.trim()).filter(Boolean) })),
      active,
    };
    const r = product
      ? await gateway<ProductDetail>(`/api/products/manage/${product.id}`, { method: "PUT", token, body })
      : await gateway<ProductDetail>("/api/products/manage", { method: "POST", token, body });
    setBusy(false);
    if (!r.ok || !r.data) {
      setError(r.error);
      return;
    }
    toast(product ? "Đã lưu thông tin sản phẩm" : "Đã tạo sản phẩm (bản nháp) — thêm SKU và ảnh rồi bật bán", "ok");
    onSaved(r.data);
  }

  return (
    <form onSubmit={submit} className="card space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="h2">Thông tin sản phẩm (SPU)</h2>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={active} onChange={(e) => setActive(e.target.checked)} />
          Đang bán (hiển thị ở cửa hàng)
        </label>
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        <div>
          <label className="label" htmlFor="name">Tên *</label>
          <input id="name" className="input" required maxLength={200} value={name} onChange={(e) => setName(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="slug">Slug (URL)</label>
          <input id="slug" className="input mono" maxLength={220} pattern="[a-z0-9]+(-[a-z0-9]+)*" value={slug}
                 placeholder={product ? "" : "để trống: tự sinh từ tên"} onChange={(e) => setSlug(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="brand">Thương hiệu</label>
          <input id="brand" className="input" maxLength={100} value={brand} onChange={(e) => setBrand(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="short">Mô tả ngắn</label>
          <input id="short" className="input" maxLength={500} value={shortDescription} onChange={(e) => setShortDescription(e.target.value)} />
        </div>
      </div>

      <div>
        <div className="mb-1 flex items-center justify-between">
          <label className="label mb-0" htmlFor="desc">Mô tả chi tiết (Markdown)</label>
          <button type="button" className="btn btn-sm" onClick={() => setPreview(!preview)}>
            {preview ? "Sửa" : "Xem trước"}
          </button>
        </div>
        {preview ? (
          <div className="min-h-40 rounded-lg border border-border p-3">
            <Markdown>{description || "_(trống)_"}</Markdown>
          </div>
        ) : (
          <textarea id="desc" className="input mono min-h-40" maxLength={20000} value={description}
                    placeholder={"## Tiêu đề\n\n- gạch đầu dòng\n- **in đậm**\n\n| Size | Ngực |\n|---|---|\n| M | 96 |"}
                    onChange={(e) => setDescription(e.target.value)} />
        )}
        <p className="mt-1 text-xs text-muted">HTML thô không được render ở cửa hàng (chống XSS) — chỉ dùng cú pháp Markdown.</p>
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <fieldset className="space-y-2">
          <legend className="label">Thông số kỹ thuật</legend>
          {specs.map((s, i) => (
            <div key={i} className="flex gap-2">
              <input aria-label="Tên thông số" className="input" placeholder="Kết nối" value={s.name}
                     onChange={(e) => setSpecs(specs.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
              <input aria-label="Giá trị" className="input" placeholder="USB-C" value={s.value}
                     onChange={(e) => setSpecs(specs.map((x, j) => (j === i ? { ...x, value: e.target.value } : x)))} />
              <button type="button" className="btn btn-sm" aria-label="Xóa" onClick={() => setSpecs(specs.filter((_, j) => j !== i))}>✕</button>
            </div>
          ))}
          <button type="button" className="btn btn-sm" onClick={() => setSpecs([...specs, { name: "", value: "" }])}>+ Thông số</button>
        </fieldset>

        <fieldset className="space-y-2">
          <legend className="label">Phân loại (option) — tối đa 3</legend>
          {options.map((o, i) => (
            <div key={i} className="flex gap-2">
              <input aria-label="Tên option" className="input w-32" placeholder="Màu" value={o.name}
                     onChange={(e) => setOptions(options.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)))} />
              <input aria-label="Giá trị" className="input" placeholder="Đen, Trắng, Xanh" value={o.values}
                     onChange={(e) => setOptions(options.map((x, j) => (j === i ? { ...x, values: e.target.value } : x)))} />
              <button type="button" className="btn btn-sm" aria-label="Xóa" onClick={() => setOptions(options.filter((_, j) => j !== i))}>✕</button>
            </div>
          ))}
          {options.length < 3 && (
            <button type="button" className="btn btn-sm" onClick={() => setOptions([...options, { name: "", values: "" }])}>+ Option</button>
          )}
          <p className="text-xs text-muted">
            Mỗi SKU chọn đúng một giá trị trên mỗi option. Không thể xóa giá trị đang được SKU dùng (server trả 422).
          </p>
        </fieldset>
      </div>

      <ErrorBox error={error} />
      <button className="btn btn-primary" disabled={busy}>{busy ? "Đang lưu…" : product ? "Lưu thông tin" : "Tạo sản phẩm"}</button>
    </form>
  );
}
