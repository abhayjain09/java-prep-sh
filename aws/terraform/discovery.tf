resource "aws_service_discovery_private_dns_namespace" "main" {
  name = local.service_discovery_domain
  vpc  = aws_vpc.main.id

  tags = merge(local.tags, {
    Name = "${local.name_prefix}-namespace"
  })
}

resource "aws_service_discovery_service" "postgres" {
  name = "${local.name_prefix}-postgres"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }

  tags = local.tags
}

resource "aws_service_discovery_service" "redis" {
  name = "${local.name_prefix}-redis"

  dns_config {
    namespace_id = aws_service_discovery_private_dns_namespace.main.id

    dns_records {
      ttl  = 10
      type = "A"
    }

    routing_policy = "MULTIVALUE"
  }

  health_check_custom_config {
    failure_threshold = 1
  }

  tags = local.tags
}
