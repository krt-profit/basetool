# Test-stack Redis ACL

`users.acl` is the Redis ACL the **E2E stack** loads (`docker-compose.e2e.yml` → `redis-dev`). It is
[`scripts/redis-users.acl.tmpl`](../../scripts/redis-users.acl.tmpl) — the rules production loads —
rendered with the throwaway E2E passwords in `RedisAclTemplate.E2E_PASSWORDS` (test-support) and
`REDIS_DEFAULT_USER=off`, the state production reaches at the end of its rollout (REQ-SEC-068,
ADR-0207).

It carries SHA-256 hashes, never a password, and the passwords behind them are published on purpose:
the E2E Redis is created and destroyed per run and holds nothing. Never a production value.

**It must equal the template.** `RedisAclFrontendIntegrationTest#theCommittedE2eAclIsTheTemplate`
fails when it does not. After changing the template, re-render it from the repository root with a
throwaway env file holding the values from `RedisAclTemplate.E2E_PASSWORDS` plus
`REDIS_DEFAULT_USER=off`:

```bash
python scripts/render-redis-acl.py --env <that file> \
  --template scripts/redis-users.acl.tmpl --out docker/test-redis/users.acl
```
