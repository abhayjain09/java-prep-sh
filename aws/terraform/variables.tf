variable "aws_region" {
  description = "AWS region where all resources are created."
  type        = string
  default     = "us-east-1"
}

variable "project_name" {
  description = "Short project name used in resource names."
  type        = string
  default     = "orders"
}

variable "environment" {
  description = "Deployment environment suffix."
  type        = string
  default     = "test"
}

variable "vpc_cidr" {
  description = "CIDR block for the application VPC."
  type        = string
  default     = "10.42.0.0/16"
}

variable "public_subnet_cidrs" {
  description = "Two public subnet CIDR blocks for the ALB and NAT gateway."
  type        = list(string)
  default     = ["10.42.0.0/24", "10.42.1.0/24"]
}

variable "private_subnet_cidrs" {
  description = "Two private subnet CIDR blocks for ECS tasks."
  type        = list(string)
  default     = ["10.42.10.0/24", "10.42.11.0/24"]
}

variable "image_tag" {
  description = "Tag used for the API and web images built by the pipeline."
  type        = string
  default     = "latest"
}

variable "postgres_image_tag" {
  description = "Tag used for the mirrored Postgres image."
  type        = string
  default     = "16-alpine"
}

variable "redis_image_tag" {
  description = "Tag used for the mirrored Redis image."
  type        = string
  default     = "7-alpine"
}

variable "db_name" {
  description = "Application database name."
  type        = string
  default     = "orders_db"
}

variable "db_username" {
  description = "Application database username."
  type        = string
  default     = "orders_app"
}

variable "api_desired_count" {
  description = "Desired number of Spring API tasks."
  type        = number
  default     = 1
}

variable "web_desired_count" {
  description = "Desired number of Angular web tasks."
  type        = number
  default     = 1
}

variable "common_tags" {
  description = "Extra tags applied to all resources."
  type        = map(string)
  default     = {}
}
