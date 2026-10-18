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

import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.mission.api.Mission;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionReferenceDto;
import de.greluc.krt.profit.basetool.backend.mission.api.MissionRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import de.greluc.krt.profit.basetool.backend.scope.api.ScopeParityCallers;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Parity of the mission picker query and the mission read gate (REQ-DATA-010, plan section 5.4):
 * over the caller matrix of the access-policy differential tests and missions that are internal or
 * public, of every unit and of none, {@code MissionRepository#findAllActiveReference} never lists a
 * mission {@link MissionAccessPolicy#canSeeMission} refuses, and the two agree on every mission,
 * for callers that count as a member or above and for those that do not.
 */
@SpringBootTest
@Transactional
class MissionScopeParityTest {

  @Autowired private MissionRepository missionRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private UserRepository userRepository;

  @Test
  void theListAndTheGateWidenTogetherForMembers() {
    run(true);
  }

  @Test
  void theListAndTheGateWidenTogetherForCallersBelowMember() {
    run(false);
  }

  private void run(boolean memberOrAbove) {
    ScopeParityCallers.Units units = ScopeParityCallers.persistUnits(squadronRepository);
    UUID callerId = ScopeParityCallers.persistUser(userRepository, "caller").getId();
    List<ScopeParityCallers.Row> rows =
        List.of(
            row("internal mission of A", mission(units.unitA(), true)),
            row("internal mission of B", mission(units.unitB(), true)),
            row("internal mission of child C", mission(units.childC(), true)),
            row("public mission of B", mission(units.unitB(), false)),
            row("internal ownerless mission", mission(null, true)),
            row("public ownerless mission", mission(null, false)));
    missionRepository.flush();

    ScopeParityCallers.check(
        units,
        callerId,
        memberOrAbove,
        rows,
        wired ->
            missionRepository
                .findAllActiveReference(
                    wired.predicate().adminAllScope(),
                    wired.predicate().activeOrgUnitId(),
                    wired.predicate().memberOrgUnitIds(),
                    wired.authHelper().isMemberOrAbove(),
                    Instant.EPOCH)
                .stream()
                .map(MissionReferenceDto::id)
                .collect(Collectors.toSet()),
        (wired, id) ->
            new MissionAccessPolicy(
                    wired.ownerScopeService(),
                    wired.authHelper(),
                    missionRepository,
                    null,
                    null,
                    null,
                    null)
                .canSeeMission(id));
  }

  private static ScopeParityCallers.Row row(String name, Mission mission) {
    return new ScopeParityCallers.Row(name, mission.getId(), true);
  }

  private Mission mission(UUID unit, boolean internal) {
    Mission mission = new Mission();
    mission.setName("Parity-M-" + UUID.randomUUID());
    mission.setStatus("PLANNED");
    mission.setIsInternal(internal);
    mission.setOwningOrgUnit(unit == null ? null : squadronRepository.getReferenceById(unit));
    return missionRepository.save(mission);
  }
}
