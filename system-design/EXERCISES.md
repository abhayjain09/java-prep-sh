# Module 13 — Exercises

Do these in order — each assumes the README, diagrams, and LLD have been read first. Unlike earlier code-heavy modules, most of these are design/reasoning exercises (produce a diagram, a written trade-off analysis, or an extension to the LLD skeleton) rather than runnable code, matching this module's diagram/doc-driven scope. Where an exercise does ask for code, follow the LLD's convention: illustrative, carefully written, not required to compile in this sandbox.

## 1. (Beginner) Trace the CAP/PACELC decision for two more data types

The README applies CP-vs-AP reasoning to exactly two data types: stock count (CP) and product description (AP). Pick two more pieces of data from this domain — for example, a customer's loyalty-points balance, and an order's estimated-delivery-date display — and write a short paragraph for each arguing CP or AP, using the same "what does an error cost vs. what does staleness cost" framing the README uses. Then answer: is either of your two examples genuinely ambiguous (a reasonable case for either choice depending on additional context)? If so, say what additional fact would resolve the ambiguity.

**Check yourself:** a strong answer names the specific bad outcome staleness or unavailability would cause for your chosen data — not just "CP because it's important."

## 2. (Beginner) Extend the saga's compensation table

The README's Section 6 compensation table stops at `ConfirmOrder`. Add a new step, `SendConfirmationEmailStep`, that runs after `ConfirmOrderStep` in the happy path. Write its row in the compensation table (forward action, compensation, when the compensation runs) — and specifically address: is a "send confirmation email" action realistically compensable at all once it's happened? If not, what does that imply about where in the saga sequence this kind of step should be placed (hint: compare "steps with real compensations" vs. "steps that are safe to fail without needing a rollback, because nothing depends on them completing")?

**Check yourself:** this exercise is really asking whether every step in a saga needs a *symmetric* compensation, or whether some steps are better modeled as "fire, and if it fails, retry later — don't roll back the whole order over a failed email."

## 3. (Intermediate) Add a `CancelOrderSaga` to the LLD

Using the existing `SagaStep`/`SagaOrchestrator`/`CompensationRegistry` classes in [lld/src/main/java/com/interviewprep/orders/saga/](lld/src/main/java/com/interviewprep/orders/saga/) unchanged, design (and write, illustrative-only) a *second* saga: cancelling a `CONFIRMED` order after the fact (customer-initiated cancellation, not a failure-triggered rollback). Steps might include `RefundPaymentStep`, `ReleaseInventoryStep` (note: this reuses the same *concept* as `ReserveInventoryStep`'s compensation, but as a **forward** action this time, not a compensation — think about whether that means you can literally reuse the `ReserveInventoryStep` class or need a new one), and `CancelShipmentStep`. Write the new step classes' skeletons (execute/compensate) and explain in a comment: does a "cancel order" saga's *own* compensation path make sense (i.e., if `CancelShipmentStep` fails, do you re-reserve inventory and re-charge the customer to "undo the cancellation"). Justify your answer.

**Check yourself:** the trick in this exercise is noticing that not every saga needs steps that are meaningfully compensable in the reverse direction — a "cancellation" saga's own failure mode is often "retry the failing step" rather than "undo the whole cancellation," which is a different design decision than the order-placement saga's.

## 4. (Intermediate) Redraw the HLD for choreography instead of orchestration

The README picks orchestration and justifies why. Take [diagrams/hld-microservices.md](diagrams/hld-microservices.md) and [diagrams/saga-happy-path.md](diagrams/saga-happy-path.md) and redraw both (as Mermaid, following this repo's diagram conventions) for a **choreography-based** version of the same order-placement flow: `OrderService` publishes `OrderPlaced`; `InventoryService` reacts and publishes `StockReserved` or `StockReservationFailed`; `PaymentService` reacts to `StockReserved`; and so on. Then write a short paragraph: what does your choreography sequence diagram make *harder* to see at a glance compared to the orchestration version, and what does it make *easier*?

**Check yourself:** if your choreography diagram ends up just as easy to read as the orchestration one, look again — you may have accidentally drawn a "hidden orchestrator" (one service that everyone reports back to) rather than genuine peer-to-peer choreography.

## 5. (Senior) Design the saga orchestrator's own persistence and recovery

`SagaOrchestrator.run()`'s Javadoc and `CompensationRegistry`'s Javadoc both flag, but don't implement, that a real orchestrator must persist saga progress so a crash mid-saga can be recovered from. Design (write as a short doc, not code): a `saga_instance` table schema (columns, and why each one), and the recovery algorithm a restarted orchestrator process runs on startup: how does it find in-flight sagas, and for each one, how does it decide whether to resume forward execution or begin compensating? Specifically address: if the orchestrator crashed *while* a step's remote call was in flight (not before, not cleanly after), how do you determine on recovery whether that call actually succeeded on the remote side or not — and why does this make step idempotency (README Section 6) necessary even for the *forward* actions, not just compensations?

**Check yourself:** the hard part of this exercise is the "crashed mid-call" case — a good answer recognizes that the orchestrator genuinely cannot know for certain whether the remote call succeeded without an idempotency key and a reconciliation/query capability on the remote service (e.g. "ask the Payment service: did a charge for this sagaId already happen?"), not just a resend-and-hope approach.

## 6. (Scenario) Redesign for a hypothetical acquisition doubling order volume overnight

Your company just acquired a competitor. Effective in 90 days, your order volume doubles overnight (not a gradual ramp — a hard cutover date when their customer base migrates onto your platform), and the acquired company's product catalog and inventory data need to be merged into yours, with some overlapping SKUs that need reconciliation. Using everything in this module, write a short design memo (a page or so) covering:
- Which of README Section 9's "genuine scaling need" or "genuine team-ownership need" triggers does this event satisfy, if any — does it change your microservices-vs-modular-monolith recommendation, and for which specific bounded context(s)?
- What in your current design (assume the modular monolith recommended in Section 9, with the Saga/CQRS/caching patterns from this module already in place) is most likely to break first at 2x volume, and how would you find out *before* the cutover rather than during it?
- How do you handle the SKU-overlap data merge without violating the CAP-driven consistency guarantee on stock counts (Section 2) during the migration window — is there a safe way to do this merge live, or does it require a maintenance window, and how do you justify that trade-off to a business stakeholder who doesn't want any downtime?
- What HA/DR numbers (Section 13) would you revisit given the business now presumably cares more about this system, and why.

**Check yourself:** the strongest answers explicitly connect each recommendation back to a specific section/pattern from this module rather than inventing new, unjustified architecture — this exercise is testing whether you can *apply* the full toolkit under a new constraint, not whether you can design a system from scratch.
