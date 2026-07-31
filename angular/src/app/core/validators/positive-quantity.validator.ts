import { AbstractControl, ValidationErrors, ValidatorFn } from '@angular/forms';

/**
 * Custom reactive-form validator enforcing the exact same invariant as
 * `OrderLine`'s compact canonical constructor in java-basics:
 *
 *   public OrderLine {
 *       if (quantity <= 0) {
 *           throw new IllegalArgumentException("OrderLine quantity must be positive: " + quantity);
 *       }
 *   }
 *
 * WHY VALIDATE THIS ON THE FRONTEND *AND* KNOW THE BACKEND ALSO VALIDATES
 * IT: this validator exists purely for UX — instant feedback without a
 * round trip, and disabling the Submit button before wasting a request.
 * It is NOT a security or data-integrity boundary. The backend
 * (`OrderLine`'s constructor, ultimately) is the real enforcement point,
 * because a client-side check can always be bypassed (disabled JS, a
 * direct `curl -X POST`, a modified request from browser devtools). This
 * is the exact same "client-side is UX, server-side is the boundary"
 * lesson `AuthService.hasRole()` calls out for RBAC — it applies to
 * validation just as much as authorization.
 *
 * WHY A FACTORY FUNCTION RETURNING A `ValidatorFn`, NOT A BARE FUNCTION:
 * Angular's built-in validators (`Validators.required`, `Validators.min`)
 * follow this same "factory returns validator" shape so they can be
 * parameterized (`Validators.min(5)`) while still matching the
 * `ValidatorFn = (control: AbstractControl) => ValidationErrors | null`
 * signature `FormControl`/`FormGroup` expect. This validator doesn't
 * currently need parameters, but the factory shape keeps it consistent
 * with how you'd extend it (e.g. `positiveQuantity({ max: 100 })` for a
 * per-SKU max-order-quantity rule) without changing every call site.
 */
export function positiveQuantityValidator(): ValidatorFn {
  return (control: AbstractControl): ValidationErrors | null => {
    const value = control.value;

    // Let `Validators.required` own the "empty" case — a validator should
    // do exactly one job. Returning `null` (valid) for empty/null values
    // here avoids this validator firing a confusing "must be positive"
    // error on a field the user simply hasn't typed into yet.
    if (value === null || value === undefined || value === '') {
      return null;
    }

    const numeric = Number(value);
    if (!Number.isInteger(numeric) || numeric <= 0) {
      // The returned object's key (`positiveQuantity`) is what templates
      // check via `control.hasError('positiveQuantity')` to show a
      // specific message — see order-form.component.ts's template.
      return { positiveQuantity: { actual: value } };
    }
    return null;
  };
}
