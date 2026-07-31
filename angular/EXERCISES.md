# Module 9 — Exercises

Do these in order — each builds on the previous one's code. Work directly
in `src/app/`. This sandbox has no Node/Angular CLI, so these exercises
assume you're working on a machine that does (`npm install` in `angular/`
first — see README.md's "How to build & run" section). No test framework
is wired up yet (Module 10) — verify each exercise by running `ng serve`
and exercising the UI directly in the browser, and by reading
`ng build`'s output for type errors.

## 1. (Beginner) Add a `stockOnHand` field to the product display

`Product` in java-basics has no stock field directly (`Inventory` tracks
stock separately, keyed by SKU) — but a real product listing UI usually
shows "in stock" / "low stock" / "out of stock." Extend
`core/models/product.model.ts`'s `Product` interface with an optional
`stockOnHand?: number` field, and update `product-list.component.ts`'s
template to show a badge: "In stock" (>10), "Low stock" (1-10), or "Out of
stock" (0), using `@if`/`@else if`/`@else`.

**Check yourself:** why is `stockOnHand` marked OPTIONAL (`?:`) rather than
required? What should the template do if a `Product` arrives from the API
without that field at all?

## 2. (Beginner) Add a "remove from cart" control to the cart

`CartStateService.removeItem(sku)` already exists but nothing in the UI
calls it yet. Add a small cart summary view (a new section in
`product-list.component.ts`'s template, or a new tiny standalone component
— your choice, and worth reasoning about which is more appropriate) that
lists each `CartLine` from `cart.items()` with a "Remove" button wired to
`removeItem`.

**Check yourself:** if you mutate `cart.items()`'s returned array directly
instead of calling `removeItem`, what happens on screen, and why does it
relate to this module's `OnPush` discipline?

## 3. (Intermediate) Add a `mergeMap`-vs-`switchMap` bug, then fix it

In `order.service.ts`, change `watchOrder`'s operator from `switchMap` to
`mergeMap`. Simulate two rapid navigations (`/orders/1` then immediately
`/orders/2`) where order 1's backend response is artificially delayed
(you can fake this locally with `delay(2000)` from RxJS piped onto
`getOrder` temporarily, or by pointing at a mock backend with an
artificial delay). Observe: does the screen ever show order 1's data
AFTER you've already navigated to order 2? Explain in a comment exactly
why `mergeMap` allows this and `switchMap` (the original, correct choice)
does not. Revert to `switchMap` when done.

## 4. (Intermediate) Add a `minQuantity`-per-SKU business rule to the custom validator

Product wants some SKUs to have a minimum order quantity (e.g. a SKU sold
only in packs of 6). Extend `positiveQuantityValidator` (or add a second
validator alongside it) to accept a `minQuantity: number` parameter and
reject quantities below it, with a distinct error key
(`{ belowMinimum: { required: minQuantity, actual } }`) so the template
can show a specific message. Wire it into `order-form.component.ts`'s
`buildLineGroup` for a hard-coded SKU of your choice.

**Check yourself:** should this rule live only in the frontend validator,
only in the backend, or both? Justify your answer the same way
java-basics' `InsufficientStockException` section justifies where business
rules should be enforced.

## 5. (Senior) Convert `ProductListComponent`'s search from debounced-HTTP to client-side filtering with a size threshold

Currently every search keystroke (after debouncing) hits the backend via
`ProductService.searchProducts`. For a catalog small enough to hold
entirely in memory (say, under 500 products), filtering client-side after
ONE initial fetch is often faster and simpler than a server round trip per
search. Refactor `product-list.component.ts` so that:
- The full catalog is fetched ONCE via `toSignal(productService.getProducts())`.
- The search box filters that already-loaded signal locally via a
  `computed()` (no HTTP call per keystroke, no `debounceTime` needed for
  correctness — though consider whether it's still worth keeping for
  large-list render-cost reasons).
- Write a short comment explaining the exact trade-off: at what catalog
  size would you switch BACK to server-side search, and why (hint: think
  about initial payload size and memory, not just per-keystroke latency).

## 6. (Scenario) The backend team ships `spring/`'s real contract, and it doesn't match your assumption

`spring/` (Module 5) is now done, and its real API differs from this
module's assumed contract in two ways: (a) `POST /api/v1/orders`'s
response wraps the order in an envelope, `{ data: Order, meta: {...} }`,
instead of returning the `Order` directly; (b) cancelling an order is
`PATCH /api/v1/orders/{id}` with body `{ status: "CANCELLED" }`, not a
dedicated `/cancel` endpoint. Update `order.service.ts` to match, using
`map` to unwrap the envelope in `placeOrder`, and change `cancelOrder`'s
HTTP verb/body accordingly — WITHOUT changing either method's public
signature (`Observable<Order>` in, `Observable<Order>` out) or touching
`order-form.component.ts`/`order-detail.component.ts` at all.

**Check yourself:** why was it possible to absorb both changes entirely
inside `OrderService` with zero changes to any component? What does that
tell you about where the "real API shape" should — and shouldn't — leak
into a codebase? (This is the same lesson `OrderLine.java`'s "snapshot the
price, don't trust the client" note and `Inventory.java`'s encapsulated
map are both instances of, applied to a frontend service boundary instead
of a Java domain object.)
