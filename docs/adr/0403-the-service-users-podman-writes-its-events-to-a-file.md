# ADR-0403 — The service user's podman writes its events to a file, not to the journal

- **Status:** Accepted
- **Date:** 2026-11-21
- **Deciders:** @greluc
- **Related:** REQ-OBS-014 · REQ-OPS-064 · ADR-0163 ·
  `ansible/roles/basetool_host/tasks/50-podman.yml` · `scripts/check-conformance.py`
  (`podman-exporter-images-current`)

## Context

`prometheus-podman-exporter` runs as a user unit of `basetool` and links libpod in process. It reads
the image list once when it starts and again on every podman event of type image. Its `podman_image_*`
series should therefore follow each pull, untag and removal.

They do not. On both hosts the exporter reported the image list it read at start. Production's
exporter started at 10:11:33 UTC on 2026-11-21 and lacked the backend, frontend and ingest images
that the 10:17 deploy pulled. Testing's exporter started on 2026-11-18 and lacked 7 of the 16 images
its running containers used. The pull and untag events are in the journal, under `basetool`'s
`_UID`, from after both starts. The exporter's journal shows `starting podman event streamer` and
nothing after it: no error, no restart.

The cause is where those events are stored and who may read them:

- `basetool` is a system account. Its uid (994 on production, 992 on testing) is below
  `SYS_UID_MAX` 999. journald gives a user its own `user-<uid>.journal` only above that limit and
  writes a system account's entries into `system.journal`.
- `system.journal` is readable by root and by the groups `systemd-journal`, `adm` and `wheel`.
  `basetool` is in none of them, so it can read no journal file at all.
- Podman's events backend on Rocky is `journald`. Its reader opens the journal, matches
  `SYSLOG_IDENTIFIER=podman` and its own `_UID`, seeks to the tail and waits for new entries.
  `sd_journal_open` succeeds when there is no readable file, so the reader waits forever and
  reports nothing.

The same cause empties `podman logs` for `basetool`: the containers log through journald, and
`podman logs --tail 3 redis-session` as `basetool` prints nothing and exits 0, while
`journalctl CONTAINER_NAME=redis-session` as the operator shows the lines.

Nothing in the repository reads podman events from the journal. Alloy ships only journal entries
that carry a `CONTAINER_NAME`, which an event entry does not.

## Decision

**The service user's podman writes its events to a file.** The role writes the drop-in
`~basetool/.config/containers/containers.conf.d/60-events.conf`:

```toml
[engine]
events_logger = "file"
```

Podman then appends each event to `$XDG_RUNTIME_DIR/libpod/tmp/events/events.log`, a file on the
user's tmpfs that `basetool` owns. It caps the file at `events_logfile_max_size` (the vendor
default, 1 MB) and rotates it by renaming a copy of the newer half over it, between a begin and an
end marker. The streaming reader reopens the renamed file and skips the copied half.

The role reads the setting back with `podman info --format '{{.Host.EventLogger}}'` and fails the
run unless it says `file`, because a later drop-in or a main `containers.conf` could override it.
When the drop-in changes, the role restarts the exporter, which chooses its reader when it starts.
`check-conformance.py --only podman-exporter-images-current` checks the result: every image a
running container uses must be in the exporter's `podman_image_info`.

## Consequences

- **Positive.** The exporter follows pulls, untags and removals as they happen, so a superseded
  digest name (REQ-OPS-064) leaves its series without a restart. `podman events` as `basetool`
  works.
- **Negative / cost.** New events are no longer in the journal, so `journalctl PODMAN_TYPE=…`
  shows only older ones. Containers started before the switch keep writing to journald until their
  next restart: their healthcheck timers and conmon exit commands carry `--events-backend
  journald`. The file lives on tmpfs and holds about 1 MB. On testing that is minutes to an hour,
  because 99% of its roughly 84,000 events a day are `health_status`. Reading the events takes the
  operator's sudo password: `sudo -u basetool XDG_RUNTIME_DIR=/run/user/$(id -u basetool) podman
  events --since 1h --stream=false`.
- **Residual risk.** The reader polls every 250 ms and reopens a rotated file without draining the
  old one. An event written in the last poll interval before a rotation is therefore missed, until
  the next image event re-reads the whole list. The exporter's vendored podman 5.8.4 does not know
  podman 6's `artifact` event type, and the exporter does not check a read error, so such an event
  would crash it. Its unit restarts it on failure. Nothing here uses `podman artifact`.
- **Neutral.** Container output stays in the journal (log driver `journald`), and `podman logs` as
  `basetool` stays empty. Operators read container output with `journalctl CONTAINER_NAME=<name>`,
  as `check-conformance.py` does.

## Alternatives considered

- **Add `basetool` to `systemd-journal`.** This fixes events and `podman logs` alike, but the
  account that runs every container could then read the whole host journal: every account's SSH
  logins, sudo command lines and kernel messages. Rejected for that reach.
- **Give `basetool` a uid above `SYS_UID_MAX`**, so that journald writes it a user journal.
  Renumbering the account that owns the container store, every bind-mounted directory and the
  subuid mapping is out of proportion to the defect.
- **A system unit for the exporter with `User=basetool` and `SupplementaryGroups=systemd-journal`.**
  It confines the group to the exporter process, but that process still reads the whole journal,
  and it would run outside the user manager whose containers it watches.
- **Restart the exporter on a timer or after each deploy.** It bounds the staleness without
  removing the cause, and it leaves `podman events` as `basetool` reading nothing.
