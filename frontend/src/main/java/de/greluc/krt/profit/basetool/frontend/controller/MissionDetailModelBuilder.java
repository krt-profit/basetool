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

package de.greluc.krt.profit.basetool.frontend.controller;

import de.greluc.krt.profit.basetool.frontend.model.dto.JobTypeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionCrewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFrequencyDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionParticipantDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionUnitDto;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/**
 * Derives the mission-detail view attributes that are pure functions of a {@link MissionDto}:
 * participant sorting and grouping, the facts-bar leader, unit-assignment lookups, participation
 * percentages and the frequency split.
 *
 * <p>Stateless and static-only; makes no backend call and does not touch the {@code Model}.
 */
public final class MissionDetailModelBuilder {

  private MissionDetailModelBuilder() {}

  /**
   * The mission-derived attributes the mission-detail template consumes; all collections are
   * mutable copies.
   *
   * @param participants the mission participants, sorted case-insensitively by display name
   * @param participantsByLeadType participants grouped by their leadership job-type id (string key)
   * @param missionLeadTypes the distinct leadership job types present, in first-seen order
   * @param factLeaderName the facts-bar "Leiter" display name, or {@code null}
   * @param participantUserIds ids of every account-backed participant (guests excluded)
   * @param assignedUnitByParticipantId participant id → space-joined names of the unit(s) they crew
   * @param assignedUnitShipIds ids of ships already pinned to a unit of this mission
   * @param unassignedParticipants participants not yet crewed into any unit, in sorted order
   * @param participantsById participant id → full participant payload
   * @param participationPercentages participant id → share of total participant-time (0.0 default)
   * @param frequencyByTypeId typed (global) channel by frequency-type id (string key)
   * @param customFrequencies mission-specific channels, sorted case-insensitively by name
   */
  public record MissionDetailViewModel(
      List<MissionParticipantDto> participants,
      Map<String, List<MissionParticipantDto>> participantsByLeadType,
      List<JobTypeDto> missionLeadTypes,
      String factLeaderName,
      Set<UUID> participantUserIds,
      Map<UUID, String> assignedUnitByParticipantId,
      Set<UUID> assignedUnitShipIds,
      List<MissionParticipantDto> unassignedParticipants,
      Map<UUID, MissionParticipantDto> participantsById,
      Map<UUID, Double> participationPercentages,
      Map<String, MissionFrequencyDto> frequencyByTypeId,
      List<MissionFrequencyDto> customFrequencies) {}

  /**
   * Computes every mission-derived detail attribute from the fetched mission payload.
   *
   * @param mission the fetched mission (with its embedded participants, units and frequencies)
   * @return the bundle of pure view attributes for the detail model
   */
  public static @NotNull MissionDetailViewModel build(MissionDto mission) {
    List<MissionParticipantDto> participants = sortedParticipants(mission);
    LeadTypeGrouping leads = groupByLeadType(participants);
    UnitAssignments units = unitAssignments(mission);
    FrequencyGrouping frequencies = groupFrequencies(mission);
    return new MissionDetailViewModel(
        participants,
        leads.byLeadType(),
        leads.leadTypes(),
        factLeaderName(participants, mission),
        participantUserIds(participants),
        units.byParticipantId(),
        units.shipIds(),
        unassignedParticipants(participants, units.byParticipantId()),
        participantsById(participants),
        participationPercentages(participants, mission),
        frequencies.byTypeId(),
        frequencies.custom());
  }

  /**
   * Returns the mission's participants in a fresh list, sorted case-insensitively by display name.
   *
   * @param mission the mission whose participants to sort
   * @return a mutable, name-sorted participant list
   */
  @NotNull
  private static List<MissionParticipantDto> sortedParticipants(@NotNull MissionDto mission) {
    List<MissionParticipantDto> participants = new ArrayList<>(mission.participants());
    participants.sort(
        (p1, p2) -> {
          String name1 = extractParticipantName(p1);
          String name2 = extractParticipantName(p2);
          return name1.compareToIgnoreCase(name2);
        });
    return participants;
  }

  /**
   * Groups the leadership-role participants by their job-type id and collects the distinct
   * leadership job types in first-seen order.
   *
   * @param participants the sorted participants
   * @return the lead-type grouping
   */
  @NotNull
  private static LeadTypeGrouping groupByLeadType(List<MissionParticipantDto> participants) {
    Map<String, List<MissionParticipantDto>> participantsByLeadType = new HashMap<>();
    List<JobTypeDto> missionLeadTypes = new ArrayList<>();
    Set<UUID> addedLeadTypes = new HashSet<>();
    for (MissionParticipantDto p : participants) {
      JobTypeDto job = p.plannedMissionJobType();
      if (job != null && job.isLeadershipRole()) {
        UUID jobId = job.id();
        participantsByLeadType.computeIfAbsent(jobId.toString(), k -> new ArrayList<>()).add(p);
        if (addedLeadTypes.add(jobId)) {
          missionLeadTypes.add(job);
        }
      }
    }
    return new LeadTypeGrouping(participantsByLeadType, missionLeadTypes);
  }

  /**
   * Resolves the facts-bar "Leiter" (REQ-MISSION-013): the participant whose planned job type is
   * the mission-lead designation, else the visible mission owner, else {@code null}.
   *
   * @param participants the sorted participants
   * @param mission the mission (for the owner fallback)
   * @return the facts-bar leader display name, or {@code null}
   */
  private static String factLeaderName(
      List<MissionParticipantDto> participants, MissionDto mission) {
    String factLeaderName = null;
    for (MissionParticipantDto p : participants) {
      JobTypeDto job = p.plannedMissionJobType();
      if (job != null && Boolean.TRUE.equals(job.isMissionLead())) {
        if (p.user() != null) {
          factLeaderName = p.user().effectiveName();
        } else if (p.guestName() != null && !p.guestName().isBlank()) {
          factLeaderName = p.guestName();
        }
        break;
      }
    }
    if (factLeaderName == null && mission.owner() != null) {
      factLeaderName = mission.owner().effectiveName();
    }
    return factLeaderName;
  }

  /**
   * Collects the user ids of every account-backed participant, whose ships the unit modals offer.
   *
   * @param participants the sorted participants
   * @return the account-backed participant user ids
   */
  @NotNull
  private static Set<UUID> participantUserIds(List<MissionParticipantDto> participants) {
    Set<UUID> participantUserIds = new HashSet<>();
    for (MissionParticipantDto p : participants) {
      if (p.user() != null && p.user().id() != null) {
        participantUserIds.add(p.user().id());
      }
    }
    return participantUserIds;
  }

  /**
   * Builds the unit-assignment lookups: participant id to the space-joined names of the units they
   * crew, and the ids of ships already assigned to a unit.
   *
   * @param mission the mission whose assigned units to index
   * @return the unit-assignment lookups
   */
  @NotNull
  private static UnitAssignments unitAssignments(MissionDto mission) {
    Map<UUID, String> assignedUnitByParticipantId = new HashMap<>();
    Set<UUID> assignedUnitShipIds = new HashSet<>();
    if (mission.assignedUnits() != null) {
      for (MissionUnitDto unit : mission.assignedUnits()) {
        if (unit.ship() != null && unit.ship().id() != null) {
          assignedUnitShipIds.add(unit.ship().id());
        }
        String unitName = unit.name() != null ? unit.name() : "";
        if (unit.crew() != null) {
          for (MissionCrewDto c : unit.crew()) {
            if (c.participantId() != null) {
              assignedUnitByParticipantId.merge(
                  c.participantId(), unitName, (oldVal, newVal) -> oldVal + " " + newVal);
            }
          }
        }
      }
    }
    return new UnitAssignments(assignedUnitByParticipantId, assignedUnitShipIds);
  }

  /**
   * Selects the participants not yet assigned to any unit, for the "Crew zuweisen" dropdown,
   * preserving the order of {@code participants}.
   *
   * @param participants the sorted participants
   * @param assignedUnitByParticipantId the participant → unit lookup
   * @return the participants not yet assigned to any unit
   */
  private static List<MissionParticipantDto> unassignedParticipants(
      @NotNull List<MissionParticipantDto> participants,
      Map<UUID, String> assignedUnitByParticipantId) {
    return participants.stream()
        .filter(p -> p.id() != null && !assignedUnitByParticipantId.containsKey(p.id()))
        .toList();
  }

  /**
   * Indexes the participants by id, so the crew board can resolve the full payload of a unit's crew
   * member.
   *
   * @param participants the sorted participants
   * @return participant id → participant payload
   */
  @NotNull
  private static Map<UUID, MissionParticipantDto> participantsById(
      List<MissionParticipantDto> participants) {
    Map<UUID, MissionParticipantDto> participantsById = new HashMap<>();
    for (MissionParticipantDto p : participants) {
      if (p.id() != null) {
        participantsById.put(p.id(), p);
      }
    }
    return participantsById;
  }

  /**
   * Calculates each participant's percentage share of the total participant-time, clamped to the
   * mission window; all shares are {@code 0.0} until the mission has an actual start time.
   *
   * @param participants the sorted participants
   * @param mission the mission (for the actual start/end window)
   * @return participant id → participation percentage
   */
  @NotNull
  private static Map<UUID, Double> participationPercentages(
      List<MissionParticipantDto> participants, MissionDto mission) {
    Map<UUID, Double> participationPercentages = new HashMap<>();
    for (MissionParticipantDto p : participants) {
      participationPercentages.put(p.id(), 0.0);
    }

    Instant missionStart = mission.actualStartTime();
    Instant missionEnd = mission.actualEndTime();

    if (missionStart != null) {
      long totalDurationSeconds = 0;

      Map<UUID, Long> participantDurations = new HashMap<>();

      for (MissionParticipantDto p : participants) {
        Instant participantStart = p.startTime();
        Instant participantEnd = p.endTime();

        if (participantStart != null) {
          Instant effectiveStart =
              participantStart.isBefore(missionStart) ? missionStart : participantStart;
          Instant effectiveEnd;
          if (participantEnd != null) {
            effectiveEnd =
                (missionEnd != null && participantEnd.isAfter(missionEnd))
                    ? missionEnd
                    : participantEnd;
          } else {
            effectiveEnd = (missionEnd != null) ? missionEnd : Instant.now();
          }

          if (effectiveEnd.isAfter(effectiveStart)) {
            long duration = Duration.between(effectiveStart, effectiveEnd).getSeconds();
            participantDurations.put(p.id(), duration);
            totalDurationSeconds += duration;
          }
        }
      }

      if (totalDurationSeconds > 0) {
        for (MissionParticipantDto p : participants) {
          Long duration = participantDurations.get(p.id());
          if (duration != null) {
            double percentage = (double) duration / totalDurationSeconds * 100.0;
            participationPercentages.put(p.id(), percentage);
          }
        }
      }
    }
    return participationPercentages;
  }

  /**
   * Splits the mission's frequencies into a lookup of typed channels and a name-sorted list of
   * custom channels (REQ-MISSION-014).
   *
   * @param mission the mission whose frequencies to split
   * @return the frequency grouping
   */
  @NotNull
  private static FrequencyGrouping groupFrequencies(MissionDto mission) {
    Map<String, MissionFrequencyDto> frequencyByTypeId = new HashMap<>();
    List<MissionFrequencyDto> customFrequencies = new ArrayList<>();
    if (mission.frequencies() != null) {
      for (MissionFrequencyDto f : mission.frequencies()) {
        if (f.frequencyTypeId() != null) {
          frequencyByTypeId.put(f.frequencyTypeId().toString(), f);
        } else if (f.name() != null) {
          customFrequencies.add(f);
        }
      }
    }
    customFrequencies.sort(
        Comparator.comparing(MissionFrequencyDto::name, String.CASE_INSENSITIVE_ORDER));
    return new FrequencyGrouping(frequencyByTypeId, customFrequencies);
  }

  /**
   * Resolves a participant's display name: the account's effective, display or user name, else the
   * guest name, else the empty string.
   *
   * @param participant the participant, or {@code null}
   * @return the non-null display name (possibly empty)
   */
  private static String extractParticipantName(MissionParticipantDto participant) {
    if (participant == null) {
      return "";
    }
    if (participant.user() != null) {
      if (participant.user().effectiveName() != null
          && !participant.user().effectiveName().isBlank()) {
        return participant.user().effectiveName();
      }
      if (participant.user().displayName() != null && !participant.user().displayName().isBlank()) {
        return participant.user().displayName();
      }
      if (participant.user().username() != null && !participant.user().username().isBlank()) {
        return participant.user().username();
      }
    }
    return participant.guestName() != null ? participant.guestName() : "";
  }

  /**
   * Paired output of {@link #groupByLeadType}: the by-lead-type participant map and the distinct
   * leadership job types in first-seen order.
   *
   * @param byLeadType participants grouped by leadership job-type id (string key)
   * @param leadTypes the distinct leadership job types, in first-seen order
   */
  private record LeadTypeGrouping(
      Map<String, List<MissionParticipantDto>> byLeadType, List<JobTypeDto> leadTypes) {}

  /**
   * Paired output of {@link #unitAssignments}: the participant → unit-name lookup and the
   * already-assigned ship ids.
   *
   * @param byParticipantId participant id → space-joined unit name(s)
   * @param shipIds ids of ships already pinned to a unit
   */
  private record UnitAssignments(Map<UUID, String> byParticipantId, Set<UUID> shipIds) {}

  /**
   * Paired output of {@link #groupFrequencies}: the typed-channel lookup and the ordered custom
   * channels.
   *
   * @param byTypeId typed (global) channel by frequency-type id (string key)
   * @param custom the mission-specific free-text channels, sorted by name
   */
  private record FrequencyGrouping(
      Map<String, MissionFrequencyDto> byTypeId, List<MissionFrequencyDto> custom) {}
}
