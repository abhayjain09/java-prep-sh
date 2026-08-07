output "alb_dns_name" {
  description = "Public DNS name of the application load balancer."
  value       = aws_lb.app.dns_name
}

output "application_url" {
  description = "Primary URL for the deployed application."
  value       = local.sso_enabled ? "https://${local.app_domain}" : "http://${aws_lb.app.dns_name}"
}

output "cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.main.name
}

output "ecr_repository_urls" {
  description = "Private ECR repositories used by the deployment."
  value = {
    for key, repo in aws_ecr_repository.repositories : key => repo.repository_url
  }
}

output "service_discovery_domain" {
  description = "Private DNS suffix used for internal service-to-service calls."
  value       = local.service_discovery_domain
}

output "sso_enabled" {
  description = "Whether Cognito-backed SSO is enabled for the ALB."
  value       = local.sso_enabled
}

output "app_domain_name" {
  description = "Custom DNS name assigned to the application when SSO is enabled."
  value       = local.sso_enabled ? local.app_domain : null
}

output "cognito_user_pool_id" {
  description = "Amazon Cognito user pool ID."
  value       = local.sso_enabled ? aws_cognito_user_pool.main[0].id : null
}

output "cognito_user_pool_client_id" {
  description = "Amazon Cognito ALB app client ID."
  value       = local.sso_enabled ? aws_cognito_user_pool_client.alb[0].id : null
}

output "cognito_identity_pool_id" {
  description = "Amazon Cognito identity pool ID."
  value       = local.sso_enabled ? aws_cognito_identity_pool.main[0].id : null
}

output "cognito_hosted_ui_domain" {
  description = "Amazon Cognito hosted UI domain used by the ALB authenticate action."
  value       = local.sso_enabled ? "https://${aws_cognito_user_pool_domain.main[0].domain}.auth.${var.aws_region}.amazoncognito.com" : null
}

output "route53_name_servers" {
  description = "Route 53 nameservers for a newly created public hosted zone. Delegate your registrar to these for ACM validation to complete."
  value       = length(aws_route53_zone.app) > 0 ? aws_route53_zone.app[0].name_servers : null
}
