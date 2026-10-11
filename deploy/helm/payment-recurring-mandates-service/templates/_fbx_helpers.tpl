{{/* Workload name: serviceName, which is also the PLATFORM_CONTRACT <sa> name. */}}
{{- define "fbx.name" -}}
{{- required "serviceName is required (e.g. loan-lifecycle-service)" .Values.serviceName | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "fbx.serviceAccountName" -}}
{{- default (include "fbx.name" .) .Values.serviceAccount.name -}}
{{- end -}}

{{- define "fbx.secretName" -}}
{{- printf "%s-secrets" (include "fbx.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "fbx.version" -}}
{{- if .Values.image.tag -}}
{{- .Values.image.tag | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- .Chart.AppVersion -}}
{{- end -}}
{{- end -}}

{{/*
Selector labels. app.kubernetes.io/component=service keeps the Service, PDB,
NetworkPolicy and topology spread off the Flyway migration Job pods, which
carry the same app.kubernetes.io/name=<sa> (the mesh keys datastore egress on
it) with app.kubernetes.io/component=db-migration.
*/}}
{{- define "fbx.selectorLabels" -}}
app.kubernetes.io/name: {{ include "fbx.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
{{- end -}}

{{- define "fbx.labels" -}}
{{ include "fbx.selectorLabels" . }}
app.kubernetes.io/version: {{ include "fbx.version" . | quote }}
app.kubernetes.io/part-of: {{ printf "fintechbankx-%s" (required "boundedContext is required (e.g. lending)" .Values.boundedContext) }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
fintechbankx.io/service-id: {{ required "serviceId is required (e.g. svc-ln-loan-lifecycle)" .Values.serviceId }}
fintechbankx.io/context: {{ .Values.boundedContext }}
{{- end -}}

{{/* Pod labels: common labels plus the Istio canonical app/version labels. */}}
{{- define "fbx.podLabels" -}}
{{ include "fbx.labels" . }}
app: {{ include "fbx.name" . }}
version: {{ include "fbx.version" . | quote }}
{{- if .Values.istio.inject }}
sidecar.istio.io/inject: "true"
{{- end }}
{{- with .Values.podLabels }}
{{ toYaml . }}
{{- end }}
{{- end -}}

{{/* Image reference: digest wins; a mutable "latest" tag is rejected. */}}
{{- define "fbx.image" -}}
{{- $repo := required "image.repository is required" .Values.image.repository -}}
{{- if eq (lower (toString .Values.image.tag)) "latest" -}}
{{- fail "image.tag=latest is not allowed; use the git SHA tag or image.digest" -}}
{{- end -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" $repo .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" $repo (required "image.tag or image.digest is required" .Values.image.tag) -}}
{{- end -}}
{{- end -}}

{{/*
fbx.guard: the datasource / TLS guard, the one entry point a chart calls (at
the top of its deployment template; any one rendered template is enough, a
failure stops the whole render). It reads only .Values, so a service chart
with other value names vendors this file unchanged and passes an adapter dict
(README "Vendoring the guard"):
  include "fbx.guard" (dict "Values" (dict "config" <map> "extraEnv" <list>
    "envFrom" <list> "extraEnvFrom" <list>
    "javaToolOptions" <string>
    "databaseCa" <dict enabled/mountPath/key/configMapName>
    "kafka" (dict "runtime" <""|msk|strimzi>)
    "externalSecret" (dict "enabled" <bool> "data" <list> "extraData" <list>
      "dataFrom" <list>)))
Every key is optional except that a databaseCa with enabled: true needs
mountPath, key and configMapName, which must be /etc/fintechbankx/rds-ca,
global-bundle.pem and rds-ca-bundle; the adapter passes the configMapName
its templates mount (fbx.validateDatabaseCa). A key left out is never checked, so every value of
the chart that feeds one of these routes must be mapped, including any
envFrom-like list and an ExternalSecret dataFrom (the guard refuses them
when non-empty). It runs fbx.validateEnvSources, fbx.validateDatabaseCa,
fbx.validateDatabaseTls, fbx.validateKafkaTls, fbx.validateSecretNames and
fbx.validateKeyNames.
*/}}
{{- define "fbx.guard" -}}
{{- include "fbx.validateEnvSources" . -}}
{{- include "fbx.validateDatabaseCa" . -}}
{{- include "fbx.validateDatabaseTls" . -}}
{{- include "fbx.validateKafkaTls" . -}}
{{- include "fbx.validateSecretNames" . -}}
{{- include "fbx.validateKeyNames" . -}}
{{- end -}}

{{/*
Key and name shapes. The guard reads a config key, an extraEnv name or an
ExternalSecret secretKey as one name; a key with a newline or quote that a
template writes unquoted can open further keys (SPRING_CONFIG_IMPORT, DB_URL,
SPRING_DATASOURCE_URL) the guard never sees. So, after the name rules (whose
messages are more specific):
  - config keys and externalSecret data/extraData secretKeys must be
    Kubernetes ConfigMap / Secret keys, [-._a-zA-Z0-9]+;
  - extraEnv names must be printable ASCII other than '=' and white space
    (what Kubernetes 1.32+ accepts, without the space; non-ASCII letters such
    as U+0130 lower-case to ASCII in Spring Boot);
  - an ExternalSecret property or remoteSecretName must not contain a control
    character.
The chart's templates also quote every key and value they interpolate; a
vendoring chart must do the same.
*/}}
{{- define "fbx.validateKeyNames" -}}
{{- range $name, $value := .Values.config -}}
{{- if not (regexMatch "^[-._a-zA-Z0-9]+$" (toString $name)) -}}
{{- fail (printf "config key %q is not a ConfigMap key ([-._a-zA-Z0-9]+); a newline, quote or other character can inject keys the datasource/TLS guard never sees" $name) -}}
{{- end -}}
{{- end -}}
{{- range $env := .Values.extraEnv -}}
{{- $envName := toString (default "" ($env | default dict).name) -}}
{{- if not (regexMatch "^[\\x21-\\x3c\\x3e-\\x7e]+$" $envName) -}}
{{- fail (printf "extraEnv name %q must be printable ASCII other than '=' and white space (a non-ASCII letter can spell a refused name)" $envName) -}}
{{- end -}}
{{- end -}}
{{- $es := .Values.externalSecret | default dict -}}
{{- if $es.enabled -}}
{{- range $field := list "data" "extraData" -}}
{{- range $entry := (index $es $field | default list) -}}
{{- $e := $entry | default dict -}}
{{- $key := toString (default "" $e.secretKey) -}}
{{- if not (regexMatch "^[-._a-zA-Z0-9]+$" $key) -}}
{{- fail (printf "externalSecret.%s secretKey %q is not a Secret key ([-._a-zA-Z0-9]+); a newline, quote or other character can inject keys the datasource/TLS guard never sees" $field $key) -}}
{{- end -}}
{{- range $f := list "property" "remoteSecretName" -}}
{{- if regexMatch "[\\x00-\\x1f\\x7f]" (toString (index $e $f | default "")) -}}
{{- fail (printf "externalSecret.%s %s %q must not contain a control character (a newline can inject another key)" $field $f (toString (index $e $f))) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Environment sources: the guard sees config keys, extraEnv names and the
ExternalSecret data/extraData secretKeys. An envFrom ConfigMap or Secret, or
an ExternalSecret dataFrom (every key of a remote secret), would load names
it never sees, so values envFrom, extraEnvFrom and externalSecret.dataFrom
are refused (the chart renders only its own configMapRef and secretRef).
*/}}
{{- define "fbx.validateEnvSources" -}}
{{- range $k := list "envFrom" "extraEnvFrom" -}}
{{- if index $.Values $k -}}
{{- fail (printf "%s is not supported: an envFrom ConfigMap or Secret loads keys the datasource/TLS guard never sees; use config, extraEnv or externalSecret.data/extraData" $k) -}}
{{- end -}}
{{- end -}}
{{- if (.Values.externalSecret | default dict).dataFrom -}}
{{- fail "externalSecret.dataFrom is not supported: it materialises every key of a remote secret, which the datasource/TLS guard never sees; list the keys in externalSecret.data/extraData" -}}
{{- end -}}
{{- end -}}

{{/*
Database CA mount. While databaseCa.enabled the chart mounts the ConfigMap
<configMapName> (item <key>) at <mountPath> in every pod that reads the
database, and sslrootcert and DB_SSL_ROOT_CERT are <mountPath>/<key>; they
are pinned to the trust-manager Bundle the mesh repo publishes in every
service namespace:
  - mountPath must be /etc/fintechbankx/rds-ca: another path can put a
    values-chosen ConfigMap item where the service reads files (/app is the
    image WORKDIR and Spring Boot loads optional:file:./config/, so
    mountPath /app/config with key application.yml can activate the local
    profile or set fintechbankx.tls.enforce=false);
  - key must be global-bundle.pem;
  - configMapName is required and must be rds-ca-bundle: another
    ConfigMap replaces the database trust anchor, and an adapter that left
    the name out would let values choose it. A key a values file sets to
    null is deleted by Helm before any template runs, so it is refused as
    missing.
With databaseCa.enabled false nothing is mounted and nothing is checked here.
*/}}
{{- define "fbx.validateDatabaseCa" -}}
{{- $ca := .Values.databaseCa | default dict -}}
{{- if $ca.enabled -}}
{{- $mountPath := toString ($ca.mountPath | default "") -}}
{{- if ne $mountPath "/etc/fintechbankx/rds-ca" -}}
{{- fail (printf "databaseCa.mountPath must be /etc/fintechbankx/rds-ca (got %q): the CA bundle is mounted only there; another path can put a ConfigMap where the service reads files (/app is the image WORKDIR and Spring Boot loads ./config/application.yml)" $mountPath) -}}
{{- end -}}
{{- $key := toString ($ca.key | default "") -}}
{{- if ne $key "global-bundle.pem" -}}
{{- fail (printf "databaseCa.key must be global-bundle.pem (got %q), the key of the rds-ca-bundle ConfigMap the mesh repo's trust-manager Bundle publishes" $key) -}}
{{- end -}}
{{- $name := toString ($ca.configMapName | default "") -}}
{{- if eq $name "" -}}
{{- fail "databaseCa.configMapName is required while databaseCa.enabled and must be rds-ca-bundle: pass the ConfigMap name the chart mounts (an adapter that leaves it out lets values choose the database trust anchor; Helm deletes a key set to null)" -}}
{{- end -}}
{{- if ne $name "rds-ca-bundle" -}}
{{- fail (printf "databaseCa.configMapName must be rds-ca-bundle (got %q), the ConfigMap the mesh repo's trust-manager Bundle publishes in every service namespace; another ConfigMap replaces the database trust anchor" $name) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Every PostgreSQL JDBC URL the chart passes to the workload must verify the
server certificate and host name (sslmode=require encrypts but trusts any
certificate). The query string is parsed the way PgJDBC reads it (split after
the first '?', then on '&', key=value on the first '='), not searched for a
substring:
  - exactly one sslmode, equal to verify-full;
  - with databaseCa.enabled, exactly one sslrootcert, equal to
    <databaseCa.mountPath>/<databaseCa.key>, which fbx.validateDatabaseCa
    pins to /etc/fintechbankx/rds-ca/global-bundle.pem (at most one
    otherwise);
  - no sslfactory / sslfactoryarg (NonValidatingFactory), sslhostnameverifier,
    sslpasswordcallback or service (pg_service.conf can override the TLS settings);
  - parameter names are plain [A-Za-z0-9_.-] (no percent-encoding), no
    percent-encoded '=' or '&' anywhere in the query, TLS keys in lower case
    (the driver ignores SSLMODE and would fall back to sslmode=prefer), and no
    TLS key before the '?';
  - no '${' or '$(' anywhere (Spring resolves a ${...} placeholder and
    Kubernetes a $(VAR) reference after this check; either could append
    &sslmode=disable).
Checked values: every config value and every extraEnv value that starts with
jdbc:[<wrapper>:]postgresql: (case-insensitive). Every extraEnv value is also
refused when it contains '$(': Kubernetes expands $(VAR) in env[].value from
earlier env entries and envFrom keys, so the pod would get text the guard
never saw (valueFrom.fieldRef replaces the usual $(POD_IP)-style use).

Names (fbx.datasourceOverrideName; config keys, extraEnv names whether they use
value or valueFrom, and externalSecret.data / extraData secretKeys, which are
the env names the Secret materialises and whose values are never seen here):
  - (?i)^spring[._-]?(datasource|flyway|liquibase|r2dbc)[._-] (any Spring
    datasource, Flyway, Liquibase or R2DBC property, not only *URL), except
    SPRING_DATASOURCE_USERNAME and SPRING_DATASOURCE_PASSWORD;
  - (?i)^spring[._-]?data[._-]?mongodb([._-]|$): every spring.data.mongodb.*
    name (uri, host, port, ssl.enabled, ssl.bundle, replica-set-name,
    database, ...) can point the DocumentDB client at another server or
    switch its TLS off, except SPRING_DATA_MONGODB_USERNAME and
    SPRING_DATA_MONGODB_PASSWORD (any case), allowed where the datasource
    credentials are. The connection string comes from MONGODB_URI, a
    Secret-only name (fbx.validateSecretNames);
  - spring.application.json in any spelling ([._-] or none, any case);
  - any name containing jdbc[._-]?url, ssl[._-]?factory (also sslfactoryarg),
    ssl[._-]?host[._-]?name[._-]?verifier or ssl[._-]?password[._-]?callback
    (spring.datasource.hikari.jdbc-url, REPORTING_JDBCURL, PGJDBC_SSL_FACTORY);
  - any name containing ssl[._-]?root[._-]?cert or ssl[._-]?mode
    (DB_SSL_ROOT_CERT, PGSSLROOTCERT, OPF_DB_SSL_MODE): DB_SSL_ROOT_CERT is
    rendered by the chart from databaseCa, and an extraEnv entry of the same
    name comes later in the env list and replaces it (the customer, risk and
    compliance guards are active only while it is set); sslmode and
    sslrootcert belong in config.DB_URL;
  - DB_URL outside config, and any other spelling of it in config
    (fbx.isDbUrlName: dburl once lower-cased and stripped of everything but
    [a-z0-9], e.g. db.url, db-url, dbUrl; the config key DB_URL is the one
    allowed place). A non-empty config.DB_URL must be a
    jdbc:[<wrapper>:]postgresql: URL, so it always goes through the parse
    above;
  - (?i)^spring[._-]?config([._-]|$): every spring.config.* name by prefix
    (import, location, additional-location, name, activate.on-profile,
    on-not-found, and the indexed forms spring.config.import[0] /
    SPRING_CONFIG_IMPORT_0_). An imported file or config tree, another config
    file name in the image, or an activation condition can set
    spring.datasource.* where the chart never sees it. The chart renders no
    config import; a configtree, if a service ever needs one, must be rendered
    by the chart itself on the fixed mount
    optional:configtree:/etc/fintechbankx/config/ from a boolean value, never
    taken from a user-supplied value;
  - (?i)^spring[._-]?profiles([._-]|$): every spring.profiles.* name
    (active, include, default, group.*, ...). A profile switches on an
    application-<profile>.yml inside the image; spring.profiles.default
    activates one (e.g. local) when none is active, and spring.profiles.group.*
    can expand kafka-msk into local. The chart renders the only
    SPRING_PROFILES_ACTIVE (fbx.kafkaProfile, from kafka.runtime), so no
    user-set profile name is allowed;
  - (?i)^spring[._-]?ssl([._-]|$) and any name containing ssl[._-]?bundle:
    spring.ssl.bundle.pem.* / jks.* (SPRING_SSL_BUNDLE_*) can replace the
    trust anchor of a DocumentDB, PostgreSQL or Kafka client, and
    spring.data.mongodb.ssl.bundle, spring.kafka.ssl.bundle, ... can point a
    client at another bundle;
  - (?i)^fintechbankx[._-]?tls([._-]|$): fintechbankx.tls.enforce
    (FINTECHBANKX_TLS_ENFORCE) is the only switch that turns the service's
    startup TLS assertion off, and only the local profile and test resources
    set it; any other fintechbankx.tls.* key is refused with it;
  - Kafka client TLS, (?i)^spring[._-]?kafka[._-](<client>[._-])?(properties[._-])?ssl([._-]|$):
    spring.kafka.ssl.* and spring.kafka.<client>.ssl.* (<client> producer,
    consumer, admin, streams or any other one element: trust store, key
    store, PEM certificates, key password, TLS protocol) and
    spring.kafka[.<client>].properties.ssl.* (the raw client properties
    ssl.truststore.location, ssl.keystore.type, ...), whatever kafka.runtime
    is; they come from the kafka-msk / kafka-strimzi profile in the image.
    The one name under these prefixes that stays allowed is
    ssl.endpoint.identification.algorithm (its canonical form ends in
    ssl.endpoint.identification.algorithm), whose value fbx.validateKafkaTls
    checks; security.protocol is not under ssl and keeps its value check.
    Other spring.kafka[.<client>].properties.* names (sasl.* for MSK IAM)
    are not refused.
Each rule is checked against the name as given and against both of its
relaxed-binding readings: the environment reading (fbx.canonicalName: '_'
separates elements, Spring Boot skips every character other than [a-z0-9]
inside an element and reads foo[bar] as foo.bar, so spring.pro-files,
spring.pro:files and spring[profiles] bind like the plain name) and the
property reading (fbx.propertyName: only '.' and '[...]' separate elements and
'_' is skipped too, so spring.pro_files, spring.kafka.s_sl and
spring.data.mongo_db bind as spring.profiles, spring.kafka.ssl and
spring.data.mongodb). The helper prints the reason (non-empty means rejected).
JVM options (JAVA_TOOL_OPTIONS, JDK_JAVA_OPTIONS, _JAVA_OPTIONS and the chart's
javaToolOptions) can set -Dspring.datasource.url=..., -Djavax.net.ssl.*,
-Dspring.config.*, -Dspring.profiles.*, -Dspring.ssl.bundle.*,
-Dfintechbankx.tls.*, -Dspring.kafka.*, -Dspring.data.mongodb.*,
-DKAFKA_TLS_CA or -DMONGODB_URI (a system property resolves the services'
${KAFKA_TLS_CA} and ${MONGODB_URI} before the environment does),
-Djava.security.properties (a security properties file can replace the trust
manager algorithm or keystore type), -Djdk.tls.* or
-Djdk.internal.httpclient.disableHostnameVerification, or read more options
from a file: a value that mentions datasource, flyway, liquibase, r2dbc, jdbc,
ssl, application[._-]json, spring[._-]config, spring[._-]profiles,
fintechbankx[._-]tls, security[._-]protocol, endpoint[._-]identification,
kafka, mongo[._-]db, java[._-]security[._-]properties, jdk[._-]tls or
hostname[._-]verification
(also once every character other than [a-z0-9] is removed, keeping and then
dropping white space: -Dspring..config.import, -Dspring.[profiles].active,
-Dspring.pro_files.active, a quoted "-Dspring.pro files.active" and JSON
nested in -Dspring.application..json bind like the plain names), that
contains a character outside printable ASCII (Character.toLowerCase reads
U+0130 as 'i'), that contains '$(' or '${' (the options would be assembled
from a config key, a secret or other literals after this check), an option
that starts with '@' (argument file; also after a quote), -XX:VMOptionsFile
or -XX:Flags (case-insensitive) is rejected, and these names need a literal extraEnv
value (no valueFrom, not even next to an empty value) and may not come from
the ExternalSecret.
This closes the chart-side routes only; a profile or config file baked into
the image, and TLS on routes the chart does not see (a Kafka client or a
datasource built in code), are the service's own startup check (README,
"Service-side TLS assertion"). Kafka settings the chart does see are checked
by fbx.validateKafkaTls.
*/}}
{{/*
Canonical form of a property or env name for the name rules: Spring Boot's
relaxed binding skips every character other than [a-z0-9] inside a name
element and reads foo[bar] as foo.bar, so spring.pro-files.active,
spring.pro:files.active (an env name Kubernetes 1.32+ accepts),
fintech-bankx.tls.enforce or spring.kafka.properties[security.protocol] bind
like the plain names. Lower case, '[', ']' and '_' read as '.', every other
character outside [a-z0-9.] removed, repeated dots collapsed, leading and
trailing dots trimmed. Every name rule is checked against the name as given
and against this form.
*/}}
{{- define "fbx.canonicalName" -}}
{{- $c := regexReplaceAll "[\\[\\]_.]+" (lower (toString .)) "." -}}
{{- $c = regexReplaceAll "[^a-z0-9.]+" $c "" -}}
{{- $c = regexReplaceAll "[.]+" $c "." -}}
{{- trimAll "." $c -}}
{{- end -}}

{{/*
Property reading of a name, the second form every name rule is checked
against: Spring Boot 3.3.6 maps an environment variable through its default
property mapper as well as the environment one, and the default mapper splits
the name on '.' only and skips every character other than [a-z0-9] inside an
element, '_' included. So spring.kafka.s_sl.trust-store-type,
spring.data.mongo_db.host, spring.pro_files.active and fintech_bankx.tls.enforce
(env names and ConfigMap keys Kubernetes accepts) bind
spring.kafka.ssl.trust-store-type, spring.data.mongodb.host,
spring.profiles.active and fintechbankx.tls.enforce, which fbx.canonicalName
(s.sl, mongo.db, pro.files) does not show. Lower case, '[' and ']' read as
'.', every character outside [a-z0-9.] removed ('_' too), repeated dots
collapsed, leading and trailing dots trimmed.
*/}}
{{- define "fbx.propertyName" -}}
{{- $c := regexReplaceAll "[\\[\\]]+" (lower (toString .)) "." -}}
{{- $c = regexReplaceAll "[^a-z0-9.]+" $c "" -}}
{{- $c = regexReplaceAll "[.]+" $c "." -}}
{{- trimAll "." $c -}}
{{- end -}}

{{- define "fbx.datasourceOverrideName" -}}
{{- $n := toString . -}}
{{- if not (regexMatch "(?i)^SPRING_(DATASOURCE|DATA_MONGODB)_(USERNAME|PASSWORD)$" $n) -}}
{{- $reason := include "fbx.overrideNameReason" $n -}}
{{- if not $reason -}}
{{- $reason = include "fbx.overrideNameReason" (include "fbx.canonicalName" $n) -}}
{{- end -}}
{{- if not $reason -}}
{{- $reason = include "fbx.overrideNameReason" (include "fbx.propertyName" $n) -}}
{{- end -}}
{{- $reason -}}
{{- end -}}
{{- end -}}

{{- define "fbx.overrideNameReason" -}}
{{- $n := toString . -}}
{{- if regexMatch "(?i)^spring[._-]?(datasource|flyway|liquibase|r2dbc)[._-]|^spring[._-]?application[._-]?json$|jdbc[._-]?url|ssl[._-]?factory|ssl[._-]?host[._-]?name[._-]?verifier|ssl[._-]?password[._-]?callback" $n -}}
it can redirect or override the datasource past the sslmode=verify-full check; set the JDBC URL in config.DB_URL
{{- else if regexMatch "(?i)ssl[._-]?root[._-]?cert|ssl[._-]?mode" $n -}}
a TLS parameter name can replace the mounted CA or the verify-full mode, and DB_SSL_ROOT_CERT is rendered by the chart from databaseCa (an empty or other value would turn the services' startup TLS guard off); set sslmode and sslrootcert in config.DB_URL
{{- else if regexMatch "(?i)^spring[._-]?config([._-]|$)" $n -}}
a spring.config.* property (import, location, additional-location, name, activate.*, indexed forms) can load or activate a file or config tree that overrides the datasource past the sslmode=verify-full check; the chart renders no config import
{{- else if regexMatch "(?i)^spring[._-]?profiles([._-]|$)" $n -}}
a profile can activate an application-<profile> config in the image (e.g. local) whose datasource and TLS settings the chart cannot check; the chart renders the only profile, from kafka.runtime
{{- else if regexMatch "(?i)^spring[._-]?ssl([._-]|$)|ssl[._-]?bundle" $n -}}
an SSL bundle property can replace the trust anchor of the service's DocumentDB, PostgreSQL or Kafka client, or point the client at another bundle
{{- else if regexMatch "(?i)^fintechbankx[._-]?tls([._-]|$)" $n -}}
it can switch off the service's startup TLS assertion (fintechbankx.tls.enforce is for the local profile and tests only)
{{- else if and (regexMatch "(?i)^spring[._-]?kafka[._-](?:[a-z0-9]+[._-])?(?:properties[._-])?ssl(?:[._-]|$)" $n) (not (regexMatch "^spring\\.?kafka\\.(?:[a-z0-9]+\\.)?(?:properties\\.)?ssl\\.endpoint\\.?identification\\.?algorithm$" (include "fbx.canonicalName" $n))) -}}
a Kafka client TLS setting (spring.kafka[.<client>].ssl.*, spring.kafka[.<client>].properties.ssl.*) can replace the client's trust store, key store or certificates or change its TLS protocol; they come from the kafka-msk / kafka-strimzi profile in the image (the Strimzi client certificate, key and CA through KAFKA_TLS_CERT, KAFKA_TLS_KEY and KAFKA_TLS_CA from a Secret), and only ssl.endpoint.identification.algorithm may be set (https)
{{- else if regexMatch "(?i)^spring[._-]?data[._-]?mongodb([._-]|$)" $n -}}
a spring.data.mongodb.* property can point the DocumentDB client at another server or switch its TLS off; the connection string comes only from MONGODB_URI in a Secret, and only SPRING_DATA_MONGODB_USERNAME and SPRING_DATA_MONGODB_PASSWORD may be set
{{- end -}}
{{- end -}}

{{/*
The only Spring profile the chart renders: kafka.runtime selects the Kafka
auth profile of the Kafka repo's client guide (msk -> kafka-msk, Amazon MSK
IAM over SASL_SSL; strimzi -> kafka-strimzi, Strimzi mutual TLS over SSL).
"" renders no profile. The schema holds the enum; this repeats it for
--skip-schema-validation.
*/}}
{{- define "fbx.kafkaProfile" -}}
{{- $runtime := toString ((.Values.kafka | default dict).runtime | default "") -}}
{{- if eq $runtime "msk" -}}kafka-msk
{{- else if eq $runtime "strimzi" -}}kafka-strimzi
{{- else if ne $runtime "" -}}
{{- fail (printf "kafka.runtime must be \"\", msk or strimzi (got %q)" $runtime) -}}
{{- end -}}
{{- end -}}

{{/* DB_URL in any spelling: lower case with every character other than [a-z0-9] removed is dburl (DB_URL, db.url, db-url, dbUrl, db[url], "DB_URL "). */}}
{{- define "fbx.isDbUrlName" -}}
{{- if eq (regexReplaceAll "[^a-z0-9]" (lower (toString .)) "") "dburl" -}}true{{- end -}}
{{- end -}}

{{- define "fbx.isJvmOptionsName" -}}
{{- if regexMatch "(?i)^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS)$" (toString .) -}}true{{- end -}}
{{- end -}}

{{- define "fbx.validateJvmOptions" -}}
{{- $v := toString .value -}}
{{- if not (regexMatch "^[\\t\\n\\r\\x20-\\x7e]*$" $v) -}}
{{- fail (printf "%s must contain only printable ASCII (Spring Boot lower-cases a property name with Character.toLowerCase, so a non-ASCII letter such as U+0130 can spell a refused name)" .where) -}}
{{- end -}}
{{- if regexMatch "\\$[({]" $v -}}
{{- fail (printf "%s must not contain '$(' or '${' (Kubernetes expands $(VAR) from earlier env entries and envFrom keys, and the JVM options would then be read from a value the guard never sees)" .where) -}}
{{- end -}}
{{- $alt := regexReplaceAll "[^a-z0-9\\s]+" (lower $v) "" -}}
{{- $flat := regexReplaceAll "[^a-z0-9]+" (lower $v) "" -}}
{{- $rule := "(?i)datasource|flyway|liquibase|r2dbc|jdbc|ssl|application[._-]?json|spring[._-]?config|spring[._-]?profiles|fintechbankx[._-]?tls|security[._-]?protocol|endpoint[._-]?identification|kafka|mongo[._-]?db|java[._-]?security[._-]?properties|jdk[._-]?tls|hostname[._-]?verification|(^|[\\s\"'])@|-XX:(VMOptionsFile|Flags)" -}}
{{- if or (regexMatch $rule $v) (regexMatch $rule $alt) (regexMatch $rule $flat) -}}
{{- fail (printf "%s must not mention datasource, flyway, liquibase, r2dbc, jdbc, ssl, application.json, spring.config, spring.profiles, fintechbankx.tls, security.protocol, endpoint.identification, kafka, mongodb, java.security.properties, jdk.tls or hostname verification, nor read options from a file ('@' argument file, also quoted, -XX:VMOptionsFile, -XX:Flags) (JVM system properties would override the datasource past the sslmode=verify-full check, the trust store, the Kafka or DocumentDB client settings or the service's TLS assertion)" .where) -}}
{{- end -}}
{{- end -}}

{{- define "fbx.validateDatabaseTls" -}}
{{- $root := . -}}
{{- include "fbx.validateJvmOptions" (dict "where" "javaToolOptions" "value" (.Values.javaToolOptions | default "")) -}}
{{- range $name, $value := .Values.config -}}
{{- if and (ne $name "DB_URL") (include "fbx.isDbUrlName" $name) -}}
{{- fail (printf "config.%s is DB_URL in another spelling; use the key DB_URL, where the JDBC URL is checked" $name) -}}
{{- end -}}
{{- if and (eq $name "DB_URL") (ne (trim (toString $value)) "") (not (regexMatch "(?i)^jdbc:(?:[a-z0-9-]+:)*postgresql:" (trim (toString $value)))) -}}
{{- fail "config.DB_URL must be a jdbc:[<wrapper>:]postgresql: URL (or empty), so that it goes through the sslmode=verify-full check" -}}
{{- end -}}
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
{{- if include "fbx.isDbUrlName" $envName -}}
{{- fail (printf "extraEnv must not set %s (value or valueFrom); set the JDBC URL in config.DB_URL, where sslmode=verify-full is enforced" $envName) -}}
{{- end -}}
{{- with include "fbx.datasourceOverrideName" $envName -}}
{{- fail (printf "extraEnv must not set %s (value or valueFrom): %s" $envName .) -}}
{{- end -}}
{{- if and (hasKey $env "value") (contains "$(" (toString $env.value)) -}}
{{- fail (printf "extraEnv.%s must not contain '$(': Kubernetes expands $(VAR) from earlier env entries and envFrom keys after the guard has checked the text; use valueFrom or a literal" $envName) -}}
{{- end -}}
{{- if include "fbx.isJvmOptionsName" $envName -}}
{{- if or (not (hasKey $env "value")) (hasKey $env "valueFrom") -}}
{{- fail (printf "extraEnv %s must set a literal value (valueFrom cannot be checked)" $envName) -}}
{{- end -}}
{{- include "fbx.validateJvmOptions" (dict "where" (printf "extraEnv.%s" $envName) "value" $env.value) -}}
{{- end -}}
{{- if hasKey $env "value" -}}
{{- include "fbx.validateJdbcUrl" (dict "root" $root "where" (printf "extraEnv.%s" $envName) "url" (toString $env.value)) -}}
{{- end -}}
{{- end -}}
{{- $es := .Values.externalSecret | default dict -}}
{{- if $es.enabled -}}
{{- range $field := list "data" "extraData" -}}
{{- range $entry := (index $es $field | default list) -}}
{{- $key := toString (default "" $entry.secretKey) -}}
{{- if or (include "fbx.isDbUrlName" $key) (include "fbx.isJvmOptionsName" $key) -}}
{{- fail (printf "externalSecret.%s must not materialise %s; set the JDBC URL in config.DB_URL and JVM options in javaToolOptions, where they are checked (keep only the credentials in the secret)" $field $key) -}}
{{- end -}}
{{- with include "fbx.datasourceOverrideName" $key -}}
{{- fail (printf "externalSecret.%s must not materialise %s (keep only the credentials in the secret): %s" $field $key .) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Kafka client TLS on the routes the chart renders (the service's own startup
check covers clients built in code; README "Service-side TLS assertion"):
  - a config key or extraEnv name matching
    (?i)(^|[._-])security[._-]?protocol$ (KAFKA_SECURITY_PROTOCOL,
    SPRING_KAFKA_SECURITY_PROTOCOL, SPRING_KAFKA_PRODUCER_SECURITY_PROTOCOL,
    SPRING_KAFKA_PROPERTIES_SECURITY_PROTOCOL,
    spring.kafka.streams.security.protocol, ...) must hold SASL_SSL or SSL
    (case-insensitive, trimmed, as the Kafka client reads it); PLAINTEXT,
    SASL_PLAINTEXT and empty are rejected;
  - a name matching (?i)endpoint[._-]?identification[._-]?algorithm must hold
    https (empty turns off broker host name verification);
  - such a protocol name needs kafka.runtime msk or strimzi (with "" no
    auth profile is rendered); with msk every such protocol must be SASL_SSL
    (Amazon MSK IAM), with strimzi SSL (Strimzi mutual TLS);
  - these names need a literal extraEnv value (no valueFrom, not even next to
    an empty value) and may not come from the ExternalSecret.
Both name patterns are also checked against fbx.canonicalName and
fbx.propertyName (spring.kafka.secu-rity.protocol,
spring.kafka.security.pro_tocol, spring.kafka.properties[security.protocol]).
*/}}
{{- define "fbx.kafkaTlsName" -}}
{{- $n := toString . -}}
{{- $c := include "fbx.canonicalName" $n -}}
{{- $p := include "fbx.propertyName" $n -}}
{{- if or (regexMatch "(?i)(^|[._-])security[._-]?protocol$" $n) (regexMatch "(^|\\.)security\\.?protocol$" $c) (regexMatch "(^|\\.)security\\.?protocol$" $p) -}}protocol
{{- else if or (regexMatch "(?i)endpoint[._-]?identification[._-]?algorithm" $n) (regexMatch "endpoint\\.?identification\\.?algorithm" $c) (regexMatch "endpoint\\.?identification\\.?algorithm" $p) -}}endpoint
{{- end -}}
{{- end -}}

{{- define "fbx.validateKafkaTlsValue" -}}
{{- $v := trim (toString .value) -}}
{{- if eq .kind "protocol" -}}
{{- if not .runtime -}}
{{- fail (printf "%s is set but kafka.runtime is empty; set kafka.runtime to msk or strimzi so the chart renders the Kafka auth profile (kafka-msk or kafka-strimzi)" .where) -}}
{{- end -}}
{{- $p := upper $v -}}
{{- if not (has $p (list "SASL_SSL" "SSL")) -}}
{{- fail (printf "%s must be SASL_SSL or SSL (got %q); PLAINTEXT, SASL_PLAINTEXT and empty send Kafka traffic without TLS" .where $v) -}}
{{- end -}}
{{- if and (eq .runtime "msk") (ne $p "SASL_SSL") -}}
{{- fail (printf "%s must be SASL_SSL with kafka.runtime msk (Amazon MSK IAM; got %q)" .where $v) -}}
{{- end -}}
{{- if and (eq .runtime "strimzi") (ne $p "SSL") -}}
{{- fail (printf "%s must be SSL with kafka.runtime strimzi (Strimzi mutual TLS; got %q)" .where $v) -}}
{{- end -}}
{{- else if eq .kind "endpoint" -}}
{{- if ne (lower $v) "https" -}}
{{- fail (printf "%s must be https (got %q); any other or an empty value turns off Kafka broker host name verification" .where $v) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "fbx.validateKafkaTls" -}}
{{- $runtime := toString ((.Values.kafka | default dict).runtime | default "") -}}
{{- range $name, $value := .Values.config -}}
{{- with include "fbx.kafkaTlsName" $name -}}
{{- include "fbx.validateKafkaTlsValue" (dict "kind" . "where" (printf "config.%s" $name) "value" $value "runtime" $runtime) -}}
{{- end -}}
{{- end -}}
{{- range $env := .Values.extraEnv -}}
{{- $envName := toString (default "" $env.name) -}}
{{- with include "fbx.kafkaTlsName" $envName -}}
{{- if or (not (hasKey $env "value")) (hasKey $env "valueFrom") -}}
{{- fail (printf "extraEnv %s must set a literal value (valueFrom cannot be checked)" $envName) -}}
{{- end -}}
{{- include "fbx.validateKafkaTlsValue" (dict "kind" . "where" (printf "extraEnv.%s" $envName) "value" $env.value "runtime" $runtime) -}}
{{- end -}}
{{- end -}}
{{- $es := .Values.externalSecret | default dict -}}
{{- if $es.enabled -}}
{{- range $field := list "data" "extraData" -}}
{{- range $entry := (index $es $field | default list) -}}
{{- $key := toString (default "" $entry.secretKey) -}}
{{- if include "fbx.kafkaTlsName" $key -}}
{{- fail (printf "externalSecret.%s must not materialise %s; set it as a literal in config or extraEnv, where SASL_SSL/SSL and https are enforced" $field $key) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Secret-only names (fbx.validateSecretNames): env names a service reads as a
${NAME} placeholder that carry TLS material, so they reach the pod only from
a Secret, whatever kafka.runtime is:
  - KAFKA_TLS_CERT, KAFKA_TLS_KEY, KAFKA_TLS_CA: the PEM client certificate,
    key and cluster CA the kafka-strimzi profile reads
    (spring.kafka.ssl.key-store-certificate-chain: ${KAFKA_TLS_CERT}, ...);
    the Kafka repo's client guide (docs/guides/SERVICE_CLIENT_CONFIGURATION.md)
    maps them from the Secret kafka-client-tls with secretKeyRef. Under msk
    or "" no profile reads them; the same routes keep one values file valid
    for every runtime;
  - MONGODB_URI: the DocumentDB connection string (credentials and TLS
    options, tls=true) the open-finance services read as ${MONGODB_URI};
    their charts materialise it from their own ExternalSecret.
Allowed only as an extraEnv entry with valueFrom.secretKeyRef alone (no
value, not even an empty one, no configMapKeyRef, fieldRef or
resourceFieldRef) or as an externalSecret data/extraData secretKey, and only
in exactly these spellings: Spring Boot 3.3.6 resolves ${KAFKA_TLS_CA} and
${MONGODB_URI} from the env names KAFKA_TLS_CA and MONGODB_URI, not from
kafka.tls.ca, Kafka_Tls_Ca or mongodb.uri, so any other spelling
((?i)^kafka[._-]?tls([._-]|$) and (?i)^mongodb[._-]?uri$, also in canonical
form) is refused on every route, and every spelling is refused under config.
(These names are read only as ${NAME} placeholders, not bound through
relaxed binding, so fbx.propertyName adds nothing here.)
JVM options cannot set them (-DKAFKA_TLS_CA or -DMONGODB_URI would win over
the environment): fbx.validateJvmOptions refuses kafka and mongodb.
fbx.secretOnlyName prints "exact" for an allowed spelling, "other" for
another spelling of such a name, nothing otherwise; fbx.secretOnlyReason
prints the reason for the name's family.
*/}}
{{- define "fbx.secretOnlyName" -}}
{{- $n := toString . -}}
{{- if or (regexMatch "(?i)^kafka[._-]?tls([._-]|$)" $n) (regexMatch "^kafka\\.?tls(\\.|$)" (include "fbx.canonicalName" $n)) -}}
{{- if has $n (list "KAFKA_TLS_CERT" "KAFKA_TLS_KEY" "KAFKA_TLS_CA") -}}exact{{- else -}}other{{- end -}}
{{- else if or (regexMatch "(?i)^mongodb[._-]?uri$" $n) (regexMatch "^mongodb\\.?uri$" (include "fbx.canonicalName" $n)) -}}
{{- if eq $n "MONGODB_URI" -}}exact{{- else -}}other{{- end -}}
{{- end -}}
{{- end -}}

{{- define "fbx.secretOnlyReason" -}}
{{- if regexMatch "(?i)^mongodb" (include "fbx.canonicalName" .) -}}
MONGODB_URI is the DocumentDB connection string (credentials and TLS options) the services read as ${MONGODB_URI}; it comes only from a Secret, under exactly this name: an extraEnv valueFrom.secretKeyRef or an externalSecret data/extraData key
{{- else -}}
KAFKA_TLS_CERT, KAFKA_TLS_KEY and KAFKA_TLS_CA carry the Kafka client certificate, key and cluster CA the kafka-strimzi profile reads as ${KAFKA_TLS_*}; they come only from a Secret, under exactly these names: an extraEnv valueFrom.secretKeyRef (the Kafka repo's client guide maps them from Secret kafka-client-tls) or an externalSecret data/extraData key
{{- end -}}
{{- end -}}

{{- define "fbx.validateSecretNames" -}}
{{- range $name, $value := .Values.config -}}
{{- if include "fbx.secretOnlyName" $name -}}
{{- fail (printf "config.%s is not allowed: %s" $name (include "fbx.secretOnlyReason" $name)) -}}
{{- end -}}
{{- end -}}
{{- range $env := .Values.extraEnv -}}
{{- $envName := toString (default "" ($env | default dict).name) -}}
{{- $kind := include "fbx.secretOnlyName" $envName -}}
{{- $why := include "fbx.secretOnlyReason" $envName -}}
{{- if eq $kind "other" -}}
{{- fail (printf "extraEnv %s is not allowed: %s" $envName $why) -}}
{{- else if eq $kind "exact" -}}
{{- $from := $env.valueFrom -}}
{{- if or (hasKey $env "value") (not (kindIs "map" $from)) (ne (len ($from | default dict)) 1) (not (hasKey ($from | default dict) "secretKeyRef")) -}}
{{- fail (printf "extraEnv %s must come from valueFrom.secretKeyRef alone (no value, configMapKeyRef, fieldRef or resourceFieldRef): %s" $envName $why) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- $es := .Values.externalSecret | default dict -}}
{{- if $es.enabled -}}
{{- range $field := list "data" "extraData" -}}
{{- range $entry := (index $es $field | default list) -}}
{{- $key := toString (default "" ($entry | default dict).secretKey) -}}
{{- if eq (include "fbx.secretOnlyName" $key) "other" -}}
{{- fail (printf "externalSecret.%s %s is not allowed: %s" $field $key (include "fbx.secretOnlyReason" $key)) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "fbx.validateJdbcUrl" -}}
{{- $where := .where -}}
{{- $url := trim .url -}}
{{- if regexMatch "(?i)^jdbc:(?:[a-z0-9-]+:)*postgresql:" $url -}}
{{- $ca := .root.Values.databaseCa | default dict -}}
{{- $want := printf "%s/%s" (trimSuffix "/" (toString $ca.mountPath)) (toString $ca.key) -}}
{{- if regexMatch "\\$[({]" $url -}}
{{- fail (printf "%s must not contain '${' or '$(' (a Spring placeholder or a Kubernetes variable reference is resolved after this check and can add sslmode=disable)" $where) -}}
{{- end -}}
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

{{/*
Egress CIDR floor (networkPolicy.egressCidrs), on top of the schema pattern
(IPv4 /8-/32, IPv6 /32-/128). The README intent is "VPC or VPC endpoint
subnets", so:
  - IPv4: a prefix shorter than /16 only inside 10.0.0.0/8, 172.16.0.0/12,
    192.168.0.0/16 (RFC1918) or 100.64.0.0/10 (shared address space); any
    other range must be /16 or narrower (an AWS VPC CIDR block is /16 at most,
    so a VPC in public address space still fits);
  - IPv6: no range that overlaps the IPv4-mapped block ::ffff:0:0/96 (inside
    it, or containing it such as ::/80) or the NAT64 prefixes 64:ff9b::/96 and
    64:ff9b:1::/48, which would re-open IPv4 egress;
  - IPv6 width: a range broader than /48 only when it lies fully inside the
    unique local block fc00::/7 (the schema already stops at /32); public
    IPv6 must be /48 or narrower (an Amazon-provided VPC IPv6 block is /56,
    a subnet /64);
  - IPv6 text must parse (one '::' at most, 8 hextets, valid dotted tail).
Ranges are compared as bit strings: two prefixes overlap when their first
min(p, q) bits are equal.
*/}}
{{- define "fbx.validateEgressCidrs" -}}
{{- $hexBits := dict "0" "0000" "1" "0001" "2" "0010" "3" "0011" "4" "0100" "5" "0101" "6" "0110" "7" "0111" "8" "1000" "9" "1001" "a" "1010" "b" "1011" "c" "1100" "d" "1101" "e" "1110" "f" "1111" -}}
{{- $octet := "(?:25[0-5]|2[0-4][0-9]|1[0-9][0-9]|[1-9]?[0-9])" -}}
{{- $dotted := printf "^%s(?:\\.%s){3}$" $octet $octet -}}
{{- range $i, $entry := (.Values.networkPolicy.egressCidrs | default list) -}}
{{- $cidr := toString $entry.cidr -}}
{{- $where := printf "networkPolicy.egressCidrs[%d].cidr %s" $i $cidr -}}
{{- $parts := regexSplit "/" $cidr 2 -}}
{{- $addr := lower (index $parts 0) -}}
{{- $prefix := atoi (index (append $parts "0") 1) -}}
{{- if contains ":" $addr -}}
{{- /* IPv6: expand to 128 bits */ -}}
{{- if contains "." $addr -}}
{{- $v4 := regexFind "[^:]*$" $addr -}}
{{- if not (regexMatch $dotted $v4) -}}
{{- fail (printf "%s is not a valid IPv6 CIDR (bad dotted IPv4 tail)" $where) -}}
{{- end -}}
{{- $o := splitList "." $v4 -}}
{{- $hex := printf "%x:%x" (add (mul (atoi (index $o 0)) 256) (atoi (index $o 1))) (add (mul (atoi (index $o 2)) 256) (atoi (index $o 3))) -}}
{{- $addr = printf "%s%s" (trimSuffix $v4 $addr) $hex -}}
{{- end -}}
{{- $groups := list -}}
{{- $doubles := len (regexFindAll "::" $addr -1) -}}
{{- if gt $doubles 1 -}}
{{- fail (printf "%s is not a valid IPv6 CIDR (more than one '::')" $where) -}}
{{- else if eq $doubles 1 -}}
{{- $halves := regexSplit "::" $addr 2 -}}
{{- $left := list -}}{{- if index $halves 0 -}}{{- $left = splitList ":" (index $halves 0) -}}{{- end -}}
{{- $right := list -}}{{- if index $halves 1 -}}{{- $right = splitList ":" (index $halves 1) -}}{{- end -}}
{{- $n := add (len $left) (len $right) -}}
{{- if gt $n 7 -}}
{{- fail (printf "%s is not a valid IPv6 CIDR (too many hextets)" $where) -}}
{{- end -}}
{{- $groups = $left -}}
{{- range until (int (sub 8 $n)) -}}{{- $groups = append $groups "0" -}}{{- end -}}
{{- $groups = concat $groups $right -}}
{{- else -}}
{{- $groups = splitList ":" $addr -}}
{{- end -}}
{{- if ne (len $groups) 8 -}}
{{- fail (printf "%s is not a valid IPv6 CIDR (need 8 hextets)" $where) -}}
{{- end -}}
{{- $bits := "" -}}
{{- range $g := $groups -}}
{{- if not (regexMatch "^[0-9a-f]{1,4}$" $g) -}}
{{- fail (printf "%s is not a valid IPv6 CIDR (bad hextet %q)" $where $g) -}}
{{- end -}}
{{- range $c := splitList "" (printf "%s%s" (repeat (int (sub 4 (len $g))) "0") $g) -}}
{{- $bits = printf "%s%s" $bits (get $hexBits $c) -}}
{{- end -}}
{{- end -}}
{{- $mapped := printf "%s%s" (repeat 80 "0") (repeat 16 "1") -}}
{{- $m := min $prefix 96 -}}
{{- if eq (trunc (int $m) $bits) (trunc (int $m) $mapped) -}}
{{- fail (printf "%s is IPv4-mapped IPv6 or overlaps ::ffff:0:0/96; list the IPv4 range instead" $where) -}}
{{- end -}}
{{- /* NAT64: 64:ff9b::/96 (RFC 6052) and 64:ff9b:1::/48 (RFC 8215) translate to any IPv4 address */ -}}
{{- $nat64 := "00000000011001001111111110011011" -}}
{{- range $r := list (list (printf "%s%s" $nat64 (repeat 64 "0")) 96 "64:ff9b::/96") (list (printf "%s%s" $nat64 "0000000000000001") 48 "64:ff9b:1::/48") -}}
{{- $k := int (min $prefix (index $r 1)) -}}
{{- if eq (trunc $k $bits) (trunc $k (index $r 0)) -}}
{{- fail (printf "%s overlaps the NAT64 prefix %s, which reaches any IPv4 address; list the IPv4 range instead" $where (index $r 2)) -}}
{{- end -}}
{{- end -}}
{{- /* width floor: a public IPv6 range is /48 or narrower; only ULA (fc00::/7) may be wider */ -}}
{{- if and (lt $prefix 48) (not (and (ge $prefix 7) (eq (trunc 7 $bits) "1111110"))) -}}
{{- fail (printf "%s is a public IPv6 range broader than /48; only ranges inside the unique local block fc00::/7 may be wider" $where) -}}
{{- end -}}
{{- else -}}
{{- /* IPv4 (shape already checked by values.schema.json) */ -}}
{{- $bits := "" -}}
{{- range $o := splitList "." $addr -}}{{- $bits = printf "%s%08b" $bits (atoi $o) -}}{{- end -}}
{{- if lt $prefix 16 -}}
{{- $inside := false -}}
{{- range $r := list (list "00001010" 8) (list "101011000001" 12) (list "0110010001" 10) -}}
{{- $q := index $r 1 -}}
{{- if and (ge $prefix $q) (eq (trunc $q $bits) (index $r 0)) -}}{{- $inside = true -}}{{- end -}}
{{- end -}}
{{- if not $inside -}}
{{- fail (printf "%s is a public IPv4 range broader than /16; only RFC1918 (10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16) and 100.64.0.0/10 may be wider" $where) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
