# Java Full-Stack Interview Prep

A single, evolving teaching project for becoming interview-ready for **Senior Full Stack Java** roles (Java + Spring Boot + Angular + SQL + AWS + Security + System Design). The goal is not to ship software fast — it's to *understand* every layer well enough to defend it in a senior interview at companies like S&P Global, JPMorgan, Goldman Sachs, Microsoft, Amazon, Google, Oracle, Adobe, Salesforce, and Atlassian.

The full curriculum is defined in [MASTER_JAVA_FULLSTACK_INTERVIEW_PROMPT.md](MASTER_JAVA_FULLSTACK_INTERVIEW_PROMPT.md). This repo is built **module by module** — never all at once — following that spec's delivery process: explain concepts → README → diagrams → code → line-by-line explanation → exercises → interview questions → wait for confirmation before the next module.

## The running example: an Order/Inventory System

Every module reuses and extends the same small domain — customers, products, orders, inventory, and (later) payments — so that each new topic (persistence, REST, security, caching, messaging, system design) is demonstrated on a codebase you already understand, instead of a new toy example every time. It's deliberately close to real fintech/e-commerce interview scenarios: stock reservation under concurrency, order state transitions, transactional integrity, and eventually event-driven fulfillment.

## Prerequisites (not installed in every environment — install locally)

- JDK 21 (the project is written against 8→21 evolution, but code samples target 21 unless a module is specifically demonstrating older behavior)
- Maven 3.9+ (introduced when `spring/` starts)
- Node.js 20+ and Angular CLI (introduced when `angular/` starts)
- Docker (introduced when `database/`/Testcontainers modules start)
- PostgreSQL and/or Oracle (for the `database/` module — can also run via Docker)

This sandbox does not have `java`, `mvn`, `node`, or `docker` installed, so code here is written and reviewed carefully but must be compiled/run on your own machine. Each module's README includes the exact commands to do that.

## Roadmap / Module Index

| # | Module | Folder | Status |
|---|--------|--------|--------|
| 1 | Java 8→21 evolution, Core Java (OOP, Collections, Generics, Streams, Lambdas, Exceptions) | [java-basics/](java-basics/) | ✅ Done |
| 2 | File System APIs (java.io, java.nio, Path/Files, WatchService, ZIP/CSV/JSON/XML, serialization, large files) | [java-advanced/](java-advanced/) | ⬜ Not started |
| 3 | Multithreading & Concurrency (Executors, CompletableFuture, Locks, Atomics, Virtual Threads) | [java-advanced/](java-advanced/) | ⬜ Not started |
| 4 | SOLID, GRASP, GoF Design Patterns (wrong vs. correct) | [design-patterns/](design-patterns/) | ⬜ Not started |
| 5 | Spring Core, Spring Boot, Spring Data JPA, REST APIs | [spring/](spring/) | ⬜ Not started |
| 6 | Security: Spring Security, JWT, OAuth2, OIDC, SAML, OKTA, SCIM, MFA, RBAC, CORS, CSRF | [security/](security/) | ⬜ Not started |
| 7 | Databases: PostgreSQL & Oracle, SQL basic→advanced, execution plans, indexing, transactions | [database/](database/) | ⬜ Not started |
| 8 | Caching: Spring Cache, Redis, patterns, TTL, eviction | [database/](database/) or [spring/](spring/) | ⬜ Not started |
| 9 | Angular beginner→advanced (standalone, routing, RxJS, Signals, forms, DI, interceptors, guards) | [angular/](angular/) | ⬜ Not started |
| 10 | Testing: JUnit, Mockito, integration testing, Testcontainers | across modules | ⬜ Not started |
| 11 | JVM internals: memory model, GC, class loading, JIT, JFR/JMC, JMH | [java-advanced/](java-advanced/) | ⬜ Not started |
| 12 | AWS: IAM, VPC, EC2/ECS/EKS, Lambda, API Gateway, S3, SQS/SNS, EventBridge, Step Functions, CloudWatch, Secrets Manager, RDS, DynamoDB, CloudFront, Route53, Terraform, Well-Architected | [aws/](aws/) | ⬜ Not started |
| 13 | System Design: scalability, CAP/PACELC, replication, sharding, CQRS, Event Sourcing, Saga, Circuit Breaker, rate limiting, microservices, DDD, clean architecture, HA/DR | [system-design/](system-design/) | ⬜ Not started |
| — | Cross-cutting interview notes and mock scenarios | [interview/](interview/) | ⬜ Ongoing |
| — | Cross-cutting exercises index | [exercises/](exercises/) | ⬜ Ongoing |
| — | Architecture diagrams, ADRs, glossary | [docs/](docs/) | ⬜ Ongoing |

Each module folder contains its own `README.md` (theory + diagrams + interview notes) plus, once implemented, its own code, `EXPLANATION.md`, `EXERCISES.md`, and `INTERVIEW.md`.

## How to build/run (once a module has code)

Module-specific instructions live in that module's README. For the current module:

```bash
cd java-basics/src/main/java
javac com/interviewprep/orders/**/*.java
java com.interviewprep.orders.Main
```
