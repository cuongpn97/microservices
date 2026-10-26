import Image from "next/image";

/**
 * Ảnh sản phẩm (tải thẳng từ object storage). Chưa có ảnh → ô giữ chỗ cùng tỉ lệ với chữ cái đầu tên,
 * để lưới sản phẩm không bị xô lệch.
 */
export function ProductImage({
  src,
  alt,
  sizes = "(min-width: 1024px) 33vw, (min-width: 640px) 50vw, 100vw",
  priority = false,
  className = "",
}: {
  src: string | null | undefined;
  alt: string;
  sizes?: string;
  priority?: boolean;
  className?: string;
}) {
  return (
    <div className={`relative aspect-square overflow-hidden rounded-lg bg-surface-2 ${className}`}>
      {src ? (
        <Image src={src} alt={alt} fill sizes={sizes} priority={priority} className="object-cover" />
      ) : (
        <div aria-hidden className="grid h-full w-full place-items-center text-4xl font-semibold text-muted/50">
          {alt.trim().charAt(0).toUpperCase() || "?"}
        </div>
      )}
    </div>
  );
}
