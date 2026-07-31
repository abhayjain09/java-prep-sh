# Module 12 — Walkthrough: Terraform Files & the Lambda Handler

This walks through every file in [terraform/](terraform/) and [lambda/](lambda/) in dependency order, connecting each resource/decision back to the concept explained in [README.md](README.md). As with `java-basics/EXPLANATION.md`, the "why" lives mostly in the files' own inline comments — this file adds narrative and cross-references.

**Reminder:** none of this is applied anywhere. Reading it should feel like reading a well-commented PR that hasn't merged yet, not a runbook.

## `terraform/vpc.tf`

This is the foundation every other `.tf` file depends on (RDS needs subnets, ECS needs subnets and security groups, everything needs the VPC to exist first) — which is also why it's read first here.

- `aws_vpc.orders_vpc` — one VPC, `10.0.0.0/16`. `enable_dns_hostnames = true` matters specifically because RDS and ECS service discovery both rely on DNS resolution working inside the VPC; without it, resources would only be reachable by IP, which breaks the moment an instance is replaced and gets a new one.
- Three `for_each`-driven subnet resources (`public`, `private_app`, `private_data`) instead of three separate `resource` blocks per AZ — this is idiomatic Terraform for "the same shape, repeated with different inputs," and it mirrors README.md §2's three-tier subnet split exactly: public (ALB, NAT), private-app (ECS), private-data (RDS).
- `aws_nat_gateway.orders_nat` is deliberately singular in this teaching file (comment calls out that a real setup would run one per AZ) — a concrete, named simplification rather than a silent one, so it's clear this file trades production-grade resilience for readability.
- The three `aws_route_table` resources are the mechanical enforcement of the "public/private/isolated" story: `public` routes `0.0.0.0/0` to the Internet Gateway, `private_app` routes it to the NAT Gateway instead, and `private_data` has **no default route at all** — not "a security group blocks it," but "there is no path out of this subnet to the internet, period." This is the network-layer half of defense-in-depth that README.md §2 argues for.
- The three `aws_security_group` resources (`alb`, `app`, `rds`) chain by **security-group reference**, not CIDR block: `app`'s ingress rule names `aws_security_group.alb.id` as its source, and `rds`'s ingress rule names `aws_security_group.app.id`. This is what "stateful, resource-to-resource" (README.md §2's SG-vs-NACL table) looks like in actual HCL — nobody had to hardcode an IP range that would break the moment a task's IP changed (which, on Fargate, happens on every deploy).

## `terraform/rds.tf`

Depends on `vpc.tf`'s `private_data` subnets and `rds` security group.

- `aws_db_subnet_group.orders` exists because RDS needs to be told *which* subnets it may place instances/standbys into — it can't just be dropped into "the VPC" generically.
- `multi_az = true` is the single line that mechanically implements README.md §12's Reliability-pillar decision — everything else in the file (encryption, backup retention, deletion protection) is standard hardening, but Multi-AZ is the one directly tied to "tolerates the failures that will actually happen."
- `manage_master_user_password = true` is worth dwelling on: it's the Terraform-level expression of README.md §9's Secrets Manager story. Without it, a real config would need a `password` argument taking a literal string (or a `var.db_password` — either way, something sensitive ends up in Terraform state, which is itself a secret-handling problem). Letting RDS generate and own the secret sidesteps that entirely; the app never needs the Terraform operator to hand it a password at all.
- The commented-out read-replica resource at the bottom isn't dead weight — it's there because README.md §10 names "no read replica for a read-heavy reporting workload" as a common mistake, and this shows the concrete one-resource fix, without actually provisioning it (since nothing here should be applied).

## `terraform/s3.tf`

Two buckets, deliberately structured to mirror README.md §5 (exports) and §11 (frontend) as distinct concerns.

- `aws_s3_bucket_public_access_block` is applied to **both** buckets, unconditionally — this is the single resource that would have prevented the "public bucket" class of incident named as a common mistake in §5. Even the frontend bucket, which serves public content, stays blocked at the S3 level; CloudFront's Origin Access Control (mentioned in comments, not modeled as a full resource here to keep the file S3-focused) is the only thing allowed to read it, via a bucket policy scoped to CloudFront's ARN rather than a public ACL.
- `aws_s3_bucket_lifecycle_configuration.orders_exports` has two rules with different prefixes and different intents: `exports/` tiers down to Glacier over time (§5's cost-tiering story), while `imports/` just expires after 30 days — imports are transient inputs to the Lambda-triggered import flow (§4), not artifacts worth retaining once processed.
- `noncurrent_version_expiration` is easy to forget and is called out explicitly in §5's common mistakes: versioning without ever expiring old versions means storage cost grows unbounded. This rule caps that.

## `terraform/sqs.tf`

Implements the SQS half of README.md §6's fan-out diagram.

- The DLQ (`fulfillment_dlq`) is defined **before** the main queue specifically because `fulfillment_queue`'s `redrive_policy` needs its ARN — Terraform would actually handle either order via implicit dependency resolution, but ordering it this way in the file mirrors the logical dependency and makes the file easier to read top-to-bottom.
- `redrive_policy` with `maxReceiveCount = 5` is the mechanical implementation of "a poison message gets quarantined instead of retried forever or silently dropped" (§6) — this is a queue-level setting, not application code the fulfillment consumer has to implement itself.
- `aws_sqs_queue_policy.fulfillment_queue_policy` is the piece that's easy to overlook: an SNS topic can't deliver to an SQS queue just because you "subscribe" it in the console/API — the queue needs an explicit resource policy granting `sns.amazonaws.com` permission to `sqs:SendMessage`, scoped (via the `ArnEquals` condition) to the specific topic ARN. This is the same least-privilege discipline from README.md §1, applied to a queue-to-topic trust relationship instead of an IAM role.
- `notifications_queue` is a second, independent queue — the file's comment calls out explicitly why the notifications consumer must NOT share the fulfillment queue: independent failure/retry/redelivery behavior per consumer type is exactly what SNS fan-out (§6) is for.

## `terraform/ecs.tf`

Depends on `vpc.tf` (subnets, app security group), `s3.tf` (exports bucket ARN), and `rds.tf` (the RDS-managed secret ARN) — this is the file that ties the whole least-privilege story together.

- **Two IAM roles, not one** — `ecs_execution_role` (ECS agent: pull image, write logs) vs. `ecs_task_role` (application code: S3, SNS, Secrets Manager). The file's comment calls this out as a common point of confusion; conflating them either breaks deployment (execution role missing ECR/logs permissions) or over-grants the running application (task role given infrastructure-level permissions it never needs).
- `data.aws_iam_policy_document.orders_app_task_policy` is the literal Terraform expression of README.md §1's three-permission policy snippet — same three capabilities (S3 read/write on the exports bucket, SNS publish to OrderPlaced, Secrets Manager read on exactly two ARNs), now wired to real resource references (`aws_s3_bucket.orders_exports.arn`, `aws_db_instance.orders_postgres.master_user_secret[0].secret_arn`) instead of hardcoded placeholder ARNs.
- The task definition's `secrets` block (not `environment`) is where README.md §9's "correct pattern" becomes concrete: `DB_PASSWORD`'s `valueFrom` points at the RDS-managed secret's ARN, and ECS resolves it at container launch — the plaintext value is never written into this JSON, never visible via `DescribeTaskDefinition` in plaintext, and never touches source control.
- `aws_appautoscaling_policy.orders_api_cpu` is the concrete mechanism behind two separate README.md claims: §3's "scale independently" (more Fargate tasks when the fulfillment/API load actually increases) and §12's Performance Efficiency pillar decision (capacity tracks real demand rather than being fixed at a guessed peak).

## `lambda/pom.xml`

Not built anywhere in this repo — included so `OrderEventHandler.java`'s dependency requirements aren't just described in a comment but shown as a complete, realistic Maven file. The two required dependencies (`aws-lambda-java-core`, `aws-lambda-java-events`) are exactly what the module brief asked to be called out; the commented-out Step Functions SDK dependency and Shade plugin block show what a *real*, deployable version of this module would add next (a fat-jar build and the SDK client the handler's comments describe calling).

## `lambda/OrderEventHandler.java`

```java
public class OrderEventHandler implements RequestHandler<SQSEvent, SQSBatchResponse> {
```
Implements the Lambda `RequestHandler<I, O>` contract — `SQSEvent` as input (the batch of messages Lambda pulled from `orders-fulfillment-queue`), `SQSBatchResponse` as output. The module brief named `RequestHandler<SQSEvent, Void>` specifically; this file implements the richer `SQSBatchResponse`-returning form as the primary handler and includes a nested `SimpleOrderEventHandler implements RequestHandler<SQSEvent, Void>` alongside it purely for direct comparison — read both Javadocs to see the actual trade-off (partial-batch-failure precision vs. simplicity) rather than treating one as strictly "more correct."

```java
for (SQSEvent.SQSMessage message : event.getRecords()) {
    try {
        processOrderPlacedEvent(message, context);
    } catch (Exception e) {
        ...
        failures.add(new SQSBatchResponse.BatchItemFailure(message.getMessageId()));
    }
}
```
This loop is the one place in this file (and arguably in the whole repo's exception-handling story) where catching the broad `Exception` type is the *correct* call, and the Javadoc says so explicitly — it's a batch-processing boundary where one message's failure must be isolated from the other nine, individually logged, and individually left in the queue for redelivery, never silently swallowed. Compare this against `java-basics/README.md`'s "never catch Exception and swallow it silently" guidance: the distinction is that nothing here is swallowed — every failure is logged and explicitly reported back to Lambda/SQS.

```java
private void processOrderPlacedEvent(SQSEvent.SQSMessage message, Context context) {
```
Deliberately thin — the method's Javadoc walks through what a real implementation would do (deserialize, call Step Functions' `StartExecution`, let exceptions propagate) without actually calling any SDK, because no real AWS SDK dependency is resolved in this teaching environment. The idempotency note in this method's Javadoc — using the order ID as the Step Functions execution *name* — is the single most interview-relevant detail in this file: it's the concrete mechanism that makes SQS's at-least-once delivery contract (README.md §6) safe to build on top of.

## Reading order for the module as a whole

1. [README.md](README.md) §1–§2 (IAM, VPC) — the foundational security/network model everything else assumes.
2. [terraform/vpc.tf](terraform/vpc.tf) — see those concepts as real resources.
3. [README.md](README.md) §3 (compute choice) → [terraform/ecs.tf](terraform/ecs.tf).
4. [README.md](README.md) §5, §9, §10 → [terraform/s3.tf](terraform/s3.tf), [terraform/rds.tf](terraform/rds.tf).
5. [README.md](README.md) §6–§7 (messaging, Step Functions) → [terraform/sqs.tf](terraform/sqs.tf) → [lambda/OrderEventHandler.java](lambda/src/main/java/com/interviewprep/orders/lambda/OrderEventHandler.java) → [diagrams/event-driven-fulfillment-sequence.md](diagrams/event-driven-fulfillment-sequence.md).
6. [README.md](README.md) §8, §11, §12 (observability, frontend delivery, Well-Architected) tie the rest together conceptually — no additional `.tf` files back them in this teaching module, by design, to keep the Terraform scope to the five files the brief called for.
