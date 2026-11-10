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

package de.greluc.krt.profit.basetool.backend.mission.web;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RequestMapping;

/** Pins the mission routes and effective handler gates across their operation families. */
class MissionHttpRoutesParityTest {
  @Test
  void preservesEveryRouteAndAuthorizationExpression() {
    TreeSet<String> actual = new TreeSet<>();
    for (Class<?> controller :
        List.of(
            MissionController.class,
            MissionScheduleController.class,
            MissionRosterController.class,
            MissionStructureController.class,
            MissionPlanController.class)) {
      assertThat(controller.getAnnotation(Transactional.class)).isNotNull();
      assertThat(controller.getAnnotation(RequestMapping.class).value())
          .containsExactly("/api/v1/missions");
      Arrays.stream(controller.getDeclaredMethods())
          .forEach(method -> {
            RequestMapping route =
                AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
            if (route != null) {
              PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
              String path = route.value().length == 0 ? "" : route.value()[0];
              actual.add(
                  method.getName()
                      + "|"
                      + route.method()[0]
                      + "|"
                      + path
                      + "|"
                      + (gate == null ? "" : gate.value()));
            }
          });
    }
    assertThat(actual)
        .containsExactlyInAnyOrder(
            "getAllMissions|GET||" + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "lookupMissions|GET|/lookup|" + ("isAuthenticated()"),
            "getMissionById|GET|/{id}|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"
                    + " and @missionAccessPolicy.canSeeMission(#id)"),
            "getNextMission|GET|/next|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "createMission|POST||" + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "createSubMission|POST|/{id}/sub-missions|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "patchMissionCore|PATCH|/{id}/core|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "patchMissionSchedule|PATCH|/{id}/schedule|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "patchMissionFlags|PATCH|/{id}/flags|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "deleteMission|DELETE|/{id}|" + (Roles.HAS_ROLE_ADMIN),
            "joinMission|POST|/{id}/join|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "updateMissionOwner|PUT|/{id}/owner|"
                + ("@missionAccessPolicy.canChangeOwner(#id, authentication)"),
            "updateMissionOwningOrgUnit|PUT|/{id}/owning-org-unit|"
                + ("@missionAccessPolicy.canChangeOwner(#id, authentication)"),
            "setPartyLead|PUT|/{id}/party-lead|" + ("isAuthenticated()"),
            "addUnitSlim|POST|/{id}/units|" + ("isAuthenticated()"),
            "updateUnitSlim|PUT|/{id}/units/{unitId}|" + ("isAuthenticated()"),
            "deleteUnitSlim|DELETE|/{id}/units/{unitId}|" + ("isAuthenticated()"),
            "addStepSlim|POST|/{id}/steps|" + ("isAuthenticated()"),
            "updateStepSlim|PUT|/{id}/steps/{stepId}|" + ("isAuthenticated()"),
            "deleteStepSlim|DELETE|/{id}/steps/{stepId}|" + ("isAuthenticated()"),
            "reorderStepsSlim|PUT|/{id}/steps/reorder|" + ("isAuthenticated()"),
            "toggleStepDoneSlim|PATCH|/{id}/steps/{stepId}/done|" + ("isAuthenticated()"),
            "addObjectiveSlim|POST|/{id}/objectives|" + ("isAuthenticated()"),
            "updateObjectiveSlim|PUT|/{id}/objectives/{objectiveId}|" + ("isAuthenticated()"),
            "deleteObjectiveSlim|DELETE|/{id}/objectives/{objectiveId}|" + ("isAuthenticated()"),
            "reorderObjectivesSlim|PUT|/{id}/objectives/reorder|" + ("isAuthenticated()"),
            "getUnitShipOptions|GET|/{id}/unit-ship-options|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "addCrewSlim|POST|/{id}/units/{missionUnitId}/crew|" + ("isAuthenticated()"),
            "updateCrewSlim|PUT|/{id}/units/{missionUnitId}/crew/{crewId}|" + ("isAuthenticated()"),
            "removeCrewSlim|DELETE|/{id}/units/{missionUnitId}/crew/{crewId}|"
                + ("isAuthenticated()"),
            "updateParticipantSlim|PUT|/{id}/participants/{participantId}|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "checkInParticipantSlim|POST|/{id}/participants/{participantId}/check-in|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "checkOutParticipantSlim|POST|/{id}/participants/{participantId}/check-out|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "updatePayoutPreferenceSlim|PUT|/{id}/participants/{participantId}/payout-preference|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "addParticipantSlim|POST|/{id}/participants|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "removeParticipantSlim|DELETE|/{id}/participants/{participantId}|"
                + ("isAuthenticated() and @authHelperService.isMemberOrAbove()"),
            "getUnassignedParticipants|GET|/{id}/participants/unassigned|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "addOrUpdateFrequencySlim|POST|/{id}/frequencies|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "removeFrequencySlim|DELETE|/{id}/frequencies/{frequencyId}|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "addCustomFrequencySlim|POST|/{id}/frequencies/custom|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "updateCustomFrequencySlim|PUT|/{id}/frequencies/custom/{frequencyId}|"
                + ("@missionAccessPolicy.canManageMission(#id, authentication)"),
            "addManagerSlim|POST|/{id}/managers/{userId}|" + ("isAuthenticated()"),
            "removeManagerSlim|DELETE|/{id}/managers/{userId}|" + ("isAuthenticated()"));
  }
}
