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
6. [Running BuggyWuggy](#running-buggywuggy)
7. [First-time setup](#first-time-setup)
8. [Running as a service (systemd)](#running-as-a-service-systemd)
9. [HTTPS with a reverse proxy](#https-with-a-reverse-proxy)
10. [Administration](#administration)
11. [Backup and restore](#backup-and-restore)
12. [Upgrading](#upgrading)
13. [Monitoring](#monitoring)
14. [Troubleshooting](#troubleshooting)

## Overview

BuggyWuggy is a single Java application (one `.jar` file) that contains both the server and
the web interface. It stores all its data in PostgreSQL. It needs no other services: no search
engine, no message queue and no cache.

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

BuggyWuggy is configured with environment variables:

| Variable | Default | Description |
|---|---|---|
| `BUGGYWUGGY_DB_URL` | `jdbc:postgresql://localhost:5432/buggywuggy` | JDBC URL of the database. |
| `BUGGYWUGGY_DB_USER` | `buggywuggy` | Database user. |
| `BUGGYWUGGY_DB_PASSWORD` | `buggywuggy` | Database password. **Always set this in production.** |
| `BUGGYWUGGY_PORT` | `8080` | HTTP port the application listens on. |
| `BUGGYWUGGY_SESSION_TIMEOUT` | `8h` | How long an idle login stays valid, e.g. `30m`, `8h`, `7d`. |
| `BUGGYWUGGY_SECURE_COOKIES` | `false` | Set to `true` when users reach BuggyWuggy over HTTPS (recommended). The session cookie is then only sent over HTTPS. |
| `BUGGYWUGGY_SETUP_TOKEN` | *(random, written to the log)* | Fixed token for first-time setup. Useful for automated installations. |

Because BuggyWuggy is a Spring Boot application, any standard Spring Boot setting can also be
given as an environment variable. One that is useful behind a reverse proxy:

| Variable | Recommended value | Description |
|---|---|---|
| `SERVER_FORWARD_HEADERS_STRATEGY` | `framework` | Trust the `X-Forwarded-*` headers of your reverse proxy, so the application knows the original scheme (HTTPS) and host. Only set this when BuggyWuggy can **only** be reached through the proxy. |

Login protection is built in: after 5 failed login attempts for the same username from the
same address, further attempts are refused for 15 minutes.

## Running BuggyWuggy

```sh
export BUGGYWUGGY_DB_URL=jdbc:postgresql://localhost:5432/buggywuggy
export BUGGYWUGGY_DB_USER=buggywuggy
export BUGGYWUGGY_DB_PASSWORD=choose-a-strong-password
java -jar /opt/buggywuggy/buggywuggy.jar
```

On first start the database schema is created. When the log shows `Started BuggyWuggyApplication`,
open `http://<server>:8080` in a browser.

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

Create a system user and an environment file:

```sh
sudo useradd --system --home /opt/buggywuggy --shell /usr/sbin/nologin buggywuggy
sudo install -d -o buggywuggy -g buggywuggy /opt/buggywuggy
sudo install -m 600 -o root -g root /dev/null /etc/buggywuggy.env
```

`/etc/buggywuggy.env`:

```sh
BUGGYWUGGY_DB_URL=jdbc:postgresql://localhost:5432/buggywuggy
BUGGYWUGGY_DB_USER=buggywuggy
BUGGYWUGGY_DB_PASSWORD=choose-a-strong-password
BUGGYWUGGY_PORT=8080
BUGGYWUGGY_SECURE_COOKIES=true
SERVER_FORWARD_HEADERS_STRATEGY=framework
```

`/etc/systemd/system/buggywuggy.service`:

```ini
[Unit]
Description=BuggyWuggy issue tracker
After=network-online.target postgresql.service
Wants=network-online.target

[Service]
User=buggywuggy
Group=buggywuggy
EnvironmentFile=/etc/buggywuggy.env
ExecStart=/usr/bin/java -Xmx1g -jar /opt/buggywuggy/buggywuggy.jar
SuccessExitStatus=143
Restart=on-failure
# Hardening
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
```

Start it and follow the log (this is also where the setup token appears):

```sh
sudo systemctl daemon-reload
sudo systemctl enable --now buggywuggy
sudo journalctl -u buggywuggy -f
```

## HTTPS with a reverse proxy

Run BuggyWuggy behind a reverse proxy that handles TLS, and let it listen only on localhost or
an internal network. Example for nginx:

```nginx
server {
    listen 443 ssl;
    server_name bugs.example.org;

    ssl_certificate     /etc/letsencrypt/live/bugs.example.org/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/bugs.example.org/privkey.pem;

    client_max_body_size 50m;

    location / {
        proxy_pass         http://127.0.0.1:8080;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_set_header   X-Forwarded-Host  $host;
    }
}

server {
    listen 80;
    server_name bugs.example.org;
    return 301 https://$host$request_uri;
}
```

With HTTPS in place, set `BUGGYWUGGY_SECURE_COOKIES=true`.

BuggyWuggy must be served at the root of a host name (`https://bugs.example.org/`). Serving
it under a sub-path such as `https://example.org/bugs/` is not supported yet.

## Administration

The web interface does not have administration screens yet. Until it does, administrative
tasks are done through the REST API. The easiest way is the interactive API documentation
built into BuggyWuggy:

1. Log in to BuggyWuggy in the browser as an administrator.
2. In the same browser, open `https://<your-server>/api/docs`.
3. Open an operation, choose **Try it out**, fill in the fields and choose **Execute**. Your
   login session is used automatically.

Common tasks:

| Task | Operation |
|---|---|
| List users | `GET /api/v1/admin/users` |
| Create a user | `POST /api/v1/admin/users` with `username`, `displayName`, `email`, `password` (12–256 characters). New users are added to the `users` group. |
| Deactivate a user | `PATCH /api/v1/admin/users/{id}` with `{"active": false}`. The user is logged out everywhere. |
| Reset a password | `PUT /api/v1/admin/users/{id}/password` |
| Create a group | `POST /api/v1/admin/groups` |
| Add a user to a group | `PUT /api/v1/admin/groups/{id}/members/{userId}` |
| Add a user to a project role | `PUT /api/v1/projects/{key}/roles/{roleId}/users/{userId}` (role ids: `GET /api/v1/project-roles`) |
| Add a group to a project role | `PUT /api/v1/projects/{key}/roles/{roleId}/groups/{groupId}` |
| Manage components | `/api/v1/projects/{key}/components` |
| Manage versions | `/api/v1/projects/{key}/versions` |
| Rename, archive or change a project's key | `PATCH /api/v1/projects/{key}` |
| Delete a project (and all its issues) | `DELETE /api/v1/projects/{key}` |

Users can change their own name, e-mail, language and time zone with `PATCH /api/v1/myself`,
and their password with `PUT /api/v1/myself/password`.

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

Permission changes take effect immediately; users do not need to log in again.

## Backup and restore

All data lives in the PostgreSQL database, so a database backup is a complete backup.
(When attachments are added in a future version, their files will need to be backed up too.)

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

Test your restore procedure regularly.

## Upgrading

1. Make a backup (see above).
2. Build or download the new `buggywuggy.jar`.
3. Stop BuggyWuggy, replace the jar and start it again.

Database changes are applied automatically at startup and are recorded in the table
`flyway_schema_history`. Do not downgrade to an older jar after an upgrade has changed the
database; restore the backup instead.

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
