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
 * Check and bump helpers for {@link Mission}'s per-section optimistic-lock counters (REQ-ORG-018),
 * so an edit to one section never conflicts with an edit to another.
 *
 * <p>The counters are business {@code Long}s, not the JPA {@code @Version}; a {@code null} counter
 * reads as {@code 0L}. {@link #enforceSectionVersion(MissionRepository, Mission, MissionSection,
 * Long, UUID)} is the primary guard.
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
     * Binds a section to its counter accessors on {@link Mission} and its conditional bump on
     * {@link MissionRepository}.
     *
     * @param getter reads the raw, nullable counter
     * @param setter writes the counter back
     * @param dbBump runs the section's conditional {@code UPDATE} and returns the affected row
     *     count
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
     * Returns this section's counter for the mission, {@code 0L} when it is {@code null}.
     *
     * @param mission the mission to read the counter from
     * @return the current section version, or {@code 0L} when the counter is null
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
     * @param repository the mission repository
     * @param missionId the mission id
     * @param expected the counter value the caller echoed back
     * @return the affected row count ({@code 1} on a match, {@code 0} on a stale echo)
     */
    int bump(MissionRepository repository, UUID missionId, long expected);
  }

  /**
   * Atomically validates and bumps a section counter with a conditional {@code UPDATE}, raising a
   * 409 on a stale echo (REQ-ORG-018).
   *
   * <p>On success the managed mission's counter is set to {@code expectedVersion + 1}. Call it
   * before mutating the section.
   *
   * @param repository the mission repository
   * @param mission the managed mission whose counter to advance on success
   * @param section the section the caller echoed a version for
   * @param expectedVersion the version echoed from the rendered page
   * @param missionId the mission id
   * @throws ObjectOptimisticLockingFailureException when the expected version is stale
   * @throws ArithmeticException when the counter is already at {@link Long#MAX_VALUE}
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
    section.set(mission, Math.addExact(expectedVersion, 1L));
  }

  /**
   * Increments a section counter in memory, treating {@code null} as {@code 0L}.
   *
   * @param mission the managed mission
   * @param section the section whose counter to increment
   * @throws ArithmeticException when the counter is already at {@link Long#MAX_VALUE}
   */
  public static void bumpSectionVersion(@NotNull Mission mission, @NotNull MissionSection section) {
    section.set(mission, Math.addExact(section.current(mission), 1L));
  }
}
