# Angular Interview Prep — Architecture, Lifecycle & Advanced Concepts

---

## Q1: How to create a custom structural directive?

A structural directive changes the DOM layout by adding/removing elements (like `*ngIf`/`*ngFor`). To build one, you work with `TemplateRef` (the template to render) and `ViewContainerRef` (where to render it).

**Example — a custom `*appUnless` directive (opposite of `*ngIf`):**

```typescript
@Directive({
  selector: '[appUnless]'
})
export class UnlessDirective {
  private hasView = false;

  constructor(
    private templateRef: TemplateRef<any>,
    private viewContainer: ViewContainerRef
  ) {}

  @Input() set appUnless(condition: boolean) {
    if (!condition && !this.hasView) {
      this.viewContainer.createEmbeddedView(this.templateRef);
      this.hasView = true;
    } else if (condition && this.hasView) {
      this.viewContainer.clear();
      this.hasView = false;
    }
  }
}
```

```html
<p *appUnless="isLoggedIn">You are not logged in.</p>
```

**Key concepts to explain:**
- The `*` prefix is syntactic sugar — `*appUnless="condition"` desugars to `<ng-template [appUnless]="condition"><p>...</p></ng-template>`.
- `TemplateRef` holds a reference to the `<ng-template>` content.
- `ViewContainerRef` is the location in the DOM where you insert or remove that template.
- `createEmbeddedView()` renders it; `clear()` removes it.

---

## Q2: Explain the `ngOnInit` lifecycle hook

Already introduced in earlier prep — expanding here since this is asked in almost every Angular interview:

`ngOnInit` is called **once**, right after Angular has set the component's initial `@Input()` properties (i.e., after the first `ngOnChanges`). It's part of the `OnInit` interface.

```typescript
export class UserProfileComponent implements OnInit {
  @Input() userId: string;
  user: User;

  constructor(private userService: UserService) {}

  ngOnInit(): void {
    this.userService.getUser(this.userId).subscribe(u => this.user = u);
  }
}
```

**Why not do this in the constructor?** At constructor time, Angular hasn't bound `@Input()` values yet — `this.userId` could be `undefined`. By `ngOnInit`, it's guaranteed to be set. Also, constructors should be reserved for dependency injection, keeping them lightweight and testable; `ngOnInit` is the intended place for actual initialization work like API calls.

---

## Q3: Explain the concept of Dependency Injection

Already covered in depth earlier — quick recap for continuity: DI is a pattern where a class receives its dependencies from an external injector rather than creating them itself. Angular's hierarchical injector (root → module/component) resolves and provides singleton or scoped instances automatically based on constructor parameter types.

```typescript
@Injectable({ providedIn: 'root' })
export class LoggerService {
  log(msg: string) { console.log(msg); }
}

@Component({ selector: 'app-root', template: '...' })
export class AppComponent {
  constructor(private logger: LoggerService) {
    this.logger.log('App started');
  }
}
```

**Good add-on for a senior interview:** mention **injection tokens** (`InjectionToken`) for injecting non-class values (like config objects), and **`@Optional()`/`@Self()`/`@SkipSelf()`** decorators for controlling how the injector resolves a dependency.

---

## Q4: Explain the folder structure and bootstrapping in Angular

**Typical folder structure (classic NgModule-based project):**

```
src/
├── app/
│   ├── core/              # singleton services, guards, interceptors (imported once)
│   ├── shared/             # reusable components/pipes/directives used across features
│   ├── features/           # feature modules (each with its own components, routes)
│   │   └── user/
│   │       ├── user.module.ts
│   │       ├── user-routing.module.ts
│   │       └── components/
│   ├── app.component.ts
│   ├── app.module.ts
│   └── app-routing.module.ts
├── assets/                 # images, fonts, static files
├── environments/           # environment.ts, environment.prod.ts (config per env)
├── index.html
├── main.ts                 # entry point — bootstraps the app
└── styles.css               # global styles
```

**Bootstrapping process:**

1. `main.ts` is the entry point:
   ```typescript
   platformBrowserDynamic().bootstrapModule(AppModule);
   ```
2. This bootstraps `AppModule`, which declares `bootstrap: [AppComponent]`.
3. Angular finds `<app-root>` in `index.html` and renders `AppComponent` into it.

**Standalone bootstrapping (Angular 14+/17+ style):**
```typescript
bootstrapApplication(AppComponent, {
  providers: [provideRouter(routes), provideHttpClient()]
});
```
No `AppModule` needed at all — `AppComponent` is marked `standalone: true` and directly declares its own imports.

**Interview tip:** mention that the `core`/`shared`/`feature` split is a common **scalability convention**, not an Angular-enforced rule — good to bring up in Q7 (large-scale apps) too.

---

## Q5: How do you handle version updates and migrations in Angular projects?

- **`ng update`** — the Angular CLI's official upgrade tool. Run `ng update @angular/core @angular/cli` to upgrade the framework itself; it also runs **schematics** that automatically rewrite code for breaking changes where possible.
- **Follow the official Angular Update Guide** (update.angular.io) — generates a step-by-step migration checklist based on your current and target versions; Angular's policy is to support **one major version increment at a time**, so large jumps (e.g., v8 → v17) should be done incrementally.
- **Check the changelog / breaking changes** for each major version before upgrading.
- **Run the full test suite** after each upgrade step to catch regressions early.
- **Update third-party dependencies** (e.g., Angular Material, ngrx) in step with the Angular version, since they often have matching version requirements.
- **Use a feature branch** for the upgrade, and upgrade dependencies incrementally rather than all at once, to isolate what broke if something does.
- **Watch for deprecations** — Angular typically deprecates an API one version before removing it, giving you a migration window (Angular's release notes flag these clearly).

---

## Q6: How to implement a Progressive Web App (PWA) in Angular?

**Steps:**

1. Add the PWA package via CLI:
   ```bash
   ng add @angular/pwa
   ```
   This automatically:
   - Adds a **service worker** (`ngsw-worker.js`) for offline caching.
   - Creates a **`manifest.webmanifest`** file (app name, icons, theme colors — used for "Add to Home Screen").
   - Adds icon assets and updates `index.html`/`angular.json` accordingly.

2. Configure caching strategy in `ngsw-config.json` — define which assets/API calls are cached and how (e.g., cache-first for static assets, freshness strategy for API data).

3. Register the service worker in production builds only (handled automatically by the CLI setup):
   ```typescript
   provideServiceWorker('ngsw-worker.js', {
     enabled: !isDevMode(),
     registrationStrategy: 'registerWhenStable:30000'
   })
   ```

4. Build for production and serve over HTTPS (service workers require a secure context):
   ```bash
   ng build --configuration production
   ```

**Why PWA matters (good context to add):** offline support, faster repeat loads (cached assets), installability on mobile/desktop home screens, and push notification support — without needing a separate native app.

---

## Q7: How would you approach building a large-scale Angular application with multiple modules and components?

Key architectural decisions to discuss:

- **Modular structure** — split into `CoreModule` (singleton services, guards, interceptors — imported once in `AppModule`), `SharedModule` (reusable components/pipes/directives, imported wherever needed), and multiple **feature modules** (each self-contained, often lazy-loaded).
- **Lazy loading** every feature module by route, so the initial bundle stays small.
- **Smart vs. dumb (presentational) components** — container components manage state/data-fetching; presentational components just receive `@Input()`s and emit `@Output()`s, making them reusable and easy to test.
- **State management** — for apps with complex shared state, consider NgRx (Redux-style) or a simpler service-with-`BehaviorSubject` pattern depending on team size/complexity.
- **Consistent folder conventions** (`core/`, `shared/`, `features/`) so any developer can navigate the codebase predictably.
- **Barrel files (`index.ts`)** to simplify imports from a module — used carefully, since overuse can hurt build performance.
- **Strict TypeScript settings** (`strict: true`) to catch bugs early at scale.
- **Style guide adherence** — Angular's official style guide (naming conventions, single responsibility per file) matters a lot more once many developers touch the codebase.
- **Automated testing strategy** — unit tests (Jasmine/Karma or Jest) for services/components, and e2e tests (Cypress/Playwright) for critical user flows.
- **Monorepo tooling (Nx)** — worth mentioning if the app is actually split into multiple apps/libraries sharing code.

---

## Q8: How would you approach optimizing the performance of an Angular application?

This overlaps heavily with earlier prep (Q24 and Q30 in the fundamentals doc) — consolidated list for quick recall:

- Lazy load feature modules/routes.
- `OnPush` change detection + immutable data updates.
- `trackBy` with `*ngFor`.
- `async` pipe for automatic subscription management.
- Pure pipes instead of function calls in templates.
- AOT compilation + production builds (`ng build --configuration production`).
- Virtual scrolling for long lists (`cdk-virtual-scroll`).
- Bundle analysis to remove unused/heavy dependencies.
- `NgZone.runOutsideAngular()` for high-frequency events that don't need change detection.
- Image optimization (`NgOptimizedImage`, lazy-loaded images).
- Caching API responses where appropriate (`shareReplay`).

---

## Q9: Explain the `ngDoCheck` lifecycle hook

`ngDoCheck` is called during **every change detection cycle**, immediately after `ngOnChanges` and `ngOnInit` on their respective first runs, and after `ngOnChanges` on every subsequent run.

**Why it exists:** Angular's default change detection only catches reference changes to `@Input()` properties — it won't notice if you mutate an object/array in place. `ngDoCheck` lets you implement **custom change detection logic** for cases like that.

```typescript
export class MyComponent implements DoCheck {
  @Input() items: string[];
  private previousItemsLength = 0;

  ngDoCheck() {
    if (this.items.length !== this.previousItemsLength) {
      console.log('Items array changed in length');
      this.previousItemsLength = this.items.length;
    }
  }
}
```

**Important caution to mention in interview:** `ngDoCheck` runs very frequently (on every change detection cycle across the whole app), so any logic inside it needs to be cheap — expensive operations here can seriously hurt performance. It's a powerful escape hatch, not something to use by default.

---

## Q10: Difference between Observable and Promises

Already covered in full in the RxJS prep doc (Q8) — summary: a Promise emits once, executes eagerly, and can't be cancelled; an Observable can emit multiple values over time, is lazy until subscribed, is cancellable via `unsubscribe()`, and has a rich operator library (`map`, `switchMap`, `debounceTime`, etc.) via `.pipe()`.

---

## Q11: What are routing events in Angular?

The Angular Router emits a stream of events (`router.events`, an Observable) as navigation progresses through its lifecycle. Useful for showing loading indicators, logging analytics, or reacting to navigation errors.

Key events, in order during a navigation:

1. **`NavigationStart`** — navigation begins.
2. **`RouteConfigLoadStart`** / **`RouteConfigLoadEnd`** — fired around lazy-loading a module.
3. **`RoutesRecognized`** — the router has matched the URL to a route configuration.
4. **`GuardsCheckStart`** / **`GuardsCheckEnd`** — route guards (`CanActivate`, etc.) are being evaluated.
5. **`ResolveStart`** / **`ResolveEnd`** — route resolvers are fetching data before activation.
6. **`NavigationEnd`** — navigation completed successfully.
7. **`NavigationCancel`** — navigation was cancelled (e.g., a guard returned `false`).
8. **`NavigationError`** — navigation failed due to an error.

**Example — showing a loading spinner during navigation:**
```typescript
this.router.events.subscribe(event => {
  if (event instanceof NavigationStart) this.loading = true;
  if (event instanceof NavigationEnd || event instanceof NavigationCancel || event instanceof NavigationError) {
    this.loading = false;
  }
});
```

---

## Q12: What is the purpose of the `ngOnChanges` lifecycle hook?

`ngOnChanges` is called **whenever an `@Input()` property's value changes** — including the very first time it's set (before `ngOnInit`), and again every time a new value is passed from the parent.

```typescript
export class ChildComponent implements OnChanges {
  @Input() userId: string;

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['userId']) {
      console.log('Previous:', changes['userId'].previousValue);
      console.log('Current:', changes['userId'].currentValue);
      console.log('First change?', changes['userId'].firstChange);
    }
  }
}
```

**Why it's useful:** it's the right place to react specifically to **input changes from a parent** — e.g., re-fetching data when a parent passes a new `userId`, without re-running all of `ngOnInit`'s setup logic.

**Important caveat to mention:** `ngOnChanges` only fires for changes to **reference values** of `@Input()` properties bound in the template — it does NOT fire if you mutate an object/array in place (same limitation mentioned in Q9's `ngDoCheck` discussion).

---

## Q13: Describe the role of the Angular Router and how it creates dynamic views

The **Router** maps URL paths to components, letting an SPA show different "views" without full page reloads. Already covered in the first prep doc (Q3) — expanding on the "dynamic views" part here:

**Dynamic views via route parameters:**
```typescript
{ path: 'user/:id', component: UserDetailComponent }
```
```typescript
// in UserDetailComponent
this.route.paramMap.subscribe(params => {
  this.userId = params.get('id');
  this.loadUser(this.userId);
});
```

**Dynamic views via child/nested routes:**
```typescript
{
  path: 'dashboard',
  component: DashboardComponent,
  children: [
    { path: 'stats', component: StatsComponent },
    { path: 'settings', component: SettingsComponent }
  ]
}
```
The parent component has its own `<router-outlet>` for its children, allowing nested dynamic content.

**Dynamic views via resolvers** — pre-fetch data before the component even loads, so the view renders with data already available instead of showing a loading state:
```typescript
{ path: 'user/:id', component: UserDetailComponent, resolve: { user: UserResolver } }
```

---

## Q14: What is `NgZone` and how do you use it?

**Zone.js** is a library Angular uses to detect when asynchronous operations (events, `setTimeout`, HTTP calls, Promises) complete, so it knows when to run change detection. `NgZone` is Angular's wrapper around this — it's how Angular "patches" async APIs to hook into its change detection cycle automatically.

**Two main use cases:**

1. **`NgZone.runOutsideAngular()`** — run code *outside* Angular's zone so it does NOT trigger change detection on every event. Useful for high-frequency events (mouse move, scroll, a WebSocket firing very often, `requestAnimationFrame` loops) where you don't want Angular checking the whole component tree every single time.
   ```typescript
   constructor(private ngZone: NgZone) {}

   ngOnInit() {
     this.ngZone.runOutsideAngular(() => {
       window.addEventListener('scroll', this.onScroll);
     });
   }

   onScroll = () => {
     // heavy logic here doesn't trigger Angular change detection each time
   };
   ```

2. **`NgZone.run()`** — re-enter Angular's zone from inside code that's running outside it (e.g., after `runOutsideAngular`, if you eventually need to update a bound property and want the view to reflect it):
   ```typescript
   this.ngZone.run(() => {
     this.someBoundProperty = newValue; // now triggers change detection
   });
   ```

**Why this matters for a senior answer:** it shows you understand *why* Angular change detection can become a performance bottleneck and how to selectively opt out of it for non-UI-critical async work — a common real optimization in apps with heavy real-time data (charts, live feeds, drag-and-drop).

---

## Q15: Why the `async` pipe in Angular, and what are its benefits?

The **`async` pipe** subscribes to an `Observable` (or `Promise`) directly in the template and automatically unwraps the emitted value — without you writing manual `subscribe()`/`unsubscribe()` code in the component class.

```html
<div *ngIf="user$ | async as user">
  {{ user.name }}
</div>

<ul>
  <li *ngFor="let item of items$ | async">{{ item.name }}</li>
</ul>
```

```typescript
user$: Observable<User> = this.userService.getUser(id);
```

**Benefits:**
- **Automatic subscription management** — subscribes when the template is rendered, and **automatically unsubscribes when the component is destroyed**, eliminating a major source of memory leaks.
- **Less boilerplate** — no need for manual `subscribe()` calls, local state variables, or `ngOnDestroy` cleanup for that stream.
- **Plays well with `OnPush` change detection** — the async pipe marks the component for check when a new value arrives, so `OnPush` components update correctly without extra manual `ChangeDetectorRef` calls.
- **Handles `null`/undefined gracefully** — combined with `*ngIf="... as user"`, avoids errors from accessing properties before data has loaded.

**Interview line:** "The async pipe is usually the right default for template-bound Observables — it's less code, avoids memory leaks by construction, and it's the pattern Angular's own style guide recommends over manual subscribe/unsubscribe wherever the value is only needed in the template."

---

### Cross-references to earlier prep docs
- **DI, Services, Lazy loading, Observable vs Promise** — full explanations in the first Angular core doc.
- **RxJS operators, unsubscribe patterns, Push vs Pull** — full explanations in the RxJS doc.
- **Memory leak debugging, performance optimization steps, multiple API calls** — full explanations in the fundamentals/scenarios doc.

This doc focuses on what's new here: custom structural directives, `ngDoCheck`/`ngOnChanges` internals, folder structure/bootstrapping, version migration process, PWA setup, large-scale architecture, routing events, `NgZone`, and the `async` pipe — these tend to be the questions that separate mid-level from senior-level Angular candidates.
