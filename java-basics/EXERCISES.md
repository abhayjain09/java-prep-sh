# Module 1 — Exercises

Do these in order — each builds on the previous one's code. Work directly in `src/main/java/com/interviewprep/orders/`. No test framework yet (JUnit arrives once Module 5 sets up Maven) — verify each exercise by extending `Main.java` with a call that prints the result and eyeballing it, or writing a quick throwaway `System.out.println` check.

## 1. (Beginner) Add a `Payment` value object

Create `domain/Payment.java` as a record with `String orderId`, `BigDecimal amount`, and `PaymentMethod method` (a new enum: `CREDIT_CARD`, `BANK_TRANSFER`, `CASH_ON_DELIVERY`). Add validation in the compact constructor: `amount` must be positive. This exercises records + validation, exactly like `Customer`/`Product`.

**Check yourself:** what should happen if `amount` is `BigDecimal.ZERO`? Should a $0 payment be legal? Justify your answer — there's no single correct one, but you should be able to defend a choice.

## 2. (Beginner) Fix a raw-type bug

Write a method `printAllLines(List lines)` (deliberately using the **raw** `List` type, no generic parameter) that casts each element to `OrderLine` and prints its `lineTotal()`. Call it with a list that (accidentally, for the exercise) contains one `Product` mixed in with `OrderLine`s, and observe the `ClassCastException` at runtime. Then fix the method signature to `List<OrderLine>` and notice the bug becomes impossible to introduce — the mixed list won't even compile.

## 3. (Intermediate) Implement `Inventory.reserveAll` atomically-in-appearance

Add a method `reserveAll(Map<String, Integer> quantitiesBySku)` to `Inventory` that reserves multiple SKUs in one call, with the same "all or nothing" guarantee `OrderService.placeOrder` currently implements manually with a `Deque` and a catch block. Decide: should this logic live in `Inventory` itself (so any caller gets the guarantee for free) or stay in `OrderService` (so `Inventory` stays a simple, low-level component)? Write a short comment in the code justifying your choice — this is a real API design trade-off, not a right/wrong quiz question.

## 4. (Intermediate) Replace a raw loop with a Stream pipeline

In a new method, given `List<Order> orders`, find the single order with the highest `totalAmount()` for a given customer, using `Collectors` / `Stream.max`. Then write the imperative loop version next to it (like `OrderService.totalSpentByImperative` vs. `...Streams`) and compare readability. What happens if `orders` is empty — what does your Streams version return, and does it match what your imperative version returns? (Hint: this is exactly the kind of edge case `Optional` exists for.)

## 5. (Senior) Make `Inventory` correct under concurrency — without reading Module 3 first

Before Module 3 explains the "correct" answers, attempt this yourself: `Inventory.reserve()` has a documented race condition (read-then-check-then-write, not atomic). Try to fix it using only what Module 1 covered (no `Executors`, no explicit locks yet) — one option is `Map.compute()`, which *is* atomic per-key on `ConcurrentHashMap`. Swap the backing map to `ConcurrentHashMap` and rewrite `reserve()` using `compute()` to make the check-and-decrement atomic in one call. Write down (in a comment) why this specific fix works and what class of concurrency bug it does or doesn't protect against — you'll compare your answer against Module 3's full treatment later.

## 6. (Scenario) Extend the `OrderStatus` state machine

Product wants a `RETURNED` status: a `DELIVERED` order can move to `RETURNED` within some window. Add the constant and the transition rule. Then ask: does adding `RETURNED` break the compiler's exhaustiveness checking anywhere in the existing code? Find every `switch` over `OrderStatus` in the codebase (there's currently one, in `OrderStatus.legalNextStates()`) and confirm the compiler forces you to handle the new case — that forced update *is* the safety feature generics/enums/sealed types are built around. Write one sentence explaining why a `String status` field instead of an enum would have let this bug slip through silently.
