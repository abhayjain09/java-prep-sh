# Angular Interview Prep — 10 Core Questions + Related Topics

---

## Q1: What are directives, and what types exist?

**Directives** are classes that let you attach behavior to DOM elements or change their structure/appearance. Angular has three types:

1. **Component directives** — directives with a template. Every `@Component` is technically a directive with a view attached. This is the most common type you use.
2. **Structural directives** — change the DOM layout by adding/removing elements. Prefixed with `*`.
   - `*ngIf` — conditionally include an element
   - `*ngFor` — repeat an element for each item in a list
   - `*ngSwitch` — switch between templates
3. **Attribute directives** — change the appearance or behavior of an existing element, but don't add/remove elements.
   - `ngClass` — add/remove CSS classes dynamically
   - `ngStyle` — apply inline styles dynamically
   - Custom attribute directives (e.g., a `highlight` directive that changes background color on hover)

**Follow-up they might ask:** "How do you create a custom directive?" — Use `@Directive({ selector: '[appHighlight]' })`, inject `ElementRef` and `Renderer2`, and use `@HostListener` to react to events like mouseenter/mouseleave.

---

## Q2: What is the use of SPA (Single Page Application)?

An SPA loads a single HTML page once, then dynamically updates content via JavaScript as the user navigates — instead of requesting a full new page from the server each time.

**Why it matters:**
- **Faster perceived performance** — no full page reloads; only data/components change.
- **Better UX** — feels like a native app, smoother transitions.
- **Reduced server load** — server sends data (JSON) instead of re-rendering full HTML pages.
- **Clear separation of concerns** — frontend (Angular) handles UI/state, backend (e.g., your Java/Spring API) handles data and business logic.

**Trade-offs to mention (shows depth):** SEO can be harder (mitigated with Angular Universal/SSR), initial bundle load can be heavier (mitigated with lazy loading), and browser history/routing must be handled manually by the framework's router.

---

## Q3: What is routing?

**Routing** is how an Angular SPA maps URLs to components, so different "pages" can be shown without a full browser reload.

Key pieces:
- `RouterModule.forRoot(routes)` — defines app-wide routes (registered once, in the root/app module or standalone bootstrap).
- `RouterModule.forChild(routes)` — used in feature modules for their own routes.
- `<router-outlet>` — placeholder in the template where the matched component renders.
- `routerLink` — directive for navigation links (avoids full page reload, unlike `href`).
- **Route guards** — control access: `CanActivate` (can you enter this route?), `CanDeactivate` (can you leave, e.g., unsaved changes warning?), `Resolve` (pre-fetch data before route loads).
- **Route parameters** — `:id` in a path, read via `ActivatedRoute`.

**Good follow-up answer:** "How do you pass data between routes?" — via route params, query params, or a shared service.

---

## Q4: Why do we need lazy loading?

**Lazy loading** means loading a feature module's code only when the user navigates to a route that needs it, instead of bundling everything into the initial JavaScript payload.

**Why it's needed:**
- **Faster initial load** — smaller main bundle = faster first paint, especially important on slow networks/devices.
- **Better scalability** — as an app grows to dozens of feature modules, you don't want users downloading admin/reporting code just to see the login page.
- **Efficient resource use** — code is fetched on-demand.

**How it's done (implementation detail interviewers love):**
```typescript
{
  path: 'admin',
  loadChildren: () => import('./admin/admin.module').then(m => m.AdminModule)
}
```
With standalone components (Angular 14+), you lazy-load individual components too:
```typescript
{
  path: 'admin',
  loadComponent: () => import('./admin.component').then(c => c.AdminComponent)
}
```

---

## Q5: Angular lifecycle (hooks)

Every component/directive goes through a lifecycle Angular manages. Key hooks, in order:

1. **`ngOnChanges`** — called when an `@Input()` property value changes (called before `ngOnInit`, and again on every subsequent input change).
2. **`ngOnInit`** — called once, after the first `ngOnChanges`. Ideal place for initialization logic (e.g., calling a service to fetch data).
3. **`ngDoCheck`** — called during every change detection run; used for custom change detection logic.
4. **`ngAfterContentInit`** — called once after content (projected via `<ng-content>`) is initialized.
5. **`ngAfterContentChecked`** — called after every check of projected content.
6. **`ngAfterViewInit`** — called once after the component's view (and child views) are fully initialized. Safe place to access `@ViewChild` references.
7. **`ngAfterViewChecked`** — called after every check of the component's view.
8. **`ngOnDestroy`** — called just before the component is destroyed. Use this to **unsubscribe from observables**, detach event listeners, clear timers — prevents memory leaks.

**Common interview trap:** "Why use `ngOnInit` instead of the constructor for initialization?" — see Q6.

---

## Q6: Constructor vs `ngOnInit`

| Constructor | `ngOnInit` |
|---|---|
| Part of the JS/TS class itself, not Angular-specific | Angular lifecycle hook, part of `OnInit` interface |
| Runs when the class is instantiated (dependency injection happens here) | Runs after Angular has set the initial `@Input()` values |
| Should only be used for **simple initialization** and **DI** (injecting services) | Should be used for **actual initialization logic** — API calls, subscribing to observables, setting up component state |

**Why this separation matters:** At constructor time, Angular hasn't yet bound your `@Input()` properties — so if a child component needs a parent-passed value to do setup work, doing it in the constructor could use `undefined`. By the time `ngOnInit` runs, inputs are set.

**Rule of thumb to state in interview:** "Constructor is for injection, `ngOnInit` is for initialization."

---

## Q7: Services

A **service** is a class that holds logic/data that isn't tied to a specific component — reusable business logic, data fetching, shared state, logging, etc.

- Created via `@Injectable({ providedIn: 'root' })` — this registers it as a singleton at the app level (tree-shakable, only loaded if used).
- Injected into components/other services through Angular's **Dependency Injection (DI)** system, via the constructor.
- Promotes separation of concerns: components handle the view, services handle data/logic.

**Example:**
```typescript
@Injectable({ providedIn: 'root' })
export class UserService {
  constructor(private http: HttpClient) {}
  getUsers() { return this.http.get('/api/users'); }
}
```

**Good follow-up:** "What's the difference between `providedIn: 'root'` and providing a service in a component's `providers` array?" — `root` gives you one singleton for the whole app; providing it at the component level creates a **new instance per component instance**, useful when you want isolated state per component.

---

## Q8: Dependency Injection (DI)

**DI** is a design pattern where a class receives its dependencies (usually services) from an external source rather than creating them itself.

**Why it matters:**
- Decouples classes from the concrete implementations they depend on.
- Makes unit testing easy — you can inject mock services instead of real ones.
- Angular's **injector hierarchy** manages singleton instances efficiently.

**How Angular does it:**
```typescript
constructor(private userService: UserService) {}
```
Angular sees `UserService` in the constructor, checks its injector tree, and provides (or creates) an instance automatically.

**Injector hierarchy (good depth to mention):** Root injector → Module injector → Component injector. A service registered at a lower level (e.g., component `providers`) shadows the one from root for that component's subtree.

**Since you have Java background:** this is conceptually the same idea as Spring's `@Autowired`/IoC container — Angular's DI is directly inspired by that pattern, so you can draw that parallel in the interview.

---

## Q9: How to make HTTP calls?

Angular provides `HttpClient` (from `@angular/common/http`) for calling REST APIs.

**Setup:**
```typescript
// standalone apps
bootstrapApplication(AppComponent, {
  providers: [provideHttpClient()]
});
```

**Making calls:**
```typescript
this.http.get<User[]>('/api/users').subscribe(users => this.users = users);
this.http.post('/api/users', newUser).subscribe(res => console.log(res));
```

**Key concepts to mention:**
- Returns an **Observable**, not a Promise — so you `subscribe()` to it (or use the `async` pipe in templates to auto-subscribe/unsubscribe).
- **HttpInterceptors** — intercept every outgoing request/incoming response, commonly used for attaching auth tokens (JWT/Okta), logging, or global error handling.
- **Error handling** — use RxJS `catchError` operator.
- Always **unsubscribe** in `ngOnDestroy` if not using `async` pipe, to avoid memory leaks.

---

## Q10: Explain RxJS

**RxJS (Reactive Extensions for JavaScript)** is a library for working with asynchronous data streams using **Observables**. Angular uses it heavily internally (HttpClient, forms, router events, EventEmitter).

**Core concepts:**
- **Observable** — a stream of data over time that you can subscribe to.
- **Observer/Subscriber** — the consumer that reacts to `next`, `error`, and `complete` events.
- **Operators** — functions that transform/combine streams, used with `.pipe()`:
  - `map` — transform emitted values
  - `filter` — only emit values matching a condition
  - `switchMap` — cancel the previous inner observable and switch to a new one (classic use: typeahead search, so old HTTP requests are cancelled)
  - `mergeMap` — run multiple inner observables concurrently
  - `debounceTime` — wait for a pause in emissions before emitting (great for search inputs)
  - `catchError` — handle errors in the stream
- **Subject / BehaviorSubject** — a Subject is both an Observable and Observer; a BehaviorSubject holds a "current value" and is commonly used for shared state between components.

**Interview-winning line:** "Promises resolve once; Observables can emit multiple values over time and are cancellable — that's why Angular chose Observables for HTTP and event streams."

---

## Related/Similar Questions Worth Prepping

- **What is change detection, and how does it work?** Angular checks the component tree for changes and updates the DOM. Default strategy checks everything; `OnPush` strategy only re-checks when `@Input()` references change or an event fires within the component — big performance topic.
- **What are Angular modules (NgModule) vs standalone components?** Modern Angular (14+) is moving away from NgModules toward standalone components — expect this to come up if the role uses a recent Angular version.
- **What is two-way data binding, and how does `[(ngModel)]` work?** It's syntactic sugar combining property binding `[ngModel]` and event binding `(ngModelChange)`.
- **What's the difference between `Observable` and `Promise`?** (ties directly into Q10 — multiple values vs single, lazy vs eager, cancellable vs not.)
- **What is a pipe, and difference between pure and impure pipes?** Pipes transform displayed data (`date`, `currency`, custom pipes); pure pipes only re-run when input reference changes, impure pipes run on every change detection cycle (expensive).
- **What is `@ViewChild` vs `@ContentChild`?** `@ViewChild` accesses elements/components in the component's own template; `@ContentChild` accesses projected content passed via `<ng-content>`.
- **How does Angular handle forms?** Template-driven forms vs Reactive forms — reactive forms (`FormGroup`, `FormControl`, `Validators`) are generally preferred for complex/testable forms.
- **What is Angular Universal / SSR?** Server-side rendering to improve SEO and initial load performance for SPAs.

---

### Tips for framing answers in the interview
- Lead with a one-sentence definition, then give a concrete example or code snippet.
- Where you can, connect Angular concepts to Java/Spring equivalents (DI, singleton services) — it shows you're thinking in patterns, not memorized syntax, which is exactly what a senior fullstack role looks for.
- For anything with a "why" (lazy loading, DI, RxJS), always mention the performance or maintainability payoff — that's usually what distinguishes a senior-level answer from a junior one.
