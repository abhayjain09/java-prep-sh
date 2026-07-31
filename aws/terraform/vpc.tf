# =============================================================================
# TEACHING ARTIFACT — ILLUSTRATIVE ONLY. DO NOT RUN `terraform apply`.
#
# This file exists to show realistic HCL structure and reasoning for the
# Order/Inventory system's network layer (see ../README.md section 2 for the
# full explanation of the public/private subnet split, NAT Gateway, and
# security groups vs. NACLs). It is intentionally NOT a production-hardened
# module: no remote state backend, no variable validation, no multi-region
# support, hardcoded CIDRs for readability. Read it to understand shape and
# intent, not as something to copy-paste into a real account.
# =============================================================================

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "us-east-1" # illustrative — a real setup would parameterize this
}

# -----------------------------------------------------------------------------
# The VPC itself. 10.0.0.0/16 gives 65,536 addresses — far more than this
# system needs, but leaves room to grow subnets without re-planning the CIDR
# block later (a genuinely painful thing to change after the fact).
# -----------------------------------------------------------------------------
resource "aws_vpc" "orders_vpc" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true # required for ECS service discovery / RDS endpoints to resolve

  tags = {
    Name   = "orders-vpc"
    Module = "aws-teaching-module-12"
  }
}

# -----------------------------------------------------------------------------
# Internet Gateway — the VPC's only door to/from the public internet.
# Attached to the VPC, referenced by the PUBLIC route table below.
# -----------------------------------------------------------------------------
resource "aws_internet_gateway" "orders_igw" {
  vpc_id = aws_vpc.orders_vpc.id
  tags   = { Name = "orders-igw" }
}

# -----------------------------------------------------------------------------
# PUBLIC subnets — one per AZ, for the Application Load Balancer and the NAT
# Gateway. Nothing application-sensitive (no ECS tasks, no RDS) ever runs here.
# -----------------------------------------------------------------------------
resource "aws_subnet" "public" {
  for_each = {
    a = { cidr = "10.0.0.0/24", az = "us-east-1a" }
    b = { cidr = "10.0.1.0/24", az = "us-east-1b" }
  }

  vpc_id                  = aws_vpc.orders_vpc.id
  cidr_block               = each.value.cidr
  availability_zone        = each.value.az
  map_public_ip_on_launch  = true # ALB/NAT need a public IP; app/data subnets never set this

  tags = { Name = "orders-public-${each.key}" }
}

# -----------------------------------------------------------------------------
# PRIVATE APP subnets — ECS Fargate tasks (the Spring Boot API, the
# fulfillment consumer) live here. No public IP, no direct internet route;
# outbound traffic (Secrets Manager, third-party calls) goes via NAT.
# -----------------------------------------------------------------------------
resource "aws_subnet" "private_app" {
  for_each = {
    a = { cidr = "10.0.10.0/24", az = "us-east-1a" }
    b = { cidr = "10.0.11.0/24", az = "us-east-1b" }
  }

  vpc_id            = aws_vpc.orders_vpc.id
  cidr_block        = each.value.cidr
  availability_zone = each.value.az

  tags = { Name = "orders-private-app-${each.key}" }
}

# -----------------------------------------------------------------------------
# PRIVATE DATA subnets — RDS Postgres only. Deliberately has NO route to NAT
# or the Internet Gateway at all (see the route table below) — a database has
# no legitimate reason to make outbound internet calls, so the route simply
# doesn't exist, which is a stronger guarantee than relying on a security
# group rule alone (see README.md section 2's "why NOT to over-engineer /
# common mistakes" discussion of public RDS instances).
# -----------------------------------------------------------------------------
resource "aws_subnet" "private_data" {
  for_each = {
    a = { cidr = "10.0.20.0/24", az = "us-east-1a" }
    b = { cidr = "10.0.21.0/24", az = "us-east-1b" }
  }

  vpc_id            = aws_vpc.orders_vpc.id
  cidr_block        = each.value.cidr
  availability_zone = each.value.az

  tags = { Name = "orders-private-data-${each.key}" }
}

# -----------------------------------------------------------------------------
# NAT Gateway — lives in a PUBLIC subnet, requires its own Elastic IP. Lets
# private-app-subnet resources initiate outbound internet connections without
# being reachable inbound. One NAT Gateway shown here for simplicity/cost in
# this teaching example; a real production setup would typically run one NAT
# Gateway PER AZ to avoid a cross-AZ single point of failure (and cross-AZ
# data transfer cost) — called out explicitly as a simplification.
# -----------------------------------------------------------------------------
resource "aws_eip" "nat" {
  domain = "vpc"
  tags   = { Name = "orders-nat-eip" }
}

resource "aws_nat_gateway" "orders_nat" {
  allocation_id = aws_eip.nat.id
  subnet_id     = aws_subnet.public["a"].id
  tags          = { Name = "orders-nat" }

  depends_on = [aws_internet_gateway.orders_igw]
}

# -----------------------------------------------------------------------------
# Route tables. Three distinct tables reflect the three distinct network
# postures: public (route to IGW), private-app (route to NAT only), and
# private-data (no default route out at all).
# -----------------------------------------------------------------------------
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.orders_vpc.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.orders_igw.id
  }

  tags = { Name = "orders-public-rt" }
}

resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table" "private_app" {
  vpc_id = aws_vpc.orders_vpc.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.orders_nat.id
  }

  tags = { Name = "orders-private-app-rt" }
}

resource "aws_route_table_association" "private_app" {
  for_each       = aws_subnet.private_app
  subnet_id      = each.value.id
  route_table_id = aws_route_table.private_app.id
}

# No default route added here at all — this table has ONLY the implicit
# local VPC route. RDS in these subnets cannot reach, or be reached from,
# the internet under any circumstance, regardless of security group rules.
resource "aws_route_table" "private_data" {
  vpc_id = aws_vpc.orders_vpc.id
  tags   = { Name = "orders-private-data-rt" }
}

resource "aws_route_table_association" "private_data" {
  for_each       = aws_subnet.private_data
  subnet_id      = each.value.id
  route_table_id = aws_route_table.private_data.id
}

# -----------------------------------------------------------------------------
# Security groups — stateful, resource-to-resource. See README.md section 2
# for the full security-group-vs-NACL comparison; NACLs are deliberately
# omitted here since this teaching example relies on security groups as the
# primary control, per that discussion.
# -----------------------------------------------------------------------------
resource "aws_security_group" "alb" {
  name        = "orders-alb-sg"
  description = "Allows inbound HTTPS from the internet; forwards to the app SG only"
  vpc_id      = aws_vpc.orders_vpc.id

  ingress {
    description = "HTTPS from anywhere"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}

resource "aws_security_group" "app" {
  name        = "orders-app-sg"
  description = "ECS Fargate tasks — inbound ONLY from the ALB security group, on the app port"
  vpc_id      = aws_vpc.orders_vpc.id

  ingress {
    description     = "App traffic from the ALB only — not from the open internet"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id] # SG-to-SG reference, not a CIDR block
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"] # needed to reach NAT -> Secrets Manager, third parties
  }
}

resource "aws_security_group" "rds" {
  name        = "orders-rds-sg"
  description = "RDS Postgres — inbound ONLY from the app security group, on 5432"
  vpc_id      = aws_vpc.orders_vpc.id

  ingress {
    description     = "Postgres from the app tier only"
    from_port       = 5432
    to_port         = 5432
    protocol        = "tcp"
    security_groups = [aws_security_group.app.id]
  }

  # Deliberately NO egress rule to 0.0.0.0/0 — RDS has no legitimate reason
  # to initiate outbound connections. Combined with private_data's route
  # table having no internet route at all, this is defense in depth.
}
