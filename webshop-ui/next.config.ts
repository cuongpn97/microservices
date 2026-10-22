import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Bản build tự chứa (node server.js) cho Docker image nhỏ gọn.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
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
