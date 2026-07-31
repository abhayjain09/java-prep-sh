# =============================================================================
# TEACHING ARTIFACT — ILLUSTRATIVE ONLY. DO NOT RUN `terraform apply`.
# See vpc.tf's header comment. Models the ECS Fargate deployment of the
# spring/ REST API — the compute choice justified in ../README.md section 3
# (ECS Fargate over raw EC2 or EKS for this system's scale).
# =============================================================================

resource "aws_ecs_cluster" "orders" {
  name = "orders-cluster"

  setting {
    name  = "containerInsights"
    value = "enabled" # feeds CloudWatch Container Insights — see README.md section 8
  }
}

# -----------------------------------------------------------------------------
# IAM roles — TWO distinct roles, a distinction that's easy to get wrong and
# worth calling out explicitly:
#
#  - EXECUTION role: used by the ECS AGENT itself to pull the container image
#    from ECR and write logs to CloudWatch — infrastructure-level permissions,
#    not the application's own permissions.
#  - TASK role: assumed by the APPLICATION CODE running inside the container
#    — this is the least-privilege role from README.md section 1 (S3, SQS,
#    Secrets Manager, scoped to exactly what the app needs).
#
# Conflating these two (e.g. giving the execution role S3 access, or the task
# role ECR pull permissions) is a common real-world misconfiguration that
# either breaks deployment or over-grants the application.
# -----------------------------------------------------------------------------
data "aws_iam_policy_document" "ecs_tasks_assume_role" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ecs_execution_role" {
  name               = "orders-ecs-execution-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

resource "aws_iam_role_policy_attachment" "ecs_execution_role_managed" {
  role       = aws_iam_role.ecs_execution_role.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role" "ecs_task_role" {
  name               = "orders-ecs-task-role"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume_role.json
}

# The least-privilege policy from README.md section 1, translated to HCL:
# S3 (exports bucket), SQS (fulfillment queue publish), Secrets Manager
# (exactly the two secrets the app needs). NOT s3:*, NOT sqs:*, NOT
# secretsmanager:* on Resource "*" — every Resource line is scoped.
data "aws_iam_policy_document" "orders_app_task_policy" {
  statement {
    sid       = "ExportsBucketReadWrite"
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.orders_exports.arn}/*"]
  }

  statement {
    sid       = "PublishToFulfillmentFlow"
    actions   = ["sns:Publish"]
    resources = ["arn:aws:sns:us-east-1:123456789012:orders-order-placed"] # the OrderPlaced topic
  }

  statement {
    sid     = "ReadAppSecrets"
    actions = ["secretsmanager:GetSecretValue"]
    resources = [
      aws_db_instance.orders_postgres.master_user_secret[0].secret_arn,
      "arn:aws:secretsmanager:us-east-1:123456789012:secret:orders/jwt-signing-key-*",
    ]
  }
}

resource "aws_iam_role_policy" "orders_app_task_policy" {
  name   = "orders-app-least-privilege-policy"
  role   = aws_iam_role.ecs_task_role.id
  policy = data.aws_iam_policy_document.orders_app_task_policy.json
}

# -----------------------------------------------------------------------------
# Task definition — Fargate launch type, so no EC2 instances to define or
# manage (see README.md section 3's EC2 vs. ECS-Fargate vs. EKS trade-off).
# -----------------------------------------------------------------------------
resource "aws_ecs_task_definition" "orders_api" {
  family                   = "orders-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc" # required for Fargate — each task gets its own ENI
  cpu                      = "512"    # 0.5 vCPU — illustrative sizing, would be load-tested in reality
  memory                   = "1024"   # 1 GB

  execution_role_arn = aws_iam_role.ecs_execution_role.arn
  task_role_arn      = aws_iam_role.ecs_task_role.arn

  container_definitions = jsonencode([
    {
      name      = "orders-api"
      image     = "REPLACE_WITH_ECR_IMAGE_URI:latest" # built from spring/'s Dockerfile, not part of this module's scope
      essential = true

      portMappings = [
        { containerPort = 8080, protocol = "tcp" }
      ]

      # Secrets injected AT LAUNCH by the ECS agent calling Secrets Manager —
      # the plaintext value never appears in this task definition JSON. See
      # README.md section 9 for why this is the correct pattern versus a
      # plain "environment" block with a hardcoded value.
      secrets = [
        {
          name      = "DB_PASSWORD"
          valueFrom = aws_db_instance.orders_postgres.master_user_secret[0].secret_arn
        },
        {
          name      = "JWT_SIGNING_KEY"
          valueFrom = "arn:aws:secretsmanager:us-east-1:123456789012:secret:orders/jwt-signing-key"
        }
      ]

      environment = [
        # Non-secret config only — this is the correct home for a DB
        # hostname/port, NOT for the password (see secrets block above).
        { name = "DB_HOST", value = aws_db_instance.orders_postgres.address },
        { name = "DB_PORT", value = "5432" },
        { name = "SPRING_PROFILES_ACTIVE", value = "aws" }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = "/ecs/orders-api"
          "awslogs-region"        = "us-east-1"
          "awslogs-stream-prefix" = "orders-api"
        }
      }
    }
  ])
}

# -----------------------------------------------------------------------------
# The service — keeps the desired count of tasks running, registers them
# with the ALB target group, and replaces unhealthy tasks automatically
# (this replacement logic is exactly what you'd otherwise hand-roll with an
# Auto Scaling Group + health checks on raw EC2 — see README.md section 3).
# -----------------------------------------------------------------------------
resource "aws_ecs_service" "orders_api" {
  name            = "orders-api-service"
  cluster         = aws_ecs_cluster.orders.id
  task_definition = aws_ecs_task_definition.orders_api.arn
  desired_count   = 2 # at least 2 for basic HA across the 2 private_app AZs
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = [for s in aws_subnet.private_app : s.id]
    security_groups  = [aws_security_group.app.id]
    assign_public_ip = false # private subnet — no public IP; outbound via NAT
  }

  # load_balancer { ... } block omitted here — wiring this task definition's
  # port 8080 into the ALB target group defined in vpc.tf/elsewhere is left
  # out to keep this file focused on the ECS resources themselves.

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200 # allows a full extra set of tasks during rolling deploys
}

# Application Auto Scaling — target-tracking on CPU utilization, the
# concrete mechanism behind README.md section 3's "scale independently"
# claim and section 12's Performance Efficiency pillar decision.
resource "aws_appautoscaling_target" "orders_api" {
  max_capacity       = 10
  min_capacity       = 2
  resource_id        = "service/${aws_ecs_cluster.orders.name}/${aws_ecs_service.orders_api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}

resource "aws_appautoscaling_policy" "orders_api_cpu" {
  name               = "orders-api-cpu-target-tracking"
  policy_type        = "TargetTrackingScaling"
  resource_id        = aws_appautoscaling_target.orders_api.resource_id
  scalable_dimension = aws_appautoscaling_target.orders_api.scalable_dimension
  service_namespace  = aws_appautoscaling_target.orders_api.service_namespace

  target_tracking_scaling_policy_configuration {
    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
    target_value       = 60.0 # scale out once average CPU sustains above 60%
    scale_in_cooldown  = 300
    scale_out_cooldown = 60 # react faster to load increases than to decreases
  }
}
