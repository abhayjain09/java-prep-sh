# CONTEXT.md — Session Handoff

Purpose of this file: paste it (or point a new Claude Code session at it) when starting a fresh chat about this repo, so the assistant doesn't need to re-derive what already exists. Written after the session that built out the entire curriculum described below.

## What this repo is

A Java Full-Stack interview-prep teaching repo, built from the spec in [MASTER_JAVA_FULLSTACK_INTERVIEW_PROMPT.md](MASTER_JAVA_FULLSTACK_INTERVIEW_PROMPT.md). One running example — an **Order/Inventory system** (`Customer`, `Product`, `Order`, `OrderLine`, `Inventory`, `OrderStatus`, `OrderService`) — is introduced in Module 1 ([java-basics/](java-basics/)) and extended by every later module instead of using a new toy example each time.

## Current state: all 13 modules are built

| # | Module | Folder |
|---|---|---|
| 1 | Java 8→21 evolution, Core Java | [java-basics/](java-basics/) |
| 2 | File System APIs | [java-advanced/file-io/](java-advanced/file-io/) |
| 3 | Concurrency | [java-advanced/concurrency/](java-advanced/concurrency/) |
| 4 | Design Patterns (SOLID/GRASP/23 GoF) | [design-patterns/](design-patterns/) |
| 5 + 8 | Spring Core/Boot/JPA/REST + Caching | [spring/](spring/) |
| 6 | Security (JWT/OAuth2/OIDC/RBAC) | [security/](security/) |
| 7 | Database (SQL/Postgres/Oracle) | [database/](database/) |
| 9 | Angular | [angular/](angular/) |
| 10 | Testing (JUnit/Mockito/Testcontainers) | [testing/](testing/) — **new folder, added this session** |
| 11 | JVM Internals | [java-advanced/jvm-internals/](java-advanced/jvm-internals/) |
| 12 | AWS | [aws/](aws/) |
| 13 | System Design | [system-design/](system-design/) |

Cross-cutting: [docs/](docs/) (glossary, ADRs, end-to-end architecture diagram), [interview/](interview/) and [exercises/](exercises/) (indexes into every module's `INTERVIEW.md`/`EXERCISES.md`, plus a few exercises that span multiple modules). The root [README.md](README.md) has the full roadmap table and quick-reference build commands for every module.

**Every module folder has the same shape:** `README.md` (theory: what it is / why introduced / problem solved / when-(not)-to-use / trade-offs / performance / enterprise examples / common mistakes), `diagrams/*.md` (Mermaid), code under `src/`, `EXPLANATION.md` (line-by-line walkthrough), `EXERCISES.md` (4-6, increasing difficulty), `INTERVIEW.md` (beginner/intermediate/senior/scenario Q&A tied to companies: S&P Global, JPMorgan, Goldman Sachs, Microsoft, Amazon, Google, Oracle, Adobe, Salesforce, Atlassian).

## How it was built (useful if you want more of the same)

- Module 1 was built by hand, directly in the main conversation, establishing the templates every later module copies.
- Modules 2–13 (11 modules) were built by **parallel background agents**, one per module, each briefed with: the domain model's exact shape, the java-basics template files to read and match, the specific spec scope for their module, and a hard scope boundary (only touch their own folder). `spring/`, `security/`, `angular/` were built concurrently and deliberately do **not** import each other's code — each documents the contract/interface it assumes from the others instead (this is a known, intentional source of drift — see below).

## Environment constraint (still true — check before assuming otherwise)

This sandbox has **no JDK/Maven/Gradle/Node/npm/Docker/AWS CLI/Terraform CLI**. Nothing in this repo has been compiled or run — every file was written carefully and re-read for syntax correctness, but **none of it has been verified to actually build**. This is the single biggest open item: if you get access to real tooling, running `javac`/`mvn`/`npm install`/`ng serve` on each module (commands are in each module's README) and fixing whatever surfaces is the highest-value next step.

## Known drift / follow-up items worth doing next

1. **Angular's assumed REST contract vs. Spring's real one.** [angular/README.md](angular/README.md) documents the API contract it assumed while being built without reading `spring/`. Now that `spring/` exists for real, diff `angular/src/app/core/services/*.ts` against `spring/src/main/java/.../controller/*.java` and fix any mismatch. (Also listed as exercise 2 in [exercises/README.md](exercises/README.md).)
2. **security/ is a separate standalone Maven module from spring/** (own `pom.xml`, own `SecurityDemoApplication`) rather than merged into it — intentional for this session (both were being built concurrently), but a real next step would be merging `security/`'s classes into `spring/`'s application and deleting the duplicate scaffolding. See `security/README.md`'s "Scope & integration" section.
3. **Nothing has been compiled.** See above — this is the real gap, everything else is secondary to it.
4. One background agent ran `git init` and made an unauthorized commit ("first commit") partway through the session. Flagged to the user, who chose to leave it as-is — the repo is now a git repo with that one early commit, and everything built after it is uncommitted.

## Notable cross-module design decisions (in case you're asked to justify or change them)

- **JPA entities in `spring/` are separate classes from `java-basics/`'s records**, not reused — records can't be JPA entities (no-arg constructor requirement, mutable identity, proxying for lazy loading). Full reasoning in `spring/README.md` §0 and `docs/README.md` ADR-2.
- **`Inventory` is a field on `Product` (`stockQuantity` + `@Version`) in `spring/`**, not a separate entity/table like it is in `java-basics/`. Justified in `spring/README.md` §6 and `docs/README.md` ADR-3 — promote it back to a separate entity if multi-warehouse or audit-ledger requirements ever appear.
- **Every module builds independently** (no shared Maven multi-module reactor, no shared domain-core library) — deliberate for a teaching repo (read one module without understanding a build topology spanning all of them), documented as ADR-1 in `docs/README.md`. Real product code should not make this trade-off.
- **`system-design/` recommends starting as a modular monolith**, not microservices-by-default, splitting out a service (Payments first, most likely) only when a real scaling/ownership need appears.
- **`aws/` recommends ECS on Fargate** over raw EC2 or EKS for this system's scale — full reasoning in `aws/README.md` §3.
- **`java-advanced/concurrency/`** closes the loop `java-basics/Inventory.reserve()` deliberately leaves open (a documented race condition) with three fixes (`ConcurrentInventory`, `SynchronizedInventory`, `StripedLockInventory`) compared via a shared stress-test harness. `database/` solves the same underlying problem at the DB layer (pessimistic `SELECT...FOR UPDATE` vs. optimistic version-column locking) — see both together for the full picture.
- **`design-patterns/`** covers all 23 GoF patterns as compact wrong/correct pairs (116 Java files) plus separate `SOLID.md` and `GRASP.md` — breadth over depth was the deliberate choice there.

## How to continue in a new chat

Point a new session at this file and this repo, then give a specific ask. Examples of prompts that would work well:

- "Read CONTEXT.md, then verify [module] actually compiles/runs and fix whatever's broken." (highest-value next step, per above)
- "Read CONTEXT.md, then reconcile angular/'s assumed API contract against spring/'s real one."
- "Read CONTEXT.md, then merge security/ into spring/ as a single application."
- "Read CONTEXT.md, then deepen [module] with [specific addition]."
- "Read CONTEXT.md, then add a new module for [topic not currently covered]."

Whoever picks this up next doesn't need the original conversation — this file plus the repo's own READMEs (start with the root [README.md](README.md)) should be enough context to act.
