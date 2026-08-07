import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute } from '@angular/router';
import { map, startWith } from 'rxjs';

import { AuthService } from '../../core/services/auth.service';
import { OrderService } from '../../core/services/order.service';
import { Order, OrderStatus } from '../../core/models/order.model';

/**
 * OrderDetailComponent — shows one order, its status, and a Cancel button
 * gated by both business rules (can this STATUS legally cancel?) and role
 * (is this USER allowed to cancel?).
 *
 * Reached via the lazy-loaded, guarded `orders/:id` route (see
 * `app.routes.ts` and `core/guards/auth.guard.ts`) — you cannot navigate
 * here without `authGuard` passing first.
 */
@Component({
  selector: 'app-order-detail',
  standalone: true,
  imports: [DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (currentOrder(); as order) {
      <section class="order-detail">
        <h2>Order {{ order.id }}</h2>
        <p>Customer: {{ order.customer.name }} ({{ order.customer.email }})</p>
        <p>Status: <strong>{{ order.status }}</strong></p>

        <table>
          <thead>
            <tr>
              <th>SKU</th>
              <th>Product</th>
              <th>Qty</th>
              <th>Price</th>
            </tr>
          </thead>
          <tbody>
            @for (line of order.lines; track line.product.sku) {
              <tr>
                <td>{{ line.product.sku }}</td>
                <td>{{ line.product.name }}</td>
                <td>{{ line.quantity }}</td>
                <td>{{ line.product.price | number: '1.2-2' }}</td>
              </tr>
            }
          </tbody>
        </table>

        <p>Total: {{ order.totalAmount | number: '1.2-2' }}</p>

        <!--
          Double-gated Cancel button:
          1. canTransitionToCancelled() mirrors OrderStatus.canTransitionTo
             from java-basics — only PENDING/CONFIRMED orders can legally
             move to CANCELLED. Hiding the button for a SHIPPED order isn't
             just UX polish; it reflects the exact same state machine the
             backend enforces, so the user isn't invited to attempt an
             action the backend will reject with 409 Conflict anyway.
          2. auth.hasRole('MANAGER') is a CLIENT-SIDE-ONLY convenience
             (see AuthService.hasRole's comment) — hiding the button from a
             CUSTOMER is UX, not security. The backend's Spring Security
             method security (Module 6) is what actually prevents a
             CUSTOMER's direct API call from succeeding.
        -->
        @if (canCancel()) {
          <button type="button" (click)="onCancel(order.id)" [disabled]="cancelling()">
            {{ cancelling() ? 'Cancelling...' : 'Cancel order' }}
          </button>
        }

        @if (cancelError()) {
          <p class="form-error">{{ cancelError() }}</p>
        }
      </section>
    } @else {
      <p>Loading order...</p>
    }
  `,
})
export class OrderDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly orderService = inject(OrderService);
  private readonly auth = inject(AuthService);

  readonly cancelling = signal(false);
  readonly cancelError = signal<string | null>(null);

  /**
   * `ActivatedRoute.paramMap` is itself an Observable that re-emits every
   * time the `:id` route param changes (e.g. navigating from
   * `/orders/1` to `/orders/2` without leaving this route's component
   * instance). Mapped down to just the id string and handed to
   * `OrderService.watchOrder`, which applies `switchMap` internally — see
   * that method's doc comment for why `switchMap` (not `mergeMap`) is the
   * right operator here: it cancels any in-flight request for a
   * previous id the moment a new id arrives, so a slow response for an id
   * the user has already navigated away from can never overwrite the
   * current one.
   */
  private readonly orderId$ = this.route.paramMap.pipe(map((params) => params.get('id')!));

  /**
   * `toSignal` bridges that Observable pipeline into a Signal so the
   * template reads it synchronously with no `| async` pipe and no manual
   * `OnDestroy` unsubscribe. `startWith(null)` gives the signal an immediate
   * "no order yet" state until the first HTTP response — handled by the
   * template's `@if (currentOrder(); as order) { ... } @else { loading...
   * }`.
   */
  private readonly liveOrder = toSignal(
    this.orderService.watchOrder(this.orderId$).pipe(startWith(null as Order | null)),
    { requireSync: true },
  );

  /**
   * Local override that reflects a just-completed cancel immediately,
   * without waiting for a fresh `watchOrder` emission (which would require
   * either a route re-navigation or a second HTTP round trip). `computed()`
   * prefers this override when present and otherwise falls back to the
   * live, route-driven value — a small but realistic example of combining
   * two signals into one derived read model.
   */
  private readonly cancelledOverride = signal<Order | null>(null);

  readonly currentOrder = computed(() => this.cancelledOverride() ?? this.liveOrder());

  canCancel(): boolean {
    const order = this.currentOrder();
    if (!order) {
      return false;
    }
    return canTransitionToCancelled(order.status) && this.auth.hasRole('MANAGER');
  }

  onCancel(orderId: string): void {
    this.cancelling.set(true);
    this.cancelError.set(null);
    this.orderService.cancelOrder(orderId).subscribe({
      next: (updated) => {
        this.cancelling.set(false);
        this.cancelledOverride.set(updated);
      },
      error: (err: unknown) => {
        this.cancelling.set(false);
        this.cancelError.set('Could not cancel this order — it may no longer be cancellable.');
        console.error(err);
      },
    });
  }
}

/**
 * Free function mirroring `OrderStatus.canTransitionTo` from java-basics,
 * scoped down to just the CANCELLED transition this UI cares about. Kept
 * as a plain function (not a method on `OrderStatus`, which is just a
 * string union with no runtime representation — see order.model.ts's
 * header comment on that choice). The exhaustive `switch` with no
 * `default` case means TypeScript flags this function if a new
 * `OrderStatus` member is ever added without updating it here — the same
 * "the compiler forces you to handle it" safety net java-basics'
 * EXERCISES.md exercise 6 asks you to verify for the Java enum.
 */
function canTransitionToCancelled(status: OrderStatus): boolean {
  switch (status) {
    case 'PENDING':
    case 'CONFIRMED':
      return true;
    case 'SHIPPED':
    case 'DELIVERED':
    case 'CANCELLED':
      return false;
  }
}
