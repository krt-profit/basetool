# Test-stack Redis ACLs

The **E2E stack** runs both Redis instances (ADR-0388) and loads one ACL file each
(`docker-compose.e2e.yml`, `docker-compose.sandbox.yml`):

| File | Instance | Template |
|---|---|---|
| `session-users.acl` | `redis-session-dev` | [`scripts/redis-session-users.acl.tmpl`](../../scripts/redis-session-users.acl.tmpl) |
| `shared-users.acl` | `redis-shared-dev` | [`scripts/redis-shared-users.acl.tmpl`](../../scripts/redis-shared-users.acl.tmpl) |

Each is its template — the rules production loads — rendered with the throwaway E2E passwords in
`RedisAclTemplate.E2E_PASSWORDS` (test-support) and `REDIS_DEFAULT_USER=off`, the state production
reaches at the end of its rollout (REQ-SEC-068, ADR-0207).

They carry SHA-256 hashes, never a password, and the passwords behind them are published on purpose:
the E2E Redis instances are created and destroyed per run and hold nothing. Never a production value.

**They must equal their templates.** `RedisAclFrontendIntegrationTest#theCommittedE2eAclsAreTheTemplates`
fails when one does not. After changing a template, re-render both from the repository root with a
throwaway env file holding the values from `RedisAclTemplate.E2E_PASSWORDS` plus
`REDIS_DEFAULT_USER=off`:

```bash
python scripts/render-redis-acl.py --env <that file> \
  --template scripts/redis-session-users.acl.tmpl --out docker/test-redis/session-users.acl
python scripts/render-redis-acl.py --env <that file> \
  --template scripts/redis-shared-users.acl.tmpl --out docker/test-redis/shared-users.acl
```
