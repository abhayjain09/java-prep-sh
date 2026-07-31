import { ApplicationConfig, provideZoneChangeDetection } from '@angular/core';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { provideRouter } from '@angular/router';

import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';

/**
 * `ApplicationConfig` — the standalone bootstrap config, replacing the
 * classic `AppModule` (`@NgModule({ imports: [BrowserModule, HttpClientModule,
 * RouterModule.forRoot(routes)], bootstrap: [AppComponent] })`). Every
 * cross-cutting service the app needs is registered here as a `provide*`
 * function instead of an NgModule import — this is the Angular 17 default
 * project shape (`ng new` no longer scaffolds NgModules unless you opt out
 * of `--standalone`).
 */
export const appConfig: ApplicationConfig = {
  providers: [
    /**
     * `provideZoneChangeDetection({ eventCoalescing: true })`: this app
     * still runs on zone.js (Angular's default change-detection trigger
     * mechanism today) — `eventCoalescing: true` batches multiple DOM
     * events that fire within the same task (e.g. several synthetic events
     * from one user interaction) into a SINGLE change-detection pass
     * instead of one per event, a free performance win with no code
     * changes required. See README.md's Change Detection section for what
     * zone.js actually does (monkey-patches async APIs — setTimeout,
     * Promise.then, addEventListener, XHR/fetch — so Angular knows "some
     * async work just finished, a re-render might be needed") and the
     * forward-looking note on Angular's EXPERIMENTAL zoneless mode
     * (`provideExperimentalZonelessChangeDetection()`), which this app
     * does NOT use yet — it's still maturing, and this app's discipline
     * (Signals + OnPush everywhere in features/) is exactly what makes a
     * future opt-in to zoneless low-risk when it graduates from
     * experimental.
     */
    provideZoneChangeDetection({ eventCoalescing: true }),

    /**
     * `provideRouter(routes)` replaces `RouterModule.forRoot(routes)`.
     * Route-level code splitting (`loadComponent` in app.routes.ts) works
     * out of the box with no extra configuration here.
     */
    provideRouter(routes),

    /**
     * `provideHttpClient(withInterceptors([...]))` replaces
     * `HttpClientModule` + the old multi-provider `HTTP_INTERCEPTORS`
     * registration. `withInterceptors` takes an array of FUNCTIONAL
     * interceptors (`HttpInterceptorFn`) and runs them, in array order, for
     * every outgoing request — `authInterceptor` here attaches the current
     * JWT (see core/interceptors/auth.interceptor.ts). Additional
     * interceptors (e.g. a logging interceptor, a retry-with-backoff
     * interceptor) would simply be added to this array; order matters when
     * interceptors depend on each other's effects (e.g. a logging
     * interceptor placed AFTER the auth interceptor logs the
     * already-authorized request).
     */
    provideHttpClient(withInterceptors([authInterceptor])),
  ],
};
