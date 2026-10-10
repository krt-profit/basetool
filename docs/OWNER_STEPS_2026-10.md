> **Doc type:** Operator runbook, one page — **the owner runs every step; nothing here was or may be
> executed by an agent.** Written 2026-10-10 (domain modularisation plan, D-26). **Living document:**
> a step is struck from the table below when it is done, with the date. Last reviewed: 2026-10-10.

# Owner steps, October 2026

Every step that only the owner can take, in one place. Each has the exact command, the expected
output, the rollback and the approval it needs. The long-form procedures stay where they are and are
linked; this page is the order, the approvals and the words to type.

> [!important] Approvals
> A **production write** (host, realm, release) happens only after @greluc has said yes in chat to
> *that* command — root [`CLAUDE.md` → *Production host access*](../CLAUDE.md). **Repository
> settings** (GitHub environments and secrets) are the owner's own action in the web UI or with
> `gh` under the owner's login. Where the table says *testing*, the owner's own call is enough; the
> testing host is never an agent's to touch either.

| # | Step | Where | Approval | Depends on |
|---|------|-------|----------|------------|
| 1 | [`release` environment, its secret, and the repository secret's deletion](#1-the-release-environment-ci-sec-16) | GitHub | owner (repository settings) | — |
| 2 | [Deploy sudoers rule](#2-the-deploy-sudoers-rule-ops-sec-08) | testing, then production host | testing: owner's call; production: per command | — |
| 3 | [Restore drill after the S-09 rollout](#3-the-restore-drill-s-09) | production host | per command | 2 (the drill runs as `deploy`) |
| 4 | [Keycloak: the session for steps 5–7](#4-keycloak-one-session-for-the-three-realm-steps) | production host | per command | — |
| 5 | [Keycloak step 2: forgot password and the sender](#5-keycloak-step-2-forgot-password-and-the-realms-sender) | production realm | per command | 4 |
| 6 | [Keycloak step 12: session windows](#6-keycloak-step-12-session-windows) | production realm | per command, after a decision | 4 |
| 7 | [Keycloak step 11: OTP for admins](#7-keycloak-step-11-otp-for-admins--last) | production realm | per command | 4, 5, 6 |
| 8 | [Trusted Types: enforce](#8-trusted-types-enforce) | production host | per command | a quiet week |
| 9 | [Promote v1.14.0](#9-promote-v1140) | GitHub, production | the `production` environment's approval | 2 done or consciously not |

Nothing on this page needs a code change first, with two exceptions that are marked: step 6 changes
one file when the owner picks the numbers, and step 9 needs the release to exist.

---

## 1. The `release` environment (CI-SEC-16)

**State read on 2026-10-10 (read-only):** the environment `release` exists but without a branch rule
(GitHub created it empty on the first job that named it), holds no secret, and the repository secret
`RELEASE_APP_PRIVATE_KEY` is still set. The release jobs therefore read the repository secret. After
this step they read the environment secret, which only `main` can reach
([ADR-0201](adr/0201-release-tags-and-release-prs-are-created-by-the-basetool-release-app.md)
amendments 3 and 4).

**Do it in this order; the secret goes in before the old one goes out.**

```powershell
gh api -X PUT repos/krt-profit/basetool/environments/release -F "deployment_branch_policy[protected_branches]=false" -F "deployment_branch_policy[custom_branch_policies]=true"
gh api -X POST repos/krt-profit/basetool/environments/release/deployment-branch-policies -f name=main -f type=branch
Get-Content -Raw C:\path\to\basetool-release.private-key.pem | gh secret set RELEASE_APP_PRIVATE_KEY --env release
```

The same in the UI: *Settings → Environments → release → Deployment branches and tags → Selected
branches and tags → Add rule `main`*; *Environment secrets → Add* `RELEASE_APP_PRIVATE_KEY`. No
reviewers, no wait timer.

**Check before deleting anything:**

```powershell
gh api repos/krt-profit/basetool/environments/release --jq ".deployment_branch_policy"
gh api repos/krt-profit/basetool/environments/release/deployment-branch-policies --jq ".branch_policies[].name"
gh secret list --env release
```

Expected: `{"custom_branch_policies":true,"protected_branches":false}`, then `main`, then one line naming
`RELEASE_APP_PRIVATE_KEY`.

**Then delete the repository secret** (keep the Dependabot copy, which is a separate store):

```powershell
gh secret delete RELEASE_APP_PRIVATE_KEY
gh secret list
```

Expected: the list names `GRADLE_ENCRYPTION_KEY` and `NVD_API_KEY` and no longer
`RELEASE_APP_PRIVATE_KEY`.

**Proof that it works:** the next run of a job that mints the token passes its *Refuse to start without the
release App's key* step — the weekly `refresh-versions` run, or step 9's release PR. A failure reads
`secret RELEASE_APP_PRIVATE_KEY is not set in the release environment`.

**Rollback** (puts the repository secret back; the environment can stay):

```powershell
Get-Content -Raw C:\path\to\basetool-release.private-key.pem | gh secret set RELEASE_APP_PRIVATE_KEY
```

A key rotation updates two copies afterwards: the environment secret and the Dependabot secret.

---

## 2. The deploy sudoers rule (OPS-SEC-08)

The rule admits `podman` for the `deploy` account with the 15 sub-commands the scripts use instead
of `podman *` (#2387). Nothing changes on a host until the role runs. From the Ansible controller
(WSL), in `ansible/`; the inventory is the owner's private one, not in the repository.

**Read-only look first** (as `deploy` on the host, or via the controller): `sudo -l -U deploy`.
Before the rollout it shows `(iri) NOPASSWD: /usr/bin/podman *`.

```bash
ansible-playbook site.yml --limit testing --tags deploy --check --diff
ansible-playbook site.yml --limit testing --tags deploy
ansible-playbook site.yml --limit production --tags deploy --check --diff
ansible-playbook site.yml --limit production --tags deploy
```

The `--check --diff` runs write nothing and show the `/etc/sudoers.d/basetool-deploy` change. The
`deploy` tag runs only `22-deploy-user.yml`: it validates the new file with `visudo -cf` before it
moves it into place, then proves the bridge with `sudo -n -u iri podman info` and
`systemctl --user is-system-running` as `deploy`.

**Expected:** the apply ends with no failed task; `sudo -l -U deploy` lists the 15 sub-commands as a
`Cmnd_Alias` instead of `podman *`. Do **testing first** and let one `iri-deploy.service` and one
`iri-backup.service` run there (`sudo systemctl start iri-backup.service`; the log
`/var/log/iri-backup.log` ends without `FATAL`) before production.

**Rollback** (on the host, as root) — the previous single rule:

```bash
printf '%s\n' 'Defaults:deploy !requiretty' \
  'deploy ALL=(iri) NOPASSWD: /usr/bin/podman *' \
  'deploy ALL=(iri) NOPASSWD: SETENV: /usr/bin/systemctl --user *' \
  'deploy ALL=(root) NOPASSWD: /usr/bin/systemctl restart alloy.service' \
  > /etc/sudoers.d/basetool-deploy.new \
  && visudo -cf /etc/sudoers.d/basetool-deploy.new \
  && install -m 0440 -o root -g root /etc/sudoers.d/basetool-deploy.new /etc/sudoers.d/basetool-deploy \
  && rm /etc/sudoers.d/basetool-deploy.new
```

or check out the previous commit of the role and re-run it with `--tags deploy`.

---

## 3. The restore drill (S-09)

S-09 made `backup.sh` and `restore-drill.sh` refuse an unpinned PostgreSQL image (#2304). The weekly
drill (`iri-restore-drill.timer`, Sundays 05:30) already runs the new code once the release is
deployed; this step runs one **now** and reads the result, so the proof does not wait for Sunday. It
is a production write in the weak sense: it reads the off-site repository and starts and removes one
throwaway container; it changes no data.

**Read-only look first:**

```bash
systemctl list-timers 'iri-*'
grep -c '' /var/log/iri-restore-drill.log
```

**Run it** (as root on the production host):

```bash
sudo systemctl start iri-restore-drill.service
sudo tail -n 40 /var/log/iri-restore-drill.log
```

**Expected output** (the numbers differ; the shape and the last line do not):

```text
container runtime: podman
drill image: docker.io/library/postgres:18-alpine@sha256:<64 hex>
restoring latest snapshot dumps from <repository>
  present: <each backup artifact>
restored: <size> backend, <size> keycloak
starting throwaway Postgres (docker.io/library/postgres:18-alpine@sha256:<64 hex>)
verification: flyway_schema_history rows=<n>, backend public tables=<n>, keycloak public tables=<n>
RESTORE DRILL PASSED — the off-site DB backup is recoverable (monitoring artifacts: grafana=1, secrets=1)
```

The `drill image:` line must carry an `@sha256:` digest: that is S-09. Then, in Grafana → Explore →
Prometheus, `time() - basetool_restore_drill_last_success_timestamp` is a few seconds and
`basetool_restore_drill_artifact_ok == 0` returns nothing.

**If it fails:** `FATAL … refusing to run with an unpinned image` means the unit's image pin is
unreadable (`db-backend.container` has no `@sha256:`), which is the defect S-09 guards against: stop
and report it. Any other `FAIL:` line is a restore problem; treat it as the severe incident
[`backup.md`](backup.md) says it is.

**Rollback:** there is nothing to undo. An interrupted drill leaves one container and a work
directory: `sudo -u iri podman rm -f iri-restore-drill` and `rm -rf /var/iri/backup/restore-drill`.

---

## 4. Keycloak: one session for the three realm steps

Steps 5, 6 and 7 are done with `scripts/harden-keycloak-realm.py`, which does exactly what
[`KEYCLOAK_HARDENING_RUNBOOK.md`](KEYCLOAK_HARDENING_RUNBOOK.md) steps 2, 12 and 11 describe, as a
dry run first, with a rollback file. It was proven against a throwaway Keycloak 26.8 with the
sandbox realm (`scripts/harden-keycloak-realm.integration.py`: the admin forced to set up OTP and
asked for the code afterwards, a member never asked, the session windows on the realm, the
forgot-password mail through a test SMTP sink, the rollback). **Not tested there, and checked by you
at step 7:** a login that comes in through Discord, because the test realm has no Discord provider.

**The identity** is the short-lived `basetool-provisioner` of the runbook's § 0.3. For this script it
needs `manage-realm` (settings, flows, required actions), `manage-identity-providers` (binding the
post-login flow onto `discord`) and, optionally, `manage-clients` (only to *list* client session
overrides above the new windows; without it the script says it did not check). Not `realm-admin`.
Delete it at the end (step 7, *Clean up*).

```bash
cd /
install -d -m 0700 /root/kc-harden /root/kc-harden/keycloak
install -m 0700 harden-keycloak-realm.py provision-keycloak-mobile-client.py /root/kc-harden/
install -m 0600 keycloak/session-windows.json /root/kc-harden/keycloak/
KCCFG=/opt/keycloak/data/tmp/kcadm.config
kc() { sudo -u iri podman exec -i keycloak /opt/keycloak/bin/kcadm.sh "$@" --config "$KCCFG"; }
KCADM="sudo -u iri podman exec -i keycloak sh -c 'exec /opt/keycloak/bin/kcadm.sh \"\$@\" --config $KCCFG' kcadm"
sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config truststore \
    --trustpass - /run/secrets/keystore.p12 --config "$KCCFG"
sudo -u iri podman exec -it keycloak /opt/keycloak/bin/kcadm.sh config credentials \
    --server https://localhost:18443/auth --realm iri --client basetool-provisioner --config "$KCCFG"
kc get realms/iri --fields realm,sslRequired,resetPasswordAllowed,browserFlow
```

Expected from the last line: `{ "realm" : "iri", "sslRequired" : "external", "resetPasswordAllowed" : true, "browserFlow" : "browser" }`
(the key order may differ). If a call answers `No server specified`, the session is gone: run the two
`config` commands again. The rollback basis, taken **once**, before step 5:

```bash
kc get realms/iri                 > /root/kc-harden/realm.before.json
kc get authentication/flows -r iri > /root/kc-harden/flows.before.json
kc get identity-provider/instances/discord -r iri > /root/kc-harden/discord.before.json
kc get authentication/required-actions -r iri     > /root/kc-harden/required-actions.before.json
```

`realm.before.json` holds the SMTP settings with the password masked; keep the directory `0700` and
remove it at the end. Each `--apply` below also writes its own rollback file next to the script
(`/root/kc-harden/keycloak-hardening.before.json`, mode `0600`), keeps it across re-runs, and
`--rollback <file> --apply` puts the bound values back.

---

## 5. Keycloak step 2: forgot password and the realm's sender

**Decision first, with the console:** *Realm settings → Email → Test connection*. It mails the admin
who clicks it, so it cannot be scripted. Mail arrives → "Forgot password" stays on; mail does not
arrive → switch it off until the sender works.

```bash
python3 /root/kc-harden/harden-keycloak-realm.py --realm iri --kcadm-command "$KCADM" --step 2
```

Dry run; it writes nothing. **Expected:**

```text
[step 2 - Forgot password and the realm's SMTP sender]
  forgot-password link on; smtpServer host=<host> port=<port> from=<address> auth=<...> password set
  The sender's own test is the console's Realm settings > Email > Test connection; ...

No changes: the selected steps are in shape.
```

If the sender is missing it prints `WARNING: the link is offered but the realm has no sender`. To
switch the link off: add `--reset-password off`, read the plan (`~ forgot-password link: on ->
off`, exit `2`), then the same command with `--apply`. **Verify:** the `[verify]` block says
`the selected steps are in shape`, and `kc get realms/iri --fields resetPasswordAllowed` agrees.
**Rollback:** `--rollback /root/kc-harden/keycloak-hardening.before.json --apply`, or `kc update
realms/iri -s resetPasswordAllowed=true`. The sender itself (host, user, password) is never changed
by this step on production.

---

## 6. Keycloak step 12: session windows

**The decision is the owner's, and the numbers live in one file.** `scripts/keycloak/session-windows.json`
has two profiles: `active` (what the realm has today: 30 d idle, 180 d maximum, with and without
remember-me) and `proposal` (30 d idle, **90 d** maximum). The provisioner
(`scripts/provision-keycloak-realm.py`) reads `active` for the realm's two SSO windows, so the two
scripts cannot disagree. Why the proposal keeps idle at 30 d: the exchange clients carry client
overrides of 30 d idle and 90 d maximum, and Keycloak refuses a client override above the realm's
window; 90 d is also what a device compromised in March stops being useful at. `basetool-android`
carries 30 d / 180 d overrides that the realm provisioner clamps to the realm.

1. **Pick the numbers.** Accept the proposal, or name others. A shorter *idle* than 30 d also needs
   the exchange client constants in the provisioner changed in the same PR.
2. **A pull request** makes the choice `active` in `session-windows.json` (swap the values) — ask for
   it; it is a four-line change plus the docs. Merge and release as usual.
3. **Apply to the realm**, on the host, with the script from that release:

```bash
python3 /root/kc-harden/harden-keycloak-realm.py --realm iri --kcadm-command "$KCADM" --step 12
python3 /root/kc-harden/harden-keycloak-realm.py --realm iri --kcadm-command "$KCADM" --step 12 --apply
```

**Expected (dry run):**

```text
[step 12 - SSO session windows ('active' of session-windows.json)]
  ~ ssoSessionMaxLifespan: 15552000 -> 7776000
  ~ ssoSessionMaxLifespanRememberMe: 15552000 -> 7776000
    (one partial update of the realm)
  REVIEW: client 'basetool-android' overrides above the realm (client.session.max.lifespan=15552000 > 7776000); run scripts/provision-keycloak-realm.py, which clamps the app and exchange clients

[dry-run] 2 change(s) planned; nothing was written. Re-run with --apply.
```

Exit `2`. After `--apply` the `[verify]` block says the step is in shape. **Then re-run the realm
provisioner** ([`INGEST_KEYCLOAK_SETUP.md`](INGEST_KEYCLOAK_SETUP.md#new-or-out-of-date-realm-run-the-provisioner),
copy `keycloak/session-windows.json` next to it): its dry run plans the Android client's maximum from
180 d to 90 d, and a second run must report *No changes*.

**What members notice:** a session older than the new maximum ends at its next refresh and the member
signs in again; the Android app does the same after its refresh token passes the maximum. A support
question, not an outage.

**Rollback:** `--rollback /root/kc-harden/keycloak-hardening.before.json --apply` (puts the four
numbers back), then make the old numbers `active` again in the file, or the provisioner puts the new
ones back.

---

## 7. Keycloak step 11: OTP for admins — last

> [!danger] Keep a second admin session open in another browser while this runs
> Close nothing until the verification below has passed in a *third* place, a private window. After
> the bind an admin account **cannot authenticate to kcadm any more** — the provisioner service
> account can, which is why §4 uses it.

What the script builds (all additive; it never edits the built-in `browser` flow and never deletes
anything):

- a copy of `browser`, `browser-admin-otp`, with an `Admin OTP (browser)` sub-flow at the end of its
  `forms` flow: conditional on the realm role `Admin` **and** on no OTP having been presented in this
  login yet, with an OTP Form. An admin who already has a device is asked once, by the built-in
  second-factor step; an admin without one is made to set one up at that login.
- the required action **Configure OTP**, registered and enabled if it is not. Without it an admin
  with no device is refused with *credential setup required* instead of being asked to set one up
  (found on the sandbox realm, which had not registered it).
- a small top-level flow `post-broker-admin-otp` (conditional on `Admin`, OTP Form) as the Discord
  provider's **post login flow**, so a login that comes in through Discord is gated too. The
  runbook used to say "select the flow from 11a" there; that would have re-run the username and
  password pages for a brokered user, and is corrected.
- the bindings: the realm's browser flow and the Discord provider's post login flow.

```bash
python3 /root/kc-harden/harden-keycloak-realm.py --realm iri --kcadm-command "$KCADM" --step 11
```

**Expected (dry run, shape):**

```text
[step 11 - OTP for holders of the realm role 'Admin']
  + copy flow 'browser' as 'browser-admin-otp'
  + browser flow: the block 'Admin OTP (browser)' does not exist yet
  + top-level flow 'post-broker-admin-otp'
  + post-broker flow: the block 'Admin OTP (post-broker)' does not exist yet
  ~ realm browserFlow: browser -> browser-admin-otp
  ~ identity provider 'discord' postBrokerLoginFlowAlias: <none> -> post-broker-admin-otp
  (required action CONFIGURE_TOTP appears too when it is missing or disabled)

[dry-run] <n> change(s) planned; nothing was written. Re-run with --apply.
```

If the provider is not found, the post-broker half is skipped with a note: stop and look why before
applying. Then:

```bash
python3 /root/kc-harden/harden-keycloak-realm.py --realm iri --kcadm-command "$KCADM" --step 11 --apply
python3 /root/kc-harden/harden-keycloak-realm.py --realm iri --kcadm-command "$KCADM" --step 11
```

The first ends with `[verify]` and `the selected steps are in shape`; the second prints *No
changes*. **Verify with real accounts — both paths, three places:**

1. An admin, username and password → asked to **set up** the authenticator (first time) or for the
   **code** (every time after), once, not twice.
2. The same admin through **Discord** → the code (or the set-up page) is demanded. *This is the one
   path the repository's test cannot reach.*
3. A non-admin member, both ways → no OTP.

**Rollback** — needs no admin login, only the provisioner session:

```bash
python3 /root/kc-harden/harden-keycloak-realm.py --realm iri --kcadm-command "$KCADM" --step 11 \
  --rollback /root/kc-harden/keycloak-hardening.before.json --apply
```

It binds the built-in `browser` flow again and unbinds the post login flow. The two flows it built
stay in the realm, unbound; delete them in the console when they are no longer wanted. An admin who
configured an OTP device keeps it and is asked for it by the built-in flow.

**Clean up — after all three Keycloak steps:**

```bash
kc get clients -r iri -q clientId=basetool-provisioner --fields id          # note the id, then delete the client in the console
sudo -u iri podman exec keycloak rm -f "$KCCFG"
rm -rf /root/kc-harden
kc get clients -r iri -q clientId=basetool-provisioner --fields clientId    # must come back empty (the session is gone; run it before the rm if you want the proof)
```

Then write step 11's flow down: the sanitizer drops `authenticationFlows`
([`KEYCLOAK_HARDENING_RUNBOOK.md`](KEYCLOAK_HARDENING_RUNBOOK.md#after-the-twelve) says so); the
structure is now defined by `scripts/harden-keycloak-realm.py` itself, which is the record.

---

## 8. Trusted Types: enforce

The CSP's Trusted Types directives ship in report-only mode (ADR-0239). The switch is the only step
of that decision still open; the full procedure, with the reasoning, is
[`deployment.md` → *Trusted Types: report, then enforce*](deployment.md#trusted-types-report-then-enforce).

**Precondition** — at least a week of ordinary use since the release with the report-only policy went
live, and `DialogA11yE2eTest` green on `main`. In Grafana → Explore → Prometheus the query must
return **nothing**:

```text
sum(increase(basetool_client_error_total{kind="csp_violation"}[7d])) > 0
```

A hit is a violation, not necessarily a Trusted Types one; the deployment doc names how to find the
sink. Do not enforce around it.

**Apply** (production write; as root, from `/`; `${UCTL}` from the deployment doc's *Shell
conventions*):

```bash
cd /
cp -p /var/iri/code/.env /var/iri/code/.env.backup-$(date +%Y%m%d-%H%M%S)
sudo -u deploy "${EDITOR:-vi}" /var/iri/code/.env      # set APP_SECURITY_TRUSTED_TYPES=enforce (one line)
grep -c '^APP_SECURITY_TRUSTED_TYPES=' /var/iri/code/.env       # 1
sudo -u deploy /var/iri/code/scripts/render-env-d.py \
  --env /var/iri/code/.env --templates /var/iri/code/quadlet/env.d --out /var/iri/code/env.d
grep -c '^APP_SECURITY_TRUSTED_TYPES=enforce$' /var/iri/code/env.d/frontend.env   # 1
${UCTL} restart frontend.service
```

**Expected:** both `grep -c` print `1`; the restart returns once the unit is healthy (sessions live in
Redis). Effective mode: Prometheus `basetool_trusted_types_mode == 1` with `mode="enforce"`, or
`curl -sI https://profit-base.online/ | grep -i '^content-security-policy'` shows the directives at the
end of the enforced header. Watch for a day: `sum(increase(basetool_client_error_total{kind="csp_violation"}[1h]))`
stays empty and `ClientErrorSpike` silent.

**Rollback:** set the line to `APP_SECURITY_TRUSTED_TYPES=report` (or delete it), render `env.d/`
again with the same command, `${UCTL} restart frontend.service`.

---

## 9. Promote v1.14.0

**Not cut yet** — the latest release on 2026-10-10 is v1.13.7. This step is the standard promotion
([`deployment.md` → *Promoting to production*](deployment.md#promoting-to-production)) with the
checks this release needs. Read the new section of `CHANGELOG.md` for host steps before you start;
a release that carries some gets its own runbook, as 1.11.0 and the exchange go-live did.

1. **Cut it:** *Actions → Release · Prepare → Run workflow*, version `1.14.0`; merge the
   `chore(release): v1.14.0` PR. The publish job tags the merge commit and releases (needs step 1's
   key in place or the repository secret).
2. **Read-only check** (the app floor this release commits is 17 / 17):

```bash
curl -s https://api.profit-base.online/api/v1/app/version-policy
```

   If production answers anything else, the promotion would change the floor — correct the literal
   in a PR first, or accept the change deliberately.
3. **Test it on testing first** if the release is not already there: `gh workflow run
   promote-testing.yml -f version=1.14.0`.
4. **Promote** (from `main`; this is the production write):

```powershell
gh workflow run promote.yml -f version=1.14.0
```

   Three gates run in order: the vulnerability scan of the three images, the approval on the
   `production` environment (**you approve in the GitHub UI**), and the signature and one-release
   check before the five tags move to `:stable`.
5. **Expected on the host,** within about five minutes: the `deploy.sh` tick logs `release parts: …`,
   applies the release in one restart window, and the health gate passes; no `DeployRolledBack` /
   `DeployFailed` alert. Check the version chip in the web app and
   `https://profit-base.online/` answering 200.

**Rollback:** `gh workflow run promote.yml -f version=1.13.7` (the previous release); the health
gate already rolls a failed deploy back by itself and records the backoff. A rollback keeps the
database: if 1.14.0 carried a migration, read its note in the changelog before rolling back.

---

## Order that avoids surprises

Steps 1 and 2 are independent of everything else and can go first. 3 follows 2. The three Keycloak
steps run in the order 5, 6, 7 inside one session (§4); 7 is last because it takes your admin
account away from kcadm. 8 waits for its quiet week. 9 is the release, whenever you want it; if step
6 is part of the release (the file changes), promote first, then apply the realm change, so the
provisioner and the realm agree.
