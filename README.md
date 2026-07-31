# Java Full-Stack Interview Prep

A single, evolving teaching project for becoming interview-ready for **Senior Full Stack Java** roles (Java + Spring Boot + Angular + SQL + AWS + Security + System Design). The goal is not to ship software fast — it's to *understand* every layer well enough to defend it in a senior interview at companies like S&P Global, JPMorgan, Goldman Sachs, Microsoft, Amazon, Google, Oracle, Adobe, Salesforce, and Atlassian.

The full curriculum is defined in [MASTER_JAVA_FULLSTACK_INTERVIEW_PROMPT.md](MASTER_JAVA_FULLSTACK_INTERVIEW_PROMPT.md). All 13 modules below are now built out — theory, diagrams, code, line-by-line explanations, exercises, and interview Q&A in every module folder.

## The running example: an Order/Inventory System

Every module reuses and extends the same small domain — customers, products, orders, inventory, and (later) payments — so that each new topic (persistence, REST, security, caching, messaging, system design) is demonstrated on a codebase you already understand, instead of a new toy example every time. It's deliberately close to real fintech/e-commerce interview scenarios: stock reservation under concurrency, order state transitions, transactional integrity, and eventually event-driven fulfillment.

See [docs/README.md](docs/README.md) for a glossary, cross-module architecture decisions, and an end-to-end diagram of how every module's piece fits into one deployment.

## Prerequisites (not installed in this sandbox — install locally to build/run)

- JDK 21 (the project is written against 8→21 evolution, but code samples target 21 unless a module is specifically demonstrating older behavior)
- Maven 3.9+ (for `spring/`, `security/`, `testing/`)
- Node.js 20+ and Angular CLI (for `angular/`)
- Docker (for `spring/`'s docker-compose Postgres+Redis, and `testing/`'s Testcontainers integration test)
- PostgreSQL and/or Oracle (for `database/` — can also run via Docker)
- Terraform CLI (for reading/planning, never applying, `aws/`'s illustrative `.tf` files)

This sandbox had none of the above installed, so every module's code was written and carefully re-read for correctness but never compiled or run here. Each module's README states the exact commands to build/run it for real.

## Roadmap / Module Index

| # | Module | Folder | Status |
|---|--------|--------|--------|
| 1 | Java 8→21 evolution, Core Java (OOP, Collections, Generics, Streams, Lambdas, Exceptions) | [java-basics/](java-basics/) | ✅ Done |
| 2 | File System APIs (java.io, java.nio, Path/Files, WatchService, ZIP/CSV/JSON/XML, serialization, large files) | [java-advanced/file-io/](java-advanced/file-io/) | ✅ Done |
| 3 | Multithreading & Concurrency (Executors, CompletableFuture, Locks, Atomics, Virtual Threads) | [java-advanced/concurrency/](java-advanced/concurrency/) | ✅ Done |
| 4 | SOLID, GRASP, all 23 GoF Design Patterns (wrong vs. correct) | [design-patterns/](design-patterns/) | ✅ Done |
| 5 | Spring Core, Spring Boot, Spring Data JPA, REST APIs | [spring/](spring/) | ✅ Done |
| 6 | Security: Spring Security, JWT, OAuth2, OIDC, SAML, OKTA, SCIM, MFA, RBAC, CORS, CSRF | [security/](security/) | ✅ Done |
| 7 | Databases: PostgreSQL & Oracle, SQL basic→advanced, execution plans, indexing, transactions | [database/](database/) | ✅ Done |
| 8 | Caching: Spring Cache, Redis, patterns, TTL, eviction | [spring/](spring/) (§5, alongside Module 5) | ✅ Done |
| 9 | Angular beginner→advanced (standalone, routing, RxJS, Signals, forms, DI, interceptors, guards) | [angular/](angular/) | ✅ Done |
| 10 | Testing: JUnit, Mockito, integration testing, Testcontainers | [testing/](testing/) | ✅ Done |
| 11 | JVM internals: memory model, GC, class loading, JIT, JFR/JMC, JMH | [java-advanced/jvm-internals/](java-advanced/jvm-internals/) | ✅ Done |
| 12 | AWS: IAM, VPC, EC2/ECS/EKS, Lambda, API Gateway, S3, SQS/SNS, EventBridge, Step Functions, CloudWatch, Secrets Manager, RDS, DynamoDB, CloudFront, Route53, Terraform, Well-Architected | [aws/](aws/) | ✅ Done |
| 13 | System Design: scalability, CAP/PACELC, replication, sharding, CQRS, Event Sourcing, Saga, Circuit Breaker, rate limiting, microservices, DDD, clean architecture, HA/DR, LLD/HLD, mock interview | [system-design/](system-design/) | ✅ Done |
| — | Cross-cutting interview notes and mock scenarios | [interview/](interview/) | ✅ Indexed |
| — | Cross-cutting exercises index | [exercises/](exercises/) | ✅ Indexed |
| — | Architecture diagrams, ADRs, glossary | [docs/](docs/) | ✅ Written |

Every module folder follows the same shape: `README.md` (theory, using a consistent teaching lens — what it is / why introduced / problem it solves / when (not) to use / trade-offs / performance implications / enterprise examples / common mistakes), `diagrams/` (Mermaid, with ASCII fallback where useful), code (`src/`), `EXPLANATION.md` (line-by-line walkthrough), `EXERCISES.md` (4-6 exercises, increasing difficulty, ending scenario-style), and `INTERVIEW.md` (beginner/intermediate/senior/scenario questions with ideal answers and follow-ups, tied to real companies).

### How the modules relate to each other

- `java-basics/` is the seed: every other module either extends its domain classes directly (`java-advanced/*`, `design-patterns/`, `testing/`) or re-expresses the same concepts for a different concern (`spring/`'s JPA entities, `system-design/`'s distributed Saga generalizing `OrderService.placeOrder`'s rollback logic).
- `spring/`, `security/`, `angular/`, and `testing/` were built concurrently and deliberately do **not** import each other's in-progress code — each states the contract/interface it assumes from the others in its own README. Module 5's actual REST contract vs. Module 9's assumed one is a good example of real drift worth reconciling yourself (see [exercises/README.md](exercises/README.md)'s cross-module exercises).
- `java-advanced/concurrency/` and `database/` solve the *same* stock-overselling race condition at two different layers (application-level locking vs. DB-level locking) — see both for the full picture.
- `aws/` and `system-design/` both cover the order-fulfillment saga: `aws/` from the "which AWS services implement this" angle, `system-design/` from the "here's the pattern and its trade-offs, with a full orchestrator LLD" angle.

## How to build/run each module

Every module's own README has the exact commands. Quick reference:

```bash
# Module 1 — plain Java, no build tool
cd java-basics/src/main/java && javac com/interviewprep/orders/**/*.java && java com.interviewprep.orders.Main

# Modules 2-3 (java-advanced) — plain Java, compiled against java-basics' sources too
javac -d out $(find java-basics/src/main/java java-advanced/file-io/src/main/java -name "*.java")
javac -d out $(find java-basics/src/main/java java-advanced/concurrency/src/main/java -name "*.java")

# Module 4 — design patterns, same two-source-root compile pattern
javac -d out $(find java-basics/src/main/java design-patterns/src/main/java -name "*.java")

# Module 5 — Spring Boot (needs Postgres + Redis, see spring/docker-compose.yml)
cd spring && docker compose up -d && mvn spring-boot:run

# Module 6 — standalone Spring Security demo
cd security && mvn spring-boot:run

# Module 9 — Angular (needs Node.js + Angular CLI)
cd angular && npm install && npm start

# Module 10 — tests (Testcontainers-backed integration test needs Docker)
cd testing && mvn test      # unit tests only
cd testing && mvn verify    # + Testcontainers integration test
```

`database/`'s SQL files run against a real Postgres instance; `aws/`'s Terraform and Lambda code are illustrative teaching artifacts, explicitly never meant to be applied against a real AWS account from this repo.
