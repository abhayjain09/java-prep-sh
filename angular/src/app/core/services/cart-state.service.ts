import { Injectable, computed, signal } from '@angular/core';

import { Product } from '../models/product.model';

/**
 * A cart line — deliberately NOT reusing the `OrderLine` model. `OrderLine`
 * is a wire-format DTO shaped by the backend contract; `CartLine` is local,
 * in-progress UI state that doesn't exist as a concept on the server until
 * the user submits `OrderFormComponent`. Conflating "what the UI is
 * currently editing" with "what the API sends/returns" is a common source
 * of confusing, over-coupled frontend code — keep them as separate types
 * even when their shape looks identical today.
 */
export interface CartLine {
  readonly product: Product;
  readonly quantity: number;
}

/**
 * CartStateService — the Signals showcase for this module.
 *
 * WHY SIGNALS, NOT AN RxJS `BehaviorSubject<CartLine[]>`: the cart is
 * local, synchronous, in-memory UI state — "what has the user clicked 'add
 * to cart' on so far." Nothing asynchronous happens here; every mutation
 * is a direct, synchronous function call from a button click. That's
 * precisely the case README.md's "when to reach for Signals" section
 * describes: Signals shine for local component/app state that's read
 * synchronously in templates, because Angular's renderer subscribes to
 * signal reads automatically and can skip re-checking components that
 * don't read a signal that changed (fine-grained reactivity) — no
 * `| async` pipe, no manual subscribe/unsubscribe, no `OnDestroy`
 * boilerplate to avoid leaking a subscription.
 *
 * Compare this to `OrderService`/`ProductService` above: those return
 * Observables because HTTP responses are asynchronous and benefit from
 * RxJS's operator vocabulary (`switchMap`, `debounceTime`, `catchError`,
 * retry/backoff strategies). Signals and Observables INTEROPERATE rather
 * than compete — see `product-list.component.ts` for `toObservable` (feed
 * a signal's values into an RxJS pipeline) and `toSignal` (pull an
 * Observable's latest value into a signal for template reads) used
 * together in the same component.
 */
@Injectable({ providedIn: 'root' })
export class CartStateService {
  // The ONE writable signal — everything else derives from it via
  // computed(). This mirrors Inventory.java's encapsulation lesson: keep
  // exactly one mutable source of truth, expose only validated mutator
  // methods, never a raw reference callers could mutate directly.
  private readonly lines = signal<readonly CartLine[]>([]);

  /** Read-only view for templates/components — cannot be `.set()` externally. */
  readonly items = this.lines.asReadonly();

  /**
   * `computed()` — a DERIVED signal. It does NOT store its own value; it
   * re-runs its function only when a signal it read last time
   * (`this.lines()`) actually changes, and its result is cached between
   * reads until then (memoized). This is the Signals equivalent of a
   * getter that's cheap to call repeatedly from a template without
   * re-computing on every change-detection pass for unrelated reasons.
   */
  readonly itemCount = computed(() => this.lines().reduce((sum, line) => sum + line.quantity, 0));

  readonly total = computed(() =>
    this.lines().reduce((sum, line) => sum + line.product.price * line.quantity, 0),
  );

  readonly isEmpty = computed(() => this.lines().length === 0);

  /**
   * Adds a product to the cart, merging quantity into an existing line for
   * the same SKU rather than creating a duplicate line.
   *
   * CRITICAL DISCIPLINE — IMMUTABLE UPDATES: notice this never does
   * `this.lines().push(...)` or mutates an existing line object in place.
   * `signal.update()` must be given a function that returns a NEW array
   * (and new line objects for anything that changed) built from the old
   * one. This isn't just a Signals convention — it's the same discipline
   * `OnPush` change detection requires for `@Input()`-bound data (see
   * README.md's Change Detection section and `product-list.component.ts`).
   * If you mutated the array in place and called `.set()` with the SAME
   * reference, Angular's signal equality check (`===` by default) would
   * see no change and skip re-rendering — a subtle bug where the cart
   * updates in memory but the screen doesn't, until some unrelated
   * re-render happens to paper over it.
   */
  addItem(product: Product, quantity = 1): void {
    this.lines.update((current) => {
      const existingIndex = current.findIndex((line) => line.product.sku === product.sku);
      if (existingIndex === -1) {
        return [...current, { product, quantity }];
      }
      const next = [...current];
      const existing = next[existingIndex];
      next[existingIndex] = { ...existing, quantity: existing.quantity + quantity };
      return next;
    });
  }

  updateQuantity(sku: string, quantity: number): void {
    if (quantity <= 0) {
      this.removeItem(sku);
      return;
    }
    this.lines.update((current) =>
      current.map((line) => (line.product.sku === sku ? { ...line, quantity } : line)),
    );
  }

  removeItem(sku: string): void {
    this.lines.update((current) => current.filter((line) => line.product.sku !== sku));
  }

  clear(): void {
    this.lines.set([]);
  }
}
