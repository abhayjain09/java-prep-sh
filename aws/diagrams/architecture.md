# AWS Architecture — Order/Inventory System

Companion to [../README.md](../README.md). Two diagrams: the full deployed architecture (this file) and the event-driven fulfillment sequence ([event-driven-fulfillment-sequence.md](event-driven-fulfillment-sequence.md)).

**Reminder:** this is an illustrative target architecture for teaching purposes — it is not deployed anywhere from this repo. See [../terraform/](../terraform/) for the corresponding (also illustrative, not-for-`apply`) HCL.

```mermaid
flowchart TB
    subgraph Internet["Internet"]
        User["Browser / Angular SPA user"]
    end

    subgraph Route53["Route53 (DNS + health-check failover)"]
        DNS["app.example.com / api.example.com"]
    end

    subgraph CDN["Edge"]
        CF["CloudFront distribution\n(ACM TLS cert)"]
    end

    subgraph VPC["VPC (10.0.0.0/16)"]
        subgraph PublicSubnets["Public subnets (2 AZs)"]
            IGW["Internet Gateway"]
            ALB["Application Load Balancer"]
            NAT["NAT Gateway"]
        end

        subgraph PrivateAppSubnets["Private subnets — app tier (2 AZs)"]
            ECS["ECS Fargate service\n(Spring Boot REST API,\nangular/ + spring/ + security/)"]
            FulfillLambda["Fulfillment consumer\n(Lambda or Fargate task)"]
        end

        subgraph PrivateDataSubnets["Private subnets — data tier, isolated (2 AZs)"]
            RDS["RDS PostgreSQL\n(Multi-AZ)\ndatabase/ schema"]
            DDB[("DynamoDB\norder-event audit log /\nsession store")]
        end
    end

    subgraph S3Buckets["S3"]
        S3Frontend["S3: Angular production build\n(private, CloudFront OAC only)"]
        S3Exports["S3: orders-exports-bucket\n(CSV/JSON, versioned,\nlifecycle -> Glacier)"]
    end

    subgraph Messaging["Event-driven fulfillment (see sequence diagram)"]
        SNS["SNS Topic: OrderPlaced"]
        SQSFulfill["SQS: fulfillment-queue"]
        SQSNotify["SQS: notifications-queue"]
        DLQ["SQS: fulfillment-DLQ"]
        StepFn["Step Functions:\nfulfillment saga\n(reserve -> charge -> ship -> notify)"]
        ImportLambda["Lambda: CSV/JSON import\n(S3-triggered)"]
    end

    subgraph Ops["Cross-cutting"]
        IAM["IAM roles & policies\n(least-privilege per task)"]
        SecretsMgr["Secrets Manager\n(DB creds, JWT signing key)"]
        CW["CloudWatch\n(metrics, logs, alarms)"]
        CT["CloudTrail\n(API call audit)"]
    end

    User -->|HTTPS| DNS
    DNS --> CF
    DNS -->|api.example.com| ALB
    CF --> S3Frontend
    User -->|API calls| ALB
    IGW --- PublicSubnets
    ALB --> ECS
    ECS --> NAT --> IGW
    ECS -->|JDBC| RDS
    ECS -->|audit writes| DDB
    ECS -->|publish OrderPlaced| SNS
    SNS --> SQSFulfill
    SNS --> SQSNotify
    SQSFulfill --> FulfillLambda
    SQSFulfill -.->|after maxReceiveCount| DLQ
    FulfillLambda --> StepFn
    ECS -->|export snapshot| S3Exports
    S3Exports -.->|ObjectCreated trigger| ImportLambda
    ImportLambda --> RDS

    ECS -.->|assumes role, reads secrets| IAM
    ECS -.-> SecretsMgr
    FulfillLambda -.-> IAM
    ECS -.->|metrics/logs| CW
    FulfillLambda -.->|metrics/logs| CW
    IAM -.->|every API call recorded| CT
```

## Reading notes

- **Public vs. private subnets**: only the ALB and NAT Gateway sit in public subnets with a route to the Internet Gateway. The ECS Fargate tasks and the fulfillment consumer are in private app subnets — reachable only via the ALB, with outbound internet access (for Secrets Manager, third-party calls) routed through NAT. RDS and DynamoDB-adjacent app traffic sit in isolated private data subnets with no internet route at all.
- **CloudFront + S3 vs. ALB + ECS** are two separate paths: static Angular assets never touch the ECS tier; API calls never touch the S3/CloudFront path (in this design — an alternative would put CloudFront in front of the API too for edge caching of cacheable GETs, not modeled here to keep the diagram focused).
- **The messaging subgraph** is expanded in detail, with exact message ordering, in the sequence diagram — this diagram only shows *what* is wired to *what*, not the order events happen in.
- **Cross-cutting concerns** (IAM, Secrets Manager, CloudWatch, CloudTrail) apply to every compute component; they're drawn once here rather than repeated on every arrow to keep the diagram legible — see [../README.md](../README.md) §1, §8, §9 for exactly which permissions/secrets/metrics apply to which component.
