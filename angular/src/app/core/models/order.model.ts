import { Customer } from './customer.model';
import { Product } from './product.model';

/**
 * Mirrors java-basics' `OrderStatus` enum exactly (PENDING, CONFIRMED,
 * SHIPPED, DELIVERED, CANCELLED). Modeled as a TypeScript **string union**,
 * not a `class`/`enum`, deliberately:
 *
 * - A TS `enum` compiles to a runtime object and has historically had rough
 *   edges with tree-shaking and `const enum` isolatedModules restrictions.
 * - A string union (`'PENDING' | 'CONFIRMED' | ...`) is erased entirely at
 *   compile time (zero runtime cost) and compares by value with `===`,
 *   which is exactly what comparing JSON strings from the backend needs.
 * - It gives the same exhaustiveness benefit as the Java enum: a `switch`
 *   over `OrderStatus` that's missing a case is flagged if you assign the
 *   switch's result to a variable typed `never` in the default branch
 *   (see `order-detail.component.ts` for that pattern in `canCancel()`).
 */
export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';

/**
 * Mirrors `OrderLine` (product, quantity). The Java side derives
 * `lineTotal()` on demand from `product.price() * quantity` rather than
 * storing it — we mirror that here with a pure helper function
 * (`lineTotal`) instead of a class method, since interfaces can't carry
 * behavior. Keeping it a free function (not a method smuggled onto the
 * interface) keeps `OrderLine` a plain, JSON-serializable shape.
 */
export interface OrderLine {
  product: Product;
  quantity: number;
}

export function lineTotal(line: OrderLine): number {
  return line.product.price * line.quantity;
}

/**
 * Mirrors `Order` (id, customer, lines, status). `totalAmount` is included
 * here as a field because the assumed API returns it pre-computed from the
 * server (the same way `Order.totalAmount()` is computed server-side in
 * Java) — the frontend should not need to re-derive business totals from
 * raw lines; that logic belongs in exactly one place (the backend), not
 * duplicated in the UI. `orderTotal()` below is provided only as a
 * client-side fallback/display helper (e.g. optimistic UI before the
 * server responds), not as the source of truth.
 */
export interface Order {
  id: string;
  customer: Customer;
  lines: OrderLine[];
  status: OrderStatus;
  totalAmount: number;
}

export function orderTotal(order: Pick<Order, 'lines'>): number {
  return order.lines.reduce((sum, line) => sum + lineTotal(line), 0);
}

/**
 * The request body shape for `POST /api/v1/orders` (assumed contract — see
 * angular/README.md "API contract this module assumes"). The backend looks
 * up `Customer`/`Product` by id/sku server-side; the client only sends
 * identifiers, never full nested objects, to avoid trusting client-supplied
 * price/name data for anything that affects money.
 */
export interface PlaceOrderRequest {
  customerId: string;
  lines: Array<{ sku: string; quantity: number }>;
}
