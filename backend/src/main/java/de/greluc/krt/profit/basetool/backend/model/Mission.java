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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OptimisticLock;

/**
 * Mission JPA entity.
 *
 * <p><strong>Fine-grained concurrency model (REQ-ORG-018, #1112/#1114/#1147).</strong> A mission is
 * edited section-by-section (core / schedule / flags / party-lead / owning-org-unit / Ablauf steps
 * / goals), and an edit to one section must never 409 a concurrent edit to another. To achieve
 * that, <em>every</em> mutable business column and association is {@code @OptimisticLock(excluded =
 * true)}, so touching it does NOT bump the inherited row {@link AbstractEntity#getVersion()}; each
 * section instead carries its own plain {@code *Version} counter which is bumped through an atomic,
 * DB-enforced conditional {@code UPDATE … WHERE id = ? AND xVersion = ?} (see {@code
 * MissionRepository.bump*VersionIfMatches} and {@code
 * MissionSectionVersions.enforceSectionVersion}). Because the section writes only ever dirty the
 * columns they own, the class is {@code @DynamicUpdate}: Hibernate narrows each flush {@code
 * UPDATE} to the actually-dirtied columns, so two concurrent section writers never clobber each
 * other's untouched columns with a stale full-row snapshot (the silent lost update a non-dynamic
 * full-row UPDATE would cause once the scalars are excluded).
 *
 * <p>With every mutable column excluded, the row {@code @Version} no longer moves on a section
 * edit; it now guards exactly one path — the legacy full-replace {@code
 * MissionService.updateMission} ({@code PUT /missions/{id}}), which force-increments it (JPA {@code
 * OPTIMISTIC_FORCE_INCREMENT}) so two concurrent whole-mission overwrites still surface a 409
 * against each other.
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
public class Mission extends AbstractEntity<UUID> {

  @Getter(onMethod_ = @__(@Override))
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  private UUID id;

  // The core/schedule/flags business scalars below are all @OptimisticLock(excluded = true): a
  // section edit dirties only its own columns and must NOT bump the row @Version (which would 409 a
  // concurrent edit to an unrelated section). Their per-section counters + @DynamicUpdate provide
  // the real concurrency guard instead — see the class Javadoc (#1114).

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
  private String status; // e.g., PLANNED, ACTIVE, COMPLETED

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
   * Section-scoped optimistic-lock counter for the {@code core} patch endpoint (name, description,
   * calendar link, status, operation). Independent of {@link AbstractEntity#getVersion()} so that
   * concurrent edits on {@code schedule} and {@code flags} do not produce spurious 409 conflicts.
   * Marked {@code @OptimisticLock(excluded = true)} so bumping it does not in turn bump the global
   * {@link AbstractEntity#getVersion()}.
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
   * Section-scoped optimistic-lock counter for the owning-org-unit reassignment endpoint ({@link
   * #owningOrgUnit}). Independent of the global {@link AbstractEntity#getVersion()} and marked
   * {@code @OptimisticLock(excluded = true)} so re-homing the mission to a different org unit never
   * invalidates another user's open core / schedule / flags form on the same mission. Two managers
   * racing on the assignment surface a 409 against each other via this counter (REQ-ORG-018).
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
   * Ordered, reorderable "Ablauf" (procedure timeline) steps. Loaded by ascending {@link
   * MissionStep#getOrderIndex()} into a {@link LinkedHashSet} so iteration (and the mapped DTO
   * list) preserves the checklist order. Excluded from the global optimistic-lock; the dedicated
   * {@link #stepsVersion} guards concurrent edits instead.
   *
   * <p>The Lombok getter is suppressed ({@link AccessLevel#NONE}) in favour of the hand-written
   * {@link #getSteps()}, which hands out an unmodifiable view so callers cannot mutate the managed
   * collection through the getter; structural changes go through {@link #addStep(MissionStep)} /
   * {@link #removeStep(UUID)}.
   */
  @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("orderIndex ASC")
  @OptimisticLock(excluded = true)
  @Getter(AccessLevel.NONE)
  private Set<MissionStep> steps = new LinkedHashSet<>();

  /**
   * Ordered, reorderable mission goals (Ziele). Loaded by ascending {@link
   * MissionObjective#getOrderIndex()} into a {@link LinkedHashSet} so iteration (and the mapped DTO
   * list) preserves the authored order; the overview regroups them by {@link MissionObjectiveKind}.
   * Excluded from the global optimistic-lock; the dedicated {@link #objectivesVersion} guards
   * concurrent edits instead.
   *
   * <p>The Lombok getter is suppressed ({@link AccessLevel#NONE}) in favour of the hand-written
   * {@link #getObjectives()}, which hands out an unmodifiable view so callers cannot mutate the
   * managed collection through the getter; structural changes go through {@link
   * #addObjective(MissionObjective)} / {@link #removeObjective(UUID)}.
   */
  @OneToMany(mappedBy = "mission", cascade = CascadeType.ALL, orphanRemoval = true)
  @OrderBy("orderIndex ASC")
  @OptimisticLock(excluded = true)
  @Getter(AccessLevel.NONE)
  private Set<MissionObjective> objectives = new LinkedHashSet<>();

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "parent_mission_id")
  @com.fasterxml.jackson.annotation.JsonIgnore
  private Mission parent;

  @OneToMany(mappedBy = "parent", cascade = CascadeType.ALL)
  @OptimisticLock(excluded = true)
  private Set<Mission> subMissions = new HashSet<>();

  @OneToMany(mappedBy = "mission")
  @OrderBy("createdAt DESC")
  @com.fasterxml.jackson.annotation.JsonIgnore
  @OptimisticLock(excluded = true)
  private Set<InventoryItem> inventoryEntries = new LinkedHashSet<>();

  @OneToMany(mappedBy = "mission")
  @OrderBy("startedAt DESC")
  @com.fasterxml.jackson.annotation.JsonIgnore
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
   * Optional party lead (Partyleiter) of this mission, as a linked registered user. Mutually
   * exclusive with {@link #partyLeadGuestName}: a registered party lead clears the guest handle and
   * vice versa. {@code null} when no party lead is assigned or when the lead is an unregistered
   * person captured via {@link #partyLeadGuestName}. Excluded from the global optimistic-lock; the
   * dedicated {@link #partyLeadVersion} guards concurrent edits instead.
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
   * Org-unit owner of this mission, or {@code null} for an <em>ownerless leadership mission</em>.
   * Set at creation time from the caller's active org-unit context (via {@code
   * OwnerScopeService.resolveOrgUnitForPickerOutputNullable}). Since REQ-ORG-018 it is no longer
   * immutable: the Verwaltung tab exposes a reassignment control that re-homes the mission to a
   * different org unit (or to ownerless) through the dedicated {@code
   * MissionService.updateOwningOrgUnit} endpoint, guarded by {@link #owningOrgUnitVersion} and the
   * caller's assignable-org-unit scope. The change retroactively re-scopes read/write visibility
   * per the gates below — see ADR-0050. Gates read/write access together with {@link #isInternal}:
   *
   * <ul>
   *   <li><b>Org-owned</b> (non-null): non-internal missions are visible across org units, internal
   *       ones are restricted to the owning org unit and admins.
   *   <li><b>Ownerless</b> (null): created by a user who belongs to no OrgUnit but is allowed to
   *       plan org-wide missions (organisation leadership / "Bereichsleitung", which sits above
   *       every Staffel and SK). Such a mission is attributable through its {@link #owner}. A
   *       non-internal ownerless mission is visible to everyone (the public default); an internal
   *       one is visible to organisation members-or-above. Editing follows the usual
   *       mission-management gate (elevated roles, owner, co-managers, admins), minus the
   *       squadron-scope narrowing. See {@code OwnerScopeService.canSeeMission} / {@code
   *       canEditMission}.
   * </ul>
   *
   * <p>R9 Step 2 dropped the legacy {@code owningSquadron} mirror field together with the
   * {@code @PrePersist} / {@code @PreUpdate} / {@code @PostLoad} {@code syncOwnerFields()}
   * lifecycle hook; V100 drops the matching {@code owning_squadron_id} column. V99 first tightened
   * the new column to NOT NULL; V144 relaxed it again so an ownerless mission can persist
   * (mirroring the V132 relaxation for ship / refinery order / inventory item).
   */
  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "owning_org_unit_id")
  @OptimisticLock(excluded = true)
  private OrgUnit owningOrgUnit;

  /**
   * Returns the Ablauf steps as an unmodifiable view ordered by {@code orderIndex}. Reads (DTO
   * mapping, index lookups, reorder validation) iterate this view; callers that need to add or
   * remove a step use {@link #addStep(MissionStep)} / {@link #removeStep(UUID)} so the managed
   * collection is never mutated through the getter.
   *
   * @return an unmodifiable view of the mission's procedure-timeline steps
   */
  public Set<MissionStep> getSteps() {
    return Collections.unmodifiableSet(steps);
  }

  /**
   * Appends a step to the Ablauf collection and wires the inverse side so the bidirectional
   * association stays consistent before the cascade persists it.
   *
   * @param step the step to attach to this mission
   */
  public void addStep(MissionStep step) {
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
   * Returns the mission goals as an unmodifiable view ordered by {@code orderIndex}. Reads (DTO
   * mapping, index lookups, reorder validation) iterate this view; callers that need to add or
   * remove a goal use {@link #addObjective(MissionObjective)} / {@link #removeObjective(UUID)} so
   * the managed collection is never mutated through the getter.
   *
   * @return an unmodifiable view of the mission's goals
   */
  public Set<MissionObjective> getObjectives() {
    return Collections.unmodifiableSet(objectives);
  }

  /**
   * Appends a goal to the collection and wires the inverse side so the bidirectional association
   * stays consistent before the cascade persists it.
   *
   * @param objective the goal to attach to this mission
   */
  public void addObjective(MissionObjective objective) {
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
