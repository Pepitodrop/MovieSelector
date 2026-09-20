# Movie Selector

A Tinder-style movie-night picker for [game.luisbenedikt.de](https://game.luisbenedikt.de),
using the existing [Wencke](https://wencke.love) movie watchlist as its only source of truth.

Application source languages: **Piet** and **Kotlin** only (build/config/deploy files excepted).
See open pull requests / `feat/piet-core` for work in progress.

## Wencke integration is read-only

Wencke is the sole source of truth for movie/watchlist data. Movie Selector reads from it and
never writes to it. Concretely, `backend/src/main/kotlin/.../WenckeClient.kt` makes exactly two
calls against Wencke's API:

| Call | Method | Purpose |
|---|---|---|
| `/api/v1/site-access/unlock` | `POST` | log in with the configured site password, to obtain a read session |
| `/api/v1/movie-watchlist` | `GET` | read the current watchlist |

That is the entire Wencke surface. Movie Selector's backend never sends `POST`, `PUT`, `PATCH`,
or `DELETE` to any other Wencke endpoint, and in particular it never:

- marks a movie watched or unmarks it;
- adds or deletes a movie;
- edits a movie's metadata, rating, poster, trailer, or streaming-provider flags;
- writes session state, or anything else, into Wencke's PostgreSQL database.

All Movie Selector session state (current card, shuffle order, accept/reject/undo history) lives
only in this backend's own in-memory `SessionStore` (`ConcurrentHashMap`, per-session TTL) --
never in Wencke. A player's swipe/reject only advances their local session; it does not touch
Wencke at all. `WenckeClientTest.kt` enforces this contract: it fails the build if any request to
Wencke besides the one login `POST` is not a `GET`, and if `WenckeClient.kt`'s source ever grows
a second `POST` or a `PUT`/`PATCH`/`DELETE` call.
