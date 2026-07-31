# Module 12 — Exercises

These are design/analysis exercises, not "run this command" exercises — per this module's "explain locally, do not deploy" constraint, you're not expected to have an AWS account or run Terraform. Work through each on paper/in a doc, and where a diagram is asked for, sketch it in Mermaid (see [diagrams/](diagrams/) for the conventions used throughout this repo). Each builds on the previous one's context.

## 1. (Beginner) Trace the least-privilege policy

Open [terraform/ecs.tf](terraform/ecs.tf)'s `orders_app_task_policy` and, for each of its three statements, answer: what specific action would fail (and with what AWS error, roughly) if that statement were deleted? Then answer the inverse: name one action the *current* policy would reject, and explain why rejecting it is correct rather than an oversight (hint: think about what the ECS **execution** role in the same file is responsible for instead).

**Check yourself:** you should be able to point at the exact line in [README.md](README.md) §1 that explains why the task role never gets `iam:*` permissions of its own.

## 2. (Beginner) NAT Gateway vs. VPC Endpoint

The app's ECS task role includes `secretsmanager:GetSecretValue`, and traffic to Secrets Manager from the private app subnet currently routes out through the NAT Gateway (see [terraform/vpc.tf](terraform/vpc.tf)'s `private_app` route table). Redraw that one path using a **VPC Interface Endpoint** for Secrets Manager instead. What changes in the route table? What does NOT change in the security group rules? Referencing README.md §2's trade-offs section, explain in 2-3 sentences why this swap is a legitimate cost optimization and whether it fully eliminates the need for the NAT Gateway (it doesn't — why not?).

## 3. (Intermediate) SNS vs. EventBridge, applied to a new requirement

Product now wants: orders over $10,000 routed to a separate "high-value review" SQS queue (a human glances at them before fulfillment proceeds), while all other orders continue straight to the existing fulfillment queue. Using README.md §6's SNS vs. EventBridge trade-off table, decide which service you'd use for this specific requirement and why. Write the rule/filter (pseudocode is fine — you don't need real AWS syntax) that implements the $10,000 threshold, and explain concretely why the *other* service in the comparison would require workarounds (e.g. "every consumer receives everything and filters itself") to achieve the same result.

## 4. (Intermediate) Design the Step Functions retry policy

[README.md](README.md) §7 describes the fulfillment saga's forward path (reserve → charge → ship → notify) and compensating branches, but doesn't specify retry policies per step. For each of the four forward states, decide: should it retry automatically before falling through to its `Catch` branch, and if so, how many times / what backoff? Justify differently for at least two states — e.g. "Charge Payment" and "Ship Order" plausibly deserve different retry behavior because of what a retry actually means for each (a payment gateway timeout vs. a shipping-carrier API hiccup). What's the risk of retrying "Charge Payment" too aggressively without an idempotency key?

## 5. (Senior) Find and fix the DLQ blind spot

A teammate's PR adds the DLQ in [terraform/sqs.tf](terraform/sqs.tf) exactly as shown, ships it, and closes the ticket "add dead-letter queue for fulfillment." Using README.md §6 and §8, explain precisely what's still missing before this DLQ is actually useful operationally (not just "technically present"), and sketch (in words or a short CloudWatch alarm definition) the specific metric, threshold, and action that closes the gap. Then answer: if the DLQ fills up with 50 messages overnight and nobody notices for three days, what's the blast radius on the business (in terms of the Order/Inventory domain — think about what those 50 orders' actual state is) versus if the same 50 messages had simply been retried forever with no DLQ at all?

## 6. (Scenario) Design for a 10x flash-sale traffic spike

The Order/Inventory system normally handles a steady, modest request rate. Marketing announces a flash sale expected to bring roughly **10x normal traffic for a 3-hour window**, concentrated on: (a) the REST API's order-placement endpoint, (b) the fulfillment SQS→Step-Functions pipeline, and (c) RDS (both reads for product/stock lookups and writes for order placement).

Produce a short design document (bullet points are fine) covering:
- **Compute**: does the ECS Fargate choice from README.md §3 still hold at 10x, or does this scenario change the calculus toward reconsidering EKS, provisioned Lambda concurrency, or something else? Justify using the auto-scaling policy in [terraform/ecs.tf](terraform/ecs.tf) — what specifically would you tune (min/max capacity, target CPU, cooldowns) for a *known, scheduled* spike versus an *unpredictable* one?
- **Data tier**: RDS is a common bottleneck under a write-heavy spike. Referencing README.md §10, would you introduce a read replica, consider DynamoDB for any specific piece of this flow, or something else? Be specific about *which* reads/writes actually benefit versus which are inherently relational and can't be offloaded.
- **Messaging**: does the SQS/SNS/EventBridge design in README.md §6 need any changes at 10x throughput (hint: think about SQS Standard vs. FIFO throughput limits, and whether the DLQ's `maxReceiveCount` behavior changes in character under sustained high volume vs. occasional poison messages).
- **Cost**: name one Well-Architected Cost Optimization pillar decision (README.md §12) that you would deliberately relax *temporarily* for the 3-hour window, and one you would explicitly NOT relax even under traffic pressure, with reasoning for each.
- Sketch (Mermaid, following this repo's diagram conventions) how your revised architecture differs from [diagrams/architecture.md](diagrams/architecture.md) — a diff description ("add X here," "change Y's scaling policy") is acceptable in place of a full redraw if that's clearer.

**Check yourself:** a strong answer names specific, load-tested-sounding numbers (even if illustrative) rather than only qualitative statements like "scale it up" — e.g. "raise `max_capacity` from 10 to 40 and lower `scale_out_cooldown` from 60s to 20s for the announced window, then revert after" is a much stronger answer than "increase auto-scaling."
