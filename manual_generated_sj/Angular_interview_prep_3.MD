# Angular Interview Prep — Fundamentals, Data Binding & Scenario-Based Questions

---

## Section 1: Angular Basics

### Q1: What is Angular?

Angular is a **TypeScript-based, component-driven front-end framework** (maintained by Google) for building single-page web applications. It provides a complete toolkit out of the box — routing, forms, HTTP client, dependency injection, testing utilities — rather than being just a rendering library like some alternatives.

### Q2: What are the key features of Angular?

- **Component-based architecture** — UI is built from reusable, self-contained components.
- **Two-way data binding** — `[(ngModel)]` keeps model and view in sync automatically.
- **Dependency Injection** — built-in DI system for managing services and their lifetimes.
- **TypeScript** — static typing catches errors at compile time.
- **RxJS integration** — reactive programming baked into HTTP, forms, router.
- **CLI tooling** — Angular CLI scaffolds projects, generates components, handles builds/tests.
- **Routing** — built-in client-side router with guards, lazy loading, resolvers.
- **Cross-platform** — same codebase basis for web, mobile (via Ionic/NativeScript), and desktop (via Electron).

### Q3: What is the difference between AngularJS and Angular?

| AngularJS (v1.x) | Angular (v2+) |
|---|---|
| Written in JavaScript | Written in TypeScript |
| Based on `$scope` and controllers | Based on components and classes |
| MVC architecture | Component-based architecture |
| Two-way binding via `ng-model`, performance issues with digest cycle | Improved change detection, better performance |
| No mobile support out of the box | Mobile-friendly, supports responsive/native app development |
| Not modular by default | Modular by design (NgModules / standalone components) |

**Key line for interview:** "They're not the same framework with a version bump — Angular is a complete rewrite. AngularJS is essentially legacy at this point."

### Q4: What is the role of TypeScript in Angular?

TypeScript is a superset of JavaScript that adds **static typing**, interfaces, decorators, and modern ES features compiled down to JS.

Angular relies on TypeScript for:
- **Type safety** — catches errors (wrong types, typos in property names) at compile time instead of runtime.
- **Decorators** — `@Component`, `@Injectable`, `@Input`, `@Output` are TypeScript decorators that Angular's compiler reads to understand metadata about classes.
- **Better tooling** — autocomplete, refactoring support, and self-documenting code via interfaces.
- **OOP features** — classes, interfaces, generics, access modifiers (`private`/`public`) map well to Angular's class-based component/service model.

### Q5: What is an Angular component?

A **component** controls a section ("view") of the UI. It's the fundamental building block of an Angular app.

```typescript
@Component({
  selector: 'app-user-card',
  templateUrl: './user-card.component.html',
  styleUrls: ['./user-card.component.css']
})
export class UserCardComponent {
  @Input() user: User;
  @Output() selected = new EventEmitter<User>();

  onSelect() {
    this.selected.emit(this.user);
  }
}
```

A component consists of:
- A **decorator** (`@Component`) with metadata (selector, template, styles).
- A **class** with the logic/state.
- A **template** (HTML) defining the view.
- Optional **styles** scoped to that component.

### Q6: How does Angular help in creating a single-page application (SPA)?

Angular provides everything needed to build an SPA out of the box:
- **Router** — swaps components in/out based on URL without a full page reload (`<router-outlet>`).
- **Component-based views** — each "page" is just a component, no server round-trip needed to render it.
- **HttpClient** — fetches data asynchronously from the backend as JSON, so only data (not full HTML) is exchanged.
- **Change detection** — automatically keeps the DOM in sync with the component's state as data changes.

Together, this lets the browser load the app once, then dynamically re-render views and fetch data without reloading the page — the definition of an SPA.

### Q7: If you want to display a list of items dynamically, how would you do it?

Use the `*ngFor` structural directive:

```html
<ul>
  <li *ngFor="let item of items; let i = index">
    {{ i + 1 }}. {{ item.name }}
  </li>
</ul>
```

```typescript
items = [{ name: 'Apple' }, { name: 'Banana' }, { name: 'Cherry' }];
```

**Good addition to mention:** for large or frequently-updating lists, use `trackBy` to help Angular efficiently re-render only changed items instead of destroying/recreating the whole list:

```html
<li *ngFor="let item of items; trackBy: trackById">{{ item.name }}</li>
```
```typescript
trackById(index: number, item: any) { return item.id; }
```

### Q8: What would be the possible reasons for not updating the UI after a variable change?

This is a common debugging/scenario question. Possible causes:

1. **Change detection strategy is `OnPush`** and the input reference hasn't changed (e.g., you mutated an array/object in place instead of creating a new reference).
2. **The change happened outside Angular's zone** — e.g., inside a `setTimeout` from a third-party library, a WebSocket callback, or after detaching from `NgZone` — so Angular doesn't know to run change detection.
3. **You're updating a plain variable, not something bound in the template** (typo in property name, or binding to the wrong variable).
4. **The update happens in a place Angular doesn't automatically check**, like a callback from a non-Angular API.

**Fixes to mention:**
- Use immutable updates (spread/clone) so reference changes are detected with `OnPush`.
- Manually trigger `ChangeDetectorRef.detectChanges()` or `markForCheck()`.
- Wrap the code in `NgZone.run(() => { ... })` if it happened outside Angular's zone.

### Q9: How to handle a scenario where a user enters invalid input in an Angular form?

Use **Reactive Forms** with validators, and display errors conditionally:

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

Key points:
- `touched`/`dirty` flags prevent showing errors before the user has interacted with the field.
- Custom validators can be written for business-specific rules (e.g., password strength).
- The submit button can be disabled with `[disabled]="form.invalid"`.

### Q10: How would you call an API to fetch data when a button is clicked?

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

`(click)` is Angular's **event binding** syntax — it calls the component method when the button is clicked.

---

## Section 2: Directives, Lifecycle & Data Binding

### Q11: What is a directive in Angular?

Covered in detail in the first prep doc — quick recap: a directive is a class that adds behavior to DOM elements. Three types: **Components** (directive with a template), **Structural** (`*ngIf`, `*ngFor` — change DOM structure), **Attribute** (`ngClass`, `ngStyle` — change appearance/behavior).

### Q12: What is a lifecycle hook in Angular?

Covered in detail earlier (Q5 of the first doc) — the sequence of methods Angular calls as a component is created, updated, and destroyed: `ngOnChanges → ngOnInit → ngDoCheck → ngAfterContentInit → ngAfterContentChecked → ngAfterViewInit → ngAfterViewChecked → ngOnDestroy`.

### Q13: What is the difference between `ngIf` and `ngSwitch`?

| `*ngIf` | `ngSwitch` |
|---|---|
| Shows/hides a single element based on a boolean condition | Shows one of several elements based on matching a value |
| Good for simple conditional rendering | Good for multi-way branching (like a switch-case) |
| `*ngIf="isLoggedIn"` | `[ngSwitch]="status"` with `*ngSwitchCase` for each option |

```html
<div [ngSwitch]="status">
  <p *ngSwitchCase="'loading'">Loading...</p>
  <p *ngSwitchCase="'error'">Something went wrong</p>
  <p *ngSwitchDefault>Ready</p>
</div>
```

**Rule of thumb:** two conditions (show/hide) → `*ngIf`; three or more mutually exclusive states → `ngSwitch`.

### Q14: How does data binding work in Angular?

Four types of data binding:

1. **Interpolation** (`{{ }}`) — component → view, one-way.
   ```html
   <h1>{{ title }}</h1>
   ```
2. **Property binding** (`[ ]`) — component → view, one-way, binds to DOM element properties.
   ```html
   <img [src]="imageUrl" />
   ```
3. **Event binding** (`( )`) — view → component, one-way, listens to DOM events.
   ```html
   <button (click)="onSave()">Save</button>
   ```
4. **Two-way binding** (`[( )]`) — combines property + event binding, keeps both in sync.
   ```html
   <input [(ngModel)]="username" />
   ```

### Q15: What is an Angular Module?

An `NgModule` is a container that groups related components, directives, pipes, and services, declaring what belongs together and what's available for use.

```typescript
@NgModule({
  declarations: [AppComponent, UserListComponent],
  imports: [BrowserModule, FormsModule, HttpClientModule],
  providers: [UserService],
  bootstrap: [AppComponent]
})
export class AppModule {}
```

**Worth mentioning:** modern Angular (14+) supports **standalone components**, which don't require being declared in an `NgModule` at all — each component declares its own imports. Many new projects (Angular 17+ especially) default to standalone and skip NgModules almost entirely, so it's worth knowing both approaches for interviews depending on the Angular version the role uses.

### Q16: How would you share data between parent and child component?

**Parent → Child:** `@Input()`
```typescript
// child
@Input() user: User;
```
```html
<!-- parent template -->
<app-user-card [user]="selectedUser"></app-user-card>
```

**Child → Parent:** `@Output()` + `EventEmitter`
```typescript
// child
@Output() userSelected = new EventEmitter<User>();
selectUser() { this.userSelected.emit(this.user); }
```
```html
<!-- parent template -->
<app-user-card (userSelected)="onUserSelected($event)"></app-user-card>
```

**Alternative for unrelated/distant components:** a shared **service** with a `BehaviorSubject`, so any component can subscribe to or update shared state without a direct parent-child relationship.

### Q17: A component's event is not triggering when clicked — what could be wrong?

Common causes to walk through (this is a debugging/scenario question, so structure your answer like a checklist):

1. **Typo/mismatch** in the event binding name or method name.
2. **The element is disabled** or covered by another element (CSS z-index/overlay issue).
3. **Change detection zone issue** — if the click handler is attached outside Angular (rare, but possible with manual DOM manipulation).
4. **The method isn't actually bound** — e.g., using `onClick` instead of `(click)="onClick()"` (missing parentheses/binding syntax), or forgetting the `()`.
5. **`this` context lost** — if the method was passed as a reference somewhere instead of called normally, `this` might not refer to the component instance (less common in Angular's own binding syntax, more common with plain JS event listeners).
6. **The component itself isn't rendering** — check if it's wrapped in an `*ngIf` that's false, or if there's a console error breaking the component tree.

### Q18: How to apply a directive to dynamically change the background colour of an element?

Custom attribute directive example:

```typescript
@Directive({
  selector: '[appHighlight]'
})
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

Or more simply for a static/conditional case, `ngStyle`/`ngClass` is often enough without writing a custom directive:
```html
<p [ngStyle]="{ backgroundColor: isActive ? 'lightgreen' : 'white' }">Status</p>
```

### Q19: How do you detect changes when a user types in an input field?

**Option 1 — Template-driven, with event binding:**
```html
<input (input)="onInputChange($event.target.value)" />
```

**Option 2 — Two-way binding:**
```html
<input [(ngModel)]="searchTerm" (ngModelChange)="onSearchChange(searchTerm)" />
```

**Option 3 — Reactive Forms with `valueChanges` (preferred for anything beyond trivial, since it's Observable-based and composes with RxJS operators):**
```typescript
this.searchControl.valueChanges
  .pipe(debounceTime(300), distinctUntilChanged())
  .subscribe(value => this.performSearch(value));
```

### Q20: How to prevent unnecessary re-renders in an Angular application?

- **`OnPush` change detection** — only re-checks the component when `@Input()` references change or an event originates from within it.
  ```typescript
  @Component({ changeDetection: ChangeDetectionStrategy.OnPush })
  ```
- **Immutable data updates** — replace objects/arrays instead of mutating them, so reference-based checks (`OnPush`) actually catch the change.
- **`trackBy` with `*ngFor`** — avoids destroying/recreating unchanged DOM nodes in lists.
- **Pure pipes instead of methods in templates** — calling a method in a template re-runs it on every change detection cycle; a pure pipe only recomputes when its input changes.
- **Avoid complex logic/function calls directly in templates** — precompute values in the component class instead.
- **`async` pipe** — manages subscriptions efficiently and integrates cleanly with change detection.

---

## Section 3: Advanced / Scenario-Based Questions

### Q21: What is Dependency Injection (DI) in Angular?

Already covered in detail in the first doc (Q8) — Angular's mechanism for supplying a class with its dependencies (usually services) rather than the class creating them itself, managed through an injector hierarchy (root → module → component).

### Q22: What are Angular services and why are they used?

Already covered in detail (Q7, first doc) — reusable classes for logic/data not tied to a specific view (API calls, shared state, logging), registered via `@Injectable`, promoting separation of concerns and testability.

### Q23: What is lazy loading in Angular?

Already covered in detail (Q4, first doc) — loading a feature module's code only when its route is visited, reducing the initial bundle size.

### Q24: How do you optimize an Angular application for better performance?

A strong "senior-level" answer combines several techniques:

- **Lazy load feature modules/routes** to reduce initial bundle size.
- **`OnPush` change detection** to reduce unnecessary checks.
- **`trackBy` in `*ngFor`** for efficient list rendering.
- **Use `async` pipe** to auto-manage subscriptions and avoid manual leaks.
- **AOT (Ahead-of-Time) compilation** — enabled by default in production builds, compiles templates at build time instead of in the browser.
- **Tree-shaking / production builds** (`ng build --configuration production`) to strip unused code and minify.
- **Virtual scrolling** (`cdk-virtual-scroll`) for very long lists, so only visible items are rendered.
- **Image optimization / lazy-loading images**, using the `NgOptimizedImage` directive.
- **Bundle analysis** (`webpack-bundle-analyzer` or `source-map-explorer`) to find and remove large unused dependencies.
- **Avoid heavy computation in templates** — precompute in the component or use pure pipes.

### Q25: What is an Observable in Angular and how does it differ from a Promise?

Already covered in full detail in the RxJS doc (Q8) — Observables can emit multiple values over time, are lazy (only run on `subscribe()`), and are cancellable (`unsubscribe()`); Promises resolve once, execute eagerly, and can't be cancelled.

### Q26: You have an API with large amounts of data. How would you optimize data fetching?

Multiple valid strategies to mention together:

- **Pagination** — fetch data in pages (`?page=1&size=20`) instead of all at once.
- **Server-side filtering/sorting** — push filtering logic to the backend query instead of fetching everything and filtering client-side.
- **Virtual scrolling** on the frontend to render only visible rows even if more data is loaded.
- **Debounced search** — avoid firing a request on every keystroke (`debounceTime` + `switchMap`).
- **Caching** — cache responses (e.g., using RxJS `shareReplay`, or a simple in-memory cache in a service) to avoid redundant calls for the same data.
- **Lazy loading of related data** — fetch details only when the user expands/clicks into an item, not upfront.
- **Compression** — ensure the backend gzips large JSON payloads.

```typescript
// Example: caching with shareReplay
getUsers(): Observable<User[]> {
  if (!this.users$) {
    this.users$ = this.http.get<User[]>('/api/users').pipe(shareReplay(1));
  }
  return this.users$;
}
```

### Q27: A user reports a memory leak in an Angular application. How would you debug it?

Structured debugging approach (good to narrate step-by-step in an interview):

1. **Check for un-unsubscribed Observables** — subscriptions to services, `Subject`s, or intervals that never call `unsubscribe()` in `ngOnDestroy`. Look especially at long-lived services (`providedIn: 'root'`) that components subscribe to.
2. **Use Chrome DevTools → Memory tab** — take heap snapshots before/after navigating away from a component; compare to see if component instances are being retained ("detached DOM trees" is the classic symptom).
3. **Check event listeners** — manually added listeners (`addEventListener`, third-party library bindings) that aren't removed in `ngOnDestroy`.
4. **Check `setInterval`/`setTimeout`** that aren't cleared.
5. **Look for detached DOM nodes** retained by closures, or components stored in a service's array/map that never get removed.
6. **Fix pattern:** consistently apply `takeUntil(this.destroy$)` or use the `async` pipe to let Angular manage subscription lifecycles automatically.

### Q28: How would you handle multiple API calls and return the combined result?

Depends on whether the calls are independent or dependent:

**Independent calls, run in parallel — `forkJoin`:**
```typescript
forkJoin({
  users: this.http.get<User[]>('/api/users'),
  orders: this.http.get<Order[]>('/api/orders')
}).subscribe(({ users, orders }) => {
  this.users = users;
  this.orders = orders;
});
```

**Dependent calls, where one depends on the result of another — `switchMap`:**
```typescript
this.userService.getUser(userId).pipe(
  switchMap(user => this.orderService.getOrdersForUser(user.id))
).subscribe(orders => this.orders = orders);
```

**Combine emissions of ongoing streams over time — `combineLatest`:**
```typescript
combineLatest([this.filter$, this.sort$]).pipe(
  switchMap(([filter, sort]) => this.api.getData(filter, sort))
).subscribe(data => this.data = data);
```

### Q29: An app needs to handle offline scenarios where API calls fail. How would you do it?

- **`catchError`** in the RxJS pipe to gracefully handle failed requests instead of breaking the stream:
  ```typescript
  this.http.get('/api/data').pipe(
    catchError(err => {
      console.error('Request failed, using cached data', err);
      return of(this.cachedData);
    })
  ).subscribe(data => this.data = data);
  ```
- **`retry`/`retryWhen`** operators to automatically retry failed requests (e.g., retry 3 times with backoff before giving up).
- **Service Worker / Angular PWA support** (`@angular/pwa`) — cache assets and API responses so the app remains usable offline.
- **`navigator.onLine`** and the `online`/`offline` browser events to detect connectivity changes and show a UI banner.
- **Local storage / IndexedDB caching** — store the last successful response and fall back to it when offline.
- **Queueing writes** — if the user performs an action offline (e.g., submits a form), queue it locally and sync when connectivity returns.

### Q30: What steps would you take to analyse and improve the performance of slow rendering?

1. **Profile first** — use Chrome DevTools Performance tab and Angular DevTools to identify which components are re-rendering excessively or taking long to render.
2. **Check change detection frequency** — look for components triggering change detection too often; switch to `OnPush` where possible.
3. **Check for expensive operations in templates** — method calls or complex expressions evaluated on every change detection cycle; move them to pure pipes or precomputed properties.
4. **Audit `*ngFor` usage** — add `trackBy`, consider virtual scrolling for long lists.
5. **Check bundle size** — a large initial bundle slows down first render; apply lazy loading.
6. **Reduce DOM complexity** — deeply nested components/large DOM trees are expensive to check and render.
7. **Use `NgZone.runOutsideAngular()`** for high-frequency events (e.g., mouse move, scroll) that don't need to trigger Angular's change detection every time.
8. **Verify AOT + production build** is being used, since dev builds are unoptimized.

---

## Notes
- The "companies hiring Angular developers" and "salaries" segments from the source aren't technical interview content, so they're skipped here — happy to help with either separately if useful (e.g., researching current Angular developer salary ranges).
- Timestamps from the original source were dropped per your note, since they were copied from a video reference and aren't relevant to the content itself.
