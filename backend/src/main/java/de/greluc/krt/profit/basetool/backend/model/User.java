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
   * When the roster sync observed this account missing from Keycloak, i.e. when {@link #inKeycloak}
   * became {@code false} (REQ-SEC-059); {@code null} while the account is present, and cleared when
   * it returns. Its age lets a guard detect local rows left undeleted after the Keycloak account
   * was removed.
   */
  @Nullable
  @Column(name = "keycloak_absent_since")
  private Instant keycloakAbsentSince;

  /**
   * The Keycloak account's {@code enabled} flag as of the last roster sync (ADR-0129), distinct
   * from the presence flag {@link #inKeycloak}. Read only by the acting-member liveness guard;
   * defaults to {@code true} so a row not yet synced is not locked out.
   */
  @Column(name = "enabled_in_keycloak", nullable = false)
  private boolean enabledInKeycloak = true;

  @Nullable
  @Column(name = "join_date")
  private LocalDate joinDate;

  /**
   * The user's default payout preference, pre-filled into the participant's {@code
   * payoutPreference} on mission sign-up (see {@link
   * de.greluc.krt.profit.basetool.backend.service.MissionService#addParticipant}). {@code null}
   * means no choice, falling back to {@link PayoutPreference#PAYOUT}; changes never rewrite
   * existing {@link MissionParticipant} rows (REQ-MISSION-002).
   */
  @Nullable
  @Enumerated(EnumType.STRING)
  @Column(name = "default_payout_preference")
  private PayoutPreference defaultPayoutPreference;

  /**
   * Opt-in flag: when {@code true}, the user's {@link PersonalBlueprint} rows count in the
   * blueprint availability and coverage views of every org unit, not only their own. Read-only
   * widening that exposes the owner by display name only (REQ-INV-018).
   */
  @Column(name = "share_blueprints_globally", nullable = false)
  private boolean shareBlueprintsGlobally = false;

  /**
   * The linked Discord account id (a numeric snowflake as text), persisted from the {@code
   * discord_user_id} token claim on login; {@code null} for credential-only users and unique per
   * user. Records the federation link only; the membership gate lives in the Keycloak SPI
   * (REQ-DATA-006).
   */
  @Nullable
  @Column(name = "discord_user_id", unique = true)
  private String discordUserId;

  /**
   * The user's Discord server nickname in the das-kartell guild, captured best-effort from the
   * {@code discord_guild_nickname} token claim at each Discord login and shown only in the admin
   * registration-approval queue (REQ-DATA-018). {@code null} when unset or not captured; grants
   * nothing.
   */
  @Nullable
  @Column(name = "discord_guild_nickname")
  private String discordGuildNickname;

  /**
   * The member's optional RSI account handle, entered on their own profile and unique across
   * members case-insensitively (REQ-SEC-072). Visible to the member and {@code ADMIN} only.
   */
  @Nullable
  @ToString.Exclude
  @Column(name = "rsi_handle", length = 60)
  private String rsiHandle;

  /**
   * Account approval lifecycle (REQ-SEC-017). Every new non-admin registration is set to {@link
   * ApprovalStatus#PENDING} by {@link UserService} and holds only {@code ROLE_PENDING_APPROVAL}
   * until an admin approves; the field default {@link ApprovalStatus#ACTIVE} covers admin bootstrap
   * and direct construction.
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

  @ManyToMany(fetch = FetchType.LAZY)
  @JoinTable(
      name = "user_roles",
      joinColumns = @JoinColumn(name = "user_id"),
      inverseJoinColumns = @JoinColumn(name = "role_id"))
  @ToString.Exclude
  private Set<Role> roles = new HashSet<>();
}
