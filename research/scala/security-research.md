# Security research: application auth, RBAC, Kafka cluster auth, audit, masking

**Date:** 2026-09-03

## Questions

1. How do the reference projects authenticate application users, keep sessions, handle CSRF, and
   tell the frontend which auth type is active?
2. What exactly is Kafbat's RBAC model and how is it evaluated and exposed to the frontend?
3. Which Kafka cluster authentication mechanisms do the references support and how are they
   configured (keystores, secrets)?
4. How do audit logging and data masking behave, rule by rule?
5. Which threats are specific to a "Kafka management UI" and how should KUI handle them?
6. What should KUI's identity service, gateway session model, gateway-to-service principal
   header, OIDC/LDAP libraries and pure RBAC core look like, and how does all-in-one mode differ?

## Method and sources

Local clones (paths are relative to `/tmp/kui-ref/`; line numbers refer to the files as cloned):

| Reference | Commit | Notes |
| --- | --- | --- |
| `kafbat/` (kafbat/kafka-ui) | `fa485c2bd45cac713cd994c62bc2d458abd3f328` (2026-09-03) | Spring Boot 3.5.16 (`gradle/libs.versions.toml:2`), WebFlux, Spring Security |
| `provectus/` (provectus/kafka-ui) | `83b5a60cc08501b570a0c4d0b4cdfceb1b88d6b7` (2024-04-08) | Archived ancestor of Kafbat |
| `consdata/` (consdata/kouncil) | `6e2fb85e6ceac813c39f762eecd2f4bce1b31faf` (2026-08-04) | Spring Boot MVC (servlet), archived |

Web (versions checked 2026-09-03):

- pac4j-oidc 6.5.4 (2026-07-21), Java 17+: https://mvnrepository.com/artifact/org.pac4j/pac4j-oidc, https://www.pac4j.org/docs/release-notes.html
- nimbus-jose-jwt 10.9.1 (2026-05-31): https://central.sonatype.com/artifact/com.nimbusds/nimbus-jose-jwt, roadmap https://connect2id.com/blog/nimbus-jose-jwt-roadmap-2026
- UnboundID LDAP SDK 7.0.5 (2026-06-12): https://central.sonatype.com/artifact/com.unboundid/unboundid-ldapsdk, https://github.com/pingidentity/ldapsdk
- ldaptive 2.5.1 (2026-01-22): https://www.ldaptive.org/changelog.html
- jwt-scala 11.x line, Scala 3, last release 2026-04-07: https://github.com/jwt-scala/jwt-scala/releases
- aws-msk-iam-auth 2.3.7 (2026-06-01): https://github.com/aws/aws-msk-iam-auth/releases
- GCP `managed-kafka-auth-login-handler`: https://github.com/googleapis/managedkafka, https://central.sonatype.com/artifact/com.google.cloud.hosted.kafka/managed-kafka-auth-login-handler
- Azure Event Hubs OAUTHBEARER: https://learn.microsoft.com/en-us/azure/event-hubs/azure-event-hubs-apache-kafka-overview, Kafbat issue https://github.com/kafbat/kafka-ui/issues/1696
- Kafka SASL mechanisms and OIDC login (KIP-768): https://kafka.apache.org/41/security/authentication-using-sasl/, https://cwiki.apache.org/confluence/pages/viewpage.action?pageId=186877575
- Tapir 1.13.x line (1.13.15, 2026-04-03; the KUI stack pins 1.13.31): https://github.com/softwaremill/tapir/releases

---

## 1. Application authentication in the references

### 1.1 Kafbat (and Provectus, which is identical in structure)

Auth type is selected by the single property `auth.type` with values `DISABLED | LOGIN_FORM |
OAUTH2 | LDAP`; each value activates one `@Configuration` class via `@ConditionalOnProperty`
(`kafbat/api/src/main/java/io/kafbat/ui/config/auth/DisabledAuthSecurityConfig.java:4`,
`BasicAuthSecurityConfig.java:4`, `OAuthSecurityConfig.java:3`, `LdapSecurityConfig.java:4`).
The default is `DISABLED` (`api/src/main/resources/application.yml:1-2`). The old
`auth.enabled` flag makes the app exit with code 1 (`DisabledAuthSecurityConfig.java`,
`configure()`: logs an error and calls `System.exit(1)`).

**Public path whitelist** (`AbstractAuthSecurityConfig.java:17` onward) — static assets,
`/metrics`, `/actuator/health/**`, `/actuator/info`, `/actuator/prometheus`, Swagger paths,
`/login`, `/logout`, `/oauth2/**`, `/api/config/authentication`, `/api/authorization`.
Everything else requires `authenticated()`. Note that `/api/authorization` is public but returns
only `rbacEnabled` when there is no principal (`AuthorizationController.java`, `switchIfEmpty`).

| Type | Mechanism | Key facts (path:line) |
| --- | --- | --- |
| `DISABLED` | `permitAll()` on every exchange, CSRF disabled | `DisabledAuthSecurityConfig.java:35` |
| `LOGIN_FORM` | Spring `formLogin` on `/login`; users come from Spring Boot's default in-memory user (`SPRING_SECURITY_USER_NAME/PASSWORD`, see `documentation/compose/auth-context.yaml:17-19`). Success handler returns an empty redirect (the SPA navigates itself). Logout is `GET /logout` → redirect to `/auth?logout`. CSRF disabled. | `BasicAuthSecurityConfig.java` (`formLogin`, `requiresLogout(GET /logout)`, `:38` csrf disable); `AbstractAuthSecurityConfig.java` (`LOGIN_URL`, `LOGOUT_URL`, `emptyRedirectSuccessHandler`) |
| `LDAP` | Same form login; `BindAuthenticator` (user DN pattern from `spring.ldap.base` or `FilterBasedLdapUserSearch` from `userFilterSearchBase/Filter`), admin bind (`adminUser/adminPassword`). Active Directory mode via `oauth2.ldap.activeDirectory=true` + `.domain` uses `ActiveDirectoryLdapAuthenticationProvider`; `ldaps://` URLs get a custom SSL socket factory. Group extraction only when RBAC is enabled: `NestedLdapAuthoritiesPopulator` subclass (LDAP) or `DefaultActiveDirectoryAuthoritiesPopulator` (AD). CSRF disabled. | `LdapProperties.java` (`spring.ldap.*`, `:22,:24` AD flags); `LdapSecurityConfig.java` (`authenticationProvider`, `ldapBindAuthentication`, `authoritiesExtractor`, `activeDirectoryProvider`, `:159` csrf) |
| `OAUTH2` | Spring `oauth2Login` with a `DelegatingReactiveAuthenticationManager` (OIDC manager + plain OAuth2 manager). Providers are a map `auth.oauth2.client.<id>.*` with fields `provider, clientId, clientSecret, clientAuthenticationMethod, clientName, redirectUri, authorizationGrantType, scope, issuerUri, authorizationUri, tokenUri, userInfoUri, jwkSetUri, userNameAttribute, customParams`. Google gets `?hd=<allowedDomain>` appended when `customParams.allowedDomain` is set. ID tokens are validated with `NimbusReactiveJwtDecoder.withJwkSetUri(...)` through a proxy-aware WebClient. Optional **resource-server** mode accepts bearer tokens (JWT via `jwkSetUri`, or opaque tokens via introspection with client id/secret) so API clients can call without a browser session. Logout: provider-specific `LogoutSuccessHandler` (Cognito builds `logoutUrl?client_id&logout_uri`) else OIDC RP-initiated logout. CSRF disabled. | `OAuthProperties.java`; `OAuthPropertiesConverter.java` (`applyGoogleTransformations`); `OAuthSecurityConfig.java:89` (JWK decoder), `:112-119` (resource server JWT / opaque), `:107` csrf; `logout/CognitoLogoutSuccessHandler.java`, `logout/OAuthLogoutSuccessHandler.java` |

**Session model.** Kafbat has no `spring-session` dependency and no session properties in
`application.yml`, so it uses WebFlux defaults: server-side `InMemoryWebSessionStore`, cookie
named `SESSION`, `HttpOnly`, `SameSite=Lax`, `Secure` only over HTTPS, 30-minute idle timeout,
no persistence across restarts and no sharing between replicas. Spring Security's reactive
`WebSessionServerSecurityContextRepository` rotates the session id when it saves a new security
context, which is the framework's session-fixation mitigation (medium confidence; not visible in
Kafbat code, it is framework behaviour). The frontend POSTs `/login` as
`application/x-www-form-urlencoded` (`frontend/src/lib/hooks/api/appConfig.ts:29`), detects
failure by the redirect URL containing `error` (`BasicSignIn.tsx:28`), and logs out via a plain
link to `${basePath}/logout` (`frontend/src/components/NavBar/UserInfo/UserInfo.tsx:22`).
Bearer tokens (resource-server mode) are stateless.

**CSRF.** Disabled in all four configurations (`:35`, `:38`, `:159`, `:107` above). Logout is a
`GET`. This means any site can trigger a mutating request against a Kafbat instance in a
browser that holds a `SESSION` cookie; `SameSite=Lax` is the only thing preventing cross-site
POSTs. This is a gap KUI must not copy.

**How the frontend learns the auth type.** `GET /api/config/authentication` (public) returns
`AppAuthenticationSettings { authType: DISABLED|OAUTH2|LOGIN_FORM|LDAP, oAuthProviders:
[{clientName, authorizationUri}] }` (`contract/src/main/resources/swagger/kafbat-ui-api.yaml:2422-2434,
2569-2593`; `service/ApplicationInfoService.java:108-133`). `oAuthProviders` lists only
authorization-code registrations, with `authorizationUri = /oauth2/authorization/<id>`. The
SPA's `AuthPage` calls `useAuthSettings()` and renders the basic form or one button per
provider (`frontend/src/components/AuthPage/AuthPage.tsx:9`, `SignIn/OAuthSignIn/OAuthSignIn.tsx:42`).

**Principal shape.** Every provider wraps the Spring principal in an `RbacUser { name(), groups() }`
(`config/auth/RbacUser.java`; `RbacOidcUser`, `RbacOAuth2User`, `RbacLdapUser`). `groups()` are
**the names of the Kafbat roles the user matched**, computed once at login (see §2.3), not raw
IdP groups. `AccessControlService.getUser()` (`service/rbac/AccessControlService.java:139`)
turns this into `AuthenticatedUser(principal, groups)`.

**Provectus differences.** Same class layout; Kafbat added `DefaultRole`, the OAuth2
resource-server mode, the AD authorities extractor, `CONNECTOR` and `CLIENT_QUOTAS` resources,
`ANALYSIS_VIEW/ANALYSIS_RUN` topic actions, action dependencies, and the Azure Entra callback
handler (`provectus/kafka-ui-api/.../model/rbac/permission/TopicAction.java:9-15` has seven
flat actions; no `provider/Provider` change).

### 1.2 Kouncil (consdata)

Selected by `kouncil.auth.active-provider: inmemory | ldap | ad | sso`
(`consdata/kouncil-backend/src/main/resources/kouncil.yaml:17-32`). Each provider has its own
`SecurityFilterChain` (`config/security/inmemory/InMemoryWebSecurityConfig.java:6`,
`ldap/LdapWebSecurityConfig.java:6`, `ad/ActiveDirectoryWebSecurityConfig.java:6`,
`sso/SSOWebSecurityConfig.java:6`).

- **inmemory:** four fixed users (`admin`, `editor`, `viewer`, `superuser`) with `{noop}`
  passwords, persisted as `password;group` text files; first login forces a password change
  (`inmemory/InMemoryUserManager.java`, `createUser`, `changeDefaultPassword`). Documented as
  "only for test purposes" (`docs/configuration/security/LOCAL_AUTHENTICATION.md`).
- **ldap / ad:** technical-user bind, `FilterBasedLdapUserSearch`, group lookup by
  `group-search-base/filter/role-attribute` (`ldap/LdapWebSecurityConfig.java:37-77`,
  `ldap/CustomLdapAuthoritiesPopulator.java`); AD via `ActiveDirectoryLdapAuthenticationProvider`
  (`ad/ActiveDirectoryWebSecurityConfig.java:45-53`).
- **sso:** Spring `oauth2Login` with standard `spring.security.oauth2.client.registration.*`;
  the list of enabled providers is `kouncil.auth.sso.supported.providers` exposed at
  `GET /api/sso-providers` (`sso/SSOProvidersController.java:10-21`). Groups: OIDC `groups`
  claim, or GitHub teams fetched via GraphQL (`sso/CustomOAuth2UserService.java:13-19`,
  `docs/configuration/security/GITHUB.md`, `OKTA.md`). The OAuth authorization request cache is
  a plain `HashMap` keyed by `state` (`sso/InMemoryAuthRepository.java`) — not multi-instance safe
  and never evicted.
- **Session/CSRF:** servlet `JSESSIONID`; login is `POST /api/login` with a JSON body that
  manually stores the security context in the session (`security/AuthService.java:7-17`);
  logout is `GET /api/logout`. CSRF is **enabled** with a double-submit cookie
  (`CookieCsrfTokenRepository.withHttpOnlyFalse()` + `SpaCsrfTokenRequestHandler`, the Spring
  SPA recipe: XOR-encoded token in cookie, plain token in `X-XSRF-TOKEN` header)
  (`config/security/SpaCsrfTokenRequestHandler.java`). CORS is `*` for origins, methods and
  headers in every chain (`sso/SSOWebSecurityConfig.java:19-27`).
- **Frontend discovery:** `GET /api/active-provider` returns the provider string
  (`security/AuthController.java:9-12`); `GET /api/user-roles` returns granted function names.

---

## 2. Kafbat RBAC model

### 2.1 Configuration model

```
rbac:
  roles[]:
    name: string                       # role name; becomes the user's "group"
    clusters[]: string                 # cluster names (case-insensitive match)
    subjects[]:
      provider: OAUTH_GOOGLE|OAUTH_GITHUB|OAUTH_COGNITO|OAUTH|LDAP|LDAP_AD
      type: user|group|domain|organization|team|role   # per provider, see 2.3
      value: string                    # exact (case-insensitive) or regex when isRegex
      isRegex: boolean
    permissions[]:
      resource: APPLICATIONCONFIG|CLUSTERCONFIG|TOPIC|CONSUMER|SCHEMA|CONNECT|CONNECTOR|KSQL|ACL|AUDIT|CLIENT_QUOTAS
      value: regex | absent            # resource-name pattern (java.util.regex, full match)
      actions[]: ALL | <resource-specific action names, case-insensitive>
  defaultRole:                         # optional; permissions only, applies to all clusters
    permissions[]: ...
```

Sources: `model/rbac/Role.java` (validation: clusters and subjects non-empty),
`model/rbac/Subject.java:17-18,28-33` (`isRegex`, `matches` = regex `String.matches` or
`equalsIgnoreCase`), `model/rbac/Permission.java` (`transform()` compiles `value` into a
`Pattern`, expands `ALL` to `resource.allActions()`, otherwise
`parseActionsWithDependantsUnnest`), `model/rbac/Resource.java` (`aliases`: `restart` →
`CONNECT.OPERATE`, `ConnectAction.java:26`), `model/rbac/DefaultRole.java`,
`config/auth/RoleBasedAccessControlProperties.java`.

RBAC is **enabled iff** at least one role or a `defaultRole` is configured
(`AccessControlService.java`, `init()`); otherwise every check returns `true`.

### 2.2 Resource × action matrix (complete, from `model/rbac/permission/*.java`)

Legend: `A → B` means granting A also grants B (`dependantActions`, unnested recursively by
`PermissibleAction.unnestAllDependants`). `alter` = counted as a write for audit `ALTER_ONLY`.

| Resource | Actions | Dependencies | `alter` actions | Named by |
| --- | --- | --- | --- | --- |
| `APPLICATIONCONFIG` | VIEW, EDIT | EDIT → VIEW | EDIT | none (global) |
| `CLUSTERCONFIG` | VIEW, EDIT | EDIT → VIEW | EDIT | cluster only |
| `TOPIC` | VIEW, CREATE, EDIT, DELETE, MESSAGES_READ, MESSAGES_PRODUCE, MESSAGES_DELETE, ANALYSIS_VIEW, ANALYSIS_RUN | all → VIEW; ANALYSIS_RUN → ANALYSIS_VIEW (`TopicAction.java:17`) | CREATE, EDIT, DELETE, MESSAGES_PRODUCE, MESSAGES_DELETE | topic name |
| `CONSUMER` | VIEW, DELETE, RESET_OFFSETS | all → VIEW | DELETE, RESET_OFFSETS | group id |
| `SCHEMA` | VIEW, CREATE, DELETE, EDIT, MODIFY_GLOBAL_COMPATIBILITY | CREATE/DELETE/EDIT → VIEW; MODIFY_GLOBAL_COMPATIBILITY has none | CREATE, DELETE, EDIT, MODIFY_GLOBAL_COMPATIBILITY | subject name; global-compat check has no name |
| `CONNECT` | VIEW, EDIT, CREATE, OPERATE, DELETE, RESET_OFFSETS (alias `restart` = OPERATE) | all → VIEW | CREATE, EDIT, DELETE, OPERATE, RESET_OFFSETS | connect cluster name |
| `CONNECTOR` | VIEW, EDIT, CREATE, OPERATE, DELETE, RESET_OFFSETS | each → same-named `ConnectAction` on the parent connect + CONNECTOR.VIEW | CREATE, EDIT, DELETE, OPERATE, RESET_OFFSETS | `"<connect>/<connector>"` |
| `KSQL` | EXECUTE | none | EXECUTE | none |
| `ACL` | VIEW, EDIT | EDIT → VIEW | EDIT | none |
| `AUDIT` | VIEW | none | none | none |
| `CLIENT_QUOTAS` | VIEW, EDIT | EDIT → VIEW | EDIT | none |

The public `Action` enum in the OpenAPI contract is the union of these plus `ALL` and `RESTART`
(`kafbat-ui-api.yaml:4138-4155`); `ResourceType` is the resource list (`:4157-4170`).

### 2.3 Subject matching per provider (role assignment at login)

Role assignment happens once, at login, inside the user-service / authorities-populator, and is
cached as `groups()` on the principal. Each extractor returns the **set of role names** whose
subjects match (`service/rbac/extractor/*`).

| Provider (`subject.provider`) | Subject `type` | Compared against |
| --- | --- | --- |
| `OAUTH_GITHUB` | `user` | `login` attribute |
|  | `organization` | `GET <userinfo-base>/orgs?per_page=100` with the access token, `login` of each org (`GithubAuthorityExtractor.java:118`) |
|  | `team` | `GET /teams?per_page=100`, formatted `org/slug` (`:165`) |
| `OAUTH_GOOGLE` | `user` | `email` |
|  | `domain` | `hd` claim (`GoogleAuthorityExtractor.java:20`) |
| `OAUTH_COGNITO` | `user` | principal name |
|  | `group` | list attribute `cognito:groups` or `customParams.roles-field` (`CognitoAuthorityExtractor.java:24-25`) |
| `OAUTH` (generic OIDC/OAuth2) | `user` | principal name (`userNameAttribute`) |
|  | `role` | attribute named by `customParams.roles-field` (list, set, or comma-separated string) (`OauthAuthorityExtractor.java:25`) |
| `LDAP` | `user` | bind username |
|  | `group` | nested group membership from `groupFilterSearchBase/Filter` (`RbacLdapAuthoritiesExtractor.java`) |
| `LDAP_AD` | `user` / `group` | username / AD `memberOf` groups (`RbacActiveDirectoryAuthoritiesExtractor.java`) |

Which extractor applies is decided by `provider.provider` or `customParams.type` equal to
`github|google|cognito|oauth` (`ProviderAuthorityExtractor.isApplicable`,
`AbstractProviderCondition.java:18-26`). A provider without a matching extractor yields a user
with no roles (falls to `defaultRole`, if any).

### 2.4 Evaluation per endpoint

Every controller method builds an `AccessContext` and pipes `validateAccess(context)` before the
operation and `audit(context, signal)` after it (`controller/AbstractController.java`,
example `controller/TopicsController.java:125-138`):

```java
var context = AccessContext.builder().cluster(clusterName).topicActions(topicName, DELETE)
    .operationName("deleteTopic").build();
return validateAccess(context).then(...).doOnEach(sig -> audit(context, sig));
```

Algorithm (`AccessControlService.java:119-154`, `model/rbac/AccessContext.java:75`):

1. If RBAC disabled → allow.
2. If the context names a cluster: the user must have at least one matched role whose
   `clusters` contains it (case-insensitive), **or** a `defaultRole` exists (`:147-154`).
3. Effective permissions = union of `permissions` of matched roles whose `clusters` contain the
   cluster; if that set is empty and `defaultRole` exists, use `defaultRole.permissions`
   (`:122-133`). Note: a user who matches roles for other clusters only still falls back to
   `defaultRole` on this cluster.
4. For every `ResourceAccess` in the context (all must pass): collect actions from permissions
   with the same `resource` where (a) the resource is unnamed and the permission has no `value`,
   or (b) both are present and `Pattern.matcher(name).matches()` (full match). Allowed iff the
   collected set contains all requested actions, else try the `fallback` access (used by
   `CONNECTOR` → parent `CONNECT` with the equivalent connect action).
5. Denied → `AccessDeniedException` → HTTP 403 and an audit record with `ACCESS_DENIED`.

List endpoints filter instead of failing: `filterViewableTopics`, `isConsumerGroupAccessible`,
`isSchemaAccessible`, `isConnectAccessible` (a connect is visible if the user has a `CONNECTOR`
VIEW permission whose `value` prefix before `/` matches the connect name, `:208-215` and
`connectorPermissionMatchesConnect`).

Read-only cluster flag: `kafka.clusters[].readOnly` (`config/ClustersProperties.java:99`) is
enforced by a WebFilter that rejects any non-GET/OPTIONS request whose path matches
`/api/clusters/{name}` with `ReadOnlyModeException` (HTTP 405), except `.../smartfilters` and
`.../analysis` (`config/ReadOnlyModeFilter.java:24-27,56`). It is independent of RBAC.

### 2.5 How the frontend gets its permission set

`GET /api/authorization` → `AuthenticationInfo { rbacEnabled, userInfo: { username,
permissions: [UserPermission { clusters[], resource, value?, actions[] }] } }`
(`kafbat-ui-api.yaml:2325,4094-4130`; `controller/AuthorizationController.java`). The
permissions are the matched roles' permissions with **dependants already expanded**
(`getParsedActions()`), or the `defaultRole` permissions expanded to all cluster names
(`:37`). The SPA builds `Map<cluster, Map<resource, [permission]>>` and `isPermitted()` returns
`true` when `rbacEnabled` is false, otherwise regex-matches `value` client-side
(`frontend/src/lib/permissions.ts:75-125`). The UI check is advisory; the server re-evaluates.

### 2.6 Kouncil's model (for contrast)

Function-level RBAC stored in a database: `UserGroup` (code, name) → set of
`SystemFunctionName` (44 functions such as `TOPIC_LIST`, `TOPIC_SEND_MESSAGE`,
`CONSUMER_GROUP_DELETE`, `POLICY_UPDATE`; `model/admin/SystemFunctionName.java`). IdP groups are
mapped to `UserGroup.code` (`security/UserRolesMapping.java`), functions become authorities, and
controllers use `@RolesAllowed(<function>)` (50 usages). There is **no per-resource pattern and
no per-cluster scoping**; the older `kouncil.authorization.role-admin/editor/viewer` scheme is
deprecated (`docs/configuration/security/AUTHORIZATION.md`). Useful idea for KUI: an admin UI
for editing role→function assignments; not useful: flat function list.

---

## 3. Kafka cluster authentication

### 3.1 Kafbat

Kafbat does not model Kafka auth; it passes `kafka.clusters[].properties` (flattened nested
maps, `ClustersProperties.java:311-330`) straight into the AdminClient/consumer/producer
configuration, plus `consumerProperties`/`producerProperties`. Mechanism support therefore
equals `kafka-clients` support plus the login modules on the classpath:

| Mechanism | Configuration (Kafbat keys) | Extra dependency |
| --- | --- | --- |
| SASL/PLAIN | `properties.security.protocol=SASL_PLAINTEXT|SASL_SSL`, `sasl.mechanism=PLAIN`, `sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=... password=...;` (`documentation/compose/ui-sasl.yaml:15-17`) | none |
| SASL/SCRAM-SHA-256/512 | same with `ScramLoginModule` | none |
| SASL/GSSAPI (Kerberos) | `sasl.mechanism=GSSAPI`, `sasl.kerberos.service.name`, JAAS `Krb5LoginModule` with keytab; needs `krb5.conf` in the container | none |
| SASL/OAUTHBEARER (OIDC, KIP-768) | `sasl.mechanism=OAUTHBEARER`, `sasl.login.callback.handler.class=org.apache.kafka.common.security.oauthbearer.OAuthBearerLoginCallbackHandler`, `sasl.oauthbearer.token.endpoint.url`, JAAS `OAuthBearerLoginModule required clientId=... clientSecret=... scope=...;` | none |
| AWS MSK IAM | `sasl.mechanism=AWS_MSK_IAM`, `sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required [awsProfileName=...];`, `sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler` (Kouncil generates exactly this: `clusters/converter/ClusterConfigConverter.java:106-112`) | `software.amazon.msk:aws-msk-iam-auth` 2.3.0 in Kafbat (`gradle/libs.versions.toml:6`), latest 2.3.7 |
| Azure Event Hubs (Entra ID) | `sasl.mechanism=OAUTHBEARER`, `sasl.login.callback.handler.class=io.kafbat.ui.config.auth.azure.AzureEntraLoginCallbackHandler`; token via `DefaultAzureCredential` (env, managed identity, CLI), audience `https://<namespace>/.default`, single bootstrap server only (`config/auth/azure/AzureEntraLoginCallbackHandler.java:29`, `buildEventHubsServerUri`) | `com.azure:azure-identity` 1.15.4 (`libs.versions.toml:7`) |
| GCP Managed Service for Apache Kafka | not in Kafbat. Standard recipe: `SASL_SSL`, `OAUTHBEARER`, `sasl.login.callback.handler.class=com.google.cloud.hosted.kafka.auth.GcpLoginCallbackHandler`, JAAS `OAuthBearerLoginModule required;`, credentials via Application Default Credentials | `com.google.cloud.hosted.kafka:managed-kafka-auth-login-handler` |
| SSL (server auth) | `kafka.clusters[].ssl.truststoreLocation/truststorePassword/verify` → `ssl.truststore.location/password`; `verify=false` sets `ssl.endpoint.identification.algorithm=""` (`util/KafkaClientSslPropertiesUtil.java:20`; `ClustersProperties.TruststoreConfig`) | none |
| mTLS (client cert) | keystore only through raw `properties.ssl.keystore.location/password/key.password`; no typed field for Kafka itself (typed `KeystoreConfig` exists only for Schema Registry, ksqlDB, Connect: `ClustersProperties.java:82,87,156-163`) | none |

Related HTTP services: Schema Registry basic auth or OAuth2 client-credentials
(`SchemaRegistryAuth`, `OauthConfig { tokenUrl, clientId, clientSecret, scopes, tokenCacheEnabled,
tokenRefreshBuffer, maxRetries }`, `ClustersProperties.java:165-183`), Kafka Connect basic auth +
keystore (`ConnectCluster`), ksqlDB basic auth + keystore, JMX/Prometheus metrics with
username/password/keystore (`MetricsConfig`), Prometheus storage URL + push-gateway credentials
(`PrometheusStorage`, `:138-146`).

**Secrets in config.** Passwords are excluded from `toString` via Lombok `@ToString(exclude=...)`
(`ClustersProperties.java:117,137,151,165,186,196,214`) but nothing else: `properties` is a
free-form map so `sasl.jaas.config` (which embeds passwords) is **not** redacted, and
`GET /api/config` returns the whole `PropertiesStructure` to anyone with
`APPLICATIONCONFIG.VIEW` (`controller/ApplicationConfigController.java:59-70`; no redaction in
`util/DynamicConfigOperations.java`). The config wizard uploads keystore files to disk and
references them by path.

### 3.2 Kouncil

Typed per-cluster model stored in a database: `authenticationMethod: NONE|SASL|SSL|AWS_MSK`,
`securityProtocol: SASL_PLAINTEXT|SASL_SSL|SSL`, `saslMechanism: PLAIN|AWS_MSK_IAM|SCRAM_SHA_256|
SCRAM_SHA_512`, truststore/keystore locations and passwords, key password, username/password,
`awsProfileName` (`model/cluster/ClusterSecurityConfig.java`, `ClusterSASLMechanism.java`,
`ClusterSecurityProtocol.java`). Passwords are stored in plaintext columns. The converter builds
the JAAS string by `String.format` (`ClusterConfigConverter.java:104-115`) — a `"` in a
password breaks or injects into the JAAS config.

---

## 4. Audit logging and data masking (Kafbat)

### 4.1 Audit

Config per cluster (`ClustersProperties.AuditProperties`, `:239-253`): `topic` (default
`__kui-audit-log`, `AuditService.java:47`), `auditTopicsPartitions` (default 1),
`topicAuditEnabled`, `consoleAuditEnabled`, `level: ALL|ALTER_ONLY` (default `ALTER_ONLY`),
`auditTopicProperties`, `requireAuditTopic`.

Behaviour (`service/audit/AuditService.java`, `AuditWriter.java`):

- A writer is created per cluster at startup only if topic or console audit is enabled. Topic
  mode creates the topic if missing with `retention.ms=90d, cleanup.policy=delete`
  (`AuditService.java:50`); on failure it degrades to console-only, or fails startup when
  `requireAuditTopic=true`.
- `audit(ctx, signal)` is called from `doOnEach` on every controller pipeline; it emits on
  `onComplete` (success) or `onError` (with the error classified as `ACCESS_DENIED |
  VALIDATION_ERROR | EXECUTION_ERROR | UNRECOGNIZED_ERROR`).
- `ALTER_ONLY` drops records whose accessed resources contain no `alter` action
  (`AuditWriter.java:43`); the alter set is the one in §2.2. `ALL` logs reads too (including
  every message poll).
- Cluster-less operations (application config) always go to the `audit` SLF4J logger.
- Record JSON (`AuditRecord.java:16`): `{ timestamp (ISO instant), username, clusterName,
  resources: [{ type, id, alter, accessType[] }], operation, operationParams, result: {
  success, error? } }`. Producer uses gzip, `null` key; errors sending are logged and dropped.
- The user is taken from the Reactor context (`UserDetails` or `AuthenticatedPrincipal` name,
  else `"Unknown"`).
- `isAuditTopic()` lets the message service refuse to browse/delete the audit topic of the same
  cluster.

### 4.2 Masking

Config per cluster (`ClustersProperties.Masking`, `:221-236`): `type: REMOVE|MASK|REPLACE`,
`fields[]` **or** `fieldsNamePattern` (both → validation error; neither → all fields),
`maskingCharsReplacement` (4 strings for MASK), `replacement` (REPLACE, default
`***DATA_MASKED***`), `topicKeysPattern`, `topicValuesPattern` (at least one required).

Behaviour (`service/masking/DataMasking.java`, `policies/*.java`):

- Applied in `DeserializationService` to the already-deserialized key and value strings
  (`service/DeserializationService.java:118`), i.e. after serde, before the DTO leaves the
  server; masking is not applied on produce.
- For each message, masks whose `topicKeysPattern` (for keys) / `topicValuesPattern` (for values)
  fully match the topic name are selected. If the string parses as a JSON object/array, **all**
  matching policies are applied in order to the JSON tree; otherwise only the **first** policy's
  `applyToString` is used (`DataMasking.java:75-97`).
- `REMOVE`: drops selected fields at any depth; for non-JSON returns the literal string `"null"`
  (`Remove.java:18`).
- `REPLACE`: replaces selected fields' values (recursively, keeping object/array shape, every
  leaf becomes the replacement string); non-JSON → the replacement string.
- `MASK`: per character class: uppercase → `X`, lowercase → `x`, digit → `n`, other → `-`,
  whitespace preserved (`Mask.java:15`); applied to selected fields recursively; non-JSON → whole
  string masked.
- Field selection is by exact name in `fields` or regex full match of `fieldsNamePattern`, at
  any nesting depth; once a field is selected its whole subtree is masked.

Kouncil's masking is a database-managed policy with dotted field paths and masking types
`ALL | FIRST_5 | LAST_5`, applied only to valid JSON (`datamasking/PolicyApplier.java`).
Nothing there is preferable to Kafbat's rule model.

---

## 5. Threats specific to this kind of application

| Threat | Where it shows up in the references | KUI requirement |
| --- | --- | --- |
| **SSRF via configured URLs** (Schema Registry, Connect, ksqlDB, Prometheus storage, JMX, OAuth `tokenUrl`, LDAP URL) | Any admin with `APPLICATIONCONFIG.EDIT` can `PUT /api/config` or `POST /api/config/validate` with arbitrary URLs and the server connects to them and returns error text (`ApplicationConfigController.java:107-133`). Prometheus proxy runs server-side PromQL templates only — user input goes through `PromQueryTemplate` parameters and a grammar check, so it is not a raw query proxy (`service/graphs/PromQueryTemplate.java`). `configureNoSsl()` uses `InsecureTrustManagerFactory` (`util/WebClientConfigurator.java:117`). | Treat every outbound URL as config-time input: parse with a strict URL type, allow only `http/https`, optional operator allow-list of hosts/CIDRs, deny link-local/metadata ranges (169.254.0.0/16, `fd00::/8` metadata endpoints) by default, never follow redirects to a different host, never echo upstream response bodies into error messages. Config wizard writes require `APPLICATIONCONFIG.EDIT` **and** audit; consider a separate `config.remote-validation.enabled` flag. |
| **Secret leakage through config endpoints/logs** | `GET /api/config` returns raw properties including `sasl.jaas.config`; `toString` exclusions are ad hoc; validation errors may include upstream messages. | `Secret[A]` opaque type whose `toString`, Circe encoder and log encoder emit `***`; the config read endpoint uses a `Redacted` view derived from the same model; JAAS strings are generated from typed fields, never accepted verbatim, and are built with proper quoting/escaping. Secret values may be resolved from `file://` or env references. |
| **Header injection / spoofed principal** | Kafbat is a monolith so no internal principal header exists. A gateway that forwards `X-User` style headers can be spoofed if a service is reachable directly. | Signed principal header (§6.3); services reject requests lacking a valid signature, strip any incoming `X-KUI-*` header at the gateway edge, and bind the signature to method+path+body hash so a captured header cannot be replayed on another endpoint. Log/trace context uses only the verified principal. |
| **CSRF** | Disabled everywhere in Kafbat; logout is GET. | Session cookie `SameSite=Lax` + `HttpOnly` + `Secure`; every state-changing request must carry a header the browser cannot set cross-site (`X-KUI-CSRF` double-submit token, or simply require `Content-Type: application/json` plus `Origin`/`Sec-Fetch-Site` validation). Logout is `POST`. |
| **Session fixation / hijack** | Relies on Spring's rotation on login; in-memory session store; 30-min idle timeout. Kouncil's OAuth state map never evicts. | Rotate the session id on login and on privilege change; store server-side session state keyed by an opaque random id (≥ 128 bits) with idle and absolute timeouts; OAuth `state`/`nonce`/PKCE verifier stored in a short-lived, single-use, server-side entry. |
| **Open redirect on login/logout** | `redirect_uri`/logout URL are built from config, not request input (safe). | Keep it that way; `returnTo` after login must be a same-origin relative path. |
| **Regex DoS** | RBAC `value` patterns and subject regexes are compiled from config; masking patterns run per message. | Compile once at startup; reject nested quantifiers with a linter or apply a match timeout; patterns come only from operator config, never from users. |
| **Privilege escalation through role naming** | A role is applied to a user when `user.groups()` contains the role **name**, and `groups()` is computed from subjects, so an IdP group literally named like a role is not enough — only subjects grant roles (safe). | Keep roles as the only bridge; never treat raw IdP group names as role names. |
| **Audit topic tampering** | Audit topic is a normal topic; `isAuditTopic` blocks browsing/deleting via the UI on that cluster only. | Same guard plus `TOPIC` deny-rule for the audit topic in the default policy; recommend broker-side ACLs in docs. |
| **Read-only bypass** | Enforced by path regex in a filter; a new mutating endpoint with an unconventional path (or a KSQL statement) bypasses it. | Enforce read-only per **operation** in the domain layer (every command carries a `Mutation` marker) rather than by URL. |
| **Wildcard CORS** | Kouncil allows `*` origins/headers/methods with cookies. | Gateway serves the SPA from the same origin; CORS off by default, explicit origin list if enabled. |

---

## 6. Recommendation for KUI

### 6.1 `kui-identity-service` design

Bounded context: application identity. It owns: authentication adapters, session store, role
configuration, permission evaluation API, audit sink. Modules follow KUI's per-service layout:

```
domain/         Principal, Session, Role, Subject, Permission, Action ADTs (from kui-security-core)
application/    LoginService (form/ldap), OidcLoginService, SessionService, PermissionQueryService, AuditService
ports/          IdentityProviderPort (verifyCredentials, groups), OidcProviderPort, SessionStorePort, AuditSinkPort, ClockPort
infrastructure/ LdapIdentityProvider (UnboundID), InMemory/StaticUsersProvider, OidcClient (nimbus), InMemorySessionStore, KafkaAuditSink, ConsoleAuditSink
api/            Tapir endpoints: /identity/login, /identity/logout, /identity/me, /identity/oidc/{provider}/start|callback, /identity/permissions, /identity/audit
```

Config (`kui.auth`, typed, validated with `Validated`):

```
kui.auth:
  type: disabled | form | oidc | ldap          # exactly one primary type, as in Kafbat
  form.users[]: { username, passwordHash (bcrypt/argon2), groups[] }   # no plaintext; a CLI hashes passwords
  ldap: { urls[], bindDn, bindPassword: Secret, userSearchBase, userSearchFilter, userDnPattern?, groupSearchBase, groupSearchFilter, groupRoleAttribute, activeDirectory: { domain }?, tls: { truststore?, verify } }
  oidc.providers[<id>]: { kind: generic|github|gitlab|google|cognito|azure|okta, clientId, clientSecret: Secret, issuerUri?, authorizationUri?, tokenUri?, userInfoUri?, jwkSetUri?, scopes[], userNameClaim, groupsClaim?, pkce: true, custom: { allowedDomain?, logoutUrl?, rolesField? } }
  bearer: { jwkSetUri?, introspection?: { uri, clientId, clientSecret } }   # optional API tokens, like Kafbat's resource-server mode
  session: { idleTimeout: 30m, absoluteTimeout: 12h, cookieName: kui_session, sameSite: Lax, secure: auto }
kui.rbac:
  roles[]: { name, clusters[], subjects[]{ provider, type, value, isRegex }, permissions[]{ resource, value?, actions[] } }
  defaultRole?: { permissions[] }
```

The subject/provider/action vocabulary is kept **identical to Kafbat** (§2.1–2.3) so the
Kafbat→KUI migration tool is a key rename. GitLab is added as `kind: gitlab` (groups
from `/api/v4/groups?min_access_level=10`), following the GitHub extractor pattern.

Group resolution stays a login-time computation producing `Set[RoleName]` exactly as Kafbat
does; the result is stored in the session so RBAC evaluation never calls the IdP.

### 6.2 Gateway session model

- **Browser clients:** opaque session id in an `HttpOnly; Secure; SameSite=Lax; Path=/` cookie
  (`kui_session`), 32 random bytes base64url. Session state (principal, role names, expiry, CSRF
  secret, OIDC tokens if needed for RP-initiated logout) lives in the identity service's
  `SessionStorePort`; the gateway caches `sessionId → Principal` for a short TTL (30 s) and
  revalidates on miss. Single-replica default is in-memory; a Kafka-compacted-topic or Redis
  store is an adapter behind the port (out of scope for M1).
- **API clients:** `Authorization: Bearer <jwt>` validated against `kui.auth.bearer.jwkSetUri`
  (nimbus `JWTProcessor` with cached JWKS) or introspection; roles resolved via the same subject
  matcher on claims. Stateless, no cookie, no CSRF requirement.
- **Login flows live at the gateway**: `POST /api/v1/auth/login` (JSON body,
  form/LDAP), `GET /api/v1/auth/oidc/{id}/start` → 302 to the provider with `state`, `nonce`,
  PKCE `code_challenge` stored server-side (single use, 5-minute TTL), `GET /api/v1/auth/oidc/{id}/callback`
  → code exchange, ID token validation (issuer, audience, nonce, `exp`, signature via JWKS),
  userinfo fetch when needed, role resolution, **new** session id issued (fixation defence),
  302 to `/`. `POST /api/v1/auth/logout` invalidates the session and, for OIDC, redirects to
  `end_session_endpoint` (or Cognito's `logoutUrl`) with `post_logout_redirect_uri`.
- **CSRF:** every non-GET request with a cookie session must carry `X-KUI-CSRF` equal to the
  session's CSRF secret (available to the SPA from `GET /api/v1/auth/me`); additionally the
  gateway rejects cookie-authenticated mutations whose `Sec-Fetch-Site` is `cross-site`.
- **Discovery for the frontend:** `GET /api/v1/auth/settings` (public) →
  `{ authType, providers: [{ id, displayName, startUrl }] }`; `GET /api/v1/auth/me` →
  `{ username, rbacEnabled, permissions[], csrfToken }` with permissions pre-expanded like
  Kafbat's `/api/authorization`.

### 6.3 Signed principal header between gateway and services

Header `X-KUI-Principal`, value = compact JWS (RFC 7515), built and verified by
`kui-security-core` with no framework dependency:

- **Algorithm:** `HS256` (HMAC-SHA-256) with a shared 256-bit key from
  `kui.gateway.principalKey` (`Secret`), because gateway and services are one trust domain and
  symmetric verification is cheap. Optional upgrade to `EdDSA` (Ed25519) when services must not
  be able to mint headers (multi-tenant deployments); the codec is parameterised by a
  `SignerVerifier` so the switch is config-only.
- **Claims:** `sub` (username), `roles` (role names), `iss=kui-gateway`, `aud=<service id>`,
  `iat`, `exp = iat + 60s`, `jti` (random), `sid` (session id hash, for audit correlation),
  `req` = SHA-256 of `METHOD\nPATH\nsha256(body)` binding the token to the exact call. Services
  verify signature, `aud`, `exp` with 5 s skew, and `req`; the `jti` is not tracked (short
  expiry + request binding make replay useless).
- **Rotation:** config accepts `principalKeys: [{ kid, key, notBefore }]`; the gateway signs
  with the newest key whose `notBefore` has passed and services accept any listed key by `kid`.
  Rolling restart order: add key to services, then gateway, then remove the old key.
- **Services** expose Tapir endpoints with a `securityIn(header[String]("X-KUI-Principal"))`
  input whose security logic decodes into `Principal`; they re-run the pure RBAC check for the
  operation (defense in depth).

### 6.4 Library choices (Scala 3, Cats Effect 3, Tapir 1.13.x)

| Concern | Options considered | Recommendation |
| --- | --- | --- |
| OIDC/OAuth2 relying party | **pac4j 6.5.4** (full-featured, Java, `pac4j-oidc` depends on nimbus `oauth2-oidc-sdk`; needs a web-framework adapter — none exists for Tapir/Netty, so KUI would write a `WebContext`/`SessionStore` bridge and pac4j's model is servlet-shaped and mutable). **nimbus-jose-jwt 10.9.1 + `oauth2-oidc-sdk`** (same author, pure Java, immutable request/response types, no framework coupling, used by Spring Security itself). **jwt-scala 11.x** (Scala 3, JWT only, no OIDC). | **nimbus-jose-jwt + nimbus `oauth2-oidc-sdk`** wrapped behind `OidcProviderPort[F]`: discovery document fetch, authorization URL builder (with PKCE + nonce), code exchange, ID token validation (`IDTokenValidator`), userinfo. HTTP calls go through sttp so proxies/TLS follow KUI config. pac4j rejected: adapter cost equals hand-rolling the small OIDC surface KUI needs, and it drags in mutable session abstractions. jwt-scala rejected: no JWKS/OIDC support. Provider quirks (GitHub orgs/teams, GitLab groups, Google `hd`, Cognito `cognito:groups`, Azure `groups` overage) are small `GroupResolver` adapters. |
| JWS for the principal header | nimbus-jose-jwt (already present) vs. hand-written HMAC | nimbus `JWSObject`/`MACSigner`/`MACVerifier`; keep the claim set as a Circe-encoded case class, not `JWTClaimsSet`, so `kui-security-core` stays framework-free. |
| LDAP | **UnboundID LDAP SDK 7.0.5** (mature, thread-safe pools, StartTLS/LDAPS, AD-friendly, Apache/LGPL/GPL tri-licence — Apache 2.0 is available), **ldaptive 2.5.1** (own protocol implementation on Netty, Apache 2.0, more moving parts), Apache Directory API (heavier). | **UnboundID** behind `IdentityProviderPort`: `LDAPConnectionPool` for the bind account, `bind` with the user's DN for credential check, `search` for groups (with nested-group option via `memberOf`/`LDAP_MATCHING_RULE_IN_CHAIN` for AD). Wrap in `Sync[F].blocking`. UnboundID is already the KUI stack's pick. |
| Password hashing (form users) | bcrypt via `at.favre.lib:bcrypt`, or Argon2 via `de.mkammerer:argon2-jvm` | bcrypt (`at.favre.lib:bcrypt`) — pure Java, no native lib; a `kui hash-password` CLI writes the hash into config. |
| Kafka auth callback handlers | `aws-msk-iam-auth` 2.3.7, `azure-identity` (current), `managed-kafka-auth-login-handler` (GCP) | Optional runtime modules in `kui-kafka-auth`; typed `security` config renders `sasl.*` properties and JAAS strings with escaping (§3). |

### 6.5 RBAC evaluation as pure functions in `kui-security-core`

```scala
enum Resource { case ApplicationConfig, ClusterConfig, Topic, ConsumerGroup, Schema, Connect, Connector, Ksql, Acl, Audit, ClientQuotas }
sealed trait Action { def resource: Resource; def implies: Set[Action]; def isAlter: Boolean }
final case class Permission(resource: Resource, value: Option[CompiledPattern], actions: Set[Action])   // actions already expanded
final case class Role(name: RoleName, clusters: Set[ClusterId], subjects: List[Subject], permissions: List[Permission])
final case class Principal(name: String, roles: Set[RoleName], kind: PrincipalKind)
final case class ResourceAccess(resource: Resource, name: Option[String], actions: Set[Action], fallback: Option[ResourceAccess])
final case class AccessRequest(cluster: Option[ClusterId], resources: List[ResourceAccess], operation: OperationName)

object Rbac {
  def resolveRoles(subjectsByProvider: Map[Provider, IdentityAttributes], roles: List[Role]): Set[RoleName]    // login time
  def effectivePermissions(policy: RbacPolicy, principal: Principal, cluster: Option[ClusterId]): List[Permission]
  def decide(policy: RbacPolicy, principal: Principal, req: AccessRequest): Decision   // Allowed | Denied(reason)
  def filterVisible[A](policy, principal, cluster, resource, name: A => String): List[A] => List[A]
}
```

Rules encoded as laws with ScalaCheck: `decide` is monotone in permissions; `implies` closure is
idempotent; `ALL` equals the full action set; `defaultRole` applies only when no role matches;
cluster gate precedes resource gate; `Connector` falls back to `Connect`. `RbacPolicy` is built
once from config (patterns compiled, actions expanded, RBAC-enabled flag = roles or default role
present) and is immutable. The same module compiles to Scala.js so the shell can run the exact
`decide` function on the pre-expanded permission list it receives from `/auth/me` — no
divergent TypeScript-style re-implementation.

Read-only enforcement: `decide` also receives `ClusterFlags(readOnly)` and denies any request
whose actions contain an `isAlter` action, with the two Kafbat exceptions modelled as
non-alter actions (`Topic.AnalysisRun`, smart-filter test). Audit records are produced from the
same `AccessRequest` + `Decision` + outcome, so audit and authorization can never disagree
about what was accessed.

### 6.6 All-in-one mode

Same composition root wires every service in-process. Differences:

- No `X-KUI-Principal` header is signed or verified; the gateway passes the verified
  `Principal` value directly to service application layers (the Tapir security logic is
  replaced by a `PrincipalSource.InProcess`). The signer still exists behind a `NoopSigner`, so
  code paths are identical and the pure `decide` runs in both places.
- Session store, RBAC policy and audit sink are single instances shared in memory.
- `kui.gateway.principalKey` is not required; if set it is ignored with a warning.
- Threat model note: in all-in-one mode services must not bind their own listeners; only the
  gateway port is exposed, otherwise header-less services would be reachable unauthenticated.

---

## Decision candidates (Appendix D format)

**ADR-015 — Application authentication approach (OIDC/LDAP/form)**
- Decision: identity adapters in `kui-identity-service`; OIDC via nimbus-jose-jwt 10.9.x +
  nimbus oauth2-oidc-sdk behind `OidcProviderPort`; LDAP/AD via UnboundID LDAP SDK 7.0.x behind
  `IdentityProviderPort`; form users with bcrypt hashes; optional bearer-token API auth (JWKS or
  introspection) mirroring Kafbat's resource-server mode.
- Evidence: Kafbat covers DISABLED/LOGIN_FORM/OAUTH2/LDAP with Spring Security (§1.1); pac4j
  has no Tapir adapter (§6.4); nimbus is what Spring itself uses; UnboundID already the KUI
  stack's pick.
- Tradeoff: more hand-written OIDC glue than pac4j would give; fewer providers "for free"
  (SAML/CAS out of scope).
- Reversibility: medium — ports isolate the libraries; swapping to pac4j later touches only the
  infrastructure module.

**ADR-017 (proposed) — Gateway session and CSRF model**
- Decision: opaque server-side session id in `HttpOnly; Secure; SameSite=Lax` cookie, idle
  30 min / absolute 12 h, id rotated at login; CSRF header `X-KUI-CSRF` + `Sec-Fetch-Site`
  check on all cookie-authenticated mutations; `POST` logout.
- Evidence: Kafbat disables CSRF everywhere and uses GET logout (§1.1); Kouncil uses the
  double-submit cookie pattern (§1.2).
- Tradeoff: SPA must send one header; stateful store limits horizontal scaling until a shared
  store adapter exists.
- Reversibility: high (store is a port; CSRF strategy is a gateway middleware).

**ADR-018 (proposed) — Signed principal header**
- Decision: `X-KUI-Principal` compact JWS, HS256 with keyed rotation (`kid`), 60 s expiry,
  request binding (`req` hash), `aud` per service; services reject unsigned requests except in
  all-in-one mode where the principal is passed in-process.
- Evidence: KUI's security design mandates HMAC; header-spoofing threat (§5).
- Tradeoff: body hashing costs one pass over request bodies at the gateway; symmetric key means
  any service can mint headers (mitigated by optional EdDSA).
- Reversibility: high (algorithm behind `SignerVerifier`).

**ADR-019 (proposed) — RBAC model and evaluation**
- Decision: adopt Kafbat's role/subject/cluster/permission model and its complete
  resource × action matrix (§2.2) verbatim, evaluated by pure functions in
  `kui-security-core` shared with Scala.js; read-only enforced per operation via `isAlter`.
- Evidence: §2.1–2.5; migration compatibility is a project requirement.
- Tradeoff: regex patterns from config carry ReDoS risk (mitigated at load); no per-cluster
  `defaultRole` (matches Kafbat).
- Reversibility: medium — the vocabulary becomes part of the config contract.

**ADR-020 (proposed) — Kafka cluster auth as typed config**
- Decision: `kui.clusters[].security` typed ADT: `plaintext | ssl { truststore?, keystore? }
  | sasl { protocol, mechanism: plain|scram256|scram512|gssapi|oauthbearer|awsMskIam|azureEntra|gcp, credentials… }`,
  rendered to `kafka-clients` properties with escaped JAAS; free-form `properties` still allowed
  as an override layer; cloud login handlers as optional modules of `kui-kafka-auth`.
- Evidence: Kafbat passes raw properties (§3.1) and leaks JAAS in config endpoints; Kouncil
  builds JAAS by `String.format` (§3.2).
- Tradeoff: more config surface to maintain as Kafka adds mechanisms.
- Reversibility: high (override layer keeps raw mode available).

**ADR-021 (proposed) — Audit and masking rule model**
- Decision: keep Kafbat's audit record shape and `ALL|ALTER_ONLY` levels with console and Kafka
  sinks; keep Kafbat's `REMOVE|MASK|REPLACE` masking with `fields`/`fieldsNamePattern` and
  topic key/value patterns; masking runs in `kui-message-service` after deserialization.
- Evidence: §4.
- Tradeoff: JSON-only structural masking (non-JSON gets whole-value policy), same as Kafbat.
- Reversibility: high.

## Open questions

1. Do we need a shared session store adapter (Kafka compacted topic vs Redis) in M1, or is
   single-replica gateway acceptable until later?
2. Should bearer-token API access be in M1 scope? It affects the audit principal model and CSRF
   exemptions.
3. Azure Entra: Kafbat supports only Event Hubs with a single bootstrap server; should KUI also
   support the generic `OAuthBearerLoginCallbackHandler` + Entra token endpoint path (no Azure SDK)?
4. Does the config-wizard remote validation (`/config/validate`) ship at all, given the SSRF
   surface, or only with an explicit allow-list?

## Confidence

**High** for §1–§4 (read directly from source at the cited commits). **Medium** for the Spring
session-rotation claim and for exact GitLab/GCP/Azure provider details (web sources, not
exercised). **Medium** for library recommendations: versions verified on the web today, but no
prototype was built against Tapir 1.13.x.
