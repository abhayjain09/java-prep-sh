output "alb_dns_name" {
  description = "Public DNS name of the application load balancer."
  value       = aws_lb.app.dns_name
}

output "application_url" {
  description = "HTTP URL for the deployed application."
  value       = "http://${aws_lb.app.dns_name}"
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
