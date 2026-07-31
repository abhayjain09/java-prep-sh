import { Routes } from '@angular/router';

import { authGuard } from './core/guards/auth.guard';

/**
 * `Routes` — the standalone-era route table. Notice there is no
 * `RouterModule.forRoot(routes)` / `@NgModule` anywhere in this app: since
 * Angular 14's standalone APIs (the default project shape from Angular 17
 * onward), `provideRouter(routes)` in `app.config.ts` is the entire router
 * setup. NgModules are not required anywhere in this codebase.
 *
 * LAZY LOADING — `loadComponent`: every route below loads its component via
 * a dynamic `import()` rather than a static top-of-file import. Angular's
 * builder splits each `loadComponent` target into its own JS chunk at
 * build time; that chunk is only fetched over the network the first time
 * the user navigates to that route. Compare this to eagerly importing
 * `ProductListComponent`/`OrderFormComponent`/`OrderDetailComponent` at
 * the top of this file (or worse, in `AppComponent`) — that would bundle
 * every feature into the initial JS payload the browser must download,
 * parse, and execute before ANYTHING renders, even if the user only ever
 * visits `/products`. This module lazy-loads every feature route (not just
 * the "at least one" the brief asks for) specifically to demonstrate that
 * essentially any standalone component route is a natural lazy-loading
 * boundary — the trade-off (see README.md's Routing/Lazy-Loading section)
 * is a small extra network round trip on FIRST navigation to a given
 * route, which is why very-likely-to-be-visited-immediately routes (e.g. a
 * marketing landing page) are sometimes deliberately kept eager instead.
 *
 * The OLDER `loadChildren: () => import('./feature/feature.module').then(m
 * => m.FeatureModule)` pattern lazy-loaded an entire NgModule (and every
 * component declared in it, whether needed yet or not). `loadComponent`
 * is more granular: it lazy-loads exactly one component, so a route that
 * only needs one component doesn't drag in siblings it doesn't use yet.
 *
 * GUARDS — `canActivate: [authGuard]`: applied to `orders/new` and
 * `orders/:id`. `products` deliberately has NO guard — browsing the
 * catalog doesn't require being "logged in," matching a typical e-commerce
 * UX (browse freely, authenticate at checkout).
 */
export const routes: Routes = [
  {
    path: '',
    pathMatch: 'full',
    redirectTo: 'products',
  },
  {
    path: 'products',
    title: 'Products',
    loadComponent: () =>
      import('./features/product-list/product-list.component').then(
        (m) => m.ProductListComponent,
      ),
  },
  {
    path: 'orders/new',
    title: 'Place Order',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/order-form/order-form.component').then((m) => m.OrderFormComponent),
  },
  {
    path: 'orders/:id',
    title: 'Order Detail',
    canActivate: [authGuard],
    loadComponent: () =>
      import('./features/order-detail/order-detail.component').then(
        (m) => m.OrderDetailComponent,
      ),
  },
  {
    // Wildcard fallback — must be LAST; Angular matches routes top-to-bottom
    // and this would otherwise swallow every other route.
    path: '**',
    redirectTo: 'products',
  },
];
