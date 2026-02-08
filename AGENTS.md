# AGENTS.md

## Codex Instructions for This Repository (MongoDB)

### General Rules
- Follow SPEC.md exactly.
- Do not change persisted document shapes without adding a migration strategy (Mongo migration script).
- Prefer small, incremental commits.
- Do not invent features not present in the spec.

### Tech Stack
- Language: Scala 3
- HTTP: Akka HTTP
- JSON: Circe
- DB: MongoDB using the official MongoDB Scala driver (reactive/async)
- Migrations: custom migration runner (versioned scripts) or Mongock (if desired)
- Async/background work: Akka Typed actors or scheduled jobs
- Build: sbt

### MongoDB Guidance
- Prefer normalized collections as specified (registrations/rounds/pairings/byes).
- Use denormalized fields where indicated (tournamentId, roundNumber on pairings/byes) to avoid multi-collection joins.
- Use transactions for multi-step writes; require replica set in dev/test to enable them.
- Create all indexes specified in SPEC.md.

### Coding Standards
- Use immutable data structures where practical
- Explicit types for public APIs
- Avoid blocking calls on request threads
- All external calls (Lichess API) must have timeouts and retries

### Required Checks
Before marking a task complete:
- `sbt test` passes
- `sbt compile` passes
- Index creation verified in code (startup migration) or via migration scripts
- API endpoints documented in code comments

### Testing Expectations
- Unit tests for pairing eligibility, effectiveMaxRounds calculation
- Integration tests using a test MongoDB (Testcontainers recommended)
- Mock Lichess API in tests; do not call real API

### Background Workers
- Event-stream ingestion must be idempotent
- Worker failures must not corrupt pairing state
