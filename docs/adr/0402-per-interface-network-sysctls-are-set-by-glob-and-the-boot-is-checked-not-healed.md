# ADR-0402 — Per-interface network sysctls are set by glob, and the boot is checked, not healed

- **Status:** Accepted
- **Date:** 2026-11-21
- **Deciders:** @greluc
- **Related:** REQ-OPS-053 · `ansible/roles/basetool_host/tasks/65-firewall.yml` ·
  `scripts/check-conformance.py` (`network-sysctls`)

## Context

`/etc/sysctl.d/90-basetool-network.conf` refuses ICMP redirects on `all`, on `default` and, key by
key, on each physical interface the role found in its facts (`net.ipv6.conf.eth0.accept_redirects
= 0`). IPv6 honours only the interface's own value. An interface takes `default` when it is
created, and on these hosts that happens in the initrd, before any sysctl.d file is read.

A key that names an interface reaches it only if the interface has that name at one of two
moments: when `systemd-sysctl.service` runs, or when udev's `add` rule in `99-systemd.rules` runs
`systemd-sysctl --prefix=/net/ipv6/conf/$name …` for it. That rule does not run on `move`, which
is a rename.

The testing host (a Proxmox VM with cloud-init and two virtio NICs) misses both moments. In the
initrd udev renames `eth0`/`eth1` to `ens18`/`ens19`. `systemd-sysctl.service` then finds no
`eth0` (`Couldn't write '0' to 'net/ipv6/conf/eth0/accept_redirects'`), and a second later
`cloud-init-local` renames the NICs back. After every boot `net.ipv6.conf.eth0.accept_redirects`
and `eth1` read 1, and the role's read-back fails every `--tags firewall` run there. The IPv4
values read 0 only because devinet copies a change to `default` into every interface that never
set the key itself. Production (one NIC, never renamed) shows no difference.

## Decision

**The file sets the per-interface keys by glob:** `net.ipv4.conf.*.accept_redirects`,
`…secure_redirects`, `…send_redirects` and `net.ipv6.conf.*.accept_redirects`, all `0`, next to
the explicit `all` and `default` keys. `systemd-sysctl` expands a glob over the interfaces that
exist when it runs, whatever they are called at that moment. The `add` rule's `--prefix` matches
the same glob for every interface added later. A rename does not reset a value already written to
an interface. procps' `sysctl -p`, which the role runs when the file changes, expands the same
globs (procps-ng 4.0.4 on Rocky 10).

The glob is `*`, not `eth*` and `en*`. With `*` there is no second spelling of the role's
interface pattern. The vendor's own `50-default.conf` and `50-redhat.conf` set `rp_filter` and
`accept_source_route` the same way. And refusing redirects on loopback, a tunnel or a bridge
cannot break a host that routes nothing.

**The role still applies the file only when it changed, and the read-back still fails the run**
when the kernel differs. It does not re-apply the file whenever the live values differ. The
read-back is the only place where the role learns whether the boot applied the file. If the role
re-applied first, a boot that misses a key would show up only as a `changed` line, and the host
would run without the setting from every boot until the next role run. The read-back still reads
each physical interface by name (`basetool_host_network_interface_pattern`), as
`check-conformance.py --only network-sysctls` does.

## Consequences

- **Positive.** The redirect switches hold after every boot, on every interface, renamed or not.
  The file no longer depends on the names the facts reported at run time, so both hosts get the
  same file.
- **Negative / cost.** In `systemd-sysctl` a key that names one interface beats a glob from any
  file, not only from a later one. The read-back's failure message names that case. Loopback and
  any tunnel or bridge refuse redirects too.
- **Neutral.** No udev rule, unit or host-script companion is added, so `host-companions.json`
  and the host-scripts manifest stay as they are. `check-conformance.py` already reads every
  `eth*`/`en*` interface's IPv6 value and fails on 1.

## Alternatives considered

- **A udev rule that mirrors systemd's for `ACTION=="move"`.** It re-applies every key naming the
  new name after a rename, so it fixes this host too. But it adds a file to the host, and the file
  still depends on the names the facts reported. It patches one event instead of removing the
  dependence on names.
- **Re-apply the file whenever the live values differ.** Rejected for the reason above: until the
  next role run, nothing would show that the boot missed a key.
- **Globs `eth*` and `en*`.** The same effect on these hosts, but a second spelling of the
  interface pattern that has to be kept in step with the regex.
- **`net.ifnames=0` on the testing host's kernel command line**, so that no rename happens. That
  is a boot-loader change on one host for a problem that lies in the file.
