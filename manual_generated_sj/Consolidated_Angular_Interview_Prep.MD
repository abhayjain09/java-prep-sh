# The Complete Angular Interview Prep Guide — Basic to Advanced

*One consolidated document merging all prior prep material. Organized as a single learning path — read top to bottom for full basic-to-advanced coverage, or jump to a section using the table of contents.*

---

## Table of Contents

1. [Angular Basics & Ecosystem](#1-angular-basics--ecosystem)
2. [TypeScript Fundamentals](#2-typescript-fundamentals)
3. [Tooling: NPM, CLI, Build](#3-tooling-npm-cli-build)
4. [Components, Modules & Bootstrapping](#4-components-modules--bootstrapping)
5. [Decorators](#5-decorators)
6. [Data Binding](#6-data-binding)
7. [Directives](#7-directives)
8. [Pipes](#8-pipes)
9. [Services & Dependency Injection](#9-services--dependency-injection)
10. [Lifecycle Hooks](#10-lifecycle-hooks)
11. [Routing](#11-routing)
12. [Asynchronous Angular: HTTP, Promises & RxJS](#12-asynchronous-angular-http-promises--rxjs)
13. [Change Detection & Performance](#13-change-detection--performance)
14. [Application Architecture at Scale](#14-application-architecture-at-scale)
15. [PWA & Offline Support](#15-pwa--offline-support)
16. [Debugging & Scenario-Based Questions](#16-debugging--scenario-based-questions)
17. [Angular Version History — Basic to Advanced Evolution](#17-angular-version-history--basic-to-advanced-evolution)
18. [Interview Framing Tips](#18-interview-framing-tips)

---

## 1. Angular Basics & Ecosystem

### What is Angular?

Angular is a **TypeScript-based, component-driven front-end framework** (maintained by Google) for building single-page web applications. It ships as a complete toolkit — routing, forms, HTTP client, dependency injection, testing utilities — rather than just a rendering library.

### Key features

- **Component-based architecture** — UI built from reusable, self-contained components.
- **Two-way data binding** — `[(ngModel)]` keeps model and view in sync automatically.
- **Dependency Injection** — built-in DI system for managing services and their lifetimes.
- **TypeScript** — static typing catches errors at compile time.
- **RxJS integration** — reactive programming baked into HTTP, forms, router.
- **CLI tooling** — scaffolds projects, generates components, handles builds/tests.
- **Routing** — built-in client-side router with guards, lazy loading, resolvers.
- **Cross-platform** — same codebase basis for web, mobile (Ionic/NativeScript), desktop (Electron).

### AngularJS vs Angular

| AngularJS (v1.x) | Angular (v2+) |
|---|---|
| Written in JavaScript | Written in TypeScript |
| Based on `$scope` and controllers | Based on components and classes |
| MVC architecture | Component-based architecture |
| Two-way binding via `ng-model`; digest-cycle performance issues at scale | Improved, more efficient change detection |
| No mobile support out of the box | Mobile-friendly |
| Not modular by default | Modular by design (NgModules / standalone components) |

**Key point:** Angular isn't AngularJS-with-a-version-bump — it's a complete rewrite with no automatic migration path. See [Section 17](#17-angular-version-history--basic-to-advanced-evolution) for why this rewrite happened.

### Role of TypeScript in Angular

TypeScript is a superset of JavaScript adding static typing, interfaces, decorators, and modern ES features, compiled down to plain JS.

Angular relies on it for:
- **Type safety** — catches errors at compile time.
- **Decorators** — `@Component`, `@Injectable`, `@Input`, `@Output` are metadata Angular's compiler reads.
- **Better tooling** — autocomplete, refactoring, self-documenting code.
- **OOP features** — classes, interfaces, generics map naturally to Angular's class-based model.

### How Angular enables Single-Page Applications (SPA)

An SPA loads a single HTML page once, then dynamically updates content via JavaScript instead of full page reloads. Angular provides everything needed:
- **Router** — swaps components based on URL without a reload (`<router-outlet>`).
- **Component-based views** — each "page" is just a component.
- **HttpClient** — fetches JSON data asynchronously instead of full HTML re-renders.
- **Change detection** — keeps DOM in sync with component state automatically.

**Why SPAs matter:** faster perceived performance, smoother UX, reduced server load, clean separation between frontend (Angular) and backend (e.g., a Java/Spring API).

**Trade-offs worth mentioning:** SEO can be harder (mitigated with SSR — see Section 17), and initial bundle load can be heavier (mitigated with lazy loading — Section 11).

---

## 2. TypeScript Fundamentals

### `let` vs `var`

| `var` | `let` |
|---|---|
| Function-scoped | Block-scoped |
| Can be redeclared in the same scope | Cannot be redeclared |
| Hoisted and initialized as `undefined` | Hoisted but not initialized ("temporal dead zone") |
| Can leak out of blocks (classic loop bug) | Stays contained in its block |

```typescript
for (var i = 0; i < 3; i++) { setTimeout(() => console.log(i), 0); }
// logs 3, 3, 3 — var leaks; all closures share one i

for (let j = 0; j < 3; j++) { setTimeout(() => console.log(j), 0); }
// logs 0, 1, 2 — let creates a new binding per iteration
```
Modern convention: use `let`/`const`; treat `var` as legacy.

### Type annotations

```typescript
let username: string = 'Shreya';
let age: number = 30;
function greet(name: string): string { return `Hello, ${name}`; }
```
TypeScript infers types where possible, but explicit annotations matter most for function params, return types, and empty collections.

### Primitive vs non-primitive types

- **Primitive** (compared/copied **by value**): `string`, `number`, `boolean`, `null`, `undefined`, `symbol`, `bigint`
- **Non-primitive** (compared/copied **by reference**): objects, arrays, classes, interfaces, functions

This reference-vs-value distinction directly underpins Angular's `OnPush` change detection (Section 13) — it relies on reference equality checks.

### The `any` type

`any` opts a variable out of type checking entirely:
```typescript
let data: any = 'hello';
data = 42;         // no error
data.foo.bar.baz;  // no compile-time error, even though this crashes at runtime
```
Generally discouraged — a strict codebase (`strict: true`) restricts it in favor of `unknown` (forces narrowing) or real interfaces.

### Enums

```typescript
enum OrderStatus { Pending, Shipped, Delivered, Cancelled } // numbered from 0
enum OrderStatusStr { Pending = 'PENDING', Shipped = 'SHIPPED' } // string enum, more readable when debugging
```
Common Angular use: fixed sets of states (loading states, roles, form modes) in a type-safe way instead of raw strings prone to typos.

### Type assertion

```typescript
let someValue: unknown = 'this is a string';
let strLength: number = (someValue as string).length;
```
Tells the compiler "trust me" — doesn't validate or convert at runtime. Common Angular use: `document.getElementById('username') as HTMLInputElement`.

### Arrow functions

Arrow functions don't have their own `this` — they inherit it lexically from the enclosing scope.

```typescript
export class MyComponent {
  count = 0;
  incrementRegular() { this.count++; }              // `this` can be lost if passed as a bare reference
  incrementArrow = () => { this.count++; };          // always retains `this` from the component instance
}
```
This is why `@HostListener` callbacks and RxJS subscription callbacks are commonly written as arrow functions.

---

## 3. Tooling: NPM, CLI, Build

### NPM and `node_modules`

**NPM** is the default package manager for JavaScript/Node.js.
- `package.json` — lists dependencies, scripts, metadata.
- `npm install` — downloads everything listed (and sub-dependencies).
- **`node_modules/`** — where installed packages physically live; excluded from version control.
- **`package-lock.json`** — locks exact versions for reproducible installs.

```bash
npm install
npm install lodash
npm install --save-dev jest
```

### Angular CLI

Scaffolds, builds, tests, and maintains projects without hand-configuring the build toolchain.

```bash
ng new my-app
ng generate component user     # ng g c user
ng generate service auth       # ng g s auth
ng serve
ng build
ng test
ng lint
ng update
```

### `ng serve` vs `ng build`

| `ng serve` | `ng build` |
|---|---|
| Local **development server** with live reload | Compiles to static output (`dist/`) |
| Output kept in memory | Output written to disk for deployment |
| Unoptimized, fast rebuilds | Optimized (`--configuration production`): minified, tree-shaken, AOT-compiled |

```bash
ng serve
ng build --configuration production
```

---

## 4. Components, Modules & Bootstrapping

### Angular components

A component controls a section of the UI — the fundamental building block of an app.

```typescript
@Component({
  selector: 'app-user-card',
  templateUrl: './user-card.component.html',
  styleUrls: ['./user-card.component.css']
})
export class UserCardComponent {
  @Input() user: User;
  @Output() selected = new EventEmitter<User>();
  onSelect() { this.selected.emit(this.user); }
}
```
Made up of: a **decorator** (`@Component`, with metadata), a **class** (logic/state), a **template** (view), and optional scoped **styles**.

### Selector and Template

- **Selector** — the custom HTML tag name (`app-user-card`) used to place the component in another template.
- **Template** — the HTML defining what the component renders; can be inline (`template: '...'`) or external (`templateUrl`).

### Angular Modules (`NgModule`)

A container grouping related components, directives, pipes, and services.

```typescript
@NgModule({
  declarations: [AppComponent, UserListComponent],
  imports: [BrowserModule, FormsModule, HttpClientModule],
  providers: [UserService],
  bootstrap: [AppComponent]
})
export class AppModule {}
```

**Modern alternative — standalone components (Angular 14+, default from v19):** components declare their own imports and skip `NgModule` entirely (see Section 17 for why this evolved).
```typescript
@Component({
  selector: 'app-user',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './user.component.html'
})
export class UserComponent {}
```

### Folder structure (typical NgModule-based project)

```
src/
├── app/
│   ├── core/              # singleton services, guards, interceptors (imported once)
│   ├── shared/             # reusable components/pipes/directives
│   ├── features/           # feature modules, each often lazy-loaded
│   │   └── user/
│   │       ├── user.module.ts
│   │       ├── user-routing.module.ts
│   │       └── components/
│   ├── app.component.ts
│   ├── app.module.ts
│   └── app-routing.module.ts
├── assets/
├── environments/            # environment.ts, environment.prod.ts
├── index.html
├── main.ts                  # entry point
└── styles.css
```
`core`/`shared`/`features` is a scalability **convention**, not something Angular enforces — worth mentioning explicitly if asked.

### Bootstrapping: how an Angular app loads and starts

1. `main.ts` is the entry point:
   ```typescript
   platformBrowserDynamic().bootstrapModule(AppModule);
   ```
2. This bootstraps `AppModule`, which declares `bootstrap: [AppComponent]` — the **bootstrapped component**. `AppModule` itself is the **bootstrapped module**.
3. Angular finds `<app-root>` in `index.html` and renders `AppComponent` into it.

**Standalone bootstrapping (modern style, no `AppModule` at all):**
```typescript
bootstrapApplication(AppComponent, {
  providers: [provideRouter(routes), provideHttpClient()]
});
```

---

## 5. Decorators

### What is a decorator?

A TypeScript feature — `@SomethingLikeThis` — that attaches metadata to a class, property, method, or parameter. Angular's compiler reads this metadata to know how to treat that code. Without `@Component`, a class would just be a plain TypeScript class Angular knows nothing about.

### Types of decorators

1. **Class decorators** — define what kind of Angular building block a class is:
   `@Component`, `@NgModule`, `@Injectable`, `@Directive`, `@Pipe`
2. **Property decorators**:
   `@Input()`, `@Output()`, `@ViewChild()`, `@ContentChild()`
3. **Method decorators**:
   `@HostListener()` — binds a method to a DOM event on the host element
4. **Parameter decorators** (control DI resolution):
   `@Inject()`, `@Optional()`, `@Self()`, `@SkipSelf()`

---

## 6. Data Binding

Four types:

1. **Interpolation** (`{{ }}`) — component → view, one-way.
   ```html
   <h1>{{ title }}</h1>
   ```
2. **Property binding** (`[ ]`) — component → view, one-way, binds DOM element properties.
   ```html
   <img [src]="imageUrl" />
   ```
3. **Event binding** (`( )`) — view → component, one-way, listens to DOM events.
   ```html
   <button (click)="onSave()">Save</button>
   ```
4. **Two-way binding** (`[( )]`) — combines property + event binding.
   ```html
   <input [(ngModel)]="username" />
   ```

---

## 7. Directives

### Types of directives

1. **Component directives** — a directive with a template; every `@Component` is technically a directive with a view.
2. **Structural directives** — change DOM layout by adding/removing elements. Prefixed with `*`.
   - `*ngIf` — conditionally include an element
   - `*ngFor` — repeat an element per list item
   - `ngSwitch` — switch between templates
3. **Attribute directives** — change appearance/behavior without adding/removing elements.
   - `ngClass`, `ngStyle`, and custom ones (e.g., a highlight-on-hover directive)

### `*ngIf` vs `ngSwitch`

| `*ngIf` | `ngSwitch` |
|---|---|
| Shows/hides a single element based on a boolean | Shows one of several elements based on matching a value |
| Good for simple show/hide | Good for multi-way branching |

```html
<div [ngSwitch]="status">
  <p *ngSwitchCase="'loading'">Loading...</p>
  <p *ngSwitchCase="'error'">Something went wrong</p>
  <p *ngSwitchDefault>Ready</p>
</div>
```
Rule of thumb: two states → `*ngIf`; three or more mutually exclusive states → `ngSwitch`.

### `*ngFor` — dynamic lists

```html
<ul>
  <li *ngFor="let item of items; let i = index; trackBy: trackById">
    {{ i + 1 }}. {{ item.name }}
  </li>
</ul>
```
```typescript
trackById(index: number, item: any) { return item.id; }
```
`trackBy` lets Angular efficiently re-render only changed items instead of destroying/recreating the whole list.

### `ngStyle` and `ngClass`

```html
<p [ngStyle]="{ backgroundColor: isActive ? 'lightgreen' : 'white' }">Status</p>
<div [ngClass]="{ 'active': isActive, 'disabled': !isEnabled }">Item</div>
```

### Difference between Component, Attribute, and Structural directives

| Type | Has a template? | Changes DOM structure? | Example |
|---|---|---|---|
| Component | Yes | N/A (it *is* the view) | `@Component` |
| Structural | No | Yes — adds/removes elements | `*ngIf`, `*ngFor` |
| Attribute | No | No — changes appearance/behavior only | `ngClass`, `ngStyle` |

### Custom structural directive

A structural directive uses `TemplateRef` (the template to render) and `ViewContainerRef` (where to render it).

```typescript
@Directive({ selector: '[appUnless]' })
export class UnlessDirective {
  private hasView = false;
  constructor(private templateRef: TemplateRef<any>, private viewContainer: ViewContainerRef) {}

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
The `*` prefix is sugar — `*appUnless="condition"` desugars to `<ng-template [appUnless]="condition">...</ng-template>`.

### Custom attribute directive

```typescript
@Directive({ selector: '[appHighlight]' })
export class HighlightDirective {
  @Input() appHighlight: string = 'yellow';
  constructor(private el: ElementRef, private renderer: Renderer2) {}

  @HostListener('mouseenter') onMouseEnter() {
    this.renderer.setStyle(this.el.nativeElement, 'backgroundColor', this.appHighlight);
  }
  @HostListener('mouseleave') onMouseLeave() {
    this.renderer.removeStyle(this.el.nativeElement, 'backgroundColor');
  }
}
```
```html
<p [appHighlight]="'lightblue'">Hover over me</p>
```

---

## 8. Pipes

Pipes transform data for display in a template without changing the underlying data.

**Built-in pipes:** `date`, `currency`, `uppercase`/`lowercase`, `number` (decimal), `percent`, `json` (great for debugging), `async` (unwraps Observables/Promises — see Section 13).

**Custom pipe:**
```typescript
@Pipe({ name: 'truncate' })
export class TruncatePipe implements PipeTransform {
  transform(value: string, limit: number = 20): string {
    return value.length > limit ? value.substring(0, limit) + '...' : value;
  }
}
```

**Parameterized pipes:**
```html
{{ birthday | date:'shortDate' }}
{{ amount | currency:'USD':'symbol':'1.2-2' }}
{{ description | truncate:50 }}
```

**Pure vs impure pipes:**
- **Pure** (default) — only re-runs when the input *reference* changes. Efficient.
- **Impure** (`{ pure: false }`) — re-runs on **every** change detection cycle regardless of whether input changed. Needed for in-place array filtering, but expensive — use sparingly.

**Pipe chaining:**
```html
{{ user.name | uppercase | slice:0:10 }}
{{ birthday | date:'fullDate' | uppercase }}
```
Order matters — applied left to right.

---

## 9. Services & Dependency Injection

### Services

A reusable class holding logic/data not tied to a specific component (API calls, shared state, logging).

```typescript
@Injectable({ providedIn: 'root' })
export class UserService {
  private apiUrl = '/api/users';
  constructor(private http: HttpClient) {}
  getUsers(): Observable<User[]> { return this.http.get<User[]>(this.apiUrl); }
}
```
`providedIn: 'root'` registers it as an app-wide singleton (tree-shakable — excluded from the bundle if never injected anywhere).

**Creating one via CLI:**
```bash
ng generate service services/user   # ng g s services/user
```

### Dependency Injection (DI)

A pattern where a class receives dependencies from an external injector rather than creating them itself.

```typescript
export class OrderComponent {
  constructor(private orderService: OrderService, private authService: AuthService) {}
}
```
No `new OrderService()` anywhere — Angular's injector handles instantiation and lifetime.

**Why it matters:** decouples classes from concrete implementations, makes unit testing easy (inject mocks), and Angular's injector hierarchy manages singletons efficiently.

**If you have a Java/Spring background:** this is conceptually the same idea as `@Autowired`/IoC — Angular's DI is directly inspired by that pattern, a good parallel to draw in interviews.

### Hierarchical Dependency Injection

Angular's DI is a **tree of injectors** mirroring the component tree, plus module and root levels above it:

```
Root injector (providedIn: 'root' — app-wide singletons)
   └── Module injector (services in an NgModule's `providers`)
         └── Component injector (services in a component's own `providers`)
               └── Child component injector (inherits, can override)
```

Resolution: a component looks in its own injector first; if not found, walks **up** the tree until a provider is found (or throws).

```typescript
@Component({
  selector: 'app-cart',
  providers: [CartService]  // new instance scoped to this component subtree
})
export class CartComponent {}
```
Useful when you want isolated state per component instance (e.g., independent mini-cart widgets).

### Providers

A provider tells the injector **how to create** a dependency:
```typescript
providers: [
  UserService,                                          // shorthand
  { provide: UserService, useClass: MockUserService },  // substitute (great for testing)
  { provide: API_URL, useValue: 'https://api.example.com' },
  { provide: Logger, useFactory: () => new Logger(isDevMode()) },
  { provide: SomeToken, useExisting: AnotherService }
]
```
This flexibility is what makes DI powerful for **testing** (swap mocks in) and **environment-specific config** without touching consuming components.

### Role of `@Injectable`

Marks a class as injectable and tells Angular's compiler to process **that class's own constructor dependencies** too, so a service can depend on other services.

```typescript
@Injectable({ providedIn: 'root' })
export class OrderService {
  constructor(private http: HttpClient, private logger: LoggerService) {}
}
```
Best practice: always include it, even with no dependencies yet — otherwise adding one later silently breaks. `providedIn: 'root'` also enables tree-shaking.

**Additional `providedIn` options (since Angular 9):**
- `'platform'` — one instance shared across all apps on the page
- `'any'` — a unique instance per lazy-loaded module that injects it

### Sharing data between parent and child components

**Parent → Child:** `@Input()`
```typescript
@Input() user: User;
```
```html
<app-user-card [user]="selectedUser"></app-user-card>
```

**Child → Parent:** `@Output()` + `EventEmitter`
```typescript
@Output() userSelected = new EventEmitter<User>();
selectUser() { this.userSelected.emit(this.user); }
```
```html
<app-user-card (userSelected)="onUserSelected($event)"></app-user-card>
```

**Unrelated/distant components:** a shared service with a `BehaviorSubject`, so any component can subscribe to or update shared state without a direct parent-child relationship.

---

## 10. Lifecycle Hooks

Full sequence as a component is created, updated, and destroyed:

1. **`ngOnChanges`** — called whenever an `@Input()` value changes (before `ngOnInit` on first call, then on every subsequent input change).
2. **`ngOnInit`** — called once, after the first `ngOnChanges`. The right place for initialization logic (API calls, etc.).
3. **`ngDoCheck`** — called on **every** change detection cycle; custom change detection logic.
4. **`ngAfterContentInit`** — once, after projected content (`<ng-content>`) is initialized.
5. **`ngAfterContentChecked`** — after every check of projected content.
6. **`ngAfterViewInit`** — once, after the component's view (and child views) are fully initialized; safe to access `@ViewChild`.
7. **`ngAfterViewChecked`** — after every check of the component's view.
8. **`ngOnDestroy`** — just before destruction; unsubscribe Observables, detach listeners, clear timers.

### Constructor vs `ngOnInit`

| Constructor | `ngOnInit` |
|---|---|
| Plain JS/TS class feature, not Angular-specific | Angular lifecycle hook (`OnInit` interface) |
| Runs at instantiation — DI happens here | Runs after Angular sets initial `@Input()` values |
| Use for **simple init** and **DI** only | Use for **actual initialization logic** — API calls, subscriptions |

**Why the separation:** at constructor time, `@Input()`s aren't bound yet — `this.userId` could be `undefined`. By `ngOnInit`, inputs are guaranteed set.

**Rule of thumb:** "Constructor is for injection, `ngOnInit` is for initialization."

### `ngOnChanges` in depth

```typescript
ngOnChanges(changes: SimpleChanges): void {
  if (changes['userId']) {
    console.log('Previous:', changes['userId'].previousValue);
    console.log('Current:', changes['userId'].currentValue);
    console.log('First change?', changes['userId'].firstChange);
  }
}
```
Right place to react specifically to input changes from a parent (e.g., re-fetch when `userId` changes) without re-running all of `ngOnInit`. **Caveat:** only fires on reference changes to `@Input()`-bound values — not on in-place mutation of an object/array.

### `ngDoCheck` in depth

Runs on every change detection cycle, catching things Angular's default detection misses (like in-place array/object mutation):
```typescript
ngDoCheck() {
  if (this.items.length !== this.previousItemsLength) {
    console.log('Items array changed in length');
    this.previousItemsLength = this.items.length;
  }
}
```
**Caution:** runs very frequently — keep logic cheap. A powerful escape hatch, not a default tool.

---

## 11. Routing

### Core concepts

- `RouterModule.forRoot(routes)` — app-wide routes, registered once.
- `RouterModule.forChild(routes)` — feature module routes.
- `<router-outlet>` — placeholder where the matched component renders.
- `routerLink` — navigation directive (no full reload, unlike `href`).
- **Route guards** — `CanActivate` (can enter?), `CanDeactivate` (can leave, e.g. unsaved changes), `Resolve` (pre-fetch data before activation).
- **Route parameters** — `:id` in a path, read via `ActivatedRoute`.

### Dynamic views via the router

**Route params:**
```typescript
{ path: 'user/:id', component: UserDetailComponent }
```
```typescript
this.route.paramMap.subscribe(params => {
  this.userId = params.get('id');
  this.loadUser(this.userId);
});
```

**Nested/child routes:**
```typescript
{
  path: 'dashboard', component: DashboardComponent,
  children: [
    { path: 'stats', component: StatsComponent },
    { path: 'settings', component: SettingsComponent }
  ]
}
```

**Resolvers** — pre-fetch data before the component loads, so the view renders with data already present:
```typescript
{ path: 'user/:id', component: UserDetailComponent, resolve: { user: UserResolver } }
```

### Routing events

The Router emits an Observable stream (`router.events`) as navigation progresses:

1. `NavigationStart`
2. `RouteConfigLoadStart` / `RouteConfigLoadEnd` (around lazy-loading a module)
3. `RoutesRecognized`
4. `GuardsCheckStart` / `GuardsCheckEnd`
5. `ResolveStart` / `ResolveEnd`
6. `NavigationEnd`
7. `NavigationCancel`
8. `NavigationError`

```typescript
this.router.events.subscribe(event => {
  if (event instanceof NavigationStart) this.loading = true;
  if (event instanceof NavigationEnd || event instanceof NavigationCancel || event instanceof NavigationError) {
    this.loading = false;
  }
});
```

### Lazy loading

Loading a feature module's code only when a route needs it, instead of bundling everything upfront.

**Why:** faster initial load, better scalability (users don't download admin/reporting code just to see a login page), efficient on-demand resource use.

```typescript
{
  path: 'admin',
  loadChildren: () => import('./admin/admin.module').then(m => m.AdminModule)
}
```
Standalone-component style (Angular 14+):
```typescript
{ path: 'admin', loadComponent: () => import('./admin.component').then(c => c.AdminComponent) }
```

---

## 12. Asynchronous Angular: HTTP, Promises & RxJS

### Making HTTP calls

```typescript
onFetchClick() {
  this.http.get<User[]>('/api/users').subscribe({
    next: (data) => this.users = data,
    error: (err) => console.error('Error fetching users', err)
  });
}
```
```html
<button (click)="onFetchClick()">Load Users</button>
```

Key HttpClient concepts:
- Returns an **Observable**, not a Promise — `subscribe()` to it, or use the `async` pipe.
- **HttpInterceptors** — intercept every request/response; common for attaching auth tokens, logging, global error handling.
- **Error handling** — RxJS `catchError`.
- Unsubscribe in `ngOnDestroy` if not using `async` pipe.

### Promise vs Observable

| Promise | Observable (RxJS) |
|---|---|
| Emits a **single** value | Can emit **multiple** values over time |
| **Eager** — executes immediately | **Lazy** — executes only when subscribed |
| **Not cancellable** | **Cancellable** via `unsubscribe()` |
| Only `.then()`/`.catch()`/`.finally()` | Rich operators (`map`, `filter`, `debounceTime`, `retry`...) |
| Built into JavaScript | Requires the RxJS library |
| Good for one-off async ops | Good for streams: events, WebSockets, cancellable HTTP |

**One-liner:** "A Promise resolves once and can't be cancelled; an Observable can emit many values over time, is lazy until subscribed, and is cancellable — that's why Angular built HttpClient, Forms, and the Router around Observables."

### What is RxJS?

**Reactive Extensions for JavaScript** — a library for composing async, event-based programs using observable sequences. Angular uses it internally for `HttpClient`, Reactive Forms (`valueChanges`/`statusChanges`), Router events, and `EventEmitter` (which extends `Subject`).

### Observables and Observers

- **Observable** — a stream of data produced over time; *lazy* — nothing happens until subscribed.
- **Observer** — the consumer, with three optional callbacks: `next(value)`, `error(err)`, `complete()`.

```typescript
const observable = new Observable(subscriber => {
  subscriber.next(1);
  subscriber.next(2);
  subscriber.complete();
});

observable.subscribe({
  next: value => console.log('Got value:', value),
  error: err => console.error('Error:', err),
  complete: () => console.log('Done')
});
```

### `subscribe()`

Triggers execution of an Observable. Each `subscribe()` call triggers a **new execution** for "cold" observables (like HTTP calls) — unlike a Promise, which runs once regardless of how many `.then()`s are attached.

### Unsubscribing

Not unsubscribing from long-lived streams (a `Subject`, an interval, route params) causes **memory leaks**.

**Manual:**
```typescript
private sub: Subscription;
ngOnInit() { this.sub = this.someService.data$.subscribe(data => this.data = data); }
ngOnDestroy() { this.sub.unsubscribe(); }
```

**`takeUntil` pattern (preferred for multiple subscriptions):**
```typescript
private destroy$ = new Subject<void>();
ngOnInit() {
  this.someService.data$.pipe(takeUntil(this.destroy$)).subscribe(data => this.data = data);
}
ngOnDestroy() { this.destroy$.next(); this.destroy$.complete(); }
```

**`async` pipe (best — see Section 13 for full benefits):**
```html
<div *ngIf="data$ | async as data">{{ data.name }}</div>
```

Note: `HttpClient` calls auto-complete after one emission, so strictly you don't *have* to unsubscribe from a simple `http.get()` — but it's good practice for consistency.

### Operators

Pure functions that take an Observable and return a new one, used inside `.pipe()`.

```typescript
this.searchTerm$.pipe(
  debounceTime(300),
  distinctUntilChanged(),
  switchMap(term => this.searchService.search(term))
).subscribe(results => this.results = results);
```

**By category:**
- **Transformation** — `map`, `pluck`, `scan`
- **Filtering** — `filter`, `take`, `debounceTime`, `distinctUntilChanged`
- **Combination** — `merge`, `combineLatest`, `forkJoin`, `zip`
- **Error handling** — `catchError`, `retry`
- **Utility** — `tap` (side effects without altering the stream)

**Commonly used ones to name in an interview** (with the reason for each):
- `map` — transform an emitted value
- `switchMap` — cancel a previous inner Observable, switch to a new one (typeahead search — avoids stale requests)
- `mergeMap` — run multiple inner Observables concurrently
- `concatMap` — run inner Observables sequentially, in order
- `debounceTime` — wait for a pause before emitting (search inputs)
- `distinctUntilChanged` — ignore consecutive duplicates
- `catchError` — handle errors gracefully
- `tap` — logging/analytics side effects
- `takeUntil` — auto-complete/unsubscribe cleanup pattern
- `combineLatest` / `forkJoin` — combine multiple streams

### Installing RxJS

Bundled automatically with the Angular CLI. Standalone install: `npm install rxjs`.
```typescript
import { Observable, of, Subject } from 'rxjs';
import { map, filter, switchMap } from 'rxjs'; // RxJS 7+ allows direct import
```

### Where RxJS is actually used in Angular

- HTTP calls (`HttpClient`)
- Reactive Forms (`valueChanges`, `statusChanges`)
- Router (`ActivatedRoute.params`, `queryParams`)
- Search/typeahead (`debounceTime` + `distinctUntilChanged` + `switchMap`)
- Shared state between components (`BehaviorSubject` in a service)
- WebSocket / real-time data
- Combining multiple API calls (`forkJoin`)

### Push (Reactive) vs Pull (Imperative)

About **who initiates the flow of data**.

| | Producer | Consumer | Example |
|---|---|---|---|
| **Pull** | Passive (waits to be asked) | Active (decides when to get data) | Functions, Iterators |
| **Push** | Active (sends data whenever ready) | Passive (reacts when data arrives) | Promises, Observables, `EventEmitter` |

**Interview line:** "Angular's whole reactive model — Observables, EventEmitter, Reactive Forms — is push-based: components don't poll for changes, they react when the producer pushes a new value."

---

## 13. Change Detection & Performance

### `NgZone`

**Zone.js** lets Angular detect when async operations (events, `setTimeout`, HTTP, Promises) complete, so it knows when to run change detection. `NgZone` is Angular's wrapper around this.

**`runOutsideAngular()`** — run code outside Angular's zone so it does NOT trigger change detection on every event (useful for high-frequency events: scroll, mouse move, frequent WebSocket messages):
```typescript
constructor(private ngZone: NgZone) {}
ngOnInit() {
  this.ngZone.runOutsideAngular(() => {
    window.addEventListener('scroll', this.onScroll);
  });
}
```

**`run()`** — re-enter Angular's zone to trigger change detection when needed:
```typescript
this.ngZone.run(() => { this.someBoundProperty = newValue; });
```

### `async` pipe

Subscribes to an Observable/Promise directly in the template and auto-unwraps the value.

```html
<div *ngIf="user$ | async as user">{{ user.name }}</div>
<li *ngFor="let item of items$ | async">{{ item.name }}</li>
```

**Benefits:**
- **Automatic subscription management** — subscribes on render, **unsubscribes automatically on destroy**, eliminating a major memory-leak source.
- Less boilerplate — no manual `subscribe()`/`ngOnDestroy` cleanup needed for that stream.
- Plays well with `OnPush` — marks the component for check when a new value arrives.
- Handles `null` gracefully with `*ngIf="... as x"`.

### Why the UI might not update after a variable change

1. **`OnPush` change detection** and the `@Input()` reference hasn't changed (mutated in place instead of replaced).
2. **Change happened outside Angular's zone** (third-party library callback, detached `NgZone`).
3. **Wrong variable bound** — typo, binding to the wrong property.
4. **Update happens somewhere Angular doesn't automatically check.**

**Fixes:** immutable updates so `OnPush` catches reference changes; `ChangeDetectorRef.detectChanges()`/`markForCheck()`; wrap in `NgZone.run()`.

### Preventing unnecessary re-renders / general performance optimization

Consolidated list (this comes up across many interview questions — memorize as one checklist):

- **Lazy load** feature modules/routes.
- **`OnPush` change detection** — only re-checks when `@Input()` references change or an internal event fires.
  ```typescript
  @Component({ changeDetection: ChangeDetectionStrategy.OnPush })
  ```
- **Immutable data updates** — replace objects/arrays instead of mutating.
- **`trackBy` with `*ngFor`.**
- **Pure pipes instead of method calls in templates** (methods re-run every cycle; pure pipes only on input change).
- **`async` pipe** for automatic subscription management.
- **AOT compilation + production builds** (`ng build --configuration production`).
- **Virtual scrolling** (`cdk-virtual-scroll`) for long lists.
- **Bundle analysis** (`webpack-bundle-analyzer`/`source-map-explorer`) to remove unused dependencies.
- **`NgZone.runOutsideAngular()`** for high-frequency non-UI-critical events.
- **Image optimization** (`NgOptimizedImage`, lazy-loaded images).
- **Cache API responses** where appropriate (`shareReplay`).

---

## 14. Application Architecture at Scale

### Building a large-scale Angular app

- **Modular structure** — `CoreModule` (singleton services, guards, interceptors — imported once), `SharedModule` (reusable pieces), multiple **feature modules** (often lazy-loaded).
- **Lazy load** every feature module by route.
- **Smart vs. dumb (presentational) components** — containers manage state/data-fetching; presentational components just take `@Input()`/emit `@Output()`, staying reusable and testable.
- **State management** — NgRx (Redux-style) for complex shared state, or a simpler service + `BehaviorSubject` pattern for smaller needs.
- **Consistent folder conventions** (`core/`, `shared/`, `features/`).
- **Strict TypeScript** (`strict: true`) to catch bugs early at scale.
- **Angular's official style guide** — naming conventions, single responsibility per file.
- **Automated testing** — unit tests (Jasmine/Karma or Jest), e2e (Cypress/Playwright) for critical flows.
- **Monorepo tooling (Nx)** — if the app is split into multiple apps/libraries sharing code.

### Handling version updates and migrations

- **`ng update`** — official CLI upgrade tool; runs schematics that auto-rewrite code for breaking changes.
- **Follow the official Angular Update Guide** (update.angular.io) — Angular supports upgrading **one major version at a time**, so large jumps should be incremental.
- Check changelogs/breaking changes before upgrading.
- Run the full test suite after each step.
- Update third-party dependencies (Material, ngrx) in step with the Angular version.
- Use a feature branch; upgrade incrementally to isolate what broke.
- Watch for deprecations — Angular typically deprecates one version before removal.

---

## 15. PWA & Offline Support

### Implementing a PWA in Angular

```bash
ng add @angular/pwa
```
This automatically adds:
- A **service worker** (`ngsw-worker.js`) for offline caching.
- A **`manifest.webmanifest`** (name, icons, theme — for "Add to Home Screen").
- Icon assets and `index.html`/`angular.json` updates.

Configure caching in `ngsw-config.json`. Register in production only:
```typescript
provideServiceWorker('ngsw-worker.js', {
  enabled: !isDevMode(),
  registrationStrategy: 'registerWhenStable:30000'
})
```
Build and serve over HTTPS (service workers require a secure context): `ng build --configuration production`.

**Why it matters:** offline support, faster repeat loads, installability, push notifications — without a separate native app.

### Handling offline scenarios where API calls fail

- **`catchError`** to gracefully handle failed requests:
  ```typescript
  this.http.get('/api/data').pipe(
    catchError(err => { console.error('Request failed, using cached data', err); return of(this.cachedData); })
  ).subscribe(data => this.data = data);
  ```
- **`retry`/`retryWhen`** to auto-retry failed requests.
- **Service Worker/PWA** to cache assets and API responses.
- **`navigator.onLine`** + `online`/`offline` browser events for connectivity UI.
- **Local storage/IndexedDB caching** as a fallback.
- **Queueing writes** made offline, synced when connectivity returns.

---

## 16. Debugging & Scenario-Based Questions

### Handling invalid form input

```typescript
this.form = new FormGroup({
  email: new FormControl('', [Validators.required, Validators.email])
});
```
```html
<input formControlName="email" />
<div *ngIf="form.get('email').invalid && form.get('email').touched">
  <span *ngIf="form.get('email').errors?.['required']">Email is required</span>
  <span *ngIf="form.get('email').errors?.['email']">Invalid email format</span>
</div>
```
`touched`/`dirty` flags prevent showing errors before interaction; disable submit with `[disabled]="form.invalid"`.

### A component's event isn't firing on click — checklist

1. Typo/mismatch in the event binding or method name.
2. Element disabled or covered (CSS z-index/overlay).
3. Zone issue (rare — manual DOM manipulation outside Angular).
4. Missing/incorrect binding syntax (`(click)="onClick()"` not `onClick`).
5. `this` context lost if the method reference was passed around outside Angular's own binding syntax.
6. Component not actually rendering (check a wrapping `*ngIf`, or console errors breaking the tree).

### Detecting changes as a user types

```html
<input (input)="onInputChange($event.target.value)" />
<!-- or -->
<input [(ngModel)]="searchTerm" (ngModelChange)="onSearchChange(searchTerm)" />
```
Preferred for anything non-trivial — Reactive Forms `valueChanges` (Observable-based, composes with RxJS operators):
```typescript
this.searchControl.valueChanges.pipe(debounceTime(300), distinctUntilChanged())
  .subscribe(value => this.performSearch(value));
```

### Optimizing fetch for large API datasets

- **Pagination** (`?page=1&size=20`) instead of fetching everything.
- **Server-side filtering/sorting.**
- **Virtual scrolling** on the frontend.
- **Debounced search** (`debounceTime` + `switchMap`).
- **Caching** (`shareReplay`, or a simple in-memory service cache).
- **Lazy loading of related/detail data** — fetch on expand/click, not upfront.
- **Compression** — ensure the backend gzips large JSON payloads.

```typescript
getUsers(): Observable<User[]> {
  if (!this.users$) { this.users$ = this.http.get<User[]>('/api/users').pipe(shareReplay(1)); }
  return this.users$;
}
```

### Debugging a reported memory leak

1. **Check for un-unsubscribed Observables** — especially subscriptions to long-lived services (`providedIn: 'root'`) without cleanup in `ngOnDestroy`.
2. **Chrome DevTools → Memory tab** — heap snapshots before/after navigating away; look for "detached DOM trees."
3. **Check manually added event listeners** not removed in `ngOnDestroy`.
4. **Check `setInterval`/`setTimeout`** not cleared.
5. **Look for detached DOM nodes** retained by closures or arrays/maps in a service that never shrink.
6. **Fix pattern:** consistently apply `takeUntil(this.destroy$)` or use the `async` pipe.

### Combining multiple API calls

**Independent, parallel — `forkJoin`:**
```typescript
forkJoin({
  users: this.http.get<User[]>('/api/users'),
  orders: this.http.get<Order[]>('/api/orders')
}).subscribe(({ users, orders }) => { this.users = users; this.orders = orders; });
```

**Dependent — `switchMap`:**
```typescript
this.userService.getUser(userId).pipe(
  switchMap(user => this.orderService.getOrdersForUser(user.id))
).subscribe(orders => this.orders = orders);
```

**Combine ongoing streams over time — `combineLatest`:**
```typescript
combineLatest([this.filter$, this.sort$]).pipe(
  switchMap(([filter, sort]) => this.api.getData(filter, sort))
).subscribe(data => this.data = data);
```

### Analyzing and improving slow rendering

1. **Profile first** — Chrome DevTools Performance tab + Angular DevTools.
2. **Check change detection frequency** — switch to `OnPush` where components re-check too often.
3. **Audit templates** for expensive method calls; move to pure pipes/precomputed properties.
4. **Audit `*ngFor`** — add `trackBy`, consider virtual scrolling.
5. **Check bundle size** — apply lazy loading if the initial bundle is large.
6. **Reduce DOM complexity** — deeply nested trees are expensive to check/render.
7. **`NgZone.runOutsideAngular()`** for high-frequency non-UI events.
8. **Verify AOT + production build** — dev builds are unoptimized.

---

## 17. Angular Version History — Basic to Advanced Evolution

*This is a favorite senior-level topic — structure your answer around **eras**, not a raw version list.*

### The four eras

| Era | Versions | Theme |
|---|---|---|
| **The Rewrite** | AngularJS (1.x) → Angular 2 | Complete rebuild in TypeScript |
| **Stabilization & Tooling** | Angular 4–7 | CLI, RxJS, Material maturity, synchronized versioning |
| **The Ivy Era** | Angular 8–15 | New rendering engine, smaller bundles, standalone components |
| **The Renaissance** | Angular 16–21+ | Signals, zoneless, new control flow, Vite/esbuild, SSR/hydration |

### AngularJS (1.x) → Angular 2: The Rewrite (2016)

**What changed:** A ground-up rewrite — TypeScript instead of plain JS, components instead of `$scope`/controllers.

**Why:** AngularJS's `$scope`-based two-way binding and "dirty checking" digest cycle didn't scale for large apps — performance degraded as watchers grew. Google wanted a component-based model and a typed language for better tooling at scale.

**How it helped:** Component architecture, much better performance, TypeScript's compile-time safety — the foundation everything since has built on.

**The cost:** no real migration path — many teams simply never rewrote, which is why AngularJS still runs in production at plenty of companies years past its end-of-life.

```typescript
// AngularJS: controller-based
angular.module('app').controller('UserCtrl', function($scope) { $scope.name = 'Shreya'; });

// Angular 2+: component-based
@Component({ selector: 'app-user', template: '<p>{{ name }}</p>' })
export class UserComponent { name = 'Shreya'; }
```

### Angular 4–7: Stabilization & Tooling (2017–2018)

- **v4** — smaller/faster compiled output; `*ngIf`/`else` syntax.
- **v5** — build optimizer; improved `HttpClient` (interceptors, typed responses, auto JSON parsing) replacing the old `Http` module.
- **v6** — **synchronized versioning** across core/CLI/Material, removing compatibility confusion; introduced `ng update`/`ng add`.
- **v7** — CLI prompts for routing/CSS, virtual scrolling + drag-and-drop in the CDK.

**Why this era mattered:** after the 2016 shock, this was about making Angular **trustworthy for production teams** — predictable releases and automated migration tooling.

### Angular 8–15: The Ivy Era (2019–2022)

**Ivy rendering engine** (opt-in v8, default v9) replaced the old "View Engine" compiler.
- **Why:** View Engine's output wasn't very tree-shakable, and compilation was slow.
- **How it helped:** smaller bundles, faster compilation, better error messages, enabled Angular DevTools.

**Differential loading (v8)** — modern JS bundles to modern browsers, legacy bundles to old ones.

**DI improvement (v9):**
```typescript
@Injectable({ providedIn: 'any' }) // unique instance per lazy-loaded module
export class FeatureFlagService {}
```

**v12–13:** View Engine fully removed — Ivy becomes the only compiler.

**Standalone components (v14 preview, v15 stable):**
- **Why:** `NgModule`s were consistently cited as Angular's biggest learning-curve pain point.
```typescript
@Component({
  selector: 'app-user', standalone: true,
  imports: [CommonModule, FormsModule], templateUrl: './user.component.html'
})
export class UserComponent {}
```
```typescript
// v15: bootstrap without any root NgModule
bootstrapApplication(AppComponent, { providers: [provideRouter(routes), provideHttpClient()] });
```

### Angular 16–21+: The Renaissance (2023–2026)

**Signals** (preview v16, stable v17, adopted across the framework by v20):
- **Why:** Zone.js-based change detection checks the *entire* tree on any async event — wasteful. Signals track exactly which state a template depends on.
```typescript
import { signal, computed, effect } from '@angular/core';
export class CounterComponent {
  count = signal(0);
  doubled = computed(() => this.count() * 2);
  increment() { this.count.update(c => c + 1); }
  constructor() { effect(() => console.log('Count changed to', this.count())); }
}
```
```html
<p>Count: {{ count() }}</p> <button (click)="increment()">+</button>
```

**Signal-based Inputs/Outputs/Queries (v17.1–v19):**
```typescript
user = input<User>();               // replaces @Input()
userSelected = output<User>();       // replaces @Output()
nameEl = viewChild<ElementRef>('nameRef'); // replaces @ViewChild()
```

**Built-in control flow `@if`/`@for`/`@switch`** (preview v17, stable v17.1):
- **Why:** old structural directives had real performance costs and forgettable `trackBy`.
```html
@if (user) { <div>{{ user.name }}</div> } @else { <p>No user</p> }
@for (item of items; track item.id) { <li>{{ item.name }}</li> } @empty { <li>No items</li> }
```
Compiled directly (not a directive) — faster by default, and `track` is required, harder to forget.

**Deferrable views `@defer`** (preview v17, stable v17.1):
```html
@defer (on viewport) {
  <heavy-comments-widget />
} @placeholder { <p>Comments will load when scrolled into view</p> } @loading (minimum 500ms) { <spinner /> }
```
Solves lazy-loading for widgets *within* a route, not just whole routes.

**Vite + esbuild** (default v17) — much faster dev/rebuild times than the old Webpack-based builder.

**SSR & Hydration** (stable v17, refined through v19) — **non-destructive hydration** reuses server-rendered DOM instead of tearing it down and re-rendering (which caused flicker). By v19, **incremental hydration** hydrates parts of the page on demand (paired with `@defer`).

**Zoneless Angular** (experimental v18, maturing v20–21):
- **Why:** Zone.js monkey-patches browser async APIs to detect changes — adds bundle size, overhead, and friction with third-party libraries. Signals made this unnecessary.
```typescript
bootstrapApplication(AppComponent, { providers: [provideExperimentalZonelessChangeDetection()] });
```

**Standalone by default (v19)** — new projects skip `NgModule`s entirely unless explicitly requested.

**Angular Material 3 stable + event replay (v19)** — updated design system; event replay captures interactions during hydration and replays them once hydration completes (previously silently dropped).

### Quick reference table

| Feature | Introduced | Stable | Problem it solved |
|---|---|---|---|
| TypeScript + Components | Angular 2 | Angular 2 | AngularJS scaling/performance |
| Synchronized versioning, `ng update` | Angular 6 | Angular 6 | Tooling compatibility confusion |
| Ivy rendering engine | Angular 8 (opt-in) | Angular 9 | Large bundles, slow compilation |
| Standalone components | Angular 14 (preview) | Angular 15 | NgModule boilerplate |
| Signals | Angular 16 (preview) | Angular 17 | Zone.js's coarse, whole-tree detection |
| `@if`/`@for`/`@switch` | Angular 17 (preview) | Angular 17.1 | Structural directive overhead, forgotten `trackBy` |
| `@defer` | Angular 17 (preview) | Angular 17.1 | No sub-route-level lazy loading |
| Vite + esbuild | — | Angular 17 (default) | Slow Webpack-based dev builds |
| SSR non-destructive hydration | — | Angular 17 | Hydration flicker, weak SSR |
| Zoneless change detection | Angular 18 (experimental) | maturing v20–21 | Zone.js overhead/unpredictability |
| Standalone by default | — | Angular 19 | Completing the NgModule-optional transition |

*Exact "current"/"latest" version numbers shift roughly every 6 months on Angular's release cycle — worth a quick check on angular.dev right before an interview if you want to cite the very latest version confidently.*

---

## 18. Interview Framing Tips

- **"What's changed in Angular recently?"** — good summary:
  > "The last few major versions have all pushed toward the same goal: making change detection more predictable and performant by moving from Zone.js's 'check everything' model to **Signals**, a fine-grained reactive primitive. Standalone components removed most NgModule boilerplate, the new `@if`/`@for`/`@switch` control flow replaced structural directives with compiler-native syntax, and SSR/hydration got a lot more solid. The build toolchain moved to Vite/esbuild for faster dev builds. All of it points toward an eventual **zoneless-by-default** Angular."

- **"Should you migrate an older app to these new features?"** — depends on team bandwidth/app size; `ng update` and official schematics automate a lot (e.g., `*ngIf`/`*ngFor` → `@if`/`@for`, NgModules → standalone), but a full signals/zoneless migration on a large legacy codebase is a real project — plan incrementally.

- For "why" questions (lazy loading, DI, RxJS, Ivy, Signals), always state the performance/maintainability payoff — that's what separates a senior answer from a junior one.

- Where relevant, connect Angular concepts to Java/Spring equivalents (DI, singleton services) — shows pattern-level thinking rather than memorized syntax, which fits a senior fullstack profile well.

- For RxJS-heavy questions, have a short code snippet ready — interviewers want to see `.pipe()` chains, not just definitions.

- For scenario/debugging questions (memory leaks, slow rendering, non-firing events), answer as a **structured checklist**, not a single guess — it demonstrates a systematic debugging process, which is exactly what's being evaluated.
