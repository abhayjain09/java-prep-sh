# PLAN.md — Your Learning Path Through This Repo

A module-by-module study plan. Check boxes off as you go (in your editor or on GitHub) so you can see progress and pick up where you left off. Each module follows the same 6-step routine — do all 6 for a module before moving to the next one; skipping straight to code or straight to interview questions without the theory first defeats the point of this repo.

## The routine, every module

1. **Read `README.md`** — theory. Don't skim; each concept is explained with what/why/when-not/trade-offs, which is exactly the reasoning interviewers probe for.
2. **Study `diagrams/`** — before reading code, look at the diagram. It's the map; the code is the territory.
3. **Read the code in `src/`** (or `SOLID.md`/`GRASP.md`/`sql/`/etc. where a module isn't Java-code-shaped) — read it like a code review, not a skim.
4. **Read `EXPLANATION.md`** — check your own understanding of the code against this line-by-line walkthrough. Where you disagree with yourself, that's exactly where to slow down.
5. **Do `EXERCISES.md`** — actually write the code/queries/answers, don't just read them and nod. The last exercise in every module is scenario-style and deliberately harder — that's the real bar.
6. **Review `INTERVIEW.md`** — try answering each question yourself (out loud or written) *before* reading the ideal answer. Note which follow-ups you wouldn't have anticipated.

Nothing in this repo has been compiled (no JDK/Maven/Node/Docker were available when it was built — see [CONTEXT.md](CONTEXT.md)). Wherever you have real tooling, actually run the code — build/run commands are at the top of every module's README and in the root [README.md](README.md). Fixing whatever breaks when you first compile something is itself valuable learning, not a failure of the material.

---

## Phase 1 — Java Foundations

- [ ] **Module 1 — Java Basics** ([java-basics/](java-basics/)): Java 8→21 evolution, OOP, Collections, Generics, Streams, Lambdas, Exceptions. Start here no matter your background — every later module assumes you know this domain model cold.
- [ ] **Module 2 — File System APIs** ([java-advanced/file-io/](java-advanced/file-io/)): java.io/nio, CSV/JSON/ZIP, WatchService.
- [ ] **Module 3 — Concurrency** ([java-advanced/concurrency/](java-advanced/concurrency/)): do this right after Module 1, not later — it directly fixes a race condition Module 1 deliberately leaves broken in `Inventory.reserve()`. Read `java-basics/EXERCISES.md` exercise 5 first and actually attempt your own fix before reading this module's four solutions.
- [ ] **Module 11 — JVM Internals** ([java-advanced/jvm-internals/](java-advanced/jvm-internals/)): memory model, GC, JIT, JFR, JMH. Put this after Concurrency — lock contention and allocation-pressure discussions build on it.
- [ ] **Module 4 — Design Patterns** ([design-patterns/](design-patterns/)): SOLID, GRASP, all 23 GoF patterns. The largest module (116 code files) — budget real time here. Read [SOLID.md](design-patterns/SOLID.md) and [GRASP.md](design-patterns/GRASP.md) before the GoF patterns; the patterns constantly reference back to those principles.

**Checkpoint:** you should be able to explain the `Inventory` race condition, its two different fixes (app-level locking vs. DB-level, the DB one comes in Phase 2), and name at least 5 GoF patterns with a wrong/correct example each, from memory.

## Phase 2 — Backend Services

- [ ] **Module 5 + 8 — Spring & Caching** ([spring/](spring/)): Spring Core/Boot/Data JPA/REST + Spring Cache/Redis. Read section 0 first (why JPA entities aren't the java-basics records) — it's a genuinely common interview question.
- [ ] **Module 6 — Security** ([security/](security/)): JWT, OAuth2/OIDC, RBAC, CORS/CSRF. Read the "Scope & integration" note at the top — this module is a standalone Spring app that would merge into Module 5's in a real deployment.
- [ ] **Module 7 — Database** ([database/](database/)): SQL, indexing, transactions, locking. Do the `transactions-and-locking.sql` section right after re-reading Module 1's `OrderService.placeOrder()` — the whole point is seeing a real DB transaction replace that hand-rolled rollback logic.
- [ ] **Module 10 — Testing** ([testing/](testing/)): JUnit, Mockito, Testcontainers. Do this after Modules 5 & 7 exist conceptually, even though the tests here target Module 1's plain-Java code — the Testcontainers section previews what integration-testing Module 5/7 for real would look like.

**Checkpoint:** you should be able to trace a single `POST /orders` request through validation → service → repository → transaction → response, and explain what happens if stock is insufficient at each layer (service-level exception, DB-level constraint, HTTP response code).

## Phase 3 — Frontend & Cloud

- [ ] **Module 9 — Angular** ([angular/](angular/)): standalone components, RxJS, Signals, forms, guards/interceptors. Read the "API contract this module assumes" section, then (if you've done Module 5) go check whether Spring's real controllers actually match — this mismatch-hunting exercise is in [exercises/README.md](exercises/README.md).
- [ ] **Module 12 — AWS** ([aws/](aws/)): IAM/VPC/compute/S3/messaging/Terraform, all mapped onto this specific system. Read the compute-choice justification (§3) carefully — it's a model for how to answer "which AWS compute service would you use" without just naming the newest one.

**Checkpoint:** you should be able to sketch (from memory, on a whiteboard) the full AWS architecture diagram from `aws/diagrams/architecture.md` and explain why each piece is there.

## Phase 4 — Bringing It Together

- [ ] **Module 13 — System Design** ([system-design/](system-design/)): CAP/PACELC, CQRS, Event Sourcing, Saga, microservices vs. modular monolith, DDD, clean architecture, HA/DR. This module generalizes Module 1's `OrderService.placeOrder()` rollback into a full distributed Saga — re-read that method once more right before starting this module, the connection is the whole point.
- [ ] **[system-design/mock-interview.md](system-design/mock-interview.md)** — do this as a real mock interview. Cover the ideal answer, try to work through the "design an order system for Black Friday traffic" prompt yourself first (clarifying questions → capacity estimate → HLD → deep dives), then compare.
- [ ] **[docs/README.md](docs/README.md)** — read the glossary and the 3 cross-module ADRs once you've done everything above; they'll make more sense retroactively than they would up front.

**Final checkpoint:** pick one company from the list this repo targets (S&P Global, JPMorgan, Goldman Sachs, Microsoft, Amazon, Google, Oracle, Adobe, Salesforce, Atlassian) and do a full mock loop against yourself — one question from each module's `INTERVIEW.md` "senior" or "scenario" tier, timed, without looking at the ideal answer first.

---

## Suggested pacing

This is dense material — 234 Java files, 86 Markdown docs, a full Spring Boot app, a full Angular app, SQL, Terraform, and a mock interview. Realistic paces:

- **Intensive (interview in ~2-3 weeks):** ~1 module/day for Phases 1-3, 2-3 days for Phase 4. Skip nothing, but move fast — you're refreshing more than learning from scratch.
- **Steady (interview in ~6-8 weeks):** 2-3 modules/week, actually doing every exercise. This is the pace this repo was designed for.
- **Deep (no deadline, building real mastery):** 1 module/week, and for each module also do the "what would break this" exercise: after finishing a module's exercises, deliberately try to break your own solution (bad input, concurrent access, a malicious value) before moving on.

Whichever pace, don't skip the exercises to "save time" — the READMEs and INTERVIEW.md files test recognition (can you follow an explanation); the exercises test recall and application (can you produce the answer from nothing), which is what an actual interview tests.
