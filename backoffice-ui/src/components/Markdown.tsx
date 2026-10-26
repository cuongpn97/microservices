import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

/**
 * Render mô tả sản phẩm (Markdown do admin nhập). react-markdown KHÔNG render HTML thô theo mặc định (không dùng
 * rehype-raw) → thẻ <script>, onerror=... trong mô tả chỉ hiện như văn bản: không có XSS từ dữ liệu catalog.
 * remark-gfm thêm bảng (bảng size), gạch đầu dòng công việc, gạch ngang.
 */
export function Markdown({ children }: { children: string }) {
  return (
    <div className="markdown">
      <ReactMarkdown remarkPlugins={[remarkGfm]}>{children}</ReactMarkdown>
    </div>
  );
}
