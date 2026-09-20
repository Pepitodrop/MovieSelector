# Movie Selector -- Manual Deployment Guide

This document is for **you** to run manually. Nothing in it has been executed against any
production system. No code here has touched `wencke.love`, `game.luisbenedikt.de`, DNS, or any
live server.

**Final SHAs for this review** (update if you pull newer commits before deploying):

| Repo | Branch | SHA |
|---|---|---|
| `Pepitodrop/MovieSelector` | `feat/piet-core` | `0d63f5a41881e3add9eaea87088eaf969b7219d6` |
| `Pepitodrop/GamePage` | `feat/movie-selector-integration` | `b516360c87900f6853b7e86acdadf56a837d0dce` |

Both are pull requests, not yet merged to `main`. Merge them yourself (or ask me to, in a
follow-up) once you're satisfied, then substitute `main`'s SHA after merge for the commands
below.

**One placeholder you must fill in yourself, nowhere else:** `<WENCKE_SITE_PASSWORD>` -- the
real Wencke site-access password. It is never in this repo, never in `.env.example`, and I have
never seen or guessed it.

---

## A. Wencke server

**Wencke needs zero code changes.** The integration is a server-to-server client (Movie
Selector's backend logs into Wencke's existing `/api/v1/site-access/unlock` the same way a
browser would, using a password you configure) -- nothing in `Pepitodrop/Wencke` was touched.
This section is verification only: confirm Wencke is on the SHA you expect and its public API is
healthy before pointing Movie Selector at it.

```bash
# --- record current state ---
cd /path/to/Wencke                     # <- substitute your real Wencke checkout path
git fetch origin
git rev-parse HEAD                     # record this SHA before doing anything else
git status                             # confirm a clean tree (no accidental local changes)

# --- optional backup (skip if you already snapshot this host another way) ---
# e.g. a database dump, if you want one before Movie Selector starts reading from it:
# docker compose exec postgres pg_dump -U wencke wencke > wencke-backup-$(date +%F).sql

# --- no pull, no migration, no rebuild is required for Movie Selector's sake ---
# If you separately have unrelated Wencke changes pending, deploy those on your own
# normal Wencke schedule; Movie Selector does not depend on any specific Wencke commit
# beyond what's already in production (movie_watchlist with netflix/disneyPlus/
# primeVideo/watched/streamingCheckedAt fields, and /api/v1/site-access/unlock).

# --- sanity checks ---
docker compose config --quiet          # only meaningful if you *are* deploying other Wencke changes
curl --fail --silent https://wencke.love/api/v1/health && echo " -- Wencke is healthy"

# --- confirm the site-access password you're about to give Movie Selector actually works ---
# (run this from the Wencke host or anywhere with network access; replace the placeholder)
curl --fail --silent -X POST https://wencke.love/api/v1/site-access/unlock \
  -H "Content-Type: application/json" \
  -d '{"password":"<WENCKE_SITE_PASSWORD>"}' \
  -c /tmp/wencke-verify-cookies.txt && echo " -- password accepted"

# and that the watchlist is actually reachable with that session:
curl --fail --silent -b /tmp/wencke-verify-cookies.txt https://wencke.love/api/v1/movie-watchlist \
  | head -c 300; echo
rm -f /tmp/wencke-verify-cookies.txt   # don't leave the session cookie lying around

# --- rollback ---
# Nothing was changed here, so there is nothing to roll back. If you separately deployed
# unrelated Wencke changes and need to revert those:
git checkout <previous-Wencke-SHA>
docker compose up -d --wait
```

If the password check above fails, stop -- fix the password (or Wencke's own health) before
touching the GamePage server. Movie Selector will start fine either way (it only talks to
Wencke lazily, on the first player action), but every game session will fail with a
"Wencke authentication failed" error until it's right.

---

## B. GamePage server

Existing documented layout: `/srv/games/` (see `Pepitodrop/GamePage`'s own README). This adds a
fifth sibling checkout, `MovieSelector`, alongside the four that are already there.

```bash
# --- record current state of every service, before touching anything ---
cd /srv/games/GamePage
git fetch origin
git rev-parse HEAD                                        # current GamePage SHA
git -C ../TrumpVsShakespeare rev-parse HEAD                # current sibling SHAs
git -C ../CrazyMiniGolf rev-parse HEAD
git -C ../CrazyRaceGame rev-parse HEAD
[ -d ../MovieSelector ] && git -C ../MovieSelector rev-parse HEAD || echo "MovieSelector not yet cloned"

# --- clone Movie Selector if this is the first deploy ---
if [ ! -d ../MovieSelector ]; then
  git clone https://github.com/Pepitodrop/MovieSelector.git ../MovieSelector
fi

# --- check out the exact reviewed SHAs ---
git -C ../MovieSelector fetch origin
git -C ../MovieSelector checkout 0d63f5a41881e3add9eaea87088eaf969b7219d6   # or main, after you merge the PR
git fetch origin
git checkout b516360c87900f6853b7e86acdadf56a837d0dce                       # or main, after you merge the PR

# --- update .env ---
# Add/confirm these lines in /srv/games/GamePage/.env (see the updated .env.example
# in this PR for the full annotated list; only the Movie Selector additions are new):
#
#   MOVIES_REPO_PATH=../MovieSelector
#   MOVIES_ENABLED=true
#   MOVIES_REQUIRED=true
#   MOVIES_MAX_LATENCY_MS=2500
#   WENCKE_BASE_URL=https://wencke.love
#   WENCKE_SITE_PASSWORD=<WENCKE_SITE_PASSWORD>
#
# Edit it by hand (this is the one file that holds the real password -- never commit it):
nano .env    # or your usual editor
chmod 600 .env

# --- validate before building anything ---
docker compose config --quiet

# --- build and start ---
docker compose build --pull
docker compose up -d --wait --wait-timeout 240 --remove-orphans

# --- smoke test the whole stack, including the new game ---
bash scripts/docker-smoke-test.sh

# --- GamePage-level health checks ---
curl --fail --silent https://game.luisbenedikt.de/healthz && echo " -- gateway alive"
curl --fail --silent https://game.luisbenedikt.de/readyz && echo " -- stack ready"
curl --fail --silent https://game.luisbenedikt.de/api/status | head -c 2000; echo

# --- Movie Selector route and data/auth verification ---
curl --fail --silent -o /dev/null -w "movie-selector page: %{http_code}\n" \
  https://game.luisbenedikt.de/play/movie-selector/
# Start a real session through the proxy and confirm it reaches Wencke and gets movies back:
curl --fail --silent -X POST https://game.luisbenedikt.de/play/movie-selector/api/session \
  -H "Content-Type: application/json" -d '{}' | head -c 500; echo
# Expect a JSON body with "sessionId", "remaining" > 0, and "currentMovie" populated.
# If you get HTTP 502/"Wencke is unavailable" or "Wencke authentication failed" here,
# re-check WENCKE_SITE_PASSWORD in .env and re-run the Section A password check.

# --- verify the other three games are unaffected ---
curl --fail --silent -o /dev/null -w "trump: %{http_code}\n"  https://game.luisbenedikt.de/play/trump/
curl --fail --silent -o /dev/null -w "golf: %{http_code}\n"   https://game.luisbenedikt.de/play/golf/
curl --fail --silent -o /dev/null -w "race: %{http_code}\n"   https://game.luisbenedikt.de/play/race/

# --- if something fails ---
docker compose ps --all
docker compose logs --no-color --tail=300 movie-selector
docker compose logs --no-color --tail=300 gamepage

# --- rollback to the previously recorded SHAs ---
git checkout <previous-GamePage-SHA>
git -C ../TrumpVsShakespeare checkout <previous-Trump-SHA>
git -C ../CrazyMiniGolf checkout <previous-Golf-SHA>
git -C ../CrazyRaceGame checkout <previous-Race-SHA>
git -C ../MovieSelector checkout <previous-MovieSelector-SHA>   # or: rm -rf ../MovieSelector if this was the first deploy
# Remove the MOVIES_*/WENCKE_* lines from .env if rolling back past the first Movie Selector deploy.
docker compose up -d --wait --wait-timeout 240 --remove-orphans
bash scripts/docker-smoke-test.sh
```

### Expected final verification URLs

- `https://wencke.love/api/v1/health`
- `https://game.luisbenedikt.de/healthz`
- `https://game.luisbenedikt.de/readyz`
- `https://game.luisbenedikt.de/api/status` -- should show `"movies"` with `"launchable": true`
- `https://game.luisbenedikt.de/play/movie-selector/`
- `https://game.luisbenedikt.de/play/trump/`, `/play/golf/`, `/play/race/` -- unaffected

### Android

Not deployed by any of the above -- it's a standalone app, not a server component. Artifacts are
built from `Pepitodrop/MovieSelector`, `android/build/outputs/`:
- `apk/debug/android-debug.apk`
- `apk/release/android-release-unsigned.apk` (needs a real signing config before any store
  distribution -- none exists in this repo)
- `bundle/release/android-release.aab`

Install manually for testing: `adb install android/build/outputs/apk/debug/android-debug.apk`.
Point it at a non-production backend for testing with `-PapiBaseUrl=...` (see `android/README.md`);
the production default is already `https://game.luisbenedikt.de/play/movie-selector/api`.
