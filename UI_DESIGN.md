# LiTD User Interface Design (v1)

This document proposes a practical first UI for LiTD built around the existing API surface and tournament workflow.

## 1) Product goals

- Make it easy for a tournament director (TD) to run asynchronous Swiss rounds.
- Make player actions obvious: register, view pairings, issue/accept challenges, and track standings.
- Keep round-management actions safe with clear state, confirmations, and audit visibility.

## 2) Primary personas

- **Tournament Director (TD):** creates tournaments, generates rounds, resolves edge cases.
- **Player:** registers and tracks own games/results.
- **Public Viewer:** views standings/crosstable without authentication.

## 3) Navigation and information architecture

Top-level routes:

- `/` — landing + active tournaments
- `/auth/callback` — OAuth return handling page
- `/tournaments/:id` — tournament hub
- `/tournaments/:id/rounds/:roundNumber` — round operations
- `/tournaments/:id/standings` — full standings view
- `/tournaments/:id/crosstable` — crosstable matrix
- `/tournaments/:id/admin` — TD-focused controls

Tournament hub tabs:

1. **Overview** (status, next actions, countdown/deadlines)
2. **My Pairings** (player-centric current + historical games)
3. **Standings**
4. **Crosstable**
5. **Admin** (visible to all, actionable for TD only)

## 4) Core screens

## 4.1 Landing screen

Sections:

- Hero + “Sign in with Lichess” CTA.
- “Open tournaments” list with status badge (`draft`, `active`, `completed`).
- “How LiTD works” 3-step explainer.

Key interactions:

- Unauthenticated users can browse public standings/crosstable links.
- Authenticated users see “My tournaments” first.

## 4.2 Tournament hub

Header:

- Tournament name, status, configured/effective max rounds.
- Current round and “round state” chip.
- Contextual action buttons (e.g., Register, Generate Round, Refresh Results).

Overview cards:

- **Round progress**: completed pairings, unresolved pairings, byes.
- **Your status**: registered/withdrawn/disqualified.
- **System status**: last result refresh timestamp + explicit “Refresh now” action.

Late registration treatment:

- When a player registers after round 1, show a clear banner: “You are active starting round N”.
- Surface `effectiveRound` in player-facing UI (registration success panel, profile chip in tournament, and tooltip near standings row).

## 4.3 My Pairings (player view)

Table columns:

- Round
- Opponent
- Color (white/black)
- Challenge status
- Game/result
- Last update

Row actions:

- “Issue challenge” (only if user is one of pairing players and challenge not yet issued).
- “Open game” (when game ID exists).

States:

- Empty state for not yet paired.
- Warning banner for unresolved games near round end.

## 4.4 Standings view

Table columns:

- Rank
- Player
- Points
- Games played
- Buchholz
- Sonneborn-Berger

Behavior:

- Sort default by points desc, then tiebreaks.
- Highlight current user row.
- Mobile: collapse tie-break columns into expandable row details.

## 4.5 Crosstable view

- Matrix with players on both axes.
- Cells show result markers (`1`, `0`, `½`, `B` for bye) and optional color dot.
- Sticky first row/column for large events.

## 4.6 Admin console (TD controls, non-TD visible disabled)

Action groups:

1. **Tournament lifecycle**
   - Create tournament (name, configured max rounds)
2. **Round control**
   - Generate next round
   - Refresh round results
   - End round
3. **Interventions**
   - Grant TD bye
   - Override pairing result

Safeguards:

- Confirmation dialogs with explicit consequence text.
- Disable invalid actions based on tournament/round status.
- For non-TD users, show disabled controls with lock icon and help text (“TD permission required”).
- Inline error mapping from API responses.

## 5) UX states and feedback

- Use persistent toasts for mutation success/failure.
- Every async action gets an inline loading state and disabled duplicate submissions.
- For idempotent challenge issuance, show “Already issued” as informational success.
- Prefer explicit manual refresh controls over background real-time updates.

## 6) API-to-UI mapping (current backend)

- Create tournament → `POST /tournaments`
- Register player → `POST /tournaments/{tournamentId}/registrations`
- Generate round → `POST /tournaments/{tournamentId}/rounds/generate`
- Grant TD bye → `POST /tournaments/{tournamentId}/rounds/{roundNumber}/byes/td`
- Issue challenge → `POST /tournaments/{tournamentId}/pairings/{pairingId}/challenge`
- Refresh results → `POST /tournaments/{tournamentId}/rounds/{roundNumber}/results/refresh`
- End round → `POST /tournaments/{tournamentId}/rounds/{roundNumber}/end`
- Override result → `POST /tournaments/{tournamentId}/pairings/{pairingId}/result/override`
- Public standings → `GET /public/tournaments/{tournamentId}/standings`
- Public crosstable → `GET /public/tournaments/{tournamentId}/crosstable`

## 7) Visual style direction (align with Lichess)

LiTD should feel native to Lichess as much as possible.

- **Color system:** mirror Lichess light/dark palette conventions (background gradients, panel surfaces, text hierarchy, success/warn colors) rather than introducing a custom brand palette.
- **Typography:** match Lichess font stack and sizing rhythm for headings, body text, and compact table labels.
- **Spacing + surfaces:** reuse Lichess-like card radii, borders, shadows, and table density so screens visually blend with existing Lichess pages.
- **Controls:** use Lichess-style button hierarchy (primary, secondary, destructive), chips, and form field treatments.
- **Icons + states:** follow Lichess icon tone and hover/focus/disabled state behavior.
- **Accessibility:** preserve AA contrast, clear focus rings, and keyboard-first navigation.
- **Implementation note:** while building frontend styles, use the Lichess `lila` repository as a reference for tokens/components to maximize consistency.

## 8) Suggested implementation approach

Phase 1 (MVP):

- Landing, tournament hub, standings, crosstable, register + challenge actions.

Phase 2:

- Full admin console with guardrails and detailed audit timeline.
- Polish manual refresh UX (refresh badges, “last updated” labels, and stale-data indicators).

## 9) Product decisions captured

- **Late registration:** yes, expose `effectiveRound` clearly to players in tournament UI.
- **TD-only actions:** keep visible for non-TD users, but disabled with clear permission messaging.
- **Update model:** no real-time push for now; users refresh manually when desired.
