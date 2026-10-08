output "workload_role_arn" {
  description = "IRSA role for the Helm value serviceAccount.roleArn."
  value       = aws_iam_role.workload.arn
}

output "jdbc_url" {
  description = "Helm value config.DB_URL."
  value       = "jdbc:postgresql://${aws_rds_cluster.database.endpoint}:5432/${local.database}?sslmode=require"
}

output "reader_endpoint" {
  description = "Aurora reader endpoint for reporting and reconciliation queries."
  value       = aws_rds_cluster.database.reader_endpoint
}

output "app_db_secret_name" {
  description = "Helm value externalSecret.remoteSecretName."
  value       = aws_secretsmanager_secret.app_database.name
}

output "master_user_secret_arn" {
  description = "RDS-managed admin credential, for the DBA bootstrap only."
  value       = aws_rds_cluster.database.master_user_secret[0].secret_arn
}

output "log_group_name" {
  value = module.service_base.cloudwatch_log_group_name
}
