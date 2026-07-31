import { Injectable, computed, signal } from '@angular/core';

/**
 * AuthService — SIGNAL-BASED state, on purpose.
 *
 * WHY SIGNALS HERE, NOT AN RxJS `BehaviorSubject`: "am I logged in, and as
 * what role" is local, synchronous UI state — it changes only in response
 * to direct calls (`login`/`logout`), never as a stream of async events
 * from outside. That's exactly the "reach for Signals" case described in
 * README.md's "Signals vs RxJS" section. Contrast with `OrderService` /
 * `ProductService` below, which return Observables because HTTP responses
 * are asynchronous, one-shot-per-call, and compose naturally with RxJS
 * operators (`switchMap`, `catchError`, `debounceTime`).
 *
 * PRODUCTION NOTE — WHAT THIS MOCKS AND WHY: a real app gets its JWT from
 * `POST /api/v1/auth/login` handled by Spring Security in `security/`
 * (Module 6), which hasn't been built yet in this curriculum's sequencing.
 * To keep this Angular module runnable and self-contained *today*, this
 * service fabricates a JWT-shaped string locally (`buildFakeJwt`) instead
 * of calling a real endpoint. Everything downstream — the interceptor
 * attaching `Authorization: Bearer <token>`, the guard checking
 * `isAuthenticated()`, the role-gated Cancel button in OrderDetail — works
 * identically once `login()` is swapped for a real
 * `http.post<{token: string}>('/api/v1/auth/login', credentials)` call.
 * That swap is the ONLY place that would need to change.
 */

export type Role = 'CUSTOMER' | 'MANAGER';

interface AuthState {
  readonly token: string;
  readonly username: string;
  readonly role: Role;
}

const STORAGE_KEY = 'orders-app.auth-state';

@Injectable({ providedIn: 'root' })
export class AuthService {
  // A private, writable signal is the single source of truth. Everything
  // public is a read-only `computed()` derived from it — callers can react
  // to auth state but can't mutate it except through login()/logout(). This
  // is the Signals equivalent of "encapsulate the mutable field, expose
  // only validated mutators" that Inventory.java demonstrates in Module 1.
  private readonly state = signal<AuthState | null>(readPersistedState());

  readonly isAuthenticated = computed(() => this.state() !== null);
  readonly username = computed(() => this.state()?.username ?? null);
  readonly role = computed(() => this.state()?.role ?? null);

  /**
   * The raw bearer token, read by the functional HTTP interceptor. Exposed
   * as a computed signal (not a plain getter) so the interceptor always
   * reads the *current* value at request time, never a stale snapshot.
   */
  readonly token = computed(() => this.state()?.token ?? null);

  /**
   * Mock login — see the class-level PRODUCTION NOTE. Persists to
   * localStorage purely so a refresh doesn't immediately log you out while
   * developing/demoing; a real app would instead persist a refresh token
   * and re-authenticate silently (Module 6 territory).
   */
  login(username: string, role: Role): void {
    const next: AuthState = { username, role, token: buildFakeJwt(username, role) };
    this.state.set(next);
    localStorage.setItem(STORAGE_KEY, JSON.stringify(next));
  }

  logout(): void {
    this.state.set(null);
    localStorage.removeItem(STORAGE_KEY);
  }

  /**
   * Client-side role check used ONLY to decide what the UI *shows*
   * (e.g. hiding the Cancel button from a CUSTOMER). This is a UX
   * convenience, never a security boundary — a malicious or just
   * technically curious user can trivially call the API directly with
   * curl/Postman. Real authorization MUST be re-enforced server-side
   * (Spring Security method security / `@PreAuthorize`, Module 6). Every
   * place this method is used in this codebase has a comment repeating
   * this, because "the UI hid the button" is a common — and dangerous —
   * misconception about what client-side RBAC actually protects.
   */
  hasRole(required: Role): boolean {
    return this.role() === required;
  }
}

function readPersistedState(): AuthState | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return raw ? (JSON.parse(raw) as AuthState) : null;
  } catch {
    // Corrupt/unavailable storage (e.g. private browsing mode in some
    // browsers throws on access) should degrade to "logged out", not crash
    // the app at bootstrap.
    return null;
  }
}

/**
 * Builds a real-shaped (header.payload.signature) but NOT cryptographically
 * signed JWT, purely so `auth.interceptor.ts` and any future debugging
 * (pasting the token into jwt.io) look and behave like the real thing. The
 * "signature" segment is a fixed placeholder — do not model this as secure;
 * it isn't, and it never touches a real backend that would validate it.
 */
function buildFakeJwt(username: string, role: Role): string {
  const header = base64UrlEncode(JSON.stringify({ alg: 'none', typ: 'JWT' }));
  const payload = base64UrlEncode(
    JSON.stringify({
      sub: username,
      roles: [role],
      iat: Math.floor(Date.now() / 1000),
    }),
  );
  return `${header}.${payload}.mock-signature`;
}

function base64UrlEncode(value: string): string {
  return btoa(value).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
