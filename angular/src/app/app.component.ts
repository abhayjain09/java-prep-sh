import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { RouterLink, RouterLinkActive, RouterOutlet } from '@angular/router';

import { AuthService } from './core/services/auth.service';

/**
 * AppComponent — the root shell: a toolbar (nav links + mock login/logout)
 * and a `<router-outlet>` where every lazily-loaded feature route renders.
 *
 * WHY A LOGIN TOOLBAR INSTEAD OF A DEDICATED LOGIN PAGE/ROUTE: this
 * module's brief is Angular fundamentals (components, routing, RxJS,
 * Signals, forms, DI, interceptors, guards, change detection) using a
 * MOCK auth state (see AuthService's header comment) — building a full
 * login form/page would mostly exercise the same reactive-forms skills
 * `order-form.component.ts` already demonstrates, without teaching
 * anything new, while adding a page that would need to be thrown away once
 * `security/` (Module 6) provides a real `/api/v1/auth/login` endpoint to
 * build a real login page against. A toolbar with "log in as
 * Customer/Manager" buttons exercises the SAME guard/interceptor/RBAC
 * mechanics with far less incidental surface area.
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [RouterOutlet, RouterLink, RouterLinkActive],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="toolbar">
      <nav>
        <a routerLink="/products" routerLinkActive="active">Products</a>
        <a routerLink="/orders/new" routerLinkActive="active">Place Order</a>
      </nav>

      <div class="toolbar__auth">
        @if (auth.isAuthenticated()) {
          <span>{{ auth.username() }} ({{ auth.role() }})</span>
          <button type="button" (click)="auth.logout()">Log out</button>
        } @else {
          <button type="button" (click)="auth.login('alice', 'CUSTOMER')">
            Log in as Customer
          </button>
          <button type="button" (click)="auth.login('bob', 'MANAGER')">Log in as Manager</button>
        }
      </div>
    </header>

    <main>
      <router-outlet />
    </main>
  `,
})
export class AppComponent {
  // Injected as `protected readonly auth` rather than the `private
  // readonly` used in every other component in this app: Angular's Ivy
  // renderer allows a component's OWN template to read `protected` (and
  // `public`) members but not `private` ones, and the template below reads
  // `auth.isAuthenticated()`/`auth.username()`/`auth.role()` directly and
  // calls `auth.login(...)`/`auth.logout()` — there's no separate
  // component-level state to wrap it in, so exposing the service directly
  // to the template avoids pointless pass-through methods. This is a
  // judgment call, not a rule: for a component with more presentation
  // logic, wrapping service calls in component methods (as
  // `order-detail.component.ts`'s `onCancel` does) keeps the template
  // decoupled from the service's exact API shape.
  protected readonly auth = inject(AuthService);
}
