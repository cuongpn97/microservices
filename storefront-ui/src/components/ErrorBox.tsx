import type { ApiError } from "@/lib/types";

export function ErrorBox({ error }: { error: ApiError | null | undefined }) {
  if (!error) return null;
  return (
    <div className="rounded-lg border border-bad/30 bg-bad-soft px-3 py-2 text-sm text-bad">
      <div className="font-semibold">
        {error.status ? `${error.status} · ` : ""}
        {error.code}
      </div>
      <div>{error.message}</div>
      {error.violations && error.violations.length > 0 && (
        <ul className="mt-1 list-disc pl-5">
          {error.violations.map((v) => (
            <li key={v.field}>
              {v.field}: {v.message}
            </li>
          ))}
        </ul>
      )}
      {error.correlationId && <div className="mono mt-1 opacity-70">correlationId: {error.correlationId}</div>}
    </div>
  );
}
