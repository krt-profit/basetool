/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.backend.identity.api;

/**
 * Backend {@code basetool_*} metric names, tag keys and bounded tag values of the identity module:
 * sync, registration, consent and the Discord pre-check (REQ-OBS-011). The names and values are
 * part of the dashboards and alert rules under {@code monitoring/}.
 */
public final class IdentityMetricNames {

  /**
   * Counter {@code basetool_keycloak_sync_fetch_failures_total} (untagged). Bumped when the daily
   * user-sync roster fetch against the Keycloak Admin API throws and is swallowed into an empty
   * list, which would otherwise look like a legitimately empty roster (REQ-OBS-011).
   */
  public static final String KEYCLOAK_SYNC_FETCH_FAILURES = "basetool.keycloak.sync.fetch.failures";

  /**
   * Counter {@code basetool_admin_registration_auto_activated_total} (untagged). Bumped when the
   * reconciliation inserts a new {@code app_user} row that is {@code ACTIVE} on arrival because the
   * subject holds the Keycloak ADMIN realm role (REQ-SEC-017). Normally zero (REQ-OBS-011).
   */
  public static final String ADMIN_REGISTRATION_AUTO_ACTIVATED =
      "basetool.admin.registration.auto.activated";

  /**
   * Counter {@code basetool_user_callsign_collisions_total} (untagged). Bumped when a login
   * presents a subject that matches no {@code app_user} row while another row holds the same {@code
   * preferred_username} (ADR-0142).
   *
   * <p>Normally zero. Untagged because a username is unbounded and PII (REQ-OBS-011, REQ-OBS-004).
   */
  public static final String USER_CALLSIGN_COLLISIONS = "basetool.user.callsign.collisions";

  /**
   * Counter {@code basetool_user_sync_failures_total} (untagged). Bumped once per user whose
   * reconciliation threw during a Keycloak sync run; such a user keeps its last local roles
   * (REQ-OBS-011).
   */
  public static final String USER_SYNC_FAILURES = "basetool.user.sync.failures";

  /**
   * Counter {@code basetool_user_discord_link_collisions_total} (untagged). Bumped when a
   * reconciliation skips writing a Discord snowflake that a <em>different</em> {@code app_user} row
   * already holds.
   *
   * <p>Normally zero; stays non-zero on every run until the duplicate accounts are consolidated.
   * Untagged because a snowflake is unbounded personal data (REQ-OBS-004, REQ-OBS-011).
   */
  public static final String USER_DISCORD_LINK_COLLISIONS = "basetool.user.discord.link.collisions";

  /**
   * Counter {@code basetool_discord_precheck_total} — tag {@code outcome} ({@link
   * #DISCORD_PRECHECK_OK} / {@link #DISCORD_PRECHECK_UNAUTHORIZED} / {@link
   * #DISCORD_PRECHECK_DISABLED}). The endpoint sits outside {@code /api/**}, the rate limiter and
   * the {@code basetool_http_error} funnel.
   */
  public static final String DISCORD_PRECHECK = "basetool.discord.precheck";

  /** Gauge {@code basetool_registration_pending_count} — pending user registrations. */
  public static final String REGISTRATION_PENDING = "basetool.registration.pending.count";

  /** Gauge {@code basetool_registration_pending_oldest_age_seconds}. */
  public static final String REGISTRATION_PENDING_OLDEST_AGE =
      "basetool.registration.pending.oldest.age";

  /**
   * Gauge {@code basetool_users_pending_deletion_count} — accounts present locally but already gone
   * from Keycloak, waiting for an admin to complete their deletion (REQ-SEC-059).
   */
  public static final String USERS_PENDING_DELETION = "basetool.users.pending.deletion.count";

  /**
   * Gauge {@code basetool_users_pending_deletion_oldest_age_seconds} — how long the longest-waiting
   * orphaned account has been waiting, measured from {@code app_user.keycloak_absent_since}
   * (REQ-SEC-059).
   */
  public static final String USERS_PENDING_DELETION_OLDEST_AGE =
      "basetool.users.pending.deletion.oldest.age";

  /** Discord precheck outcome: an existence check ran and answered {@code 200}. */
  public static final String DISCORD_PRECHECK_OK = "ok";

  /** Discord precheck outcome: a missing/invalid shared secret was rejected with {@code 401}. */
  public static final String DISCORD_PRECHECK_UNAUTHORIZED = "unauthorized";

  /** Discord precheck outcome: the feature is unconfigured (blank secret), answered {@code 503}. */
  public static final String DISCORD_PRECHECK_DISABLED = "disabled";

  /**
   * Counter: a user recorded consent to the Terms of Use (REQ-SEC-028). Untagged on purpose — the
   * version is already carried by {@link #TERMS_ACCEPTED_USERS}, and tagging a monotonically
   * growing counter with it would leave a dead series behind after every terms change.
   */
  public static final String TERMS_ACCEPTANCES = "basetool.terms.acceptances";

  /**
   * Gauge: how many users have accepted the terms version this process serves (REQ-SEC-028). The
   * rollout signal — after a terms change the series starts at zero and climbs, and a flat line
   * means people are hitting the gate instead of getting through it.
   */
  public static final String TERMS_ACCEPTED_USERS = "basetool.terms.accepted.users";

  /**
   * Tag: the Terms-of-Use version a measurement belongs to. Bounded by construction — one process
   * serves exactly one version, because the value is a build artifact (REQ-OBS-011).
   */
  public static final String TAG_TERMS_VERSION = "terms_version";

  /**
   * Counter {@code basetool_user_role_changes_total} — tags {@link #TAG_ROLE} (one of the four
   * assignable role codes) and {@link #TAG_ACTION} ({@link #ROLE_ACTION_ASSIGNED} / {@link
   * #ROLE_ACTION_WITHDRAWN}). Bumped once per role written in the Basetool, the bootstrap grant
   * included (REQ-SEC-088, REQ-OBS-011).
   */
  public static final String USER_ROLE_CHANGES = "basetool.user.role.changes";

  /** Tag: the role code of a role change, bounded by the four assignable roles. */
  public static final String TAG_ROLE = "role";

  /** Tag: whether a role was assigned or withdrawn. */
  public static final String TAG_ACTION = "action";

  /** Role-change action: the role was assigned. */
  public static final String ROLE_ACTION_ASSIGNED = "assigned";

  /** Role-change action: the role was withdrawn. */
  public static final String ROLE_ACTION_WITHDRAWN = "withdrawn";

  private IdentityMetricNames() {}
}
