import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, OnInit, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormArray, FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';

import { positiveQuantityValidator } from '../../core/validators/positive-quantity.validator';
import { CartStateService } from '../../core/services/cart-state.service';
import { OrderService } from '../../core/services/order.service';
import { ProductService } from '../../core/services/product.service';
import { PlaceOrderRequest } from '../../core/models/order.model';

/**
 * OrderFormComponent — a REACTIVE form (`ReactiveFormsModule`), not a
 * template-driven one. See README.md's "Reactive vs template-driven forms"
 * section for the full trade-off table; the short version, specific to why
 * THIS form needed reactive forms:
 *
 * - The line-items list is DYNAMIC (add/remove a product row at runtime) —
 *   that's a `FormArray`, which reactive forms model as a first-class,
 *   directly-constructible object (`this.fb.array([...])`). Template-driven
 *   forms have no equivalent for a dynamically-sized array of controls
 *   without awkward workarounds.
 * - Validation logic (`positiveQuantityValidator`) is defined and unit-
 *   testable in a plain TypeScript file, independent of any template —
 *   with template-driven forms, validators are typically directives
 *   applied IN the template (e.g. `[min]="1"`), which is harder to unit
 *   test in isolation and harder to compose/reuse across forms.
 * - The whole form's value is available synchronously as a plain object
 *   (`this.form.value`) for building the API request — no need to bind
 *   every field with `[(ngModel)]` and assemble the request from scattered
 *   component properties.
 *
 * The trade-off: reactive forms require more upfront TypeScript
 * (constructing the `FormGroup`/`FormArray` structure here) versus template-
 * driven forms' "just add `ngModel` to an input and go" simplicity for a
 * TRULY trivial form (e.g. a single search box — see how
 * `product-list.component.ts` uses a plain signal + `(input)` event instead
 * of either forms API for exactly that reason: pulling in
 * `ReactiveFormsModule`/`FormsModule` for one input would be overkill).
 */
@Component({
  selector: 'app-order-form',
  standalone: true,
  imports: [ReactiveFormsModule, DecimalPipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="order-form">
      <h2>Place an Order</h2>

      <form [formGroup]="form" (ngSubmit)="onSubmit()">
        <label>
          Customer ID
          <input type="text" formControlName="customerId" placeholder="e.g. cust-001" />
        </label>
        @if (form.controls.customerId.touched && form.controls.customerId.invalid) {
          <p class="form-error">Customer ID is required.</p>
        }

        <table>
          <thead>
            <tr>
              <th>SKU</th>
              <th>Quantity</th>
              <th></th>
            </tr>
          </thead>
          <tbody formArrayName="lines">
            @for (lineGroup of lines.controls; track $index; let i = $index) {
              <tr [formGroupName]="i">
                <td><input type="text" formControlName="sku" placeholder="SKU" /></td>
                <td>
                  <input type="number" formControlName="quantity" min="1" step="1" />
                  @if (lineGroup.get('quantity')?.hasError('positiveQuantity')) {
                    <span class="form-error">Quantity must be a positive whole number.</span>
                  }
                </td>
                <td>
                  <button type="button" (click)="removeLine(i)">Remove</button>
                </td>
              </tr>
            }
          </tbody>
        </table>

        <button type="button" (click)="addBlankLine()">Add line</button>

        <p>Estimated total (client-side, for display only): {{ estimatedTotal() | number: '1.2-2' }}</p>

        <!--
          `form.invalid` disables submission entirely while any control
          fails validation (including our custom positiveQuantityValidator
          and the required/min-length checks on customerId and each line's
          sku). This is the reactive-forms equivalent of the "check before
          you leap" discipline OrderStatus.canTransitionTo demonstrates in
          java-basics — reject invalid input before it's ever sent, rather
          than relying solely on the backend's 400/409 response.
        -->
        <button type="submit" [disabled]="form.invalid || submitting()">
          {{ submitting() ? 'Placing order...' : 'Place order' }}
        </button>

        @if (submitError()) {
          <p class="form-error">{{ submitError() }}</p>
        }
      </form>
    </section>
  `,
})
export class OrderFormComponent implements OnInit {
  private readonly fb = inject(FormBuilder);
  private readonly cart = inject(CartStateService);
  private readonly orderService = inject(OrderService);
  private readonly productService = inject(ProductService);
  private readonly router = inject(Router);

  readonly submitting = signal(false);
  readonly submitError = signal<string | null>(null);

  // Exposed for symmetry/teaching — a real form might offer a product
  // picker (`<select>`) populated from this instead of a free-text SKU
  // field. Kept as a Signal via `toSignal` to show the same RxJS->Signal
  // bridge used in product-list, without duplicating that component's
  // debounced-search logic here.
  readonly availableProducts = toSignal(this.productService.getProducts(), { initialValue: [] });

  /**
   * The reactive form's root `FormGroup`. Built with `FormBuilder` (a thin
   * convenience wrapper over `new FormGroup({...})`/`new FormControl(...)`
   * — purely less boilerplate, not a different concept).
   */
  readonly form = this.fb.group({
    customerId: this.fb.control('', { validators: [Validators.required], nonNullable: true }),
    lines: this.fb.array<FormGroup>([]),
  });

  get lines(): FormArray {
    return this.form.controls.lines;
  }

  ngOnInit(): void {
    // Seed the form from whatever's already in the cart (added via
    // ProductListComponent's "Add to cart" buttons) — this is the
    // Signals -> reactive-form bridge called out in this module's brief:
    // `CartStateService.items()` is read ONCE here (a plain synchronous
    // signal read, not a subscription) to initialize form state. The form
    // then owns its own state independently; it does not stay "live-bound"
    // to the cart signal after this point, which is the correct boundary —
    // once the user is editing a form, further cart changes from another
    // tab/component shouldn't silently rewrite what they're mid-editing.
    const cartItems = this.cart.items();
    if (cartItems.length === 0) {
      this.addBlankLine();
    } else {
      for (const item of cartItems) {
        this.lines.push(this.buildLineGroup(item.product.sku, item.quantity));
      }
    }
  }

  private buildLineGroup(sku = '', quantity: number | null = 1): FormGroup {
    return this.fb.group({
      sku: this.fb.control(sku, { validators: [Validators.required], nonNullable: true }),
      // Custom validator applied alongside a built-in one — reactive forms
      // compose multiple `ValidatorFn`s in an array; ALL must pass.
      quantity: this.fb.control(quantity, {
        validators: [Validators.required, positiveQuantityValidator()],
      }),
    });
  }

  addBlankLine(): void {
    this.lines.push(this.buildLineGroup());
  }

  removeLine(index: number): void {
    this.lines.removeAt(index);
  }

  /**
   * Client-side estimate only (see the template's disclaimer) — looks up
   * each line's price from `availableProducts()` purely for a "does this
   * look roughly right" display before submitting. The AUTHORITATIVE total
   * is whatever `OrderService.placeOrder()`'s response contains, computed
   * server-side from `Order.totalAmount()` (java-basics) — never trust a
   * client-computed total for anything that affects billing.
   */
  estimatedTotal(): number {
    const products = this.availableProducts();
    return this.lines.controls.reduce((sum, group) => {
      const sku = group.get('sku')?.value as string;
      const quantity = Number(group.get('quantity')?.value) || 0;
      const product = products.find((p) => p.sku === sku);
      return sum + (product ? product.price * quantity : 0);
    }, 0);
  }

  onSubmit(): void {
    if (this.form.invalid) {
      this.form.markAllAsTouched();
      return;
    }

    const raw = this.form.getRawValue();
    const request: PlaceOrderRequest = {
      customerId: raw.customerId,
      lines: raw.lines.map((line) => ({ sku: line.sku, quantity: Number(line.quantity) })),
    };

    this.submitting.set(true);
    this.submitError.set(null);

    this.orderService.placeOrder(request).subscribe({
      next: (order) => {
        this.submitting.set(false);
        this.cart.clear();
        this.router.navigate(['/orders', order.id]);
      },
      error: (err: unknown) => {
        this.submitting.set(false);
        this.submitError.set('Could not place order. Please check stock availability and try again.');
        console.error(err);
      },
    });
  }
}
