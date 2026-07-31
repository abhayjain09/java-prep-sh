import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { RouterLink } from '@angular/router';
import { debounceTime, distinctUntilChanged, switchMap } from 'rxjs';

import { CartStateService } from '../../core/services/cart-state.service';
import { ProductService } from '../../core/services/product.service';
import { Product } from '../../core/models/product.model';

/**
 * ProductListComponent — the product catalog, and this module's worked
 * example of `OnPush` + Signals/RxJS interop together.
 *
 * WHY `ChangeDetectionStrategy.OnPush` HERE SPECIFICALLY (see
 * README.md's Change Detection section for the full default-vs-OnPush
 * theory): this component has no `@Input()` bindings at all — every piece
 * of data it renders (`products`, `cart.itemCount`, `cart.total`) is a
 * Signal, either owned locally or read from an injected service. As of
 * Angular 17, a component's template reading a signal registers that
 * template with the signal's reactive graph — when the signal changes,
 * Angular schedules exactly that component (and its OnPush ancestors on
 * the path to root) for re-check, WITHOUT needing zone.js to tell it "some
 * async event happened somewhere, recheck everything." That's the concrete
 * mechanism behind README.md's forward-looking note on zoneless Angular:
 * Signal-driven, OnPush components already behave the way a zoneless app
 * would behave everywhere, today.
 *
 * DISCIPLINE THIS REQUIRES: because this is OnPush, if this component ever
 * gained an `@Input() products: Product[]`, mutating that array in place
 * from a parent (`this.products.push(newProduct)`) would NOT trigger a
 * re-render — OnPush compares `@Input()` references with `===`, and a
 * mutated-in-place array is still the same reference. The parent would
 * have to do `this.products = [...this.products, newProduct]` instead. See
 * `CartStateService.addItem()` for this exact "never mutate, always
 * replace" pattern already being followed for that reason.
 */
@Component({
  selector: 'app-product-list',
  standalone: true,
  imports: [RouterLink, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="product-list">
      <header class="product-list__header">
        <h2>Product Catalog</h2>
        <input
          type="search"
          placeholder="Search products by name..."
          aria-label="Search products"
          (input)="onSearchInput($any($event.target).value)"
        />
      </header>

      <!-- New Angular control-flow syntax (@if / @for), Angular 17+. -->
      @if (products().length === 0) {
        <p class="product-list__empty">No products found.</p>
      } @else {
        <table class="product-list__table">
          <thead>
            <tr>
              <th>SKU</th>
              <th>Name</th>
              <th>Price</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <!--
              `track product.sku`: REQUIRED (not optional like `trackBy` was
              with `*ngFor`) in the new `@for` syntax. It tells Angular's
              renderer which DOM node corresponds to which data item across
              re-renders, so re-ordering/filtering the array (e.g. from the
              search box below) moves/reuses existing <tr> elements instead
              of destroying and recreating every row. Tracking by `sku`
              (a stable business key) instead of array index means a
              re-sorted or filtered list still maps correctly to the SAME
              DOM nodes — tracking by index would misattribute rows after
              any reorder, causing input focus, animations, or component
              state within a row to jump to the wrong item.
            -->
            @for (product of products(); track product.sku) {
              <tr>
                <td>{{ product.sku }}</td>
                <td>{{ product.name }}</td>
                <td>{{ product.price | number: '1.2-2' }}</td>
                <td>
                  <button type="button" (click)="addToCart(product)">Add to cart</button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      }

      <footer class="product-list__footer">
        Cart: {{ cartCount() }} item(s) — {{ cartTotal() | number: '1.2-2' }}
        <a routerLink="/orders/new">Go to checkout</a>
      </footer>
    </section>
  `,
})
export class ProductListComponent {
  private readonly productService = inject(ProductService);
  private readonly cart = inject(CartStateService);

  /**
   * Local Signal holding the raw search-box text. Updated synchronously on
   * every keystroke via `onSearchInput` — a plain, local piece of UI state,
   * which is exactly the "reach for Signals" case.
   */
  readonly searchTerm = signal('');

  /**
   * `toObservable(signal)` (from `@angular/core/rxjs-interop`) converts a
   * Signal into an Observable that emits the signal's value every time it
   * changes. This is the bridge that lets a Signal (synchronous local
   * state) feed into RxJS operators that only make sense for a STREAM of
   * values over time — `debounceTime` and `distinctUntilChanged` here.
   *
   * `debounceTime(300)`: waits for 300ms of silence after the last
   * keystroke before letting a value through — without it, EVERY keystroke
   * would fire an HTTP request, wasting bandwidth/backend load and racing
   * responses against each other.
   *
   * `distinctUntilChanged()`: skips emitting if the new value equals the
   * previous one (e.g. typing then immediately backspacing back to the
   * same string) — avoids a redundant, identical search request.
   *
   * `switchMap`: maps each debounced search term to a fresh
   * `productService.searchProducts(term)` call, cancelling any still-
   * in-flight previous search. This is the same "cancel the stale request"
   * rationale documented on `OrderService.watchOrder` — critical here too,
   * since without it a slow response to an earlier keystroke could arrive
   * AFTER a faster response to a later keystroke and overwrite it with
   * stale results ("search result flicker back to an old query").
   */
  private readonly filteredProducts$ = toObservable(this.searchTerm).pipe(
    debounceTime(300),
    distinctUntilChanged(),
    switchMap((term) => this.productService.searchProducts(term)),
  );

  /**
   * `toSignal(observable, { initialValue })` converts that RxJS pipeline
   * back into a Signal so the template can read `products()` directly —
   * no `| async` pipe, no manual subscribe/unsubscribe/OnDestroy
   * bookkeeping. `initialValue: []` matters for OnPush: without it, the
   * signal would start `undefined` and the template's `.length` access
   * would throw before the first HTTP response arrives.
   *
   * This single field is the module's concrete answer to "how do Signals
   * and RxJS interoperate": Signal (searchTerm) -> Observable
   * (toObservable) -> RxJS operators (debounceTime/distinctUntilChanged/
   * switchMap calling an Observable-returning service method) -> Signal
   * again (toSignal) for template consumption.
   */
  readonly products = toSignal(this.filteredProducts$, { initialValue: [] });

  readonly cartCount = this.cart.itemCount;
  readonly cartTotal = this.cart.total;

  onSearchInput(value: string): void {
    this.searchTerm.set(value);
  }

  addToCart(product: Product): void {
    this.cart.addItem(product);
  }
}
