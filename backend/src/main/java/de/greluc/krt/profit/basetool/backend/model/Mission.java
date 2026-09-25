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
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.OptimisticLock;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.UnmodifiableView;

/**
 * Mission JPA entity, edited section by section (REQ-ORG-018).
 *
 * <p>Every mutable column is excluded from the row {@code @Version}; each section has its own
 * counter bumped by an atomic conditional {@code UPDATE}, and {@code @DynamicUpdate} limits each
 * flush to dirtied columns. The row version guards only the full-replace {@code
 * MissionService.updateMission}, which force-increments it.
 */
@Entity
@DynamicUpdate
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString(
    exclude = {
      "participants",
      "assignedUnits",
      "subMissions",
      "financeEntries",
      "steps",
      "objectives"
    })
@BatchSize(size = 100)
public class Mission extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  @OptimisticLock(excluded = true)
  private String name;

  @Column(columnDefinition = "TEXT")
  @OptimisticLock(excluded = true)
  private String description;

  /**
   * Free-text rally point ("Treffpunkt", e.g. "Lobby Mining → ARC-L1") shown in the overview.
   * Nullable. Part of the {@code core} section (guarded by {@link #coreVersion}).
   */
  @Column(name = "meeting_point", length = 200)
  @OptimisticLock(excluded = true)
  private String meetingPoint;

  @Column(length = 2048)
  @OptimisticLock(excluded = true)
  private String calendarLink;

  @OptimisticLock(excluded = true)
  private String status;

  @OptimisticLock(excluded = true)
  private Instant meetingTime;

  @OptimisticLock(excluded = true)
  private Instant plannedStartTime;

  @OptimisticLock(excluded = true)
  private Instant actualStartTime;

  @OptimisticLock(excluded = true)
  private Instant plannedEndTime;

  @OptimisticLock(excluded = true)
  private Instant actualEndTime;

  @Column(name = "is_internal", nullable = false)
  @OptimisticLock(excluded = true)
  private Boolean isInternal = false;

  /**
   * Section counter for the {@code core} patch endpoint (name, description, calendar link, status,
   * operation), independent of {@link AbstractEntity#getVersion()}.
   */
  @Column(name = "core_version", nullable = false)
  @OptimisticLock(excluded = true)
  private Long coreVersion = 0L;

  /**
   * Section-scoped optimistic-lock counter for the {@code schedule} patch endpoint (meeting,
   * planned-start, planned-end, actual-start, actual-end). Status-driven auto-transitions that set
   * {@code actualStartTime} (PLANNED → ACTIVE) bump this counter via {@code …WithinTransaction}.
   */
  @Column(name = "schedule_version", nullable = false)
  @OptimisticLock(excluded = true)
  private Long scheduleVersion = 0L;

  /**
   * Section-scoped optimistic-lock counter for the {@code flags} patch endpoint ({@code
   * isInternal}).
   */
  @Column(name = "flags_version", nullable = false)
  @OptimisticLock(excluded = true)
  private Long flagsVersion = 0L;

  /**
   * Section-scoped optimistic-lock counter for the {@code party-lead} endpoint ({@link
   * #partyLeadUser} / {@link #partyLeadGuestName}). Independent of the global {@link
   * AbstractEntity#getVersion()} and marked {@code @OptimisticLock(excluded = true)} so assigning a
   * party lead does not invalidate other users' open forms on the same mission.
   */
  @Column(name = "party_lead_version", nullable = false)
  @OptimisticLock(excluded = true)
  private Long partyLeadVersion = 0L;

  /**
   * Section-scoped optimistic-lock counter for the Ablauf editor and the per-step done-toggle
   * ({@link #steps}). Independent of the global {@link AbstractEntity#getVersion()} and marked
   * {@code @OptimisticLock(excluded = true)} so editing the procedure timeline never invalidates
   * another user's open core / schedule / flags form on the same mission.
   */
  @Column(name = "steps_version", nullable = false)
  @OptimisticLock(excluded = true)
  private Long stepsVersion = 0L;

  /**
   * Section-scoped optimistic-lock counter for the goals (Ziele) editor ({@link #objectives}).
   * Independent of the global {@link AbstractEntity#getVersion()} and marked
   * {@code @OptimisticLock(excluded = true)} so editing the goal list never invalidates another
   * user's open core / schedule / flags / Ablauf form on the same mission.
   */
  @Column(name = "objectives_version", nullable = false)
  @OptimisticLock(excluded = true)
  private Long objectivesVersion = 0L;

  /**
   * Section counter for reassigning {@link #owningOrgUnit}, independent of {@link
   * AbstractEntity#getVersion()} so a re-homing never conflicts with other section edits
   * (REQ-ORG-018).
   */
  @Column(name = "owning_org_unit_version", nullable = false)
  @OptimisticLock(excluded = true)
  private Long owningOrgUnitVersion = 0L;

  @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true)
  @OptimisticLock(excluded = true)
  private Set<MissionParticipant> participants = new HashSet<>();

  @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("name ASC")
  @OptimisticLock(excluded = true)
  private Set<MissionUnit> assignedUnits = new LinkedHashSet<>();

  @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true)
  @OptimisticLock(excluded = true)
  private Set<MissionFrequency> frequencies = new HashSet<>();

  @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true)
  @OptimisticLock(excluded = true)
  private Set<MissionFinanceEntry> financeEntries = new HashSet<>();

  /**
   * Ablauf (procedure timeline) steps in ascending {@link MissionStep#getOrderIndex()} order,
   * guarded by {@link #stepsVersion}. Read through {@link #getSteps()}; changed through {@link
   * #addStep(MissionStep)} / {@link #removeStep(UUID)}.
   */
  @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("orderIndex ASC")
  @OptimisticLock(excluded = true)
  @Getter(AccessLevel.NONE)
  private Set<MissionStep> steps = new LinkedHashSet<>();

  /**
   * Mission goals (Ziele) in ascending {@link MissionObjective#getOrderIndex()} order, guarded by
   * {@link #objectivesVersion}. Read through {@link #getObjectives()}; changed through {@link
   * #addObjective(MissionObjective)} / {@link #removeObjective(UUID)}.
   */
  @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("orderIndex ASC")
  @OptimisticLock(excluded = true)
  @Getter(AccessLevel.NONE)
  private Set<MissionObjective> objectives = new LinkedHashSet<>();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_mission_id")
  @JsonIgnore
  private Mission parent;

  @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
  @OptimisticLock(excluded = true)
  private Set<Mission> subMissions = new HashSet<>();

  @OneToMany(mappedBy = "mission")
  @OrderBy("startedAt DESC")
  @JsonIgnore
  @OptimisticLock(excluded = true)
  private Set<RefineryOrder> refineryOrders = new LinkedHashSet<>();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "operation_id")
  @OptimisticLock(excluded = true)
  private Operation operation;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owner_id")
  @OptimisticLock(excluded = true)
  private User owner;

  /**
   * Version of this mission's {@link MissionOwnership} row, or {@code 0} before the first owner
   * change; the optimistic-lock echo for {@code PUT /api/v1/missions/{id}/owner}.
   *
   * <p>Read-only {@code @Formula}; the owner change writes the new value back through the setter so
   * its response carries the fresh counter.
   */
  @Formula("coalesce((select mo.version from mission_ownership mo where mo.mission_id = id), 0)")
  @OptimisticLock(excluded = true)
  private Long ownershipVersion = 0L;

  /**
   * Optional registered party lead (Partyleiter), mutually exclusive with {@link
   * #partyLeadGuestName}; guarded by {@link #partyLeadVersion}.
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "party_lead_user_id")
  @OptimisticLock(excluded = true)
  private User partyLeadUser;

  /**
   * Optional free-text party-lead handle for an unregistered/anonymous person, mirroring {@link
   * MissionParticipant#getGuestName()}. Mutually exclusive with {@link #partyLeadUser}. {@code
   * null} when no party lead is assigned or when the lead is a registered user.
   */
  @Column(name = "party_lead_guest_name", length = 100)
  @OptimisticLock(excluded = true)
  private String partyLeadGuestName;

  @ManyToMany
  @JoinTable(
      name = "mission_managers",
      joinColumns = @JoinColumn(name = "mission_id"),
      inverseJoinColumns = @JoinColumn(name = "user_id"))
  @OptimisticLock(excluded = true)
  private Set<User> managers = new HashSet<>();

  /**
   * Org-unit owner of this mission, or {@code null} for an ownerless leadership mission.
   * Reassignable via {@code MissionService.updateOwningOrgUnit}, guarded by {@link
   * #owningOrgUnitVersion} (ADR-0050).
   *
   * <ul>
   *   <li>Org-owned: non-internal missions are visible to all org units, internal ones only to the
   *       owning org unit and admins.
   *   <li>Ownerless: attributable through {@link #owner}; visible to everyone, or to organisation
   *       members and above when {@link #isInternal}.
   * </ul>
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id")
  @OptimisticLock(excluded = true)
  private OrgUnit owningOrgUnit;

  /**
   * Returns the Ablauf steps ordered by {@code orderIndex}; mutations go through {@link
   * #addStep(MissionStep)} / {@link #removeStep(UUID)}.
   *
   * @return an unmodifiable view of the mission's steps
   */
  @NotNull
  @UnmodifiableView
  public Set<MissionStep> getSteps() {
    return Collections.unmodifiableSet(steps);
  }

  /**
   * Appends a step to the Ablauf collection and wires the inverse side so the bidirectional
   * association stays consistent before the cascade persists it.
   *
   * @param step the step to attach to this mission
   */
  public void addStep(@NotNull MissionStep step) {
    step.setMission(this);
    steps.add(step);
  }

  /**
   * Removes the step with the given id from the Ablauf collection (orphan-removal then deletes the
   * row on flush).
   *
   * @param stepId the id of the step to remove
   * @return {@code true} if a step was removed, {@code false} if no step had that id
   */
  public boolean removeStep(UUID stepId) {
    return steps.removeIf(s -> stepId.equals(s.getId()));
  }

  /**
   * Returns the mission goals ordered by {@code orderIndex}; mutations go through {@link
   * #addObjective(MissionObjective)} / {@link #removeObjective(UUID)}.
   *
   * @return an unmodifiable view of the mission's goals
   */
  @NotNull
  @UnmodifiableView
  public Set<MissionObjective> getObjectives() {
    return Collections.unmodifiableSet(objectives);
  }

  /**
   * Appends a goal to the collection and wires the inverse side so the bidirectional association
   * stays consistent before the cascade persists it.
   *
   * @param objective the goal to attach to this mission
   */
  public void addObjective(@NotNull MissionObjective objective) {
    objective.setMission(this);
    objectives.add(objective);
  }

  /**
   * Removes the goal with the given id from the collection (orphan-removal then deletes the row on
   * flush).
   *
   * @param objectiveId the id of the goal to remove
   * @return {@code true} if a goal was removed, {@code false} if no goal had that id
   */
  public boolean removeObjective(UUID objectiveId) {
    return objectives.removeIf(o -> objectiveId.equals(o.getId()));
  }
}
