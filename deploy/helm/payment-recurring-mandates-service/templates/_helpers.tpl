{{- define "mandates.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- /*
Name and instance, shared by the API pods and the db-migration Job pod. The
mesh NetworkPolicies grant datastore egress (Aurora 5432) by
app.kubernetes.io/name only, so both pods carry the service account name there
and app.kubernetes.io/component tells them apart.
*/ -}}
{{- define "mandates.instanceLabels" -}}
app.kubernetes.io/name: {{ include "mandates.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- /*
Selects the API pods only (Deployment, Service, PDB, spread constraints,
NetworkPolicy), never the db-migration Job pod. Changing a Deployment selector
is immutable on upgrade; see the runbook, section "Database roles".
*/ -}}
{{- define "mandates.selectorLabels" -}}
{{ include "mandates.instanceLabels" . }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "mandates.labels" -}}
{{ include "mandates.instanceLabels" . }}
app: {{ include "mandates.name" . }}
app.kubernetes.io/part-of: fintechbankx-payments
fintechbankx.io/service-id: svc-pay-recurring-mandates
fintechbankx.io/app: app-pay-recurring-mandates
app.kubernetes.io/version: {{ .Values.image.tag | default .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ .Chart.Name }}-{{ .Chart.Version }}
{{- end -}}

{{- define "mandates.secretName" -}}
{{ include "mandates.name" . }}-secrets
{{- end -}}

{{- /*
Secrets Manager key for an ExternalSecret remoteRef. Platform contract "Secret
stores and deploy supply chain": a service ExternalSecret may read only keys
under <env>/<service account>/ (admission policy fintechbankx-externalsecret-scope),
e.g. dev/payment-recurring-mandates-service/db-app. Anything else fails the render.
Usage: include "mandates.remoteKey" (list "externalSecret.remoteSecretName" .Values.externalSecret.remoteSecretName .)
*/ -}}
{{- define "mandates.remoteKey" -}}
{{- $field := index . 0 -}}
{{- $key := required (printf "%s is required" $field) (index . 1) -}}
{{- $root := index . 2 -}}
{{- $pattern := printf "^(dev|staging|prod)/%s/[a-z0-9-]+$" $root.Values.serviceAccount.name -}}
{{- if not (regexMatch $pattern $key) -}}
{{- fail (printf "%s must be <env>/%s/<name> (env dev, staging or prod), got %q" $field $root.Values.serviceAccount.name $key) -}}
{{- end -}}
{{- $key -}}
{{- end -}}

{{- /*
Labels of every ExternalSecret: the admission policy requires
app.kubernetes.io/name = the service account name.
*/ -}}
{{- define "mandates.externalSecretLabels" -}}
{{- if ne (include "mandates.name" .) .Values.serviceAccount.name -}}
{{- fail (printf "chart name %q must equal serviceAccount.name %q (ExternalSecret label app.kubernetes.io/name)" (include "mandates.name" .) .Values.serviceAccount.name) -}}
{{- end -}}
{{ include "mandates.labels" . }}
{{- end -}}

{{- define "mandates.migrationName" -}}
{{ include "mandates.name" . }}-db-migration
{{- end -}}

{{- /*
Round 6, guardrail 4a: no values override may move the service off the checked
JDBC URL, activate another profile or switch the startup TLS assertion off.

Two layers:
  1. The platform guard, vendored verbatim in templates/_fbx-guard.tpl
     (cicd-templates 2caa48f fbx.validateDatabaseTls and the helpers it calls):
     the datasource name regexes (fbx.datasourceOverrideName), the strict PgJDBC
     parse of every config and extraEnv value that is a PostgreSQL JDBC URL
     (fbx.validateJdbcUrl), extraEnv DB_URL (value or valueFrom) refused, JVM
     option variables (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS) with a
     literal value only and checked (fbx.validateJvmOptions), and the
     ExternalSecret keys. It reads .Values.databaseCa, .Values.extraEnv,
     .Values.externalSecret.{data,extraData} and .Values.javaToolOptions, which
     mandates.fbxValues maps from this chart's values without renaming them.
  2. This chart's additions (mandates.refusedEnvName, mandates.validateJvmWords,
     mandates.kafkaProfile): indexed and suffixed spring.config.* forms,
     spring.profiles.{active,include,default,group}, fintechbankx.tls.* in any
     form, spring.kafka.*security.protocol and spring.kafka.properties.*, the
     words fintechbankx and kafka in JVM options, KAFKA_SECURITY_PROTOCOL limited
     to the TLS protocols, config.DB_URL a PostgreSQL URL, and the profile
     rendered from kafka.profile (kafka-msk or kafka-strimzi only; the local
     profile, which sets fintechbankx.tls.enforce=false, can never be reached
     from values).
Names are compared the way Spring's relaxed binding reads them: upper case,
'.' and '-' read as '_'.
Usage: include "mandates.validateValues" . (configmap.yaml and deployment.yaml)
*/ -}}
{{- define "mandates.validateValues" -}}
{{- include "fbx.validateDatabaseTls" (include "mandates.fbxValues" . | fromJson) -}}
{{- range $name, $value := .Values.config -}}
{{- with include "mandates.refusedEnvName" $name -}}
{{- fail (printf "config.%s is not allowed: %s" $name .) -}}
{{- end -}}
{{- if include "fbx.isJvmOptionsName" $name -}}
{{- include "mandates.validateJvmWords" (dict "where" (printf "config.%s" $name) "value" $value) -}}
{{- end -}}
{{- end -}}
{{- range $env := .Values.extraEnv -}}
{{- $name := toString (required "extraEnv[].name is required" $env.name) -}}
{{- if eq ($name | upper | replace "." "_" | replace "-" "_") "DB_URL" -}}
{{- fail (printf "extraEnv must not set %s in any spelling (value or valueFrom): Kubernetes env wins over the ConfigMap, so the JDBC URL is config.DB_URL only, where it is checked" $name) -}}
{{- end -}}
{{- with include "mandates.refusedEnvName" $name -}}
{{- fail (printf "extraEnv must not set %s (value or valueFrom): %s" $name .) -}}
{{- end -}}
{{- if and (include "fbx.isJvmOptionsName" $name) (hasKey $env "value") -}}
{{- include "mandates.validateJvmWords" (dict "where" (printf "extraEnv.%s" $name) "value" $env.value) -}}
{{- end -}}
{{- end -}}
{{- if not (regexMatch "^jdbc:postgresql://" (toString .Values.config.DB_URL)) -}}
{{- fail "config.DB_URL must be a PostgreSQL JDBC URL (jdbc:postgresql://...), checked for sslmode=verify-full and the mounted RDS CA bundle" -}}
{{- end -}}
{{- if and (hasKey .Values.config "KAFKA_SECURITY_PROTOCOL") (not (has (toString .Values.config.KAFKA_SECURITY_PROTOCOL) (list "SASL_SSL" "SSL"))) -}}
{{- fail (printf "config.KAFKA_SECURITY_PROTOCOL must be SASL_SSL (MSK, IAM) or SSL (Strimzi mutual TLS), got %q" (toString .Values.config.KAFKA_SECURITY_PROTOCOL)) -}}
{{- end -}}
{{- end -}}

{{- /*
Adapter for the vendored platform guard: the root it expects, built from this
chart's values. databaseCa = rdsCaBundle (always mounted, so enabled);
externalSecret.data = the fixed env names the chart's two ExternalSecrets
materialise (externalsecret.yaml and migration-job.yaml), so the guard proves
they are credentials only; javaToolOptions: this chart has no such value.
Rendered as JSON because a helper can only return a string.
*/ -}}
{{- define "mandates.fbxValues" -}}
{{- dict "Values" (dict
      "databaseCa" (dict "enabled" true "mountPath" .Values.rdsCaBundle.mountPath "key" .Values.rdsCaBundle.key)
      "config" .Values.config
      "extraEnv" (.Values.extraEnv | default list)
      "externalSecret" (dict "enabled" true
        "data" (list (dict "secretKey" "SPRING_DATASOURCE_PASSWORD") (dict "secretKey" "SERVICE_CLIENT_SECRET"))
        "extraData" (list (dict "secretKey" "DB_MIGRATION_USERNAME") (dict "secretKey" "DB_MIGRATION_PASSWORD")))
      "javaToolOptions" "") | toJson -}}
{{- end -}}

{{- /*
This chart's name rules on top of fbx.datasourceOverrideName (which the vendored
guard already applies): prints the reason when the name is refused, nothing
otherwise. Checked on the relaxed-binding form of the name.
*/ -}}
{{- define "mandates.refusedEnvName" -}}
{{- $n := . | toString | upper | replace "." "_" | replace "-" "_" -}}
{{- if regexMatch "(?i)^spring[._-]?config[._-]?(import|location|additional[._-]?location|name)([._-]?[0-9]+)?[._-]?$" $n -}}
a config import, location or name (indexed forms included) can load a file or config tree that overrides the datasource or the TLS assertion; the chart renders no config import
{{- else if regexMatch "(?i)^spring[._-]?profiles[._-]?(active|include|default|group)([._-]|$)" $n -}}
a profile (active, include, default or group, indexed forms included) can activate an application-<profile> config in the image, the local one included; the chart sets the profile itself from kafka.profile
{{- else if regexMatch "(?i)^fintechbankx[._-]?tls" $n -}}
the startup TLS assertion (fintechbankx.tls.*) is never configured from values
{{- else if regexMatch "(?i)^spring[._-]?kafka[._-].*security[._-]?protocol|^spring[._-]?kafka[._-]?properties[._-]" $n -}}
it can move the Kafka client off SASL_SSL/SSL; the Kafka TLS settings come from the kafka-msk and kafka-strimzi profiles only
{{- end -}}
{{- end -}}

{{- /*
Words this chart refuses in a JVM option value on top of fbx.validateJvmOptions:
-Dfintechbankx.tls.enforce=false and -Dspring.kafka.* would switch the
assertion off or move the Kafka client off TLS.
*/ -}}
{{- define "mandates.validateJvmWords" -}}
{{- if regexMatch "(?i)fintechbankx|kafka" (toString .value) -}}
{{- fail (printf "%s must not mention fintechbankx or kafka (a JVM system property would switch the startup TLS assertion off or move the Kafka client off TLS)" .where) -}}
{{- end -}}
{{- end -}}

{{- /*
The Spring profile the chart renders as SPRING_PROFILES_ACTIVE: exactly one of
the Kafka profiles in the image (application-kafka-msk.yml, SASL_SSL with IAM;
application-kafka-strimzi.yml, SSL with the KafkaUser certificate). No list, no
other name: the local profile sets fintechbankx.tls.enforce=false and must
never reach a cluster.
*/ -}}
{{- define "mandates.kafkaProfile" -}}
{{- $profile := toString ((.Values.kafka | default dict).profile | default "") -}}
{{- if not (regexMatch "^(kafka-msk|kafka-strimzi)$" $profile) -}}
{{- fail (printf "kafka.profile must be kafka-msk or kafka-strimzi (one profile, no list; no other profile, local included, may be activated), got %q" $profile) -}}
{{- end -}}
{{- $profile -}}
{{- end -}}
