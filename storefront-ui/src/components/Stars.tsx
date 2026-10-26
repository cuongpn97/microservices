/** Hiển thị điểm 0–5 bằng sao (đọc được bởi trình đọc màn hình qua aria-label). */
export function Stars({ value, size = "text-sm" }: { value: number | null; size?: string }) {
  const v = value ?? 0;
  return (
    <span className={`inline-flex ${size} leading-none`} aria-label={value === null ? "Chưa có đánh giá" : `${v} trên 5 sao`}>
      {[1, 2, 3, 4, 5].map((i) => {
        const fill = Math.max(0, Math.min(1, v - (i - 1)));
        return (
          <span key={i} aria-hidden className="relative inline-block text-border">
            ★
            <span className="absolute inset-0 overflow-hidden text-warn" style={{ width: `${fill * 100}%` }}>
              ★
            </span>
          </span>
        );
      })}
    </span>
  );
}

export function RatingLine({ avg, count }: { avg: number | null; count: number }) {
  if (count === 0) return <span className="text-xs text-muted">Chưa có đánh giá</span>;
  return (
    <span className="inline-flex items-center gap-1.5 text-xs text-muted">
      <Stars value={avg} />
      <span className="font-medium text-text">{avg?.toFixed(1)}</span>
      <span>({count})</span>
    </span>
  );
}
