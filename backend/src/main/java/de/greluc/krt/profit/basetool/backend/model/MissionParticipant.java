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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.BatchSize;

/** Mission Participant JPA entity. */
@Entity
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
public class MissionParticipant extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @ManyToOne
  @JoinColumn(name = "mission_id", nullable = false)
  @com.fasterxml.jackson.annotation.JsonIgnore
  private Mission mission;

  @ManyToOne
  @JoinColumn(name = "user_id")
  private User user;

  private String guestName;

  /**
   * The org units (Staffel and/or Spezialkommandos) this participant is affiliated with for this
   * mission, stamped at sign-up time. For a registered user this is auto-derived from their
   * memberships (empty when they belong to none, the Staffel or SK when they belong to one, both
   * when they belong to both); for a guest it is the caller-submitted, authorization-filtered
   * selection. Replaces the former single {@code squadron_id} FK so a member of both a Staffel and
   * an SK no longer loses one of the two affiliations.
   *
   * <p>Mapped EAGER (matching the eager fetch of the former {@code @ManyToOne squadron}) because
   * the slim participant endpoints map the entity to its DTO after the service transaction has
   * closed and {@code open-in-view} is disabled — a lazy collection would raise {@code
   * LazyInitializationException} at mapping time. {@code @BatchSize} keeps the eager load from
   * degenerating into one SELECT per participant when a roster is rendered.
   *
   * <p>{@code @OptimisticLock(excluded = true)} would be inappropriate here: unlike the parent
   * mission's participant collection, mutating a participant's own affiliations is a genuine edit
   * of that participant row and is covered by the participant's own {@code @Version}.
   */
  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "mission_participant_org_unit",
      joinColumns = @JoinColumn(name = "mission_participant_id"),
      inverseJoinColumns = @JoinColumn(name = "org_unit_id"))
  @BatchSize(size = 50)
  @Getter(AccessLevel.NONE)
  @Setter(AccessLevel.NONE)
  private Set<OrgUnit> orgUnits = new LinkedHashSet<>();

  @ManyToOne
  @JoinColumn(name = "desired_mission_job_type_id")
  private JobType desiredMissionJobType;

  @ManyToOne
  @JoinColumn(name = "planned_task_job_type_id")
  private JobType plannedMissionJobType;

  /**
   * Derived flag: {@code true} exactly when {@link #plannedMissionJobType} is the designated
   * mission-lead ("Einsatzleiter") job type ({@code JobType.isMissionLead}). Maintained by the
   * service wherever the planned job type changes and backed by a partial unique index ({@code
   * uq_mission_participant_single_lead}, V206) on {@code (mission_id) WHERE
   * is_mission_lead_participant} so two managers cannot concurrently make two different
   * participants of the same mission the Einsatzleiter (REQ-MISSION-013, #1113). Stored (not
   * computed) because the partial unique index needs a column and the invariant depends on a join
   * to {@code job_type}.
   */
  @Column(name = "is_mission_lead_participant", nullable = false)
  private boolean missionLeadParticipant = false;

  @Column(columnDefinition = "TEXT")
  private String comment;

  private Instant startTime;
  private Instant endTime;

  @Enumerated(EnumType.STRING)
  private PayoutPreference payoutPreference = PayoutPreference.PAYOUT;

  /**
   * Returns an unmodifiable view of this participant's org-unit affiliations. The Lombok getter is
   * suppressed for {@code orgUnits} so callers cannot mutate the entity-owned set through the
   * accessor (CWE-374); use {@link #setOrgUnits(Collection)} to replace the affiliations.
   *
   * @return an unmodifiable snapshot of the affiliated org units; never {@code null}, possibly
   *     empty.
   */
  public Set<OrgUnit> getOrgUnits() {
    return Collections.unmodifiableSet(orgUnits);
  }

  /**
   * Replaces this participant's org-unit affiliations with the given org units, mutating the
   * entity-owned {@link LinkedHashSet} in place (clear + re-add) so Hibernate's dirty-checking sees
   * the change on the managed collection rather than a swapped reference. A {@code null} argument
   * clears all affiliations.
   *
   * @param orgUnits the new affiliations, or {@code null} to clear them.
   */
  public void setOrgUnits(Collection<? extends OrgUnit> orgUnits) {
    this.orgUnits.clear();
    if (orgUnits != null) {
      this.orgUnits.addAll(orgUnits);
    }
  }
}
