# Module 12 — AWS: Running the Order/Inventory System in the Cloud

**Domain used throughout:** the same Order/Inventory system from every prior module — `Customer`, `Product`, `Order`, `OrderLine`, `Inventory`, `OrderService` (Module 1), CSV/JSON export-import (Module 2), the Spring Boot REST API (`spring/`), the Postgres schema (`database/`), the JWT/OAuth2 security layer (`security/`), and the Angular frontend (`angular/`). This module does not introduce a new example — it asks one question over and over: **"how would this exact system actually run on AWS?"**

**Explain locally; do not deploy.** Per the master spec, this module is deliberately hands-off real infrastructure: no AWS CLI, no Terraform `apply`, no live account. Every `.tf` file and every AWS SDK/Lambda code sample here is a **teaching artifact** — realistic enough to read in an interview and structurally correct, but not a production-hardened module and never meant to be run against a real account from this repo. Where it matters, comments say so explicitly.

Companion files:
- [diagrams/architecture.md](diagrams/architecture.md) — full system architecture on AWS (VPC, subnets, compute, data, messaging, frontend delivery)
- [diagrams/event-driven-fulfillment-sequence.md](diagrams/event-driven-fulfillment-sequence.md) — sequence diagram of the `OrderPlaced` event flow through SNS/EventBridge → SQS → fulfillment
- [terraform/](terraform/) — illustrative HCL: `vpc.tf`, `rds.tf`, `s3.tf`, `sqs.tf`, `ecs.tf`
- [lambda/](lambda/) — a Java Lambda handler (`OrderEventHandler`) consuming SQS-triggered order events
- [EXPLANATION.md](EXPLANATION.md) — walkthrough of every Terraform file and the Lambda handler
- [EXERCISES.md](EXERCISES.md) — hands-on / design exercises, increasing difficulty
- [INTERVIEW.md](INTERVIEW.md) — beginner/intermediate/senior/scenario interview questions with ideal answers, tied to target companies

---

## 1. IAM — Identity and Access Management

### What it is
IAM is AWS's authentication and authorization system: **users** (long-lived human or legacy machine identities with passwords/access keys), **roles** (temporary credentials assumed by a person or, critically, by an AWS *resource* like an ECS task or Lambda function), and **policies** (JSON documents that grant or deny specific actions on specific resources).

### Why introduced / problem it solves
Every AWS API call — `s3:PutObject`, `rds:DescribeDBInstances`, `sqs:SendMessage` — is authorized against an IAM policy. Without IAM, "can this service call that API" would have no answer at all; with a naive answer ("give everything the root account's keys"), a single leaked credential compromises the entire account. IAM exists to make **least privilege** enforceable and auditable: each identity gets exactly the permissions its job requires, nothing more.

### Applied to the Order/Inventory system: the ECS task role
The `spring/` REST API, once containerized and running on ECS, needs to:
- read/write the `orders` bucket (Module 2 CSV/JSON exports) — `s3:PutObject`, `s3:GetObject` scoped to `arn:aws:s3:::orders-exports-bucket/*`
- send messages to the order-fulfillment SQS queue — `sqs:SendMessage` scoped to that queue's ARN
- connect to RDS Postgres — not an IAM permission at all for the connection itself (that's a security-group + DB credential concern, see Secrets Manager below), but `rds-db:connect` if using **IAM database authentication** instead of a password
- read its DB credentials and JWT signing key from Secrets Manager — `secretsmanager:GetSecretValue` scoped to those two specific secret ARNs

That's the **entire** policy. It does not get `s3:*`, it does not get access to buckets belonging to other modules' infrastructure, and it never gets `iam:*` (an app should never be able to modify its own permissions — that's how a compromised container becomes a compromised account). This is the **ECS task role** in `terraform/ecs.tf` — attached to the *task*, not baked into the container image or passed as environment variables.

```
# Illustrative least-privilege policy shape for the app's ECS task role
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": "arn:aws:s3:::orders-exports-bucket/*" },
    { "Effect": "Allow", "Action": "sqs:SendMessage",
      "Resource": "arn:aws:sqs:us-east-1:123456789012:order-fulfillment-queue" },
    { "Effect": "Allow", "Action": "secretsmanager:GetSecretValue",
      "Resource": ["arn:...:secret:orders/db-credentials-*", "arn:...:secret:orders/jwt-signing-key-*"] }
  ]
}
```

### When to use which construct
- **IAM roles** for anything that isn't a permanent human identity: EC2 instances, ECS tasks, Lambda functions, cross-account access, CI/CD pipelines. Roles issue **temporary** credentials (auto-rotating, typically 1-hour STS tokens) — nothing to leak in a git commit because nothing long-lived exists.
- **IAM users** only for humans who need console/CLI access outside of federated SSO (increasingly rare in modern setups — most enterprises use IAM Identity Center / SSO federated from Okta or Azure AD, which is exactly what `security/`'s OIDC coverage connects to conceptually).
- **Root account** for literally nothing in day-to-day operation. It exists to create the account and should have MFA enabled, its access keys deleted (not just unused — deleted), and be locked in a break-glass procedure for account-level emergencies only.

### Why root account access keys should never be used in an app
The root user has unconditional, unrestrictable access to every resource and every billing action in the account — there is no policy that can scope it down. An access key for it embedded in an application (even accidentally, via a misconfigured environment variable or a committed `.env` file) means one leaked string is a full account takeover: every S3 bucket, every RDS snapshot, every dollar of spend. A scoped IAM role's temporary credentials leaking is bounded damage (exactly the permissions above, and only until the token expires); a leaked root key is unbounded damage. AWS's own security recommendations put "delete your root access keys" as literally the first item on the account setup checklist.

### Trade-offs & common mistakes
- **Trade-off:** least-privilege policies take more upfront effort to scope correctly (you have to know exactly which actions/resources are needed) versus a broad `*` policy that "just works" during development. The cost of getting it wrong compounds — an over-permissioned role discovered in a security audit six months later is far more expensive to unwind (what actually depends on that extra permission?) than scoping it correctly on day one.
- **Common mistake:** attaching a policy to an **instance profile shared across unrelated services** — e.g. one IAM role used by both the order-fulfillment ECS service and an unrelated reporting job, so both inherit the union of permissions either one needs. Each distinct workload should get its own role.
- **Common mistake:** using `Resource: "*"` "temporarily" during development and never tightening it before production.
- **Enterprise example:** at a bank-scale org (JPMorgan/Goldman-style), IAM policy changes are themselves audited via CloudTrail (see §8) and often go through a policy-as-code review (e.g. AWS IAM Access Analyzer, or Terraform-plan-reviewed policy diffs) before merge — permissions are treated as a change requiring the same review rigor as application code.

---

## 2. VPC — Virtual Private Cloud

### What it is
A VPC is an isolated, logically-defined network within AWS — your own private slice of the cloud with its own IP address range, subnets, route tables, and gateways. Nothing in a VPC is reachable from the internet unless you explicitly wire it that way.

### Why introduced / problem it solves
Before VPCs (in EC2-Classic, long deprecated), all instances shared a flat network space with limited isolation between customers' workloads and awkward, coarse security controls. A VPC gives you the same segmentation disciplines you'd design on-prem — DMZ vs internal network — as a first-class, software-defined construct.

### Applied to the Order/Inventory system: public/private subnet split
```
Internet
   │
   ▼
[Internet Gateway]
   │
   ▼
PUBLIC subnets (2 AZs)         ← Application Load Balancer only
   │
   ▼ (traffic forwarded, ALB → private target group)
PRIVATE subnets (2 AZs)        ← ECS Fargate tasks running the Spring Boot API
   │
   ▼
PRIVATE subnets (2 AZs, isolated) ← RDS Postgres (no public IP, no route to IGW)
```
- The **Application Load Balancer** lives in **public subnets** — it's the only thing that needs a route to the internet gateway, because it's the one component designed to accept inbound internet traffic.
- The **ECS Fargate tasks** running the Spring Boot API live in **private subnets** — they have no public IP and cannot be reached directly from the internet, only via the ALB's forwarded traffic. Outbound calls they make (e.g. to Secrets Manager, or to a third-party payment gateway) go through a **NAT Gateway**.
- **RDS Postgres** lives in its own private subnets with **no route to the internet at all**, not even outbound via NAT — a database has no legitimate reason to make outbound internet calls, so that route simply doesn't exist, which is a stronger guarantee than a security group rule that could be misconfigured.

See [diagrams/architecture.md](diagrams/architecture.md) for the full visual, and [terraform/vpc.tf](terraform/vpc.tf) for the illustrative subnet/route-table HCL.

### NAT Gateway — why it exists
A NAT (Network Address Translation) Gateway sits in a **public** subnet and lets resources in **private** subnets initiate outbound internet connections (pulling a container image update, calling out to a third-party API, downloading OS patches) **without** being reachable from the internet inbound. It's a one-way door: private → internet is allowed, internet → private is not. Without it, private-subnet resources would have zero internet access at all, which is often too restrictive (they still need to reach AWS service endpoints not covered by VPC endpoints, or genuine third parties).

### Security Groups vs. Network ACLs
| | Security Group | Network ACL (NACL) |
|---|---|---|
| Applies to | Individual resource (ENI) — e.g. one ECS task, one RDS instance | Entire subnet |
| State | **Stateful** — a response to an allowed inbound request is automatically allowed out, no matching outbound rule needed | **Stateless** — inbound and outbound rules are evaluated independently; you must explicitly allow the return traffic |
| Rule type | Allow only | Allow **and** explicit Deny |
| Evaluation | All rules evaluated; if any matches, allowed | Rules evaluated **in numbered order**, first match wins |
| Typical use here | "Only the ALB's security group may reach the ECS task's security group on port 8080"; "Only the ECS task's security group may reach RDS's security group on port 5432" | A coarser, subnet-wide backstop — e.g. explicitly denying a known-bad IP range at the subnet level regardless of what any security group says |

**In practice**, most day-to-day access control for this system is done with **security groups** (resource-to-resource, e.g. "ALB SG → App SG on 8080", "App SG → RDS SG on 5432") because they're stateful and far less error-prone. NACLs are used sparingly as a coarse, explicit-deny safety net at the subnet boundary — genuinely defense-in-depth, not the primary control.

### When NOT to over-engineer this
A single VPC with 2 AZs, one public/private subnet pair, and one NAT Gateway is plenty for this system's scale. Multi-VPC architectures (hub-and-spoke with Transit Gateway, VPC peering across environments) only earn their complexity at genuine multi-team, multi-account, or multi-region scale — introducing them here would be solving a problem this system doesn't have yet.

### Trade-offs & performance implications
- **NAT Gateway cost**: billed per-hour **and** per-GB processed — for a system pushing large export files through outbound calls, this is a real line item to watch, not just a fixed cost. An alternative for AWS-service-only outbound traffic (S3, SQS, Secrets Manager) is a **VPC Gateway/Interface Endpoint**, which avoids the NAT Gateway's per-GB charge for that specific traffic entirely — a concrete, common cost optimization.
- **Multi-AZ subnets** (2+ Availability Zones) cost nothing extra in AWS billing terms but are essential for the reliability story (§12) — a single-AZ deployment has a hard availability ceiling no amount of application-level redundancy can fix, because the whole AZ can go down.
- **Common mistake:** giving RDS a public IP "just to make local development easier," then leaving it that way in a staging/production Terraform module. This single misconfiguration is one of the most common real-world data breach root causes; a security-group misconfiguration is only one mistake, but a database with no public IP at all can't be reached from the internet **no matter what** the security group says — layering the network-level control on top of the security-group control is why both matter.

---

## 3. Compute Choice — EC2 vs. ECS (Fargate) vs. EKS

### What it is
Three ways to run the containerized Spring Boot API (and, conceptually, a background fulfillment worker):
- **EC2**: you provision and manage virtual machines yourself, install a container runtime (or run the JAR directly on the JVM), handle OS patching, scaling, and health-replacement.
- **ECS (Elastic Container Service)**, specifically the **Fargate** launch type: AWS's own container orchestrator; Fargate removes the underlying EC2 instances entirely — you define a task (container image, CPU/memory, IAM role) and AWS runs it on infrastructure you never see or patch.
- **EKS (Elastic Kubernetes Service)**: managed Kubernetes control plane; you (or a team) still operate everything Kubernetes exposes — CRDs, ingress controllers, Helm charts, cluster autoscaler, node groups (unless also paired with EKS Fargate profiles).

### The decision for this system: **ECS on Fargate**

**The call, made explicitly:** for a mid-size Order/Inventory REST API + a background fulfillment consumer, **ECS on Fargate** is the right choice, not raw EC2 and not EKS. Justification:

- **vs. raw EC2**: EC2 means owning AMI patching, OS-level CVEs, instance right-sizing, and writing your own health-check/replace-unhealthy-instance logic (or bolting on an Auto Scaling Group + target-tracking policy to approximate what ECS gives natively). None of that operational surface teaches anything about the *order/inventory domain* — it's pure infrastructure toil for a workload that doesn't need VM-level control (no GPU, no custom kernel modules, no licensing that requires dedicated hosts).
- **vs. EKS**: Kubernetes' power is real — but it's power this system doesn't need. This is one REST API, one background consumer, and a handful of supporting resources. Kubernetes' value shows up at genuine multi-team, multi-service scale (tens of services, complex traffic-shaping/service-mesh needs, multi-cloud portability requirements) where its operational overhead (etcd/control-plane awareness even when managed, CRD sprawl, a dedicated platform team to run it well) pays for itself. Standing up EKS for a two-service system is bringing a distributed-systems platform to a job a simpler tool does just as well — a classic over-engineering trap interviewers specifically probe for ("would you actually reach for Kubernetes here, or are you cargo-culting it because it's popular?").
- **Fargate specifically over ECS-on-EC2**: ECS can also run tasks on EC2 instances you manage. Fargate removes that instance layer — no AMI/OS patching, no bin-packing tasks onto instances yourself, no capacity planning for the *hosts*, only for the *tasks*. The trade is a per-vCPU/per-GB-hour premium over equivalent raw EC2 pricing and less control (no privileged containers, no direct host access for exotic debugging). For this system's scale, that premium buys back real operational time and is worth it.

### Trade-off summary
| | EC2 | ECS Fargate | EKS |
|---|---|---|---|
| Operational overhead | Highest — OS patching, capacity planning, scaling logic | Low — only the task definition; no hosts to manage | High — Kubernetes expertise required even "managed" |
| Control / flexibility | Highest — full host access | Constrained — no host access, AWS-defined container runtime | Highest — full Kubernetes API surface |
| Cost model | Pay for instance uptime regardless of utilization | Pay per task's requested vCPU/memory while running | Control plane fee + node/Fargate profile costs |
| Right fit here | No — unnecessary toil for this scale | **Yes** — matches the system's actual complexity | No — over-engineered for 1-2 services |
| Right fit at larger scale | Rare (licensing/hardware edge cases) | Still viable well past this scale | Yes, once genuinely multi-team/multi-service |

### When to reconsider
If this system grew into 15+ independently-deployed microservices with different teams owning different services, needed a service mesh (mTLS between services, fine-grained traffic splitting for canary releases across many services at once), or needed to run identically across AWS and another cloud/on-prem for portability — that's the point EKS's overhead starts paying for itself. Naming that threshold explicitly (rather than either dogmatically avoiding Kubernetes forever or reaching for it by default) is exactly what a senior interview answer should demonstrate.

### Common mistakes
- Choosing EKS because it's the most resume-visible/trendy option rather than because the system's actual requirements call for it.
- Running stateful workloads (the database) *inside* ECS/EKS instead of using RDS — containers are excellent for stateless application tiers, not for a system-of-record relational database that needs point-in-time recovery, automated backups, and Multi-AZ failover that RDS provides natively.

---

## 4. Lambda + API Gateway — a serverless alternative

### What it is
**Lambda** runs code in response to an event without a provisioned, always-on server — you pay only for actual invocation time (rounded to the millisecond), and AWS handles all scaling, from zero to thousands of concurrent invocations. **API Gateway** provides an HTTP front door that can invoke a Lambda per route, giving you a REST (or HTTP) API with no server to run at all.

### Applied to the Order/Inventory system: CSV/JSON export-import as Lambda
Module 2 built CSV/JSON export/import as a batch operation on the Spring app. A fully serverless alternative for **that specific slice**: a user (or the Angular frontend) uploads a CSV of orders to an S3 bucket; an **S3 `ObjectCreated` event** triggers a Lambda that parses the CSV, validates rows, and writes resulting `Order`/`OrderLine` records to RDS (or emits `OrderPlaced` events onto EventBridge for downstream processing — see §6). No Fargate task needs to be running at all for this to work, and it costs nothing when no file is being uploaded.

```
S3 (orders-imports/ prefix)
   │  ObjectCreated:Put event
   ▼
Lambda (parses CSV, validates, writes to RDS / publishes OrderPlaced events)
```

This is genuinely a **different architectural choice**, not just "the same code deployed differently" — see the cold-start discussion below for why it fits *this* slice of the system well but wouldn't replace the whole REST API.

### Cold starts — the central trade-off
A Lambda function that hasn't run recently must be initialized before handling a request: download the deployment package, start the runtime (JVM start-up for Java Lambdas is one of the **slower** cold-start profiles among Lambda runtimes — often several hundred ms to a few seconds depending on package size and whether you're using plain Lambda vs. a framework like Spring Cloud Function), then run any static initialization in your handler class. A "warm" invocation (the execution environment is reused for a subsequent request) skips all of that and runs in milliseconds.

- For an **infrequent, tolerant-of-latency** trigger like a CSV import (a human uploads a file, waits a few seconds, gets a result) — a multi-second worst-case cold start is a complete non-issue.
- For a **latency-sensitive, high-frequency** synchronous API call (e.g. "add item to cart," expected to respond in tens of milliseconds under normal traffic) — cold starts are a real user-facing latency risk, especially on Java, especially at low/spiky traffic where the execution environment keeps getting recycled between requests.
- Mitigations exist (Provisioned Concurrency keeps N environments permanently warm at a **fixed cost**, GraalVM native-image compilation for Java to shrink cold-start time dramatically) but each adds either cost or build complexity — they don't make the trade-off disappear, they shift it.

### When serverless fits vs. a long-running Fargate service
| Signal | Favors Lambda + API Gateway | Favors ECS Fargate |
|---|---|---|
| Traffic pattern | Spiky, infrequent, or highly variable (batch import runs, webhook handlers) | Steady, predictable, sustained request volume |
| Latency sensitivity | Tolerant of occasional cold-start latency | Needs consistently low, predictable latency |
| Execution duration | Short-lived (Lambda has a 15-minute hard cap) | Can run indefinitely (a long-lived HTTP server, WebSocket connections) |
| Cost shape at this system's actual load | Would likely cost *more* than one small always-on Fargate service, once traffic is roughly constant | Cheaper at steady, non-trivial request volume — you're not paying a per-invocation premium on top of already-utilized compute |
| Operational shape | Fits event-driven, single-purpose functions cleanly (CSV import, a fulfillment step, a scheduled job) | Fits a cohesive, stateful-in-memory-cache, connection-pooled REST API better |

**Conclusion for this system:** the core Spring Boot REST API stays on ECS Fargate (§3) — it's a steady-traffic, connection-pooled (JDBC pool to RDS), always-warm workload where Lambda's per-invocation cost and cold-start risk don't pay off. CSV/JSON import/export, and the fulfillment consumer in §6, are excellent Lambda candidates — bursty, event-triggered, short-lived, and cheap to run at near-zero cost when idle.

### Common mistakes
- Wrapping an entire, always-hot Spring Boot application in a single Lambda "for cost savings" without measuring — often this is *more* expensive and *slower* than one small Fargate task once traffic is non-trivial and constant.
- Ignoring the 15-minute Lambda execution cap for a workload that could occasionally process an unusually large CSV file — Step Functions (§7) or breaking the work into smaller Lambda invocations is the fix, not raising a limit that doesn't exist to raise.

---

## 5. S3 — Simple Storage Service

### What it is
S3 is object storage: durable (11 nines), effectively infinitely scalable storage for blobs (files) organized into buckets, addressed by key, with no filesystem-style directory structure underneath (prefixes only *look* like folders in the console).

### Applied to the Order/Inventory system
The Module 2 CSV/JSON export files (a snapshot of orders/inventory) land in an `orders-exports-bucket`, organized by prefix and date: `exports/2026/07/31/orders-snapshot.csv`. Imports (§4) land in `imports/` and are consumed by the Lambda trigger.

### Versioning
S3 **versioning**, once enabled on a bucket, keeps every version of an object ever written under the same key instead of overwriting in place. For export files, this means an accidental re-export that clobbers `orders-snapshot.csv` doesn't destroy the previous day's version — it's still retrievable by version ID. This is the S3-level analogue of "never `UPDATE` without a `WHERE`" — a safety net against overwrite mistakes, at the cost of storing every version until a lifecycle rule expires it.

### Lifecycle policies — cost-tiering old exports
A lifecycle rule automates moving/deleting objects by age, without any application code:
```
exports/ prefix:
  0–30 days   → S3 Standard (frequent access expected)
  30–90 days  → S3 Standard-IA (Infrequent Access — cheaper storage, retrieval fee)
  90+ days    → S3 Glacier (very cheap storage, retrieval takes minutes-to-hours)
  365+ days   → expire (delete) — if retention policy allows
```
This directly matches how export snapshots are actually used in practice: today's export is queried often (someone's reconciling this week's orders), a 6-month-old export is almost never opened but must be retained for audit/compliance, and a multi-year-old export may have no retention justification left at all. See [terraform/s3.tf](terraform/s3.tf) for the illustrative lifecycle configuration block.

### When to use / when NOT to use S3
- Use it for: export/import files (this system), static frontend assets (§11), Lambda deployment packages, application logs shipped for long-term archival.
- Don't use it for: data that needs transactional consistency across multiple related writes, low-latency random-access reads at the row/field level (that's RDS/DynamoDB's job, §10), or as a substitute for a real database "because it's cheap" — S3 has no query language beyond basic prefix listing and S3 Select's limited SQL-like filtering.

### Trade-offs & performance implications
- Glacier retrieval isn't instant — **Expedited** (minutes), **Standard** (3-5 hours), or **Bulk** (5-12 hours) retrieval tiers each trade cost against wait time. Moving an export to Glacier is a real decision to make retrieval slower and rarer in exchange for a large storage-cost reduction — not appropriate for anything that might need same-day retrieval.
- S3 is strongly consistent for all operations as of December 2020 (an overwrite followed immediately by a read returns the new data) — worth knowing because older material describes S3 as only "eventually consistent," which is no longer accurate and is a good "keeping current" signal in an interview.

### Common mistakes
- Public bucket ACLs/policies left open by default — one of the most common real-world data-exposure incidents in the industry. Buckets storing this system's exports should have **Block Public Access** enabled at the bucket (or account) level unless there's a specific, reviewed reason not to (e.g. a bucket genuinely serving public static assets, see §11).
- Forgetting that enabling versioning without a lifecycle rule to clean up old versions means storage cost grows unbounded over time as every overwrite accumulates a new version forever.

---

## 6. SQS + SNS + EventBridge — event-driven order fulfillment

### What it is, concretely, for this system
When `OrderService.placeOrder(...)` (Module 1) succeeds, the system should tell the rest of the world "an order was placed" so fulfillment, notifications, and analytics can react — without the order-placement code needing to know or call any of those consumers directly. That's the event-driven pattern, and AWS gives you three different building blocks for it, each solving a different shape of the problem.

```
OrderService.placeOrder() succeeds
        │
        ▼
   Publish "OrderPlaced" event
        │
        ▼
┌──────────────────────────────────────────────┐
│  SNS Topic  (or EventBridge event bus)        │  ← fan-out point
└──────────────────────────────────────────────┘
        │                         │
        ▼                         ▼
  SQS: fulfillment-queue    SQS: notifications-queue
        │                         │
        ▼                         ▼
  Fulfillment Lambda/       Email/SMS notification
  Fargate consumer          consumer
        │
        ▼ (on repeated failure)
  Dead-Letter Queue (DLQ) — poison messages parked here for inspection
```

### SQS — point-to-point, competing consumers
SQS is a **queue**: a message is delivered to (and processed by) **one** consumer among a pool of competing consumers, then deleted. It's the right tool when you have work items that must each be processed exactly once by exactly one worker — e.g. "reserve inventory and create a shipment for this order" should not happen twice.
- **Why here**: the `fulfillment-queue` decouples "an order was placed" from "fulfillment processing," and — crucially — lets fulfillment scale independently (more consumers when the queue backs up) and survive a fulfillment-service outage (messages simply wait in the queue instead of being lost, up to the queue's retention period, default 4 days, configurable up to 14).
- **Dead-letter queue (DLQ)**: after a message fails processing a configured number of times (`maxReceiveCount`), SQS automatically moves it to a separate DLQ instead of retrying forever. This is the mechanism that turns "one malformed order event" into "one message quarantined for inspection" instead of "the fulfillment consumer stuck in an infinite retry loop, or worse, silently dropping the message." See [terraform/sqs.tf](terraform/sqs.tf).

### SNS — pub/sub fan-out
SNS is a **topic**: one published message is delivered to **every** subscriber (SQS queues, Lambda functions, HTTP endpoints, email/SMS) — not competing consumers, but independent copies to each subscription. It's the right tool when multiple, unrelated downstream systems all need to know the same thing happened.
- **Why here**: `OrderPlaced` genuinely has multiple independent interested parties — fulfillment, customer notifications, analytics/reporting — and none of them should have to coordinate with each other or know the others exist. SNS → fan-out to multiple SQS queues (the standard "fan-out" pattern: SNS topic with several SQS subscriptions) gives each consumer its own durable queue and its own retry/DLQ semantics, while the publisher only ever talks to one topic.

### EventBridge — schema-aware event bus with routing rules
EventBridge is a step beyond SNS: an **event bus** where consumers subscribe via **rules** that match on event *content* (source, detail-type, and arbitrary fields within the event JSON), not just "give me everything on this topic." It also supports a **schema registry** (discovering and versioning the shape of events flowing through the bus) and native integrations with 100+ AWS services and SaaS partners as event sources.
- **Why it can replace or complement SNS here**: instead of "everyone subscribed to the OrderPlaced topic gets every OrderPlaced event," EventBridge lets you write a rule like *"route to the fraud-check queue only if `detail.totalAmount > 10000`"* or *"route high-priority customer tiers to a separate, faster fulfillment queue"* — routing logic lives declaratively in the event bus configuration instead of being coded into every consumer (which would otherwise have to receive everything and filter it itself).

### Concrete trade-off table
| | SQS | SNS | EventBridge |
|---|---|---|---|
| Delivery model | One consumer per message (competing consumers) | Every subscriber gets a copy (fan-out) | Every *matching* subscriber gets a copy (content-based routing) |
| Filtering | None built-in (consumer filters after receipt) | Basic message-attribute filter policies | Rich, content-based rules against the full event payload |
| Schema awareness | None — opaque message body | None — opaque message body | Schema registry; can generate code bindings from a discovered schema |
| Ordering | Standard queues: no ordering guarantee; FIFO queues: strict order per message group | No ordering guarantee | No ordering guarantee (use SQS FIFO downstream if order matters) |
| Typical role here | The actual work queue consumers pull from | The fan-out point from one publish to many queues | An alternative/complementary fan-out point when routing needs to be content-aware, or when integrating many AWS-native event sources |
| This system's choice | **Always** present — every consumer of an event ultimately reads from its own SQS queue for durability + DLQ semantics | Simple case: publish `OrderPlaced` once, fan out to fulfillment + notification queues | Adopted once routing rules matter (e.g. splitting large/VIP orders to a different pipeline) — otherwise SNS's simpler model is enough and shouldn't be replaced just because EventBridge is newer |

### When to use / when NOT to use each
- Use **SQS alone** (no SNS/EventBridge in front) when there's genuinely only one consumer type for an event — no fan-out needed yet. Don't add SNS/EventBridge speculatively "in case we need fan-out later" if there is, today, exactly one consumer; that's premature complexity.
- Use **SNS** when fan-out is needed but routing is simple ("everyone subscribed gets everything," maybe with a coarse attribute filter).
- Use **EventBridge** when you need content-based routing, schema governance across many event producers/consumers, or you're already integrating multiple AWS services as event sources (e.g. reacting to an S3 upload *and* an RDS event *and* a custom application event, all through one bus with consistent tooling).

### Trade-offs & performance implications
- SQS Standard queues are **at-least-once** delivery — a consumer must be idempotent (processing the same `OrderPlaced` event twice must not double-reserve inventory or double-ship). This directly connects back to `Inventory.reserve()`'s idempotency concerns from Module 1/3 — the same discipline, now at the messaging layer.
- SQS FIFO queues guarantee ordering and exactly-once processing *within a message group* but cap throughput (300 msg/s without batching, 3000 with) versus Standard queues' effectively unlimited throughput — only reach for FIFO if event ordering is a genuine business requirement (e.g. status transitions must be applied in order), not by default.
- DLQ messages need active monitoring (a CloudWatch alarm on `ApproximateNumberOfMessagesVisible` on the DLQ, §8) — a DLQ that silently accumulates poison messages with no alerting is just a slower, quieter version of dropping them.

### Common mistakes
- Treating SNS or EventBridge as durable storage for undelivered messages — if a subscriber's SQS queue isn't there to catch it, a direct Lambda/HTTP subscription that fails has much weaker retry guarantees than a queue-backed one. Always put an SQS queue (with a DLQ) between the fan-out point and any consumer that must not lose messages.
- Non-idempotent consumers — assuming "the event will only arrive once" when SQS's at-least-once contract explicitly does not promise that.

---

## 7. Step Functions — orchestrating the order fulfillment saga

### What it is
AWS Step Functions is a managed state machine service: you define **states** (a Lambda invocation, a wait, a choice/branch, a parallel fan-out) and the **transitions** between them in JSON (Amazon States Language), and Step Functions executes and tracks that state machine's progress, including retries, timeouts, and error-driven branching, with a visual execution history for every run.

### Applied to the Order/Inventory system: the fulfillment saga, mechanically
This previews the **Saga pattern** that `system-design/` covers in full — this module's job is only to explain **how Step Functions mechanically models it**, not to re-teach saga theory.

```
Start
  │
  ▼
[Reserve Inventory]  (Lambda: calls Inventory.reserve() equivalent)
  │ success                         │ failure
  ▼                                 ▼
[Charge Payment]              [Fail: Insufficient Stock] → End
  │ success        │ failure
  ▼                ▼
[Ship Order]   [Compensate: Release Inventory] → End (failed)
  │ success        │ failure
  ▼                ▼
[Notify         [Compensate: Refund Payment]
 Customer]           │
  │                  ▼
  ▼            [Compensate: Release Inventory]
 End (success)       │
                      ▼
                 End (failed, fully compensated)
```

- Each forward step (`Reserve Inventory`, `Charge Payment`, `Ship Order`, `Notify Customer`) is a **Task state**, typically invoking a Lambda or another AWS service integration directly (Step Functions can call SQS, SNS, DynamoDB, ECS `RunTask`, and dozens of other services natively as a task, not just Lambda).
- Each failure path branches (via a **Catch** on the task state) into a **compensating transaction** — the mechanism that undoes a prior step's real-world effect, because these are separate service calls, not one database transaction that can simply `ROLLBACK`. This is exactly the manual-rollback problem `OrderService.placeOrder()` solved by hand with an `ArrayDeque` in Module 1 — Step Functions is the AWS-native, distributed-systems-scale version of that same idea: **when you can't get a single ACID commit across services, you must explicitly model the undo path.**
- Step Functions retries a failed task automatically per a configurable **retry policy** (exponential backoff, max attempts) before falling through to the `Catch` branch — so transient failures (a momentary network blip calling the payment service) don't trigger a full compensation cascade for something that would have succeeded on retry.

### Why Step Functions over hand-rolling the orchestration in application code
- **Visibility**: every execution's state transitions, inputs, and outputs are visible in the console/API — invaluable for debugging "why did order #4521 end up partially fulfilled" without grepping through distributed logs across four Lambdas.
- **Durability**: the orchestration state itself survives a Lambda cold-start, a deploy, or a transient AWS outage — it's not held in the memory of a single running process that could crash mid-saga.
- **Built-in retry/timeout/catch semantics** — declarative, not another bespoke retry-loop implementation to maintain per workflow.

### When to use / when NOT to use
- Use Step Functions when a business process spans multiple independent service calls with a real need for compensating actions on failure, and where visibility into "where exactly did this specific order get stuck" matters operationally.
- Don't reach for Step Functions for a process that's genuinely a single service's local transaction — that's massive overkill for something a `@Transactional` method already handles correctly and atomically (Module 5's Spring transaction coverage). Step Functions earns its cost specifically at the *cross-service* boundary where ACID transactions are unavailable.

### Trade-offs & performance implications
- Standard Step Functions workflows are billed per state transition; Express workflows (higher throughput, shorter-duration, at-least-once semantics) are billed per invocation/duration instead — the fulfillment saga described here (relatively low volume, needs exactly-once-per-attempt semantics and long execution history retention) fits **Standard**, while a very high-volume, short-lived event-processing workflow would fit **Express** better.
- Adds latency versus an in-process function call — each state transition is an AWS API round trip. Fine for a fulfillment process measured in seconds-to-minutes; wrong tool for a sub-millisecond hot path.

### Common mistakes
- Modeling compensations as an afterthought instead of designing them alongside the forward path — "what undoes this specific step" should be answered when the step is designed, not discovered during the first real failure in production.
- Forgetting Step Functions' compensations are **not automatic** the way a database `ROLLBACK` is — you must explicitly define and wire the `Catch` → compensating-task path for every state that has a real-world side effect.

---

## 8. CloudWatch + CloudTrail — observability vs. audit

### What it is
Two different questions, two different services:
- **CloudWatch**: "what is my application doing right now, and is it healthy?" — metrics (CPU/memory utilization, request latency, queue depth), logs (application/container stdout, structured JSON logs), and alarms (trigger an action when a metric crosses a threshold).
- **CloudTrail**: "who did what to my AWS account, and when?" — an immutable log of every API call made against the account (console, CLI, SDK, another AWS service acting on your behalf), including the identity that made it, the source IP, and the request/response.

### Applied to the Order/Inventory system
- **CloudWatch metrics/alarms**: ECS task CPU/memory utilization (trigger auto-scaling or paging when sustained high), ALB target 5xx rate (a spike means the Spring Boot app is failing requests), SQS `ApproximateAgeOfOldestMessage` on the fulfillment queue (a growing value means fulfillment consumers can't keep up — a leading indicator before customers notice delayed shipments), and the DLQ's message count (§6 — should alarm at `>0`, since **any** DLQ message is worth a human look).
- **CloudWatch Logs**: the Spring Boot app's structured logs (including the `InsufficientStockException` WARN-level logs from Module 1's exception-handling design) ship here via the ECS awslogs driver, queryable with **CloudWatch Logs Insights** (e.g. "show me every WARN log mentioning a specific SKU in the last hour" without SSHing anywhere — there's nowhere to SSH to, on Fargate).
- **CloudTrail**: answers a fundamentally different class of question — "who changed the IAM policy on the ECS task role last Tuesday, and what did it allow before/after?", "who deleted that RDS snapshot?", "did anyone call `iam:CreateAccessKey` for the root account?" This is the audit trail a SOC 2 / PCI-DSS / bank-grade compliance review asks for, and it answers questions about **account-level control-plane changes**, not application behavior.

### The security/compliance distinction, stated plainly
CloudWatch tells you the application is unhealthy; CloudTrail tells you **who was responsible for a change to the infrastructure or permissions** that might explain why. A production incident investigation typically needs both: CloudWatch metrics/logs to find *when* something broke and *what* the application was doing, CloudTrail to find *whether a permissions or configuration change* (and by whom) correlates with that time window. Treating them as interchangeable ("we have logs, so we don't need CloudTrail") is a real gap auditors specifically look for — CloudTrail is frequently a compliance-mandated control independent of whatever application observability tooling exists.

### When to use / when NOT to use
- CloudWatch alarms should back **every** SLO-relevant metric (latency, error rate, queue backlog) — under-alarming means outages are discovered by customers first.
- Don't alarm on every possible metric indiscriminately — alarm fatigue (too many low-signal alerts) trains responders to ignore pages, which is worse than having fewer, well-chosen alarms tied to actual customer impact.
- CloudTrail should be enabled account-wide (an "organization trail" in a multi-account setup) from day one, not turned on reactively after an incident — by the time you need the audit trail, it's too late to have started collecting it.

### Trade-offs & performance implications
- CloudWatch Logs storage cost scales with volume and retention — verbose DEBUG-level logging left on in production is a real, recurring cost, not just a performance concern; set log retention policies explicitly (default is *indefinite*, which silently accumulates cost) and use log levels deliberately.
- CloudTrail has a small inherent delivery delay (typically within 15 minutes for the management-event log) — it's an audit trail, not a real-time security control; pairing it with **GuardDuty** (anomaly detection over CloudTrail/VPC Flow Logs/DNS logs) is how "audit log" becomes closer to "real-time alert."

### Common mistakes
- Logging sensitive data (customer PII, full credit card numbers, JWTs, DB passwords) to CloudWatch Logs in plaintext — logs are a data-exposure surface too, and this is a very common real-world compliance violation.
- Assuming CloudTrail alone constitutes "monitoring" — it tells you about account/API activity, not application health; a system needs both, not one instead of the other.

---

## 9. Secrets Manager

### What it is
Secrets Manager stores sensitive values (DB credentials, API keys, JWT signing keys) encrypted at rest (via KMS), retrievable at runtime via an authenticated API call, with built-in support for **automatic rotation**.

### Applied to the Order/Inventory system
The Spring Boot app needs two secrets at startup: the RDS Postgres connection credentials (Module 5/`database/`) and the JWT signing key (`security/`). The **wrong** ways to supply these, all still common in the wild:
- **Hardcoded in source** — visible to anyone with repo access, forever, in git history even after "removal."
- **Plain environment variables** in the ECS task definition — better than hardcoding, but still visible to anyone who can `DescribeTaskDefinition` (a broader set of people/roles than should be able to read a DB password) and shows up unencrypted in the task definition JSON, CloudFormation/Terraform state, and potentially CI/CD logs.

**The correct pattern**: the ECS task role (§1) is granted `secretsmanager:GetSecretValue` scoped to exactly the two secret ARNs it needs. At container startup, the application (or an ECS task definition `secrets` block, which injects the value as an environment variable **at launch time**, resolved by ECS itself calling Secrets Manager — never appearing in the task definition JSON in plaintext) retrieves the current secret value. The secret's plaintext value never appears in source control, CI/CD logs, or the task definition — only the ARN reference does.

### Automatic rotation
Secrets Manager can invoke a rotation Lambda on a schedule (e.g. every 30/60/90 days) that: generates a new credential, updates it in the target system (e.g. `ALTER USER ... PASSWORD ...` on RDS — Secrets Manager has native rotation support for RDS specifically), and updates the stored secret — all without human involvement and, critically, without an application redeploy, since the app always fetches the *current* value at startup (or via a short-TTL cache during long-lived processes). This closes a real operational gap: a manually-rotated credential is a credential that, in practice, almost never gets rotated, because it requires coordinated, error-prone manual work across the secret store and every consumer.

### When to use / when NOT to use
- Use Secrets Manager for anything an application needs to authenticate with another system: DB credentials, JWT signing keys, third-party API keys.
- **Parameter Store** (part of Systems Manager, not Secrets Manager) is a cheaper alternative for **non-rotating, less sensitive configuration** (feature flags, non-secret config values) — it can also encrypt values via KMS but lacks Secrets Manager's native rotation integrations and per-secret pricing is different (Parameter Store's standard tier is free; Secrets Manager charges per secret per month). Don't pay for Secrets Manager's rotation machinery for values that never need to rotate and aren't truly secret.

### Trade-offs & performance implications
- Every `GetSecretValue` call is a network round trip — fetch once at startup and cache in memory (with a sane refresh interval if rotation is enabled) rather than calling Secrets Manager on every request; the latter adds needless latency and cost to every single API call the app serves.
- Rotation Lambdas must handle the **overlap window** correctly (old credential must keep working until every consumer has picked up the new one) — a naive rotation that invalidates the old credential immediately can cause a brief outage for any process still holding the old value; AWS's rotation Lambda templates handle this via a staged (`AWSCURRENT`/`AWSPENDING`/`AWSPREVIOUS`) versioning scheme specifically to avoid it.

### Common mistakes
- Fetching secrets on every request instead of caching them — unnecessary latency, cost, and API rate-limit risk.
- Granting a role `secretsmanager:GetSecretValue` on `Resource: "*"` instead of scoping to the exact secret ARNs the app needs — the same least-privilege lesson from §1, applied specifically here because secrets are exactly the resource where over-broad access is most damaging.

---

## 10. RDS vs. DynamoDB

### What it is
- **RDS**: managed relational databases (Postgres, MySQL, Oracle, SQL Server, MariaDB) — AWS handles patching, backups, Multi-AZ failover, and read replicas, but the data model is still fully relational: tables, foreign keys, joins, transactions.
- **DynamoDB**: a managed NoSQL key-value/wide-column store — single-digit-millisecond latency at effectively any scale, no joins, no foreign keys, schema-flexible beyond the partition/sort key, and two capacity modes (**provisioned**, where you set read/write capacity units ahead of time, or **on-demand**, where you pay per request with no capacity planning).

### This system's actual choice: RDS Postgres as the system of record
`database/` builds the canonical schema on **Postgres via RDS** — `orders`, `order_lines`, `products`, `customers`, `inventory` as related tables with foreign keys and ACID transactions. This is correct because the domain's core integrity guarantees are inherently **relational**: an `OrderLine` must reference a real `Product`, `Order.totalAmount()` derives from a join-and-sum over lines, and `OrderService.placeOrder()`'s all-or-nothing reservation logic (Module 1) is exactly what a relational transaction (`@Transactional`, Module 5) is built to guarantee atomically. None of that maps cleanly onto a key-value model without re-implementing referential integrity and multi-row atomicity in application code — which is precisely the kind of hand-rolled correctness work a relational database exists to remove.

### When DynamoDB would earn a place *alongside* RDS (not instead of it)
Two concrete candidates from this exact system:
1. **An order-event audit log** — an append-only record of every state transition (`OrderStatus` change, inventory reservation, payment attempt) for every order, written far more often than it's read, almost always accessed by a single, simple key (`orderId` + timestamp), and never joined against anything else. This is DynamoDB's ideal access pattern: high write throughput, simple partition-key lookups, no relational structure needed.
2. **A session store** for the Spring Security JWT layer (`security/`) — if moving from stateless JWTs to a model needing server-side session/refresh-token tracking, session data is naturally key-value (session ID → session blob), needs single-digit-millisecond reads at high volume, and has no relational structure to preserve.

### Concrete trade-off table
| | RDS (Postgres) | DynamoDB |
|---|---|---|
| Data model | Relational — tables, foreign keys, joins | Key-value / wide-column — partition key (+ optional sort key), no joins |
| Schema | Fixed schema, enforced by the database (columns, types, constraints) | Schema-flexible per item beyond the key structure |
| Transactions | Full ACID, multi-row, multi-table | Transactions exist (`TransactWriteItems`) but are scoped and costlier; not the primary design center |
| Query flexibility | Arbitrary SQL — ad hoc joins, aggregations, `WHERE` on any column (with the right index) | Query only by partition key (+ sort key range) or a defined secondary index — ad hoc queries on arbitrary attributes require a Global Secondary Index designed in advance, or a full scan (expensive, avoid) |
| Latency at scale | Single-digit-to-double-digit ms typical, but can degrade under heavy load/lock contention or missing indexes | Single-digit ms, consistently, even at very high request volume — this is DynamoDB's headline strength |
| Capacity | Instance-sized (vCPU/RAM you provision), read replicas for read scaling | Provisioned (set RCU/WCU ahead of time, cost-predictable) or On-Demand (auto-scales instantly, pay-per-request, better for spiky/unpredictable traffic) |
| Right fit here | System of record: orders, products, customers, inventory | A specific high-throughput, simple-access-pattern table: order-event audit log, session store |

### Trade-offs & performance implications
- Reaching for DynamoDB "everywhere, because it scales better" for data that's genuinely relational just relocates the referential-integrity and multi-row-atomicity work into application code, with none of the database-enforced guarantees `Order`/`OrderLine`/`Inventory`'s foreign keys and transactions currently give for free — a real regression, not a scaling win, for this system's core data.
- Reaching for RDS for a write-heavy, simple-key audit log means paying for relational query flexibility and join capability you'll never use, while fighting write throughput/connection-pool limits an audit log genuinely can push against at scale.
- DynamoDB On-Demand capacity is the pragmatic default for unpredictable or bursty traffic (this system's order-event volume would plausibly spike during a flash sale — see EXERCISES.md); Provisioned capacity with auto-scaling is more cost-efficient once traffic is well-understood and relatively steady.

### Common mistakes
- Designing a DynamoDB table's key structure as an afterthought — unlike RDS (where you can add an index or rewrite a query later fairly cheaply), DynamoDB access patterns must largely be designed **up front**, because the partition/sort key structure determines what queries are even possible without an expensive full table scan.
- Using RDS with no read replicas for a read-heavy reporting workload that's contending with transactional order-placement traffic on the same primary instance — a classic case for adding a read replica (or considering whether that specific reporting need is actually a better DynamoDB/audit-log candidate).

---

## 11. CloudFront + Route53 — delivering the Angular frontend

### What it is
- **CloudFront**: AWS's CDN — caches content at edge locations worldwide, close to end users, in front of an origin (here, an S3 bucket holding the Angular production build).
- **Route53**: AWS's DNS service, plus health-check-based routing/failover.

### Applied to the Order/Inventory system
The `angular/` frontend's production build (`ng build` output — static HTML/CSS/JS) is uploaded to an S3 bucket configured for static website hosting, but that bucket is **not** made public directly — CloudFront sits in front of it (using an **Origin Access Control**) as the only thing allowed to read from the bucket, and CloudFront is what's actually exposed to the internet.

```
User's browser
   │  DNS lookup for app.example.com
   ▼
Route53  (returns CloudFront's domain, or fails over per health check)
   │
   ▼
CloudFront (edge-cached, HTTPS via ACM certificate)
   │  cache miss
   ▼
S3 bucket (Angular production build — private, only CloudFront can read it)
```

Meanwhile, API calls from the Angular app go to the ALB → ECS Fargate path (§2/§3) — CloudFront/S3 serves **only** the static frontend assets; it is not in the path for the actual REST API traffic (a distinct origin/behavior would be configured if you wanted CloudFront in front of the API too, e.g. for edge caching of specific GET endpoints — a reasonable future optimization, not assumed here).

### Why CloudFront in front of S3, not S3 alone
- **Latency**: edge caching means a user in Singapore gets the Angular bundle from a nearby edge location, not a round trip to wherever the S3 bucket's region actually is.
- **HTTPS**: S3 static website hosting endpoints don't support HTTPS with a custom domain directly; CloudFront does, via an ACM (AWS Certificate Manager) certificate.
- **Security**: keeping the S3 bucket private and only CloudFront-readable (via Origin Access Control) avoids the "public bucket" risk class entirely for frontend assets, while still serving them globally.

### Route53 — DNS and health-check-based failover
Route53 resolves `app.example.com` to the CloudFront distribution (frontend) and `api.example.com` to the ALB (backend). Beyond basic DNS, Route53 supports **health checks**: it can poll an endpoint (e.g. the ALB's `/health` route backed by the Spring Boot app) and, on failure, automatically stop routing traffic to it — relevant in a multi-region DR setup where Route53 fails over DNS resolution from a primary region's ALB to a secondary region's, without any manual DNS change during an incident.

### When to use / when NOT to use
- Use CloudFront in front of any publicly-served static content — there's essentially no scenario where serving a public SPA build directly from S3 (or, worse, from within the Fargate app itself) is preferable once real users are geographically distributed.
- Route53 health-check failover is worth the added complexity once there's an actual second region/environment to fail over to — configuring it with nothing on the other end to fail over to is complexity without benefit.

### Trade-offs & performance implications
- CloudFront cache invalidation after a new Angular deploy costs money per invalidation path (or can be avoided by versioned asset filenames + a cache-busting index.html with a short TTL — the standard SPA-on-CDN deployment pattern).
- Route53 health checks add a small recurring cost per check and introduce **failover latency** bounded by DNS TTLs — clients caching a stale DNS answer won't see a failover instantly; keep TTLs on the relevant records low enough (without going so low that resolver load/cost becomes an issue) to bound real failover time.

### Common mistakes
- Making the S3 bucket itself public "so CloudFront can read it" — Origin Access Control (or the older Origin Access Identity) exists specifically so the bucket can stay fully private while CloudFront alone is granted read access.
- Forgetting to invalidate (or version) cached `index.html` after a deploy — users keep getting a cached HTML shell that references old, now-deleted JS bundle filenames, breaking the app until the cache expires.

---

## 12. AWS Well-Architected Framework applied to this system

The Framework's six pillars, each with **one concrete decision already made above**, named and justified:

1. **Operational Excellence** — *"can you understand and operate this system without heroics?"* Decision: CloudWatch Logs Insights + structured JSON logging from the Spring Boot app (§8), rather than relying on SSH-and-`grep` (which isn't even possible on Fargate — there's no host to SSH into). Operations are performed through AWS-native tooling that's queryable and shareable across the team, not tribal knowledge of one engineer's debugging habits.
2. **Security** — *"is access minimized and auditable?"* Decision: the ECS task's IAM role is scoped to exactly three permissions (§1) rather than a broad managed policy, and DB/JWT secrets come from Secrets Manager rather than plaintext environment variables (§9) — least privilege applied concretely, not as a slogan.
3. **Reliability** — *"does the system tolerate the failures that will actually happen?"* Decision: RDS Multi-AZ (automatic failover to a standby in a second AZ on primary failure) plus the VPC's 2-AZ subnet layout for the ECS service (§2/§3) — a single AZ outage (which does happen; AWS AZs have had real, documented outages) doesn't take the whole system down.
4. **Performance Efficiency** — *"is the right tool doing the job, at the right cost of complexity?"* Decision: ECS Fargate over EKS for this system's actual scale (§3) — matching operational/architectural complexity to genuine need rather than over- or under-provisioning capability.
5. **Cost Optimization** — *"is money being spent on things that matter?"* Decision: S3 lifecycle policies moving old exports to Glacier (§5), and DynamoDB On-Demand (not over-provisioned fixed capacity) for the bursty order-event audit log (§10) — paying for actual usage shape rather than worst-case-always capacity.
6. **Sustainability** — *"is resource usage minimized for the workload's actual needs?"* Decision: Fargate's shared, AWS-optimized infrastructure (versus dedicated, likely-underutilized EC2 instances sized for peak-but-rarely-hit load) means AWS can pool and better-utilize physical hardware across many customers' Fargate tasks — right-sizing compute to actual task requirements (rather than round-tripping up to the nearest EC2 instance size and leaving capacity idle) is a direct, measurable reduction in wasted energy, not just a cost side-effect.

**The point for an interview**: naming a pillar in the abstract ("we care about security") is weak; pointing at a specific decision and the trade-off it embodies ("we chose Fargate over EKS specifically because of X, which costs us Y") is what "Well-Architected" thinking actually looks like in practice, and it's how a senior candidate is expected to talk about architecture trade-offs rather than reciting pillar names.

---

## Next module

`system-design/` picks up the **Saga pattern** introduced mechanically here (§7) and covers it as a general distributed-systems pattern (alongside CQRS, Event Sourcing, Circuit Breaker, and the rest of the resilience/scalability toolkit) — this module intentionally stopped at "how Step Functions models a saga," not "what a saga is and when to use one in general," to avoid duplicating that coverage.
