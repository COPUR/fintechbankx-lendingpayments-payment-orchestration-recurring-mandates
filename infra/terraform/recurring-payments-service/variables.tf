variable "aws_region" {
  type        = string
  description = "AWS region for resource provisioning"
  default     = "us-east-1"
}

variable "environment" {
  type        = string
  description = "Deployment environment"
}

variable "database_engine" {
  type        = string
  description = "Database engine"
}

variable "cache_engine" {
  type        = string
  description = "Cache engine"
}

variable "identity_provider_url" {
  type        = string
  description = "OIDC/IdP URL"
}

variable "observability_endpoint" {
  type        = string
  description = "Observability endpoint"
}

variable "tags" {
  type        = map(string)
  description = "Additional tags for service resources"
  default     = {}
}
