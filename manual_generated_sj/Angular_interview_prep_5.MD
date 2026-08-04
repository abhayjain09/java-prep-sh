# Angular Interview Prep — Tooling, Pipes, DI Internals & TypeScript Fundamentals

*Many topics in these two videos overlap with the earlier prep docs (Angular vs AngularJS, directives, data binding, SPA, routing, lazy loading, services, DI, lifecycle hooks, Observable vs Promise, RxJS, HttpClient) — those are covered in full detail earlier, so this doc focuses on what's genuinely new: tooling, decorators, pipes, DI internals, and TypeScript basics.*

---

## Section 1: Tooling

### Q1: What is NPM, and what is `node_modules`?

**NPM (Node Package Manager)** is the default package manager for JavaScript/Node.js. It's used to install, manage, and publish libraries and their dependencies.

- `package.json` — lists your project's dependencies, scripts, and metadata.
- `npm install` — reads `package.json` and downloads all listed dependencies (and their sub-dependencies).
- **`node_modules/`** — the folder where all installed packages actually live on disk. It's typically huge (thousands of files) since every dependency's dependencies get installed too. It's excluded from version control (`.gitignore`) since it can be regenerated from `package.json` + `package-lock.json`.
- **`package-lock.json`** — locks exact installed versions so installs are reproducible across machines/CI.

```bash
npm install          # installs everything from package.json
npm install lodash   # adds a new dependency
npm install --save-dev jest   # dev-only dependency (testing, build tools)
```

### Q2: What is the Angular CLI, and what does it do?

The **Angular CLI** is a command-line tool for scaffolding, building, testing, and maintaining Angular projects, so you don't have to hand-configure Webpack/TypeScript/testing setup yourself.

Common commands:
```bash
ng new my-app              # scaffold a new project
ng generate component user # generate a component (also: ng g c user)
ng generate service auth   # generate a service (also: ng g s auth)
ng serve                   # run a local dev server
ng build                   # build for production
ng test                    # run unit tests
ng lint                    # run linter
ng update                  # upgrade Angular and dependencies
```

### Q3: `ng serve` vs `ng build` — what's the difference?

| `ng serve` | `ng build` |
|---|---|
| Starts a local **development server** (default: `localhost:4200`) | Compiles the app into static output files |
| Watches files and **auto-reloads** on changes (great for development) | Produces a `dist/` folder with HTML/JS/CSS ready to deploy |
| Output is kept **in memory**, not written to disk | Output is written to disk, meant for deployment |
| Not optimized (fast rebuilds, unminified, easier debugging) | Optimized for production (`--configuration production` minifies, tree-shakes, applies AOT) |

```bash
ng serve                                  # local dev
ng build --configuration production       # deployable production build
```

**Interview line:** "`ng serve` is for local development with live reload; `ng build` is what you actually deploy — you'd never `ng serve` on a production server."

---

## Section 2: Decorators

### Q4: What is a decorator in Angular?

A **decorator** is a TypeScript feature — a special kind of declaration (`@SomethingLikeThis`) that attaches metadata to a class, property, method, or parameter. Angular's compiler reads this metadata to understand how to treat that piece of code.

```typescript
@Component({
  selector: 'app-user',
  template: '<p>{{ name }}</p>'
})
export class UserComponent {}
```
Without the `@Component` decorator, `UserComponent` would just be a plain TypeScript class Angular knows nothing about — the decorator is what turns it into something Angular's framework can render.

### Q5: What are the types of decorators in Angular?

1. **Class decorators** — applied to a whole class, defining what kind of Angular building block it is.
   - `@Component` — defines a component (view + logic).
   - `@NgModule` — defines a module.
   - `@Injectable` — marks a class as available for dependency injection (typically a service).
   - `@Directive` — defines a directive.
   - `@Pipe` — defines a pipe.

2. **Property decorators** — applied to a class property.
   - `@Input()` — marks a property as receiving data from a parent component.
   - `@Output()` — marks a property (an `EventEmitter`) as emitting events to a parent.
   - `@ViewChild()` / `@ContentChild()` — get a reference to a child element/component/directive.

3. **Method decorators** — applied to a class method.
   - `@HostListener()` — binds a method to a DOM event on the host element.

4. **Parameter decorators** — applied to a constructor parameter, controlling DI resolution.
   - `@Inject()` — explicitly specify what to inject (useful with `InjectionToken`s).
   - `@Optional()` — don't throw if the dependency isn't found; inject `null` instead.
   - `@Self()` / `@SkipSelf()` — control which injector level to resolve from.

---

## Section 3: Pipes

### Q6: What are the types of pipes, and what are parameterized pipes?

**Pipes** transform data for display in a template without changing the underlying data.

**Built-in pipes:**
- `DatePipe` — `{{ today | date }}`
- `CurrencyPipe` — `{{ price | currency }}`
- `UpperCasePipe` / `LowerCasePipe` — `{{ name | uppercase }}`
- `DecimalPipe` — `{{ value | number }}`
- `PercentPipe` — `{{ ratio | percent }}`
- `JsonPipe` — `{{ obj | json }}` (great for quick debugging in templates)
- `AsyncPipe` — unwraps Observables/Promises directly in the template (covered in detail in the architecture prep doc).

**Custom pipes** — created with `@Pipe`:
```typescript
@Pipe({ name: 'truncate' })
export class TruncatePipe implements PipeTransform {
  transform(value: string, limit: number = 20): string {
    return value.length > limit ? value.substring(0, limit) + '...' : value;
  }
}
```

**Parameterized pipes** — pipes that accept arguments using a colon `:` syntax to customize the transformation:
```html
{{ birthday | date:'shortDate' }}
{{ price | currency:'EUR' }}
{{ description | truncate:50 }}
```
Multiple parameters:
```html
{{ amount | currency:'USD':'symbol':'1.2-2' }}
```

**Pure vs impure pipes (worth mentioning):**
- **Pure pipes** (default) — only re-run when the *reference* of the input changes. Efficient.
- **Impure pipes** (`@Pipe({ name: 'x', pure: false })`) — re-run on **every** change detection cycle, regardless of whether input actually changed. Needed for things like filtering an array in place, but expensive — use sparingly.

### Q7: What is pipe chaining?

You can apply multiple pipes in sequence, where each pipe's output feeds into the next:

```html
{{ user.name | uppercase | slice:0:10 }}
{{ birthday | date:'fullDate' | uppercase }}
```

Order matters — pipes are applied left to right.

---

## Section 4: Services & Dependency Injection Internals

### Q8: Explain services with an example (recap + practical example)

Already introduced earlier — practical example showing the full pattern:

```typescript
@Injectable({ providedIn: 'root' })
export class UserService {
  private apiUrl = '/api/users';
  constructor(private http: HttpClient) {}

  getUsers(): Observable<User[]> {
    return this.http.get<User[]>(this.apiUrl);
  }
}

@Component({ selector: 'app-user-list', templateUrl: './user-list.component.html' })
export class UserListComponent implements OnInit {
  users: User[] = [];
  constructor(private userService: UserService) {}
  ngOnInit() {
    this.userService.getUsers().subscribe(users => this.users = users);
  }
}
```

### Q9: How to create a service in Angular?

**Via CLI (recommended, generates boilerplate + registers it):**
```bash
ng generate service services/user
# or shorthand:
ng g s services/user
```

This creates `user.service.ts`:
```typescript
@Injectable({
  providedIn: 'root'
})
export class UserService {
  constructor() {}
}
```

`providedIn: 'root'` registers it as a singleton at the application root — no need to add it to any module's `providers` array manually.

### Q10: How to use Dependency Injection with services?

Simply declare the service as a constructor parameter with its type — Angular's injector resolves and provides an instance automatically:

```typescript
export class OrderComponent {
  constructor(private orderService: OrderService, private authService: AuthService) {}
}
```

No `new OrderService()` anywhere — Angular's injector handles instantiation and lifecycle.

### Q11: What is Hierarchical Dependency Injection?

Angular's DI system is organized as a **tree of injectors** that mirrors the component tree, plus a module-level and root-level injector above it:

```
Root injector (platform/app-wide singletons, providedIn: 'root')
   └── Module injector (services provided in an NgModule's `providers`)
         └── Component injector (services provided in a component's own `providers`)
               └── Child component injector (inherits from parent, can override)
```

**How resolution works:** when a component asks for a service, Angular looks in its own injector first; if not found there, it walks **up** the tree until it finds a provider (or throws an error if none exists).

**Why this matters practically:**
- A service registered at `providedIn: 'root'` → one single instance shared app-wide.
- A service registered in a specific component's `providers` array → a **new instance** created for that component (and shared by its children, unless a child also provides its own).

```typescript
@Component({
  selector: 'app-cart',
  providers: [CartService]  // new CartService instance scoped to this component subtree
})
export class CartComponent {}
```
This is useful when you want isolated state per component instance — e.g., a `CartService` where each instance of a "mini cart" widget on the page should have independent state, rather than sharing global state.

### Q12: What is a Provider in Angular?

A **provider** tells Angular's injector **how to create** a dependency when it's requested. The simplest form (`providedIn: 'root'` or a class in a `providers` array) just says "instantiate this class" — but providers support more flexible configurations:

```typescript
providers: [
  UserService,                                          // shorthand: useClass implied
  { provide: UserService, useClass: MockUserService },  // substitute implementation (great for testing)
  { provide: API_URL, useValue: 'https://api.example.com' }, // provide a static value
  { provide: Logger, useFactory: () => new Logger(isDevMode()) }, // provide via a factory function
  { provide: SomeToken, useExisting: AnotherService }   // alias to an existing provider
]
```

**Why this matters in interviews:** it's the mechanism that makes Angular's DI powerful for **testing** (swap real services for mocks) and **configuration** (inject environment-specific values) without changing the consuming component's code at all.

### Q13: What is the role of the `@Injectable` decorator?

`@Injectable()` marks a class as **available to be injected** by Angular's DI system, and (more importantly) tells Angular's compiler to process that class's **own constructor dependencies** too — so a service can itself depend on other services.

```typescript
@Injectable({ providedIn: 'root' })
export class OrderService {
  constructor(private http: HttpClient, private logger: LoggerService) {}
}
```

**Key point often missed:** technically, a class *can* be injected without `@Injectable()` if it has no constructor dependencies of its own — but it's considered best practice to always add it, since forgetting it becomes a bug the moment you add a dependency to that service's constructor later. `providedIn: 'root'` inside the decorator also enables **tree-shaking** — if the service is never actually injected anywhere in the app, it won't be included in the final bundle.

---

## Section 5: TypeScript Fundamentals

### Q14: What is TypeScript? (recap)

Already covered in the first prep doc — TypeScript is a statically-typed superset of JavaScript that compiles down to plain JS, adding type safety, interfaces, generics, and decorators, all of which Angular relies on heavily.

### Q15: Difference between `let` and `var`

| `var` | `let` |
|---|---|
| **Function-scoped** | **Block-scoped** (respects `{ }`, including `if`/`for` blocks) |
| Can be **redeclared** in the same scope | Cannot be redeclared in the same scope |
| **Hoisted** and initialized as `undefined` (usable, but `undefined`, before declaration) | Hoisted but not initialized — accessing before declaration throws a `ReferenceError` ("temporal dead zone") |
| Can leak out of blocks (classic source of bugs in loops) | Stays contained within the block it's declared in |

```typescript
for (var i = 0; i < 3; i++) { setTimeout(() => console.log(i), 0); }
// logs 3, 3, 3 — var leaks out of the loop block, all closures share the same i

for (let j = 0; j < 3; j++) { setTimeout(() => console.log(j), 0); }
// logs 0, 1, 2 — let creates a new binding per iteration
```

**Modern convention:** use `let` (or `const` when the value won't be reassigned) — `var` is largely considered legacy.

### Q16: Type annotation in TypeScript

**Type annotations** explicitly declare the type of a variable, parameter, or return value, so the compiler can catch type mismatches at compile time.

```typescript
let username: string = 'Shreya';
let age: number = 30;
let isActive: boolean = true;

function greet(name: string): string {
  return `Hello, ${name}`;
}
```

Without annotations, TypeScript still infers types where it can (`let x = 5` is inferred as `number`), but explicit annotations are important for function parameters, return types, and anywhere inference would be ambiguous (e.g., an empty array).

### Q17: Primitive and Non-Primitive types

**Primitive types** — basic, immutable values:
- `string`, `number`, `boolean`, `null`, `undefined`, `symbol`, `bigint`

**Non-primitive (reference) types** — objects that hold collections of values or more complex structures:
- `object`, arrays (`number[]` or `Array<number>`), classes, interfaces, functions

```typescript
let name: string = 'Shreya';        // primitive
let scores: number[] = [90, 85];     // non-primitive (array)
let user: { name: string; age: number } = { name: 'A', age: 30 }; // non-primitive (object)
```

**Key distinction:** primitives are compared/copied **by value**; non-primitives are compared/copied **by reference** — this is directly relevant to the earlier discussion of Angular's `OnPush` change detection (Q8 in the fundamentals doc), which relies on reference equality checks.

### Q18: What is the `any` type in TypeScript?

`any` opts a variable **out of type checking entirely** — it can hold any value, and the compiler won't flag type errors on it.

```typescript
let data: any = 'hello';
data = 42;         // no error
data.foo.bar.baz;  // no compile-time error, even though this would crash at runtime
```

**Why it's generally discouraged:** using `any` defeats the entire purpose of TypeScript's type safety. It's sometimes necessary (e.g., working with a poorly-typed third-party library, or during rapid prototyping), but a **strict codebase (`strict: true` in `tsconfig.json`)** typically restricts or bans `any` in favor of `unknown` (which forces you to narrow the type before using it) or proper interfaces.

### Q19: Enum type in TypeScript

An **enum** defines a set of named constants, making code more readable than using raw strings/numbers.

```typescript
enum OrderStatus {
  Pending,
  Shipped,
  Delivered,
  Cancelled
}

let status: OrderStatus = OrderStatus.Shipped;
```

By default, enum values are numbered starting at 0 (`Pending = 0`, `Shipped = 1`, ...). You can also use **string enums** for more readable debugging/serialization:
```typescript
enum OrderStatus {
  Pending = 'PENDING',
  Shipped = 'SHIPPED',
  Delivered = 'DELIVERED'
}
```

**Common Angular use case:** representing a fixed set of states (loading states, user roles, form modes) in a type-safe way instead of comparing raw strings prone to typos.

### Q20: Type Assertion in TypeScript

**Type assertion** tells the compiler "trust me, I know this value's type" — it doesn't change the value at runtime, only how TypeScript treats it at compile time.

```typescript
let someValue: unknown = 'this is a string';
let strLength: number = (someValue as string).length;

// alternative angle-bracket syntax (not usable in .tsx files)
let strLength2: number = (<string>someValue).length;
```

**Common Angular use case:** when working with DOM elements, since `document.getElementById()` returns a generic `HTMLElement | null`:
```typescript
const input = document.getElementById('username') as HTMLInputElement;
console.log(input.value);
```

**Caution to mention in interview:** type assertion is not the same as type conversion — it doesn't validate or transform the value, so an incorrect assertion can cause a runtime error that TypeScript won't catch.

### Q21: Arrow functions in TypeScript

Arrow functions are a shorter syntax for writing functions, with one crucial behavioral difference from regular functions: **they don't have their own `this`** — they inherit `this` from the enclosing (lexical) scope.

```typescript
// regular function
function add(a: number, b: number): number { return a + b; }

// arrow function
const add = (a: number, b: number): number => a + b;
```

**Why this matters specifically in Angular:** class methods used as callbacks (event handlers, RxJS subscriptions, `setTimeout`) can lose their `this` binding to the component instance if declared as regular methods and passed by reference. Arrow function class properties solve this automatically:

```typescript
export class MyComponent {
  count = 0;

  // regular method — if passed as a callback reference, `this` may not point to the component
  incrementRegular() { this.count++; }

  // arrow function property — always retains `this` from the component instance
  incrementArrow = () => { this.count++; };
}
```
```html
<button (click)="incrementArrow()">+</button>
```

This is also exactly why `@HostListener` callbacks and RxJS subscription callbacks are often written as arrow functions — to safely reference `this.someProperty` inside them.

---

### Cross-references to earlier prep docs
- **AngularJS vs Angular, SPA, routing, lazy loading, directives & their types, data binding (interpolation/property/event/two-way), lifecycle hooks, constructor vs `ngOnInit`, Promise vs Observable, RxJS, Observable, HttpClient** — all covered in full in the earlier three docs; not repeated here.
- This doc's unique contribution: **NPM/CLI tooling, decorators (all four types), pipes (built-in, custom, parameterized, pure/impure, chaining), DI internals (hierarchical injection, providers, `@Injectable`), and core TypeScript fundamentals** (`let`/`var`, type annotations, primitive vs non-primitive, `any`, enums, type assertion, arrow functions).
