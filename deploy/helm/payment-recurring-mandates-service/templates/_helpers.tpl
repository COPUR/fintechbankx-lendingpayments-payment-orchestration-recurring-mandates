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
