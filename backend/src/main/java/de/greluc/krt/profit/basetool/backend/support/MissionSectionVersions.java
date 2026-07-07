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

package de.greluc.krt.profit.basetool.backend.support;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/**
 * Check/bump helpers for {@link Mission}'s fine-grained per-section optimistic-lock counters
 * (REQ-ORG-018), shared across the mission services after the {@code MissionService} god-class
 * split (L1 step 2, #920).
 *
 * <p>These counters — {@code coreVersion} / {@code scheduleVersion} / {@code flagsVersion} / {@code
 * partyLeadVersion} / {@code stepsVersion} / {@code objectivesVersion} / {@code
 * owningOrgUnitVersion} — are plain business {@code Long}s (NOT the row's JPA {@code @Version});
 * each independently versions one editable section so an edit to one section never 409s a
 * concurrent edit to another. They are deliberately <strong>not</strong> routed through {@link
 * OptimisticLock} (whose null semantics are for the JPA {@code @Version}); an absent (never-bumped)
 * counter reads as {@code 0L} here — the value a fresh section renders and echoes back, so the
 * first edit validates against {@code 0L}.
 *
 * <p><strong>DB-enforced since #1112/#1114/#1147.</strong> The primary section guard is {@link
 * #enforceSectionVersion(MissionRepository, Mission, MissionSection, Long, UUID)}: it runs the
 * section's atomic conditional {@code UPDATE … WHERE id = ? AND xVersion = ?} on the repository, so
 * the check-and-bump is a single row-locked statement instead of an in-memory read-then-write. That
 * closes the millisecond window in which two managers racing the <em>same</em> section both passed
 * the old in-memory check-then-bump and both committed — the silent lost update on party-lead /
 * owning-org-unit (whose entire write set is excluded) and the duplicate order-index on steps /
 * goals. The in-memory {@link #bumpSectionVersion} survives only for the two unconditional
 * cross-section pokes that carry no client echo: the legacy full-replace ({@code
 * MissionService.updateMission}) and the activation auto-stamp of {@code actualStartTime}.
 *
 * <p>Lives in the dependency-leaf {@code support} package: it depends only on the {@link Mission}
 * entity and its {@link MissionRepository}, never on {@code service}. The
 * core/schedule/flags/owning-org-unit sections are driven from {@code MissionService},
 * steps/objectives from {@code MissionTimelineService}, and party-lead from {@code
 * MissionParticipantService} — all through this single guard, so the counter semantics stay
 * identical across the split.
 */
public final class MissionSectionVersions {

  /** Non-instantiable static-helper holder. */
  private MissionSectionVersions() {}

  /**
   * A mission's independently-versioned edit sections. Each constant binds the getter/setter and
   * the repository conditional-bump of one manual {@code *Version} counter on {@link Mission},
   * letting {@link #enforceSectionVersion} and {@link #bumpSectionVersion} operate on any section
   * without a per-section helper.
   */
  public enum MissionSection {
    /** The mission core (name, description, status, owner-visible identity). */
    CORE(
        Mission::getCoreVersion,
        Mission::setCoreVersion,
        MissionRepository::bumpCoreVersionIfMatches),
    /** The mission schedule (planned/actual start and end times). */
    SCHEDULE(
        Mission::getScheduleVersion,
        Mission::setScheduleVersion,
        MissionRepository::bumpScheduleVersionIfMatches),
    /** The mission flags (e.g. the internal/public visibility toggle). */
    FLAGS(
        Mission::getFlagsVersion,
        Mission::setFlagsVersion,
        MissionRepository::bumpFlagsVersionIfMatches),
    /** The mission party-lead assignment. */
    PARTY_LEAD(
        Mission::getPartyLeadVersion,
        Mission::setPartyLeadVersion,
        MissionRepository::bumpPartyLeadVersionIfMatches),
    /** The Ablauf steps timeline. */
    STEPS(
        Mission::getStepsVersion,
        Mission::setStepsVersion,
        MissionRepository::bumpStepsVersionIfMatches),
    /** The mission objectives (Ziele). */
    OBJECTIVES(
        Mission::getObjectivesVersion,
        Mission::setObjectivesVersion,
        MissionRepository::bumpObjectivesVersionIfMatches),
    /** The owning-org-unit assignment. */
    OWNING_ORG_UNIT(
        Mission::getOwningOrgUnitVersion,
        Mission::setOwningOrgUnitVersion,
        MissionRepository::bumpOwningOrgUnitVersionIfMatches);

    /** Reads the raw (nullable) counter value from a mission. */
    private final transient Function<Mission, Long> getter;

    /** Writes the counter value back onto a mission. */
    private final transient BiConsumer<Mission, Long> setter;

    /** Runs this section's atomic conditional counter bump on the repository. */
    private final transient SectionCounterBump dbBump;

    /**
     * Binds a section constant to its {@code *Version} counter accessors on {@link Mission} and its
     * atomic conditional bump on {@link MissionRepository}.
     *
     * @param getter reads the raw (nullable) counter value.
     * @param setter writes the counter value back.
     * @param dbBump runs the section's {@code UPDATE … WHERE id = ? AND xVersion = ?} and returns
     *     the affected row count.
     */
    MissionSection(
        Function<Mission, Long> getter,
        BiConsumer<Mission, Long> setter,
        SectionCounterBump dbBump) {
      this.getter = getter;
      this.setter = setter;
      this.dbBump = dbBump;
    }

    /**
     * Returns this section's current counter value for the given mission, coalescing an absent
     * (never-bumped) {@code null} counter to {@code 0L} — the exact value a fresh section renders
     * and echoes back, so the very first edit validates against {@code 0L}.
     *
     * @param mission the mission to read the counter from.
     * @return the current section version, or {@code 0L} when the counter is null.
     */
    long current(@NotNull Mission mission) {
      Long value = getter.apply(mission);
      return value == null ? 0L : value;
    }

    /**
     * Writes a new value into this section's counter on the given mission.
     *
     * @param mission the mission to write the counter on.
     * @param value the new counter value.
     */
    void set(@NotNull Mission mission, long value) {
      setter.accept(mission, value);
    }
  }

  /** Runs a section's atomic conditional counter bump on the repository. */
  @FunctionalInterface
  private interface SectionCounterBump {

    /**
     * Executes the section's {@code UPDATE Mission … SET xVersion = xVersion + 1 WHERE id = ? AND
     * xVersion = ?}.
     *
     * @param repository the mission repository.
     * @param missionId the mission id.
     * @param expected the counter value the caller echoed back.
     * @return the affected row count ({@code 1} on a match, {@code 0} on a stale echo).
     */
    int bump(MissionRepository repository, UUID missionId, long expected);
  }

  /**
   * Atomically validates and bumps a mission's fine-grained {@link MissionSection} version counter
   * in a single row-locked {@code UPDATE … WHERE id = ? AND xVersion = ?}, raising a 409 when the
   * echoed version is stale so two managers racing on the <em>same</em> section surface a conflict
   * instead of one silently overwriting the other, while a concurrent edit to an <em>unrelated</em>
   * section never collides (REQ-ORG-018). Unlike the former in-memory check-then-bump this leaves
   * no window between the check and the write: the loser of a same-section race blocks on the row
   * lock, re-reads the now-incremented counter and gets {@code 0} affected rows
   * (#1112/#1114/#1147).
   *
   * <p>On success the managed {@code mission}'s in-memory counter is advanced to {@code
   * expectedVersion + 1} so the mapped response echoes the fresh version and the subsequent
   * dirty-checking flush writes the same value (idempotent with the bump above). Callers must
   * invoke this <strong>before</strong> mutating the section — the bump takes the row lock that
   * serialises the writers.
   *
   * @param repository the mission repository that owns the conditional bump query.
   * @param mission the managed mission whose in-memory counter to advance on success.
   * @param section the section the caller echoed a version back for.
   * @param expectedVersion the version the caller echoed back from the rendered page.
   * @param missionId the mission id, for the conditional bump and the conflict exception
   *     identifier.
   * @throws ObjectOptimisticLockingFailureException when the expected version is stale (0 rows
   *     affected).
   */
  public static void enforceSectionVersion(
      @NotNull MissionRepository repository,
      @NotNull Mission mission,
      @NotNull MissionSection section,
      @NotNull Long expectedVersion,
      @NotNull UUID missionId) {
    int updated = section.dbBump.bump(repository, missionId, expectedVersion);
    if (updated == 0) {
      throw new ObjectOptimisticLockingFailureException(Mission.class, missionId);
    }
    section.set(mission, expectedVersion + 1L);
  }

  /**
   * Increments a mission's fine-grained {@link MissionSection} version counter after a successful
   * edit of that section, so the next echo from a now-stale form for the <em>same</em> section is
   * rejected while unrelated sections keep their counters. An absent (never-bumped) counter is
   * treated as {@code 0L} and becomes {@code 1L}.
   *
   * @param mission the managed mission whose counter to bump.
   * @param section the section whose counter to increment.
   */
  public static void bumpSectionVersion(@NotNull Mission mission, @NotNull MissionSection section) {
    section.set(mission, section.current(mission) + 1L);
  }
}
