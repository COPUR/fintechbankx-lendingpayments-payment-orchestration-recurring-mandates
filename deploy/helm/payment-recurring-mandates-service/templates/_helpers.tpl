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
Guard for a runtime setting the chart puts into the pods' environment (a
`config` key, rendered into the ConfigMap, or an `extraEnv` name). Fails the
render for:
  - Spring config redirection (SPRING_CONFIG_IMPORT, SPRING_CONFIG_LOCATION,
    SPRING_CONFIG_ADDITIONAL_LOCATION): it would let a values override point
    the service at configuration outside this chart. The only configtree the
    platform allows is one the chart itself renders on the fixed mount
    optional:configtree:/etc/fintechbankx/config/; this chart renders none.
  - datasource and Flyway URL keys (SPRING_DATASOURCE_URL, SPRING_FLYWAY_URL):
    the database URL is config.DB_URL only, which the ConfigMap checks for
    sslmode=verify-full and the mounted RDS CA bundle.
  - FINTECHBANKX_TLS_ENFORCE: the service's startup TLS assertion is never
    switched off by the chart; only local and test configuration may.
The key is compared case-insensitively and in Spring's relaxed-binding
spellings (spring.config.import, spring-config-import, ...), so no spelling
slips past.
Usage: include "mandates.guardEnvKey" (list "config" $key)
*/ -}}
{{- define "mandates.guardEnvKey" -}}
{{- $source := index . 0 -}}
{{- $key := index . 1 -}}
{{- $normalised := $key | upper | replace "." "_" | replace "-" "_" -}}
{{- $forbidden := list "SPRING_CONFIG_IMPORT" "SPRING_CONFIG_LOCATION" "SPRING_CONFIG_ADDITIONAL_LOCATION" "SPRING_DATASOURCE_URL" "SPRING_FLYWAY_URL" "FINTECHBANKX_TLS_ENFORCE" -}}
{{- if has $normalised $forbidden -}}
{{- fail (printf "%s must not set %s: Spring config redirection, datasource/Flyway URL keys and FINTECHBANKX_TLS_ENFORCE are refused (the database URL is config.DB_URL; a configtree may only be rendered by the chart on optional:configtree:/etc/fintechbankx/config/)" $source $key) -}}
{{- end -}}
{{- end -}}
