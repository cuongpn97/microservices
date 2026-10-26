import { Suspense } from "react";
import { ReviewModeration } from "./ReviewModeration";

export default function ReviewsPage() {
  return (
    <Suspense fallback={<p className="muted">Đang tải…</p>}>
      <ReviewModeration />
    </Suspense>
  );
}
