{{/*
Vendored copy. Do not edit here.
Source: fintechbankx-platform-delivery-iac-cicd-templates, commit 2caa48f,
charts/fintechbankx-service/templates/_helpers.tpl, lines 70-239 (the datasource
guard: fbx.datasourceOverrideName, fbx.isJvmOptionsName, fbx.validateJvmOptions,
fbx.validateDatabaseTls, fbx.validateJdbcUrl), verbatim. The sha256 of this file
is pinned in the README ("Helm chart" row) and checked by the deploy/helm job
(deployability.yml, step "Vendored guard matches cicd-templates"). To update,
copy the lines again from the newer commit and change the pin in both places.
The helpers read .Values.databaseCa, .Values.config, .Values.extraEnv,
.Values.externalSecret.{enabled,data,extraData} and .Values.javaToolOptions;
this chart passes them an adapter (mandates.fbxValues in _helpers.tpl) built
from its own values. This repo's additions (profile, indexed, TLS-switch and
Kafka name rules, the kafka.profile value, the fintechbankx/kafka JVM words) are
in _helpers.tpl, not here.
*/}}
{{/*
Every PostgreSQL JDBC URL the chart passes to the workload must verify the
server certificate and host name (sslmode=require encrypts but trusts any
certificate). The query string is parsed the way PgJDBC reads it (split after
the first '?', then on '&', key=value on the first '='), not searched for a
substring:
  - exactly one sslmode, equal to verify-full;
  - with databaseCa.enabled, exactly one sslrootcert, equal to
    <databaseCa.mountPath>/<databaseCa.key> (at most one otherwise);
  - no sslfactory / sslfactoryarg (NonValidatingFactory), sslhostnameverifier,
    sslpasswordcallback or service (pg_service.conf can override the TLS settings);
  - parameter names are plain [A-Za-z0-9_.-] (no percent-encoding), no
    percent-encoded '=' or '&' anywhere in the query, TLS keys in lower case
    (the driver ignores SSLMODE and would fall back to sslmode=prefer), and no
    TLS key before the '?'.
Checked values: every config value and every extraEnv value that starts with
jdbc:[<wrapper>:]postgresql: (case-insensitive).

Names (fbx.datasourceOverrideName; config keys, extraEnv names whether they use
value or valueFrom, and externalSecret.data / extraData secretKeys, which are
the env names the Secret materialises and whose values are never seen here):
  - (?i)^spring[._-]?(datasource|flyway|liquibase|r2dbc)[._-] (any Spring
    datasource, Flyway, Liquibase or R2DBC property, not only *URL), except
    SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD;
  - spring.application.json in any spelling ([._-] or none, any case);
  - any name containing jdbc[._-]?url, sslfactory or sslhostnameverifier;
  - DB_URL outside config (config.DB_URL is the one allowed place);
  - (?i)^spring[._-]?config[._-]?(import|location|additional[._-]?location|name)$:
    an imported file or config tree, or another config file name in the
    image, can set spring.datasource.* where the chart never sees it. The chart renders no config import; a configtree, if
    a service ever needs one, must be rendered by the chart itself on the fixed
    mount optional:configtree:/etc/fintechbankx/config/ from a boolean value,
    never taken from a user-supplied value;
  - (?i)^spring[._-]?profiles[._-]?(active|include)$: a profile switches on an
    application-<profile>.yml inside the image. The chart renders no
    SPRING_PROFILES_ACTIVE, so no user-set profile name is allowed.
The helper prints the reason (non-empty means rejected).
JVM options (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS and the chart's
javaToolOptions) can set -Dspring.datasource.url=..., -Djavax.net.ssl.*,
-Dspring.config.* or -Dspring.profiles.*, or read more options from a file:
a value that mentions datasource, flyway, liquibase, r2dbc, jdbc, ssl,
application[._-]json, spring[._-]config or spring[._-]profiles, an option that
starts with '@' (argument file), -XX:VMOptionsFile or -XX:Flags
(case-insensitive) is rejected, and these names may not come from extraEnv
valueFrom or the ExternalSecret.
This closes the chart-side routes only; a profile or config file baked into
the image, and TLS on routes the chart does not see (the Kafka client, a
datasource built in code), are the service's own startup check (README,
"Service-side TLS assertion").
*/}}
{{- define "fbx.datasourceOverrideName" -}}
{{- $n := toString . -}}
{{- if regexMatch "(?i)^SPRING_DATASOURCE_(USERNAME|PASSWORD)$" $n -}}
{{- else if regexMatch "(?i)^spring[._-]?(datasource|flyway|liquibase|r2dbc)[._-]|^spring[._-]?application[._-]?json$|jdbc[._-]?url|sslfactory|sslhostnameverifier" $n -}}
it can redirect or override the datasource past the sslmode=verify-full check; set the JDBC URL in config.DB_URL
{{- else if regexMatch "(?i)^spring[._-]?config[._-]?(import|location|additional[._-]?location|name)$" $n -}}
a config import, location or name can load a file or config tree that overrides the datasource past the sslmode=verify-full check; the chart renders no config import
{{- else if regexMatch "(?i)^spring[._-]?profiles[._-]?(active|include)$" $n -}}
a profile can activate an application-<profile> config in the image whose datasource the chart cannot check; the chart sets no profile
{{- end -}}
{{- end -}}

{{- define "fbx.isJvmOptionsName" -}}
{{- if regexMatch "(?i)^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)$" (toString .) -}}true{{- end -}}
{{- end -}}

{{- define "fbx.validateJvmOptions" -}}
{{- if regexMatch "(?i)datasource|flyway|liquibase|r2dbc|jdbc|ssl|application[._-]?json|spring[._-]?config|spring[._-]?profiles|(^|\\s)@|-XX:(VMOptionsFile|Flags)" (toString .value) -}}
{{- fail (printf "%s must not mention datasource, flyway, liquibase, r2dbc, jdbc, ssl, application.json, spring.config or spring.profiles, nor read options from a file ('@' argument file, -XX:VMOptionsFile, -XX:Flags) (JVM system properties would override the datasource past the sslmode=verify-full check)" .where) -}}
{{- end -}}
{{- end -}}

{{- define "fbx.validateDatabaseTls" -}}
{{- $root := . -}}
{{- include "fbx.validateJvmOptions" (dict "where" "javaToolOptions" "value" .Values.javaToolOptions) -}}
{{- range $name, $value := .Values.config -}}
{{- with include "fbx.datasourceOverrideName" $name -}}
{{- fail (printf "config.%s is not allowed: %s" $name .) -}}
{{- end -}}
{{- if include "fbx.isJvmOptionsName" $name -}}
{{- include "fbx.validateJvmOptions" (dict "where" (printf "config.%s" $name) "value" $value) -}}
{{- end -}}
{{- include "fbx.validateJdbcUrl" (dict "root" $root "where" (printf "config.%s" $name) "url" (toString $value)) -}}
{{- end -}}
{{- range $env := .Values.extraEnv -}}
{{- $envName := toString (default "" $env.name) -}}
{{- if eq (upper $envName) "DB_URL" -}}
{{- fail (printf "extraEnv must not set %s (value or valueFrom); set the JDBC URL in config.DB_URL, where sslmode=verify-full is enforced" $envName) -}}
{{- end -}}
{{- with include "fbx.datasourceOverrideName" $envName -}}
{{- fail (printf "extraEnv must not set %s (value or valueFrom): %s" $envName .) -}}
{{- end -}}
{{- if include "fbx.isJvmOptionsName" $envName -}}
{{- if not (hasKey $env "value") -}}
{{- fail (printf "extraEnv %s must set a literal value (valueFrom cannot be checked)" $envName) -}}
{{- end -}}
{{- include "fbx.validateJvmOptions" (dict "where" (printf "extraEnv.%s" $envName) "value" $env.value) -}}
{{- end -}}
{{- if hasKey $env "value" -}}
{{- include "fbx.validateJdbcUrl" (dict "root" $root "where" (printf "extraEnv.%s" $envName) "url" (toString $env.value)) -}}
{{- end -}}
{{- end -}}
{{- if .Values.externalSecret.enabled -}}
{{- range $field := list "data" "extraData" -}}
{{- range $entry := (index $root.Values.externalSecret $field | default list) -}}
{{- $key := toString (default "" $entry.secretKey) -}}
{{- if or (eq (upper $key) "DB_URL") (include "fbx.isJvmOptionsName" $key) -}}
{{- fail (printf "externalSecret.%s must not materialise %s; set the JDBC URL in config.DB_URL and JVM options in javaToolOptions, where they are checked (keep only the credentials in the secret)" $field $key) -}}
{{- end -}}
{{- with include "fbx.datasourceOverrideName" $key -}}
{{- fail (printf "externalSecret.%s must not materialise %s (keep only the credentials in the secret): %s" $field $key .) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "fbx.validateJdbcUrl" -}}
{{- $where := .where -}}
{{- $url := trim .url -}}
{{- if regexMatch "(?i)^jdbc:(?:[a-z0-9-]+:)*postgresql:" $url -}}
{{- $ca := .root.Values.databaseCa -}}
{{- $want := printf "%s/%s" (trimSuffix "/" (toString $ca.mountPath)) (toString $ca.key) -}}
{{- $parts := regexSplit "\\?" $url 2 -}}
{{- $base := index $parts 0 -}}
{{- $query := "" -}}
{{- if eq (len $parts) 2 -}}{{- $query = index $parts 1 -}}{{- end -}}
{{- if regexMatch "(?i)ssl(mode|rootcert|factory|hostnameverifier)" $base -}}
{{- fail (printf "%s must carry TLS parameters only in the query string (after '?')" $where) -}}
{{- end -}}
{{- if regexMatch "(?i)%(3d|26)" $query -}}
{{- fail (printf "%s must not percent-encode '=' or '&' in the query string" $where) -}}
{{- end -}}
{{- $modes := list -}}
{{- $roots := list -}}
{{- range $param := splitList "&" $query -}}
{{- if $param -}}
{{- $kv := regexSplit "=" $param 2 -}}
{{- $key := index $kv 0 -}}
{{- $val := "" -}}
{{- if eq (len $kv) 2 -}}{{- $val = index $kv 1 -}}{{- end -}}
{{- if not (regexMatch "^[A-Za-z0-9_.-]+$" $key) -}}
{{- fail (printf "%s has a query parameter name that is not plain [A-Za-z0-9_.-] (percent-encoding is not allowed): %q" $where $key) -}}
{{- end -}}
{{- $lk := lower $key -}}
{{- if has $lk (list "sslfactory" "sslfactoryarg" "sslhostnameverifier" "sslpasswordcallback" "service") -}}
{{- fail (printf "%s must not set %s (it can bypass certificate or host name verification)" $where $lk) -}}
{{- end -}}
{{- if and (has $lk (list "sslmode" "sslrootcert")) (ne $key $lk) -}}
{{- fail (printf "%s must spell %s in lower case (PgJDBC ignores it otherwise)" $where $key) -}}
{{- end -}}
{{- if eq $key "sslmode" -}}{{- $modes = append $modes $val -}}{{- end -}}
{{- if eq $key "sslrootcert" -}}{{- $roots = append $roots $val -}}{{- end -}}
{{- end -}}
{{- end -}}
{{- if gt (len $modes) 1 -}}
{{- fail (printf "%s must set sslmode exactly once (found %d)" $where (len $modes)) -}}
{{- end -}}
{{- if or (eq (len $modes) 0) (ne (index (append $modes "") 0) "verify-full") -}}
{{- fail (printf "%s must use sslmode=verify-full (with sslrootcert=%s)" $where $want) -}}
{{- end -}}
{{- if $ca.enabled -}}
{{- if or (ne (len $roots) 1) (ne (index (append $roots "") 0) $want) -}}
{{- fail (printf "%s must set sslrootcert=%s exactly once (the databaseCa bundle)" $where $want) -}}
{{- end -}}
{{- else if gt (len $roots) 1 -}}
{{- fail (printf "%s must set sslrootcert at most once" $where) -}}
{{- end -}}
{{- end -}}
{{- end -}}
