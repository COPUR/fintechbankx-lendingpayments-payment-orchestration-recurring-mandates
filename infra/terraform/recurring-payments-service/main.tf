terraform {
  required_version = ">= 1.3.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region

  default_tags {
    tags = var.tags
  }
}

module "service" {
  source                 = "../../modules/microservice-base"
  service_name           = "Recurring Payments and Mandates Service"
  service_slug           = "recurring-payments-service"
  environment            = var.environment
  database_engine        = var.database_engine
  cache_engine           = var.cache_engine
  identity_provider_url  = var.identity_provider_url
  observability_endpoint = var.observability_endpoint
  tags                   = var.tags
}

output "service_info" {
  value = module.service.service_info
}

output "cloudwatch_log_group_name" {
  value = module.service.cloudwatch_log_group_name
}

output "workload_role_arn" {
  value = module.service.workload_role_arn
}

output "runtime_secret_arn" {
  value = module.service.secret_arn
}

output "ssm_parameter_paths" {
  value = module.service.ssm_parameter_paths
}
