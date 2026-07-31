# =============================================================================
# TEACHING ARTIFACT — ILLUSTRATIVE ONLY. DO NOT RUN `terraform apply`.
# See vpc.tf's header comment. Models the fulfillment queue + dead-letter
# queue pairing described in ../README.md section 6, consumed by the Lambda
# handler in ../lambda/OrderEventHandler.java.
# =============================================================================

# -----------------------------------------------------------------------------
# Dead-letter queue — created FIRST because the main queue's redrive policy
# references it. Poison messages (ones that fail processing repeatedly) land
# here instead of retrying forever or being silently dropped. See
# README.md section 6 and 8 for why this MUST be paired with a CloudWatch
# alarm on message count — a DLQ nobody watches is just a quieter failure.
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "fulfillment_dlq" {
  name = "orders-fulfillment-dlq"

  # DLQ messages are kept longer than the main queue's retention — you want
  # time to notice and investigate before AWS would otherwise expire them.
  message_retention_seconds = 1209600 # 14 days (the maximum SQS allows)

  tags = { Name = "orders-fulfillment-dlq" }
}

# -----------------------------------------------------------------------------
# The main fulfillment queue. Consumed by the fulfillment Lambda/Fargate
# consumer described in README.md section 6, which in turn starts a Step
# Functions execution (README.md section 7) per order.
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "fulfillment_queue" {
  name = "orders-fulfillment-queue"

  # Visibility timeout: how long a message is hidden from other consumers
  # after being received, before it becomes visible again if not deleted.
  # Should be set comfortably longer than the consumer's expected processing
  # time (here, starting a Step Functions execution is fast, so 60s is
  # generous) — too short and a slow-but-successful consumer causes
  # duplicate processing; too long and a genuinely failed message takes
  # longer to become retryable.
  visibility_timeout_seconds = 60

  message_retention_seconds = 345600 # 4 days — SQS default, fine for this workload

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.fulfillment_dlq.arn
    maxReceiveCount     = 5 # after 5 failed attempts, SQS moves the message to the DLQ automatically
  })

  tags = { Name = "orders-fulfillment-queue" }
}

# A queue policy allowing the SNS topic (defined conceptually in
# README.md section 6 — the OrderPlaced topic; not re-declared as a full
# resource in this file to keep scope focused on SQS) to deliver messages
# to this queue. This is the Terraform-level wiring for the SNS -> SQS
# fan-out subscription.
resource "aws_sqs_queue_policy" "fulfillment_queue_policy" {
  queue_url = aws_sqs_queue.fulfillment_queue.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Principal = { Service = "sns.amazonaws.com" }
        Action    = "sqs:SendMessage"
        Resource  = aws_sqs_queue.fulfillment_queue.arn
        Condition = {
          # Scope this to ONLY the specific OrderPlaced topic's ARN in a real
          # setup — left as a placeholder here since the SNS topic resource
          # itself is out of scope for this file.
          ArnEquals = { "aws:SourceArn" = "arn:aws:sns:us-east-1:123456789012:orders-order-placed" }
        }
      }
    ]
  })
}

# -----------------------------------------------------------------------------
# A separate queue for the notifications fan-out branch (see README.md
# section 6's SNS fan-out to multiple SQS queues). Shown briefly here to
# illustrate that each downstream consumer gets its OWN queue (and could get
# its own DLQ too, omitted here for brevity) — never share one queue across
# unrelated consumer types, or one slow/failing consumer's redelivery
# behavior interferes with the other's.
# -----------------------------------------------------------------------------
resource "aws_sqs_queue" "notifications_queue" {
  name                        = "orders-notifications-queue"
  visibility_timeout_seconds  = 30
  message_retention_seconds   = 345600

  tags = { Name = "orders-notifications-queue" }
}
