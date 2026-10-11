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

package de.greluc.krt.profit.basetool.backend.mission.internal;

import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import java.util.UUID;
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
   * A mission's independently-versioned edit sections. Each constant reads, writes and
   * conditionally bumps one manual {@code *Version} counter on {@link Mission}, letting {@link
   * #enforceSectionVersion} and {@link #bumpSectionVersion} operate on any section without a
   * per-section helper.
   */
  public enum MissionSection {
    /** The mission core (name, description, status, owner-visible identity). */
    CORE {
      @Override
      Long read(Mission mission) {
        return mission.getCoreVersion();
      }

      @Override
      void write(Mission mission, Long value) {
        mission.setCoreVersion(value);
      }

      @Override
      int bump(MissionRepository repository, UUID missionId, long expected) {
        return repository.bumpCoreVersionIfMatches(missionId, expected);
      }
    },
    /** The mission schedule (planned/actual start and end times). */
    SCHEDULE {
      @Override
      Long read(Mission mission) {
        return mission.getScheduleVersion();
      }

      @Override
      void write(Mission mission, Long value) {
        mission.setScheduleVersion(value);
      }

      @Override
      int bump(MissionRepository repository, UUID missionId, long expected) {
        return repository.bumpScheduleVersionIfMatches(missionId, expected);
      }
    },
    /** The mission flags (e.g. the internal/public visibility toggle). */
    FLAGS {
      @Override
      Long read(Mission mission) {
        return mission.getFlagsVersion();
      }

      @Override
      void write(Mission mission, Long value) {
        mission.setFlagsVersion(value);
      }

      @Override
      int bump(MissionRepository repository, UUID missionId, long expected) {
        return repository.bumpFlagsVersionIfMatches(missionId, expected);
      }
    },
    /** The mission party-lead assignment. */
    PARTY_LEAD {
      @Override
      Long read(Mission mission) {
        return mission.getPartyLeadVersion();
      }

      @Override
      void write(Mission mission, Long value) {
        mission.setPartyLeadVersion(value);
      }

      @Override
      int bump(MissionRepository repository, UUID missionId, long expected) {
        return repository.bumpPartyLeadVersionIfMatches(missionId, expected);
      }
    },
    /** The Ablauf steps timeline. */
    STEPS {
      @Override
      Long read(Mission mission) {
        return mission.getStepsVersion();
      }

      @Override
      void write(Mission mission, Long value) {
        mission.setStepsVersion(value);
      }

      @Override
      int bump(MissionRepository repository, UUID missionId, long expected) {
        return repository.bumpStepsVersionIfMatches(missionId, expected);
      }
    },
    /** The mission objectives (Ziele). */
    OBJECTIVES {
      @Override
      Long read(Mission mission) {
        return mission.getObjectivesVersion();
      }

      @Override
      void write(Mission mission, Long value) {
        mission.setObjectivesVersion(value);
      }

      @Override
      int bump(MissionRepository repository, UUID missionId, long expected) {
        return repository.bumpObjectivesVersionIfMatches(missionId, expected);
      }
    },
    /** The owning-org-unit assignment. */
    OWNING_ORG_UNIT {
      @Override
      Long read(Mission mission) {
        return mission.getOwningOrgUnitVersion();
      }

      @Override
      void write(Mission mission, Long value) {
        mission.setOwningOrgUnitVersion(value);
      }

      @Override
      int bump(MissionRepository repository, UUID missionId, long expected) {
        return repository.bumpOwningOrgUnitVersionIfMatches(missionId, expected);
      }
    };

    /**
     * Reads the raw (nullable) counter value from a mission.
     *
     * @param mission the mission to read the counter from
     * @return the stored counter, possibly {@code null}
     */
    abstract Long read(Mission mission);

    /**
     * Writes the counter value back onto a mission.
     *
     * @param mission the mission to write the counter on
     * @param value the new counter value
     */
    abstract void write(Mission mission, Long value);

    /**
     * Runs this section's atomic conditional counter bump: {@code UPDATE Mission … SET xVersion =
     * xVersion + 1 WHERE id = ? AND xVersion = ?}.
     *
     * @param repository the mission repository
     * @param missionId the mission id
     * @param expected the counter value the caller echoed back
     * @return the affected row count ({@code 1} on a match, {@code 0} on a stale echo)
     */
    abstract int bump(MissionRepository repository, UUID missionId, long expected);

    /**
     * Returns this section's counter for the mission, {@code 0L} when it is {@code null}.
     *
     * @param mission the mission to read the counter from
     * @return the current section version, or {@code 0L} when the counter is null
     */
    long current(@NotNull Mission mission) {
      Long value = read(mission);
      return value == null ? 0L : value;
    }

    /**
     * Writes a new value into this section's counter on the given mission.
     *
     * @param mission the mission to write the counter on.
     * @param value the new counter value.
     */
    void set(@NotNull Mission mission, long value) {
      write(mission, value);
    }
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
    int updated = section.bump(repository, missionId, expectedVersion);
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
