import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Bản build tự chứa (node server.js) cho Docker image nhỏ gọn.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  images: {
    // Ảnh sản phẩm đã nằm trên object storage (S3/CDN) với Cache-Control dài hạn; trình duyệt tải thẳng từ đó.
    // Không cho server Next.js tải lại để tối ưu: trong Docker, "localhost:8333" (URL công khai) không trỏ tới object storage.
    unoptimized: true,
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
};

export default nextConfig;
