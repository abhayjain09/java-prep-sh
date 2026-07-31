import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { AuthService } from '../services/auth.service';

/**
 * `authGuard` — a FUNCTIONAL guard (`CanActivateFn`, Angular 14.2+), the
 * same modernization story as `authInterceptor`: no more `class
 * AuthGuard implements CanActivate` registered via `{ path, canActivate:
 * [AuthGuard] }` with the class resolved through DI; just a plain function
 * matching `CanActivateFn`'s signature, referenced directly in
 * `app.routes.ts` as `canActivate: [authGuard]`.
 *
 * Guards this module wires up: `orders/new` (OrderFormComponent) and
 * `orders/:id` (OrderDetailComponent) both require authentication — you
 * shouldn't be able to place or view an order without being "logged in"
 * (see AuthService's mock-login note). The `products` catalog stays public.
 *
 * RETURN TYPE — `boolean | UrlTree`: returning `true` allows navigation to
 * proceed unchanged. Returning a `UrlTree` (via `router.createUrlTree(...)`)
 * REDIRECTS instead of just blocking — the router treats it as "navigate
 * here instead," which is why we don't ALSO call `router.navigate()`
 * ourselves: doing both would race two navigations against each other.
 * Returning a bare `false` would silently cancel navigation with no
 * feedback to the user about why — a redirect to a real page (with a query
 * param the destination can read to show "please log in") is almost always
 * the better UX.
 *
 * WHAT THIS DOES *NOT* PROVE: passing this guard proves the browser has
 * SOME token stored, not that the token is valid or unexpired — that check
 * only really happens when the backend's Spring Security filter chain
 * validates the JWT signature/expiry server-side (Module 6). A guard is a
 * navigation-UX gate, not a security boundary, for exactly the same reason
 * `AuthService.hasRole()`'s comment explains for the Cancel button: anyone
 * can bypass client-side routing and hit the API directly.
 */
export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated()) {
    return true;
  }

  // `state.url` is the URL the user was trying to reach — passed through so
  // a real login page could redirect back after a successful login. This
  // module has no dedicated login page (see AuthService's mock-login note
  // and AppComponent's toolbar login buttons), so we redirect to the public
  // product catalog instead, with enough context in the query params for a
  // banner to explain why the navigation was redirected.
  return router.createUrlTree(['/products'], {
    queryParams: { authRequired: state.url },
  });
};
