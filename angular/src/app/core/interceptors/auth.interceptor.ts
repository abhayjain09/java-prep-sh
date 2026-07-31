import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';

import { AuthService } from '../services/auth.service';

/**
 * `authInterceptor` — a FUNCTIONAL interceptor (`HttpInterceptorFn`,
 * Angular 15+), not the older class-based `HttpInterceptor` interface.
 *
 * WHY FUNCTIONAL, NOT CLASS-BASED: the old pattern required declaring a
 * class implementing `HttpInterceptor`, registering it via a verbose
 * multi-provider (`{ provide: HTTP_INTERCEPTORS, useClass: AuthInterceptor,
 * multi: true }`), and — because it was a class — pulling dependencies
 * through constructor injection, which only works inside an Angular DI
 * context (a class instantiated by the injector). The functional form is
 * just a plain function matching `HttpInterceptorFn`'s signature
 * `(req, next) => Observable<HttpEvent>`, registered via
 * `provideHttpClient(withInterceptors([authInterceptor]))` in
 * `app.config.ts`. It uses `inject()` to reach into DI from inside a plain
 * function (Angular arranges for the injection context to be active while
 * the interceptor chain runs) — less boilerplate, easier to unit test (call
 * it directly with a fake `req`/`next`, no `TestBed` class instantiation
 * needed), and it's the direction Angular's own APIs (guards, resolvers,
 * interceptors) have all moved: functional-by-default since v14-15.
 *
 * SEQUENCE THIS PARTICIPATES IN (see
 * diagrams/interceptor-guard-sequence.md for the full picture): a
 * component calls `OrderService.getOrder(id)` -> `HttpClient` builds the
 * request -> every registered interceptor runs, in registration order,
 * before the request leaves the browser -> this interceptor reads the
 * current JWT from `AuthService` and clones the request with an
 * `Authorization: Bearer <token>` header -> the (eventual, real) backend's
 * Spring Security filter chain (`security/`, Module 6) validates that
 * token before the controller method runs.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  const token = auth.token();

  // Requests are IMMUTABLE — `HttpRequest` has no mutable `.headers` setter
  // you can assign to. `req.clone({...})` returns a new request object with
  // the given overrides merged in, leaving the original `req` untouched.
  // This mirrors the exact same "don't mutate, copy with changes" discipline
  // `CartStateService` uses for Signals and `Order.getLines()` uses for
  // defensive copies in java-basics — it shows up everywhere state is
  // meant to be predictable.
  const authorizedReq = token
    ? req.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : req;

  return next(authorizedReq).pipe(
    catchError((error: HttpErrorResponse) => {
      // A 401 means the backend rejected the token (expired, malformed, or
      // simply absent). PRODUCTION NOTE: a real app would typically attempt
      // a silent refresh-token exchange here before giving up (out of
      // scope for this module — it belongs with the rest of the JWT
      // lifecycle in security/, Module 6). For this module, we do the
      // simplest correct thing: drop the stale/invalid session and bounce
      // to a route that doesn't require auth, so the user isn't stuck on a
      // screen silently failing every request.
      if (error.status === 401) {
        auth.logout();
        router.navigate(['/products'], { queryParams: { sessionExpired: true } });
      }
      return throwError(() => error);
    }),
  );
};
