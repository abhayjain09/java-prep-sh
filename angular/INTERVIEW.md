# Module 9 — Interview Questions

Organized by topic, then by level (beginner → intermediate → senior →
scenario), matching java-basics' `INTERVIEW.md` format. Frontend rounds at
fintech-adjacent companies (S&P Global, JPMorgan, Goldman Sachs) tend to
probe change detection and RxJS correctness harder than pure syntax
trivia; product-focused companies (Amazon, Google, Microsoft, Adobe,
Salesforce, Atlassian) more often probe component architecture,
performance budgets, and state-management trade-offs at scale. Both angles
are covered below.

---

## Components & Standalone APIs

**Beginner:** "What changed with standalone components, and why does it
matter?"
*Ideal answer:* A standalone component declares its own template
dependencies (`imports: [...]` on the `@Component` decorator) instead of
requiring an enclosing `NgModule`'s `declarations`/`imports` to supply
them. It removes an entire category of `NG0304`-style "not a known
element" errors caused by module-wiring mistakes, and enables far more
granular lazy-loading (`loadComponent` for one component, vs.
`loadChildren` pulling in an entire module).
*Follow-up:* "Is `standalone: true` still needed in Angular 17+?" → No —
it's the default; this module states it explicitly anyway for teaching
clarity.

**Intermediate:** "This module's `ProductListComponent` imports
`DecimalPipe` explicitly even though it also uses `@if`/`@for`, which need
no import. Why the difference?"
*Ideal answer:* `@if`/`@for` are built into the template compiler as of
Angular 17 — no import needed at all. Pipes (`DecimalPipe`, `AsyncPipe`,
custom pipes) are NOT part of that syntax change; they remain ordinary
importable template dependencies, same as any other standalone
component/directive. Only the control-flow DIRECTIVES were subsumed into
built-in syntax.
*Follow-up:* "What's the migration story for a large `*ngIf`/`*ngFor`
codebase?" → `ng generate @angular/core:control-flow`, a schematic that
mechanically rewrites templates — still a real, reviewable PR-sized change
across a large codebase, not literally zero-cost.

**Senior:** "How would you decide which routes/components to eagerly bundle
vs. lazy-load in a large Angular app, and what would you actually measure?"
*Ideal answer:* Start from real navigation analytics (which routes does
the median session actually hit, and in what order) rather than guessing.
Eagerly bundle the shell/root and whatever the FIRST screen needs;
lazy-load everything else, and consider a preloading strategy
(`PreloadAllModules` or custom, e.g. preload only routes reachable from
the current one) to hide the lazy-chunk fetch latency during idle time
after the initial paint. Measure with actual bundle-analyzer output
(`ng build --stats-json` + `webpack-bundle-analyzer`) and real
Time-to-Interactive/Largest-Contentful-Paint metrics, not intuition about
what "feels big."
*Follow-up:* "This module lazy-loads even its landing route
(`/products`). Good idea in production?" → Debatable — it adds one
network round trip before the very first meaningful paint. A reasonable
production call is to eagerly bundle the landing route specifically while
lazy-loading everything reached FROM it, trading a slightly larger initial
bundle for one fewer round trip on the page virtually every session hits.

**Scenario:** "A teammate wants to put ALL of `products`, `order-form`,
and `order-detail`'s imports into one shared `imports: [...]` array
exported from a `shared.ts` file, imported wholesale into every feature
component 'to reduce repetition.' What do you say in review?"
*Ideal answer:* Point out this recreates the exact over-coupling
standalone components were introduced to avoid — a "kitchen sink" shared
import list means every component pulls in dependencies it doesn't
actually use in its template, working against tree-shaking and making it
unclear, by reading one component's decorator, what it genuinely needs.
Recommend each component import exactly what its own template uses (as
this module does), and if there's genuine duplication (e.g. three
components all needing `DecimalPipe` + `RouterLink`), a named, narrow
constant array (`export const MONEY_DISPLAY_IMPORTS = [DecimalPipe] as
const`) is a reasonable middle ground — still explicit, still narrow.

---

## Routing & Lazy Loading

**Beginner:** "What does `loadComponent` do, and how is it different from
a normal `import` at the top of `app.routes.ts`?"
*Ideal answer:* `loadComponent: () => import('./x.component').then(m =>
m.XComponent)` is a DYNAMIC import — the bundler splits that component
into its own chunk, fetched over the network only when the route is
actually navigated to. A static top-of-file `import` bundles the
component into the SAME chunk as everything else that file is part of,
shipped on every page load regardless of whether that route is ever
visited.

**Intermediate:** "Where does `authGuard` run relative to the lazy chunk
being fetched, and why does the order matter?"
*Ideal answer:* The guard runs BEFORE the lazy chunk is fetched — see
`diagrams/interceptor-guard-sequence.md`. This means an unauthenticated
user attempting `/orders/new` never downloads that route's JavaScript at
all; the guard redirects before the dynamic `import()` is even triggered.
It's both a UX benefit (no flash of a screen you can't use) and a genuine
(small) performance benefit (no wasted network fetch).
*Follow-up:* "Does the same guard protect a direct API call to
`POST /api/v1/orders`?" → No — the guard only gates client-side
navigation; it has no effect on someone calling the API directly with
curl/Postman. Real enforcement is server-side (Module 6).

**Senior:** "Design the routing structure for this Order/Inventory app if
it grew to include an admin inventory-management section only MANAGERs
should reach, with its own nested routes (stock adjustment, product
CRUD)."
*Ideal answer:* A parent route (`admin`) with `canActivate: [authGuard,
managerRoleGuard]` (compose multiple guards — ALL must return true/pass)
and `loadChildren` (or nested `children: [...]` with each child itself
`loadComponent`) for the admin sub-routes, keeping the entire admin
section — including its route DEFINITIONS, not just its components — out
of the main bundle for non-manager users. Discuss `CanMatch` as an
alternative/complementary guard type: `canActivate` still resolves the
route match before blocking, while `canMatch` can prevent a route from
matching AT ALL (useful for showing/hiding a whole route tree based on
role rather than just blocking access to an otherwise-visible one).

**Scenario:** "A user reports that clicking 'Cancel' on an order sometimes
navigates them to `/products` with a confusing `authRequired` query
param, even though they're pretty sure they were logged in. How do you
debug this?"
*Ideal answer:* Walk the guard/interceptor sequence: `authRequired` in the
query string is `authGuard`'s redirect signature (see
`auth.guard.ts`), meaning `AuthService.isAuthenticated()` returned false
at that exact navigation. Given this module's mock, localStorage-backed
auth, plausible causes: the browser is in a mode that clears storage
between sessions/tabs (private browsing), `logout()` was called elsewhere
(e.g. the interceptor's 401 handler firing from an unrelated failed
request just before this navigation), or a race between two tabs sharing
the same `localStorage` key. In a REAL JWT-backed app, the more likely
cause is silent token expiry with no refresh-token flow wired up yet —
which is exactly the "production note" `authInterceptor`'s 401 handler
comment flags as a deliberately deferred concern.

---

## RxJS

**Beginner:** "Why does `HttpClient.get()` return an `Observable`, not a
`Promise`, and what does that give you that a `Promise` doesn't?"
*Ideal answer:* Observables are cancellable (unsubscribe stops the
request/ignores its result), composable with operators (retry, debounce,
combine with other streams), and can represent zero-to-many emissions,
not just one resolved value. A `Promise` is already "running" the moment
it's created and offers none of that natively.

**Intermediate:** "Explain `switchMap` vs `mergeMap` vs `concatMap` with
this module's `OrderService.watchOrder` as the example."
*Ideal answer:* All three "flatten" a higher-order Observable (a stream of
ids) into inner Observables (each id's HTTP call), but differ in how they
handle overlapping inner Observables. `switchMap` (what's used) cancels
the PREVIOUS inner Observable the moment a new outer value arrives —
correct here because only the LATEST route id's data should ever be
shown; a stale in-flight request for an abandoned id must never win.
`mergeMap` would let all inner Observables run concurrently and emit
whenever each resolves — dangerous here because a slow response for an
old id could resolve AFTER a fast response for the current id and
overwrite it with stale data. `concatMap` queues inner Observables to run
strictly one-at-a-time, in order — wrong here too (it wouldn't cancel a
stale request, just delay showing new data until the old request
finishes).
*Follow-up:* "When would you actually want `mergeMap` over `switchMap`?" →
When you want ALL triggered requests to complete and their results to all
matter — e.g. firing off several independent "mark as read" calls for
multiple notifications a user multi-selects and dismisses at once; none
should cancel another.

**Senior:** "This module's `ProductService`/`OrderService` handle HTTP
errors differently — one swallows to an empty array, the other re-throws.
Defend or critique that design."
*Ideal answer:* The asymmetry is intentional and defensible: `getProducts`
backs a passive, READ-ONLY display — degrading to "no products shown" on
a transient network blip is a reasonable, low-stakes failure mode, and
arguably better than a jarring error screen for a catalog browse. Order
placement/cancellation are COMMANDS with real consequences the user is
actively waiting on — silently showing nothing (or worse, appearing to
succeed) after a failed `placeOrder` could mean a user believes an order
went through when it didn't. The critique worth raising: `getProducts`
swallowing to `[]` gives the user zero signal that something went wrong at
all (vs. "no products match your search," which looks identical) — a
production version should probably distinguish "genuinely empty result"
from "failed to load" via a separate error-state signal, not conflate both
into an empty array.

**Scenario:** "Your team's `search$` pipeline
(`debounceTime`/`distinctUntilChanged`/`switchMap`) works great in dev but
QA reports that on a slow connection, rapidly typing and then deleting a
search term sometimes flashes old, irrelevant results for a split second
before showing the correct empty state. Where do you look?"
*Ideal answer:* Since `switchMap` already cancels stale in-flight
requests, the flash is more likely `distinctUntilChanged` allowing a value
through that LOOKS different from the immediately-prior emitted value but
matches an EARLIER already-in-flight request's term (e.g. typing "abc",
deleting to "a", retyping "abc" again quickly — three distinct debounced
emissions, all valid, and the middle one's response could still resolve
after the third if the backend itself has variable latency per query,
though `switchMap` should prevent that specific case). More likely root
cause to check first: is `searchProducts` actually being called through
this pipeline every time, or is there a code path (e.g. a direct call
bypassing `filteredProducts$`) racing it independently outside the
`switchMap` boundary. The debugging discipline matters more than the exact
answer here — verify every request to the backend actually flows through
the ONE `switchMap`-guarded pipeline before suspecting the operators
themselves.

---

## Signals

**Beginner:** "What's the difference between `signal()` and `computed()`?"
*Ideal answer:* `signal()` creates a writable, independent piece of state
(`.set()`/`.update()`). `computed()` creates a READ-ONLY, DERIVED signal —
its value is calculated from other signals' current values, memoized, and
automatically recalculated only when one of the signals it read last time
actually changes. `CartStateService.total` is a `computed()` derived from
the `lines` signal; you cannot `.set()` a `computed()` directly.

**Intermediate:** "Why does `CartStateService` use Signals while
`ProductService` uses RxJS Observables, in the same codebase?"
*Ideal answer:* The cart is local, synchronous, in-memory UI state,
mutated only by direct method calls from user actions within the app — no
asynchrony involved at all. HTTP responses are inherently asynchronous,
one-shot, and benefit from RxJS's cancellation/composition operators.
Signals aren't a "replacement" for RxJS; they solve a different, narrower
problem (synchronous local state with fine-grained reactivity) and
interoperate with RxJS via `toSignal`/`toObservable` rather than competing
with it — see `product-list.component.ts`'s search pipeline for both used
together in one field.

**Senior:** "Explain exactly how a Signal update reaches the DOM without
zone.js, and why that matters for the zoneless-Angular direction the
framework is heading."
*Ideal answer:* When a component's template reads a signal (`{{
mySignal() }}` or a Signal called inside a bound expression), Angular's
reactivity system registers that template as a "consumer" of that signal
during change detection. When the signal's value changes
(`.set()`/`.update()`), Angular schedules exactly the components that
consumed it (and any OnPush ancestors on the path to the root, so the
DOM update can actually be applied) for a re-check — WITHOUT relying on
zone.js patching `setTimeout`/`Promise.then`/DOM events to trigger a
broad, whole-tree check. This is precisely the mechanism that makes an
app built with Signals + `OnPush` everywhere (as this module is)
"already behave" like a zoneless app today, which is why Angular's
experimental `provideExperimentalZonelessChangeDetection()` mode is a
comparatively low-risk migration for codebases built this way, and a much
riskier one for codebases still relying on `Default` change detection and
implicit zone.js-triggered rechecks everywhere.
*Follow-up:* "What would break if you enabled zoneless mode on a codebase
NOT built this way?" → Any component relying on `Default` change
detection being re-checked "for free" whenever ANY async event fires
anywhere in the app (a common, often accidental pattern — e.g. a component
that mutates a plain class field in a `setTimeout` callback and expects
the template to just update) would stop updating, because nothing would
trigger a recheck without an explicit signal, `markForCheck()`, or
`async`-piped Observable telling Angular to.

**Scenario:** "A component reads `cartStateService.items()` directly in
its template to render a badge showing item count, using
`{{ cart.items().length }}`. It's marked `OnPush`. After a teammate
'fixes a bug' by changing `CartStateService.addItem` to mutate the
existing array with `.push()` instead of spreading into a new array (to
'avoid an allocation'), the badge stops updating. Diagnose it precisely,
referencing this module's actual code."
*Ideal answer:* This is exactly the bug `README.md`'s Change Detection
section walks through with `CartStateService.addItem` as the worked
example. Signals (and `OnPush`) compare by REFERENCE (`===`) by default.
`array.push(x)` mutates the array in place and returns the SAME reference
`this.lines()` already held; calling `.set()` (or the equivalent
`.update()` returning that same, now-mutated reference) with an unchanged
reference is indistinguishable, to the signal's equality check, from "no
change" — no consumer, including the badge's OnPush component, is
notified. The fix is exactly what the real `CartStateService.addItem`
does: build and return a NEW array (`[...current, newLine]`) so the
reference genuinely changes every time the logical content does. The
"avoid an allocation" framing is itself the tell that this teammate
optimized for the wrong thing — the allocation cost of copying a small
cart array is immaterial next to a silently-stale UI bug.

---

## Reactive Forms

**Beginner:** "What's the difference between reactive forms and
template-driven forms?"
*Ideal answer:* Reactive forms build the form's model explicitly in
TypeScript (`FormGroup`/`FormControl`/`FormArray`, via `ReactiveFormsModule`);
template-driven forms infer the model from `[(ngModel)]` directives in the
template (`FormsModule`). Reactive forms make dynamic structure (add/remove
fields), custom validators, and synchronous access to the whole form's
value considerably more natural; template-driven forms have less
boilerplate for genuinely simple, static forms.

**Intermediate:** "Walk through how `positiveQuantityValidator` is wired
up and what `ValidationErrors | null` actually means as a return
contract."
*Ideal answer:* It's a factory function returning a `ValidatorFn`
(`(control) => ValidationErrors | null`), attached to a `FormControl` via
its `validators` array alongside `Validators.required`. Returning `null`
means "this validator finds no problem" — Angular's forms API treats
`null` specifically as the "valid" sentinel; anything else (an object with
at least one key) marks the control invalid and merges into
`control.errors`. The template checks
`control.hasError('positiveQuantity')` to show a validator-specific
message, keyed by the object's property name the validator returned.
*Follow-up:* "Why does the validator return `null` early for
`null`/`undefined`/`''` input instead of treating it as invalid?" → Single
responsibility — `Validators.required` already owns "is this empty," so
this validator only asserts "IF there's a value, is it a positive
integer," avoiding a confusing double error message (or a "must be
positive" error showing on a field the user hasn't touched yet).

**Senior:** "This form seeds its `FormArray` once from
`CartStateService.items()` in `ngOnInit` rather than keeping it live-bound
to the cart signal via, say, an `effect()`. Why might that be the right
call, and when would live-binding be better?"
*Ideal answer:* Once a user starts editing an order (adjusting quantities,
adding/removing lines in the form itself), further cart mutations
happening elsewhere (e.g. another tab, or a "add to cart" click that
somehow still fires while this form is open) shouldn't silently rewrite
what they're mid-editing — that's a data-loss/surprise-behavior risk. A
ONE-TIME seed on init, after which the form is the sole owner of its own
state, avoids that entirely. Live-binding (via an `effect()` reacting to
`cart.items()` and calling `form.patchValue`/rebuilding the `FormArray`)
would be appropriate for a genuinely different UX — e.g. a persistent
"mini-cart" sidebar meant to always reflect the CURRENT cart state exactly,
with no separate "editing" mode of its own.

**Scenario:** "Product wants order lines to support a per-SKU maximum
quantity fetched from the backend (different products have different max
order quantities), not a single hard-coded rule. How would you adapt
`positiveQuantityValidator` and the form to support this, keeping the
validator itself pure and unit-testable?"
*Ideal answer:* Keep `positiveQuantityValidator` itself unaware of
per-SKU data — it should stay a pure, parameterless (or simply-parameterized)
function testable with plain numbers in, `ValidationErrors | null` out.
Add a SECOND validator factory, e.g. `maxQuantityValidator(max: number)`,
applied per-control at the point where the SKU (and therefore its max) is
known — likely re-applied via `setValidators()` whenever the SKU control's
value changes (listen to `sku.valueChanges`, look up that SKU's max from
already-fetched product data, and call
`quantityControl.setValidators([Validators.required,
positiveQuantityValidator(), maxQuantityValidator(max)])` followed by
`updateValueAndValidity()`). This keeps each validator single-purpose and
testable in isolation while composing them dynamically based on runtime
data — the same "small, composable, DI-friendly pieces" principle
README.md's DI section argues for services.

---

## Dependency Injection

**Beginner:** "What does `@Injectable({ providedIn: 'root' })` mean, and
why is it used for every service in this module?"
*Ideal answer:* It registers the service with Angular's root injector as
an app-wide SINGLETON, created lazily the first time anything injects it,
and tree-shaken out of the production bundle entirely if nothing ever
does. Every service here (`ProductService`, `OrderService`,
`CartStateService`, `AuthService`) needs exactly one shared instance
across the whole app — e.g. the SAME `CartStateService` instance must be
visible from both `ProductListComponent` (adding items) and
`OrderFormComponent` (reading them).

**Intermediate:** "Compare `inject()` and constructor injection — is one
actually 'better'?"
*Ideal answer:* Functionally identical — both resolve from the same
injector. `inject()` works in more places (plain functions like
`authGuard`/`authInterceptor` that aren't classes at all, and field
initializers), which is why this module uses it even inside services and
components. Constructor injection remains arguably more discoverable at a
glance for a class with several dependencies (one place lists them all)
and is still what many teams standardize on for classes specifically —
this is a style/consistency choice more than a hard technical
requirement, with the notable EXCEPTION that guards/interceptors as plain
functions have no constructor to inject through at all, making `inject()`
the only option there.

**Senior:** "When would you deliberately NOT use `providedIn: 'root'` for
a service in this app's domain, and provide it at the component level
instead?"
*Ideal answer:* Component-level providers make sense for state that should
be scoped to one component instance's lifetime and destroyed with it — a
multi-step wizard's in-progress form state that must NOT leak between two
simultaneously open instances of the same wizard component (e.g. two
browser tabs, or an app that allows opening a wizard in a modal launched
from multiple places without sharing state). Nothing in THIS module's
domain needs that — the cart and auth state are both deliberately
app-wide and long-lived — but it's a real, common need in larger apps
(e.g. per-tab draft state in a multi-document editor).
*Follow-up:* "What happens if the same service class is provided both at
root AND at a component level?" → Two separate instances exist: the root
singleton, and a second instance scoped to that component subtree, which
shadows the root one for anything injecting inside that subtree — a
frequent source of "why does this service have two different states in
different parts of my app" bugs when done accidentally.

**Scenario:** "A candidate argues dependency injection is 'unnecessary
overhead' for a small app like this one and proposes importing
`ProductService`/`OrderService` as plain singleton module-level exports
(`export const productService = new ProductService()`) instead. How do
you respond?"
*Ideal answer:* A plain module-level singleton loses: (1) testability —
you can't substitute a fake `HttpClient` (or the service itself) per-test
without DI, since the singleton's dependencies are hard-wired at
construction; (2) Angular's own lifecycle integration — `HttpClient`
itself needs to be constructed via injection to pick up configured
interceptors (`provideHttpClient(withInterceptors([...]))`) at all, so a
manually-constructed `new ProductService()` would need its own manually-
constructed `HttpClient` with no interceptors attached, silently breaking
the JWT-attachment flow this whole module demonstrates; (3) tree-shaking —
a module-level `new X()` executes at import time regardless of whether
anything uses it, unlike a DI-registered service, which is only
instantiated on first actual injection. DI's "overhead" here is a handful
of `inject()` calls — a low cost for what it buys back.

---

## Interceptors & Guards

**Beginner:** "What's the difference between a route guard and an HTTP
interceptor?"
*Ideal answer:* A guard (`CanActivateFn`) runs at NAVIGATION time, once
per attempted route change, and can allow/block/redirect that navigation
before the destination component is even constructed. An interceptor
(`HttpInterceptorFn`) runs at HTTP REQUEST time, once per outgoing
`HttpClient` call, regardless of which route triggered it, and can modify
the request/response (e.g. attaching a header) without affecting
navigation at all.

**Intermediate:** "Why is `authInterceptor` written as a plain function
rather than a class implementing `HttpInterceptor`?"
*Ideal answer:* Angular 15+'s functional interceptor API
(`HttpInterceptorFn`) needs no class, no `multi: true`
`HTTP_INTERCEPTORS` provider boilerplate, and registers via
`provideHttpClient(withInterceptors([authInterceptor]))` directly. It uses
`inject()` to reach `AuthService`/`Router` from inside a plain function —
Angular keeps an injection context active while the interceptor chain
runs, so `inject()` resolves correctly even though there's no class
instance here at all. It's also simpler to unit test: call the function
directly with a fake `HttpRequest` and a fake `next` function, no
`TestBed`-based class instantiation required.

**Senior:** "Design a token-refresh flow that plugs into this module's
`authInterceptor` without changing any component or service that calls
`OrderService`/`ProductService`."
*Ideal answer:* Extend the interceptor's `catchError` branch: on a `401`,
instead of immediately logging out, attempt a refresh-token exchange
(`authService.refreshToken()`, returning an `Observable<string>` for a new
access token) and, on success, retry the ORIGINAL request with the new
token attached (`switchMap` from the refresh call back into
`next(req.clone({...}))`), transparently to the caller — the component
that triggered the original request never sees the intermediate 401 at
all; its `subscribe({ next, error })` just eventually gets the retried
response. Only if the refresh ITSELF fails does the interceptor fall back
to `logout()` + redirect. The key design point: this entire flow lives
inside the interceptor and `AuthService`; `OrderService`/
`ProductService`/every component remain completely unaware a refresh ever
happened — the same "leak the real API shape into exactly one place"
principle EXERCISES.md's scenario exercise (envelope/PATCH contract
change) asks you to apply to `OrderService` directly.
*Follow-up:* "What's a subtlety with doing this for MULTIPLE simultaneous
401s (e.g. three requests in flight all get 401'd around the same
moment)?" → Naively, each would trigger its own independent refresh call,
racing each other and potentially invalidating tokens mid-flight (some
refresh-token schemes single-use the old token). The standard fix is to
share ONE in-flight refresh Observable across all concurrent 401s (e.g.
via a `shareReplay(1)`-wrapped refresh call that later 401s subscribe to
instead of triggering a new refresh), so N concurrent 401s produce exactly
one refresh call.

**Scenario:** "QA finds that after clicking 'Log out,' if they immediately
click the browser's back button to `/orders/abc123` (a page they'd
already loaded before logging out), they briefly see the OLD cached order
data before the guard redirects them. Is this a bug in `authGuard`, and
how would you address it?"
*Ideal answer:* Not strictly a guard bug — `authGuard` DOES correctly
re-run and redirect on that back-navigation (guards run on every
navigation attempt, including browser back/forward, which the Angular
Router intercepts). The "flash of old data" is a BROWSER caching/render
artifact: the previously-rendered DOM from before logout may still be
visible for a frame before Angular's router tears it down and redirects.
Mitigations: ensure sensitive views aren't rendered with browser-cacheable
markup that could be shown from the browser's own back-forward cache
(bfcache) without JS re-executing at all (a `Cache-Control: no-store`
response header on sensitive HTML, or, for an SPA, accepting that
client-side routing state is memory-only and the guard's redirect —
though momentarily visible — is the correct and sufficient FUNCTIONAL fix,
since no NEW data is fetched or exposed; only already-rendered DOM from
before logout briefly lingers).

---

## Change Detection & Performance

**Beginner:** "What's the difference between `ChangeDetectionStrategy.Default`
and `OnPush`?"
*Ideal answer:* `Default` checks a component's template for changes every
time Angular's change detection runs anywhere in the app (traditionally
triggered by zone.js noticing an async API completed). `OnPush` skips
checking a component unless a specific trigger fires: an `@Input()`
reference changed, an event originated from within that component's own
template, or a Signal/Observable (via `async` pipe) it reads emitted a new
value.

**Intermediate:** "Every component in this module uses `OnPush`. What
discipline does that require, with a concrete example from this
codebase?"
*Ideal answer:* All state updates must produce NEW references, never
mutate existing ones in place — `CartStateService.addItem()` is the
canonical example: it builds a new array via spread (`[...current, {
product, quantity }]`) rather than `current.push(...)`, because `OnPush`
(and Signals' own equality check) compares by `===`; mutating in place and
"setting" the same reference back would look like no change occurred, and
consumers wouldn't be notified.

**Senior:** "You've been asked to improve the perceived performance of a
large Angular data grid (thousands of rows, frequent partial updates) at
a company like this repo's target list (JPMorgan/Goldman-style trading UI,
or an Amazon/Microsoft-scale admin console). Walk through your approach,
tying in `OnPush`, `@for`'s `track`, and Signals."
*Ideal answer:* Layer the techniques: (1) `OnPush` on the grid component
and each row component (if rows are their own components) so only rows
whose OWN bound data actually changed get re-checked; (2) `@for` with a
STABLE, unique `track` key (never index) so partial data updates (a few
rows changing) map to the SAME DOM nodes/row-component instances rather
than destroying and recreating the whole visible set; (3) model
frequently-changing values (e.g. a live price column) as Signals passed
into row components, so a price tick updates exactly the cell that reads
that signal without re-checking the whole row's other columns; (4)
consider virtual scrolling (`@angular/cdk/scrolling`) so only visible rows
exist in the DOM at all, independent of change-detection strategy; (5)
profile with Angular DevTools' change-detection profiler BEFORE and AFTER
each change to confirm the optimization actually reduced check counts,
rather than assuming.

**Scenario:** "A component using `OnPush` and reading `order()` (a
`toSignal`-wrapped Observable) stops updating after a refactor where a
teammate changed `OrderService.getOrder` to return `this.http.get(...).pipe(shareReplay(1))`.
Diagnose why, referencing this exact change."
*Ideal answer:* `shareReplay(1)` makes the Observable MULTICAST and
caches/replays its last emission to new subscribers — meaning a SECOND
call to `getOrder(sameId)` (e.g. triggered by `watchOrder`'s `switchMap`
re-running for a route param that happens to resolve to the same id
again, or any other repeated subscription) now replays the CACHED,
possibly stale response instead of issuing a fresh HTTP request, because
`shareReplay` doesn't know the underlying data may have changed
server-side (e.g. after a cancel action) and has no built-in invalidation.
This isn't an `OnPush`/Signals bug at all — `toSignal` is faithfully
reflecting whatever the Observable actually emits; the bug is upstream, in
treating a per-request HTTP call as cacheable/shareable without an
invalidation strategy. The fix is either removing `shareReplay` (accept a
fresh request per subscription, the original, correct behavior) or adding
explicit cache invalidation (e.g. re-creating the shared Observable after
a mutating action like `cancelOrder` succeeds) — never adding
multicasting to a "fetch current state" call without also deciding when
that cache goes stale.
