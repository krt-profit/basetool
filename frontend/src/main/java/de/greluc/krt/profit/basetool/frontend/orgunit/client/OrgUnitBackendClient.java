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

package de.greluc.krt.profit.basetool.frontend.orgunit.client;

import de.greluc.krt.profit.basetool.frontend.model.dto.BereichCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.BereichDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MembershipFlagsPatchRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MembershipLeadToggleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitNodeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitParentResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitParentUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrganisationsleitungCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrganisationsleitungDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.SpecialCommandProfitEligibleToggleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronProfitEligibleToggleRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.SquadronPromotionToggleRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;

/**
 * Typed backend client of the org-unit domain: the org hierarchy (REQ-ORG-014), the admin
 * Spezialkommando lifecycle, the SK member roster (REQ-ORG-005) and the admin squadron and SK
 * toggles, over {@link BackendApiClient} (plan §5.9, ADR-0032). Catalogue evictions stay with the
 * caller.
 */
@Service
@RequiredArgsConstructor
public class OrgUnitBackendClient {

  /** The backend's Spezialkommando resource. */
  private static final String SPECIAL_COMMAND = "/api/v1/special-commands/{id}";

  /** One page of the Spezialkommando catalogue, sorted by name. */
  private static final String SPECIAL_COMMAND_CATALOGUE =
      "/api/v1/special-commands?size=1000&sort=name,asc"
          + "&includeInactive={includeInactive}&page={page}";

  /** The backend's single SK membership resource. */
  private static final String SPECIAL_COMMAND_MEMBER =
      "/api/v1/special-commands/{id}/members/{userId}";

  private static final ParameterizedTypeReference<List<OrgUnitNodeDto>> NODE_LIST =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<SpecialCommandDto>>
      SPECIAL_COMMAND_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipDto>> MEMBER_LIST =
      new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Lists every active org unit with its parent edge and version.
   *
   * @return the flat node list, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitNodeDto> orgUnitNodes() {
    return backendApiClient.get("/api/v1/org-units", NODE_LIST);
  }

  /**
   * Creates a Bereich, optionally already under the Organisationsleitung.
   *
   * @param request the new Bereich
   * @return the created Bereich, or {@code null} when the backend sent no body
   */
  @Nullable
  public BereichDto createBereich(@Nullable BereichCreateRequest request) {
    return backendApiClient.post("/api/v1/org-units/bereiche", request, BereichDto.class);
  }

  /**
   * Creates the Organisationsleitung; the backend refuses a second one with 409.
   *
   * @param request the new Organisationsleitung
   * @return the created Organisationsleitung, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrganisationsleitungDto createOrganisationsleitung(
      @Nullable OrganisationsleitungCreateRequest request) {
    return backendApiClient.post(
        "/api/v1/org-units/organisationsleitung", request, OrganisationsleitungDto.class);
  }

  /**
   * Sets or clears an org unit's parent, carrying the child's optimistic-lock version.
   *
   * @param id the child org unit
   * @param request the new parent and the child's version
   * @return the child's new parent edge and version, or {@code null} when the backend sent no body
   */
  @Nullable
  public OrgUnitParentResponse setParent(
      @NotNull UUID id, @Nullable OrgUnitParentUpdateRequest request) {
    return backendApiClient.patch(
        "/api/v1/org-units/{id}/parent", request, OrgUnitParentResponse.class, id);
  }

  /**
   * Reads one page of the Spezialkommando catalogue, sorted by name.
   *
   * @param includeInactive whether soft-deleted Spezialkommandos are listed
   * @param page the zero-based page index
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<SpecialCommandDto> specialCommandPage(boolean includeInactive, int page) {
    return backendApiClient.get(
        SPECIAL_COMMAND_CATALOGUE, SPECIAL_COMMAND_PAGE, includeInactive, page);
  }

  /**
   * Creates a Spezialkommando.
   *
   * @param body the new Spezialkommando
   */
  public void createSpecialCommand(@NotNull SpecialCommandDto body) {
    backendApiClient.post("/api/v1/special-commands", body, Void.class);
  }

  /**
   * Updates a Spezialkommando, carrying the optimistic-lock version in the body.
   *
   * @param id the Spezialkommando
   * @param body the edited Spezialkommando
   */
  public void updateSpecialCommand(@NotNull UUID id, @NotNull SpecialCommandDto body) {
    backendApiClient.put(SPECIAL_COMMAND, body, Void.class, id);
  }

  /**
   * Soft-deletes a Spezialkommando.
   *
   * @param id the Spezialkommando
   */
  public void deleteSpecialCommand(@NotNull UUID id) {
    backendApiClient.delete(SPECIAL_COMMAND, Void.class, id);
  }

  /**
   * Re-activates a soft-deleted Spezialkommando.
   *
   * @param id the Spezialkommando
   */
  public void activateSpecialCommand(@NotNull UUID id) {
    backendApiClient.post("/api/v1/special-commands/{id}/activate", null, Void.class, id);
  }

  /**
   * Sets or clears the SK-Leiter flag on a Spezialkommando membership.
   *
   * @param id the Spezialkommando
   * @param userId the member
   * @param request the new lead state and the membership's version
   */
  public void toggleSpecialCommandLead(
      @NotNull UUID id, @NotNull UUID userId, @NotNull MembershipLeadToggleRequest request) {
    backendApiClient.patch(
        "/api/v1/special-commands/{id}/members/{userId}/lead", request, Void.class, id, userId);
  }

  /**
   * Reads one Spezialkommando.
   *
   * @param id the Spezialkommando
   * @return the Spezialkommando, or {@code null} when the backend sent no body
   */
  @Nullable
  public SpecialCommandDto specialCommand(@NotNull UUID id) {
    return backendApiClient.get(SPECIAL_COMMAND, SpecialCommandDto.class, id);
  }

  /**
   * Reads the member roster of one Spezialkommando with each member's role flags.
   *
   * @param id the Spezialkommando
   * @return the roster, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipDto> specialCommandMembers(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/special-commands/{id}/members", MEMBER_LIST, id);
  }

  /**
   * Adds a user to a Spezialkommando.
   *
   * @param id the Spezialkommando
   * @param userId the user to add
   */
  public void addSpecialCommandMember(@NotNull UUID id, @NotNull UUID userId) {
    backendApiClient.post(SPECIAL_COMMAND_MEMBER, null, Void.class, id, userId);
  }

  /**
   * Removes a user from a Spezialkommando.
   *
   * @param id the Spezialkommando
   * @param userId the user to remove
   */
  public void removeSpecialCommandMember(@NotNull UUID id, @NotNull UUID userId) {
    backendApiClient.delete(SPECIAL_COMMAND_MEMBER, Void.class, id, userId);
  }

  /**
   * Sets the Logistiker and Einsatzmanager flags of a Spezialkommando membership.
   *
   * @param id the Spezialkommando
   * @param userId the member
   * @param request both flags and the membership's version
   */
  public void patchSpecialCommandMemberFlags(
      @NotNull UUID id, @NotNull UUID userId, @NotNull MembershipFlagsPatchRequest request) {
    backendApiClient.patch(SPECIAL_COMMAND_MEMBER, request, Void.class, id, userId);
  }

  /**
   * Sets whether a Spezialkommando may process Job Orders.
   *
   * @param id the Spezialkommando
   * @param request the new flag
   */
  public void setSpecialCommandProfitEligible(
      @NotNull UUID id, @NotNull SpecialCommandProfitEligibleToggleRequest request) {
    backendApiClient.patch(
        "/api/v1/special-commands/{id}/profit-eligible", request, Void.class, id);
  }

  /**
   * Sets whether the promotion feature is on for a squadron.
   *
   * @param id the squadron
   * @param request the new flag
   */
  public void setSquadronPromotionEnabled(
      @NotNull UUID id, @NotNull SquadronPromotionToggleRequest request) {
    backendApiClient.patch("/api/v1/squadrons/{id}/promotion-enabled", request, Void.class, id);
  }

  /**
   * Sets whether a squadron may process Job Orders.
   *
   * @param id the squadron
   * @param request the new flag
   */
  public void setSquadronProfitEligible(
      @NotNull UUID id, @NotNull SquadronProfitEligibleToggleRequest request) {
    backendApiClient.patch("/api/v1/squadrons/{id}/profit-eligible", request, Void.class, id);
  }
}
