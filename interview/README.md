# interview/

Every module has its own `INTERVIEW.md` with topic-specific questions (beginner/intermediate/senior/scenario, tied to real companies). This folder is the cross-cutting index plus material that spans multiple modules at once.

## Index of every module's INTERVIEW.md

| Module | Interview questions |
|---|---|
| 1 — Java basics | [java-basics/INTERVIEW.md](../java-basics/INTERVIEW.md) |
| 2 — File System APIs | [java-advanced/file-io/INTERVIEW.md](../java-advanced/file-io/INTERVIEW.md) |
| 3 — Concurrency | [java-advanced/concurrency/INTERVIEW.md](../java-advanced/concurrency/INTERVIEW.md) |
| 4 — Design Patterns | [design-patterns/INTERVIEW.md](../design-patterns/INTERVIEW.md) |
| 5/8 — Spring & Caching | [spring/INTERVIEW.md](../spring/INTERVIEW.md) |
| 6 — Security | [security/INTERVIEW.md](../security/INTERVIEW.md) |
| 7 — Database | [database/INTERVIEW.md](../database/INTERVIEW.md) |
| 9 — Angular | [angular/INTERVIEW.md](../angular/INTERVIEW.md) |
| 10 — Testing | [testing/INTERVIEW.md](../testing/INTERVIEW.md) |
| 11 — JVM Internals | [java-advanced/jvm-internals/INTERVIEW.md](../java-advanced/jvm-internals/INTERVIEW.md) |
| 12 — AWS | [aws/INTERVIEW.md](../aws/INTERVIEW.md) |
| 13 — System Design | [system-design/INTERVIEW.md](../system-design/INTERVIEW.md) + [system-design/mock-interview.md](../system-design/mock-interview.md) (full transcript) |

## Cross-module material worth knowing about

- **[system-design/mock-interview.md](../system-design/mock-interview.md)** is the closest thing in this repo to a full mock loop — a complete "design an order system for Black Friday-scale traffic" transcript (clarifying questions → capacity estimation → HLD → deep-dives → trade-off pushback → debrief). Do this one last, after at least `java-basics/`, `spring/`, `database/`, and `system-design/`'s own README — it assumes you can reason across all of them at once.
- Several modules explicitly reference each other's interview-relevant decisions instead of duplicating them: `java-advanced/concurrency/INTERVIEW.md` closes the race-condition question `java-basics/EXERCISES.md` (exercise 5) opens; `database/INTERVIEW.md`'s locking questions and `java-advanced/concurrency/INTERVIEW.md`'s locking questions are the same underlying problem (over-selling stock) at two different layers — a strong senior-level answer names both and explains why you'd want the DB-level guarantee even if the application-level fix is also in place (defense in depth, and the DB is the last line of defense against a bug in any calling code, not just this one).
- If you're short on time before an interview: read every module's README "Common mistakes" section first (fastest signal-to-noise), then this folder's mock interview, then work backward into the specific `INTERVIEW.md` files for the company/role's likely emphasis (e.g. heavier `database/` + `security/` prep for finance-sector interviews, heavier `system-design/` + `aws/` for Amazon/Google/Microsoft).

See the root [README.md](../README.md) for the full module roadmap.
