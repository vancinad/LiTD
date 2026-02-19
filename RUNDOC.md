# LiTD Run Documentation

This guide explains how to set up and run LiTD locally as a new contributor.

## 1) MongoDB Requirements

- MongoDB must run as a **replica set** (transactions are used by pairing workflows).
- Supported local setup in this repo:
  - Docker image: `mongo:6.0` (see `docker-compose.yml`).
  - Replica set name: `rs0` (initialized by `docker/mongo-init.js`).
- Default local connection:
  - Host/port: `localhost:27017`
  - Connection string example:
    - `mongodb://localhost:27017/?replicaSet=rs0`
- LiTD automatically runs Mongo migrations at startup (creates collections/indexes and backfills legacy fields).

### Start MongoDB (recommended)

```bash
docker compose up -d
```

### Verify MongoDB replica set is healthy

```bash
docker exec -it litd-mongo-1 mongosh --eval "rs.status().ok"
```

Expected result is `1`.

## 2) Application Server Requirements

- JDK: 17+ recommended.
- sbt: `1.9.8` (repo default in `project/build.properties`).
- Scala target in this codebase: `2.13.18` (from `build.sbt`).
- Network access from app process to:
  - MongoDB
  - `https://lichess.org` (or configured Lichess base URL)

## 3) Required External Configuration Files

LiTD loads configuration from Typesafe Config (`ConfigFactory.load()`), which merges:
- `src/main/resources/application.conf` (checked into repo; base config)
- Environment variables (for substitutions)
- Optional external override file via `-Dconfig.file=...`

### 3.1 Base file (already in repo): `src/main/resources/application.conf`

This file is required by the app and already exists. It expects Mongo env vars and supports optional Lichess env var overrides.

### 3.2 Optional local override file (recommended): `conf/local.conf`

Create a local file if you do not want to rely only on environment variables.

Example:

```hocon
litd {
  http {
    host = "127.0.0.1"
    port = 8080
  }

  mongodb {
    uri = "mongodb://localhost:27017/?replicaSet=rs0"
    database = "litd_dev"
  }

  auth {
    # Must decode to exactly 32 bytes for AES-256-GCM.
    encryptionKeyBase64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    lichess {
      baseUrl = "https://lichess.org"
      clientId = "your-lichess-oauth-client-id"
      clientSecret = "your-lichess-oauth-client-secret"
      redirectUri = "http://localhost:8080/auth/lichess/callback"
      teamId = "your-lichess-team-id"
      scope = "preference:read"
      requestTimeoutMillis = 5000
      retryCount = 3
    }

    session {
      cookieName = "litd_session"
      secureCookie = false
      maxAgeSeconds = 2592000
    }

    stateTtlSeconds = 300
    membershipCacheTtlSeconds = 300
  }

  challengeWorker {
    enabled = true
    pollIntervalSeconds = 20
    batchSize = 100
  }
}
```

Run with it:

```bash
sbt -Dconfig.file=conf/local.conf run
```

## 4) Required Environment Variables

The following env vars are required unless you provide equivalent values in an external config file:

- `LITD_DB_CONNECT_STRING`
  - MongoDB URI, should include replica set for local dev.
  - Example: `mongodb://localhost:27017/?replicaSet=rs0`
- `LITD_DB_DBNAME`
  - MongoDB database name.
  - Example: `litd_dev`

The following are strongly required for real OAuth/team-gated auth usage:

- `LITD_LICHESS_CLIENT_ID`
- `LITD_LICHESS_CLIENT_SECRET`
- `LITD_LICHESS_REDIRECT_URI`
- `LITD_TEAM_ID`

Security-sensitive auth encryption key:

- `LITD_AUTH_ENCRYPTION_KEY_BASE64`
  - Must decode to exactly 32 bytes.
  - If omitted, app falls back to an insecure development default from `application.conf`.
  - Generate one example value:
    ```bash
    openssl rand -base64 32
    ```

### Example shell setup

```bash
export LITD_DB_CONNECT_STRING='mongodb://localhost:27017/?replicaSet=rs0'
export LITD_DB_DBNAME='litd_dev'
export LITD_AUTH_ENCRYPTION_KEY_BASE64='replace-with-32-byte-base64'
export LITD_LICHESS_CLIENT_ID='replace-with-client-id'
export LITD_LICHESS_CLIENT_SECRET='replace-with-client-secret'
export LITD_LICHESS_REDIRECT_URI='http://localhost:8080/auth/lichess/callback'
export LITD_TEAM_ID='replace-with-team-id'
```

## 5) Run LiTD

1. Start Mongo replica set:
   ```bash
   docker compose up -d
   ```
2. Set required env vars (or use `-Dconfig.file=conf/local.conf`).
3. Start app:
   ```bash
   sbt run
   ```
4. Verify health:
   ```bash
   curl http://localhost:8080/health
   ```
   Expected response: `ok`

## 6) First-Run Notes

- On startup, LiTD executes migrations in `MigrationRunner.default(...)`:
  - `InitialCollectionsAndIndexesMigration`
  - `OAuthAndTeamGateMigration`
  - `ChallengeIssuanceMigration`
  - `SchemaEvolutionBackfillMigration`
- Applied migration records are stored in Mongo collection `_migrations`.
- Challenge sync worker starts automatically when `litd.challengeWorker.enabled = true`.

## 7) Developer Verification Commands

Run these before submitting changes:

```bash
sbt compile
sbt test
```

Optional integration tests:

```bash
LITD_RUN_INTEGRATION_TESTS=true sbt test
```

Integration tests require Docker/Testcontainers access for MongoDB.
