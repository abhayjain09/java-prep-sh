# Module 9 — Angular, Beginner to Advanced

**Domain used throughout:** the same Order/Inventory system introduced in
[java-basics/](../java-basics/) (Module 1) — `Customer`, `Product`,
`Order`, `OrderLine`, `OrderStatus`. This module is the browser-side UI
over the REST API that `spring/` (Module 5) is building concurrently. It
does **not** read or depend on `spring/`'s in-progress source — instead it
assumes a plausible, standard-REST contract (below) and builds
self-contained Angular code against that assumed contract. When `spring/`
lands, reconciling any drift between "what this module assumed" and "what
the real API returns" is itself a useful exercise (see EXERCISES.md).

Companion files:
- [diagrams/component-routing-tree.md](diagrams/component-routing-tree.md) — component tree, routing, lazy-loading boundaries, DI
- [diagrams/interceptor-guard-sequence.md](diagrams/interceptor-guard-sequence.md) — sequence diagram: guard at navigation time, interceptor at HTTP-request time
- [src/](src/) — the actual code
- [EXPLANATION.md](EXPLANATION.md) — line-by-line walkthrough of every file in `src/`
- [EXERCISES.md](EXERCISES.md) — hands-on exercises
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers

## The API contract this module assumes

Written without reading `spring/`'s in-progress files, on purpose (see the
top-level task's scope boundary — that module may be mid-write). This is a
standard-REST guess based on the domain shapes in `java-basics/`:

| Method | Path | Body | Returns | Notes |
|---|---|---|---|---|
| `GET` | `/api/v1/products` | — | `Product[]` | optional `?q=` search filter |
| `GET` | `/api/v1/products/{sku}` | — | `Product` | |
| `GET` | `/api/v1/orders/{id}` | — | `Order` | |
| `POST` | `/api/v1/orders` | `{ customerId, lines: [{ sku, quantity }] }` | `Order` | `409` if stock insufficient (`InsufficientStockException`, java-basics) |
| `POST` | `/api/v1/orders/{id}/cancel` | `{}` | `Order` | `409` if the current status can't legally transition to `CANCELLED` (`OrderStatus.canTransitionTo`, java-basics) |

`Product.price` is assumed to serialize as a JSON number (the default for
a Jackson-serialized `BigDecimal`) — see `core/models/product.model.ts`'s
header comment for why that's a real precision trade-off worth naming
explicitly, not silently accepting.

## How to build & run this for real

This sandbox has no Node.js/npm/Angular CLI installed, so nothing here has
been compiled or executed — every file is written by hand to be exactly
what `ng generate` plus manual edits would produce. On a machine with
Node.js 20+ and the Angular CLI installed:

```bash
cd angular
npm install                 # installs everything in package.json
npm start                   # = ng serve --proxy-config proxy.conf.json, http://localhost:4200
npm run build                # production build -> dist/orders-ui
```

`proxy.conf.json` forwards any request to `/api/**` from the dev server
(port 4200) to `http://localhost:8080` (where `spring/`'s Spring Boot app
is assumed to run) — this avoids CORS entirely in local development, which
is the standard Angular+Spring Boot dev setup. In production, `spring/`'s
API and this Angular app are typically served from the same origin
(Spring Boot serving the built Angular assets as static resources, or a
reverse proxy/API gateway in front of both) or CORS is configured
explicitly on the backend (`security/`, Module 6).

---

## 1. Standalone Components

### What it is
A standalone component (`standalone: true` in `@Component(...)`, the
DEFAULT for every component `ng generate` creates since Angular 17)
declares its own dependencies (other components, directives, pipes) via an
`imports: [...]` array directly on the `@Component` decorator, instead of
being declared inside an `NgModule`'s `declarations` array and depending
on that module's `imports` for what it can use in its template.

### Why introduced / problem it solves
Before standalone components (Angular <14), EVERY component had to belong
to exactly one `NgModule`, and using another component/directive/pipe in a
template required that dependency to flow through the module graph
(`declarations` + `exports` + `imports`) — a substantial amount of
bookkeeping that added no runtime behavior, only build-time wiring. New
Angular developers regularly hit "NG0304: 'app-foo' is not a known
element" purely from forgetting an `NgModule` import, a class of error
that doesn't exist once a component just lists what it needs directly.
Standalone components also make lazy-loading far more granular — see
Section 2.

### When to use / when not to use
- Use standalone components for every new component in an Angular 17+
  project — it's the CLI default and the direction the framework has
  fully committed to (as of Angular 19, `NgModule`s are optional
  everywhere, and the standalone `bootstrapApplication` path is the only
  one `ng new` scaffolds).
- The only reason to still reach for `NgModule`s is a large, not-yet-
  migrated legacy codebase, or a third-party library that hasn't published
  a standalone-compatible API yet (rare today, but existed during the
  Angular 14-16 transition period).

### Trade-offs & performance implications
- Every standalone component explicitly lists its own template
  dependencies, which means more `imports: [...]` boilerplate per
  component compared to one shared `NgModule` importing `CommonModule`
  once for a whole feature area. In practice this is a net win for
  comprehensibility (you can see exactly what a component template can
  use by reading its decorator, no module-graph archaeology required) and
  it enables finer-grained lazy-loading/tree-shaking, since the bundler
  can trace exactly which pipes/directives a given lazy chunk actually
  needs.
- See Section 4 (new control-flow syntax) for a related, compounding
  benefit: `@if`/`@for` need NO import at all (they're built into the
  template compiler), which further shrinks the typical `imports` array
  versus the old `*ngIf`/`*ngFor` directives that required importing
  `CommonModule` (or `NgIf`/`NgFor` individually) into every standalone
  component that used them.

### Enterprise examples
- Large Angular codebases migrating off `NgModule`s incrementally
  (Angular's own migration schematic, `ng generate @angular/core:standalone`,
  automates converting a module-based app component-by-component) —a
  realistic multi-quarter migration project at any company running an
  Angular app started pre-2023.

### Common mistakes
- Forgetting `standalone: true` is now the DEFAULT (Angular 17+
  `@Component()` decorators don't need to state it explicitly, though this
  module states it anyway for clarity/explicitness given it's teaching
  material) and instead trying to also declare the component in an
  `NgModule` — a standalone component cannot be declared in an `NgModule`'s
  `declarations` array; that's a compile error.
- Importing an entire feature's worth of components into one
  `imports: [...]` array "just in case," recreating the same
  over-coupling `NgModule`s had, rather than importing exactly what a
  given template uses.

---

## 2. Routing & Lazy Loading

### What it is
`app.routes.ts` defines a `Routes` array wired up via `provideRouter(routes)`
in `app.config.ts` (no `RouterModule.forRoot()`/`NgModule` involved). Every
feature route in this module (`products`, `orders/new`, `orders/:id`) uses
`loadComponent: () => import(...).then(m => m.XxxComponent)` — Angular's
per-route code-splitting mechanism for standalone components.

### Why introduced / problem it solves
Without lazy loading, the ENTIRE application's JavaScript ships in the
initial bundle the browser must download, parse, and execute before
anything renders — for a real app with dozens of features, most of which
a given user session never visits, that's wasted bytes on the critical
path to first paint. `loadComponent` (and its predecessor for whole
feature modules, `loadChildren`) defers fetching a route's code until the
user actually navigates there.

### When to use / when not to use
- Lazy-load any route that isn't needed for the very first screen the
  user sees, which in practice is most routes in most apps. This module
  lazy-loads every single feature route, including `/products` (the
  landing route) — a deliberate choice to demonstrate the pattern
  uniformly; a real app might instead eagerly bundle the landing route's
  component (accepting a larger initial bundle) to avoid an extra network
  round trip on the very first paint, trading initial bundle size against
  time-to-interactive for that first route specifically. There's no
  universally correct answer — profile your actual app's load waterfall.
- Don't lazy-load something used on virtually every navigation regardless
  of route (e.g. the root shell/toolbar) — `AppComponent` here is eager
  (bootstrapped directly), which is correct; it's needed immediately no
  matter which route loads.

### Trade-offs & performance implications
- Every lazy-loaded route adds one extra network round trip (fetch that
  chunk) the FIRST time a user navigates there in a session; the browser
  then caches it, so subsequent navigations to the same route are instant.
  On a slow connection, this can introduce a visible flash/spinner on
  first navigation — mitigated with route pre-fetching strategies
  (`withPreloading(PreloadAllModules)` or a custom preloading strategy) if
  the trade-off matters for your app's UX budget.
- Guards (`canActivate`) are checked BEFORE the lazy chunk is even
  fetched — see `authGuard`'s placement in
  `diagrams/interceptor-guard-sequence.md`. This means an unauthenticated
  user hitting a guarded route never pays the network cost of downloading
  that route's JS at all — a genuine performance benefit of combining
  guards with lazy loading, not just a security/UX one.

### Enterprise examples
- Any admin dashboard where 90% of users never touch the "Settings" or
  "Reports" section in a given session — those routes are prime lazy-load
  candidates; loading them eagerly for every user "just in case" wastes
  bandwidth for the common case to (marginally) speed up the rare case.

### Common mistakes
- Putting the wildcard route (`path: '**'`) anywhere except LAST in the
  `Routes` array — the router matches top-to-bottom and a wildcard placed
  earlier swallows every route after it (see `app.routes.ts`'s comment).
- Guarding a route with `canActivate` but forgetting the guard only
  protects NAVIGATION — a component reachable without going through the
  router (e.g. rendered directly by a parent that skips the router
  entirely) wouldn't be protected. Not a concern in this module's flat
  route structure, but relevant once nested/child routes and dynamically
  rendered component trees enter the picture.

---

## 3. RxJS

### What it is
RxJS models asynchronous, potentially-multi-valued event streams
(`Observable<T>`) with a rich operator vocabulary for transforming,
combining, and taming them. This module uses it for every HTTP call
(`ProductService`, `OrderService`) and for a debounced search box
(`ProductListComponent`).

### Why introduced / problem it solves
A single HTTP response could be modeled as a `Promise<T>` — and indeed
`fetch()` does exactly that. Angular's `HttpClient` instead returns
`Observable<T>` because REAL apps routinely need to: cancel an in-flight
request (unsubscribe), retry with backoff, race/combine multiple requests,
debounce a rapid sequence of triggers (a search box), or model a
genuinely multi-valued stream (WebSocket messages, polling). A `Promise`
can do none of these natively — once created, it's already running and
can't be cancelled or retried without wrapping it in more code that RxJS
gives you as composable, testable operators.

### Operators demonstrated in this module
- **`map`** — `product.service.ts`'s `getProduct` (shown for symmetry;
  transforms each emitted value).
- **`catchError`** — every service method; intercepts an error notification
  and returns a replacement Observable (`of([])` for a read that can
  degrade gracefully, `throwError(() => error)` for a command whose
  failure the user needs to see — see `order.service.ts`'s comment on why
  those two services handle errors differently).
- **`switchMap`** — `OrderService.watchOrder` (route param -> latest order,
  cancelling stale in-flight requests) and
  `ProductListComponent`'s debounced search pipeline. The single most
  interview-relevant "flattening" operator to be able to explain against
  `mergeMap`/`concatMap`/`exhaustMap` — see INTERVIEW.md.
- **`debounceTime` / `distinctUntilChanged`** — the search box, the
  textbook "don't hit the API on every keystroke" pattern.

### When to use / when not to use
- Use RxJS for anything asynchronous that isn't a one-shot "fire and
  forget" — HTTP calls with cancellation/retry needs, WebSocket/SSE
  streams, debounced user input, combining multiple async sources.
- Don't reach for RxJS for local, synchronous UI state that changes only
  in direct response to user actions within the same component tree — see
  Section 4 (Signals) for exactly that case, and why this module uses
  Signals, not a `BehaviorSubject`, for the cart and auth state.
- Avoid deeply nested operator chains (`switchMap` inside `mergeMap`
  inside `catchError` inside...) without extracting named, testable
  functions — RxJS's expressiveness is a double-edged sword; a pipeline
  that takes longer to read than the imperative equivalent has stopped
  paying for itself.

### Trade-offs & performance implications
- Every `.pipe(...)` allocates intermediate Observable wrapper objects —
  irrelevant for HTTP-call frequencies, but worth knowing it isn't free at
  extreme scale (thousands of emissions/second, e.g. raw mouse-move
  streams) without operators like `throttleTime`/`auditTime` to shed load.
- A subscribed Observable that's never unsubscribed is a memory leak
  (the classic Angular interview trip-up: subscribing in `ngOnInit`
  without unsubscribing in `ngOnDestroy`). This module sidesteps that
  entirely by using `toSignal()` (Section 5) everywhere a component needs
  an Observable's latest value — `toSignal` manages its own subscription
  lifecycle tied to the component's injection context and unsubscribes
  automatically, which is now the RECOMMENDED default over manual
  `subscribe()` + `takeUntilDestroyed()` bookkeeping for this exact reason.

### Enterprise examples
- A typeahead/autocomplete search box (this module's product search) is
  the textbook `debounceTime` + `distinctUntilChanged` + `switchMap`
  combination, used near-identically at almost every company with a
  search feature.
- Real-time order-status updates over a WebSocket (a natural extension of
  this module's `Order` domain) modeled as an `Observable<Order>` that a
  component subscribes to for the lifetime of viewing an order.

### Common mistakes
- Manually `subscribe()`-ing inside a component and forgetting to
  unsubscribe — leaks memory and can cause "why did this callback fire
  after I navigated away" bugs.
- Nesting a `subscribe()` inside another `subscribe()` ("subscribe hell,"
  the RxJS analogue of callback hell) instead of using a flattening
  operator (`switchMap`/`mergeMap`/`concatMap`) to compose the two async
  operations into one pipeline.
- Using `mergeMap` where `switchMap` was intended (or vice versa) — see
  `OrderService.watchOrder`'s comment and INTERVIEW.md for the concrete
  race condition this causes.

---

## 4. New Control-Flow Syntax (`@if`/`@for`) vs. `*ngIf`/`*ngFor`

### What it is
Angular 17 introduced built-in control-flow blocks (`@if`/`@else`,
`@for`/`@empty`, `@switch`) as first-class template syntax, replacing the
structural directives `*ngIf`/`*ngFor`/`*ngSwitch`. Every template in this
module (`product-list`, `order-form`, `order-detail`, `app.component`)
uses the new syntax exclusively.

### Why this is now preferred
1. **No import required.** `*ngIf`/`*ngFor` are DIRECTIVES — using them in
   a standalone component's template requires importing `NgIf`/`NgFor` (or
   the whole `CommonModule`) into that component's `imports: [...]` array.
   `@if`/`@for` are parsed directly by the template compiler; they need no
   import at all, shrinking every component's dependency list (see Section
   1's related point about `DecimalPipe` still needing an explicit import
   — PIPES still require imports; only the control-flow directives were
   subsumed into the syntax itself).
2. **Better type-checking.** Inside an `@if (currentOrder(); as order)`
   block, Angular's template type-checker (`strictTemplates: true`, set in
   this module's `tsconfig.json`) narrows `order` to the non-null `Order`
   type for the rest of that block — the same narrowing TypeScript gives
   you for `if (x) { ... }` in a `.ts` file. The old `*ngIf="x as y"`
   microsyntax could not express this as reliably, especially with `async`
   pipes and complex expressions.
3. **Mandatory, explicit tracking for `@for`.** `*ngFor` accepted an
   OPTIONAL `trackBy` function; leaving it off silently defaulted to
   tracking by object identity, meaning a re-fetched array of otherwise-
   identical items (e.g. products re-fetched after a search) caused Angular
   to destroy and recreate every DOM node/component for every item, even
   ones that hadn't logically changed. `@for` REQUIRES a `track` expression
   (`@for (product of products(); track product.sku)`, used throughout
   this module) — the compiler won't let you forget it. This is a
   meaningful performance difference for lists with many items or
   frequent re-renders (any input-focused element inside a row, e.g. an
   editable quantity field, keeps its focus/state across a re-render only
   if tracking correctly maps old items to new items — see
   `product-list.component.ts`'s comment on tracking by `sku` vs. index).

### When to use / when not to use
- Use `@if`/`@for` in every new template — there is no scenario in an
  Angular 17+ codebase where `*ngIf`/`*ngFor` would be preferred going
  forward; they remain supported for backward compatibility, not as an
  equally-good alternative.
- The one thing `@for` cannot skip is choosing a GOOD track expression —
  track by a stable, unique business key (`product.sku`, `line.product.sku`
  in this module), never by array index for a list that can reorder,
  filter, or have items inserted/removed in the middle.

### Trade-offs & performance implications
- Migrating a large existing codebase from `*ngIf`/`*ngFor` to `@if`/`@for`
  is mechanical (Angular ships `ng generate @angular/core:control-flow` to
  automate it) but still a real, non-zero migration cost for a mature app
  with thousands of templates — not "free," even though the new syntax
  itself has no runtime downside.

### Enterprise examples
- Any Angular 17+ codebase's templates, by default (CLI-generated
  components use the new syntax automatically); teams migrating an
  Angular 12-16 codebase forward typically run the control-flow migration
  schematic as a dedicated, isolated PR precisely because it touches
  nearly every template file with a mechanical, low-risk change.

### Common mistakes
- Forgetting `track` is mandatory syntax in `@for` (not optional the way
  `trackBy` was) — this is a template compile error, not a silent runtime
  behavior difference, so it's actually hard to miss; the mistake is more
  often choosing a POOR track expression (index) out of habit from
  `*ngFor` days.
- Assuming `@if`/`@for` eliminated the need for ANY imports in a template —
  pipes (`DecimalPipe`, `AsyncPipe`, etc.) and any custom
  components/directives you reference still need to be listed in
  `imports: [...]`. Only the control-flow directives themselves were
  absorbed into the compiler.

---

## 5. Signals vs. RxJS — and how they interoperate

### What it is
A **Signal** (`signal()`, `computed()`, Angular 16+) is a reactive
primitive holding a single, synchronously-readable value. Reading a signal
inside a component's template automatically registers that template as a
"consumer" — when the signal's value changes, Angular knows precisely
which components/templates to re-check, without needing zone.js to say
"something async happened somewhere, recheck everything" (see Section 8).

This module has two clear examples of each, side by side, specifically to
make the contrast concrete:
- **Signals:** `CartStateService` (cart line items, `computed()` totals),
  `AuthService` (auth state, role) — both local, synchronous, in-memory UI
  state.
- **RxJS:** `ProductService`/`OrderService` — every method returns an
  `Observable<T>` because it wraps an asynchronous HTTP call.

### When to reach for which
| | Signals | RxJS |
|---|---|---|
| **Shape of the data** | A single current value, read synchronously | A stream of values over time, often async |
| **Typical source** | Local component/service state, user interactions | HTTP responses, WebSocket messages, timers, debounced input |
| **Composition** | `computed()` derives new signals from others, memoized | Operators (`map`, `switchMap`, `debounceTime`, ...) transform/combine streams |
| **Cancellation** | Not applicable — signals don't represent in-flight work | First-class (`unsubscribe`, `switchMap` auto-cancels stale work) |
| **Template usage** | Read directly: `{{ mySignal() }}` | Historically needed `| async`; now often bridged via `toSignal()` |
| **This module's examples** | `CartStateService`, `AuthService` | `ProductService`, `OrderService` |

### Interop: `toSignal()` and `toObservable()`
Both live in `@angular/core/rxjs-interop`:
- **`toSignal(observable$, { initialValue })`** — subscribes to an
  Observable and exposes its latest emission as a Signal, managing the
  subscription's lifecycle automatically (tied to the current injection
  context; unsubscribes when the component/service is destroyed — no
  manual `OnDestroy` needed). Used throughout this module
  (`product-list.component.ts`'s `products`, `order-form.component.ts`'s
  `availableProducts`, `order-detail.component.ts`'s `liveOrder`)
  specifically so components can read HTTP data with the same `signal()`
  ergonomics as local state, with no `| async` pipe in the template.
- **`toObservable(signal)`** — the reverse: converts a Signal's value
  changes into an Observable, so it can be fed into RxJS operators that
  only make sense for a stream. `product-list.component.ts`'s search box
  is the concrete example: the raw search-term Signal is converted to an
  Observable via `toObservable`, debounced (`debounceTime`,
  `distinctUntilChanged`), switched into an HTTP call (`switchMap`), and
  the RESULT is converted back into a Signal via `toSignal` — Signal ->
  Observable -> RxJS operators -> Signal, in one pipeline.

### Trade-offs & performance implications
- Signals enable Angular's fine-grained reactivity: a component reading
  only `cart.itemCount` (not the full `cart.items()` array) re-renders
  only when the COUNT changes, not on every cart mutation that happens to
  leave the count unchanged (e.g. `updateQuantity` on one item while
  another item's count contribution offsets it — an edge case, but
  illustrates that `computed()` recomputation is driven by its own
  dependency tracking, not by "the cart changed" broadly).
- `toSignal` without an `initialValue` starts as `undefined` until the
  first emission — every `toSignal` call in this module explicitly passes
  `initialValue` (or accepts `undefined` deliberately, as
  `order-detail.component.ts` does, handled by the template's `@else`
  branch) specifically to make that transient state explicit rather than
  an implicit surprise.
- Overusing `computed()` for expensive derivations recalculated on every
  dependency change can matter at scale the same way an expensive
  getter would — `computed()`'s memoization only skips recomputation when
  NEITHER of its read dependencies changed, not when the underlying
  computation is otherwise "still expensive but unavoidable."

### Enterprise examples
- Shopping cart / order-builder state (this module's `CartStateService`)
  is one of the most common realistic Signals use cases across e-commerce
  and fintech frontends — local, synchronous, directly manipulated by user
  actions.
- A live "connected users" or "unread notification count" badge in an
  admin console is a common `toSignal(websocket$)` pattern — RxJS handles
  the async WebSocket stream and reconnect logic; the badge component just
  reads a Signal.

### Common mistakes
- Reaching for a `BehaviorSubject` + manual `.next()` calls for state that
  is purely local and synchronous (this module's cart is a good example of
  where that used to be the default pattern pre-Signals) — more
  boilerplate (subscribe/unsubscribe, `| async` pipes everywhere) for no
  benefit over a `signal()`.
- Calling `.set()` on a signal holding an array/object by mutating the
  existing reference first (`arr.push(x); mySignal.set(arr)`) — see
  `CartStateService.addItem()`'s comment: signals compare by reference
  (`===`) by default, so mutating in place and setting the SAME reference
  can cause Angular to (correctly, per its equality check) skip notifying
  consumers, since as far as it can tell nothing changed.

---

## 6. Reactive Forms vs. Template-Driven Forms

### What it is
`order-form.component.ts` uses **reactive forms**
(`ReactiveFormsModule`, `FormBuilder`, `FormGroup`, `FormArray`,
`Validators`) to build a dynamic list of order lines with a custom
validator. Angular's other form style, **template-driven forms**
(`FormsModule`, `[(ngModel)]`), is not used anywhere in this module — this
section explains why, and when template-driven would have been the better
call instead.

### The trade-off, concretely
| | Reactive Forms | Template-Driven Forms |
|---|---|---|
| **Form model lives in** | TypeScript (`FormGroup`/`FormArray` built explicitly) | The template (`ngModel` directives infer the model) |
| **Dynamic structure (add/remove fields)** | Natural — `FormArray.push()`/`.removeAt()` | Awkward — no first-class dynamic-array primitive |
| **Custom validators** | Plain, independently unit-testable functions (`ValidatorFn`) — see `positive-quantity.validator.ts` | Typically directives applied in-template; harder to unit test in isolation |
| **Synchronous access to the whole value** | `form.value` / `form.getRawValue()`, available immediately | Requires a template reference variable (`#form="ngForm"`) passed into the component |
| **Boilerplate for a trivial 1-field form** | Overkill — importing `ReactiveFormsModule` for one search box (see `product-list.component.ts`'s plain-signal search box, which deliberately uses NEITHER forms API) | Minimal — `[(ngModel)]` and go |
| **Testability** | Higher — form logic testable without rendering a template | Lower — form state is more entangled with the DOM |

This module's order form needs a dynamically-sized list of product/quantity
pairs and a business-rule validator matching `OrderLine`'s Java-side
invariant — exactly the case reactive forms are built for. The product
search box, by contrast, is ONE input with no validation need — that uses
a plain `signal()` + `(input)` event handler, not either forms API, because
pulling in a whole forms module for one uncontrolled input would be its
own kind of over-engineering.

### The custom validator
`positive-quantity.validator.ts` enforces `quantity > 0` and integer,
mirroring `OrderLine`'s compact canonical constructor in java-basics
(`if (quantity <= 0) { throw new IllegalArgumentException(...); }`
exactly). See that file's comments for why this is UX-only — the backend's
`OrderLine` constructor remains the actual enforcement point, since any
client-side check can be bypassed.

### When to use / when not to use
- Reactive forms: any form with conditional fields, dynamic
  arrays/repeating groups, cross-field validation, or validation logic
  worth unit testing independently of a rendered template.
- Template-driven forms: a handful of simple, static fields with no
  dynamic structure, where the lower ceremony matters more than the
  above — e.g. a two-field "contact us" form.

### Trade-offs & performance implications
- Reactive forms' `valueChanges`/`statusChanges` are Observables — every
  keystroke can trigger a stream emission; combine with `debounceTime` for
  expensive downstream work (e.g. an async cross-field validator hitting
  the backend) the same way `product-list.component.ts`'s search box
  debounces HTTP calls.
- `FormArray`/`FormGroup` validation re-runs the WHOLE group's validators
  on any control's value change by default — for a very large dynamic form
  (hundreds of rows), this can become a measurable cost; `updateOn:
  'blur'` or `'submit'` (a per-control/group option) trades instant
  feedback for fewer validation passes.

### Enterprise examples
- A multi-line invoice/order-builder form (exactly this module's
  `order-form.component.ts`) is one of the most common realistic reactive
  forms use cases — repeating line items, per-line validation, dynamic
  add/remove.

### Common mistakes
- Reaching for reactive forms out of habit for a trivial single-field
  form, adding `ReactiveFormsModule` import ceremony for no real benefit.
- Forgetting a custom `ValidatorFn` must return `null` for a VALID value —
  returning `undefined` or `false` doesn't register as "no error" the way
  `null` specifically does, per Angular's `ValidationErrors | null`
  contract.
- Validating `quantity <= 0` without also handling `null`/empty-string
  input (letting `Validators.required` own that case, as
  `positive-quantity.validator.ts` does) — otherwise an empty field shows
  a confusing "must be positive" error before the user has typed anything.

---

## 7. Dependency Injection

### What it is
Every service in this module (`ProductService`, `OrderService`,
`CartStateService`, `AuthService`) is `@Injectable({ providedIn: 'root' })`
— registered once, as an app-wide singleton, resolved automatically the
first time anything injects it (tree-shakable: if nothing ever injects
`AuthService`, it's never included in the production bundle at all).
Components/services obtain their dependencies via `inject()` (function-
based DI, Angular 14+) rather than constructor parameters.

### Why introduced / problem it solves
Dependency injection decouples "what a class needs" from "how that
dependency is constructed" — `ProductService` doesn't construct its own
`HttpClient`; it declares that it needs one, and Angular's injector
supplies the right instance. This is what makes services trivially mockable
in tests (substitute a fake `HttpClient`/`ProductService` at the injector
level, without changing the class under test) and enables singleton
sharing (every component in this app shares the SAME `CartStateService`
instance, which is exactly why items added on `/products` are still there
when you reach `/orders/new` — see `diagrams/component-routing-tree.md`).

### `inject()` vs. constructor injection
```ts
// Constructor injection (still fully supported, the original pattern)
constructor(private http: HttpClient) {}

// Function-based injection (Angular 14+, used throughout this module)
private readonly http = inject(HttpClient);
```
Both resolve `HttpClient` from the same injector and behave identically at
runtime. `inject()` is used throughout this module because: (1) it works
in more places — inside a field initializer, inside a plain function like
`authGuard`/`authInterceptor` that isn't a class at all, which constructor
injection cannot do; (2) it reads slightly better when a service has one
or two dependencies used to build field initializers directly, as
`ProductService.baseUrl`-adjacent fields do here. Constructor injection
remains arguably more DISCOVERABLE at a glance (all dependencies listed in
one place, the constructor signature) for a class with many dependencies —
a judgment call, not a strict rule.

### When to use / when not to use
- `providedIn: 'root'` (used for every service here): the default choice
  for app-wide singletons — a shared cart, a shared auth session, stateless
  HTTP-wrapping services.
- Component-level providers (`@Component({ providers: [...] })`, not used
  in this module): appropriate when a service's state should be scoped to
  ONE component instance and its children, and destroyed when that
  component is destroyed (e.g. a wizard's multi-step form state that
  shouldn't leak between two simultaneously-open wizard instances) — not a
  need this module's domain has.

### Trade-offs & performance implications
- A `providedIn: 'root'` singleton lives for the lifetime of the whole
  application — for state that should genuinely reset per-feature-visit
  (not this module's cart, which SHOULD persist across navigation), a
  component-scoped provider avoids stale state lingering after a user
  navigates away and back.
- Angular's injector resolution has a small but real runtime cost per
  injection, paid once per service instantiation (not per method call) —
  irrelevant at the scale of a typical app's service count.

### Enterprise examples
- A shared `NotificationService`/`ToastService` (`providedIn: 'root'`)
  used app-wide so any component can trigger a toast without prop-drilling
  a callback through every intermediate component — the same singleton
  pattern this module's `CartStateService` demonstrates.

### Common mistakes
- Providing the SAME service both at `providedIn: 'root'` AND again in a
  component's `providers: [...]` array without understanding that the
  component-level provider creates a SEPARATE instance scoped to that
  component subtree — a common source of "why does my service have two
  different states in different parts of the app" bugs.
- Injecting `HttpClient` (or any service) directly into a component and
  calling `.get()`/`.post()` there instead of going through a dedicated
  service (`ProductService`/`OrderService`) — couples the component to the
  API shape directly and makes the HTTP logic un-reusable and harder to
  mock in component tests.

---

## 8. Change Detection: Default vs. `OnPush`

### What it is
Angular's change detection walks the component tree checking every
template expression for changes since the last check, re-rendering any
DOM that changed. **`ChangeDetectionStrategy.Default`** checks a
component EVERY TIME change detection runs anywhere in the app — which,
historically, is triggered by zone.js patching async APIs (`setTimeout`,
`Promise.then`, DOM event listeners, `XMLHttpRequest`/`fetch`) so Angular
knows "some async work just completed, a re-render might be needed" and
runs a full tree check. **`ChangeDetectionStrategy.OnPush`** (applied to
EVERY component in this module — see the file-level comments in
`product-list.component.ts`, `order-form.component.ts`,
`order-detail.component.ts`, `app.component.ts`) tells Angular to skip
checking a component UNLESS one of a small set of specific triggers fires:
an `@Input()` reference changed (compared with `===`), an event originated
from within that component's own template, or — the mechanism this module
leans on throughout — a Signal the component's template reads was updated.

### Why OnPush, applied everywhere in this module
None of this module's components read mutable object state via
`@Input()` bindings that get mutated in place; every piece of dynamic data
they render flows through a Signal (`CartStateService`, `AuthService`,
`toSignal`-wrapped HTTP calls) or a reactive form's own change-tracking.
Signal reads inside an OnPush component's template register that
component precisely with the signal's reactive graph — Angular can then
schedule EXACTLY the components that need re-checking when a signal
changes, rather than the whole tree. This is strictly more efficient than
`Default` for exactly the same visible behavior, with no correctness
trade-off, PROVIDED the discipline below is followed.

### The concrete discipline `OnPush` requires: immutable updates
Consider `CartStateService.addItem()`. The WRONG way, which would silently
break under `OnPush`:

```ts
// WRONG under OnPush: mutates the existing array in place, then "sets" the
// same reference back onto the signal.
addItem(product: Product, quantity = 1): void {
  const current = this.lines();          // read the live array
  current.push({ product, quantity });   // MUTATE it in place
  this.lines.set(current);               // "set" — but it's the SAME reference
}
```
Angular's (and Signals') default equality check is `===` (reference
equality). Setting a signal to the SAME reference it already held is
indistinguishable, to that check, from "nothing changed" — no consumer is
notified, and any OnPush component reading `cart.items()` simply never
re-renders, even though the underlying data did in fact change. This bug
is particularly nasty because it often "resolves itself" the moment ANY
unrelated re-render happens to sweep through that component for some other
reason — making it intermittent and confusing to reproduce.

The CORRECT version, actually used in `cart-state.service.ts`:
```ts
// RIGHT: build a NEW array (and, if an existing line changed, a new line
// object) and .update() to it — a genuinely different reference every time
// something logically changed.
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
The same discipline applies to plain `@Input()`-bound data under `OnPush`
even without Signals at all: a parent component must replace an array/object
reference (`this.products = [...this.products, newProduct]`), never mutate
it in place (`this.products.push(newProduct)`), for a child `OnPush`
component bound to `[products]="products"` to notice the change.

### When to use / when not to use
- Use `OnPush` as the default for new components (this module's
  `angular.json` schematic default sets `"changeDetection": "OnPush"` for
  every `ng generate component`, matching this discipline project-wide) —
  it costs nothing when followed correctly and meaningfully reduces
  unnecessary change-detection work in a large tree.
- `Default` remains appropriate for a component that genuinely needs to
  react to mutations of objects it doesn't control the replacement of
  (rare, and often itself a sign the data flow should be restructured) or
  during incremental migration of a large legacy tree where auditing every
  component for the immutability discipline isn't feasible all at once.

### Trade-offs & performance implications
- The perceived complexity cost of `OnPush` (needing to think about
  reference equality) is largely eliminated once state is Signal-based —
  Signals enforce roughly the same discipline `OnPush` needs anyway
  (`computed()`/`update()` naturally encourage building new values), so
  adopting Signals broadly and adopting `OnPush` broadly reinforce each
  other rather than being two separate disciplines to maintain.
- Getting this wrong doesn't crash anything — it silently produces STALE
  UI, which is often harder to notice and debug than a hard error. Always
  suspect an `OnPush` reference-equality bug first when a component
  "sometimes doesn't update" without any console error.

### Enterprise examples
- Any Angular application with a large component tree and performance
  sensitivity (dashboards with dozens of live-updating widgets, trading UIs
  refreshing on every tick) adopts `OnPush` broadly specifically to bound
  change-detection cost to "components that actually changed," not "every
  component, every tick."

### Common mistakes
- Applying `OnPush` to a component, then mutating an `@Input()`-bound
  array/object in place from the PARENT and wondering why the child never
  updates (the exact bug illustrated above).
- Assuming `OnPush` means "never checked automatically" — it's still
  checked on its own template-originated events, on `@Input()` reference
  changes, and on signal changes; it's not `ChangeDetectorRef.detach()`
  (full manual control), just a narrower set of automatic triggers.

### Forward-looking: zoneless Angular
Angular has an experimental zoneless change-detection mode
(`provideExperimentalZonelessChangeDetection()`) that removes zone.js
entirely — instead of "some async API completed, recheck the tree,"
change detection is scheduled ONLY by explicit signals: a Signal changing,
an `@Input()` set via the Signal-based input API, `markForCheck()`, or an
`async` pipe emission. This module does not enable it (still experimental
as of Angular 17/18, and `app.config.ts` uses the standard
`provideZoneChangeDetection({ eventCoalescing: true })` instead), but it's
directly relevant to everything above: an app built with Signals and
`OnPush` EVERYWHERE, exactly as this module is, is already behaving almost
exactly like a zoneless app would — the eventual migration to zoneless
for a codebase built this way is expected to be comparatively low-risk,
which is precisely why the Signals + `OnPush` discipline is worth adopting
now rather than treating zoneless as a someday-unrelated concern.

---

## What's deliberately out of scope for this module

- **A real login page/flow.** `AuthService` mocks a JWT locally (see its
  header comment) because the real issuer (`security/`, Module 6) doesn't
  exist yet in this curriculum's build order. `AppComponent`'s toolbar
  buttons ("log in as Customer/Manager") exercise the same
  guard/interceptor/RBAC mechanics without needing a real backend.
- **Automated tests** (`*.spec.ts`, Jasmine/Karma or Jest) — Module 10's
  scope, not Module 9's. Every piece of logic here (validators, services,
  the guard, the interceptor) was written to be easily unit-testable
  (small, DI-friendly, few side effects) specifically so that module can
  add coverage without needing to refactor this one first.
- **Styling/design system** — `styles.css` is intentionally minimal;
  this module teaches application architecture, not CSS.
- **Reconciling against `spring/`'s actual contract** — see EXERCISES.md
  for this as a hands-on exercise once that module lands.
