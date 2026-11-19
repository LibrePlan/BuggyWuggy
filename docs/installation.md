# BuggyWuggy Installation Guide

This guide explains how to install, configure, run, back up and upgrade a BuggyWuggy server.

> **Status:** BuggyWuggy is in early development. It is fine for trying out and for small
> internal use, but there are no releases yet. You build it from source, and administration is
> partly done through the REST API (see [Administration](#administration)).

## Contents

1. [Overview](#overview)
2. [Requirements](#requirements)
3. [Preparing the database](#preparing-the-database)
4. [Building BuggyWuggy](#building-buggywuggy)
5. [Configuration](#configuration)
   - [E-mail](#e-mail)
   - [Issues from e-mail](#issues-from-e-mail)
   - [Two-factor login](#two-factor-login)
   - [Single sign-on](#single-sign-on)
6. [Running BuggyWuggy](#running-buggywuggy)
7. [First-time setup](#first-time-setup)
8. [Running as a service (systemd)](#running-as-a-service-systemd)
9. [HTTPS with a reverse proxy](#https-with-a-reverse-proxy)
10. [Administration](#administration)
    - [Importing from other trackers](#importing-from-other-trackers)
    - [Donation](#donation)
11. [Backup and restore](#backup-and-restore)
12. [Upgrading](#upgrading)
    - [Automatic deployment to a test server](#automatic-deployment-to-a-test-server)
13. [Monitoring](#monitoring)
14. [Troubleshooting](#troubleshooting)

## Overview

BuggyWuggy is a single Java application (one `.jar` file) that contains both the server and
the web interface. It stores its data in PostgreSQL, and the files attached to issues in a data
directory on disk. It needs no other services: no search engine, no message queue and no cache.

```
 Browser ──HTTPS──> reverse proxy (nginx, Apache, Caddy …) ──HTTP──> BuggyWuggy (:8080) ──> PostgreSQL
```

- The database schema is created and upgraded automatically when the application starts.
- Search uses PostgreSQL's built-in full-text search. There is no separate index that needs
  rebuilding.
- Login sessions are stored in PostgreSQL. A restart does not log users out, and several
  instances can share one database.

## Requirements

| Component | Version | Notes |
|---|---|---|
| Java | 25 | Only a Java runtime is needed to *run* BuggyWuggy; building also needs the JDK. |
| PostgreSQL | 16 or newer | The `pg_trgm` extension must be available (it is part of the standard PostgreSQL packages). |
| Operating system | Any that runs Java 25 | These instructions use Linux. |
| Memory | 1 GB RAM for the application | More for large installations. |

To **build** from source you also need Git and internet access. Maven and Node.js do not need to
be installed: the included Maven wrapper (`./mvnw`) downloads Maven, and the build downloads its
own copy of Node.js for the web interface.

## Preparing the database

Create a database user and a database owned by that user. Choose your own password:

```sh
sudo -u postgres psql -c "create role buggywuggy login password 'choose-a-strong-password'"
sudo -u postgres psql -c "create database buggywuggy owner buggywuggy"
```

BuggyWuggy creates all tables itself on first start. It also enables the `pg_trgm` extension.
If the database user is not allowed to create extensions, enable it once as a superuser:

```sh
sudo -u postgres psql -d buggywuggy -c "create extension if not exists pg_trgm"
```

### Trying it out with Docker

For a quick test you can start PostgreSQL with Docker instead. The repository contains a
`compose.yaml` that starts PostgreSQL 16 with user, password and database all set to
`buggywuggy`:

```sh
docker compose up -d
```

Do not use these default credentials in production.

## Building BuggyWuggy

```sh
git clone <repository-url> buggywuggy
cd buggywuggy
./mvnw package -DskipTests
```

The result is a single file: `server/target/buggywuggy.jar`. Copy it to the server, for
example to `/opt/buggywuggy/buggywuggy.jar`.

Running the tests (`./mvnw package` without `-DskipTests`) also needs Docker, because the tests
start their own PostgreSQL container. See the README for running tests without Docker.

## Configuration

BuggyWuggy is configured with environment variables or a [configuration file](#configuration-file):

| Variable | Default | Description |
|---|---|---|
| `BUGGYWUGGY_DB_URL` | `jdbc:postgresql://localhost:5432/buggywuggy` | JDBC URL of the database. |
| `BUGGYWUGGY_DB_USER` | `buggywuggy` | Database user. |
| `BUGGYWUGGY_DB_PASSWORD` | `buggywuggy` | Database password. **Always set this in production.** |
| `BUGGYWUGGY_PORT` | `8080` | HTTP port the application listens on. |
| `BUGGYWUGGY_SESSION_TIMEOUT` | `8h` | How long an idle login stays valid, e.g. `30m`, `8h`, `7d`. |
| `BUGGYWUGGY_SECURE_COOKIES` | `false` | Set to `true` when users reach BuggyWuggy over HTTPS (recommended). The session cookie is then only sent over HTTPS. |
| `BUGGYWUGGY_SETUP_TOKEN` | *(random, written to the log)* | Fixed token for first-time setup. Useful for automated installations. |
| `BUGGYWUGGY_DATA_DIR` | `data` (relative to the working directory) | Directory for attachment files. It is created if needed and must be writable by BuggyWuggy. Use an absolute path in production, e.g. `/var/lib/buggywuggy`. |
| `BUGGYWUGGY_MAX_ATTACHMENT_SIZE` | `50MB` | Largest file that can be attached, e.g. `10MB`, `1GB`. |
| `BUGGYWUGGY_MAX_UPLOAD_SIZE` | `200MB` | Largest total size of the files in one upload. |
| `BUGGYWUGGY_SOURCE_URL` | `https://github.com/libreplan/buggywuggy` | Where users get the source code, shown under Help → About. The AGPL requires offering the source of the version you run: point it at your own repository if you changed BuggyWuggy. |
| `BUGGYWUGGY_SSO_PASSWORD_LOGIN` | `all` | With single sign-on: who may log in with a password (`all`, `system-admins`, `none`); see [Single sign-on](#single-sign-on). |
| `BUGGYWUGGY_TWO_FACTOR` | `false` | `true`: every user logs in with a code from an authenticator app besides the password; see [Two-factor login](#two-factor-login). |
| `BUGGYWUGGY_TWO_FACTOR_ISSUER` | `BuggyWuggy` | The name authenticator apps show with the code, e.g. `BuggyWuggy ACME` when users have several installations. |
| `BUGGYWUGGY_DONATION_COMIC_DOWNLOAD` | `true` | Download the xkcd comic of the donation text on the login page once from imgs.xkcd.com (see [Donation](#donation)). `false`: BuggyWuggy never contacts xkcd.com, and the login page shows only the comic's attribution. |

Because BuggyWuggy is a Spring Boot application, any standard Spring Boot setting can also be
given as an environment variable. One that is useful behind a reverse proxy:

| Variable | Recommended value | Description |
|---|---|---|
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | Trust the `X-Forwarded-*` headers of your reverse proxy, so the application knows the original scheme (HTTPS) and host. Only set this when BuggyWuggy can **only** be reached through the proxy. |

Login protection is built in: after 5 failed login attempts for the same username from the
same address, further attempts are refused for 15 minutes.

### Configuration file

Instead of environment variables you can use a configuration file in YAML. Every setting has
a name in the file. BuggyWuggy reads the file from one of these places:

- a location you give with `--spring.config.additional-location=file:<path>` or the
  environment variable `SPRING_CONFIG_ADDITIONAL_LOCATION=file:<path>`, for example
  `/etc/buggywuggy/application.yml`;
- otherwise `config/application.yml` or `application.yml` in the working directory (the
  directory BuggyWuggy is started from).

**Template.** When BuggyWuggy starts and finds no configuration file, it writes one with every
setting, its default value and an explanation, all commented out. It goes to the location
given with `--spring.config.additional-location` if there is one (prefix the location with
`optional:`, as in `optional:file:/etc/buggywuggy/application.yml`, so BuggyWuggy may start
while the file does not exist yet), otherwise to `config/application.yml` in the working
directory. The log names the file. It is readable for its owner and group only, and an
existing file is never overwritten. When BuggyWuggy may not write there, for example under
systemd (see below), the log says so and BuggyWuggy starts normally. You can always print the
template yourself, without starting BuggyWuggy:

```sh
java -jar buggywuggy.jar config-template > application.yml
```

To change a setting, remove the `#` in front of it and in front of the keys it belongs to,
keep the indentation, and restart BuggyWuggy. Lines starting with `##` are explanations. Set
`BUGGYWUGGY_CONFIG_GENERATE=false` to stop BuggyWuggy from writing the template.

**Which file is used.** **Administration → E-mail** shows the configuration file BuggyWuggy
actually read, under *Configuration file*.

**File or environment variable.** Set each setting in one place: in the file or as an
environment variable, not both. When a setting is in both places, which one is used differs
per setting.

Example:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/buggywuggy
    username: buggywuggy
    password: choose-a-strong-password
buggywuggy:
  data-dir: /var/lib/buggywuggy
  base-url: https://bugs.example.org
  mail:
    host: smtp.example.org
    port: 587
    username: bugs@example.org
    password: the-mail-password
    security: starttls
    from: "BuggyWuggy <bugs@example.org>"
```

The file contains passwords: make it readable only for root and the `buggywuggy` group
(`chmod 640`, `chown root:buggywuggy`).

**TaskWarrior urgency.** The coefficients of the urgency calculation are set under
`buggywuggy.taskwarrior.urgency`; see [TaskWarrior urgency](taskwarrior.md#configuration).

### E-mail

BuggyWuggy sends e-mail notifications about issues (see the
[User Guide](user-guide.md#e-mail-notifications)). Without `buggywuggy.mail.host`, no mail is
sent. The settings, in the configuration file or as environment variables:

| Setting | Environment variable | Default | Description |
|---|---|---|---|
| `buggywuggy.base-url` | `BUGGYWUGGY_BASE_URL` | `http://localhost:8080` | The address users open BuggyWuggy at. Links in mails point here. **Set this when you use mail.** |
| `buggywuggy.mail.host` | `BUGGYWUGGY_MAIL_HOST` | *(empty: no mail)* | SMTP server. |
| `buggywuggy.mail.port` | `BUGGYWUGGY_MAIL_PORT` | `587` | SMTP port: usually 587 with `starttls`, 465 with `ssl`, 25 for a local relay. |
| `buggywuggy.mail.security` | `BUGGYWUGGY_MAIL_SECURITY` | `starttls` | `starttls` (required, not optional), `ssl` or `none`. |
| `buggywuggy.mail.username` | `BUGGYWUGGY_MAIL_USERNAME` | *(empty: no login)* | SMTP login. |
| `buggywuggy.mail.password` | `BUGGYWUGGY_MAIL_PASSWORD` | | SMTP password. |
| `buggywuggy.mail.from` | `BUGGYWUGGY_MAIL_FROM` | `BuggyWuggy <buggywuggy@localhost>` | Sender of the mails. Many mail servers only accept their own addresses here. |
| `buggywuggy.mail.quiet-period` | | `60s` | Changes by one person to one issue are combined into one mail once nothing happened for this long… |
| `buggywuggy.mail.max-delay` | | `5m` | …but a mail is never held back longer than this. |
| `buggywuggy.mail.max-attempts` | | `8` | Attempts to send a mail before it counts as failed. The waiting time doubles after each attempt (1, 2, 4 … minutes, at most an hour). |
| `buggywuggy.mail.retention` | | `30d` | Sent and failed mails are kept this long, then removed. |
| `buggywuggy.mail.timeout` | | `30s` | Time-out for connecting to and talking with the mail server. |
| `buggywuggy.mail.poll-interval` | | `10s` | How often waiting notifications and mails are processed. |
| `buggywuggy.mail.cleanup-cron` | | `0 17 3 * * *` | When old mails are removed (cron: second, minute, hour, day, month, weekday). |

Mails are first stored in the database and then sent by a background job, so a mail server
that is down does not slow BuggyWuggy down, and no mail is lost by a restart. Several
BuggyWuggy instances can share one database; each mail is sent once.

After changing the settings, restart BuggyWuggy and use **Send test mail** on the
**Administration → E-mail** page.

### Issues from e-mail

BuggyWuggy can read mailboxes over IMAP and make an **unassigned issue** of every mail, in the
project of that mailbox. There can be several mailboxes, each for one project (e.g.
`support@` into SUP and `feedback@` into FEEDBACK); they are checked one after the other, and a
mailbox that cannot be reached does not hold up the others. They are set in the configuration
file only; **Administration → E-mail** shows them, with the last check, errors and the last 50
mails that were handled.

```yaml
buggywuggy:
  inbound-mail:
    poll-interval: 2m
    mailboxes:
      - name: support
        host: imap.example.org
        username: support@example.org
        password: the-imap-password
        project: SUP
      - name: feedback
        host: imap.example.org
        username: feedback@example.org
        password: the-other-password
        project: FEEDBACK
        accept: donor-proof
```

| Setting (per mailbox) | Default | Description |
|---|---|---|
| `name` | | A name of your own, for the log and the overview. Must be unique. |
| `host`, `port` | `993` | IMAP server. |
| `security` | `ssl` | `ssl` (port 993), `starttls` (port 143, required) or `none`. |
| `username`, `password` | | IMAP login. |
| `folder` | `INBOX` | The folder that is read. |
| `processed-folder` | `Processed` | Where handled mail is moved to. Created when missing. |
| `spam-folder` | `Spam` | Where refused mail is moved to. Created when missing. |
| `project` | | The key of the project the issues go into. |
| `issue-type` | `Task` | The issue type of new issues. |
| `accept` | `anyone` | `anyone`, or `donor-proof`: only mail with a valid donor proof, as **Help → Send feedback** sends it; other mail is spam. |
| `address` | the username, if it is an e-mail address | Where answers come back: the Reply-To of external comments. |
| `max-attachment-size` | `10MB` | Larger files are left out (the description says which). |
| `timeout` | `30s` | Time-out for the IMAP server. |

`buggywuggy.inbound-mail.poll-interval` (default `2m`) sets how often the mailboxes are checked.
Several BuggyWuggy instances that share a database never make two issues of one mail, but let
only one of them read the mailboxes.

**What becomes of a mail.** The subject becomes the summary, and the **whole text** the
description, so that nothing is lost: plain text is kept as it was written (Markdown characters
are escaped), an HTML-only mail is converted to text, and below it is who sent it and when.
Files become attachments. The sender becomes the reporter: an existing account with that e-mail
address, or else an inactive placeholder named after the address, so that you see who it was.
Mails of the feedback form get the issue type of the feedback (*Bug* or *Feature*) and the label
`feedback`. Mail is **never deleted**: after handling it is moved to the processed folder.

**Mail that is not an issue.** Automatic mail (out-of-office replies, mailing lists, mail with
`Auto-Submitted`) and mail from the mailbox's own address are ignored, so that BuggyWuggy cannot
start a mail loop. With `accept: donor-proof`, mail without a valid, unexpired donor proof is
spam: it is moved to the spam folder and logged as `spam from … removed`. Each mail is handled
once (by its Message-ID), also when moving it failed; a mail that cannot be handled stays in the
folder and is tried again at the next check.

**Answering by e-mail.** On an issue that came by mail, a comment is **internal** by default:
only people in BuggyWuggy see it. Choose **Reply to …** above the comment field to make it
**external**: it is then also mailed to the sender, as an answer to the original mail, with the
mailbox's `address` as Reply-To. External comments are marked *Sent by e-mail*; editing one
later does not send it again. Sending needs the mail server of the previous section.

**Answers.** When the sender answers, the answer becomes a comment on the same issue, shown
indented right below the external comment it answers (marked *Reply by e-mail*). An answer is
recognised by the Message-ID of the mail it answers (In-Reply-To / References), or else by the
issue key in the subject (`[SUP-12]`), and only when it comes from the issue's sender; mail from
anyone else becomes a new issue. Answers need no donor proof: they answer a mail of ours. The
text the answer quoted (from "On … wrote:" or the like, or `>` lines at the end) is kept apart,
under **Show quoted text**. Files become attachments of the issue. **An answer to a closed issue
reopens it.**

**New issue from an answer.** When an answer raises something new, **New issue from this
answer** in the comment's menu makes an issue of its own of it, with a summary, project, type and
description to fill in. The sender becomes its reporter and its e-mail contact, it gets the
answer's files, and the two issues are linked (*split to* / *split from*). As a **copy** the
answer stays, marked *Split off into …*; **moved**, the answer and its files leave the original
issue, which takes the permission to delete all comments.

All of this is logged under `org.buggywuggy.inbound-mail`:

```bash
journalctl -u buggywuggy | grep "Inbound mail"
```

### Two-factor login

With `BUGGYWUGGY_TWO_FACTOR=true` (or `buggywuggy.security.two-factor.enabled: true` in the
configuration file) every user logs in with a second factor: after the password, a 6-digit code
from an authenticator app on their phone (TOTP, RFC 6238), such as Aegis, FreeOTP, Google
Authenticator or Microsoft Authenticator. It applies to all users, administrators included.

- **Setting it up.** A user who has no second factor yet goes through a wizard right after the
  password at the next login: scan the QR code with the app, enter the code the app shows to
  check that it works, and save the 10 **recovery codes**. Each recovery code logs in once
  instead of a code from the app, for when the phone is not at hand. They are shown only
  then; BuggyWuggy keeps only their hashes.
- **Switching it on** ends every login made without a second factor: users log in again, and
  go through the wizard. Switching it off again keeps the users' second factors; they are used
  again when it is switched back on.
- **Lost phone.** An administrator opens the user under **Administration → Users** and
  clicks **Reset two-factor login**. This removes the secret and the recovery codes and logs
  the user out everywhere; the next login starts the wizard again. Only system
  administrators can reset it for a system administrator. When the last system administrator
  has lost both phone and recovery codes, remove the second factor in the database:
  `delete from user_two_factor where user_id = (select id from user_account where username = 'admin');`
  (and the same for `user_recovery_code`).
- **Clock.** The codes depend on the time: keep the server's clock synchronised (NTP, e.g.
  `timedatectl set-ntp true`). Codes from 30 seconds before or after are accepted too; every
  code works only once.
- **Wrong codes** count as failed logins (see the login protection above): after 5, the user
  has to wait 15 minutes and start again with the password. A login waits at most 5 minutes
  for the code.
- **The key.** The secrets are stored encrypted in the database (AES-256-GCM). The key is
  `<data directory>/secrets/two-factor.key`, created at the first setup, readable only for
  the BuggyWuggy user. Back it up (see [Backup and restore](#backup-and-restore)); without
  it every user has to set up two-factor login again, after an administrator reset theirs.
- **Scripts and the REST API** log in like the web interface (`POST /api/v1/auth/login`, then
  `POST /api/v1/auth/two-factor` with `{"code": "123456"}`), so with two-factor login on,
  unattended scripts cannot log in.
- **Single sign-on** logins do not ask for this second factor; enforce MFA at the identity
  provider instead.

### Single sign-on

BuggyWuggy can let users log in through an OpenID Connect identity provider, such as
Keycloak, Microsoft Entra ID, Authentik, Google Workspace, GitLab or Forgejo, optionally
creating accounts and synchronising groups. It is configured in the configuration file; see
[docs/single-sign-on.md](single-sign-on.md) for all settings, examples per provider and
troubleshooting.

## Running BuggyWuggy

```sh
export BUGGYWUGGY_DB_URL=jdbc:postgresql://localhost:5432/buggywuggy
export BUGGYWUGGY_DB_USER=buggywuggy
export BUGGYWUGGY_DB_PASSWORD=choose-a-strong-password
export BUGGYWUGGY_DATA_DIR=/var/lib/buggywuggy
java -jar /opt/buggywuggy/buggywuggy.jar
```

On first start the database schema is created, and a [configuration file](#configuration-file)
template is written to `config/application.yml` in the working directory. When the log shows
`Started BuggyWuggyApplication`, open `http://<server>:8080` in a browser.

## First-time setup

A new installation has no users. The first administrator is created with a one-time
**setup token**, so that nobody else can claim the installation before you do.

1. Find the token in the application log:

   ```
   ==========================================================================
     BuggyWuggy has not been set up yet.
     Create the first administrator using this setup token:

         3f6c…

   ==========================================================================
   ```

   If you set `BUGGYWUGGY_SETUP_TOKEN`, use that value instead.

2. Open BuggyWuggy in the browser. It shows the **Welcome to BuggyWuggy** page.
3. Enter the setup token, a username, your full name, an e-mail address and a password of at
   least 12 characters.
4. Choose **Create administrator** and log in with the new account.

This first account is a *system administrator* and a member of the groups `administrators` and
`users`. After setup the token can no longer be used.

## Running as a service (systemd)

There are templates for the service and its settings, with instructions at the top:

- the unit: [docs/systemd/buggywuggy.service](systemd/buggywuggy.service), installed as
  `/etc/systemd/system/buggywuggy.service`;
- the settings: [docs/systemd/buggywuggy.env](systemd/buggywuggy.env), installed as
  `/etc/buggywuggy/buggywuggy.env` (only readable for root: it contains the database
  password).

Install them as root, from the directory with the jar and both templates:

```sh
useradd --system --home /opt/buggywuggy --shell /usr/sbin/nologin buggywuggy
install -d -m 755 -o root -g root /opt/buggywuggy
install -m 644 -o root -g root buggywuggy.jar /opt/buggywuggy/buggywuggy.jar
install -d -m 750 -o buggywuggy -g buggywuggy /var/lib/buggywuggy
install -d -m 750 -o root -g buggywuggy /etc/buggywuggy
install -m 600 -o root -g root buggywuggy.env /etc/buggywuggy/buggywuggy.env
```

Edit `/etc/buggywuggy/buggywuggy.env`: at least the database password and
`BUGGYWUGGY_BASE_URL`. Without a reverse proxy, set `BUGGYWUGGY_SECURE_COOKIES=false` and
remove `SERVER_FORWARD_HEADERS_STRATEGY`. For mail settings, also create the
[configuration file](#configuration-file) in the same directory, with all settings commented
out:

```sh
java -jar /opt/buggywuggy/buggywuggy.jar config-template \
  | install -m 640 -o root -g buggywuggy /dev/stdin /etc/buggywuggy/application.yml
```

The service cannot create it on its own first start: the whole file system is read-only for
it, except the data directory `/var/lib/buggywuggy`. The template also keeps BuggyWuggy away
from home directories, devices and kernel settings (`systemd-analyze security buggywuggy`
rates it 3.0, "OK").

Start it and follow the log (this is also where the setup token appears):

```sh
install -m 644 -o root -g root buggywuggy.service /etc/systemd/system/buggywuggy.service
systemctl daemon-reload
systemctl enable --now buggywuggy
journalctl -u buggywuggy -f
```

If you put the jar or the data elsewhere, change the paths in both files. `-Xmx1g` in
`ExecStart` is the most memory Java may use for BuggyWuggy itself.

## HTTPS with a reverse proxy

Run BuggyWuggy behind a reverse proxy that handles TLS, and let it listen only on localhost or
an internal network. There are ready-to-use templates, with instructions at the top:

- nginx: [docs/reverse-proxy/nginx.conf](reverse-proxy/nginx.conf)
- Apache httpd 2.4: [docs/reverse-proxy/apache.conf](reverse-proxy/apache.conf)

Replace `bugs.example.org`, the certificate paths and the port of BuggyWuggy (`8080`). Both
templates redirect HTTP to HTTPS (except for Let's Encrypt's `/.well-known/acme-challenge/`),
pass the original host and scheme in `X-Forwarded-*` headers, allow uploads up to the default
`BUGGYWUGGY_MAX_UPLOAD_SIZE` of 200 MB (change both together) and wait up to 5 minutes for
large attachments. Strict-Transport-Security is in the templates but commented out: enable it
once HTTPS works.

Then set in `/etc/buggywuggy/buggywuggy.env`:

```sh
BUGGYWUGGY_BASE_URL=https://bugs.example.org
BUGGYWUGGY_SECURE_COOKIES=true
SERVER_FORWARD_HEADERS_STRATEGY=framework
```

Only set `SERVER_FORWARD_HEADERS_STRATEGY=framework` when BuggyWuggy can **only** be reached
through the proxy: it makes BuggyWuggy trust the `X-Forwarded-*` headers.

BuggyWuggy must be served at the root of a host name (`https://bugs.example.org/`). Serving
it under a sub-path such as `https://example.org/bugs/` is not supported yet.

## Administration

Users with the global permission *Administer BuggyWuggy* (by default the group
`administrators`) see **Administration** in the top bar. It has seven pages:

- **Users**: find, create and edit users. On a user's page you can change the name and
  e-mail, deactivate the account, set a new password and change group memberships. Only
  system administrators can create or manage other system administrators.
  - New users are added to the group `users`.
  - Deactivated users cannot log in, but their name stays on their issues and comments.
    BuggyWuggy does not delete users.
  - Setting a password or deactivating a user logs that user out everywhere.
  - **Reset two-factor login** makes the user set up a new second factor at the next login;
    see [Two-factor login](#two-factor-login).
- **Groups**: create, rename and delete groups and manage their members. The built-in groups
  `administrators` and `users` cannot be renamed or deleted.
- **Global permissions**: which groups may administer BuggyWuggy, create projects and browse
  users.
- **Board**: the default board: the columns, swimlanes and number of days resolved issues stay,
  for every project that has no columns of its own. A new installation has *To Do*,
  *In Progress* and *Done*, one per status category. Project administrators can give their
  project columns of its own; see *The board* in the [User Guide](user-guide.md#the-board).
- **E-mail**: the mail server status with **Send test mail** and the configuration file in
  use, the mail templates, the languages mails are available in, and mails that could not be
  sent. A language can be enabled or disabled, made the default, added and deleted; the
  default language cannot be disabled or deleted. Failed mails can be sent again or discarded.
- **Import**: copy the issues of another issue tracker into BuggyWuggy; see
  [Importing from other trackers](#importing-from-other-trackers).
- **Donation**: support the project; see [Donation](#donation).

### Mail templates

Every mail BuggyWuggy sends has a template per language, which administrators can change on
**Administration → E-mail**: issue created, updated, assigned, status changed, comment added,
attachment added, issue deleted, and the test mail. BuggyWuggy comes with templates in English
and Dutch. Users get mails in the language of their profile; when that language is not
enabled, or has no template, the default language is used.

A template has a subject and a body in HTML. **Keywords** between double braces, such as
`{{issue.key}}` or `{{recipient.name}}`, are replaced by information about the issue and the
recipient. The box next to the editor lists the keywords of the template with an
explanation, in your own language (choose it in your profile); click one to insert it.

- Values of keywords are inserted as text: HTML in an issue summary is shown, never executed.
- *Block* keywords, such as `{{changes}}` (a table of what changed) and `{{comments}}`, insert
  ready-made HTML and can only be used in the body.
- Unknown keywords and unclosed braces are reported with their line number when you save.
- Scripts, forms, event handlers and style sheets are removed from the HTML; use `style`
  attributes for layout. The plain-text part of the mail is made from the HTML automatically.
- **Preview** shows the mail with sample data, **Send test to me** sends it to your own
  address, and **Reset to default** brings back the built-in template.

Several changes by one person to one issue within about a minute are sent as one mail. Its
template is chosen by the most important change: a new issue, then a comment, a status
change, an assignment, and otherwise *issue updated*.

Project settings (details, components, versions and people in roles) are on each project's
**Project settings** page; see the [User Guide](user-guide.md#project-settings). Archiving,
deleting and changing the key of a project can only be done by BuggyWuggy administrators.

Global permissions are stored in the login session. When a group gains or loses a global
permission, or someone joins or leaves such a group, the affected users are logged out and get
the new permissions when they log in again. This includes you when you change a group you are
a member of. To prevent lock-outs, you cannot take *Administer BuggyWuggy* away from a group
that is your only source of it; system administrators always keep every permission.

### Importing from other trackers

**Administration → Import** copies issues from another tracker into a BuggyWuggy project, with
their comments, attachments and history. Bugzilla (5.0 and newer), Forgejo (it also covers Gitea
and codeberg.org), GitHub (github.com and GitHub Enterprise Server), GitLab (gitlab.com and
self-managed), Jira (Cloud, or Server / Data Center at your own address), Redmine and Trac are
available.

**An import only reads.** It never changes anything in the source: the importer can only send
read (GET) requests, so it cannot write, close, comment or label anything there. A token with
read-only permissions is enough. The one exception is Trac, whose XML-RPC interface only takes
POST requests, also for reading: there the importer can only call a fixed list of methods that
read (such as `ticket.get` and `ticket.changeLog`); any other method is refused before anything
is sent.

#### Forgejo

The import reads one Forgejo repository through Forgejo's REST API. A wizard takes you through
four steps:

1. **Connection.** Enter the address of the Forgejo server (e.g. `https://codeberg.org`) and,
   optionally, an access token, and click **Test connection**.
   - Without a token only public repositories can be read.
   - Create a token in Forgejo under *Settings → Applications*, with read access to
     repositories, issues and users.
   - With the token of a Forgejo administrator BuggyWuggy also sees e-mail addresses, so more
     people are matched to their existing BuggyWuggy account.
2. **Repository.** Search for the repository, or type `owner/repository`, and choose the
   project to import into: a new project (key and name are filled in from the repository) or
   an existing one.
3. **Mapping.** BuggyWuggy counts the issues, comments and milestones, and lists the labels and
   the people who reported or are assigned to issues.
   - **Issue type** for issues whose labels do not set one.
   - **Labels**: each label is kept as a label (spaces become hyphens), sets the issue type or
     the priority, or is left out. Common names such as `bug`, `kind/bug` or `priority/high`
     are suggested.
   - **People**: choose the BuggyWuggy account for each person, or leave it on *Automatic*:
     BuggyWuggy then looks for an account with the same e-mail address, then with the same
     username, and otherwise creates a *placeholder*: an inactive account without password or
     e-mail address, named after the Forgejo user. Commenters are always matched automatically.
4. **Start.** The import runs in the background; its page shows the progress and a log, and
   has a **Cancel import** button. Only one import runs at a time.

What is imported:

| Forgejo | BuggyWuggy |
|---|---|
| Issue (not pull requests) | Issue with a **new number** in the project, in the order of the Forgejo numbers. The original (e.g. `forgejo/website#12`) with a link is shown as *Imported from* on the issue and in its history. |
| Title, description, deadline | Summary, description, due date |
| Open / closed | The project's first status, or *Reopened* when it was reopened; *Closed* with the default resolution. |
| Author, assignees | Reporter; the first assignee becomes the assignee, all assignees become watchers. |
| Labels | Labels, issue type or priority, as chosen in the wizard |
| Milestones | Project versions (closed milestones as released), and the fix version of their issues |
| Comments | Comments with their original author and date; commenters become watchers. |
| Attachments of issues and comments | Attachments. Larger files than `BUGGYWUGGY_MAX_ATTACHMENT_SIZE` are not copied; the text keeps a link to the original. |
| Dependencies | *Blocks* links |
| Pull requests that referenced the issue | **External links** on the issue, to the pull request on Forgejo |
| Timeline (title, labels, assignees, milestone, deadline, close, reopen) | History, with the original author and date |
| `#12`, `owner/repo#12` and attachment links in texts | The key of the imported issue and a link to the copied attachment; other references become links to Forgejo. Code blocks are not changed. |

Not imported: pull requests themselves, reactions, time tracking, project boards, and
whether an issue was pinned or locked. Forgejo shows deleted accounts as *Ghost*; they become one
placeholder named `Ghost`.

**Rate limits.** Forgejo servers can limit the number of requests (codeberg.org allows 2000 per
10 minutes). BuggyWuggy reads the limit from the server's answers and waits when it is nearly
used up. An import makes about three requests per issue, plus one per attachment.

#### GitHub

The GitHub import reads one repository through GitHub's REST API, with the same four-step wizard
as Forgejo.

1. **Connection.** The address is `https://github.com`, or the address of your GitHub
   Enterprise Server (BuggyWuggy finds its API under `/api/v3`). **A token is required**:
   without one GitHub allows only 60 requests an hour.
   - Create a *fine-grained personal access token* under *Settings → Developer settings →
     Personal access tokens*, for the repository, with read-only access to **Issues** and
     **Metadata**. A *classic* token needs the `repo` scope for private repositories and no scope
     for public ones.
   - The token never needs write access: the importer only reads.
2. **Repository**, 3. **Mapping** and 4. **Start** work as for Forgejo. GitHub's *issue types*
   (Bug, Feature, Task, …) are listed with the labels as `type:Bug` etc., so you can map them to
   a BuggyWuggy issue type. People are matched by the public e-mail address of their GitHub
   profile, then by username; display names come from their profile.

What is imported, where it differs from Forgejo:

| GitHub | BuggyWuggy |
|---|---|
| Closed as *completed* / *not planned* / *duplicate* | Resolution: the default one / *Won't Fix* / *Duplicate* (the default one if those do not exist) |
| Issue type | The issue type chosen for `type:Name` in the wizard |
| Sub-issues | A sub-issue of a standard issue becomes its **sub-task**; a sub-issue of an issue that became an epic gets it as parent. Deeper levels, and sub-issues in other repositories, become *relates to* links. |
| *Blocked by* | *Blocks* links |
| Images and files in texts (`github.com/user-attachments/…`, `user-images.githubusercontent.com/…`) | Attachments, and the text links to the copy. GitHub has no list of attachments; BuggyWuggy finds them in the texts. |
| Full links to issues of the repository | The key of the imported issue |
| Timeline (comments, title, labels, assignees, milestone, close, reopen, references from pull requests) | Comments and history with the original author and date |

GitHub sends files from temporary addresses elsewhere (a redirect); BuggyWuggy downloads them
**without** the token, which only goes to the API. Files of private repositories can therefore
often not be copied; the text keeps the link to the original and the log has a warning.

**Rate limits.** With a token GitHub allows 5000 requests an hour. An import makes about two
requests per issue (more for issues with sub-issues, dependencies or more than 100 timeline
entries), one per attachment and one per person. When the limit is used up, the import waits
until GitHub resets it, at most an hour; the status line shows until when.

#### GitLab

The GitLab import reads one project through GitLab's REST API (v4), with the same four-step
wizard.

1. **Connection.** The address is `https://gitlab.com`, or the address of your own GitLab
   (including its path, e.g. `https://example.org/gitlab`; BuggyWuggy finds the API under
   `/api/v4`). A token is optional for public projects on a GitLab of your own, but
   gitlab.com and most other large public servers (such as gitlab.gnome.org) show labels,
   comments and history only to logged-in users: there, any free account's token is enough.
   *Test connection* without a token already checks this.
   - Create a personal, project or group access token with the **`read_api`** scope under
     *Preferences → Access tokens*. It never needs more: the importer only reads.
   - With the token of a GitLab administrator, or for people who made their e-mail address
     public, BuggyWuggy can match people to existing accounts by e-mail address.
2. **Repository.** Projects can be in subgroups: search, or type the full path such as
   `group/subgroup/project`.
3. **Mapping** and 4. **Start** work as for Forgejo. GitLab's issue types other than *issue*
   (*incident*, *test case*, *task*) are listed as `type:Incident` etc.; they are left out unless
   you map them to an issue type.

What is imported, where it differs from Forgejo:

| GitLab | BuggyWuggy |
|---|---|
| Notes | Comments. **Internal notes are not imported**: BuggyWuggy has no internal comments, so they would become visible to everyone who can browse the project. |
| System notes and resource events (labels, milestone, close, reopen, title, assignees, due date) | History with the original author and date. Title, assignee and due date changes are read from GitLab's (English) system notes. |
| Confidential issues | Issues with the label `confidential`. BuggyWuggy has no confidential issues: restrict who can browse the project if needed. |
| Linked issues: *is blocked by* / *relates to* | *Blocks* / *Relates* links |
| Merge requests related to the issue | **External links** to the merge request |
| Uploads in texts (`/uploads/…`) | Attachments, and the text links to the copy. BuggyWuggy uses the uploads API (GitLab 17.4 and later) and otherwise the project's web address. |
| `#12`, `group/project#12`, links to `…/-/issues/12` | The key of the imported issue; other references become links to GitLab. `!12` (merge requests) is left as it is. |

Not imported: epics, iterations, weight, time tracking, award emoji and the tasks hierarchy
(child tasks are imported as separate issues, without a parent).

**Rate limits.** gitlab.com allows about 2000 API requests a minute with a token. An import makes
about seven requests per issue, plus one per attachment and one per person.

#### Jira

The Jira import reads one Jira project through REST API v2, which Jira Cloud and Jira Server /
Data Center both have, with the same four-step wizard.

1. **Connection.** The address of your Jira: `https://your-site.atlassian.net` for Jira Cloud,
   or that of your own server, including its path (e.g. `https://example.org/jira`). A link to a
   page of Jira (`…/browse/ABC-1`) works too.
   - **Jira Cloud:** your e-mail address and an **API token** (*Account settings → Security →
     Create and manage API tokens*). Choose a token without write permissions if you can.
   - **Jira Server / Data Center:** a **personal access token** (*Profile → Personal Access
     Tokens*, Jira 8.14 and later) with the username field left empty, or your username and
     password.
   - A public Jira can be read without logging in.
   The account only needs to browse the project; the importer never changes anything in Jira.
2. **Project.** Search for part of the name, or type the project key (`ABC`). For a new
   BuggyWuggy project the Jira key and name are suggested.
3. **Mapping.** Besides Jira's labels, the wizard lists Jira's **issue types** as `type:Bug`,
   its **priorities** as `priority/Major` and its **components** as `component/Backend`. The
   suggestions map issue types and priorities with the same (or a common) name, `type:Epic` to
   the epic type and components to components of the project; change what you like.
4. **Start** works as for Forgejo.

What is imported:

| Jira | BuggyWuggy |
|---|---|
| Summary, description, reporter, assignee, due date, created, updated, resolved | The same fields. Texts are converted from Jira's wiki markup to Markdown. |
| Status | Open or closed (status category *Done*); the resolution sets *Fixed*/*Done*, *Won't Fix* or *Duplicate*. Other statuses, such as *In Progress*, become open. |
| Issue type, priority, components, labels | As mapped in the wizard |
| Fix versions | Versions of the project; an issue gets its **first** fix version. Versions keep their release date and released state. |
| Comments | Comments. **Comments restricted to a role or group, and internal comments of Jira Service Management, are not imported**: they would become visible to everyone who can browse the project. |
| Changelog (summary, labels, type, priority, components, assignee, fix version, due date, status, resolution) | History with the original author and date |
| Sub-tasks, and the issues in an epic | Sub-tasks, and issues in the epic (when `type:Epic` is mapped to the epic type) |
| Issue links | Links of the link type with the same name (*Blocks*, *Duplicate*, *Cloners*, *Relates*), other link types as *Relates*. Links to issues that were not imported are left out. |
| Web links (remote links) | **External links**; links to pull or merge requests are marked as such |
| Attachments | Attachments; `!image.png!` and `[^file.pdf]` in texts point to the copy. |
| Issues with a security level | Issues with the label `restricted`. BuggyWuggy has no issue security: restrict who can browse the project if needed. |
| `ABC-12` and links to `…/browse/ABC-12` | The key of the imported issue. Keys of issues that were not imported link to Jira when they are of the same project, and stay as they are otherwise. |
| `[~user]` mentions | `@Display Name` |

Not imported: sprints, story points and other custom fields, work logs and time tracking,
votes, watchers and affects versions.

**Rate limits.** Jira Cloud limits requests per minute and asks clients to wait when they make
too many; the import then waits as long as Jira says. Jira Server and Data Center only limit
requests when an administrator turned that on. An import makes about three requests per issue
(the issue's changelog, comments and web links), plus one per attachment.

#### Redmine

The Redmine import reads one Redmine project (without its subprojects) through Redmine's REST
API, with the same four-step wizard.

1. **Connection.** The address of your Redmine, including its path (e.g.
   `https://example.org/redmine`); a link to a page of Redmine (`…/projects/web/issues`) works too.
   - Your **API key** (*My account → API access key*), with the username field left empty.
   - Or your username and password.
   - Public projects can be read without logging in.
   To log in, an administrator of Redmine must have turned on **Enable REST web service**
   (*Administration → Settings → API*).
   The account only needs to view the project's issues; the importer never changes anything in
   Redmine. With an administrator's key the import also sees logins and e-mail addresses, which
   helps to match people to existing accounts; without it, people are known by name only.
2. **Project.** Search for part of the name, or type the project identifier (`web`). For a new
   BuggyWuggy project the name is suggested, and a key made from it.
3. **Mapping.** Redmine has no labels: the wizard lists its **trackers** as `type:Bug`, its
   **priorities** as `priority/High` and its **categories** as `component/Backend`, with
   suggestions as for Jira.
4. **Start** works as for Forgejo.

What is imported:

| Redmine | BuggyWuggy |
|---|---|
| Subject, description, author, assignee, due date, created, updated, closed | The same fields. Textile is converted to Markdown (see below). |
| Status | Open or closed (statuses marked *Issue closed*); *Rejected* and the like set *Won't Fix*, an issue that duplicates another *Duplicate*. |
| Tracker, priority, category | As mapped in the wizard |
| Target version | The version; versions keep their due date, and *closed* versions are released. Versions shared by other projects are included. |
| Notes | Comments. **Private notes are not imported**: they would become visible to everyone who can browse the project. |
| Journal (subject, tracker, priority, category, assignee, target version, due date, status) | History with the original author and date |
| Subtasks | Sub-tasks, one level deep (deeper levels as *Relates* links) |
| Relations | *Blocks* and *precedes* become dependencies; *is duplicate of* a *Duplicate* link, *copied to* a *Cloners* link, *related to* a *Relates* link. Relations to issues that were not imported are left out; issues of other projects are found when that project was imported before. |
| Files | Attachments; `!image.png!`, `attachment:file.pdf` and Markdown links to a file name point to the copy. |
| Private issues | Issues with the label `restricted`. BuggyWuggy has no private issues: restrict who can browse the project if needed. |
| `#12` and links to `…/issues/12` | The key of the imported issue; numbers of issues that were not imported link to Redmine. |
| `[[Wiki page]]` | A link to the page in Redmine's wiki |

**Text formatting.** Redmine formats texts with Textile or Markdown, as its administrator chose,
and the API does not say which. The import guesses it from the descriptions of the project
(`h2.`, `"text":url` and `<pre>` mean Textile; `[text](url)` and code fences mean Markdown).
Textile is converted to Markdown; Markdown is kept.

Not imported: custom fields, start date, % done, estimated and spent time, watchers, the
description's history, and the project's wiki, forums, news, documents and repository.

**Rate limits.** Redmine has none of its own. An import makes one request per issue (with its
journal), one per person and one per attachment, plus a page of 100 issues at a time.

#### Trac

The Trac import reads the tickets of one Trac environment through the XML-RPC interface of the
**TracXMLRPC plugin** (`/rpc`, and `/login/rpc` when logging in), with the same four-step wizard.
Trac itself has no other interface that gives the original texts and the full change history.

1. **Connection.** The address of the environment (e.g. `https://trac.example.org/project`); a
   link to a ticket works too. Enter your Trac **username and password**, or leave both empty
   to read as anonymous. The user (or *anonymous*) needs the permissions **XML_RPC** and
   **TICKET_VIEW** (*Admin → Permissions*). Logging in uses basic authentication on `/login`; a
   Trac that only offers digest authentication or a login form cannot be read this way yet.
2. **Project.** An environment is one project: it is listed with the last part of its address
   as name.
3. **Mapping.** The wizard lists Trac's **ticket types** as `type:defect`, **priorities** as
   `priority/major`, **components** as `component/core` and **severities** as
   `severity/blocker`; **keywords** are labels. `defect` is suggested as *Bug*, `enhancement`
   as *Feature*.
4. **Start** works as for Forgejo.

What is imported:

| Trac | BuggyWuggy |
|---|---|
| Summary, description, reporter, owner, created, changed | The same fields. Texts are converted from WikiFormatting to Markdown. |
| Status | Open, or closed (status *closed*); the resolution sets *Fixed*, *Won't Fix* (*wontfix*, *invalid*, *worksforme*) or *Duplicate*. |
| Type, priority, component, severity, keywords | As mapped in the wizard |
| Milestone | The version; milestones keep their due date, and completed ones are released. |
| Comments | Comments |
| Change log (summary, type, priority, component, severity, keywords, owner, milestone, status) | History with the original author and date |
| Attachments | Attachments; `[[Image(file.png)]]` and `attachment:file.txt` point to the copy. |
| `#12`, `ticket:12` and links to `…/ticket/12` | The key of the imported ticket |
| `wiki:Page`, `milestone:1.0`, `source:…`, `changeset:…` | Links to the page in Trac |
| *blocked by* (MasterTickets plugin) | Dependencies |
| *parents* (Subtickets plugin) | Sub-tasks, one level deep |
| *sensitive* (SensitiveTickets plugin) | Issues with the label `restricted` |

People are known in Trac by username only (or by name and e-mail address, for tickets of people
who were not logged in); the import cannot read their names or e-mail addresses. Match them to
existing accounts in the wizard where needed.

Not imported: CC, version, other custom fields, time tracking, edits of comments, the
description's history, and the wiki and repository browser.

**Rate limits.** Trac has none. An import makes three requests per ticket (the ticket, its
attachments and its change log), plus one per attachment.

#### Bugzilla

The Bugzilla import reads the bugs of one Bugzilla **product** through Bugzilla's REST API
(Bugzilla 5.0 and newer), with the same four-step wizard.

1. **Connection.** The address of your Bugzilla, including its path (e.g.
   `https://bugzilla.example.org`); a link to a bug (`…/show_bug.cgi?id=12`) works too. The API is
   found under `/rest` or, where the web server does not rewrite that, `/rest.cgi`.
   - Your **API key** (*Preferences → API Keys*), with the login field left empty.
   - Or your login and password.
   - Public products can be read without logging in.
   Bugzilla takes these in headers of its own (`X-BUGZILLA-API-KEY`, or `X-BUGZILLA-LOGIN` and
   `X-BUGZILLA-PASSWORD`), which BuggyWuggy only sends to the Bugzilla itself. The account only
   needs to see the product's bugs; the importer never changes anything in Bugzilla.
2. **Project.** Search for part of the product name. For a new BuggyWuggy project the name is
   suggested, and a key made from it.
3. **Mapping.** The wizard lists the bug **type** as `type:defect` (before Bugzilla 5.2, which has
   no types: `type:enhancement` for bugs with severity *enhancement*), the **priority** as
   `priority/P1`, the **severity** as `severity/critical` and the **component** as
   `component/Backend`; **keywords** are labels. `defect` is suggested as *Bug*, `enhancement` as
   *Feature*, P1 to P5 as *Highest* to *Lowest*.
4. **Start** works as for Forgejo.

What is imported:

| Bugzilla | BuggyWuggy |
|---|---|
| Summary, reporter, assignee, deadline, opened, changed | The same fields |
| Comment 0 | The description |
| Status, resolution | Open or closed (the statuses Bugzilla marks as open or not); *WONTFIX*, *INVALID*, *WORKSFORME* and the like set *Won't Fix*, a duplicate *Duplicate*. The time it was closed comes from the activity. |
| Type, priority, severity, component, keywords | As mapped in the wizard |
| Target milestone | The version; inactive milestones are released |
| Other comments | Comments. **Private comments are not imported**: they would become visible to everyone who can browse the project. |
| Activity (summary, type, priority, severity, component, keywords, assignee, target milestone, deadline, status) | History with the original author and date |
| Depends on / blocks | Dependencies. Bugs of other products are found when that product was imported before. |
| Duplicate of | A *Duplicate* link |
| See also (bugs of the same Bugzilla) | A *Relates* link |
| Attachments | Attachments, except obsolete and private ones; "attachment 12" in a text points to the copy |
| Bugs in groups | Issues with the label `restricted`. BuggyWuggy has no private issues: restrict who can browse the project if needed. |
| `bug 12` and links to `…/show_bug.cgi?id=12` | The key of the imported issue |

**Text.** Bugzilla comments are plain text (Bugzilla 5.2 can also use Markdown, which is kept).
The import escapes what Markdown would read as formatting, so `*`, `_`, `#` and `<b>` stay as
they were typed; quoted lines (`> …`) stay quotes, and indented lines (stack traces) are shown as
code.

**People.** Bugzilla knows people by login, usually their e-mail address. The part before the @
becomes the login of a placeholder, the address its e-mail address, so the wizard matches people
to existing accounts by e-mail address.

Not imported: CC, URL, whiteboard, version, platform, OS, flags, votes, time tracking, custom
fields and comment tags.

**Rate limits.** Bugzilla has none of its own. An import makes three requests per bug (its
comments, activity and attachments), one per person and one per attachment, plus a page of 100
bugs at a time.

#### Progress, status line and log

While an import runs, **every administration page shows a status line** with its progress and
what it is doing, e.g. *Issue 12 of 140: #456*, or *Waiting for the rate limit of api.github.com
until 14:05*. The import's own page has the counters, a **Cancel import** button and the log.

The log can be downloaded as a text file with **Download log** on the import's page
(`GET /api/v1/admin/import/jobs/{id}/log.txt`). Every log line is also written to the
application log, under the logger `org.buggywuggy.import`, so with systemd:

```bash
journalctl -u buggywuggy | grep "Import job"
```

Nobody gets e-mail about imported issues. Dates are the original ones; the history entry
*Imported from* has the date and author of the import.

**Running again.** **Run again** on an import's page repeats it with the same settings. Issues
that were imported before are not imported again (also not when they changed in the source); only
their new comments are added, and new issues are imported. When some issues failed, the import
ends as *Failed* with a warning per issue; running it again imports just those. Issues of a
deleted project count as not imported.

**The access token is never stored**: not in the database and not in the log. BuggyWuggy keeps
it in memory for 24 hours, so **Run again** works without typing it; after a restart you are
asked for it again.

### Donation

BuggyWuggy is free software, but developing and supporting it takes time. Organisations can
support it with an optional annual contribution: a donation, invoiced per year, that comes with a
*donation code* valid for that year. Below the login form, **Support BuggyWuggy's continued
development** shows whether this installation supports the project:

- *Not supporting yet — Become a BuggyWuggy supporter!* when no donation code was ever entered;
- *Active supporter — Thank you for supporting BuggyWuggy!* while a donation code is valid;
- *Support expired — Renew your annual contribution to continue supporting BuggyWuggy
  development.* when all donation codes have expired.

*Click here for more information* explains why donations matter: free software is about
freedom, not price; real people work on BuggyWuggy; and donations keep the project going and pay
for new features. The link *Request a quotation* leads, after logging in as an administrator,
to **Administration → Donation** (the administration pages show the same request while there is
no valid donation code):

- **Request a quotation**: choose the country (type to search) and the number of users (by
  default the number of active accounts). The suggested contribution is 7 € per user per month in the
  Netherlands, and elsewhere in proportion to the price of a Big Mac there (The Economist's Big
  Mac index, included with BuggyWuggy, latest survey): 7 € × Big Mac in the country / Big Mac in
  the Netherlands, in the local currency. Euro countries without a price of their own use the
  euro area's; countries without any price pay 7 €. The page shows the yearly total in the local
  currency. Fill in the company, contact name, e-mail address and phone number (it starts with
  the dialling code of the chosen country) and choose **Send quotation request email**.
  BuggyWuggy mails the request, with a unique *challenge*, to sales@buggywuggy.org through its
  own mail server. When mail is not configured yet, the request waits until it is, and the page
  offers the text to send yourself.
- **Enter the donation code**: the code you receive with the invoice answers the challenge of
  your request. Once it is entered, the login page shows this installation as an active
  supporter until the code expires, normally after a year. A month before that, BuggyWuggy
  mails sales@buggywuggy.org once, to discuss a renewal. BuggyWuggy keeps the code: it proves
  in feedback mails that they come from a supporting installation. A code entered before
  BuggyWuggy had the feedback form was not kept; **enter the same code again** to make
  **Help → Send feedback** work.

While a donation code is valid, every user can send feedback to feedback@buggywuggy.org with
**Help → Send feedback** (see the user guide). The mail goes through BuggyWuggy's own mail
server, with Reply-To set to the user's e-mail address. It contains the user's name, user name
and e-mail address, the company of the donation, the address and version of this installation,
and the challenge and donation code, in the text and in the headers `X-BuggyWuggy-Donor-Challenge`
and `X-BuggyWuggy-Donor-Code`. Header `X-BuggyWuggy-Feedback` is `compliment`, `bug` or
`feature`. The shared account of a demo build cannot send feedback.

The request mail contains the details you entered, the price, and the address of this
installation (`buggywuggy.base-url`).

The explanation on the login page includes the xkcd comic 2347 "Dependency" by Randall Munroe,
licensed under CC BY-NC 2.5 and shown with that attribution. It is not part of BuggyWuggy:
after its first start, BuggyWuggy downloads it once from imgs.xkcd.com into
`<data directory>/cache/xkcd/` and serves it itself, so visitors never contact xkcd.com. When
the download fails (no internet access), it tries again once a day and the login page shows only
the attribution meanwhile. `BUGGYWUGGY_DONATION_COMIC_DOWNLOAD=false` switches the download off.

### Automating with the REST API

Everything in the web interface can also be done through the REST API, for example from
scripts. The interactive API documentation is at `https://<your-server>/api/docs`; when you are
logged in in the same browser, **Try it out** uses your session. The administration operations
are under `/api/v1/admin/` (users, groups, global permissions); project configuration is under
`/api/v1/projects/{key}/` (components, versions, roles).

### Default groups, roles and permissions

| Group | Global permissions |
|---|---|
| `administrators` | Administer BuggyWuggy, create projects |
| `users` | Browse users (needed to pick users in search filters) |

Every new project gets three **project roles**. By default the group `users` is in the role
*Users* and the group `administrators` is in the role *Administrators*:

| Project role | What its members can do |
|---|---|
| Users | See the project, create issues, comment, edit and delete their own comments |
| Developers | Everything Users can do, plus edit, assign and transition issues, be assigned issues, link issues and manage watchers |
| Administrators | Everything, including deleting issues, editing and deleting anyone's comments, and configuring the project |

Note that ordinary users can see every project but cannot work on issues until you add
them (or a group) to the *Developers* role of a project. To hide a project from everyone
except its members, remove the group `users` from the project's *Users* role.

Changes to project roles take effect immediately; users do not need to log in again.

## Backup and restore

BuggyWuggy keeps its data in two places, and a complete backup contains both:

- the **PostgreSQL database**: everything except the contents of attached files;
- the **data directory** (`BUGGYWUGGY_DATA_DIR`): the attached files, and with
  [two-factor login](#two-factor-login) the key `secrets/two-factor.key`. Its `cache`
  directory need not be backed up: BuggyWuggy downloads its contents again when they are
  missing.

The key of two-factor login protects the secrets in the database: keep backups of the
`secrets` directory apart from the database backups where you can, and only readable for root.

### Database

Back up with `pg_dump`. It produces a consistent snapshot while BuggyWuggy keeps running:

```sh
pg_dump --format=custom --file=buggywuggy-$(date +%F).dump \
        --host=localhost --username=buggywuggy buggywuggy
```

Restore into an empty database, with BuggyWuggy stopped:

```sh
sudo systemctl stop buggywuggy
sudo -u postgres psql -c "drop database buggywuggy"
sudo -u postgres psql -c "create database buggywuggy owner buggywuggy"
pg_restore --dbname=buggywuggy --host=localhost --username=buggywuggy --no-owner \
           buggywuggy-2026-11-11.dump
sudo systemctl start buggywuggy
```

### Attachment files

Files are stored under `<data directory>/attachments/` with generated names; the database
knows which file belongs to which attachment. Files are only ever added or deleted, never
changed, so a plain copy is safe while BuggyWuggy runs. Back up the database **first** and the
files right after; then every attachment in the database backup has its file:

```sh
rsync -a --delete /var/lib/buggywuggy/ /backup/buggywuggy-files/
```

Restore the files to the data directory before starting BuggyWuggy, owned by the
`buggywuggy` user. Files that are in the copy but no longer in the database do no harm.

Test your restore procedure regularly.

## Upgrading

1. Make a backup (see above).
2. Build or download the new `buggywuggy.jar`.
3. Stop BuggyWuggy, replace the jar and start it again.

A new version can have new settings. Your configuration file is never changed; to see all
settings of the new version, print the template with
`java -jar buggywuggy.jar config-template` and compare.

Database changes are applied automatically at startup and are recorded in the table
`flyway_schema_history`. Do not downgrade to an older jar after an upgrade has changed the
database; restore the backup instead.

### Automatic deployment to a test server

The CI workflow (`.forgejo/workflows/ci.yml`) can install every successful build of `main` on
a test server: after the job `build`, the job `deploy-test` copies the jar over SSH and runs
[`deploy/buggywuggy-deploy`](../deploy/buggywuggy-deploy) on the server with sudo. That script:

1. makes a backup of the database with `pg_dump` in `/var/backups/buggywuggy/` (only readable
   for root; the last 10 are kept). If the backup fails, it stops: nothing is installed and the
   running version keeps running;
2. installs the jar as `/opt/buggywuggy/buggywuggy.jar`, keeping the old one as
   `buggywuggy.jar.previous`;
3. restarts the service and waits up to three minutes until `/actuator/health` reports `UP`.
   If it does not, it puts the previous jar back, restarts again, names the backup of step 1
   and the job fails.

The data on the test server is kept: a new version only applies its database migrations, as
with any [upgrade](#upgrading). Those migrations are not undone when the previous jar is put
back. If the previous version does not start any more after a failed deploy, restore the
backup that the job log names (see [Database](#database), with the dump from
`/var/backups/buggywuggy/`).

**The test server.** Install BuggyWuggy as described above (Java 25, PostgreSQL,
[systemd service](#running-as-a-service-systemd), optionally a
[reverse proxy](#https-with-a-reverse-proxy)), `curl` and `pg_dump` (package
`postgresql-client`, at least the version of the database server; it comes with the
`postgresql` package). Then create a user for the deployments that may only run the deploy
script as root:

```sh
sudo useradd --create-home --shell /bin/bash deploy
sudo install -m 755 -o root -g root deploy/buggywuggy-deploy /usr/local/sbin/buggywuggy-deploy
echo 'deploy ALL=(root) NOPASSWD: /usr/local/sbin/buggywuggy-deploy' \
  | sudo install -m 440 -o root -g root /dev/stdin /etc/sudoers.d/buggywuggy-deploy
sudo visudo -cf /etc/sudoers.d/buggywuggy-deploy
```

The script reads the port and the database settings (`BUGGYWUGGY_PORT`, `BUGGYWUGGY_DB_URL`,
`BUGGYWUGGY_DB_USER`, `BUGGYWUGGY_DB_PASSWORD`) from `/etc/buggywuggy/buggywuggy.env`; it reads
that file, it does not execute it. The service, paths and the number of backups to keep are at
the top of the script.

**An SSH key for the workflow.** Make a key pair without a passphrase, only for this, and allow
it for the user `deploy` on the test server:

```sh
ssh-keygen -t ed25519 -N '' -C 'buggywuggy ci deploy' -f deploy_key
sudo install -d -m 700 -o deploy -g deploy ~deploy/.ssh
sudo install -m 600 -o deploy -g deploy deploy_key.pub ~deploy/.ssh/authorized_keys
ssh-keyscan TEST-SERVER > known_hosts    # check the fingerprint with ssh-keygen -lf known_hosts
```

**Forgejo.** In the repository settings, under *Actions*:

| Kind | Name | Value |
|---|---|---|
| Variable | `DEPLOY_HOST` | The host name of the test server. Without it the job `deploy-test` is skipped. |
| Variable | `DEPLOY_USER` | Optional; default `deploy`. |
| Secret | `DEPLOY_SSH_KEY` | The contents of `deploy_key` (the private key). |
| Secret | `DEPLOY_KNOWN_HOSTS` | The contents of `known_hosts`: the job only connects to a server with this host key. |

Then delete the local `deploy_key`. The Forgejo runner must be able to reach the test server
on port 22. The job runs on pushes to `main` only; pull requests and other branches are built
and tested, not deployed.

## Monitoring

| URL | Purpose |
|---|---|
| `/actuator/health` | Returns `{"status":"UP"}` when the application and its database are healthy. Use it for load balancers and monitoring. |
| `/actuator/info` | Basic application information. |
| `/api/docs` | Interactive API documentation. |
| `/api/openapi` | The OpenAPI description of the REST API, for generating API clients. |

The application logs to standard output. With systemd, view it with `journalctl -u buggywuggy`.

## Troubleshooting

**The application does not start: "Connection refused" or "password authentication failed".**
Check `BUGGYWUGGY_DB_URL`, `BUGGYWUGGY_DB_USER` and `BUGGYWUGGY_DB_PASSWORD`, and check that
PostgreSQL accepts connections from this host (`pg_hba.conf`).

**"permission denied to create extension pg_trgm".**
Enable the extension once as a superuser (see [Preparing the database](#preparing-the-database))
and start again.

**No e-mails arrive.**
Open **Administration → E-mail**. If it says that sending mail is not configured, set
`buggywuggy.mail.host` and restart. Otherwise use **Send test mail**: an error message from the
mail server is shown right away. Mails that failed repeatedly are listed with their error and
can be sent again. Also check that users have an e-mail address, did not turn notifications
off in their profile, and remember that mails are combined and sent after a minute of quiet.

**The log says "a template could not be written".**
BuggyWuggy found no configuration file and may not create one at the location in the message.
This is harmless: it starts with the built-in defaults and the environment variables. Create
the file yourself with `java -jar buggywuggy.jar config-template`, see
[Configuration file](#configuration-file).

**My changes in the configuration file have no effect.**
Check on **Administration → E-mail** which configuration file is used, that you removed the `#`
in front of the setting *and* in front of the keys above it, that the indentation is unchanged,
and that you restarted BuggyWuggy. Also check that the setting is not set as an environment
variable as well (for systemd in `/etc/buggywuggy/buggywuggy.env`).

**An import fails with "The server refused the access token" or "may not read".**
The token is wrong, expired, or lacks read access to repositories or issues. Create a new one in
Forgejo under *Settings → Applications*, on GitHub under *Settings → Developer settings*, or in
GitLab under *Preferences → Access tokens* (scope `read_api`). For Jira Cloud, enter the
e-mail address of the account that owns the API token; for a Jira personal access token, leave
the username empty. Redmine ignores API keys, and usernames and passwords, until an administrator turns on
*Enable REST web service* (*Administration → Settings → API*). Trac answers "No XML-RPC
interface" when the TracXMLRPC plugin is missing or turned off, and "may not read" when the
user lacks the XML_RPC permission. For a
private repository the
token's user must be able to see the repository; for a GitHub organisation that requires it, the
token must also be approved for (or authorised with SSO by) the organisation.

**An import seems to stand still.**
Look at the status line: it probably waits for the rate limit of the source, and says until
when. Imports of GitHub wait at most an hour at a time. The log on the import's page, or
`journalctl -u buggywuggy`, shows what it did last.

**Attachments of a GitHub import were not copied.**
GitHub only hands out the files of private repositories to a logged-in browser. The texts keep
the links to the originals; the log lists each file that was not copied.

**An import is "Interrupted".**
BuggyWuggy was stopped while the import ran. Use **Run again**: what was already imported is
skipped.

**I lost the setup token.**
Restart BuggyWuggy. As long as no administrator has been created, a new token is written to the
log at every start. Alternatively set `BUGGYWUGGY_SETUP_TOKEN`.

**Logging in works, but every change fails with "403 Forbidden".**
The browser must send back the `XSRF-TOKEN` cookie as a header. This fails when a proxy strips
cookies or headers, or when BuggyWuggy is reached through different host names. Make sure
users always use one URL, and that the proxy passes cookies unchanged.

**Users are logged out immediately behind HTTPS, or cannot log in over plain HTTP.**
`BUGGYWUGGY_SECURE_COOKIES=true` only works over HTTPS. Use `false` for plain-HTTP test
installations.

**"Too many failed login attempts".**
Wait 15 minutes, or have an administrator reset the password.
