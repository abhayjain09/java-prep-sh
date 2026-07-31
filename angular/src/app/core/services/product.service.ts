import { HttpClient, HttpErrorResponse, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, catchError, map, of } from 'rxjs';

import { Product } from '../models/product.model';

/**
 * ProductService — RxJS-based, on purpose (contrast with CartStateService,
 * which is Signals-based). Every method here wraps an HTTP call: HTTP is
 * inherently asynchronous and one-shot per subscription, which is exactly
 * the shape RxJS Observables were designed for. See README.md's "Signals
 * vs RxJS" section for the full decision table.
 *
 * ASSUMED API CONTRACT (spring/ Module 5 owns the real implementation —
 * this module was built without reading spring/'s in-progress source, per
 * this module's own scope boundary; the contract below is this module's
 * best-effort, standard-REST guess and should be reconciled once spring/
 * lands):
 *   GET  /api/v1/products            -> Product[]
 *   GET  /api/v1/products/{sku}      -> Product
 *   GET  /api/v1/products?q={term}   -> Product[]  (search-by-name filter)
 */
@Injectable({ providedIn: 'root' })
export class ProductService {
  // `inject()` (function-based DI, Angular 14+) instead of constructor
  // injection. Both work identically in a standalone component/service —
  // `inject()` just reads better when a class has many dependencies or
  // when the dependency is only used to build a field initializer (as
  // `http` is used below). See README.md's DI section for the trade-offs
  // (constructor injection remains more discoverable at a glance and is
  // required for some legacy patterns / decorators).
  private readonly http = inject(HttpClient);
  private readonly baseUrl = '/api/v1/products';

  /**
   * Fetches the full product catalog.
   *
   * `.pipe(catchError(...))` demonstrates the standard RxJS error-handling
   * shape: a failed HTTP call becomes an `HttpErrorResponse` emitted as an
   * *error* on the Observable, not a value — `catchError` intercepts that
   * and returns a *replacement* Observable (here, `of([])`, an empty
   * catalog) so a network blip degrades to "no products shown" instead of
   * an unhandled exception bubbling into the component and breaking change
   * detection. In a real app you'd likely surface a toast/error banner
   * here instead of silently swallowing it — shown simply for clarity.
   */
  getProducts(): Observable<Product[]> {
    return this.http.get<Product[]>(this.baseUrl).pipe(
      catchError((error: HttpErrorResponse) => {
        console.error('Failed to load products', error);
        return of<Product[]>([]);
      }),
    );
  }

  getProduct(sku: string): Observable<Product | undefined> {
    return this.http.get<Product>(`${this.baseUrl}/${encodeURIComponent(sku)}`).pipe(
      map((product) => product), // `map` shown for symmetry/teaching; a no-op here on purpose
      catchError((error: HttpErrorResponse) => {
        console.error(`Failed to load product ${sku}`, error);
        return of(undefined);
      }),
    );
  }

  /**
   * Backend-filtered search, driven by `ProductListComponent`'s debounced
   * search box (see that component for the Signal -> `toObservable` ->
   * `debounceTime`/`switchMap` -> `toSignal` pipeline that calls this).
   * An empty/blank term falls back to the full catalog rather than sending
   * `?q=` to the server — fewer special cases for the backend to handle.
   */
  searchProducts(term: string): Observable<Product[]> {
    const trimmed = term.trim();
    if (!trimmed) {
      return this.getProducts();
    }
    const params = new HttpParams().set('q', trimmed);
    return this.http.get<Product[]>(this.baseUrl, { params }).pipe(
      catchError((error: HttpErrorResponse) => {
        console.error(`Product search failed for "${trimmed}"`, error);
        return of<Product[]>([]);
      }),
    );
  }
}
