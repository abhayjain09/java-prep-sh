import { bootstrapApplication } from '@angular/platform-browser';

import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';

/**
 * `bootstrapApplication(AppComponent, appConfig)` — the standalone
 * bootstrap entry point, replacing the classic
 * `platformBrowserDynamic().bootstrapModule(AppModule)`. There is no
 * `AppModule` anywhere in this project; `appConfig` (app.config.ts) supplies
 * every provider (`provideRouter`, `provideHttpClient`,
 * `provideZoneChangeDetection`) that an `@NgModule`'s `imports`/`providers`
 * arrays would have supplied in the pre-standalone era.
 */
bootstrapApplication(AppComponent, appConfig).catch((err) => console.error(err));
