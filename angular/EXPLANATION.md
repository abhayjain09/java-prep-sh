# Module 9 — Line-by-Line Explanation

Walks through every file in [src/app](src/app) in dependency order
(models first, then services, then the guard/interceptor that use them,
then routing/config, then components). The "why" for each choice also
lives inline in the code comments — this file adds narrative and connects
choices across files, the same relationship java-basics'
`EXPLANATION.md` has to its `README.md`.

## `core/models/customer.model.ts`, `product.model.ts`, `order.model.ts`

Plain TypeScript `interface`s, not classes — these are wire-format DTOs
deserialized from JSON, and interfaces have zero runtime footprint (see
`customer.model.ts`'s comment on what that does and doesn't buy you:
compile-time shape-checking, but NO runtime validation that a response
actually matches).

```ts
export type OrderStatus = 'PENDING' | 'CONFIRMED' | 'SHIPPED' | 'DELIVERED' | 'CANCELLED';
```
A string union, not a TypeScript `enum` — mirrors `OrderStatus.java`'s five
constants exactly, compares with `===` against raw JSON strings from the
API with no conversion step, and is erased entirely at compile time (zero
runtime cost, unlike a TS `enum`, which compiles to a real object).

```ts
export function lineTotal(line: OrderLine): number {
  return line.product.price * line.quantity;
}
```
A free function, not a method on the `OrderLine` interface (interfaces
can't carry behavior) — the direct TypeScript analogue of
`OrderLine.lineTotal()` in java-basics, same formula, same reasoning
(derived, not stored).

```ts
export interface PlaceOrderRequest {
  customerId: string;
  lines: Array<{ sku: string; quantity: number }>;
}
```
Deliberately sends IDENTIFIERS only (`customerId`, `sku`), never full
nested `Customer`/`Product` objects — the backend looks those up and
computes prices server-side. Never trust client-supplied price/name data
for anything that affects money; this is the same principle
`OrderLine.java`'s "snapshot the price server-side, don't trust the
client" production note makes.

## `core/services/auth.service.ts`

```ts
private readonly state = signal<AuthState | null>(readPersistedState());
readonly isAuthenticated = computed(() => this.state() !== null);
```
One private writable signal as the single source of truth; every public
member is a `computed()` derived from it. This is the Signals-flavored
version of `Inventory.java`'s encapsulation lesson from Module 1: exactly
one mutable field, exposed only through read-only derivations and
validated mutator methods (`login`/`logout`), never a raw settable
reference.

```ts
function buildFakeJwt(username: string, role: Role): string { ... }
```
Fabricates a real-SHAPED (`header.payload.signature`) but unsigned token
so downstream code (the interceptor, a developer pasting it into jwt.io to
inspect claims) behaves identically to how it would against a real
backend token. The class-level comment is explicit about this being a
stand-in for `security/`'s (Module 6) eventual real
`POST /api/v1/auth/login` — the only code that would change when that
module lands is inside `login()` itself.

## `core/services/product.service.ts` / `order.service.ts`

```ts
private readonly http = inject(HttpClient);
```
Function-based injection (`inject()`), not a constructor parameter — see
README.md's DI section for the full rationale; used consistently
throughout this module.

```ts
return this.http.get<Product[]>(this.baseUrl).pipe(
  catchError((error: HttpErrorResponse) => {
    console.error('Failed to load products', error);
    return of<Product[]>([]);
  }),
);
```
`catchError` intercepts an ERROR notification on the Observable (not a
normal value) and substitutes a REPLACEMENT Observable. `ProductService`
degrades to an empty catalog on failure (a read — showing nothing is an
acceptable failure mode); `OrderService.rethrowWithContext` instead
re-throws after logging (a command with a side effect the user is waiting
on — silently showing nothing here would be actively misleading). Compare
this asymmetry to java-basics' Exception Handling section: "never swallow
silently" applies to RxJS error channels exactly as it applies to Java
`catch` blocks.

```ts
watchOrder(id$: Observable<string>): Observable<Order> {
  return id$.pipe(switchMap((id) => this.getOrder(id)));
}
```
`switchMap` maps each id to a fresh HTTP call AND cancels any still-pending
previous call — critical for a stream of route-param changes, where an
in-flight request for a PREVIOUS id resolving after a NEWER id's request
would otherwise overwrite the correct data with stale data. See
`order-detail.component.ts` for the call site and INTERVIEW.md for the
`switchMap` vs `mergeMap`/`concatMap` interview question this sets up.

## `core/services/cart-state.service.ts`

```ts
private readonly lines = signal<readonly CartLine[]>([]);
readonly items = this.lines.asReadonly();
readonly total = computed(() => this.lines().reduce((sum, l) => sum + l.product.price * l.quantity, 0));
```
The Signals showcase. `asReadonly()` returns a signal that can be READ but
not `.set()`/`.update()` externally — the same encapsulation idea as
`Order.getLines()`'s defensive copy in java-basics, adapted to Signals'
API instead of an immutable collection copy.

```ts
addItem(product: Product, quantity = 1): void {
  this.lines.update((current) => {
    const existingIndex = current.findIndex((line) => line.product.sku === product.sku);
    if (existingIndex === -1) return [...current, { product, quantity }];
    const next = [...current];
    next[existingIndex] = { ...next[existingIndex], quantity: next[existingIndex].quantity + quantity };
    return next;
  });
}
```
Every branch returns a NEW array (and, when updating an existing line, a
new line object via spread) — never mutates `current` in place. This is
the exact discipline README.md's Change Detection section walks through
with a before/after example; `CartStateService` is where that discipline
is actually implemented, not just described.

## `core/validators/positive-quantity.validator.ts`

```ts
export function positiveQuantityValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;
    if (value === null || value === undefined || value === '') return null;
    const numeric = Number(value);
    if (!Number.isInteger(numeric) || numeric <= 0) return { positiveQuantity: { actual: value } };
    return null;
  };
}
```
A factory function returning a `ValidatorFn` (matching how Angular's own
`Validators.min(5)` is shaped) enforcing exactly `OrderLine`'s Java-side
invariant (`quantity <= 0` throws). Delegates the "is it empty" case to
`Validators.required` — one validator, one job — and returns `null`
(Angular's specific "valid" sentinel; `undefined`/`false` do NOT count)
for anything it isn't checking.

## `core/interceptors/auth.interceptor.ts`

```ts
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  ...
  const authorizedReq = token ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } }) : req;
  return next(authorizedReq).pipe(catchError((error) => { ... }));
};
```
A plain FUNCTION matching `HttpInterceptorFn`'s signature, not a class
implementing `HttpInterceptor` — see the file's header comment for the
full old-vs-new comparison. `req.clone({...})` is required because
`HttpRequest` is immutable — there is no mutable `.headers` setter to
assign to directly, mirroring the same "copy with changes, never mutate"
discipline `CartStateService` follows for Signals.

## `core/guards/auth.guard.ts`

```ts
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isAuthenticated()) return true;
  return router.createUrlTree(['/products'], { queryParams: { authRequired: state.url } });
};
```
Returns a `UrlTree` (via `createUrlTree`) rather than calling
`router.navigate()` AND returning `false` — a `UrlTree` return value tells
the router itself to redirect, avoiding two navigations racing each other.
`auth.isAuthenticated()` is a synchronous Signal read — this guard needs
no Observable/Promise handling at all, even though `CanActivateFn` also
supports returning `Observable<boolean | UrlTree>` for guards that need to
make an async check (e.g. a real token-validation network call — not
needed here since `AuthService`'s mock login is synchronous).

## `app.routes.ts`

Every route uses `loadComponent: () => import(...).then(m => m.XxxComponent)`
— see README.md's Routing/Lazy-Loading section for why every route (not
just the minimum "at least one" the brief calls for) is lazy-loaded here,
and the trade-off of doing that even for the landing route (`/products`).
`orders/new` and `orders/:id` add `canActivate: [authGuard]`; `products`
deliberately has none.

## `app.config.ts`

```ts
providers: [
  provideZoneChangeDetection({ eventCoalescing: true }),
  provideRouter(routes),
  provideHttpClient(withInterceptors([authInterceptor])),
],
```
The entire standalone bootstrap configuration — no `AppModule` exists
anywhere in this project. `withInterceptors([authInterceptor])` registers
the functional interceptor; additional interceptors would simply be added
to that array, with array order determining execution order for the
outgoing-request phase.

## `app.component.ts`

The root shell — eager (bootstrapped directly in `main.ts`), unlike every
feature component. Its toolbar reads `AuthService`'s signals directly
(`auth.isAuthenticated()`, `auth.username()`, `auth.role()`) and exposes
mock login/logout buttons in place of a real login page (see README.md's
"What's deliberately out of scope" section for why).

## `features/product-list/product-list.component.ts`

```ts
readonly searchTerm = signal('');
private readonly filteredProducts$ = toObservable(this.searchTerm).pipe(
  debounceTime(300),
  distinctUntilChanged(),
  switchMap((term) => this.productService.searchProducts(term)),
);
readonly products = toSignal(this.filteredProducts$, { initialValue: [] });
```
The module's single densest teaching example: a local Signal
(`searchTerm`) feeds `toObservable`, which feeds a standard RxJS debounced-
search pipeline, whose result is converted back to a Signal (`products`)
for the template. One field demonstrates Signals, RxJS operators, AND the
interop between them simultaneously. `ChangeDetectionStrategy.OnPush` is
applied here specifically (in addition to the other components) because
this is the component README.md's Change Detection section walks through
in the most depth — see that section for the full before/after mutation
example using this component's cart-count/cart-total reads as the
motivating case.

```ts
@for (product of products(); track product.sku) { ... }
```
`track product.sku`, not index — a re-sorted or re-filtered `products()`
array (from a new search term) maps to the SAME DOM rows for unchanged
products, avoiding destroy/recreate churn on every keystroke's result.

## `features/order-form/order-form.component.ts`

```ts
readonly form = this.fb.group({
  customerId: this.fb.control('', { validators: [Validators.required], nonNullable: true }),
  lines: this.fb.array<FormGroup>([]),
});
```
A `FormArray` of `FormGroup`s for the dynamic line-items list — the core
reason this form uses `ReactiveFormsModule` rather than template-driven
forms (see README.md's Reactive vs. Template-Driven section). `ngOnInit`
seeds `lines` from `CartStateService.items()`, read ONCE synchronously
(not subscribed to) — the Signals-to-reactive-form bridge called for in
this module's brief: after that initial seed, the form owns its own state
independently of the cart.

```ts
quantity: this.fb.control(quantity, {
  validators: [Validators.required, positiveQuantityValidator()],
}),
```
Two validators composed on one control — both must pass. `onSubmit`
builds a `PlaceOrderRequest` (identifiers only, per the model's design)
and calls `OrderService.placeOrder`, clearing the cart and navigating to
the new order's detail page on success.

## `features/order-detail/order-detail.component.ts`

```ts
private readonly orderId$ = this.route.paramMap.pipe(map((params) => params.get('id')!));
private readonly liveOrder = toSignal(this.orderService.watchOrder(this.orderId$), { initialValue: undefined as Order | undefined });
private readonly cancelledOverride = signal<Order | null>(null);
readonly currentOrder = computed(() => this.cancelledOverride() ?? this.liveOrder());
```
Combines a route-driven, RxJS-backed signal (`liveOrder`, via
`watchOrder`'s `switchMap`) with a locally-set override
(`cancelledOverride`) into one derived `computed()` — showing that
`computed()` can combine signals from genuinely different sources
(one RxJS-bridged, one plain local state) transparently.

```ts
function canTransitionToCancelled(status: OrderStatus): boolean {
  switch (status) {
    case 'PENDING':
    case 'CONFIRMED':
      return true;
    case 'SHIPPED':
    case 'DELIVERED':
    case 'CANCELLED':
      return false;
  }
}
```
Mirrors `OrderStatus.canTransitionTo` from java-basics, scoped to just the
CANCELLED transition this UI checks. With `noImplicitReturns: true` set in
`tsconfig.json`, TypeScript requires every code path to return — omitting
a case here (e.g. if a sixth `OrderStatus` value were added without
updating this function) leaves a path that falls through without
returning, which fails to compile. The same "the compiler forces you to
handle it" property java-basics' `OrderStatus.legalNextStates()` switch
EXPRESSION gets for free from Java's enum-exhaustiveness checking — here
achieved via a slightly different mechanism (`noImplicitReturns` + a
`boolean` return type), since a TypeScript string union doesn't have
Java's `switch`-expression exhaustiveness checking built in natively.

The Cancel button itself is gated by BOTH this function and
`auth.hasRole('MANAGER')` — see README.md's discussion of why the role
check is UX only, not a security boundary.
