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

import com.fasterxml.jackson.annotation.JsonIgnore;
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
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

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

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "mission_id", nullable = false)
  @JsonIgnore
  @ToString.Exclude
  private Mission mission;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "user_id")
  @ToString.Exclude
  private User user;

  private String guestName;

  /**
   * The org units (Staffel and/or Spezialkommandos) this participant is affiliated with for this
   * mission, stamped at sign-up time.
   *
   * <p>For a registered user it is derived from their memberships; for an external participant it
   * is the caller-submitted selection, stored as submitted. Covered by the participant's own
   * {@code @Version}.
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

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "desired_mission_job_type_id")
  @ToString.Exclude
  private JobType desiredMissionJobType;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "planned_task_job_type_id")
  @ToString.Exclude
  private JobType plannedMissionJobType;

  /**
   * Derived flag: {@code true} exactly when {@link #plannedMissionJobType} is the mission-lead
   * ("Einsatzleiter") job type. Maintained by the service and backed by a partial unique index so a
   * mission has at most one Einsatzleiter (REQ-MISSION-013).
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
   * Returns an unmodifiable view of this participant's org-unit affiliations; use {@link
   * #setOrgUnits(Collection)} to replace them.
   *
   * @return the affiliated org units; never {@code null}, possibly empty.
   */
  @NotNull
  @UnmodifiableView
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
