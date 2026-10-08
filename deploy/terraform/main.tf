# AWS resources owned by svc-pay-recurring-mandates: its own Aurora PostgreSQL
# cluster (db_pay_recurring_mandates_<env>), encryption key, credentials, the
# IRSA role its pods use and their MSK produce rights. Shared platform pieces
# (log group, SSM parameters, runtime secret) come from the platform
# microservice-base module.
#
# Secret names: <env>/<service account>/<name> (platform contract "Secret
# stores and deploy supply chain"); the platform ESO role may read only
# secret:<env>/* and an admission policy rejects ExternalSecrets outside
# <env>/payment-recurring-mandates-service/.
# Known platform issue: microservice-base (ref=main) names its runtime secret
# "<env>-<slug>/runtime", outside that path, and still uses timestamp() in tags.
# Platform fixes both in terraform-modules #11; this service does not read that
# secret and does not work around it here.

locals {
  service_id   = "svc-pay-recurring-mandates"
  service_slug = "payment-recurring-mandates-service"
  name         = "${var.environment}-${local.service_slug}"
  database     = "db_pay_recurring_mandates_${var.environment}"

  tags = merge({
    Service            = local.service_id
    BoundedContext     = "payments"
    OwningSquad        = "recurring-and-bulk-payments"
    Environment        = var.environment
    DataClassification = "confidential"
    ManagedBy          = "terraform"
  }, var.tags)
}

data "aws_caller_identity" "current" {}

module "service_base" {
  source = "git::https://github.com/COPUR/fintechbankx-platform-delivery-iac-terraform-modules.git//modules/microservice-base?ref=main"

  service_name           = "Recurring Payments and Mandates Service"
  service_slug           = local.service_slug
  environment            = var.environment
  database_engine        = "aurora-postgresql"
  cache_engine           = "none"
  identity_provider_url  = var.identity_provider_url
  observability_endpoint = var.observability_endpoint
  parameter_prefix       = "/fintechbankx"
  log_retention_days     = var.environment == "prod" ? 365 : 30
  tags                   = local.tags
}

# --- Encryption -------------------------------------------------------------
# External Secrets Operator runs with its own platform IRSA role and may
# decrypt keys tagged fintechbankx.io/secrets=true (platform contract); the
# key policy only delegates to IAM in this account.

data "aws_iam_policy_document" "database_key" {
  statement {
    sid       = "EnableIamPolicies"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:aws:iam::${data.aws_caller_identity.current.account_id}:root"]
    }
  }
}

resource "aws_kms_key" "database" {
  description             = "Encrypts ${local.database} storage, snapshots, logs and credentials"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.database_key.json

  tags = {
    "fintechbankx.io/secrets" = "true"
  }
}

resource "aws_kms_alias" "database" {
  name          = "alias/${local.name}-db"
  target_key_id = aws_kms_key.database.key_id
}

# --- Network ----------------------------------------------------------------

resource "aws_db_subnet_group" "database" {
  name       = "${local.name}-db"
  subnet_ids = var.private_subnet_ids
}

resource "aws_security_group" "database" {
  name        = "${local.name}-db"
  description = "PostgreSQL access for ${local.service_id} only"
  vpc_id      = var.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "postgres_from_workload" {
  security_group_id            = aws_security_group.database.id
  referenced_security_group_id = var.workload_security_group_id
  ip_protocol                  = "tcp"
  from_port                    = 5432
  to_port                      = 5432
  description                  = "PostgreSQL from ${local.service_id} pods"
}

# --- Aurora PostgreSQL (Serverless v2, Multi-AZ) ---------------------------

resource "aws_rds_cluster_parameter_group" "database" {
  name   = "${local.name}-aurora-pg16"
  family = "aurora-postgresql16"

  parameter {
    name  = "rds.force_ssl"
    value = "1"
  }

  parameter {
    name  = "log_min_duration_statement"
    value = "500"
  }
}

resource "aws_rds_cluster" "database" {
  cluster_identifier                  = "${local.name}-aurora"
  engine                              = "aurora-postgresql"
  engine_mode                         = "provisioned"
  engine_version                      = var.aurora_engine_version
  database_name                       = local.database
  master_username                     = "mandates_admin"
  manage_master_user_password         = true
  master_user_secret_kms_key_id       = aws_kms_key.database.key_id
  db_subnet_group_name                = aws_db_subnet_group.database.name
  vpc_security_group_ids              = [aws_security_group.database.id]
  db_cluster_parameter_group_name     = aws_rds_cluster_parameter_group.database.name
  storage_encrypted                   = true
  kms_key_id                          = aws_kms_key.database.arn
  iam_database_authentication_enabled = true
  backup_retention_period             = var.backup_retention_days
  preferred_backup_window             = "01:00-02:00"
  preferred_maintenance_window        = "sun:03:00-sun:04:00"
  copy_tags_to_snapshot               = true
  deletion_protection                 = var.deletion_protection
  skip_final_snapshot                 = false
  final_snapshot_identifier           = "${local.name}-aurora-final"
  enabled_cloudwatch_logs_exports     = ["postgresql"]

  serverlessv2_scaling_configuration {
    min_capacity = var.aurora_min_capacity
    max_capacity = var.aurora_max_capacity
  }

  lifecycle {
    # Minor versions are upgraded by AWS (auto_minor_version_upgrade); do not fight them.
    ignore_changes = [engine_version]
  }
}

resource "aws_rds_cluster_instance" "database" {
  count                                 = var.aurora_instance_count
  identifier                            = "${local.name}-aurora-${count.index + 1}"
  cluster_identifier                    = aws_rds_cluster.database.id
  instance_class                        = "db.serverless"
  engine                                = aws_rds_cluster.database.engine
  engine_version                        = aws_rds_cluster.database.engine_version
  db_subnet_group_name                  = aws_db_subnet_group.database.name
  publicly_accessible                   = false
  auto_minor_version_upgrade            = true
  performance_insights_enabled          = true
  performance_insights_kms_key_id       = aws_kms_key.database.arn
  performance_insights_retention_period = 7
  promotion_tier                        = count.index

  lifecycle {
    ignore_changes = [engine_version]
  }
}

# Application credential (role pay_recurring_mandates_app, owner of schema
# sc_pay_recurring_mandates). The DBA bootstrap in docs/migration creates the
# role and writes {"username", "password"} here; Terraform never sees the value.
# Read by External Secrets Operator, not by the pods.
resource "aws_secretsmanager_secret" "app_database" {
  # <env>/<service account>/db-app; "<env>-<slug>/db-app" would be refused by ESO.
  name                    = "${var.environment}/${local.service_slug}/db-app"
  description             = "Application database credential for ${local.service_id}"
  kms_key_id              = aws_kms_key.database.arn
  recovery_window_in_days = 7
}

# --- IRSA: the pods' AWS identity -------------------------------------------

data "aws_iam_policy_document" "irsa_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.eks_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.eks_oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${var.kubernetes_namespace}:${var.kubernetes_service_account}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.eks_oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "workload" {
  name               = "${local.name}-irsa"
  assume_role_policy = data.aws_iam_policy_document.irsa_trust.json
}

# Secrets reach the pods through External Secrets, so the pods only read
# their own SSM parameters.
data "aws_iam_policy_document" "workload" {
  statement {
    sid       = "ReadOwnParameters"
    actions   = ["ssm:GetParameter", "ssm:GetParametersByPath"]
    resources = ["arn:aws:ssm:${var.aws_region}:${data.aws_caller_identity.current.account_id}:parameter/fintechbankx/${var.environment}/${local.service_slug}/*"]
  }
}

resource "aws_iam_role_policy" "workload" {
  name   = "${local.name}-least-privilege"
  role   = aws_iam_role.workload.id
  policy = data.aws_iam_policy_document.workload.json
}

# --- MSK: IAM client auth, scoped to this service's own topics --------------
# Platform contract: Kafka on AWS is MSK with IAM auth via the IRSA role.
# TODO: switch to the terraform-modules `msk-client-access` module once it is
# published on main (not there as of 2026-10-08).

locals {
  msk_topic_arn_prefix = var.msk_cluster_arn == "" ? "" : replace(var.msk_cluster_arn, ":cluster/", ":topic/")
  msk_group_arn_prefix = var.msk_cluster_arn == "" ? "" : replace(var.msk_cluster_arn, ":cluster/", ":group/")
  # Exactly the topics in api/asyncapi/svc-pay-recurring-mandates.yaml. No DLQ:
  # dead-letter topics belong to consumers (ADR-019/024).
  published_topics = [
    "evt.pay.mandate.created.v1",
    "evt.pay.mandate.revoked.v1",
    "evt.pay.mandate.payment-accepted.v1",
  ]
}

data "aws_iam_policy_document" "msk" {
  count = var.msk_cluster_arn == "" ? 0 : 1

  statement {
    sid       = "ConnectToCluster"
    actions   = ["kafka-cluster:Connect", "kafka-cluster:DescribeCluster"]
    resources = [var.msk_cluster_arn]
  }

  statement {
    sid = "ProduceOwnTopics"
    actions = [
      "kafka-cluster:DescribeTopic",
      "kafka-cluster:WriteData",
      "kafka-cluster:WriteDataIdempotently",
    ]
    resources = [for topic in local.published_topics : "${local.msk_topic_arn_prefix}/${topic}"]
  }

  statement {
    sid       = "IdempotentProducerTransactionalIds"
    actions   = ["kafka-cluster:DescribeTransactionalId", "kafka-cluster:AlterTransactionalId"]
    resources = ["${local.msk_group_arn_prefix}/${local.service_id}*"]
  }
}

resource "aws_iam_role_policy" "msk" {
  count  = var.msk_cluster_arn == "" ? 0 : 1
  name   = "${local.name}-msk-produce"
  role   = aws_iam_role.workload.id
  policy = data.aws_iam_policy_document.msk[0].json
}

# --- Alarms -----------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "aurora_capacity" {
  alarm_name          = "${local.name}-aurora-acu-high"
  alarm_description   = "Aurora is near its max ACUs; raise aurora_max_capacity or look for a runaway query."
  namespace           = "AWS/RDS"
  metric_name         = "ACUUtilization"
  dimensions          = { DBClusterIdentifier = aws_rds_cluster.database.cluster_identifier }
  statistic           = "Average"
  period              = 300
  evaluation_periods  = 3
  threshold           = 85
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
  ok_actions          = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}

resource "aws_cloudwatch_metric_alarm" "aurora_connections" {
  alarm_name          = "${local.name}-aurora-connections-high"
  alarm_description   = "Connections near the pool budget (HPA max replicas x DB_POOL_MAX)."
  namespace           = "AWS/RDS"
  metric_name         = "DatabaseConnections"
  dimensions          = { DBClusterIdentifier = aws_rds_cluster.database.cluster_identifier }
  statistic           = "Maximum"
  period              = 300
  evaluation_periods  = 2
  threshold           = 100
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  alarm_actions       = var.alarm_topic_arn == "" ? [] : [var.alarm_topic_arn]
}
