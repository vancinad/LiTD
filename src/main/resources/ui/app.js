const appEl = document.getElementById("app");
const authActionsEl = document.getElementById("auth-actions");

async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options
  });
  const text = await response.text();
  let payload = {};
  try {
    payload = text ? JSON.parse(text) : {};
  } catch (_) {
    payload = { error: text || "Unexpected response" };
  }
  if (!response.ok) {
    const message = payload.error || `Request failed (${response.status})`;
    throw new Error(message);
  }
  return payload;
}

function escapeHtml(value) {
  return String(value)
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;");
}

function setMessage(type, text) {
  const css = type === "error" ? "danger" : "ok";
  return `<p class="${css}">${escapeHtml(text)}</p>`;
}

async function loadCurrentUser() {
  try {
    return await fetchJson("/auth/me");
  } catch (_) {
    return null;
  }
}

function renderAuthActions(user) {
  if (!user) {
    authActionsEl.innerHTML = `<a href="/auth/lichess/start"><button>Sign in with Lichess</button></a>`;
    return;
  }
  authActionsEl.innerHTML = `<div class="inline"><span class="subtle">Signed in as <strong>${escapeHtml(
    user.lichessUserId
  )}</strong></span><a href="/"><button class="secondary">Home</button></a><button class="secondary" id="logout-btn">Logout</button></div>`;
  const logoutBtn = document.getElementById("logout-btn");
  if (logoutBtn) {
    logoutBtn.addEventListener("click", async () => {
      logoutBtn.disabled = true;
      try {
        await fetchJson("/auth/logout", { method: "POST" });
      } catch (_) {
        // Even if backend revocation fails, hard refresh to drop local app state.
      }
      window.location.href = "/";
    });
  }
}

function statusBadge(status) {
  return `<span class="badge">${escapeHtml(status)}</span>`;
}

async function renderLanding(user) {
  let tournaments = [];
  let myTournaments = [];
  let teams = [];
  const authError = new URLSearchParams(window.location.search).get("authError");
  let message = authError ? setMessage("error", authError) : "";
  try {
    if (user) {
      const visible = await fetchJson("/tournaments/visible");
      tournaments = visible.tournaments || [];
      const mine = await fetchJson("/tournaments/mine");
      myTournaments = mine.tournaments || [];
      const teamResponse = await fetchJson("/auth/teams");
      teams = teamResponse.teams || [];
    } else {
      const response = await fetchJson("/public/tournaments");
      tournaments = response.tournaments || [];
    }
  } catch (error) {
    message = setMessage("error", error.message);
  }

  const cards = tournaments
    .map(
      (item) => `
      <article class="card">
        <h3><a href="/tournaments/${item.id}">${escapeHtml(item.name)}</a></h3>
        <p>${statusBadge(item.status)}</p>
        <p class="subtle">Current round: ${item.currentRoundNumber} | Effective max rounds: ${item.effectiveMaxRounds}</p>
      </article>
    `
    )
    .join("");
  const myCards = myTournaments
    .map(
      (item) => `
      <article class="card">
        <h3><a href="/tournaments/${item.id}">${escapeHtml(item.name)}</a></h3>
        <p>${statusBadge(item.status)}</p>
        <p class="subtle">Current round: ${item.currentRoundNumber} | Effective max rounds: ${item.effectiveMaxRounds}</p>
      </article>
    `
    )
    .join("");

  appEl.innerHTML = `
    <section class="card">
      <h1 class="section-title">Run asynchronous Swiss tournaments with less manual work</h1>
      <p class="subtle">Register players, generate rounds, issue challenges, and track standings/crosstables.</p>
      ${message}
    </section>
    ${
      user
        ? `<section class="spacer">
             <h2 class="section-title">My tournaments</h2>
             <div class="grid">${myCards || `<p class="subtle">No registrations yet.</p>`}</div>
           </section>`
        : ""
    }
    ${
      user
        ? `<section class="spacer card">
             <h2 class="section-title">Create tournament</h2>
             <p class="subtle">Choose one of your Lichess teams. The tournament will be bound to that team.</p>
             <form id="create-tournament-form">
               <label for="tournament-team">Team</label>
               <select id="tournament-team" ${teams.length === 0 ? "disabled" : ""}>
                 ${teams.map((team) => `<option value="${escapeHtml(team.id)}">${escapeHtml(team.name)} (${escapeHtml(team.id)})</option>`).join("")}
               </select>
               <label for="tournament-name">Tournament name</label>
               <input id="tournament-name" type="text" maxlength="120" required />
               <label for="tournament-rounds">Configured max rounds</label>
               <input id="tournament-rounds" type="number" min="1" max="15" value="7" required />
               <label for="tournament-time-initial">Clock initial</label>
               <div class="input-inline-group">
                 <input id="tournament-time-initial" type="number" min="10" max="10800" value="180" step="1" required />
                 <div class="radio-group inline-radio-group">
                   <label class="radio-option" for="tournament-time-unit-seconds">
                     <input
                       id="tournament-time-unit-seconds"
                       name="tournament-time-unit"
                       type="radio"
                       value="seconds"
                       checked
                     />
                     Seconds
                   </label>
                   <label class="radio-option" for="tournament-time-unit-minutes">
                     <input
                       id="tournament-time-unit-minutes"
                       name="tournament-time-unit"
                       type="radio"
                       value="minutes"
                     />
                     Minutes
                   </label>
                 </div>
               </div>
               <label for="tournament-time-increment">Clock increment (seconds)</label>
               <input id="tournament-time-increment" type="number" min="0" max="180" value="2" required />
               <label>Game mode</label>
               <div class="radio-group">
                 <label class="radio-option" for="tournament-rated">
                   <input id="tournament-rated" name="tournament-game-mode" type="radio" value="rated" checked />
                   Rated
                 </label>
                 <label class="radio-option" for="tournament-unrated">
                   <input id="tournament-unrated" name="tournament-game-mode" type="radio" value="unrated" />
                   Unrated
                 </label>
               </div>
               <div class="spacer">
                 <button id="create-tournament-btn" type="submit" ${teams.length === 0 ? "disabled" : ""}>Create tournament</button>
               </div>
             </form>
             <div id="create-tournament-message" class="spacer"></div>
           </section>`
        : ""
    }
    <section class="spacer">
      <h2 class="section-title">${user ? "Tournaments for your teams" : "Open tournaments"}</h2>
      <div class="grid">${cards || `<p class="subtle">No tournaments found.</p>`}</div>
    </section>
  `;

  if (user) {
    const form = document.getElementById("create-tournament-form");
    const messageEl = document.getElementById("create-tournament-message");
    const submitBtn = document.getElementById("create-tournament-btn");
    if (form && submitBtn) {
      form.addEventListener("submit", async (event) => {
        event.preventDefault();
        const name = document.getElementById("tournament-name").value.trim();
        const configuredMaxRounds = Number(document.getElementById("tournament-rounds").value);
        const initialValue = Number(document.getElementById("tournament-time-initial").value);
        const isMinutes = document.getElementById("tournament-time-unit-minutes").checked;
        const timeControlInitialSeconds = isMinutes ? initialValue * 60 : initialValue;
        const timeControlIncrementSeconds = Number(document.getElementById("tournament-time-increment").value);
        const rated = document.getElementById("tournament-rated").checked;
        const teamId = document.getElementById("tournament-team").value;
        submitBtn.disabled = true;
        messageEl.innerHTML = `<p class="subtle">Creating tournament...</p>`;
        try {
          const created = await fetchJson("/tournaments", {
            method: "POST",
            body: JSON.stringify({
              name,
              configuredMaxRounds,
              teamId,
              timeControlInitialSeconds,
              timeControlIncrementSeconds,
              rated
            })
          });
          messageEl.innerHTML = setMessage("ok", `Tournament created: ${created.name}`);
          setTimeout(() => {
            window.location.href = `/tournaments/${created.id}`;
          }, 400);
        } catch (error) {
          messageEl.innerHTML = setMessage("error", error.message);
          submitBtn.disabled = false;
        }
      });
    }
  }
}

function resolveTab(pathParts) {
  if (pathParts[3] === "standings") return "standings";
  if (pathParts[3] === "crosstable") return "crosstable";
  return "overview";
}

async function renderTournamentPage(user, tournamentId, tab) {
  let hub;
  try {
    hub = await fetchJson(`/public/tournaments/${tournamentId}/hub?refreshResults=true`);
  } catch (error) {
    appEl.innerHTML = setMessage("error", error.message);
    return;
  }

  const qsTab = new URLSearchParams(window.location.search).get("tab");
  const resolvedTab = qsTab === "mypairings" ? "mypairings" : tab;
  const directorId = hub.tournament.tournamentDirectorLichessUserId || "unknown";

  const tabs = `
    <nav class="tabs">
      <a class="${resolvedTab === "overview" ? "active" : ""}" href="/tournaments/${tournamentId}">Overview</a>
      <a class="${resolvedTab === "mypairings" ? "active" : ""}" href="/tournaments/${tournamentId}?tab=mypairings">My Pairings</a>
      <a class="${resolvedTab === "standings" ? "active" : ""}" href="/tournaments/${tournamentId}/standings">Standings</a>
      <a class="${resolvedTab === "crosstable" ? "active" : ""}" href="/tournaments/${tournamentId}/crosstable">Crosstable</a>
    </nav>
  `;

  appEl.innerHTML = `
    <section class="card">
      <h1 class="section-title">${escapeHtml(hub.tournament.name)}</h1>
      <p class="inline">
        ${statusBadge(hub.tournament.status)}
        <span class="subtle">Current round: ${hub.currentRoundNumber} (${escapeHtml(hub.currentRoundStatus)})</span>
      </p>
      <p class="subtle">Configured max rounds: ${hub.tournament.configuredMaxRounds} | Effective max rounds: ${hub.tournament.effectiveMaxRounds}</p>
      <p class="subtle">Time control: ${hub.tournament.timeControlInitialSeconds}+${hub.tournament.timeControlIncrementSeconds}</p>
      <p class="subtle">Game mode: ${hub.tournament.rated ? "Rated" : "Unrated"}</p>
      <p class="subtle">Team: ${escapeHtml(hub.tournament.teamId || "(legacy tournament: team unspecified)")}</p>
      <p class="subtle">Tournament director: ${escapeHtml(directorId)}</p>
      ${tabs}
      <div id="tab-content"></div>
    </section>
  `;

  const tabContent = document.getElementById("tab-content");

  if (resolvedTab === "standings") {
    await renderStandings(tabContent, tournamentId, user?.lichessUserId || "");
    return;
  }
  if (resolvedTab === "crosstable") {
    await renderCrosstable(tabContent, tournamentId);
    return;
  }
  if (resolvedTab === "mypairings") {
    await renderMyPairings(tabContent, tournamentId, user);
    return;
  }
  renderOverview(tabContent, tournamentId, hub, user);
}

function renderOverview(target, tournamentId, hub, user) {
  const progress = hub.roundProgress;
  const directorId = hub.tournament.tournamentDirectorLichessUserId || "unknown";
  const isDirector = Boolean(user && directorId === user.lichessUserId);
  target.innerHTML = `
    <div class="grid">
      <article class="card">
        <h2 class="section-title">Round progress</h2>
        ${
          progress
            ? `<p>Round ${progress.roundNumber} is <strong>${escapeHtml(progress.roundStatus)}</strong></p>
               <p class="subtle">Completed: ${progress.completedPairings} | Unresolved: ${progress.unresolvedPairings} | Byes: ${progress.byeCount}</p>`
            : `<p class="subtle">No rounds generated yet.</p>`
        }
      </article>
      <article class="card">
        <h2 class="section-title">Actions</h2>
        <p class="subtle">Register for this tournament and then issue challenges from My Pairings.</p>
        <button id="register-btn" ${user ? "" : "disabled"}>${user ? "Register" : "Sign in required"}</button>
        <div id="register-message" class="spacer"></div>
      </article>
      <article class="card">
        <h2 class="section-title">Director actions</h2>
        ${
          isDirector
            ? `<p class="subtle">You can generate rounds, close rounds, and adjudicate pairings.</p>
               <div class="spacer"><button id="generate-round-btn">Generate next round</button></div>
               <div class="spacer"><button id="end-round-btn" ${hub.currentRoundNumber <= 0 || hub.currentRoundStatus !== "active" ? "disabled" : ""}>Close current round</button></div>
               <form id="override-result-form" class="spacer">
                 <label for="override-pairing-id">Pairing ID</label>
                 <input id="override-pairing-id" type="text" minlength="24" maxlength="24" required />
                 <label for="override-result">Result</label>
                 <select id="override-result">
                   <option value="white">white</option>
                   <option value="black">black</option>
                   <option value="draw">draw</option>
                   <option value="forfeit">forfeit</option>
                 </select>
                 <label for="override-reason">Reason</label>
                 <input id="override-reason" type="text" maxlength="160" required />
                 <div class="spacer"><button id="override-submit-btn" type="submit">Adjudicate pairing</button></div>
               </form>`
            : `<p class="subtle">Only tournament director ${escapeHtml(directorId)} can generate pairings, close rounds, or adjudicate games.</p>`
        }
        <div id="director-message" class="spacer"></div>
      </article>
    </div>
  `;

  if (!user) return;
  const registerBtn = document.getElementById("register-btn");
  const registerMessage = document.getElementById("register-message");
  registerBtn.addEventListener("click", async () => {
    registerBtn.disabled = true;
    registerMessage.innerHTML = `<p class="subtle">Registering...</p>`;
    try {
      const response = await fetchJson(`/tournaments/${tournamentId}/registrations`, { method: "POST" });
      registerMessage.innerHTML = setMessage(
        "ok",
        `Registered as ${response.lichessUserId}. Active starting round ${response.effectiveRound}.`
      );
    } catch (error) {
      registerMessage.innerHTML = setMessage("error", error.message);
    } finally {
      registerBtn.disabled = false;
    }
  });

  if (!isDirector) return;
  const directorMessage = document.getElementById("director-message");
  const generateRoundBtn = document.getElementById("generate-round-btn");
  const endRoundBtn = document.getElementById("end-round-btn");
  const overrideForm = document.getElementById("override-result-form");
  if (generateRoundBtn) {
    generateRoundBtn.addEventListener("click", async () => {
      generateRoundBtn.disabled = true;
      directorMessage.innerHTML = `<p class="subtle">Generating next round...</p>`;
      try {
        const response = await fetchJson(`/tournaments/${tournamentId}/rounds/generate`, {
          method: "POST",
          body: JSON.stringify({ tdByes: [] })
        });
        directorMessage.innerHTML = setMessage("ok", `Round ${response.roundNumber} generated.`);
        await renderTournamentPage(user, tournamentId, "overview");
      } catch (error) {
        directorMessage.innerHTML = setMessage("error", error.message);
        generateRoundBtn.disabled = false;
      }
    });
  }
  if (endRoundBtn) {
    endRoundBtn.addEventListener("click", async () => {
      endRoundBtn.disabled = true;
      directorMessage.innerHTML = `<p class="subtle">Closing round ${hub.currentRoundNumber}...</p>`;
      try {
        const response = await fetchJson(`/tournaments/${tournamentId}/rounds/${hub.currentRoundNumber}/end`, {
          method: "POST"
        });
        directorMessage.innerHTML = setMessage("ok", `Round ${response.roundNumber} closed.`);
        await renderTournamentPage(user, tournamentId, "overview");
      } catch (error) {
        directorMessage.innerHTML = setMessage("error", error.message);
        endRoundBtn.disabled = false;
      }
    });
  }
  if (overrideForm) {
    overrideForm.addEventListener("submit", async (event) => {
      event.preventDefault();
      const submitBtn = document.getElementById("override-submit-btn");
      const pairingId = document.getElementById("override-pairing-id").value.trim();
      const result = document.getElementById("override-result").value;
      const reason = document.getElementById("override-reason").value.trim();
      submitBtn.disabled = true;
      directorMessage.innerHTML = `<p class="subtle">Applying adjudication...</p>`;
      try {
        await fetchJson(`/tournaments/${tournamentId}/pairings/${pairingId}/result/override`, {
          method: "POST",
          body: JSON.stringify({ result, reason })
        });
        directorMessage.innerHTML = setMessage("ok", "Pairing adjudication applied.");
        await renderTournamentPage(user, tournamentId, "overview");
      } catch (error) {
        directorMessage.innerHTML = setMessage("error", error.message);
        submitBtn.disabled = false;
      }
    });
  }
}

async function renderStandings(target, tournamentId, currentUserId) {
  try {
    const data = await fetchJson(`/public/tournaments/${tournamentId}/standings`);
    const rows = (data.entries || [])
      .map((entry) => {
        const highlight = entry.lichessUserId === currentUserId ? ` style="background:#eef6ff"` : "";
        return `<tr${highlight}>
            <td>${entry.rank}</td>
            <td>${escapeHtml(entry.lichessUserId)}</td>
            <td>${entry.points}</td>
            <td>${entry.gamesPlayed}</td>
            <td>${entry.buchholz}</td>
            <td>${entry.sonnebornBerger}</td>
          </tr>`;
      })
      .join("");
    target.innerHTML = `
      <h2 class="section-title">Standings</h2>
      <table>
        <thead><tr><th>Rank</th><th>Player</th><th>Points</th><th>Games</th><th>Buchholz</th><th>Sonneborn-Berger</th></tr></thead>
        <tbody>${rows || `<tr><td colspan="6" class="subtle">No standings yet.</td></tr>`}</tbody>
      </table>
    `;
  } catch (error) {
    target.innerHTML = setMessage("error", error.message);
  }
}

async function renderCrosstable(target, tournamentId) {
  try {
    const data = await fetchJson(`/public/tournaments/${tournamentId}/crosstable`);
    const rows = (data.rows || [])
      .map((row) => {
        const games = row.games
          .map((game) => `R${game.roundNumber} vs ${escapeHtml(game.opponentLichessUserId)}: ${game.score}`)
          .join(" | ");
        const byes = row.byes.map((bye) => `R${bye.roundNumber} bye (${bye.scoreAwarded})`).join(" | ");
        return `<tr>
          <td>${escapeHtml(row.lichessUserId)}</td>
          <td>${row.points}</td>
          <td>${row.gamesPlayed}</td>
          <td>${escapeHtml(games || "-")}</td>
          <td>${escapeHtml(byes || "-")}</td>
        </tr>`;
      })
      .join("");
    target.innerHTML = `
      <h2 class="section-title">Crosstable</h2>
      <table>
        <thead><tr><th>Player</th><th>Points</th><th>Games</th><th>Games by round</th><th>Byes</th></tr></thead>
        <tbody>${rows || `<tr><td colspan="5" class="subtle">No results yet.</td></tr>`}</tbody>
      </table>
    `;
  } catch (error) {
    target.innerHTML = setMessage("error", error.message);
  }
}

async function renderMyPairings(target, tournamentId, user) {
  if (!user) {
    target.innerHTML = `<p class="subtle">Sign in to view your pairings and issue challenges.</p>`;
    return;
  }
  try {
    const data = await fetchJson(`/tournaments/${tournamentId}/pairings/me`);
    const rows = (data.entries || [])
      .map((entry) => {
        const canIssue = entry.color === "white" && entry.challengeStatus === "pending" && !entry.gameId;
        const action = canIssue
          ? `<button class="issue-btn" data-pairing-id="${entry.pairingId}">Issue challenge</button>`
          : `<span class="subtle">${entry.gameId ? "Game started" : entry.challengeStatus}</span>`;
        const gameLink = entry.gameId
          ? `<a href="https://lichess.org/${entry.gameId}" target="_blank" rel="noopener noreferrer">Open game</a>`
          : "-";
        return `<tr>
          <td>${entry.roundNumber}</td>
          <td>${escapeHtml(entry.opponentLichessUserId)}</td>
          <td>${escapeHtml(entry.color)}</td>
          <td>${escapeHtml(entry.challengeStatus)}</td>
          <td>${gameLink}</td>
          <td>${escapeHtml(entry.result || "-")}</td>
          <td>${escapeHtml(entry.lastUpdateAt)}</td>
          <td>${action}</td>
        </tr>`;
      })
      .join("");

    target.innerHTML = `
      <h2 class="section-title">My Pairings</h2>
      <table>
        <thead><tr><th>Round</th><th>Opponent</th><th>Color</th><th>Challenge</th><th>Game</th><th>Result</th><th>Last update</th><th>Action</th></tr></thead>
        <tbody>${rows || `<tr><td colspan="8" class="subtle">No pairings assigned yet.</td></tr>`}</tbody>
      </table>
      <div id="challenge-message" class="spacer"></div>
    `;

    const challengeMessage = document.getElementById("challenge-message");
    document.querySelectorAll(".issue-btn").forEach((button) => {
      button.addEventListener("click", async () => {
        button.disabled = true;
        challengeMessage.innerHTML = `<p class="subtle">Issuing challenge...</p>`;
        const pairingId = button.getAttribute("data-pairing-id");
        try {
          const response = await fetchJson(`/tournaments/${tournamentId}/pairings/${pairingId}/challenge`, {
            method: "POST"
          });
          challengeMessage.innerHTML = setMessage(
            "ok",
            `Challenge ${response.status === "already_issued" ? "already issued" : "created"}: ${response.challengeId}`
          );
          await renderMyPairings(target, tournamentId, user);
        } catch (error) {
          challengeMessage.innerHTML = setMessage("error", error.message);
          button.disabled = false;
        }
      });
    });
  } catch (error) {
    target.innerHTML = setMessage("error", error.message);
  }
}

async function main() {
  const user = await loadCurrentUser();
  renderAuthActions(user);
  const pathParts = window.location.pathname.split("/");
  if (window.location.pathname === "/" || window.location.pathname === "/auth/callback") {
    await renderLanding(user);
    return;
  }
  if (pathParts[1] === "tournaments" && pathParts[2]) {
    await renderTournamentPage(user, pathParts[2], resolveTab(pathParts));
    return;
  }
  appEl.innerHTML = `<p class="subtle">Page not found.</p>`;
}

main();
