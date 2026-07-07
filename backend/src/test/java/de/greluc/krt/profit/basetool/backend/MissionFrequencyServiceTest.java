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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.*;

import de.greluc.krt.profit.basetool.backend.model.*;
import de.greluc.krt.profit.basetool.backend.repository.*;
import de.greluc.krt.profit.basetool.backend.service.MissionService;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MissionFrequencyServiceTest {

  @Autowired private MissionService missionService;

  @Autowired private MissionRepository missionRepository;

  @Autowired private FrequencyTypeRepository frequencyTypeRepository;

  @Autowired private MissionFrequencyRepository missionFrequencyRepository;

  @Test
  void testAddMissionFrequency() {
    Mission mission = new Mission();
    mission.setName("Test Frequency Mission");
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    FrequencyType type = new FrequencyType();
    type.setName("VHF");
    type.setDescription("VHF Frequency");
    type = frequencyTypeRepository.save(type);

    Mission updatedMission =
        missionService.addOrUpdateMissionFrequency(
            mission.getId(), type.getId(), new BigDecimal("123.45"));

    assertNotNull(updatedMission);
    assertEquals(1, updatedMission.getFrequencies().size());
    MissionFrequency freq = updatedMission.getFrequencies().iterator().next();
    assertEquals("VHF", freq.getFrequencyType().getName());
    assertEquals(new BigDecimal("123.45"), freq.getValue());
  }

  @Test
  void addOrUpdateTypedFrequency_isRaceFreeIdempotentUpsert() {
    Mission mission = new Mission();
    mission.setName("Freq Upsert Mission " + UUID.randomUUID());
    mission.setStatus("PLANNED");
    mission = missionRepository.save(mission);

    FrequencyType type = new FrequencyType();
    type.setName("Kommando " + UUID.randomUUID());
    type.setDescription("Command channel");
    type = frequencyTypeRepository.save(type);

    final UUID mid = mission.getId();
    final UUID tid = type.getId();

    // First set: the INSERT branch of the atomic ON CONFLICT upsert.
    missionService.addOrUpdateMissionFrequency(mid, tid, new BigDecimal("100.00"));

    // Setting the SAME channel again hits ON CONFLICT DO UPDATE — no duplicate row and no phantom
    // 409 (the typed upsert takes no client version; last-writer-wins by design, #1148). The former
    // find-then-insert would have needed a retry here under a real race.
    Mission updated =
        assertDoesNotThrow(
            () -> missionService.addOrUpdateMissionFrequency(mid, tid, new BigDecimal("222.50")));

    assertEquals(1, updated.getFrequencies().size(), "upsert must not create a duplicate row");
    assertEquals(new BigDecimal("222.50"), updated.getFrequencies().iterator().next().getValue());
  }

  @Test
  void testAddCustomMissionFrequency() {
    Mission mission = newPlannedMission("Custom Freq Add Mission");

    Mission updated =
        missionService.addCustomMissionFrequency(
            mission.getId(), "  Bergungsteam  ", new BigDecimal("42.10"));

    assertEquals(1, updated.getFrequencies().size());
    MissionFrequency freq = updated.getFrequencies().iterator().next();
    // Custom rows carry a trimmed label and no global type.
    assertNull(freq.getFrequencyType());
    assertEquals("Bergungsteam", freq.getName());
    assertEquals(new BigDecimal("42.10"), freq.getValue());
  }

  @Test
  void testUpdateCustomMissionFrequency() {
    Mission mission = newPlannedMission("Custom Freq Update Mission");
    missionService.addCustomMissionFrequency(mission.getId(), "Recon", new BigDecimal("10.00"));
    MissionFrequency freq = onlyFrequency(mission.getId());

    Mission updated =
        missionService.updateCustomMissionFrequency(
            mission.getId(), freq.getId(), "Recon 2", new BigDecimal("11.11"), freq.getVersion());

    MissionFrequency after = onlyFrequency(mission.getId());
    assertEquals("Recon 2", after.getName());
    assertEquals(new BigDecimal("11.11"), after.getValue());
    assertNull(after.getFrequencyType());
    assertEquals(1, updated.getFrequencies().size());
  }

  @Test
  void testUpdateCustomMissionFrequencyStaleVersionThrows() {
    Mission mission = newPlannedMission("Custom Freq Stale Mission");
    missionService.addCustomMissionFrequency(mission.getId(), "Alpha", new BigDecimal("1.00"));
    MissionFrequency freq = onlyFrequency(mission.getId());

    // First edit succeeds and bumps the row version to 1 (flushed below).
    missionService.updateCustomMissionFrequency(
        mission.getId(), freq.getId(), "Alpha", new BigDecimal("2.00"), freq.getVersion());
    missionFrequencyRepository.flush();

    // A second edit echoing the now-stale original version (0) surfaces as a 409.
    UUID freqId = freq.getId();
    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () ->
            missionService.updateCustomMissionFrequency(
                mission.getId(), freqId, "Alpha", new BigDecimal("3.00"), 0L));
  }

  @Test
  void testUpdateCustomRejectsTypedRow() {
    Mission mission = newPlannedMission("Custom Freq Typed Reject Mission");
    FrequencyType type = new FrequencyType();
    type.setName("UHF");
    type = frequencyTypeRepository.save(type);
    missionService.addOrUpdateMissionFrequency(
        mission.getId(), type.getId(), new BigDecimal("50.00"));
    MissionFrequency typed = onlyFrequency(mission.getId());

    // Reaching a typed (global) row through the custom endpoint is rejected.
    UUID typedId = typed.getId();
    Long v = typed.getVersion();
    assertThrows(
        IllegalArgumentException.class,
        () ->
            missionService.updateCustomMissionFrequency(
                mission.getId(), typedId, "Hijack", new BigDecimal("9.99"), v));
  }

  @Test
  void testRemoveCustomMissionFrequency() {
    Mission mission = newPlannedMission("Custom Freq Remove Mission");
    missionService.addCustomMissionFrequency(mission.getId(), "Temp", new BigDecimal("7.00"));
    MissionFrequency freq = onlyFrequency(mission.getId());

    Mission updated = missionService.removeMissionFrequency(mission.getId(), freq.getId());

    assertTrue(updated.getFrequencies().isEmpty());
  }

  @Test
  void testAddTypedFrequencyWhenCustomExistsDoesNotNpe() {
    Mission mission = newPlannedMission("Mixed Freq Mission");
    // A custom row (frequencyType == null) is present first.
    missionService.addCustomMissionFrequency(mission.getId(), "Recon", new BigDecimal("10.00"));

    FrequencyType type = new FrequencyType();
    type.setName("Tac");
    type = frequencyTypeRepository.save(type);

    // Upserting a NEW typed frequency must scan past the custom row without dereferencing its null
    // frequencyType (regression guard for the V201 nullable-type change).
    Mission updated =
        missionService.addOrUpdateMissionFrequency(
            mission.getId(), type.getId(), new BigDecimal("122.00"));

    assertEquals(2, updated.getFrequencies().size());
  }

  private Mission newPlannedMission(String name) {
    Mission mission = new Mission();
    mission.setName(name);
    mission.setStatus("PLANNED");
    return missionRepository.save(mission);
  }

  private MissionFrequency onlyFrequency(UUID missionId) {
    Mission mission = missionService.getMissionById(missionId);
    assertEquals(1, mission.getFrequencies().size());
    return mission.getFrequencies().iterator().next();
  }
}
