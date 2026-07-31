# Event-Driven Order Fulfillment — Sequence Diagram

Companion to [../README.md](../README.md) §6 (SQS/SNS/EventBridge) and §7 (Step Functions). Shows the happy path and the poison-message/DLQ path for one `OrderPlaced` event, from the moment `OrderService.placeOrder(...)` (Module 1) succeeds through to fulfillment.

```mermaid
sequenceDiagram
    actor Customer
    participant API as Spring Boot API (ECS Fargate)
    participant DB as RDS Postgres
    participant SNS as SNS Topic: OrderPlaced
    participant SQSF as SQS: fulfillment-queue
    participant SQSN as SQS: notifications-queue
    participant SF as Step Functions: fulfillment saga
    participant DLQ as SQS: fulfillment-DLQ
    participant CW as CloudWatch (alarms)

    Customer->>API: POST /orders (place order)
    API->>DB: INSERT order, order_lines (transaction)
    DB-->>API: commit OK
    API->>SNS: publish OrderPlaced {orderId, lines, total}
    Note over API,SNS: publish happens AFTER the DB commit succeeds —<br/>never publish an event for a transaction that might still roll back
    API-->>Customer: 201 Created

    SNS->>SQSF: fan out copy 1
    SNS->>SQSN: fan out copy 2

    par Fulfillment path
        SQSF->>SF: start execution (reserve -> charge -> ship -> notify)
        SF->>DB: reserve inventory
        alt inventory available
            SF->>SF: charge payment
            alt payment succeeds
                SF->>SF: ship order
                SF->>SQSN: (or direct) trigger customer notification
                SF-->>SQSF: delete message (success)
            else payment fails
                SF->>DB: compensate: release inventory
                SF-->>SQSF: delete message (handled failure)
            end
        else insufficient stock
            SF-->>SQSF: delete message (handled failure, order marked failed)
        end
    and Notification path
        SQSN->>SQSN: notification consumer sends email/SMS
        SQSN-->>SQSN: delete message on success
    end

    Note over SQSF,DLQ: Poison-message path (e.g. malformed event, bug in consumer)
    SQSF->>SF: start execution (attempt 1)
    SF--xSQSF: processing fails, message becomes visible again
    SQSF->>SF: redelivered (attempt 2 ... up to maxReceiveCount)
    SF--xSQSF: fails again
    SQSF->>DLQ: maxReceiveCount exceeded -> moved to DLQ automatically
    DLQ->>CW: ApproximateNumberOfMessagesVisible > 0 triggers alarm
    CW-->>API: (paging/notification to on-call, out of band)
```

## Reading notes

- **Publish-after-commit**: the API only publishes `OrderPlaced` after the RDS transaction commits — publishing before commit risks announcing an order that a subsequent rollback then makes untrue, and downstream consumers (fulfillment, notifications) have no way to know the announcement was retracted.
- **Fan-out via SNS**: one publish, two independent deliveries (fulfillment queue, notifications queue) — the two consumers don't know about each other and can fail/scale/retry independently. See README §6 for why SNS (not calling both queues directly from the API) is the right shape here.
- **Step Functions as the fulfillment consumer's orchestrator**: the SQS message triggers a Step Functions execution rather than one monolithic Lambda, specifically so each step (reserve/charge/ship/notify) has its own retry policy and — on failure — its own compensating branch, exactly as detailed in README §7.
- **At-least-once delivery**: SQS can redeliver a message that wasn't deleted in time (visibility timeout expiry) or whose consumer crashed mid-processing — this is why every step in the saga (and the consumer itself) must be idempotent (reserving the same order's inventory twice must be a safe no-op, not a double reservation).
- **DLQ + alarm, not silent failure**: after `maxReceiveCount` failed attempts, SQS moves the message to the DLQ automatically (no consumer code needed to implement this) — but a DLQ with no alarm wired to it is just a quieter place for the problem to hide. The CloudWatch alarm on DLQ depth is what turns "the message is safely parked" into "a human found out."
