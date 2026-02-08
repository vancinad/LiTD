# PLAN.md

## Swiss Tournament Manager – Implementation Plan (MongoDB)

### Milestone 0 – Scaffold
- sbt project
- Akka HTTP server with health endpoint
- MongoDB client wiring (official Scala driver)
- Config loading
- Local dev Docker compose for MongoDB replica set (to support transactions)

### Milestone 1 – Collections + Indexes + Migration Framework
- Implement all collections from SPEC.md
- Index creation (migration scripts)
- Basic repository layer (CRUD)

### Milestone 2 – Lichess OAuth & Team Gate
- OAuth flow
- Token storage (encrypted) in `oauthTokens`
- TEAM_ID membership verification (+ optional cache collection with TTL index)
- Auth middleware

### Milestone 3 – Tournament & Registration
- Create tournament
- Register players
- Late registration effectiveRound logic
- Withdraw/reactivate players

### Milestone 4 – Round Generation (Transactional)
- Generate Round 1
  - compute effectiveMaxRounds and persist on tournament
  - audit + notify (response payload + auditEvents)
- Generate subsequent rounds
- Pairing-allocated byes (insert into `byes` collection)
- TD-granted byes (insert into `byes` collection)

### Milestone 5 – Challenge Issuance
- Issue challenge endpoint
- Persist challengeId on pairing
- Background worker to capture gameStart and gameId (event stream)
- Optional: start worker activity based on change stream or polling

### Milestone 6 – Results & Round End
- Refresh results (query Lichess games)
- End round (double-forfeit updates)
- Override results (overrides collection + pairing update)

### Milestone 7 – Standings & Crosstable (Read models)
- Standings computation from pairings + byes
- Crosstable view
- Public endpoints

### Milestone 8 – Hardening
- Error handling
- Idempotency checks
- Documentation cleanup
- Migration scripts for schema evolution

---
Each milestone should be implemented in a separate Codex session.
