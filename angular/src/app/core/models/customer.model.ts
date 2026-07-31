/**
 * Mirrors java-basics' `Customer` record (id, name, email) exactly, because
 * this Angular app is written against the REST contract that `spring/`
 * (Module 5) exposes over that same domain object. See java-basics/src/main/
 * java/com/interviewprep/orders/domain/Customer.java for the source of truth
 * on field names and validation rules (id non-blank, email contains '@').
 *
 * WHY A PLAIN `interface`, NOT A `class`: this is a wire-format DTO — data
 * that arrives as JSON and gets deserialized by `HttpClient` via a type
 * assertion (TypeScript interfaces have no runtime representation, so
 * `http.get<Customer>(url)` doesn't actually validate the shape; it just
 * tells the compiler "trust me"). If you need runtime validation of
 * server responses, reach for a schema library (zod, io-ts) at the HTTP
 * boundary — out of scope here, but worth knowing the interface alone
 * gives you zero runtime safety.
 */
export interface Customer {
  id: string;
  name: string;
  email: string;
}
