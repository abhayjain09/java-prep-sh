locals {
  azs         = slice(data.aws_availability_zones.available.names, 0, 2)
  name_prefix = "${var.project_name}-${var.environment}"
  sso_enabled = trimspace(var.root_domain_name != null ? var.root_domain_name : "") != ""
  root_domain = local.sso_enabled ? trimsuffix(var.root_domain_name, ".") : null
  app_domain  = local.sso_enabled ? "${var.app_subdomain}.${local.root_domain}" : null

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

  cognito_domain_prefix = local.sso_enabled ? substr(
    regexreplace(
      lower(coalesce(var.cognito_domain_prefix, "${local.name_prefix}-${data.aws_caller_identity.current.account_id}-${random_id.cognito_domain_suffix.hex}")),
      "[^a-z0-9-]",
      "-"
    ),
    0,
    63
  ) : null

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
