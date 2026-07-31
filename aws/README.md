# aws/

**Status:** Not started — coming in Module 12. Explained conceptually with local emulation where practical (e.g. LocalStack); nothing gets deployed to a real AWS account from this repo.

Planned coverage:
- IAM, VPC, EC2, ECS, EKS (overview), Lambda, API Gateway.
- S3, SQS, SNS, EventBridge, Step Functions — applied to Order/Inventory fulfillment events (e.g. `OrderPlaced` → SQS → fulfillment Lambda).
- CloudWatch, CloudTrail, Secrets Manager.
- RDS, DynamoDB — trade-offs vs. the PostgreSQL/Oracle setup in `database/`.
- CloudFront, Route53.
- Terraform concepts for provisioning the above.
- AWS Well-Architected Framework pillars applied to this system's design.

See the root [README.md](../README.md) for the full module roadmap and current progress.
