# Tournament Data Model Specification (Revised)

This document revises the original `spec.md` to address scalability, correctness, and query-efficiency concerns identified when modeling tournaments with up to **500 players**.

Key changes focus on:
- Enforcing correct pairing constraints (no duplicate players per round)
- Improving player-centric query performance
- Supporting efficient Swiss-system pairing logic
- Adding guardrails against pathological configurations
- Introducing derived per-player tournament state for scalable standings and pairing

---

## Design Goals

1. Correctness first: impossible states (e.g., a player paired twice in one round) must be prevented by schema + indexes, not just application logic.
2. Optimize for common access paths:
   - “All games for player X in this tournament up to round R”
   - “Has player A already played player B?”
   - “Current standings after round R”
3. Keep pairing and standings updates **incremental** and **transactional**.
4. Remain compatible with MongoDB multi-document transactions.

---

## Core Concepts

- **Tournament**: overall event container
- **Registration**: a player’s participation and eligibility
- **Round**: a numbered Swiss round
- **Pairing**: a game between two players in a round
- **Bye**: an unpaired player with an assigned score
- **Override**: manual TD intervention
- **PlayerTournamentState**: derived, per-player aggregate state
- **AuditEvent**: immutable audit log

---

## Tournaments

```json
{
  _id: ObjectId,
  name: string,
  status: "draft" | "active" | "completed",
  configuredMaxRounds: number,
  effectiveMaxRounds: number,
  createdAt: Date,
  updatedAt: Date
}
```

### Constraints

- `configuredMaxRounds` MUST be capped at a reasonable operational limit (default: 15).
- Larger values require an explicit admin override.

```text
configuredMaxRounds <= 15  (unless adminOverride = true)
```

Indexes:
- `{ status: 1 }`

---

## Registrations

```json
{
  _id: ObjectId,
  tournamentId: ObjectId,
  lichessUserId: string,
  status: "registered" | "withdrawn" | "disqualified",
  effectiveRound: number,
  createdAt: Date
}
```

Indexes:
- **Unique** `{ tournamentId: 1, lichessUserId: 1 }`
- `{ tournamentId: 1, status: 1, effectiveRound: 1 }`  // eligibility queries

---

## Rounds

```json
{
  _id: ObjectId,
  tournamentId: ObjectId,
  roundNumber: number,
  status: "pending" | "active" | "completed",
  createdAt: Date,
  completedAt: Date
}
```

Indexes:
- **Unique** `{ tournamentId: 1, roundNumber: 1 }`

---

## Pairings

```json
{
  _id: ObjectId,
  tournamentId: ObjectId,
  roundId: ObjectId,
  roundNumber: number,
  gameId: string,

  whiteLichessUserId: string,
  blackLichessUserId: string,

  // NEW: normalized player list for indexing and correctness
  playerIds: [string, string],

  result: "white" | "black" | "draw" | "forfeit" | null,
  isOfficial: boolean,
  createdAt: Date
}
```

### Required invariants

- `playerIds` MUST contain exactly two distinct user IDs
- Order inside `playerIds` is irrelevant (treat as a set)

### Indexes

- **Unique** `{ roundId: 1, playerIds: 1 }`
  - Enforces: a player may appear in at most one pairing per round
- `{ tournamentId: 1, roundNumber: 1 }`
- `{ tournamentId: 1, playerIds: 1, roundNumber: 1 }`  // fast player lookups
- `{ gameId: 1 }`

---

## Byes

```json
{
  _id: ObjectId,
  tournamentId: ObjectId,
  roundId: ObjectId,
  roundNumber: number,
  lichessUserId: string,
  scoreAwarded: number,
  reason: "odd" | "withdrawal" | "td_grant",
  createdAt: Date
}
```

Indexes:
- **Unique** `{ roundId: 1, lichessUserId: 1 }`
- `{ tournamentId: 1, lichessUserId: 1, roundNumber: 1 }`

---

## PlayerTournamentState (NEW)

Derived, one document per (player, tournament). Updated transactionally when rounds complete, results are overridden, or byes are granted.

```json
{
  _id: ObjectId,
  tournamentId: ObjectId,
  lichessUserId: string,

  points: number,
  gamesPlayed: number,

  opponents: [string],
  colors: ["white" | "black"],

  resultsByRound: {
    "1": "white" | "black" | "draw" | "bye",
    "2": "white" | "black" | "draw" | "bye"
  },

  tiebreaks: {
    buchholz: number,
    sonnebornBerger: number
  },

  updatedAt: Date
}
```

Indexes:
- **Unique** `{ tournamentId: 1, lichessUserId: 1 }`
- `{ tournamentId: 1, points: -1 }`

Purpose:
- Fast standings rendering
- O(1) repeat-opponent checks
- Color-balance enforcement
- Crosstable generation without scanning pairings

---

## Overrides

```json
{
  _id: ObjectId,
  pairingId: ObjectId,
  reason: string,
  appliedBy: string,
  createdAt: Date
}
```

Indexes:
- `{ pairingId: 1, createdAt: 1 }`

---

## AuditEvents

```json
{
  _id: ObjectId,
  tournamentId: ObjectId,
  type: string,
  payload: object,
  createdAt: Date
}
```

Indexes:
- `{ tournamentId: 1, createdAt: 1 }`

Optional:
- TTL index on `createdAt` if long-term retention is not required

---

## Transaction Boundaries

The following operations MUST be executed in multi-document transactions:

1. **Round generation**
   - Create round
   - Insert pairings
   - Insert byes
   - Initialize PlayerTournamentState if first round

2. **Round completion**
   - Mark round complete
   - Apply official results
   - Update PlayerTournamentState for all affected players

3. **Override application**
   - Record override
   - Update pairing result
   - Recompute affected PlayerTournamentState entries

---

## Scalability Notes (500 Players)

- Pairings per round: ~250
- Typical rounds: 7–9
- Total pairings: ~2,000–2,250
- All collections remain well within MongoDB comfort zones
- Performance is dominated by pairing logic and standings queries, not raw data volume

The introduction of `playerIds` and `PlayerTournamentState` ensures all critical paths are indexed and bounded.

---

## Summary of Changes

- Added `playerIds` to `pairings` with a unique per-round constraint
- Added `PlayerTournamentState` derived collection
- Added player-centric indexes for pairings and byes
- Added operational guardrail for maximum rounds
- Shifted standings and Swiss constraints to incremental updates

This revised model is safe, performant, and maintainable for tournaments up to and beyond 500 players.

