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

package de.greluc.krt.profit.basetool.frontend.identity.client;

import de.greluc.krt.profit.basetool.frontend.bank.model.ConsolidateAccountRequest;
import de.greluc.krt.profit.basetool.frontend.bank.model.MergeAccountRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.AdminDeletionRequestDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.ApproveRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.CreateDeletionRequestRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.DecideDeletionRequestRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.LinkRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyBlueprintSharingRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyBlueprintSharingResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyPayoutPreferenceRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyPayoutPreferenceResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyRsiHandleRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.MyRsiHandleResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.PendingCountDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.PendingRegistrationDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.PersonSearchResultDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.RegistrationStatusDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.RejectRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.ReopenRegistrationRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsAcceptanceStatusDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsDocumentDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.TermsStatusDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserAttributesUpdateDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDescriptionRequest;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserRsiHandleResponse;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserSyncResultDto;
import de.greluc.krt.profit.basetool.frontend.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.UserReferenceDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipDeltaRequest;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.MembershipDeltaResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.support.PickerSearch;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Service;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.netty.http.client.HttpClientRequest;

/**
 * Typed backend client of the identity domain: the member's own profile, RSI handle, data export
 * and erasure request, the member administration, the user pickers, the Discord registration queue,
 * the erasure-request queue, the person search, the approval status and the Terms-of-Use consent
 * (REQ-SEC-017, REQ-SEC-028, REQ-SEC-058, REQ-SEC-060, REQ-SEC-061, REQ-SEC-072), over {@link
 * BackendApiClient} (plan §5.9, ADR-0032).
 */
@Service
@RequiredArgsConstructor
public class IdentityBackendClient {

  /** The admin Discord registration queue. */
  private static final String REGISTRATIONS = "/api/v1/admin/registrations";

  private static final ParameterizedTypeReference<PageResponse<UserDto>> USER_PAGE =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<UserReferenceDto>>
      USER_REFERENCE_PAGE = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      MEMBERSHIP_OPTION_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<PendingRegistrationDto>>
      PENDING_REGISTRATION_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<List<AdminDeletionRequestDto>>
      DELETION_REQUEST_LIST = new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PersonSearchResultDto> PERSON_SEARCH_RESULT =
      new ParameterizedTypeReference<>() {};

  private static final ParameterizedTypeReference<PageResponse<TermsAcceptanceStatusDto>>
      TERMS_STATUS_PAGE = new ParameterizedTypeReference<>() {};

  /** Sends every call through the one filter chain and error mapping. */
  private final BackendApiClient backendApiClient;

  /**
   * Reads the caller's own user record.
   *
   * @return the caller's record, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto me() {
    return backendApiClient.get("/api/v1/users/me", UserDto.class);
  }

  /**
   * Reads the caller's default payout preference with the user-row version.
   *
   * @return the preference, or {@code null} when the backend sent no body
   */
  @Nullable
  public MyPayoutPreferenceResponse myPayoutPreference() {
    return backendApiClient.get(
        "/api/v1/users/me/payout-preference", MyPayoutPreferenceResponse.class);
  }

  /**
   * Reads the caller's global blueprint-sharing flag with the user-row version.
   *
   * @return the flag, or {@code null} when the backend sent no body
   */
  @Nullable
  public MyBlueprintSharingResponse myBlueprintSharing() {
    return backendApiClient.get(
        "/api/v1/users/me/blueprint-sharing", MyBlueprintSharingResponse.class);
  }

  /**
   * Reads the caller's own RSI handle with the user-row version (REQ-SEC-072).
   *
   * @return the handle, or {@code null} when the backend sent no body
   */
  @Nullable
  public MyRsiHandleResponse myRsiHandle() {
    return backendApiClient.get("/api/v1/users/me/rsi-handle", MyRsiHandleResponse.class);
  }

  /**
   * Reads the caller's own erasure request (REQ-SEC-061).
   *
   * @return the latest request, or {@code null} when the caller has none
   */
  @Nullable
  public AdminDeletionRequestDto myDeletionRequest() {
    return backendApiClient.get("/api/v1/users/me/deletion-request", AdminDeletionRequestDto.class);
  }

  /**
   * Saves the caller's description and display name.
   *
   * @param update the new values and the user-row version
   */
  public void updateMyDescription(@NotNull UserDescriptionRequest update) {
    backendApiClient.put("/api/v1/users/me/description", update, Void.class);
  }

  /**
   * Saves the caller's default payout preference.
   *
   * @param update the preference and the user-row version
   */
  public void updateMyPayoutPreference(@NotNull MyPayoutPreferenceRequest update) {
    backendApiClient.put("/api/v1/users/me/payout-preference", update, Void.class);
  }

  /**
   * Saves the caller's global blueprint-sharing flag.
   *
   * @param update the flag and the user-row version
   */
  public void updateMyBlueprintSharing(@NotNull MyBlueprintSharingRequest update) {
    backendApiClient.put("/api/v1/users/me/blueprint-sharing", update, Void.class);
  }

  /**
   * Saves the caller's RSI handle without reading the answer (REQ-SEC-072).
   *
   * @param update the trimmed handle and the user-row version
   */
  public void saveMyRsiHandle(@NotNull MyRsiHandleRequest update) {
    backendApiClient.put("/api/v1/users/me/rsi-handle", update, Void.class);
  }

  /**
   * Saves the caller's RSI handle and returns what was stored (REQ-SEC-072).
   *
   * @param update the trimmed handle and the user-row version
   * @return the stored handle and the new user-row version, or {@code null} when the backend sent
   *     no body
   */
  @Nullable
  public MyRsiHandleResponse updateMyRsiHandle(@NotNull MyRsiHandleRequest update) {
    return backendApiClient.put("/api/v1/users/me/rsi-handle", update, MyRsiHandleResponse.class);
  }

  /**
   * Raises the caller's erasure request (REQ-SEC-061).
   *
   * @param request whether the handle snapshots should be anonymised too
   * @return the created request, or {@code null} when the backend sent no body
   */
  @Nullable
  public AdminDeletionRequestDto requestDeletion(@NotNull CreateDeletionRequestRequest request) {
    return backendApiClient.post(
        "/api/v1/users/me/deletion-request", request, AdminDeletionRequestDto.class);
  }

  /** Withdraws the caller's pending erasure request (REQ-SEC-061). */
  public void withdrawDeletionRequest() {
    backendApiClient.delete("/api/v1/users/me/deletion-request", Void.class);
  }

  /**
   * Downloads the caller's data export as JSON (REQ-SEC-058).
   *
   * @param responseTimeout the per-request response timeout of the download
   * @return the JSON bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] myExportJson(@NotNull Duration responseTimeout) {
    return export("/api/v1/users/me/export", new Object[0], responseTimeout);
  }

  /**
   * Downloads the caller's data export as PDF (REQ-SEC-058).
   *
   * @param responseTimeout the per-request response timeout of the download
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] myExportPdf(@NotNull Duration responseTimeout) {
    return export("/api/v1/users/me/export/pdf", new Object[0], responseTimeout);
  }

  /**
   * Downloads another member's data export as PDF, for an admin (REQ-SEC-058).
   *
   * @param userId the member the export is about
   * @param responseTimeout the per-request response timeout of the download
   * @return the PDF bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] memberExportPdf(
      @NotNull UUID userId, @NotNull Duration responseTimeout) {
    return export(
        "/api/v1/admin/users/{userId}/export/pdf", new Object[] {userId}, responseTimeout);
  }

  /**
   * Downloads another member's data export as JSON, for an admin (REQ-SEC-058).
   *
   * @param userId the member the export is about
   * @param responseTimeout the per-request response timeout of the download
   * @return the JSON bytes, or {@code null} when the backend sent no body
   */
  public byte @Nullable [] memberExportJson(
      @NotNull UUID userId, @NotNull Duration responseTimeout) {
    return export("/api/v1/admin/users/{userId}/export", new Object[] {userId}, responseTimeout);
  }

  /**
   * Reads one page of the member list sorted by username.
   *
   * @param page the zero-based page index, or {@code null} for the backend default
   * @param size the page size, or {@code null} for the backend default
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<UserDto> memberPage(@Nullable Integer page, @Nullable Integer size) {
    return backendApiClient.get(pagedUsers("/api/v1/users", page, size), USER_PAGE);
  }

  /**
   * Reads one page of the members matching a free-text query, sorted by username.
   *
   * @param page the zero-based page index, or {@code null} for the backend default
   * @param size the page size, or {@code null} for the backend default
   * @param query the free-text query
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<UserDto> memberSearchPage(
      @Nullable Integer page, @Nullable Integer size, @NotNull String query) {
    String uri = pagedUsers("/api/v1/users/search", page, size);
    return backendApiClient.get(uri + "&query={query}", USER_PAGE, query);
  }

  /**
   * Reads the first 1000 members matching a free-text query for the member-picker typeahead.
   *
   * @param query the free-text query
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<UserDto> memberTypeahead(@NotNull String query) {
    String uri =
        UriComponentsBuilder.fromPath("/api/v1/users/search")
            .queryParam("size", 1000)
            .queryParam("sort", "username,asc")
            .toUriString();
    return backendApiClient.get(uri + "&query={query}", USER_PAGE, query);
  }

  /**
   * Reads one user record; the backend enforces the role gate and the guest redaction.
   *
   * @param id the user
   * @return the record, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto user(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/users/{id}", UserDto.class, id);
  }

  /**
   * Reads another member's RSI handle, for an admin (REQ-SEC-072).
   *
   * @param id the member
   * @return the handle, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserRsiHandleResponse userRsiHandle(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/users/{id}/rsi-handle", UserRsiHandleResponse.class, id);
  }

  /**
   * Lists a member's org-unit membership options of the Staffel and Spezialkommando kinds.
   *
   * @param id the member
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> memberships(@NotNull UUID id) {
    return backendApiClient.get("/api/v1/users/{id}/memberships", MEMBERSHIP_OPTION_LIST, id);
  }

  /**
   * Lists a member's org-unit membership options for the counterparty picker (REQ-BANK-044).
   *
   * @param userId the member
   * @param allKinds {@code true} to include all four org-unit kinds
   * @return the options, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<OrgUnitMembershipOptionDto> membershipOptions(
      @NotNull UUID userId, boolean allKinds) {
    return backendApiClient.get(
        "/api/v1/users/{userId}/memberships?allKinds={allKinds}",
        MEMBERSHIP_OPTION_LIST,
        userId,
        allKinds);
  }

  /**
   * Reads a member's org-unit memberships with their per-Staffel flags (REQ-ORG-017).
   *
   * @param id the member
   * @return the memberships, or {@code null} when the backend sent no body
   */
  @Nullable
  public MembershipDeltaResponse membershipDetail(@NotNull UUID id) {
    return backendApiClient.get(
        "/api/v1/users/{id}/memberships/detail", MembershipDeltaResponse.class, id);
  }

  /**
   * Saves a member's rank, description, display name and join date.
   *
   * @param id the member
   * @param update the new attributes and the user-row version
   */
  public void updateAttributes(@NotNull UUID id, @NotNull UserAttributesUpdateDto update) {
    backendApiClient.put("/api/v1/users/{id}/attributes", update, Void.class, id);
  }

  /**
   * Applies a membership delta to a member (REQ-ORG-017).
   *
   * @param id the member
   * @param delta the complete Staffel set
   * @return the memberships after the change, or {@code null} when the backend sent no body
   */
  @Nullable
  public MembershipDeltaResponse updateMemberships(
      @NotNull UUID id, @NotNull MembershipDeltaRequest delta) {
    return backendApiClient.patch(
        "/api/v1/users/{id}/memberships", delta, MembershipDeltaResponse.class, id);
  }

  /**
   * Deletes a user.
   *
   * @param id the user
   */
  public void deleteUser(@NotNull UUID id) {
    backendApiClient.delete("/api/v1/users/{id}", Void.class, id);
  }

  /**
   * Runs the manual Keycloak user sync.
   *
   * @return the sync result, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserSyncResultDto syncUsers() {
    return backendApiClient.post("/api/v1/users/sync", null, UserSyncResultDto.class);
  }

  /**
   * Merges a duplicate account into the surviving account (REQ-SEC-055).
   *
   * @param id the duplicate account
   * @param request the surviving account and the duplicate's version, or {@code null} when the
   *     browser sent none
   * @return the surviving account, or {@code null} when the backend sent no body
   */
  @Nullable
  public UserDto consolidate(@NotNull UUID id, @Nullable ConsolidateAccountRequest request) {
    return backendApiClient.post("/api/v1/users/{id}/consolidate", request, UserDto.class, id);
  }

  /**
   * Searches the slim user references for a participant or owner picker (REQ-FE-016).
   *
   * @param query the free-text query; empty matches all
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<UserReferenceDto> userReferences(@NotNull String query) {
    return references("/api/v1/users/search/references", query);
  }

  /**
   * Searches the slim user references for a bank picker, which also admits bank staff (ADR-0089).
   *
   * @param query the free-text query; empty matches all
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<UserReferenceDto> bankUserReferences(@NotNull String query) {
    return references("/api/v1/users/search-bank/references", query);
  }

  /**
   * Lists the Discord registrations awaiting approval.
   *
   * @return the queue, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PendingRegistrationDto> pendingRegistrations() {
    return backendApiClient.get(REGISTRATIONS, PENDING_REGISTRATION_LIST);
  }

  /**
   * Lists the rejected Discord registrations (REQ-SEC-034).
   *
   * @return the rejected registrations, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<PendingRegistrationDto> rejectedRegistrations() {
    return backendApiClient.get(REGISTRATIONS + "?status=REJECTED", PENDING_REGISTRATION_LIST);
  }

  /**
   * Approves a pending registration.
   *
   * @param id the registration
   * @param request the optimistic-lock version, or {@code null} when the browser sent none
   * @return the updated registration, or {@code null} when the backend sent no body
   */
  @Nullable
  public PendingRegistrationDto approveRegistration(
      @NotNull UUID id, @Nullable ApproveRegistrationRequest request) {
    return backendApiClient.post(
        REGISTRATIONS + "/{id}/approve", request, PendingRegistrationDto.class, id);
  }

  /**
   * Rejects a pending registration.
   *
   * @param id the registration
   * @param request the reason and optimistic-lock version, or {@code null} when the browser sent
   *     none
   * @return the updated registration, or {@code null} when the backend sent no body
   */
  @Nullable
  public PendingRegistrationDto rejectRegistration(
      @NotNull UUID id, @Nullable RejectRegistrationRequest request) {
    return backendApiClient.post(
        REGISTRATIONS + "/{id}/reject", request, PendingRegistrationDto.class, id);
  }

  /**
   * Moves a rejected registration back to pending (REQ-SEC-034).
   *
   * @param id the registration
   * @param request the note and optimistic-lock version, or {@code null} when the browser sent none
   * @return the now-pending registration, or {@code null} when the backend sent no body
   */
  @Nullable
  public PendingRegistrationDto reopenRegistration(
      @NotNull UUID id, @Nullable ReopenRegistrationRequest request) {
    return backendApiClient.post(
        REGISTRATIONS + "/{id}/reopen", request, PendingRegistrationDto.class, id);
  }

  /**
   * Merges an older account's data onto a registration (REQ-SEC-045).
   *
   * @param id the surviving registration
   * @param request the source account and optimistic-lock version, or {@code null} when the browser
   *     sent none
   * @return the surviving account, or {@code null} when the backend sent no body
   */
  @Nullable
  public PendingRegistrationDto mergeRegistration(
      @NotNull UUID id, @Nullable MergeAccountRequest request) {
    return backendApiClient.post(
        REGISTRATIONS + "/{id}/merge", request, PendingRegistrationDto.class, id);
  }

  /**
   * Links a pending registration onto an existing account (REQ-SEC-026).
   *
   * @param id the registration
   * @param request the target account and optimistic-lock version, or {@code null} when the browser
   *     sent none
   * @return the surviving account, or {@code null} when the backend sent no body
   */
  @Nullable
  public PendingRegistrationDto linkRegistration(
      @NotNull UUID id, @Nullable LinkRegistrationRequest request) {
    return backendApiClient.post(
        REGISTRATIONS + "/{id}/link", request, PendingRegistrationDto.class, id);
  }

  /**
   * Lists the members' erasure requests for the admin queue (REQ-SEC-061).
   *
   * @return the queue, or {@code null} when the backend sent no body
   */
  @Nullable
  public List<AdminDeletionRequestDto> deletionRequests() {
    return backendApiClient.get("/api/v1/admin/deletion-requests", DELETION_REQUEST_LIST);
  }

  /**
   * Refuses an erasure request with a reason (REQ-SEC-061).
   *
   * @param id the request
   * @param decision the reason and the request's version
   * @return the refused request, or {@code null} when the backend sent no body
   */
  @Nullable
  public AdminDeletionRequestDto declineDeletionRequest(
      @NotNull UUID id, @NotNull DecideDeletionRequestRequest decision) {
    return backendApiClient.post(
        "/api/v1/admin/deletion-requests/{id}/decline",
        decision,
        AdminDeletionRequestDto.class,
        id);
  }

  /**
   * Carries an erasure request out, deleting the local user and the Keycloak account (REQ-SEC-061).
   *
   * @param id the request
   * @param decision whether the handle snapshots are anonymised, and the request's version
   */
  public void executeDeletionRequest(
      @NotNull UUID id, @NotNull DecideDeletionRequestRequest decision) {
    backendApiClient.post("/api/v1/admin/deletion-requests/{id}/execute", decision, Void.class, id);
  }

  /**
   * Finds every place a person's name appears (REQ-SEC-060).
   *
   * @param term the search term, at least three characters
   * @return the hits, or {@code null} when the backend sent no body
   */
  @Nullable
  public PersonSearchResultDto personSearch(@NotNull String term) {
    return backendApiClient.get("/api/v1/admin/person-search?q={q}", PERSON_SEARCH_RESULT, term);
  }

  /**
   * Reads the caller's own approval status (REQ-SEC-017).
   *
   * @return the status, or {@code null} when the backend sent no body
   */
  @Nullable
  public RegistrationStatusDto registrationStatus() {
    return backendApiClient.get(
        "/api/v1/users/me/registration-status", RegistrationStatusDto.class);
  }

  /**
   * Reads the Terms-of-Use wording in force without a bearer token, for the public page
   * (REQ-SEC-052).
   *
   * @return the wording, or {@code null} when the backend sent no body
   */
  @Nullable
  public TermsDocumentDto publicTermsDocument() {
    return backendApiClient.getTermsDocumentAnonymously();
  }

  /**
   * Reads the Terms-of-Use wording the caller is asked to accept (REQ-SEC-028).
   *
   * @return the wording, or {@code null} when the backend sent no body
   */
  @Nullable
  public TermsDocumentDto termsDocument() {
    return backendApiClient.get("/api/v1/terms/document", TermsDocumentDto.class);
  }

  /**
   * Reads whether the caller has accepted the Terms of Use in force (REQ-SEC-028).
   *
   * @return the status, or {@code null} when the backend sent no body
   */
  @Nullable
  public TermsStatusDto termsStatus() {
    return backendApiClient.get("/api/v1/terms/status", TermsStatusDto.class);
  }

  /** Records the caller's consent to the Terms of Use in force (REQ-SEC-028). */
  public void acceptTerms() {
    backendApiClient.post("/api/v1/terms/acceptance", null, Void.class);
  }

  /**
   * Reads one page of the users with their Terms-of-Use consent state, sorted by username.
   *
   * @param filter {@code ALL}, {@code ACCEPTED} or {@code PENDING}
   * @param page the zero-based page index
   * @param size the page size
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  public PageResponse<TermsAcceptanceStatusDto> termsAcceptances(
      @NotNull String filter, int page, int size) {
    return backendApiClient.get(
        UriComponentsBuilder.fromPath("/api/v1/admin/terms")
            .queryParam("filter", filter)
            .queryParam("page", page)
            .queryParam("size", size)
            .queryParam("sort", "username,asc")
            .build()
            .toUriString(),
        TERMS_STATUS_PAGE);
  }

  /**
   * Reads how many users still owe Terms-of-Use consent.
   *
   * @return the count, or {@code null} when the backend sent no body
   */
  @Nullable
  public PendingCountDto termsPendingCount() {
    return backendApiClient.get("/api/v1/admin/terms/pending-count", PendingCountDto.class);
  }

  /**
   * Builds the URI of a member-list page sorted by username.
   *
   * @param path the listing path
   * @param page the zero-based page index, or {@code null} to omit it
   * @param size the page size, or {@code null} to omit it
   * @return the URI with its query
   */
  @NotNull
  private static String pagedUsers(
      @NotNull String path, @Nullable Integer page, @Nullable Integer size) {
    UriComponentsBuilder uri = UriComponentsBuilder.fromPath(path);
    if (page != null) {
      uri.queryParam("page", page);
    }
    if (size != null) {
      uri.queryParam("size", size);
    }
    uri.queryParam("sort", "username,asc");
    return uri.toUriString();
  }

  /**
   * Searches a user-reference endpoint with {@link PickerSearch#PAGE_SIZE} rows sorted by username,
   * the extra row past {@link PickerSearch#RENDER_CAP} being the overflow sentinel (REQ-FE-016).
   *
   * @param path the reference search path
   * @param query the free-text query
   * @return the page, or {@code null} when the backend sent no body
   */
  @Nullable
  private PageResponse<UserReferenceDto> references(@NotNull String path, @NotNull String query) {
    String uri =
        UriComponentsBuilder.fromPath(path)
            .queryParam("size", PickerSearch.PAGE_SIZE)
            .queryParam("sort", "username,asc")
            .toUriString();
    return backendApiClient.get(uri + "&query={query}", USER_REFERENCE_PAGE, query);
  }

  /**
   * Downloads one export document with an extended response timeout and no retries.
   *
   * @param uri the export URI template
   * @param uriVariables the values expanded into the template, in order
   * @param responseTimeout the per-request response timeout
   * @return the document bytes, or {@code null} when the backend sent no body
   */
  private byte @Nullable [] export(
      @NotNull String uri, @NotNull Object[] uriVariables, @NotNull Duration responseTimeout) {
    return backendApiClient.execute(
        HttpMethod.GET,
        uri,
        webClient ->
            webClient
                .get()
                .uri(uri, uriVariables)
                .httpRequest(
                    request -> {
                      HttpClientRequest nativeRequest = request.getNativeRequest();
                      nativeRequest.responseTimeout(responseTimeout);
                    }),
        spec -> spec.bodyToMono(byte[].class));
  }
}
