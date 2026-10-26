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

// ------------------------------------------------------------------ Catalog (Product Service)

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
  updatedAt: string;
};

export type ProductOption = { name: string; values: string[] };
export type Specification = { name: string; value: string };

export type ProductImage = {
  id: string;
  url: string;
  altText: string;
  position: number;
  contentType: string;
  sizeBytes: number;
};

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
  description: string;
  specifications: Specification[];
  options: ProductOption[];
  skus: Sku[];
  images: ProductImage[];
  ratingAvg: number | null;
  ratingCount: number;
  active: boolean;
  createdAt: string;
  updatedAt: string;
  version: number;
};

/** Body của POST/PUT /api/products/manage. */
export type ProductInput = {
  name: string;
  slug?: string;
  brand: string;
  shortDescription: string;
  description: string;
  specifications: Specification[];
  options: ProductOption[];
  active: boolean;
};

export type SkuInput = {
  skuCode: string;
  attributes: Record<string, string>;
  priceVnd: number;
  compareAtPriceVnd: number | null;
  imageId: string | null;
  active: boolean;
};

// ------------------------------------------------------------------ Inventory / Review

export type Stock = {
  skuId: string;
  stockOnHand: number;
  reservedQuantity: number;
  available: number;
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

// ------------------------------------------------------------------ Vận hành (Saga)

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
  failureReason: string | null;
};

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
