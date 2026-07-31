# Module 4 — Exercises

Do these roughly in order — difficulty increases, and the last one draws on
everything before it. No test framework yet (JUnit arrives in Module 5) —
verify each exercise by writing a small throwaway class with a `main`
method that exercises your code and prints the result, the same convention
`java-basics/Main.java` established. Remember the constraint this whole
module was written under: there is no `javac` available while authoring —
if you have a JDK, compile with the command in [README.md](README.md)
before and after each exercise to confirm you haven't broken the build.

## 1. (Beginner) Identify the SOLID/GRASP violation

Below is a new class, not currently in this module. Identify EVERY
principle it violates (there are at least three) from SOLID.md and
GRASP.md, and explain your reasoning for each in a comment — don't fix it
yet, just diagnose it, the way you would out loud in an interview:

```java
public class OrderThing {
    public static OrderThing INSTANCE = new OrderThing();

    public void handleEverything(String action, String orderId, String customerId,
                                  double amount, String discountType) {
        if (action.equals("PLACE")) {
            if (discountType.equals("PERCENTAGE")) {
                amount = amount - (amount * 0.1);
            } else if (discountType.equals("FLAT")) {
                amount = amount - 5.0;
            }
            System.out.println("INSERT INTO orders VALUES (" + orderId + ", " + customerId + ", " + amount + ")");
            System.out.println("Emailing customer " + customerId);
        } else if (action.equals("CANCEL")) {
            System.out.println("DELETE FROM orders WHERE id = " + orderId);
        }
    }
}
```

## 2. (Beginner) Extend Strategy with a new discount rule

Add a `BuyOneGetOneDiscountStrategy` to
[`gof/behavioral/strategy`](src/main/java/com/interviewprep/orders/patterns/behavioral/strategy)
implementing `DiscountStrategy`. Since `DiscountStrategy.apply(BigDecimal
amount)` only sees a total, not individual line quantities, you'll need to
decide: does this strategy need a different method signature (taking
`List<OrderLine>` instead of a flat `BigDecimal`)? If so, does that mean
`DiscountStrategy` itself needs to change, and if it does, what does that
do to every EXISTING implementation? Write a short comment explaining the
trade-off you chose — this is a real interface-design decision, not a
trick question with one right answer.

## 3. (Intermediate) Add a fourth OrderStatusListener

Add a `LoyaltyPointsListener` to
[`gof/behavioral/observer`](src/main/java/com/interviewprep/orders/patterns/behavioral/observer)
that awards loyalty points equal to 1 point per dollar of `order.totalAmount()`
whenever the new status is `OrderStatus.DELIVERED` (and does nothing for any
other transition). Confirm — by reading the code, not just trusting the
pattern's promise — that adding this listener required ZERO changes to
`OrderStatusPublisher`, `Order`, or any of the three existing listeners.
Then write down: which GRASP principle does this exercise most directly
demonstrate, and why?

## 4. (Intermediate) Fix a Composite bug

Deliberately introduce (then find and fix) a bug: temporarily change
`ProductBundle.price()` in
[`gof/structural/composite`](src/main/java/com/interviewprep/orders/patterns/structural/composite)
to loop over children with an indexed `for` loop instead of the stream
pipeline, and (on purpose, to see the failure) forget to add a child's
price if that child is itself a `ProductBundle` — i.e. reproduce, in
Composite, the exact class of bug
`NaiveInstanceofOperations.calculateShippingWeight` demonstrates in
Visitor. Confirm nesting a `ProductBundle` inside another `ProductBundle`
now silently under-prices the outer bundle. Revert your change and, in a
comment, explain why the ORIGINAL recursive stream pipeline can't have this
bug — what specifically about it forces every child, regardless of type or
nesting depth, to be counted?

## 5. (Senior) Add a Decorator that must run FIRST, and justify the ordering

Add a `FragileHandlingDecorator` to
[`gof/structural/decorator`](src/main/java/com/interviewprep/orders/patterns/structural/decorator)
that adds a flat $8 fee, but ONLY if applied as the OUTERMOST decorator
(i.e. its fee must be calculated on the price AFTER gift wrap and express
shipping are already added — fragile-handling insurance is priced on the
final insured value, not the pre-surcharge subtotal). Write the class, then
write TWO usage examples in a comment: one that composes the decorators in
the CORRECT order and one that composes them in the WRONG order, and
explain in a sentence why Decorator lets a caller make this ordering
mistake at all (i.e. what does Decorator NOT protect you from, compared to,
say, Template Method's fixed skeleton?).

## 6. (Scenario — combines multiple patterns) Refactor a God Object

Below is a deliberately bloated `OrderProcessor` — a realistic "junior
engineer kept adding features to the same class for a year" scenario. Your
task: refactor it using **at least three different patterns from this
module** (naming which pattern addresses which part of the mess), keeping
the same PUBLIC behavior (`processOrder(...)` still does everything it does
today, just via cleaner internal structure). For each pattern you apply,
write one sentence justifying the choice — "I used X here because Y" — the
way you'd explain a live refactor to an interviewer.

```java
public class OrderProcessor {
    private Inventory inventory = new Inventory(); // hardcoded, untestable dependency

    public Order processOrder(Customer customer, List<OrderLine> lines, String paymentType,
                               boolean isExpress, boolean giftWrap) {
        // Validation, all inline
        if (lines.isEmpty()) throw new IllegalArgumentException("empty order");
        for (OrderLine line : lines) {
            if (inventory.stockOf(line.product().sku()) < line.quantity()) {
                throw new IllegalStateException("insufficient stock");
            }
        }

        // Reservation, no rollback on partial failure
        for (OrderLine line : lines) {
            inventory.reserve(line.product().sku(), line.quantity());
        }

        // Pricing, with surcharges bolted on as booleans
        BigDecimal total = BigDecimal.ZERO;
        for (OrderLine line : lines) total = total.add(line.lineTotal());
        if (giftWrap) total = total.add(new BigDecimal("5.00"));
        if (isExpress) total = total.add(total.multiply(new BigDecimal("0.15")));

        // Payment, if/else on a raw string
        if (paymentType.equals("CREDIT_CARD")) {
            System.out.println("Charging credit card: " + total);
        } else if (paymentType.equals("BANK_TRANSFER")) {
            System.out.println("Charging bank transfer: " + total);
        } else {
            throw new IllegalArgumentException("unknown payment type");
        }

        // Order creation and hardcoded notification
        Order order = new Order("ORD-" + System.nanoTime(), customer);
        lines.forEach(order::addLine);
        System.out.println("Emailing " + customer.email() + ": order confirmed, total " + total);

        return order;
    }
}
```

**Hint (don't read until you've made your own pass):** at minimum, this
maps cleanly onto Chain of Responsibility (validation steps), Strategy
(payment selection), Decorator (surcharges), and Facade (the overall
`processOrder` becoming the simplified entry point coordinating the
others) — but there's more than one defensible decomposition. If your
answer uses a different combination with sound reasoning, that's a
legitimate outcome, not a wrong one; the goal is recognizing the SHAPES of
the problems, not matching one canonical solution.
