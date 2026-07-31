# Mock System Design Interview: "Design an Order Management System for Black Friday-Level Traffic"

This is a full transcript of the kind of 45–60 minute system-design round given at senior loops for companies like Amazon, Microsoft, Google, and the fintech-flavored bars at S&P Global/JPMorgan/Goldman Sachs. It satisfies the master spec's "Technical Interview Coach" requirement specifically for the System Design round — the round most candidates agree is the single most decisive part of a senior/staff loop, because unlike a coding round (pass/fail on correctness) it's evaluated almost entirely on *how you think*: what you ask before designing anything, how you estimate scale, how you justify trade-offs, and how you respond when the interviewer pushes on a weak spot.

Uses this repo's running example — the candidate's design is, not coincidentally, exactly the evolution path this repo's modules take: `java-basics`' single-process `OrderService`/`Inventory` → this module's distributed, saga-based, CQRS-backed system.

---

## Interviewer opens

**Interviewer:** "Design an order management system that needs to handle Black Friday-level traffic spikes. Take a few minutes to ask clarifying questions before you start designing."

## Stage 1 — Clarifying questions (the candidate asks first, doesn't design yet)

**Candidate:** "Before I sketch anything, I want to pin down scope and scale, because the right design for 10x normal traffic for four hours a year is different from the right design for a system that runs hot all the time. A few questions:

1. **What does 'order management' include end-to-end?** Just placing an order and confirming payment/stock, or also fulfillment/shipping tracking, returns, and customer-facing order history?
2. **What's normal traffic, and what's the expected Black Friday multiplier?** Is this more like 5x normal, or 50–100x?
3. **Is this B2C retail checkout, or something else** (e.g. B2B bulk ordering)? That affects order value, fraud/payment complexity, and whether 'stock' is a simple per-SKU counter or something more like a reservation-with-expiry model (add-to-cart holds).
4. **What's the consistency bar on inventory?** Is oversold inventory an acceptable, cheaply-fixable business event (cancel-and-refund), or a hard requirement to prevent?
5. **What are the availability expectations?** Is a brief 5xx during the absolute peak acceptable if the alternative is overselling, or is 'never show an error to the customer' the harder requirement?
6. **Existing constraints?** Is this greenfield, or is there a legacy monolith we're evolving (this matters a lot for how aggressively I recommend microservices vs. incremental refactoring)?"

**Interviewer:** "Good questions. Let's say: full order lifecycle through fulfillment, not returns. Normal traffic is ~500 orders/second sustained; Black Friday peak is roughly 20x that for a few hours, ~10,000 orders/second at the peak minute. B2C retail. Overselling is unacceptable — that's a hard business requirement, not a nice-to-have. Brief elevated latency during peak is acceptable; outright errors should be rare but don't need to be zero. Assume this is a real company with an existing monolith we're evolving, not greenfield."

**Candidate:** "Great, that's genuinely useful — it tells me: (a) inventory correctness is a CP decision, not an AP one, (b) we're scaling an existing system incrementally, which argues against a big-bang microservices rewrite, and (c) the traffic pattern is bursty-predictable (we know Black Friday is coming), which favors pre-provisioned/autoscaled capacity over purely reactive scaling."

## Stage 2 — Back-of-envelope capacity estimation

**Candidate:** "Let me size this before designing. Peak is 10,000 orders/sec. If each order averages 2.5 line items, that's 25,000 inventory-reservation operations/sec at peak. Assume each order placement does roughly: 1 write to create the order, 1 call to Inventory (with N line items batched into one call, not N calls), 1 call to Payment, 1 call to Shipping, and a few read-modify-write operations at the database layer for inventory decrement.

For the database: at 10,000 writes/sec sustained for a few minutes at true peak, a single primary Postgres/Oracle instance is going to struggle — realistic single-primary write throughput before needing to shard is usually in the low thousands of TPS for a transactionally-heavy write like this, depending on hardware and row/index complexity. So I'm going to flag now that sharding the orders table (by `customer_id`) is likely necessary at this scale, not a nice-to-have — I'll come back to that.

For read traffic: assume each order placement generates roughly 5-10x that in read traffic (users refreshing cart/product pages, checking order status) — so somewhere around 50,000-100,000 reads/sec at peak. That's a strong signal for aggressive read-replica and CDN/cache usage, and it's exactly the kind of read/write divergence that justifies CQRS (Section 4 of my mental checklist) rather than serving both from one model.

For storage: 10,000 orders/sec for a 4-hour peak window is ~144 million orders in that window alone — that's a lot of write volume for one unsharded table, reinforcing the sharding point.

I'll design for sustained 500/sec normal load, comfortably autoscaled/pre-provisioned for the 10,000/sec peak, and I'll call out explicitly where the design changes between those two regimes."

**Interviewer:** "Reasonable. Go ahead and sketch the high-level design."

## Stage 3 — High-level design

**Candidate:** *(sketches, narrating)*

"At a high level:

- **API Gateway** at the edge — single entry point, does auth termination, and critically for Black Friday, **rate limiting** per client/IP to protect the backend from abusive or simply buggy retry storms during the peak, plus request routing.
- Behind it, I'd evolve the existing monolith into a **modular monolith** first, not straight to microservices — given this is an existing system, not greenfield, and given the team likely doesn't have four separately-staffed on-call rotations ready to go. I'd carve out clear bounded contexts internally: Ordering, Inventory, Payment (behind an anti-corruption layer to the payment gateway), Shipping. Payment is the one context I'd consider extracting into a real separate service *first*, even before the others — it has genuinely different scaling/compliance (PCI) needs than the rest, which is exactly the kind of concrete justification that earns an early extraction rather than defaulting to a full microservices split.
- **CQRS split** between the write path (`OrderCommandService` — `placeOrder`, `transitionTo`, hitting a normalized, possibly-sharded primary datastore) and the read path (`OrderQueryService` — order history, dashboards, hitting a denormalized read store kept up to date via a change stream / event bus). Given the 50,000-100,000 reads/sec estimate versus far lower write volume, this pays for itself immediately.
- **Order placement as a Saga**: `CreateOrder → ReserveInventory → ChargePayment → ArrangeShipping → ConfirmOrder`, orchestration-based (a central saga orchestrator inside the Order module/service), with compensations (`ReleaseInventory`, `RefundPayment`, `CancelShipment`) for each forward step. I'd draw the sequence diagram for both the happy path and a payment-decline failure path if useful.
- **Inventory**: this is the CAP-sensitive piece. I'd make stock counts strongly consistent — reads for a checkout decision hit a primary/leader (or use a quorum write) rather than a stale replica, accepting the latency cost, because overselling was called out as unacceptable. Product descriptions and non-critical catalog data, by contrast, I'd serve from aggressively cached, eventually-consistent read replicas/CDN — there's no reason to pay consistency cost on data where staleness is a non-event.
- **Resilience**: circuit breakers around every cross-service call (Order → Payment, Order → Inventory) with a defined failure-rate threshold and half-open recovery, retries with exponential backoff *and jitter* specifically because a Black Friday-scale outage-then-recovery is exactly the scenario where synchronized retry storms would prevent recovery.
- **Caching**: product catalog and pricing cached aggressively (TTL-based, since staleness there is cheap); the cart/session state kept in a shared store (Redis-style) rather than in-process, which is a prerequisite for the fleet of stateless Order-service instances to scale horizontally at all.
- **Observability**: a trace ID generated at the gateway and propagated through every saga step, so a stuck or failed order at 2am on Black Friday can be traced end-to-end instead of manually correlating four services' logs.
- **HA/DR**: multi-AZ for the primary datastore with automatic failover (this needs to be bulletproof specifically *during* the peak window — a mid-Black-Friday failover is the worst possible time to discover it doesn't work), and a DR region on standby with async replication for the true disaster case, accepting an RPO of a few minutes given checkout-latency cost would be prohibitive for synchronous cross-region replication."

## Stage 4 — Interviewer deep-dives

**Interviewer:** "Let's go deeper on inventory specifically. Walk me through exactly what happens when two customers try to buy the last unit of the same SKU at the same instant, at Black Friday scale."

**Candidate:** "At the database level, the reservation for a given SKU has to be an atomic, serialized operation — not the read-then-check-then-write pattern `java-basics`' original `Inventory.reserve()` uses in a single process, which has an obvious race condition under concurrency. Options: a single-row atomic decrement with a `WHERE stock >= quantity` guard clause (`UPDATE inventory SET stock = stock - :qty WHERE sku = :sku AND stock >= :qty`, checking rows-affected == 1) is the simplest correct fix and works well under a relational database's row-level locking. At extreme per-SKU contention (a single wildly popular doorbuster item), I'd consider a dedicated hot-key mitigation — e.g. a per-SKU atomic counter in a fast key-value store (Redis `DECRBY` with a floor check via a Lua script for atomicity) in front of the database, with the database as the durable source of truth reconciled asynchronously, or pre-sharding a single hot SKU's stock count across N counters to spread contention, summed only when checking availability across shards. I'd flag that this hot-key case is worth specifically load-testing before the real event — it's the single most likely place a naive design falls over on Black Friday specifically."

**Interviewer:** "Good. Now — you said orchestration for the saga. Why not choreography, given you're worried about tight coupling from Payment already being pulled out as its own service?"

**Candidate:** "Fair pushback. Both are valid; I chose orchestration mainly for two reasons: first, it gives us one place — the orchestrator — to see and reason about the entire order-placement flow, which matters a lot operationally during an incident at 2am on the biggest traffic day of the year; a choreography-based flow means reconstructing 'why is this order stuck' by correlating events across services, which is strictly harder even with good distributed tracing. Second, this flow has a clear, mostly-linear sequence with well-defined compensations — it's not a case with many independent services reacting to a shared event in parallel, which is where choreography's lower coupling tends to pay off more. If I were designing, say, a fan-out notification system (email, SMS, push, all reacting independently to 'OrderShipped') I'd lean choreography instead. I'd also note the orchestrator itself needs to be highly available and its per-saga state persisted, or it becomes a new single point of failure and a new source of orphaned, half-completed orders if it crashes mid-saga — that's a real cost of the orchestration choice I'm accepting deliberately."

**Interviewer:** "What would you actually cut if you only had two weeks before Black Friday and had to pick the highest-leverage changes?"

**Candidate:** "Given the two-week constraint, I would NOT attempt a service extraction in that window — that's exactly the kind of change with a long tail of subtle bugs you don't want to discover for the first time under peak load. I'd prioritize, in order: (1) load-testing and fixing the inventory hot-key path specifically, since that's the single most likely correctness failure at scale; (2) circuit breakers and backoff-with-jitter on the payment gateway call, since a slow/degraded payment gateway is a very plausible Black Friday failure mode and cascading failure from it is the highest-blast-radius risk; (3) confirming autoscaling policies and pre-warming capacity ahead of the traffic spike rather than relying on reactive autoscaling to keep up with a 20x step-change; (4) a tested, rehearsed failover runbook for the primary database, since that's the worst possible day to discover the runbook is stale. A saga rewrite or a CQRS read-model migration, if not already done, would go on the *next* roadmap, not this one — they're valuable but not what breaks first under this specific stress."

## Stage 5 — Trade-offs recap

**Interviewer:** "Last question — if I told you the business now wants near-zero data loss AND near-zero downtime even in a full regional outage, what does that cost, concretely?"

**Candidate:** "That's asking for active-active multi-region with synchronous or near-synchronous cross-region replication for the inventory/order write path — which directly reopens the CAP/PACELC trade-off I made earlier in the other direction: every checkout write would now wait on cross-region acknowledgment, adding real, felt latency (likely tens to low hundreds of milliseconds depending on region distance) to every single order, all the time, not just during a disaster — that's the PACELC 'else' branch cost paid continuously to buy a rare-event improvement. I'd push back and ask what the actual cost of the current ~15-30 minute RTO / few-minutes RPO target really is to the business before recommending this — for most retailers, a rare few-minutes-of-lost-orders event recoverable by asking customers to reconfirm is far cheaper than paying that latency tax on every order forever. If the business has a hard regulatory or contractual reason (this being a finance-adjacent target company list, that's plausible) I'd design it, but I'd make sure the trade-off and its ongoing cost are an explicit, informed business decision — not a default 'more resilience is always better' engineering choice."

**Interviewer:** "Good answer — that's exactly the kind of trade-off reasoning we're looking for."

---

## What this transcript demonstrates (debrief for the candidate)

- **Clarifying questions came first and shaped the entire design** — the answer to "is overselling acceptable" directly determined the CP-vs-AP call for inventory later; the "existing monolith" answer directly justified the modular-monolith-first recommendation instead of a reflexive microservices pitch.
- **Back-of-envelope math was used to justify specific decisions** (sharding, CQRS, read replicas), not performed as a disconnected ritual — every number fed into a design choice made later.
- **The candidate pushed back once** (on choreography vs. orchestration) instead of just defending the original answer defensively — showing genuine trade-off reasoning rather than a rehearsed answer.
- **The two-week question tested prioritization under a real constraint** — the strong answer explicitly *declines* to do the most architecturally "interesting" work (a service extraction) because it's the wrong risk profile for the deadline, which is exactly the kind of judgment call a senior engineer is expected to make.
- **The final question tested whether the candidate would blindly satisfy a stronger requirement** or explain its real cost first — pushing back with a cost-aware question ("what does the business actually need") is a stronger senior signal than immediately designing the more complex system requested.
