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

package de.greluc.krt.profit.basetool.frontend.leadership.client;

import de.greluc.krt.profit.basetool.frontend.model.dto.AddBereichLeaderRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AddOlMemberRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.AssignSquadronRankRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BereichMemberResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.CreateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.GrandAdmiralRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.KommandoGroupDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.LeitungViewDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OlMemberResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgChartDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.UpdateKommandoGroupRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the Leitung page (REQ-ROLE-004): the delegated view, the org chart that
 * places its units, the Spezialkommando role flags and the rank, Kommandogruppe, Bereich and
 * Organisationsleitung writes, over {@link BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class LeadershipBackendClient {

  /** The backend's Organisationsleitung resource. */
  private static final String OL = "/api/v1/org-hierarchy/organisationsleitung/{olId}";

  private static final ParameterizedTypeReference<List<OrgUnitMembershipDto>> MEMBER_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads the units the caller may manage, with their rosters.
   *
   * @return the delegated view, or {@code null} when the backend sent no body
   */
  @Nullable
  public LeitungViewDto leitungView() {
    return backendApiClient.get("/api/v1/leitung/view", LeitungViewDto.class);
  }

  /**
   * Reads the org chart, which places each unit under its Bereich and department.
   *
   * @return the chart, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgChartDto orgChart() {
    return backendApiClient.get("/api/v1/org-chart", OrgChartDto.class);
  }

  /**
   * Reads a Spezialkommando's roster with each member's role flags.
   *
   * @param skId the Spezialkommando
   * @return the roster, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipDto> specialCommandMembers(@NotNull UUID skId) {
    return backendApiClient.get("/api/v1/special-commands/{id}/members", MEMBER_LIST, skId);
  }

  /**
   * Assigns or changes a member's squadron leadership rank.
   *
   * @param squadronId the Staffel
   * @param userId the member
   * @param request the rank, its Kommandogruppe and the membership's version
   * @return the persisted membership, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitMembershipDto assignSquadronRank(
      @NotNull UUID squadronId, @NotNull UUID userId, @Nullable AssignSquadronRankRequest request) {
    return backendApiClient.put(
        "/api/v1/squadrons/{squadronId}/ranks/{userId}",
        request,
        OrgUnitMembershipDto.class,
        squadronId,
        userId);
  }

  /**
   * Clears a member's squadron leadership rank back to a plain member.
   *
   * @param squadronId the Staffel
   * @param userId the member
   * @param version the optimistic-lock version the caller last read
   * @return the persisted membership, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitMembershipDto removeSquadronRank(
      @NotNull UUID squadronId, @NotNull UUID userId, long version) {
    return backendApiClient.delete(
        "/api/v1/squadrons/{squadronId}/ranks/{userId}?version={version}",
        OrgUnitMembershipDto.class,
        squadronId,
        userId,
        version);
  }

  /**
   * Creates a Kommandogruppe in a Staffel.
   *
   * @param squadronId the Staffel
   * @param request the group's name
   * @return the created group, or {@code null} when the backend sent no body
   */
  @Nullable
  public KommandoGroupDto createKommandoGroup(
      @NotNull UUID squadronId, @Nullable CreateKommandoGroupRequest request) {
    return backendApiClient.post(
        "/api/v1/squadrons/{squadronId}/kommando-groups",
        request,
        KommandoGroupDto.class,
        squadronId);
  }

  /**
   * Renames or reorders a Kommandogruppe.
   *
   * @param groupId the group
   * @param request the name, position and version
   * @return the updated group, or {@code null} when the backend sent no body
   */
  @Nullable
  public KommandoGroupDto updateKommandoGroup(
      @NotNull UUID groupId, @Nullable UpdateKommandoGroupRequest request) {
    return backendApiClient.put(
        "/api/v1/kommando-groups/{groupId}", request, KommandoGroupDto.class, groupId);
  }

  /**
   * Deletes a Kommandogruppe.
   *
   * @param groupId the group
   */
  public void deleteKommandoGroup(@NotNull UUID groupId) {
    backendApiClient.delete("/api/v1/kommando-groups/{groupId}", Void.class, groupId);
  }

  /**
   * Grants a Bereich leadership role.
   *
   * @param bereichId the Bereich
   * @param request the member and the role
   * @return the member's role flags, or {@code null} when the backend sent no body
   */
  @Nullable
  public BereichMemberResponse addBereichLeader(
      @NotNull UUID bereichId, @Nullable AddBereichLeaderRequest request) {
    return backendApiClient.post(
        "/api/v1/org-hierarchy/bereiche/{bereichId}/members",
        request,
        BereichMemberResponse.class,
        bereichId);
  }

  /**
   * Removes a Bereich leadership member.
   *
   * @param bereichId the Bereich
   * @param userId the member
   */
  public void removeBereichLeader(@NotNull UUID bereichId, @NotNull UUID userId) {
    backendApiClient.delete(
        "/api/v1/org-hierarchy/bereiche/{bereichId}/members/{userId}",
        Void.class,
        bereichId,
        userId);
  }

  /**
   * Adds an Organisationsleitung member.
   *
   * @param olId the Organisationsleitung
   * @param request the member
   * @return the member's flag, or {@code null} when the backend sent no body
   */
  @Nullable
  public OlMemberResponse addOlMember(@NotNull UUID olId, @Nullable AddOlMemberRequest request) {
    return backendApiClient.post(OL + "/members", request, OlMemberResponse.class, olId);
  }

  /**
   * Removes an Organisationsleitung member.
   *
   * @param olId the Organisationsleitung
   * @param userId the member
   */
  public void removeOlMember(@NotNull UUID olId, @NotNull UUID userId) {
    backendApiClient.delete(OL + "/members/{userId}", Void.class, olId, userId);
  }

  /**
   * Designates the Grand Admiral of the Organisationsleitung (REQ-ORG-021).
   *
   * @param olId the Organisationsleitung
   * @param request the designated member
   */
  public void setGrandAdmiral(@NotNull UUID olId, @Nullable GrandAdmiralRequest request) {
    backendApiClient.put(OL + "/grand-admiral", request, Void.class, olId);
  }

  /**
   * Vacates the Grand Admiral post; the former holder stays an Organisationsleitung member.
   *
   * @param olId the Organisationsleitung
   */
  public void removeGrandAdmiral(@NotNull UUID olId) {
    backendApiClient.delete(OL + "/grand-admiral", Void.class, olId);
  }

  /**
   * Sets or clears the SK-Leiter flag on a Spezialkommando member.
   *
   * @param skId the Spezialkommando
   * @param userId the member
   * @param request the new lead state and the membership's version
   * @return the persisted membership, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitMembershipDto toggleSkLead(
      @NotNull UUID skId, @NotNull UUID userId, @Nullable MembershipLeadToggleRequest request) {
    return backendApiClient.patch(
        "/api/v1/special-commands/{skId}/members/{userId}/lead",
        request,
        OrgUnitMembershipDto.class,
        skId,
        userId);
  }
}
