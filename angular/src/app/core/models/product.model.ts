/**
 * Mirrors java-basics' `Product` record (sku, name, price).
 *
 * WHY `price: number` HERE, EVEN THOUGH THE JAVA SIDE USES `BigDecimal`:
 * JSON has no decimal type — Jackson (Spring's default JSON serializer)
 * commonly renders a `BigDecimal` as a bare JSON number, which TypeScript
 * can only model as `number`. That reintroduces exactly the binary
 * floating-point imprecision `Product.java`'s Javadoc warns against
 * (0.1 + 0.2 !== 0.3 in JS either — same IEEE-754 double under the hood).
 *
 * PRODUCTION NOTE: a real fintech-grade API often serializes money as a
 * STRING ("19.99") specifically to avoid client-side float arithmetic, and
 * the frontend uses a decimal library (e.g. `decimal.js`, `big.js`) to do
 * any math instead of native `+`/`*`. This module keeps `price: number` for
 * simplicity (it matches what a default Spring Boot + Jackson setup emits
 * out of the box), but flags this trade-off explicitly rather than silently
 * assuming `number` is safe for money — an interviewer asking "how does
 * your Angular app handle currency precision?" is testing for exactly this
 * awareness.
 */
export interface Product {
  sku: string;
  name: string;
  price: number;
}
