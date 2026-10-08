{{- define "mandates.name" -}}
{{- .Chart.Name -}}
{{- end -}}

{{- define "mandates.selectorLabels" -}}
app.kubernetes.io/name: {{ include "mandates.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "mandates.labels" -}}
{{ include "mandates.selectorLabels" . }}
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
