import Link from "next/link";
import { formatPriceRange, formatVnd } from "@/lib/format";
import { ProductImage } from "./ProductImage";
import { RatingLine } from "./Stars";

export type CardProduct = {
  slug: string;
  name: string;
  brand: string | null;
  minPriceVnd: number | null;
  maxPriceVnd: number | null;
  imageUrl: string | null;
  ratingAvg: number | null;
  ratingCount: number;
};

/** Thẻ SPU dùng chung cho danh sách Product Service và kết quả search-service (cùng các field). */
export function ProductCard({ product }: { product: CardProduct }) {
  const ranged = product.maxPriceVnd !== null && product.maxPriceVnd !== product.minPriceVnd;
  return (
    <Link
      href={`/products/${product.slug}`}
      className="card group flex flex-col gap-3 p-3 transition hover:border-accent/50 hover:shadow-sm"
    >
      <ProductImage src={product.imageUrl} alt={product.name} />
      <div className="flex flex-1 flex-col gap-1 px-1 pb-1">
        {product.brand && <span className="text-xs uppercase tracking-wide text-muted">{product.brand}</span>}
        <h2 className="line-clamp-2 font-medium group-hover:text-accent">{product.name}</h2>
        <RatingLine avg={product.ratingAvg} count={product.ratingCount} />
        <div className="mt-auto pt-1 font-semibold">
          {ranged ? (
            <>
              <span className="text-xs font-normal text-muted">Từ </span>
              {formatVnd(product.minPriceVnd)}
            </>
          ) : (
            formatPriceRange(product.minPriceVnd, product.maxPriceVnd)
          )}
        </div>
      </div>
    </Link>
  );
}
