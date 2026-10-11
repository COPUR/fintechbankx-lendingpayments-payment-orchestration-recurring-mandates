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

The guard is the platform's, vendored unchanged as templates/_fbx_helpers.tpl
(cicd-templates 6b6c317, charts/fintechbankx-service/templates/_helpers.tpl,
sha256 pinned in the README and in deployability.yml, checked by
scripts/ci/verify-vendored-guard.sh). fbx.guard reads only
.Values, so mandates.guard passes it an adapter built from this chart's values
(README of that chart, "Vendoring the guard"): config, extraEnv, envFrom and
extraEnvFrom (not rendered by this chart, mapped so a stray value is refused
rather than ignored), javaToolOptions (none here), databaseCa = rdsCaBundle (enabled, mountPath, key and
configMapName; the guard pins them to /etc/fintechbankx/rds-ca, global-bundle.pem and
rds-ca-bundle and requires configMapName),
kafka.runtime = kafka.profile mapped (kafka-msk -> msk, kafka-strimzi ->
strimzi) and externalSecret = the fixed keys of the chart's two ExternalSecrets
(externalsecret.yaml, migration-job.yaml) plus a dataFrom value, if ever set,
so it is refused. fbx.guard covers: the PgJDBC parse of every PostgreSQL URL in
config or extraEnv, DB_URL in any spelling outside config.DB_URL, Spring
datasource/Flyway/Liquibase/R2DBC, spring.config.*, spring.profiles.*,
spring.ssl.*, ssl bundle, sslmode/sslrootcert (DB_SSL_ROOT_CERT) and
fintechbankx.tls.* names in any spelling (relaxed-binding canonical form),
JVM option values (literal only, no file, no TLS, datasource, profile or
config word, no $( or ${), $( in extraEnv values, envFrom/dataFrom, key and
name shapes, the Kafka client TLS names (spring.kafka.ssl.*,
spring.kafka.properties.ssl.*, per-client forms), KAFKA_TLS_* and MONGODB_URI (Secret only),
spring.data.mongodb.* and kafka or mongodb in a JVM option (all also with '_' inside an
element), and every *security.protocol or *endpoint.identification.algorithm value
against kafka.runtime (SASL_SSL with msk, SSL with strimzi, https).

Kept here because fbx.guard does not do them:
  - mandates.kafkaRuntime: kafka.profile is required and must be exactly
    kafka-msk or kafka-strimzi (fbx.kafkaProfile would render no profile for
    an empty runtime; this service needs one), and the ConfigMap renders
    KAFKA_SECURITY_PROTOCOL from it unless config sets it;
  - mandates.refusedEnvName: spring.kafka.*security.protocol names are refused
    outright (fbx.guard checks a security.protocol value, not the name) and so
    is the whole spring.kafka.properties.* prefix, because fbx.guard refuses
    only spring.kafka.properties.ssl.* and lets spring.kafka.properties.sasl.*
    through; checked on the name as given and on fbx.canonicalName. The guard's
    message fires first for the ssl names;
  - mandates.validateJvmWords: a JVM option value may not mention fintechbankx
    at all (fbx.validateJvmOptions refuses fintechbankx.tls but not other
    fintechbankx.* properties; kafka and mongodb are now refused by the guard
    itself, so the chart rule no longer repeats them).
Usage: include "mandates.guard" . at the top of deployment.yaml and migration-job.yaml.
*/ -}}
{{- define "mandates.guard" -}}
{{- include "fbx.guard" (include "mandates.fbxValues" . | fromJson) -}}
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
{{- with include "mandates.refusedEnvName" $name -}}
{{- fail (printf "extraEnv must not set %s (value or valueFrom): %s" $name .) -}}
{{- end -}}
{{- if and (include "fbx.isJvmOptionsName" $name) (hasKey $env "value") -}}
{{- include "mandates.validateJvmWords" (dict "where" (printf "extraEnv.%s" $name) "value" $env.value) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- /*
Adapter for fbx.guard: the .Values it reads, built from this chart's values
(rendered as JSON because a helper returns a string). The ExternalSecret
entries carry the remoteSecretName values so fbx.validateKeyNames sees them.
*/ -}}
{{- define "mandates.fbxValues" -}}
{{- $es := .Values.externalSecret | default dict -}}
{{- $mig := .Values.migration | default dict -}}
{{- dict "Values" (dict
      "config" (.Values.config | default dict)
      "extraEnv" (.Values.extraEnv | default list)
      "envFrom" (.Values.envFrom | default list)
      "extraEnvFrom" (.Values.extraEnvFrom | default list)
      "javaToolOptions" ""
      "databaseCa" (dict "enabled" true "mountPath" .Values.rdsCaBundle.mountPath "key" .Values.rdsCaBundle.key "configMapName" .Values.rdsCaBundle.configMapName)
      "kafka" (dict "runtime" (include "mandates.kafkaRuntime" .))
      "externalSecret" (dict "enabled" true
        "data" (list
          (dict "secretKey" "SPRING_DATASOURCE_PASSWORD" "property" "password" "remoteSecretName" ($es.remoteSecretName | default ""))
          (dict "secretKey" "SERVICE_CLIENT_SECRET" "property" "client_secret" "remoteSecretName" ($es.serviceClientSecretName | default "")))
        "extraData" (list
          (dict "secretKey" "DB_MIGRATION_USERNAME" "property" "username" "remoteSecretName" ($mig.remoteSecretName | default ""))
          (dict "secretKey" "DB_MIGRATION_PASSWORD" "property" "password" "remoteSecretName" ($mig.remoteSecretName | default "")))
        "dataFrom" ($es.dataFrom | default list))) | toJson -}}
{{- end -}}

{{- /*
kafka.profile -> fbx.guard's kafka.runtime. Exactly one of the Kafka profiles
in the image (application-kafka-msk.yml, SASL_SSL with IAM;
application-kafka-strimzi.yml, SSL with the KafkaUser certificate); no list,
no other name, not empty: the local profile sets fintechbankx.tls.enforce=false
and must never reach a cluster. The ConfigMap renders SPRING_PROFILES_ACTIVE
through fbx.kafkaProfile from this runtime.
*/ -}}
{{- define "mandates.kafkaRuntime" -}}
{{- $profile := toString ((.Values.kafka | default dict).profile | default "") -}}
{{- if eq $profile "kafka-msk" -}}msk
{{- else if eq $profile "kafka-strimzi" -}}strimzi
{{- else -}}
{{- fail (printf "kafka.profile must be kafka-msk or kafka-strimzi (one profile, no list; no other profile, local included, may be activated), got %q" $profile) -}}
{{- end -}}
{{- end -}}

{{- define "mandates.kafkaProtocol" -}}
{{- if eq (include "mandates.kafkaRuntime" .) "msk" -}}SASL_SSL{{- else -}}SSL{{- end -}}
{{- end -}}

{{- /*
This chart's name rules on top of fbx.guard: prints the reason when the name is
refused, nothing otherwise. Checked on the name as given and on its
relaxed-binding canonical form.
*/ -}}
{{- define "mandates.refusedEnvName" -}}
{{- $n := toString . -}}
{{- $rule := "(?i)^spring[._-]?kafka[._-].*security[._-]?protocol|^spring[._-]?kafka[._-]?properties([._-]|$)" -}}
{{- if or (regexMatch $rule $n) (regexMatch "^spring\\.?kafka\\..*security\\.?protocol|^spring\\.?kafka\\.?properties(\\.|$)" (include "fbx.canonicalName" $n)) -}}
it can move the Kafka client off SASL_SSL/SSL or change its SASL settings; the Kafka TLS settings come from the kafka-msk and kafka-strimzi profiles only
{{- end -}}
{{- end -}}

{{- /*
Words this chart refuses in a JVM option value on top of fbx.validateJvmOptions.
*/ -}}
{{- define "mandates.validateJvmWords" -}}
{{- if regexMatch "(?i)fintechbankx" (toString .value) -}}
{{- fail (printf "%s must not mention fintechbankx (a JVM system property would switch the startup TLS assertion off)" .where) -}}
{{- end -}}
{{- end -}}
