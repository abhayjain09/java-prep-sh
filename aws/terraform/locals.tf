locals {
  azs         = slice(data.aws_availability_zones.available.names, 0, 2)
  name_prefix = "${var.project_name}-${var.environment}"

  ecr_repositories = {
    api      = "${local.name_prefix}-api"
    web      = "${local.name_prefix}-web"
    postgres = "${local.name_prefix}-postgres"
    redis    = "${local.name_prefix}-redis"
  }

  image_uris = {
    api      = "${aws_ecr_repository.repositories["api"].repository_url}:${var.image_tag}"
    web      = "${aws_ecr_repository.repositories["web"].repository_url}:${var.image_tag}"
    postgres = "${aws_ecr_repository.repositories["postgres"].repository_url}:${var.postgres_image_tag}"
    redis    = "${aws_ecr_repository.repositories["redis"].repository_url}:${var.redis_image_tag}"
  }

  service_discovery_domain = "${local.name_prefix}.local"

  tags = merge(
    {
      Project     = var.project_name
      Environment = var.environment
      ManagedBy   = "terraform"
      Repository  = "java-prep-sh"
    },
    var.common_tags
  )
}
