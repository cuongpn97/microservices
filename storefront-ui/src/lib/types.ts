export type ApiError = {
  status: number;
  code: string;
  message: string;
  correlationId?: string | null;
  violations?: { field: string; message: string }[];
};

export type TokenResponse = {
  accessToken: string;
  tokenType: string;
  expiresIn: number;
  expiresAt: string;
  userId: string;
  roles: string[];
};

export type Page<T> = {
  items: T[];
  page: number;
  size: number;
  totalElements: number;
};

/** Một SPU trong danh sách (Product Service). Giá là khoảng min–max qua các SKU đang bán. */
export type ProductSummary = {
  id: string;
  slug: string;
  name: string;
  brand: string | null;
  shortDescription: string;
  minPriceVnd: number | null;
  maxPriceVnd: number | null;
  imageUrl: string | null;
  ratingAvg: number | null;
  ratingCount: number;
  skuCount: number;
  active: boolean;
};

export type ProductOption = { name: string; values: string[] };
export type Specification = { name: string; value: string };

export type ProductImage = {
  id: string;
  url: string;
  altText: string;
  position: number;
};

/** SKU — biến thể bán được; giỏ hàng, tồn kho, đơn hàng đều tính theo SKU. */
export type Sku = {
  id: string;
  skuCode: string;
  attributes: Record<string, string>;
  variantName: string;
  priceVnd: number;
  compareAtPriceVnd: number | null;
  imageId: string | null;
  imageUrl: string | null;
  active: boolean;
  sellable: boolean;
};

export type ProductDetail = {
  id: string;
  slug: string;
  name: string;
  brand: string | null;
  shortDescription: string;
  /** Markdown */
  description: string;
  specifications: Specification[];
  options: ProductOption[];
  skus: Sku[];
  images: ProductImage[];
  ratingAvg: number | null;
  ratingCount: number;
};

export type Stock = {
  skuId: string;
  stockOnHand: number;
  reservedQuantity: number;
  available: number;
};

/** Kết quả từ search-service (read model Elasticsearch, có thể trễ vài giây). Không có tồn kho — xem Stock. */
export type SearchHit = {
  productId: string;
  slug: string;
  name: string;
  brand: string | null;
  shortDescription: string | null;
  minPriceVnd: number | null;
  maxPriceVnd: number | null;
  imageUrl: string | null;
  ratingAvg: number | null;
  ratingCount: number;
  score: number | null;
};

export type SearchResult = {
  items: SearchHit[];
  totalElements: number;
  tookMs: number;
};

export type CartItem = {
  skuId: string;
  quantity: number;
  productId: string | null;
  productSlug: string | null;
  productName: string | null;
  skuCode: string | null;
  variantName: string | null;
  imageUrl: string | null;
  unitPriceVnd: number | null;
  lineTotalVnd: number | null;
  active: boolean;
};

export type Cart = {
  userId: string;
  revision: number;
  items: CartItem[];
  estimatedTotalVnd: number | null;
  pricingAvailable: boolean;
};

export type OrderItem = {
  skuId: string;
  /** null với đơn tạo trước khi có SKU */
  productId: string | null;
  productName: string;
  skuCode: string | null;
  variantName: string | null;
  unitPriceVnd: number;
  quantity: number;
  lineTotalVnd: number;
};

export type OrderStatus = "PENDING" | "CONFIRMED" | "FAILED";
export type SagaState =
  | "PENDING"
  | "INVENTORY_RESERVED"
  | "CONFIRMING"
  | "COMPLETED"
  | "COMPENSATING"
  | "FAILED";

export type Order = {
  orderId: string;
  status: OrderStatus;
  sagaState: SagaState | null;
  totalVnd: number;
  items: OrderItem[];
  failureReason: string | null;
  createdAt: string;
  updatedAt: string;
};

export type Wallet = {
  userId: string;
  balanceVnd: number;
  reservedVnd: number;
  availableVnd: number;
  totalCapturedVnd: number;
};

export type LedgerEntry = {
  id: string;
  type: "INITIAL_CREDIT" | "RESERVE" | "CAPTURE" | "RELEASE";
  amountVnd: number;
  balanceAfterVnd: number;
  reservedAfterVnd: number;
  referenceId: string | null;
  createdAt: string;
};

export type Review = {
  id: string;
  productId: string;
  authorName: string;
  rating: number;
  title: string | null;
  content: string;
  variantName: string;
  status: "PUBLISHED" | "HIDDEN";
  createdAt: string;
  updatedAt: string;
};

export type RatingSummary = {
  productId: string;
  ratingCount: number;
  ratingAvg: number | null;
  /** số sao → số đánh giá */
  histogram: Record<string, number>;
};

export type MyReview = { canReview: boolean; review: Review | null };

/** Nhật ký Saga (chỉ backoffice đọc được — cần token ADMIN). SagaTracker nhận tùy chọn để vẽ đúng đường đi thật. */
export type SagaTransition = {
  from_state: string | null;
  to_state: string;
  trigger: string;
  detail: string | null;
  created_at: string;
};

export type SagaView = {
  sagaId: string;
  state: SagaState;
  inventoryStep: string;
  paymentStep: string;
  stepAttempts: number;
  deadlineAt: string | null;
  needsAttention: boolean;
  failureReason: string | null;
  lastError: string | null;
  updatedAt: string;
  transitions: SagaTransition[];
};
