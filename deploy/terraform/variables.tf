variable "aws_region" {
  type        = string
  description = "AWS region of the workload cell."
}

variable "environment" {
  type        = string
  description = "Deployment environment (dev, staging, prod)."

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "environment must be dev, staging or prod."
  }
}

variable "vpc_id" {
  type        = string
  description = "VPC of the EKS cluster that runs the service."
}

variable "private_subnet_ids" {
  type        = list(string)
  description = "Private subnets in at least two Availability Zones for the Aurora cluster."

  validation {
    condition     = length(var.private_subnet_ids) >= 2
    error_message = "Aurora needs subnets in at least two Availability Zones."
  }
}

variable "workload_security_group_id" {
  type        = string
  description = "Security group of the EKS nodes or pods allowed to reach PostgreSQL."
}

variable "eks_oidc_provider_arn" {
  type        = string
  description = "IAM OIDC provider ARN of the EKS cluster (IRSA)."
}

variable "eks_oidc_provider_url" {
  type        = string
  description = "IAM OIDC provider URL of the EKS cluster without https:// (IRSA)."
}

variable "kubernetes_namespace" {
  type        = string
  description = "Namespace the Helm chart is installed in (platform contract: payments)."
  default     = "payments"
}

variable "kubernetes_service_account" {
  type        = string
  description = "Service account name from the Helm chart."
  default     = "payment-recurring-mandates-service"
}

variable "aurora_engine_version" {
  type        = string
  description = "Aurora PostgreSQL engine version."
  default     = "16.4"
}

variable "aurora_instance_count" {
  type        = number
  description = "Writer plus readers. Two or more places a reader in a second AZ for failover."
  default     = 2

  validation {
    condition     = var.aurora_instance_count >= 1
    error_message = "At least one Aurora instance is required."
  }
}

variable "aurora_min_capacity" {
  type        = number
  description = "Serverless v2 minimum ACUs."
  default     = 0.5
}

variable "aurora_max_capacity" {
  type        = number
  description = "Serverless v2 maximum ACUs."
  default     = 8
}

variable "backup_retention_days" {
  type        = number
  description = "Automated backup retention (point-in-time recovery window)."
  default     = 35
}

variable "deletion_protection" {
  type        = bool
  description = "Protect the cluster from deletion."
  default     = true
}

variable "alarm_topic_arn" {
  type        = string
  description = "SNS topic for CloudWatch alarms; empty disables notifications."
  default     = ""
}

variable "identity_provider_url" {
  type        = string
  description = "OIDC issuer of the platform Keycloak realm."
}

variable "observability_endpoint" {
  type        = string
  description = "OTLP or metrics endpoint of the platform observability stack."
}

variable "tags" {
  type        = map(string)
  description = "Additional tags (cost centre, data classification)."
  default     = {}
}

variable "msk_cluster_arn" {
  type        = string
  description = "ARN of the platform MSK cluster; grants IAM produce rights on the three evt.pay.mandate.*.v1 topics to the pods. Empty skips the policy."
  default     = ""
}
