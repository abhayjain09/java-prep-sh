# =============================================================================
# TEACHING ARTIFACT — ILLUSTRATIVE ONLY. DO NOT RUN `terraform apply`.
# See vpc.tf's header comment — same caveats apply here. This models the
# Postgres instance backing the schema built in ../../database/, deployed
# into the private, isolated data subnets defined in vpc.tf.
# =============================================================================

# A DB subnet group tells RDS which subnets it's allowed to place instances
# (and, for Multi-AZ, the standby) into. Must span at least 2 AZs for RDS
# Multi-AZ to be possible at all — matches vpc.tf's two private_data subnets.
resource "aws_db_subnet_group" "orders" {
  name       = "orders-db-subnet-group"
  subnet_ids = [for s in aws_subnet.private_data : s.id]

  tags = { Name = "orders-db-subnet-group" }
}

# -----------------------------------------------------------------------------
# The RDS instance itself.
#
# WHY POSTGRES: matches database/'s canonical schema and SQL coverage — this
# is the system of record for orders/products/customers/inventory (see
# ../README.md section 10 for the full RDS vs. DynamoDB discussion).
#
# WHY multi_az = true: a single-AZ RDS instance has a hard availability
# ceiling no application-level redundancy can fix — if that AZ has an
# outage, the database is down, full stop. Multi-AZ maintains a synchronously
# replicated standby in a second AZ and fails over automatically (typically
# under a minute) on primary failure. This is the concrete Reliability-pillar
# decision named in README.md section 12.
#
# WHY NO PASSWORD LITERAL HERE: manage_master_user_password = true tells RDS
# to generate and store the master credential in Secrets Manager itself,
# rather than requiring a plaintext password anywhere in this Terraform
# config or state file. The application retrieves it via Secrets Manager
# at runtime (see ../README.md section 9 and lambda/OrderEventHandler.java's
# comments) using the IAM role permissions granted in ecs.tf.
# -----------------------------------------------------------------------------
resource "aws_db_instance" "orders_postgres" {
  identifier     = "orders-postgres"
  engine         = "postgres"
  engine_version = "16.3" # pin explicitly — never leave this to "latest" in a real config
  instance_class = "db.t3.medium" # illustrative sizing; a real workload would be load-tested first

  allocated_storage     = 50
  max_allocated_storage = 200 # storage autoscaling ceiling — avoids manual resize under growth
  storage_type          = "gp3"
  storage_encrypted     = true # encryption at rest — non-negotiable for a system handling order/customer data

  db_name  = "orders"
  username = "orders_app"
  manage_master_user_password = true # RDS-managed credential in Secrets Manager — see comment above

  db_subnet_group_name   = aws_db_subnet_group.orders.name
  vpc_security_group_ids = [aws_security_group.rds.id] # from vpc.tf — inbound ONLY from the app SG
  publicly_accessible    = false # NEVER true — see README.md section 2's "common mistakes"

  multi_az            = true
  backup_retention_period = 7 # days — automated daily snapshots, point-in-time recovery window
  backup_window           = "03:00-04:00" # low-traffic window, illustrative
  deletion_protection     = true # blocks accidental `terraform destroy` / console deletion

  # Applying engine/parameter changes immediately vs. in the next maintenance
  # window is itself a trade-off: immediate = risk of an unplanned restart
  # mid-traffic; deferred = a config drift window until the next maintenance
  # slot. Set to false here as the safer default for a production-shaped
  # system; a lower environment might reasonably set this true for velocity.
  apply_immediately = false

  tags = {
    Name   = "orders-postgres"
    Module = "aws-teaching-module-12"
  }
}

# -----------------------------------------------------------------------------
# A read replica — NOT provisioned by default (commented out) but shown here
# because it's the concrete answer to "what do you do when reporting/read
# traffic contends with transactional order-placement traffic on the primary"
# (see README.md section 10's "common mistakes"). Uncommenting this in a real
# setup would let read-heavy queries (e.g. an analytics dashboard) target the
# replica's endpoint instead of the primary.
# -----------------------------------------------------------------------------
# resource "aws_db_instance" "orders_postgres_read_replica" {
#   identifier          = "orders-postgres-read-replica"
#   replicate_source_db = aws_db_instance.orders_postgres.identifier
#   instance_class      = "db.t3.medium"
#   publicly_accessible = false
#   vpc_security_group_ids = [aws_security_group.rds.id]
# }
