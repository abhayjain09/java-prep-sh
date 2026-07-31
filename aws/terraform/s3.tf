# =============================================================================
# TEACHING ARTIFACT — ILLUSTRATIVE ONLY. DO NOT RUN `terraform apply`.
# See vpc.tf's header comment. Models the export/import bucket from
# ../README.md section 5 (Module 2's CSV/JSON exports land here) and the
# separate bucket serving the Angular production build (section 11).
# =============================================================================

# -----------------------------------------------------------------------------
# Bucket 1: order/inventory CSV & JSON exports (Module 2) and imports (the
# Lambda-triggered import flow in README.md section 4).
# -----------------------------------------------------------------------------
resource "aws_s3_bucket" "orders_exports" {
  bucket = "orders-exports-bucket-REPLACE-WITH-UNIQUE-SUFFIX" # bucket names are globally unique across ALL of AWS

  tags = {
    Name   = "orders-exports-bucket"
    Module = "aws-teaching-module-12"
  }
}

# Block ALL public access at the bucket level — see README.md section 5's
# "common mistakes." There is no legitimate reason for this bucket (exports
# containing order/customer data) to ever be publicly readable.
resource "aws_s3_bucket_public_access_block" "orders_exports" {
  bucket = aws_s3_bucket.orders_exports.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Versioning — protects against an accidental re-export clobbering a prior
# day's snapshot (see README.md section 5). Every overwrite becomes a new
# version instead of destroying the old one.
resource "aws_s3_bucket_versioning" "orders_exports" {
  bucket = aws_s3_bucket.orders_exports.id
  versioning_configuration {
    status = "Enabled"
  }
}

# Server-side encryption at rest — SSE-S3 (AES-256) shown here as the
# simplest option; SSE-KMS would give per-key access auditing via CloudTrail
# at the cost of a small additional latency/cost per request, worth
# considering for genuinely sensitive exports.
resource "aws_s3_bucket_server_side_encryption_configuration" "orders_exports" {
  bucket = aws_s3_bucket.orders_exports.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# -----------------------------------------------------------------------------
# Lifecycle policy — see README.md section 5's exact tiering rationale:
# fresh exports are queried often, old ones almost never, but audit/
# compliance requirements mean "almost never" isn't "never" — Glacier keeps
# them retrievable (slowly, cheaply) rather than deleting them outright.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "orders_exports" {
  bucket = aws_s3_bucket.orders_exports.id

  rule {
    id     = "exports-tiering"
    status = "Enabled"

    filter {
      prefix = "exports/"
    }

    transition {
      days          = 30
      storage_class = "STANDARD_IA" # Infrequent Access — cheaper storage, small retrieval fee
    }

    transition {
      days          = 90
      storage_class = "GLACIER" # much cheaper; retrieval takes minutes to hours (see README.md section 5)
    }

    expiration {
      days = 365 # illustrative retention window — a real policy would be set by compliance/legal, not guessed
    }

    # Old, non-current versions (from versioning above) shouldn't accumulate
    # forever either — expire them on their own, shorter schedule.
    noncurrent_version_expiration {
      noncurrent_days = 90
    }
  }

  rule {
    id     = "imports-cleanup"
    status = "Enabled"

    filter {
      prefix = "imports/"
    }

    # Imports are transient — once the Lambda in lambda/ (conceptually;
    # the S3-triggered CSV import Lambda described in README.md section 4)
    # has processed a file, there's no long-term reason to keep the raw
    # upload around indefinitely.
    expiration {
      days = 30
    }
  }
}

# -----------------------------------------------------------------------------
# Bucket 2: the Angular production build (README.md section 11). Private —
# only CloudFront (via Origin Access Control) is allowed to read from it.
# The CloudFront distribution and OAC resources themselves are omitted here
# to keep this file focused on S3; see README.md section 11 for the full
# CloudFront/Route53 discussion.
# -----------------------------------------------------------------------------
resource "aws_s3_bucket" "frontend" {
  bucket = "orders-frontend-bucket-REPLACE-WITH-UNIQUE-SUFFIX"
  tags   = { Name = "orders-frontend-bucket" }
}

resource "aws_s3_bucket_public_access_block" "frontend" {
  bucket                  = aws_s3_bucket.frontend.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
  # Public access stays blocked even for the frontend bucket — CloudFront's
  # Origin Access Control reads it via a bucket policy scoped to the
  # CloudFront distribution's ARN, never via a public bucket ACL/policy.
}
