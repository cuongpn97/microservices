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

export type Product = {
  id: string;
  name: string;
  description: string;
  priceVnd: number;
  active: boolean;
};

export type Stock = {
  productId: string;
  stockOnHand: number;
  reservedQuantity: number;
  available: number;
};

export type CartItem = {
  productId: string;
  quantity: number;
  name: string | null;
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
  productId: string;
  productName: string;
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
