"use client";

import { useCallback, useEffect, useMemo, useState } from "react";
import { gateway } from "@/lib/api";
import { codePart, formatVnd } from "@/lib/format";
import type { ApiError, ProductDetail, Sku, SkuInput, Stock } from "@/lib/types";
import { ErrorBox } from "@/components/ErrorBox";
import { toast } from "@/components/Toast";

type Draft = {
  skuCode: string;
  attributes: Record<string, string>;
  priceVnd: string;
  compareAtPriceVnd: string;
  imageId: string;
  active: boolean;
};

function toDraft(sku: Sku): Draft {
  return {
    skuCode: sku.skuCode,
    attributes: { ...sku.attributes },
    priceVnd: String(sku.priceVnd),
    compareAtPriceVnd: sku.compareAtPriceVnd === null ? "" : String(sku.compareAtPriceVnd),
    imageId: sku.imageId ?? "",
    active: sku.active,
  };
}

function toInput(d: Draft): SkuInput {
  return {
    skuCode: d.skuCode.trim().toUpperCase(),
    attributes: d.attributes,
    priceVnd: Number(d.priceVnd),
    compareAtPriceVnd: d.compareAtPriceVnd === "" ? null : Number(d.compareAtPriceVnd),
    imageId: d.imageId || null,
    active: d.active,
  };
}

/** Mọi tổ hợp giá trị của các option (tích Descartes) — mỗi tổ hợp là một SKU tiềm năng. */
function combinations(options: ProductDetail["options"]): Record<string, string>[] {
  return options.reduce<Record<string, string>[]>(
    (acc, o) => acc.flatMap((combo) => o.values.map((v) => ({ ...combo, [o.name]: v }))),
    [{}],
  );
}

const sameAttributes = (a: Record<string, string>, b: Record<string, string>) =>
  Object.keys(a).length === Object.keys(b).length && Object.entries(a).every(([k, v]) => b[k] === v);

/**
 * SKU (biến thể) của SPU + tồn kho của từng SKU. Hai service khác nhau: giá/mã/thuộc tính ở Product Service, tồn kho ở
 * Inventory Service — trang này ghép hai nguồn, mỗi thao tác gọi đúng service sở hữu dữ liệu đó.
 * Không có nút xóa SKU: SKU đã nằm trong đơn hàng/tồn kho → chỉ tắt "Đang bán".
 */
export function SkuManager({
  product,
  token,
  onChange,
}: {
  product: ProductDetail;
  token: string;
  onChange: (p: ProductDetail) => void;
}) {
  const [drafts, setDrafts] = useState<Record<string, Draft>>({});
  const [stock, setStock] = useState<Record<string, Stock>>({});
  const [stockEdits, setStockEdits] = useState<Record<string, string>>({});
  const [error, setError] = useState<ApiError | null>(null);
  const base = `/api/products/manage/${product.id}/skus`;

  const loadStock = useCallback(async () => {
    if (product.skus.length === 0) return;
    const ids = product.skus.map((s) => `ids=${s.id}`).join("&");
    const r = await gateway<Stock[]>(`/api/inventory/batch?${ids}`);
    setStock(Object.fromEntries((r.data ?? []).map((s) => [s.skuId, s])));
  }, [product.skus]);

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- tải tồn kho khi danh sách SKU đổi
    void loadStock();
  }, [loadStock]);

  const missing = useMemo(
    () => combinations(product.options).filter((c) => !product.skus.some((s) => sameAttributes(s.attributes, c))),
    [product.options, product.skus],
  );

  function draftOf(sku: Sku): Draft {
    return drafts[sku.id] ?? toDraft(sku);
  }

  function edit(sku: Sku, patch: Partial<Draft>) {
    setDrafts({ ...drafts, [sku.id]: { ...draftOf(sku), ...patch } });
  }

  async function save(sku: Sku) {
    const r = await gateway<ProductDetail>(`${base}/${sku.id}`, { method: "PUT", token, body: toInput(draftOf(sku)) });
    if (!r.ok || !r.data) {
      setError(r.error);
      return;
    }
    setError(null);
    setDrafts((d) => {
      const next = { ...d };
      delete next[sku.id];
      return next;
    });
    onChange(r.data);
    toast(`Đã lưu ${sku.skuCode}`, "ok");
  }

  /** Tồn kho thuộc Inventory Service: PUT /api/inventory/{skuId} (ADMIN), không đi qua Product Service. */
  async function saveStock(skuId: string, value: string) {
    const r = await gateway<Stock>(`/api/inventory/${skuId}`, { method: "PUT", token, body: { stockOnHand: Number(value) } });
    if (!r.ok || !r.data) {
      toast(`${r.error?.code}: ${r.error?.message}`, "bad");
      return false;
    }
    setStock((s) => ({ ...s, [skuId]: r.data! }));
    setStockEdits((e) => {
      const next = { ...e };
      delete next[skuId];
      return next;
    });
    return true;
  }

  return (
    <section className="card space-y-4">
      <div>
        <h2 className="h2">Biến thể (SKU) & tồn kho</h2>
        <p className="muted">
          Giỏ hàng, tồn kho và đơn hàng đều tính theo SKU. Đổi giá chỉ áp dụng cho đơn mới — đơn cũ giữ snapshot giá.
        </p>
      </div>

      <ErrorBox error={error} />
      <div className="overflow-x-auto">
        <table className="table">
          <thead>
            <tr>
              <th>Mã SKU</th>
              {product.options.map((o) => <th key={o.name}>{o.name}</th>)}
              <th>Giá bán</th>
              <th>Giá gạch</th>
              <th>Ảnh</th>
              <th>Bán</th>
              <th />
              <th>Tồn kho (giữ / khả dụng)</th>
            </tr>
          </thead>
          <tbody>
            {product.skus.map((sku) => {
              const d = draftOf(sku);
              const dirty = !!drafts[sku.id];
              const st = stock[sku.id];
              return (
                <tr key={sku.id} className={sku.active ? "" : "opacity-60"}>
                  <td>
                    <input aria-label="Mã SKU" className="input mono w-36" value={d.skuCode}
                           onChange={(e) => edit(sku, { skuCode: e.target.value.toUpperCase() })} />
                  </td>
                  {product.options.map((o) => (
                    <td key={o.name}>
                      <select aria-label={o.name} className="input w-28" value={d.attributes[o.name] ?? ""}
                              onChange={(e) => edit(sku, { attributes: { ...d.attributes, [o.name]: e.target.value } })}>
                        {o.values.map((v) => <option key={v} value={v}>{v}</option>)}
                      </select>
                    </td>
                  ))}
                  <td>
                    <input aria-label="Giá bán" type="number" min={0} step={1000} className="input w-32" value={d.priceVnd}
                           onChange={(e) => edit(sku, { priceVnd: e.target.value })} />
                  </td>
                  <td>
                    <input aria-label="Giá gạch ngang" type="number" min={0} step={1000} className="input w-32"
                           placeholder="—" value={d.compareAtPriceVnd}
                           onChange={(e) => edit(sku, { compareAtPriceVnd: e.target.value })} />
                  </td>
                  <td>
                    <select aria-label="Ảnh biến thể" className="input w-28" value={d.imageId}
                            onChange={(e) => edit(sku, { imageId: e.target.value })}>
                      <option value="">Ảnh SPU</option>
                      {product.images.map((img, i) => <option key={img.id} value={img.id}>Ảnh {i + 1}</option>)}
                    </select>
                  </td>
                  <td>
                    <input aria-label="Đang bán" type="checkbox" checked={d.active}
                           onChange={(e) => edit(sku, { active: e.target.checked })} />
                  </td>
                  <td>
                    <button className="btn btn-sm btn-primary" disabled={!dirty} onClick={() => void save(sku)}>Lưu</button>
                  </td>
                  <td>
                    <div className="flex items-center gap-1">
                      <input aria-label="Tồn kho" type="number" min={0} className="input w-20"
                             value={stockEdits[sku.id] ?? String(st?.stockOnHand ?? 0)}
                             onChange={(e) => setStockEdits({ ...stockEdits, [sku.id]: e.target.value })} />
                      <button className="btn btn-sm" disabled={stockEdits[sku.id] === undefined}
                              onClick={() => void saveStock(sku.id, stockEdits[sku.id])}>
                        Lưu
                      </button>
                      <span className="whitespace-nowrap text-xs text-muted">
                        {st ? `${st.reservedQuantity} / ${st.available}` : "chưa có"}
                      </span>
                    </div>
                  </td>
                </tr>
              );
            })}
            {product.skus.length === 0 && (
              <tr><td colSpan={7 + product.options.length} className="muted">Chưa có SKU — sản phẩm chưa mua được.</td></tr>
            )}
          </tbody>
        </table>
      </div>

      <AddSku product={product} token={token} missing={missing} onCreated={onChange} saveStock={saveStock} />
    </section>
  );
}

/**
 * Thêm SKU: từng cái, hoặc tạo hàng loạt mọi tổ hợp option còn thiếu (ví dụ 2 màu × 3 size = 6 SKU) với cùng giá và
 * tồn kho ban đầu. Mã SKU sinh từ tiền tố + giá trị option ("TEE" + "Đen" + "XL" → TEE-DEN-XL).
 */
function AddSku({
  product,
  token,
  missing,
  onCreated,
  saveStock,
}: {
  product: ProductDetail;
  token: string;
  missing: Record<string, string>[];
  onCreated: (p: ProductDetail) => void;
  saveStock: (skuId: string, value: string) => Promise<boolean>;
}) {
  const [prefix, setPrefix] = useState(codePart(product.slug).slice(0, 12));
  const [attributes, setAttributes] = useState<Record<string, string>>({});
  const [price, setPrice] = useState("");
  const [initialStock, setInitialStock] = useState("10");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<ApiError | null>(null);

  const codeFor = (attrs: Record<string, string>) =>
    [prefix, ...product.options.map((o) => codePart(attrs[o.name] ?? ""))].filter(Boolean).join("-");

  async function create(attrs: Record<string, string>): Promise<ProductDetail | null> {
    const skuCode = codeFor(attrs);
    const body: SkuInput = { skuCode, attributes: attrs, priceVnd: Number(price), compareAtPriceVnd: null, imageId: null, active: true };
    const r = await gateway<ProductDetail>(`/api/products/manage/${product.id}/skus`, { method: "POST", token, body });
    if (!r.ok || !r.data) {
      setError(r.error);
      return null;
    }
    const created = r.data.skus.find((s) => s.skuCode === skuCode);
    if (created && initialStock !== "") await saveStock(created.id, initialStock);
    return r.data;
  }

  async function run(list: Record<string, string>[]) {
    if (price === "") {
      setError({ status: 400, code: "PRICE_REQUIRED", message: "Nhập giá bán cho SKU mới" });
      return;
    }
    setBusy(true);
    setError(null);
    let latest: ProductDetail | null = null;
    for (const attrs of list) {
      const result = await create(attrs);
      if (!result) break;
      latest = result;
    }
    setBusy(false);
    if (latest) {
      onCreated(latest);
      toast(`Đã tạo ${list.length === 1 ? "1 SKU" : `${list.length} SKU`}`, "ok");
    }
  }

  const singleReady = product.options.every((o) => attributes[o.name]);
  const noOptionsAndHasSku = product.options.length === 0 && product.skus.length > 0;

  return (
    <div className="space-y-3 rounded-lg border border-dashed border-border p-4">
      <p className="label">Thêm SKU</p>
      <div className="flex flex-wrap items-end gap-3">
        <div>
          <label className="label" htmlFor="prefix">Tiền tố mã</label>
          <input id="prefix" className="input mono w-32" value={prefix} onChange={(e) => setPrefix(codePart(e.target.value))} />
        </div>
        {product.options.map((o) => (
          <div key={o.name}>
            <label className="label" htmlFor={`new-${o.name}`}>{o.name}</label>
            <select id={`new-${o.name}`} className="input w-28" value={attributes[o.name] ?? ""}
                    onChange={(e) => setAttributes({ ...attributes, [o.name]: e.target.value })}>
              <option value="">—</option>
              {o.values.map((v) => <option key={v} value={v}>{v}</option>)}
            </select>
          </div>
        ))}
        <div>
          <label className="label" htmlFor="new-price">Giá bán (₫)</label>
          <input id="new-price" type="number" min={0} step={1000} className="input w-36" value={price} onChange={(e) => setPrice(e.target.value)} />
        </div>
        <div>
          <label className="label" htmlFor="new-stock">Tồn kho ban đầu</label>
          <input id="new-stock" type="number" min={0} className="input w-24" value={initialStock} onChange={(e) => setInitialStock(e.target.value)} />
        </div>
        <button className="btn btn-primary" disabled={busy || !singleReady || noOptionsAndHasSku} onClick={() => void run([attributes])}>
          Thêm 1 SKU {singleReady && <span className="mono opacity-80">{codeFor(attributes)}</span>}
        </button>
        {product.options.length > 0 && (
          <button className="btn" disabled={busy || missing.length === 0} onClick={() => void run(missing)}>
            Tạo {missing.length} tổ hợp còn thiếu
          </button>
        )}
      </div>
      {noOptionsAndHasSku && (
        <p className="text-xs text-muted">Sản phẩm không có option chỉ có một SKU. Thêm option (Màu, Size...) ở phần thông tin để có nhiều biến thể.</p>
      )}
      {price && <p className="text-xs text-muted">Giá áp dụng: {formatVnd(Number(price))}</p>}
      <ErrorBox error={error} />
    </div>
  );
}
