# ADR-0308 — Operators log in under a personal account, and root never over SSH

- **Status:** Accepted
- **Date:** 2026-10-23
- **Deciders:** @greluc
- **Related:** REQ-OPS-052 · REQ-OPS-016 · ADR-0188 (the host bootstrap is an Ansible role) ·
  REQ-OBS-019

## Context

The production host was administered as `root` over SSH: a read of its journal on 2026-10-23
found 66 accepted logins in fourteen days, every one of them `root`. sshd ran with the RHEL
default `PermitRootLogin without-password`. The cloud-init account `sysadm` existed on both hosts
with the same keys and `NOPASSWD: ALL`, and was never used on production. On the testing host
the CIS remediation had already set `PermitRootLogin no`, and `sysadm` was the way in.

Two consequences follow from working as root. Whoever holds one of the keys is root on the host
with nothing in between. And the auth log names `root` for every session, so it cannot say who
acted. `SshRootLoginAccepted` fired on every routine session and therefore carried no signal.

## Decision

1. **One personal admin account per operator, `greluc`**, created and kept by the role
   (`23-admin-access.yml`). It logs in only by key. Its `authorized_keys` holds exactly the keys
   the inventory names. It is in `wheel`, so it becomes root through `%wheel ALL=(ALL) ALL`:
   **with its password**. The password is set by the operator on the host and is never written by
   the role, the repository or the inventory.
2. **Root never logs in over SSH.** `00-basetool-access.conf` sets `PermitRootLogin no` and
   `AllowUsers <admin>`, first in `sshd_config.d` so no later drop-in can loosen it. Root's
   `authorized_keys` is emptied.
3. **Locking root is a second phase that proves the first.** The role refuses
   `basetool_host_ssh_lock_root: true` unless the run itself connects as the admin account and
   becomes root with its sudo password, and the account has a password. A lockout therefore
   cannot happen by a run that has not just used the new way in.
4. **Reading needs no password.** `adm` and `systemd-journal` for the logs, plus a NOPASSWD grant
   for the read-only `podman` sub-commands as the service user, `sshd -T` and
   `fail2ban-client status`. One `sudo -v` covers a conformance run, because the admin's sudo
   timestamp is global and lasts ten minutes.
5. **`sysadm` is retired** once root is locked, with its `NOPASSWD: ALL` grant.

## Alternatives considered

- **Keep `sysadm` as the operator account.** No new account, but a generic name keeps the log
  unable to say who acted, and its `NOPASSWD: ALL` makes the key the only barrier.
- **Sudo without a password.** Nothing changes for automation, but a stolen key is root at once.
  The read grant covers what automation and an assistant need without the password.
- **A per-tty sudo timestamp.** Narrower, but every SSH command of the conformance suite would
  need its own password prompt. The global timestamp is bounded to the admin account and ten
  minutes.

## Consequences

- Ansible runs as the admin account with `-K`; `check-conformance.py --ssh <admin>@<host> --sudo`
  runs after `ssh -t <admin>@<host> sudo -v`.
- The provider console stays the only way in if the admin's key and password are both lost; root
  is password-locked, so the recovery path is the provider's rescue system.
- `SshRootLoginAccepted` becomes a page that should never fire, and *Accepted logins* on the
  *SSH / host auth* dashboard shows who logged in.
