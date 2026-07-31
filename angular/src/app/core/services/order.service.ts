import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, switchMap, throwError } from 'rxjs';

import { Order, PlaceOrderRequest } from '../models/order.model';

/**
 * OrderService — RxJS-based (see ProductService's header comment for the
 * Signals-vs-RxJS rationale, which applies identically here).
 *
 * ASSUMED API CONTRACT (see ProductService for the same disclaimer — this
 * is this module's own best-effort guess at spring/'s eventual contract,
 * written without reading spring/'s in-progress source):
 *   POST /api/v1/orders               body: PlaceOrderRequest -> Order
 *   GET  /api/v1/orders/{id}                                  -> Order
 *   POST /api/v1/orders/{id}/cancel   body: {}                -> Order
 *
 * `POST .../cancel` (rather than `PATCH /orders/{id}` with a status field)
 * models cancellation as an explicit domain *action*, matching
 * `Order.transitionTo(OrderStatus)` in java-basics: the backend, not the
 * client, decides whether PENDING/CONFIRMED -> CANCELLED is legal (mirrors
 * `OrderStatus.canTransitionTo`) and returns 409 Conflict if not — this
 * client only *requests* the transition and displays whatever comes back.
 */
@Injectable({ providedIn: 'root' })
export class OrderService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/orders';

  getOrder(id: string): Observable<Order> {
    return this.http.get<Order>(`${this.baseUrl}/${encodeURIComponent(id)}`).pipe(
      catchError((error: HttpErrorResponse) => this.rethrowWithContext('load order', error)),
    );
  }

  placeOrder(request: PlaceOrderRequest): Observable<Order> {
    return this.http.post<Order>(this.baseUrl, request).pipe(
      catchError((error: HttpErrorResponse) => this.rethrowWithContext('place order', error)),
    );
  }

  cancelOrder(id: string): Observable<Order> {
    return this.http.post<Order>(`${this.baseUrl}/${encodeURIComponent(id)}/cancel`, {}).pipe(
      catchError((error: HttpErrorResponse) => this.rethrowWithContext('cancel order', error)),
    );
  }

  /**
   * `switchMap` example, as called for by this module's brief: given a
   * STREAM of order ids (e.g. `ActivatedRoute.paramMap`, which re-emits
   * every time the `:id` route param changes — think a "next order" button
   * that navigates between `/orders/1` and `/orders/2` without leaving the
   * component), `switchMap` maps each id to a fresh `getOrder()` call AND
   * cancels/discards any in-flight previous request. That cancellation is
   * the entire reason to reach for `switchMap` over `mergeMap`/`concatMap`
   * here: if the id changes again before the first request resolves, you
   * want the LATEST id's data, not a stale response for an id the user has
   * already navigated away from (a classic race condition in naive
   * "fetch on param change" code that doesn't use switchMap).
   *
   * See `order-detail.component.ts` for the call site.
   */
  watchOrder(id$: Observable<string>): Observable<Order> {
    return id$.pipe(switchMap((id) => this.getOrder(id)));
  }

  private rethrowWithContext(action: string, error: HttpErrorResponse): Observable<never> {
    // Unlike ProductService (which degrades to an empty list on failure),
    // order actions are commands with side effects the user is waiting on
    // — silently swallowing a failed "place order" and showing nothing
    // would be actively misleading. Here we log for debugging and
    // re-throw so the component's `subscribe({ error: ... })` can show the
    // user an actual error message. This is the RxJS equivalent of Java's
    // "never swallow an exception silently" rule from java-basics'
    // Exception Handling section.
    console.error(`Failed to ${action}`, error);
    return throwError(() => error);
  }
}
