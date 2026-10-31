# ADR-0262 — The Discord provider imports username and e-mail once

- **Status:** Accepted
- **Date:** 2026-10-15
- **Deciders:** @greluc
- **Related:** spec [`security-and-access.md`](../specs/security-and-access.md) (REQ-SEC-082) ·
  [`discord-integration.md`](../specs/discord-integration.md) · ADR-0051, ADR-0142

## Context

The `discord` identity provider in the `basetool` realm runs with `syncMode` **IMPORT**. Keycloak takes
the username and e-mail from the Discord profile once, at the first broker login. Afterwards it
refreshes only the stored token and the identity-provider mappers that run with `syncMode` FORCE:
`discord_user_id` and `discord_guild_nickname`. A broker login is matched on the Discord user id, so
a member who changes their Discord username or e-mail still signs in. Their Keycloak username and
e-mail, however, keep the values of the first sign-in.

The backend mirrors `preferred_username` and `email` from every token into `app_user`. The
username is the callsign the tool shows and matches on: the callsign collision check (ADR-0142), the
guest-name check and the Discord account-existence precheck (ADR-0051).

On 2026-10-15 a member could not request a password reset. They entered their server nickname,
which Keycloak's reset cannot resolve, because it looks a user up by username, or by e-mail when the
input contains `@`.

The alternative is `syncMode` **FORCE**, which overwrites username and e-mail from Discord on every
Discord login.

## Decision

1. **The `discord` provider keeps `syncMode` IMPORT.** Username and e-mail are taken from Discord
   once. The guild nickname keeps following on every Discord login through its FORCE mapper.
2. **A member changes their e-mail themselves** in the account console. The user profile lets a
   user edit `email`. The username is not editable by the user (`editUsernameAllowed` is off); an
   admin changes it.
3. **The reset page names what it accepts:** the e-mail address or the username, which for a
   Discord account is the Discord username of the first sign-in, never the server nickname or the
   display name (REQ-SEC-082).

## Consequences

- A Discord rename never renames the callsign a member is known by in the tool, and it never makes
  the callsign collide with another account behind the member's back.
- An e-mail a member edited in the account console is not overwritten by their next Discord login.
- A member who changed their Discord e-mail keeps receiving mail, including reset mail, at the old
  address until they change it in the account console.
- The Keycloak username, the backend username and the Discord username can differ. Support asks for
  the Basetool username or the stored e-mail, as the profile shows them.
