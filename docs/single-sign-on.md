# Single Sign-On (OpenID Connect)

Technical documentation for administrators who connect BuggyWuggy to an identity provider
(IdP) such as Keycloak, Microsoft Entra ID or Authentik. Single sign-on is optional: without
providers in the configuration, BuggyWuggy only has its own password login.

## Contents

1. [How it works](#how-it-works)
2. [Decisions](#decisions)
3. [Settings](#settings)
4. [Accounts: linking, creating, placeholders](#accounts-linking-creating-placeholders)
5. [Group synchronisation](#group-synchronisation)
6. [Password login and emergency access](#password-login-and-emergency-access)
7. [Logging out](#logging-out)
8. [Examples per identity provider](#examples-per-identity-provider)
   - [Keycloak](#keycloak)
   - [Microsoft Entra ID](#microsoft-entra-id)
   - [Authentik](#authentik)
   - [Google Workspace](#google-workspace)
   - [GitLab](#gitlab)
   - [Forgejo](#forgejo)
9. [Reverse proxy and certificates](#reverse-proxy-and-certificates)
10. [Administration](#administration)
11. [Troubleshooting](#troubleshooting)
12. [Testing](#testing)

## How it works

BuggyWuggy is an OpenID Connect *relying party* using the **authorization code flow with
PKCE** (S256), as a confidential client (with a client secret) or a public client (without).

1. The login page shows a button **Log in with *name*** per provider. It links to
   `/oauth2/authorization/<id>?next=<page>`.
2. BuggyWuggy sends the browser to the IdP with `state`, `nonce` and a PKCE challenge.
3. After the login at the IdP, the browser returns to
   `<BuggyWuggy>/login/oauth2/code/<id>`. BuggyWuggy exchanges the code for tokens over a
   direct connection to the IdP, checks the ID token (signature from the IdP's JWKS, issuer,
   audience, expiry, nonce) and reads the userinfo endpoint.
4. The IdP's `sub` claim is looked up in the table `user_identity`. If it is not known yet,
   BuggyWuggy links an existing account, creates one, or refuses: see
   [Accounts](#accounts-linking-creating-placeholders).
5. The user gets a normal BuggyWuggy session (Spring Session in the database), with the
   permissions of their BuggyWuggy account, and lands on `next`.

Facts worth knowing:

- **Discovery.** Only the `issuer-uri` is configured; the endpoints come from
  `<issuer-uri>/.well-known/openid-configuration`. Discovery happens at the first login, not at
  start-up: BuggyWuggy also starts when the IdP is down. When the IdP cannot be reached,
  the login page says so, and discovery is tried again after a minute at the earliest.
- **No tokens are stored.** Access and refresh tokens are thrown away after the login. Only
  the ID token stays in the user's session, as `id_token_hint` for logging out at the IdP.
  BuggyWuggy does not call APIs of the IdP on behalf of users.
- **The session is BuggyWuggy's.** Its lifetime is BuggyWuggy's session time-out, not the
  lifetime of the IdP's tokens. A user disabled at the IdP keeps a BuggyWuggy session that
  is already open until it expires or they log out; deactivate the account in BuggyWuggy to
  end it at once (see [Administration](#administration)).
- **Several providers** can be configured at the same time, each with its own settings.
- **The callback URL** is `<scheme>://<host>/login/oauth2/code/<id>`, taken from the request.
  Behind a reverse proxy, see [Reverse proxy](#reverse-proxy-and-certificates).

## Decisions

| Topic | Decision |
|---|---|
| Protocol | OpenID Connect only. SAML and LDAP are not supported; most IdPs that speak SAML also speak OIDC. |
| Accounts at first login | Per provider: `create-users` (default **false**: an administrator creates the account first, for high-security environments). |
| Existing accounts | Linked at the first login by verified e-mail address (default), by username, or not at all. |
| Placeholders from imports | Become the user's account when linked, so imported issues and comments are theirs. |
| Groups | Optional: membership of mapped BuggyWuggy groups follows the IdP. |
| Password login | `all`, `system-admins` or `none`. |
| Two-factor login | A single sign-on login does not ask for BuggyWuggy's own second factor; enforce MFA at the IdP. |
| Logging out | Also at the IdP (RP-initiated logout), when the IdP supports it. |
| Tokens | Never stored in the database. |

## Settings

Single sign-on is configured in the configuration file (see
[Configuration file](installation.md#configuration-file)), under `buggywuggy.security.sso`.
Lists and maps cannot be given well as environment variables, so use the file for providers;
the client secret can still come from the environment (see below).

```yaml
buggywuggy:
  security:
    sso:
      password-login: all               # all | system-admins | none
      providers:
        keycloak:                       # the id: lower-case letters, digits and -, at most 40
          display-name: Company login   # on the button "Log in with Company login"
          issuer-uri: https://sso.example.org/realms/example
          client-id: buggywuggy
          client-secret: change-me      # empty: public client (PKCE only)
          scopes: [openid, profile, email]
          username-claim: preferred_username
          create-users: false
          link-users-by: email          # email | username | none
          groups-claim: groups          # empty: no group synchronisation
          group-mapping:
            - idp-group: buggywuggy-admins
              group: administrators
          logout-at-provider: true
```

| Setting | Default | Meaning |
|---|---|---|
| `password-login` | `all` | Who may log in with a BuggyWuggy password: everyone, system administrators only, or nobody. Environment variable `BUGGYWUGGY_SSO_PASSWORD_LOGIN`. See [Password login](#password-login-and-emergency-access). |
| `providers.<id>` | — | One entry per IdP. The id is part of URLs (`/oauth2/authorization/<id>`, the callback URL) and is stored with each linked identity: do not change it later, or every user is linked anew. |
| `display-name` | the id | Name on the login button and in the administration. |
| `issuer-uri` | — (required) | The issuer exactly as the IdP writes it in its discovery document, including a trailing `/` if it has one. |
| `client-id` | — (required) | The client (application) id registered at the IdP. |
| `client-secret` | empty | The client secret. Empty: a public client, authenticated by PKCE alone. |
| `scopes` | `openid, profile, email` | Must contain `openid`. Add the scope that gives the groups claim if the IdP needs one (e.g. `groups`). |
| `username-claim` | `preferred_username` | Claim with the username for new accounts and for `link-users-by: username`. Must match BuggyWuggy's username rules: 2–100 letters, digits and `. _ @ + -`. |
| `create-users` | `false` | `true`: someone the IdP knows but BuggyWuggy does not gets an account at their first login, in the group `users`. `false`: they are refused until an administrator creates the account. |
| `link-users-by` | `email` | How a first login finds an existing account: `email` (only if the IdP says the address is verified, `email_verified`), `username` (the `username-claim`), or `none`. |
| `groups-claim` | empty | Claim with the user's groups at the IdP (a list of names, or one name). Empty: groups are not synchronised. |
| `group-mapping` | empty | List of `idp-group` → `group` (a BuggyWuggy group). Needs `groups-claim`. |
| `logout-at-provider` | `true` | Logging out of BuggyWuggy also ends the session at the IdP. |

The client secret as environment variable, e.g. in `/etc/buggywuggy/buggywuggy.env`
(upper case, `.` and `-` become `_`):

```sh
BUGGYWUGGY_SECURITY_SSO_PROVIDERS_KEYCLOAK_CLIENT_SECRET=...
```

BuggyWuggy refuses to start when a provider lacks `issuer-uri` or `client-id`, when the
scopes lack `openid`, when `group-mapping` is set without `groups-claim`, and when
`password-login` is not `all` while no provider is configured (nobody could log in).

### What to register at the IdP

| Item | Value |
|---|---|
| Client type | OpenID Connect, confidential (with secret) — or public with PKCE |
| Grant / flow | Authorization code (standard flow). No implicit flow, no password grant. |
| Redirect URI (callback) | `https://bugs.example.org/login/oauth2/code/<id>` |
| Post-logout redirect URI | `https://bugs.example.org/login` |
| PKCE | S256 (may be enforced) |
| Scopes | `openid profile email` (+ the groups scope, if any) |

## Accounts: linking, creating, placeholders

At every login the IdP's `sub` (subject) identifies the user: it is stable at the IdP, unlike
a username or e-mail address. A known `(provider, sub)` always leads to the same account, even
if the user's name or address changes at the IdP.

At the **first** login with a provider (an unknown `sub`):

1. **Link** an existing account, as `link-users-by` says:
   - `email` — the account with the same e-mail address (case-insensitive). Only when the
     IdP sets `email_verified: true`; otherwise the login is refused with *"Your e-mail
     address is not verified at the identity provider"*, because anyone could otherwise
     claim an account by entering its address at an IdP that does not check addresses.
   - `username` — the account whose username equals the `username-claim`. Only use this
     when usernames at the IdP are given out by administrators (no self-registration),
     because it hands the BuggyWuggy account to whoever has that name at the IdP.
   - `none` — no linking; with `create-users: true` a new account is made, otherwise the
     login is refused.
2. Otherwise, with `create-users: true`, **create** an account: username from
   `username-claim`, display name from `name`, e-mail address from `email`, member of the
   group `users`, without a password. Refused when the username or e-mail address is taken
   by another account (link that one, or ask the administrator).
3. Otherwise **refuse**: *"You have no BuggyWuggy account yet. Ask your administrator to
   create one."*

An account can be linked to one identity per provider. An inactive account cannot log in,
also not through single sign-on.

**SSO-only accounts.** When a provider is configured, an administrator can create a user
with **Single sign-on only (no password)** (Administration → Users → Create user). Such a
user can only log in through an IdP; their profile has no password section, and the user
list shows them with the badge *SSO only*. An administrator can still give them a password
with **Set password**.

**Placeholders from imports.** Importers create *placeholder* accounts for authors that have
no BuggyWuggy account (inactive, no password, no e-mail address). When a first login links a
placeholder, it becomes a normal active account: issues, comments and history that were
imported under that name are then the user's. Because placeholders have **no e-mail
address**, they are found with `link-users-by: username` — the placeholder's username is the
author's name in the old tracker, so this only works when that matches the username at the
IdP. Otherwise an administrator can give the placeholder the user's e-mail address first,
so that `link-users-by: email` finds it.

**High-security setup.** Keep `create-users: false` (the default) and `link-users-by:
email` or `username`: only accounts an administrator made can log in. Make them SSO-only
and set `password-login: system-admins` or `none`.

## Group synchronisation

With `groups-claim` and `group-mapping`, the membership of each **mapped** BuggyWuggy group
follows the IdP at every login: a user in `idp-group` is added to `group`, a user not in it
is removed. Groups that do not appear in the mapping are left alone, so they can still be
managed by hand in BuggyWuggy. Several IdP groups may map to the same BuggyWuggy group; then
membership of any of them is enough.

- The BuggyWuggy groups must exist (Administration → Groups). A mapping to a missing group
  is skipped with a warning in the log.
- Changes at the IdP take effect at the next login, not during an open session.
- Mapping to `administrators` hands BuggyWuggy administration to whoever manages that
  group at the IdP. The flag *System administrator* is never set by single sign-on.
- Some IdPs send group ids or full paths instead of names (see the examples); write them in
  `idp-group` exactly as they appear in the token.

## Password login and emergency access

| `password-login` | Login page | Password login |
|---|---|---|
| `all` | IdP buttons, and the password form below them | everyone with a password |
| `system-admins` | IdP buttons; the password form behind the link *Administrator login with password* | system administrators only |
| `none` | IdP buttons only | nobody (`POST /api/v1/auth/login` answers 403) |

With `system-admins`, a user who is not a system administrator gets the same answer as a
wrong password, so the login form does not tell which passwords are right.

**Emergency access.** With `system-admins`, a system administrator with a password can always
log in, also when the IdP is down — recommended for production. With `none`, an outage of the
IdP locks everyone out; to get in, set `BUGGYWUGGY_SSO_PASSWORD_LOGIN=all` (or
`system-admins`) in the environment and restart BuggyWuggy.

**Two-factor login.** BuggyWuggy's own two-factor login (`BUGGYWUGGY_TWO_FACTOR=true`)
applies to password logins only. A single sign-on login is trusted as it comes from the IdP:
configure MFA there (in Keycloak: *Authentication → Required actions → Configure OTP*, or
a conditional OTP flow; in Entra ID: Conditional Access).

## Logging out

With `logout-at-provider: true` (the default), **Log out** in BuggyWuggy ends the
BuggyWuggy session and then sends the browser to the IdP's `end_session_endpoint` with
`id_token_hint`, `client_id` and `post_logout_redirect_uri=<BuggyWuggy>/login`. The IdP ends
its own session (so the next **Log in with …** asks for the password again) and returns to
BuggyWuggy's login page. Register that URL as allowed post-logout redirect URI at the IdP;
otherwise the IdP shows its own "logged out" page.

When the IdP has no `end_session_endpoint` in its discovery document (Google, GitLab,
Forgejo), or with `logout-at-provider: false`, only the BuggyWuggy session ends; the user stays
logged in at the IdP. Back-channel logout (the IdP ending BuggyWuggy sessions) is not
supported.

For the REST API: `POST /api/v1/auth/logout` answers `204` after a password login and `200`
with `{"redirectUrl": "..."}` after a single sign-on login.

## Examples per identity provider

In all examples BuggyWuggy runs at `https://bugs.example.org`. Replace host names, ids and
secrets.

### Keycloak

In the admin console of Keycloak (version 26):

1. Choose or create the realm (here `example`).
2. **Clients → Create client**: type *OpenID Connect*, client id `buggywuggy`, name
   `BuggyWuggy`. Next: *Client authentication* **on**, *Standard flow* on, *Direct access
   grants* off. Next:
   - *Valid redirect URIs*: `https://bugs.example.org/login/oauth2/code/keycloak`
   - *Valid post logout redirect URIs*: `https://bugs.example.org/login`
   - *Web origins*: `+`
3. Tab **Credentials**: copy the *Client secret*.
4. Tab **Advanced → Advanced settings**: *Proof Key for Code Exchange Code Challenge Method*
   `S256` (optional, enforces PKCE).
5. Groups: **Client scopes → buggywuggy-dedicated → Configure a new mapper → Group
   Membership**: name `groups`, *Token Claim Name* `groups`, *Full group path* **off**, *Add
   to ID token*, *access token* and *userinfo* on.
6. Make sure users have an e-mail address with *Email verified* on (or let Keycloak verify
   addresses: *Realm settings → Login → Verify email*).

```yaml
buggywuggy:
  security:
    sso:
      password-login: system-admins
      providers:
        keycloak:
          display-name: Keycloak
          issuer-uri: https://sso.example.org/realms/example
          client-id: buggywuggy
          client-secret: ${KEYCLOAK_SECRET}
          create-users: true
          link-users-by: email
          groups-claim: groups
          group-mapping:
            - idp-group: buggywuggy-admins
              group: administrators
            - idp-group: developers
              group: developers
```

(`${KEYCLOAK_SECRET}` is filled in from the environment variable of that name.)

The same client as a **realm import** (partial import via *Realm settings → Action → Partial
import*, or `kc.sh import`), as used by BuggyWuggy's own tests
(`server/src/test/resources/sso/keycloak-realm.json`):

```json
{
  "clientId": "buggywuggy",
  "name": "BuggyWuggy",
  "protocol": "openid-connect",
  "publicClient": false,
  "secret": "change-me",
  "standardFlowEnabled": true,
  "directAccessGrantsEnabled": false,
  "redirectUris": ["https://bugs.example.org/login/oauth2/code/keycloak"],
  "webOrigins": ["+"],
  "attributes": {
    "post.logout.redirect.uris": "https://bugs.example.org/login",
    "pkce.code.challenge.method": "S256"
  },
  "protocolMappers": [{
    "name": "groups",
    "protocol": "openid-connect",
    "protocolMapper": "oidc-group-membership-mapper",
    "config": {
      "full.path": "false", "claim.name": "groups",
      "id.token.claim": "true", "access.token.claim": "true", "userinfo.token.claim": "true"
    }
  }]
}
```

With *Full group path* on, the claim has `/parent/child` paths; write `idp-group` the same way.

### Microsoft Entra ID

In the Entra admin center: **App registrations → New registration**:

1. Name `BuggyWuggy`, *Accounts in this organizational directory only*, redirect URI
   platform **Web**: `https://bugs.example.org/login/oauth2/code/entra`.
2. **Authentication → Web → Redirect URIs**: add `https://bugs.example.org/login` as well;
   Entra ID only returns to a post-logout redirect URI that is registered as redirect URI.
3. **Certificates & secrets → New client secret**: copy the *value* (not the id). Note its
   expiry date: BuggyWuggy logins fail once it expires.
4. **Token configuration → Add optional claim → ID**: `email`, `preferred_username`.
   For groups: **Add groups claim → Security groups**, ID token *Group ID*.
5. Copy the *Directory (tenant) ID* and *Application (client) ID* from **Overview**.

```yaml
buggywuggy:
  security:
    sso:
      providers:
        entra:
          display-name: Microsoft
          issuer-uri: https://login.microsoftonline.com/<tenant-id>/v2.0
          client-id: <application-id>
          client-secret: ${ENTRA_SECRET}
          username-claim: preferred_username   # the UPN, e.g. alice@example.org
          create-users: true
          link-users-by: username
          groups-claim: groups
          group-mapping:
            - idp-group: 3f2504e0-4f89-11d3-9a0c-0305e82c3301   # object id of the Entra group
              group: administrators
```

Notes:

- Use the tenant id in the issuer, not `common` or `organizations`: the issuer in the
  tokens must equal `issuer-uri`.
- Entra ID does **not** send `email_verified`, so `link-users-by: email` refuses to link
  existing accounts. Use `username` (BuggyWuggy usernames equal to the UPN) or `none`, or
  let administrators create the accounts with matching usernames.
- The groups claim contains group **object ids**, not names. Users in more than 200 groups
  get no groups claim at all (*groups overage*); assign the relevant groups to the
  application (*Enterprise applications → BuggyWuggy → Properties → Assignment required*,
  and *Token configuration → Groups assigned to the application*) to stay below that.

### Authentik

In Authentik: **Applications → Providers → Create → OAuth2/OpenID Provider**:

- *Client type* Confidential; *Redirect URIs*: `https://bugs.example.org/login/oauth2/code/authentik`
  (strict); signing key: the default certificate.
- *Advanced protocol settings → Scopes*: `openid`, `email`, `profile` (the default
  `profile` scope mapping includes the claim `groups` with group names).
- Then **Applications → Create** with slug `buggywuggy` and this provider.

```yaml
buggywuggy:
  security:
    sso:
      providers:
        authentik:
          display-name: Authentik
          issuer-uri: https://auth.example.org/application/o/buggywuggy/
          client-id: <client id>
          client-secret: ${AUTHENTIK_SECRET}
          create-users: true
          groups-claim: groups
          group-mapping:
            - idp-group: BuggyWuggy Admins
              group: administrators
```

The issuer ends with `/`. Authentik sends `email_verified: false` unless configured
otherwise: for `link-users-by: email`, create a scope mapping that returns
`"email_verified": True` for addresses you trust, or use `username`.

### Google Workspace

In the Google Cloud console of the organisation: **APIs & Services → OAuth consent screen**
with *User type* **Internal** (only accounts of the Workspace organisation), then
**Credentials → Create credentials → OAuth client ID**, type *Web application*, authorised
redirect URI `https://bugs.example.org/login/oauth2/code/google`.

```yaml
buggywuggy:
  security:
    sso:
      providers:
        google:
          display-name: Google
          issuer-uri: https://accounts.google.com
          client-id: <id>.apps.googleusercontent.com
          client-secret: ${GOOGLE_SECRET}
          username-claim: email        # Google sends no preferred_username
          create-users: false
          link-users-by: email
```

Google sends no groups and has no logout endpoint. **Important:** with an *External* consent
screen every Google account in the world can log in; then keep `create-users: false`, so only
accounts an administrator made get in.

### GitLab

In GitLab: **Admin area → Applications → New application** (or under a group: *Settings →
Applications*): redirect URI `https://bugs.example.org/login/oauth2/code/gitlab`,
*Confidential* on, scopes `openid`, `profile`, `email`.

```yaml
buggywuggy:
  security:
    sso:
      providers:
        gitlab:
          display-name: GitLab
          issuer-uri: https://gitlab.example.org
          client-id: <application id>
          client-secret: ${GITLAB_SECRET}
          create-users: true
          link-users-by: username
          groups-claim: groups
          group-mapping:
            - idp-group: acme/buggywuggy-admins     # full path of the GitLab group
              group: administrators
```

GitLab's `groups` claim (from the userinfo endpoint) has full group paths. GitLab has no
logout endpoint for applications.

### Forgejo

In Forgejo: **Site administration → Integrations → Applications** (or *Settings →
Applications* of a user or organisation): name `BuggyWuggy`, redirect URI
`https://bugs.example.org/login/oauth2/code/forgejo`, *Confidential client* on.

```yaml
buggywuggy:
  security:
    sso:
      providers:
        forgejo:
          display-name: Forgejo
          issuer-uri: https://git.example.org/
          client-id: <client id>
          client-secret: ${FORGEJO_SECRET}
          scopes: [openid, profile, email, groups]
          create-users: true
          link-users-by: username
          groups-claim: groups
          group-mapping:
            - idp-group: acme:owners       # organisation:team
              group: administrators
```

The issuer is Forgejo's `ROOT_URL`, with the trailing `/`. With the scope `groups`, the
`groups` claim contains organisation names and `organisation:team` entries. Forgejo has no
logout endpoint.

## Reverse proxy and certificates

**Behind a reverse proxy** BuggyWuggy must know its public scheme and host name, both for the
callback URL it sends to the IdP and the one it checks when the browser comes back. Set, as
described in [HTTPS with a reverse proxy](installation.md#https-with-a-reverse-proxy):

```sh
BUGGYWUGGY_BASE_URL=https://bugs.example.org
BUGGYWUGGY_SECURE_COOKIES=true
SERVER_FORWARD_HEADERS_STRATEGY=framework
```

Without `SERVER_FORWARD_HEADERS_STRATEGY=framework`, BuggyWuggy sends
`http://localhost:8080/login/oauth2/code/<id>` as callback, which the IdP refuses (*invalid
redirect_uri*).

**The IdP's certificate** must be trusted by Java. A certificate from a public CA works as is.
For an internal CA, add it to a truststore and point Java at it, e.g. in
`/etc/buggywuggy/buggywuggy.env`:

```sh
# keytool -importcert -alias company-ca -file company-ca.pem \
#         -keystore /etc/buggywuggy/truststore.p12 -storetype PKCS12 -storepass changeit -noprompt
# (also import the default CAs if other HTTPS connections, e.g. to the mail server, need them:
#  keytool -importkeystore -srckeystore $JAVA_HOME/lib/security/cacerts -srcstorepass changeit
#          -destkeystore /etc/buggywuggy/truststore.p12 -deststorepass changeit)
JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStore=/etc/buggywuggy/truststore.p12 -Djavax.net.ssl.trustStorePassword=changeit
```

BuggyWuggy connects to the IdP itself (discovery, token, userinfo and JWKS endpoints): the
IdP must be reachable from the server, not only from the users' browsers, and under the same
host name as in `issuer-uri`.

**Clock.** ID tokens are checked for expiry; keep the clocks of BuggyWuggy and the IdP
synchronised (NTP).

## Administration

- **Administration → Users → *user*** has a section **Single sign-on** with the providers the
  user is linked to, the e-mail address the IdP gave, and the first and last login. **Unlink**
  removes the link and logs the user out everywhere; their next login with that provider is a
  first login again (linked, created or refused per the provider's settings). Use it when an
  account was linked to the wrong person, or the IdP was replaced.
- **Deactivating** a user ends their sessions and blocks single sign-on logins too.
- Users who log in only through an IdP have no password: the badge *SSO only*. *Set password*
  gives them one (useful for a system administrator with `password-login: system-admins`).
- The log (`journalctl -u buggywuggy`) shows every SSO login, link, account creation and group
  change at level INFO, and refused or failed logins with their reason.

The database table is `user_identity` (see [Domain model](domain-model.md)):

```sql
select u.username, i.provider, i.subject, i.email, i.created_at, i.last_login_at
from user_identity i join user_account u on u.id = i.user_id order by u.username;
```

## Troubleshooting

The login page shows a message; the log of BuggyWuggy has the details.

| Message on the login page | Cause and remedy |
|---|---|
| *The identity provider cannot be reached at the moment* | Discovery at `<issuer-uri>/.well-known/openid-configuration` failed (wrong URI, DNS, firewall, certificate). The log has the exception. BuggyWuggy tries again after a minute. |
| *This single sign-on provider is not configured* | The id in the URL is not in the configuration (an old bookmark, or the id was renamed). |
| *You have no BuggyWuggy account yet* | `create-users: false` and no existing account was found by `link-users-by`. Create the account (SSO-only) with the same e-mail address or username. |
| *Your e-mail address is not verified at the identity provider* | An account with that address exists, but the IdP sends `email_verified: false` or nothing (Entra ID). Verify the address at the IdP or use `link-users-by: username`. |
| *Your BuggyWuggy account has been deactivated* | Activate the account under Administration → Users. |
| *An account with your username / e-mail address already exists* | `create-users: true` wanted to create an account, but the name or address is taken by an account it did not link. Link by that attribute, or change one of the accounts. |
| *already linked to another identity at this provider* | The account is linked to another `sub` of the same provider (e.g. the IdP account was re-created). **Unlink** under Administration → Users. |
| *Your username at the identity provider cannot be used* | The `username-claim` is missing or does not match BuggyWuggy's username rules. Choose another claim (e.g. `email`). |
| *The identity provider gave no e-mail address* | Add the scope `email`, and make sure the user has an address at the IdP. |
| *The login at the identity provider was cancelled* | The user cancelled, or refused consent. |
| *Logging in with single sign-on did not work* | See the log: e.g. `invalid_redirect_uri` / *redirect_uri mismatch* (proxy headers, see above), `invalid_client` (wrong client secret, or it expired), `invalid_id_token` (issuer differs from `issuer-uri`, often a missing or extra trailing `/`; or clocks out of sync). |

Other symptoms:

- **The IdP says *Invalid redirect URI*** before the login form: the callback URL BuggyWuggy
  sent is not registered. Look at the `redirect_uri` parameter in the browser's address bar;
  `http://` or the wrong host means the proxy settings above are missing.
- **After logging out, the IdP shows its own page** instead of returning: register
  `https://bugs.example.org/login` as post-logout redirect URI.
- **Groups are not synchronised:** decode the ID token or userinfo (log level
  `org.springframework.security=DEBUG`, temporarily) and check the claim name and the exact
  group values; check that the BuggyWuggy group exists.

## Testing

BuggyWuggy's integration tests (`SsoIntegrationTest`, `SsoTwoFactorIntegrationTest`) log in
through a real OIDC provider:

- by default **Keycloak** in a container (Testcontainers, image
  `quay.io/keycloak/keycloak:26.8.1`) with the realm
  `server/src/test/resources/sso/keycloak-realm.json`;
- with `BUGGYWUGGY_TEST_OIDC=mock` a small OIDC provider inside the test JVM (code flow, PKCE,
  RS256 ID tokens, userinfo, end session), as on the CI runners.

To try single sign-on by hand with the same realm:

```sh
docker run --rm -p 127.0.0.1:18180:8080 \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -v $PWD/server/src/test/resources/sso/keycloak-realm.json:/opt/keycloak/data/import/realm.json:ro \
  quay.io/keycloak/keycloak:26.8.1 start-dev --import-realm
```

and configure the provider with `issuer-uri: http://localhost:18180/realms/buggywuggy`,
`client-id: buggywuggy`, `client-secret: test-secret`. The users are `alice`, `bob`, `carol`
and `dave`, each with the password `<name>-password`; `alice` is in the group
`buggywuggy-admins`, and `carol`'s e-mail address is not verified.
