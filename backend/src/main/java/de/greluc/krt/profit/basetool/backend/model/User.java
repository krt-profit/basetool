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

package de.greluc.krt.profit.basetool.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.jetbrains.annotations.Nullable;

/** User JPA entity. */
@Entity
@Table(name = "app_user")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class User extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  private UUID id;

  @Override
  public boolean isNew() {
    return getVersion() == null;
  }

  private String username;
  private String displayName;
  private String email;

  @Min(1)
  @Max(20)
  @Column(name = "user_rank")
  private Integer rank;

  @Column(columnDefinition = "TEXT")
  private String description;

  @Column(name = "last_read_announcement_id")
  private UUID lastReadAnnouncementId;

  @Column(name = "in_keycloak")
  private boolean inKeycloak = true;

  /**
   * The Keycloak account's {@code enabled} flag as of the last roster sync (V230, ADR-0129).
   *
   * <p>Separate from {@link #inKeycloak}, which records whether the sync still <em>saw</em> the
   * account at all. Presence and being enabled are different facts with different remedies, and
   * only the acting-member liveness guard reads either: an ordinary caller needs a token, and a
   * deleted or disabled account stops being issued one. Defaults to {@code true} so a row created
   * before its first sync is not locked out by a flag nothing has written yet.
   */
  @Column(name = "enabled_in_keycloak", nullable = false)
  private boolean enabledInKeycloak = true;

  @Nullable
  @Column(name = "join_date")
  private LocalDate joinDate;

  /**
   * The user's personal default payout preference. Pre-fills the per-participant {@code
   * payoutPreference} when this user signs up to a mission (see {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService#addParticipant}). {@code null}
   * means the user has expressed no explicit choice, in which case sign-up falls back to {@link
   * PayoutPreference#PAYOUT}; the value is never auto-populated. Editing it is a forward-only
   * default — it does not rewrite existing {@link MissionParticipant} rows. REQ-MISSION-002.
   */
  @Nullable
  @Enumerated(EnumType.STRING)
  @Column(name = "default_payout_preference")
  private PayoutPreference defaultPayoutPreference;

  /**
   * Opt-in flag: when {@code true}, the user's owned {@link PersonalBlueprint} rows are counted in
   * the leadership blueprint-availability overview and the item-order blueprint-coverage view for
   * <em>every</em> org unit, not only the ones the user is a member of — so a Staffel member's
   * blueprint can satisfy an SK order's coverage even across org-unit boundaries. Defaults to
   * {@code false}, preserving the strict org-unit scoping for everyone who does not opt in. The
   * widening is read-only and exposes the owner by display name only (never the {@code sub} or
   * e-mail); the viewer-access gates are unchanged. REQ-INV-018 / ADR-0024.
   */
  @Column(name = "share_blueprints_globally", nullable = false)
  private boolean shareBlueprintsGlobally = false;

  /**
   * The user's linked Discord account id (a numeric snowflake, stored as text). Written by the
   * Keycloak Discord identity-provider mapper into the {@code discord_user_id} token claim and
   * persisted here on login, so a returning Discord user is recognised. {@code null} for users who
   * only ever signed in with credentials; at most one {@link User} per Discord id (DB-unique). This
   * column merely records the federation link — the guild + KRT-Mitglied membership gate itself
   * lives in the Keycloak SPI, never here. Epic #720, Track 1 / REQ-DATA-006.
   */
  @Nullable
  @Column(name = "discord_user_id", unique = true)
  private String discordUserId;

  /**
   * The user's per-guild Discord server nickname (the {@code nick} they carry inside the
   * das-kartell guild), captured best-effort at each Discord login and surfaced to an admin in the
   * Discord registration-approval queue so the decision can be tied to a recognisable in-server
   * identity (REQ-DATA-008). Written from the {@code discord_guild_nickname} token claim, which the
   * Keycloak Discord IdP fills from the guild-member call — Discord's plain profile has no
   * nickname. {@code null} when the user set no server nickname, never logged in via Discord, or
   * the optional capture mappers are not configured. Display-only: it grants nothing and is exposed
   * only on the admin-only approval queue, never in any shared user DTO.
   */
  @Nullable
  @Column(name = "discord_guild_nickname")
  private String discordGuildNickname;

  /**
   * Account approval lifecycle (epic #720, Track 1, REQ-SEC-017 — fail-safe default). A brand-new
   * non-admin registration is {@link ApprovalStatus#PENDING} (no authorities granted — only {@code
   * ROLE_PENDING_APPROVAL}) until an admin approves, whether it arrived via Discord or credentials;
   * Keycloak {@code ADMIN}-realm-role holders and all pre-existing (V173-backfilled) rows are
   * {@link ApprovalStatus#ACTIVE}. The field-level default stays {@code ACTIVE} so the
   * admin-bootstrap path and direct test/seed construction yield an active member, but both
   * creation paths in {@link UserService} ({@code syncUser(Jwt)} and {@code
   * syncUser(KeycloakUserDto)}) explicitly set {@code PENDING} for every new non-admin — so the
   * PENDING decision never depends on detecting the Discord {@code discord_user_id} claim (a
   * missing claim mapper can no longer let a federated login skip approval).
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "approval_status", nullable = false)
  private ApprovalStatus approvalStatus = ApprovalStatus.ACTIVE;

  /** When the registration was approved/rejected; {@code null} while still {@code PENDING}. */
  @Nullable
  @Column(name = "approved_at")
  private Instant approvedAt;

  /**
   * The admin who approved/rejected this registration; {@code null} while still {@code PENDING}.
   */
  @Nullable
  @Column(name = "approved_by_id")
  private UUID approvedById;

  /**
   * Whether the account may be granted its full authorities. {@code true} only for {@link
   * ApprovalStatus#ACTIVE}; {@code PENDING} and {@code REJECTED} accounts receive no authorities.
   *
   * @return {@code true} iff the approval status is {@link ApprovalStatus#ACTIVE}
   */
  public boolean isApproved() {
    return approvalStatus == ApprovalStatus.ACTIVE;
  }

  public String getEffectiveName() {
    return (displayName != null && !displayName.isBlank()) ? displayName : username;
  }

  // @ToString.Exclude on the LAZY @ManyToMany so a logged User outside of a
  // Hibernate session does not trigger LazyInitializationException — and so
  // toString() does not recurse User -> Role.permissions -> ... when Role
  // proxies are subsequently hydrated.
  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "user_roles",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  @ToString.Exclude
  private Set<Role> roles = new HashSet<>();
}
