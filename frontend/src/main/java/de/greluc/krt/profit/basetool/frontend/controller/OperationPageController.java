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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.propagateBackendError;

import de.greluc.krt.profit.basetool.frontend.model.dto.MissionFinanceSummaryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MissionListDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationFinanceSummaryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationMissionFinanceDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutStatusDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutStatusUpdateDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OperationPayoutSummaryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.form.OperationForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.service.MarkdownRenderer;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the operations pages ({@code /operations} list and {@code
 * /operations/{id}} detail).
 *
 * <p>Operations are an umbrella over missions — the detail page renders the operation header,
 * embedded missions paginated separately, and the operation-level finance and payout summaries. The
 * {@code canEdit} flag computed in {@link #operationDetails} mirrors the backend's
 * {@code @PreAuthorize("hasRole('MISSION_MANAGER')")} so the template can disable inputs for users
 * who would just bounce off a 403 on submit — without leaking any role logic into the service
 * layer.
 */
@Controller
@RequestMapping("/operations")
@RequiredArgsConstructor
@Slf4j
public class OperationPageController {

  private final BackendApiClient backendApiClient;
  private final MarkdownRenderer markdown;
  private final FrontendAuthHelperService authHelper;
  private final ParallelPageLoader parallelPageLoader;

  /** Response type for one paginated page of the operations search endpoint. */
  private static final ParameterizedTypeReference<PageResponse<OperationDto>> OPERATION_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the caller's pickable org units feeding the owner-picker fragment. */
  private static final ParameterizedTypeReference<List<OrgUnitMembershipOptionDto>>
      PICKABLE_ORG_UNIT_LIST_TYPE = new ParameterizedTypeReference<>() {};

  /** Response type for one paginated page of an operation's embedded missions. */
  private static final ParameterizedTypeReference<PageResponse<MissionListDto>> MISSION_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  /**
   * Renders the paginated, filtered operations list. Mirrors the missions overview filter contract
   * within the limits of the operation aggregate: free-text {@code search} matches name +
   * description, {@code showPast} flips the default status filter from {@code PLANNED}+{@code
   * ACTIVE} to the full set, and the {@code start}/{@code end} time range filters on the
   * operation's derived span — an operation has no {@code plannedStartTime} of its own, so the
   * backend bounds {@code start} against the planned start of the earliest linked mission and
   * {@code end} against the planned end of the latest linked mission. The two bounds arrive as
   * ISO-8601 instants assembled client-side by {@code datetime-splitter.js} and are forwarded
   * verbatim. When {@code fragment=results} is supplied the controller returns just the results
   * fragment so the client-side AJAX filter can patch the list in place without a full page reload.
   *
   * @param search free-text query, may be {@code null}
   * @param start inclusive lower bound (ISO-8601) on the earliest linked mission's planned start,
   *     may be {@code null}/blank
   * @param end inclusive upper bound (ISO-8601) on the latest linked mission's planned end, may be
   *     {@code null}/blank
   * @param showPast when {@code true} (and authenticated), include COMPLETED and CANCELED
   * @param page zero-based page index
   * @param size page size (default 20)
   * @param fragment when equal to {@code "results"}, render only the results fragment
   * @param model Thymeleaf model populated with the page content and metadata
   * @param principal current OIDC user, bound from the security context; the {@code showPast}
   *     honouring now consults {@code authHelper.isAnonymous()} rather than this parameter directly
   * @return the {@code operations-index} view name, or the results fragment for AJAX
   */
  @GetMapping
  @PreAuthorize("isAuthenticated()")
  public String listOperations(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String start,
      @RequestParam(required = false) String end,
      @RequestParam(required = false, defaultValue = "false") boolean showPast,
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer size,
      @RequestParam(required = false) String fragment,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    StringBuilder uri = new StringBuilder("/api/v1/operations/search?");
    if (search != null && !search.isBlank()) {
      uri.append("query=").append(URLEncoder.encode(search, StandardCharsets.UTF_8)).append("&");
    }
    if (start != null && !start.isBlank()) {
      uri.append("start=").append(URLEncoder.encode(start, StandardCharsets.UTF_8)).append("&");
    }
    if (end != null && !end.isBlank()) {
      uri.append("end=").append(URLEncoder.encode(end, StandardCharsets.UTF_8)).append("&");
    }
    uri.append("page=").append(page).append("&");
    uri.append("size=").append(size).append("&");
    uri.append("sort=createdAt,desc&");

    boolean effectiveShowPast = showPast && !authHelper.isAnonymous();
    if (effectiveShowPast) {
      uri.append("status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELED&");
    } else {
      uri.append("status=PLANNED&status=ACTIVE&");
    }

    try {
      PageResponse<OperationDto> operationsPage =
          backendApiClient.get(uri.toString(), OPERATION_PAGE_TYPE, false);
      model.addAttribute("operations", operationsPage.content());
      model.addAttribute("operationsPage", operationsPage);
      model.addAttribute("search", search);
      model.addAttribute("start", start);
      model.addAttribute("end", end);
      model.addAttribute("showPast", effectiveShowPast);
    } catch (Exception e) {
      log.error("Error loading operations", e);
      model.addAttribute("error", "error.operations.load");
    }
    if (fragment != null && "results".equalsIgnoreCase(fragment)) {
      return "operations-index :: operationsResults";
    }
    model.addAttribute("ownerOptions", fetchCallerMembershipOptions());
    return "operations-index";
  }

  /**
   * Fetches the caller's OrgUnit memberships for the R5.d.e owner-picker fragment on the
   * operation-create modal. Operations have no explicit owner field — the actor (caller) is the
   * implicit owner — so the picker reflects the caller's own memberships. Returns an empty list for
   * anonymous callers or on backend hiccup; the fragment collapses to its hidden state in either
   * case.
   *
   * @return picker options or empty list; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchCallerMembershipOptions() {
    if (authHelper.isAnonymous()) {
      return List.of();
    }
    try {
      // Epic #692 Phase 5: drill-down owner picker — the caller's direct memberships plus their
      // cascading leadership reach (own Bereich/OL + overseen subordinate Staffeln/SKs). Unchanged
      // for an ordinary member.
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.get("/api/v1/users/me/pickable-org-units", PICKABLE_ORG_UNIT_LIST_TYPE);
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch pickable org units for operation-create owner-picker", e);
      return List.of();
    }
  }

  /**
   * Renders the operation detail page. Pulls operation, embedded missions, the finance roll-up and
   * payouts <em>concurrently</em> via {@link ParallelPageLoader} (#1123); any backend failure
   * aborts the render and redirects back to the list with a flash error. The finance read is the
   * cheap {@code /finance-summary} roll-up (#1121) — each mission's per-entry breakdown loads
   * lazily via {@link #operationMissionFinance}. Computes {@code canEdit} at the HTTP boundary by
   * reading the authorities off the {@link Authentication} object — keeps the template free of
   * role-expression checks and mirrors what the backend's PUT endpoint will accept.
   *
   * @param id operation id
   * @param page zero-based page index for the embedded missions table
   * @param size page size for the embedded missions table (default 10)
   * @param fragment when {@code "missions"} only the embedded missions sub-table fragment is
   *     rendered (AJAX pager swap, REQ-FE-002), skipping the finance/payout round-trips; otherwise
   *     the full page is returned
   * @param authentication current user's authentication (used for {@code canEdit})
   * @param model Thymeleaf model populated with operation, missions, finance and payouts
   * @return the {@code operation-detail} view name, its {@code missions} fragment for an AJAX swap,
   *     or a redirect on backend failure of the full-page load
   */
  @GetMapping("/{id}")
  @PreAuthorize("isAuthenticated()")
  public String operationDetails(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "10") Integer size,
      @RequestParam(required = false) String fragment,
      Authentication authentication,
      Model model) {
    if ("missions".equals(fragment)) {
      return missionsFragment(id, page, size, model);
    }
    try {
      // #1123: fetch the four independent reads concurrently on virtual threads (ParallelPageLoader
      // replays the request-scoped context — auth, active-org-unit pin, correlation id, client IP)
      // instead of blocking through them in series. The finance read is the cheap /finance-summary
      // roll-up (#1121, the operation-side ADR-0078 gap); each mission's per-entry breakdown loads
      // lazily via GET /operations/{id}/finance/{missionId} when its panel is expanded.
      CompletableFuture<OperationDto> operationF =
          parallelPageLoader.loadAsync(
              () -> backendApiClient.get("/api/v1/operations/" + id, OperationDto.class, false));
      CompletableFuture<PageResponse<MissionListDto>> missionsF =
          parallelPageLoader.loadAsync(() -> fetchMissionsPage(id, page, size));
      CompletableFuture<OperationFinanceSummaryDto> financeF =
          parallelPageLoader.loadAsync(
              () ->
                  backendApiClient.get(
                      "/api/v1/operations/" + id + "/finance-summary",
                      OperationFinanceSummaryDto.class,
                      false));
      CompletableFuture<OperationPayoutSummaryDto> payoutsF =
          parallelPageLoader.loadAsync(
              () ->
                  backendApiClient.get(
                      "/api/v1/operations/" + id + "/payouts",
                      OperationPayoutSummaryDto.class,
                      false));
      CompletableFuture.allOf(operationF, missionsF, financeF, payoutsF).join();

      OperationDto operation = operationF.join();
      model.addAttribute("operation", operation);

      PageResponse<MissionListDto> missionsPage = missionsF.join();
      model.addAttribute("missions", missionsPage.content());
      model.addAttribute("missionsPage", missionsPage);

      OperationFinanceSummaryDto operationFinance = financeF.join();
      model.addAttribute("operationFinance", operationFinance);

      OperationPayoutSummaryDto payoutSummary = payoutsF.join();
      model.addAttribute("operationPayouts", payoutSummary.payouts());
      model.addAttribute("operationDonationTotal", payoutSummary.totalDonations());

      // Largest per-mission result, so the "Ergebnis je Einsatz" overview bars can be sized
      // proportionally (BigDecimal.ZERO when there are no positive results — the template then
      // renders zero-width bars rather than dividing by zero).
      BigDecimal maxMissionResult =
          operationFinance.missions() == null
              ? BigDecimal.ZERO
              : operationFinance.missions().stream()
                  .map(OperationMissionFinanceDto::totalSum)
                  .filter(Objects::nonNull)
                  .max(BigDecimal::compareTo)
                  .orElse(BigDecimal.ZERO);
      model.addAttribute("operationMaxMissionResult", maxMissionResult);

      // Resolved at the HTTP boundary so the template stays free of inline
      // role-expression checks. The backend's PUT /api/v1/operations/{id}
      // requires ROLE_MISSION_MANAGER (or any role that reaches it via the
      // hierarchy — ADMIN, OFFICER) AND the same role is granted by the
      // app_user.is_mission_manager flag through the JWT-converter, so the
      // role check here matches what the backend enforces.
      model.addAttribute("canEdit", hasMissionManagerRole(authentication));
      // The "Bezahlt"-checkbox is asymmetric: any mission manager can set
      // it to paid, but only an officer or admin may clear it back to
      // unpaid. The template uses this flag to disable an already-checked
      // checkbox for plain mission managers — mirrors the asymmetric
      // @PreAuthorize on the backend's payouts/paid-out endpoint.
      model.addAttribute("canUnsetPaidOut", hasOfficerOrAdminRole(authentication));

    } catch (Exception e) {
      log.error("Error loading operation details", e);
      model.addAttribute("error", "error.operation.load");
      return "redirect:/operations";
    }
    return "operation-detail";
  }

  /**
   * Renders just the embedded missions sub-table for an AJAX pager swap (REQ-FE-002). Fetches only
   * the operation (needed for the pagination base URL) and the requested missions page — the
   * finance/payout round-trips the full page does are skipped. Unlike the full-page load this never
   * redirects: a backend failure degrades to an empty missions list so the swapped-in fragment
   * shows its empty state rather than injecting an unrelated redirect target into the sub-table.
   *
   * @param id operation id
   * @param page zero-based page index for the embedded missions table
   * @param size page size for the embedded missions table
   * @param model Thymeleaf model populated with {@code operation}, {@code missions} and {@code
   *     missionsPage}
   * @return the {@code operation-detail :: missions} fragment view
   */
  private String missionsFragment(UUID id, Integer page, Integer size, Model model) {
    try {
      model.addAttribute(
          "operation", backendApiClient.get("/api/v1/operations/" + id, OperationDto.class, false));
      PageResponse<MissionListDto> missionsPage = fetchMissionsPage(id, page, size);
      model.addAttribute("missions", missionsPage.content());
      model.addAttribute("missionsPage", missionsPage);
    } catch (Exception e) {
      log.error("Error loading missions fragment for operation {}", id, e);
      model.addAttribute("missions", List.of());
    }
    return "operation-detail :: missions";
  }

  /**
   * Fetches one page of the operation's missions from the backend search endpoint, ordered by
   * planned start time ascending — the single source of the missions-table query shared by the
   * full-page render and the {@link #missionsFragment} AJAX swap.
   *
   * @param id operation id whose missions to page through
   * @param page zero-based page index
   * @param size page size
   * @return the requested missions page envelope
   */
  private PageResponse<MissionListDto> fetchMissionsPage(UUID id, Integer page, Integer size) {
    return backendApiClient.get(
        "/api/v1/missions/search?operationId="
            + id
            + "&page="
            + page
            + "&size="
            + size
            + "&sort=plannedStartTime,asc",
        MISSION_PAGE_TYPE,
        false);
  }

  /**
   * Renders one mission's finance breakdown fragment for the lazy per-mission {@code <details>} on
   * the operation-detail finance tab (#1121). The operation-detail page fetches this on first
   * expand and injects the returned HTML in place, so the full page render no longer materializes
   * every finance entry / refinery order across every child mission. Authorized like the rest of
   * the operation page (the backend re-checks {@code canSeeOperation} and that the mission belongs
   * to the operation); a backend failure degrades to an inline error message inside the panel
   * rather than a redirect, so one flaky expand never takes down the whole page.
   *
   * @param id operation id
   * @param missionId the mission whose breakdown to load (must belong to the operation)
   * @param model Thymeleaf model populated with {@code financeDetail} (or {@code
   *     financeDetailError})
   * @return the {@code operation-detail :: financeDetail} fragment view
   */
  @GetMapping("/{id}/finance/{missionId}")
  @PreAuthorize("isAuthenticated()")
  public String operationMissionFinance(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID missionId, Model model) {
    try {
      MissionFinanceSummaryDto detail =
          backendApiClient.get(
              "/api/v1/operations/" + id + "/finances/" + missionId,
              MissionFinanceSummaryDto.class,
              false);
      model.addAttribute("financeDetail", detail);
    } catch (Exception e) {
      log.error("Error loading finance detail for operation {} mission {}", id, missionId, e);
      model.addAttribute("financeDetailError", true);
    }
    return "operation-detail :: financeDetail";
  }

  private static boolean hasMissionManagerRole(Authentication authentication) {
    if (authentication == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(
            role ->
                Roles.authority(Roles.ADMIN).equals(role)
                    || Roles.authority(Roles.OFFICER).equals(role)
                    || Roles.authority(Roles.MISSION_MANAGER).equals(role));
  }

  private static boolean hasOfficerOrAdminRole(Authentication authentication) {
    if (authentication == null) {
      return false;
    }
    return authentication.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(
            role ->
                Roles.authority(Roles.ADMIN).equals(role)
                    || Roles.authority(Roles.OFFICER).equals(role));
  }

  /**
   * Creates a new operation. {@code MISSION_MANAGER} role is required (admin/officer satisfy it via
   * the role hierarchy).
   *
   * @param form operation form
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /operations}
   */
  @PostMapping("/create")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public String createOperation(
      @ModelAttribute OperationForm form, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.post("/api/v1/operations", form, Void.class);
      redirectAttributes.addFlashAttribute("successMessage", "operation.create.success");
    } catch (Exception e) {
      log.error("Error creating operation", e);
      redirectAttributes.addFlashAttribute("errorMessage", "operation.create.error");
    }
    return "redirect:/operations";
  }

  /**
   * Updates an operation. A {@code 409 Conflict} from the backend is mapped to the
   * optimistic-locking flash message; any other failure to the generic update-error message.
   *
   * @param id operation id
   * @param form operation form (carries the optimistic-lock version)
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /operations}
   */
  @PostMapping("/{id}/update")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public String updateOperation(
      @PathVariable @NotNull UUID id,
      @ModelAttribute OperationForm form,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.put("/api/v1/operations/" + id, form, Void.class);
      redirectAttributes.addFlashAttribute("successMessage", "operation.update.success");
    } catch (WebClientResponseException.Conflict e) {
      log.warn("Optimistic locking failure updating operation: {}", id);
      redirectAttributes.addFlashAttribute("errorMessage", "error.optimistic.locking");
    } catch (Exception e) {
      log.error("Error updating operation", e);
      redirectAttributes.addFlashAttribute("errorMessage", "operation.update.error");
    }
    return "redirect:/operations";
  }

  /**
   * AJAX endpoint behind the per-row "Bezahlt" checkbox in the Auszahlungen panel. Proxies the call
   * straight to {@code PUT /api/v1/operations/{id}/payouts/paid-out}, forwarding the operation id
   * from the URL and the participant key plus new flag from the request body. The backend
   * re-renders the affected payout row and we hand it back as JSON so the client can patch a single
   * table row without refetching the whole breakdown.
   *
   * <p>Authorization is asymmetric and mirrors the backend: any mission manager (or higher via the
   * role hierarchy) can set {@code paidOut=true}, but only ADMIN or OFFICER can clear it back to
   * {@code false}. The SpEL guard returns 403 for a plain mission manager attempting to uncheck the
   * box; the JS handler surfaces this as the {@code operation.payout.paid.forbidden} toast.
   *
   * @param id operation id (from the URL)
   * @param request participant key + new {@code paidOut} value
   * @return refreshed paid-out status block on success, or a 403 / 404 / 409 / 500 mirroring the
   *     backend status (a 409 is a same-row toggle race that survived the backend's retry — never a
   *     500, #1111)
   */
  @PostMapping("/{id}/payouts/paid-out")
  @PreAuthorize(
      "hasRole('"
          + Roles.MISSION_MANAGER
          + "') and (#request.paidOut() or hasAnyRole('"
          + Roles.ADMIN
          + "', '"
          + Roles.OFFICER
          + "'))")
  @ResponseBody
  public ResponseEntity<OperationPayoutStatusDto> updatePayoutStatus(
      @PathVariable @NotNull UUID id, @RequestBody OperationPayoutStatusUpdateDto request) {
    try {
      OperationPayoutStatusDto updated =
          backendApiClient.put(
              "/api/v1/operations/" + id + "/payouts/paid-out",
              request,
              OperationPayoutStatusDto.class,
              false);
      return ResponseEntity.ok(updated);
    } catch (BackendServiceException e) {
      log.debug(
          "Update payout paid-out flag failed with status {}: {}",
          e.getStatusCode(),
          e.getMessage());
      if (e.getStatusCode() == 401 || e.getStatusCode() == 403) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
      }
      if (e.getStatusCode() == 404) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
      }
      if (e.getStatusCode() == 409) {
        // A same-row toggle race that survived the backend's bounded retry. Mirror it as a truthful
        // 409 instead of collapsing every non-401/403/404 into a 500 — the toggle is a concurrency
        // conflict, not a server fault, so the client reverts to the current value rather than
        // showing a generic server-error toast (#1111).
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
      }
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    } catch (Exception e) {
      log.error("Update payout paid-out flag failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Server-side Markdown preview for the Verwaltung description editor's "Vorschau" tab. Renders
   * the posted Markdown through the same {@link MarkdownRenderer} (the {@code @markdown} bean) the
   * detail page uses, so the live preview is byte-identical to what is shown on save — raw HTML is
   * escaped and unsafe link/image protocols stripped, making the returned fragment safe for the
   * client to inject via {@code innerHTML}. Authenticated-only (the operation pages are
   * auth-gated).
   *
   * @param request JSON body carrying the raw Markdown under the {@code markdown} key
   * @return the sanitized rendered HTML (text/html)
   */
  @PostMapping(
      value = "/markdown-preview",
      produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
  @PreAuthorize("isAuthenticated()")
  @ResponseBody
  public ResponseEntity<String> markdownPreview(
      @RequestBody java.util.Map<String, String> request) {
    String source = request != null ? request.get("markdown") : null;
    return ResponseEntity.ok()
        .contentType(org.springframework.http.MediaType.TEXT_HTML)
        .body(markdown.render(source));
  }

  /**
   * Deletes an operation. Admin-only — narrower than the class-level read access.
   *
   * @param id operation id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /operations}
   */
  @PostMapping("/{id}/delete")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  public String deleteOperation(
      @PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/operations/" + id, Void.class);
      redirectAttributes.addFlashAttribute("successMessage", "operation.delete.success");
    } catch (Exception e) {
      log.error("Error deleting operation", e);
      redirectAttributes.addFlashAttribute("errorMessage", "operation.delete.error");
    }
    return "redirect:/operations";
  }

  /**
   * AJAX twin of {@link #createOperation} (#576): creates an operation in place. Routed by the
   * {@code X-Requested-With} header (more specific than the classic {@code POST
   * /operations/create}, which stays the no-JavaScript fallback). On success returns {@code 200}
   * with no body — the client closes the create modal and swaps the list fragment; a backend
   * failure is propagated as {@code problem+json} so the client surfaces an inline toast instead of
   * reloading.
   *
   * @param form the bound operation form (JSON body)
   * @return {@code 200} on success, or the propagated backend error
   */
  @PostMapping(value = "/create", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  @ResponseBody
  public ResponseEntity<Object> createOperationAjax(@RequestBody OperationForm form) {
    try {
      backendApiClient.post("/api/v1/operations", form, Void.class);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.debug("Create operation (ajax) failed: {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Create operation (ajax) failed", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX twin of {@link #updateOperation} (#576): saves the operation core-edit form in place.
   * Routed by the {@code X-Requested-With} header (the classic {@code POST /operations/{id}/update}
   * stays the no-JavaScript fallback). The backend {@code PUT} returns the persisted operation
   * <em>in-transaction</em>, so the twin hands its fresh {@code {version, name, status}} straight
   * back — the client writes the bumped optimistic-lock version into the form (a second consecutive
   * save does not 409) and patches the page title in place. Returning the PUT body (rather than a
   * follow-up {@code GET}) is deliberate: a second round-trip could observe a concurrent writer's
   * {@code version+2} (a silent lost update on the next save) or turn an already-committed write
   * into a reported failure if the re-read transiently fails. A backend {@code 409} is propagated
   * as {@code problem+json} preserving the {@code OPTIMISTIC_LOCK} code so the client offers the
   * sanctioned conflict reload.
   *
   * @param id the operation id
   * @param form the bound operation form (JSON body; carries the optimistic-lock version)
   * @return {@code 200} with the fresh version/name/status, or the propagated backend error
   */
  @PostMapping(value = "/{id}/update", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  @ResponseBody
  public ResponseEntity<Object> updateOperationAjax(
      @PathVariable @NotNull UUID id, @RequestBody OperationForm form) {
    try {
      OperationDto updated =
          backendApiClient.put("/api/v1/operations/" + id, form, OperationDto.class);
      java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
      result.put("version", updated.version());
      result.put("name", updated.name());
      result.put("status", updated.status());
      return ResponseEntity.ok(result);
    } catch (BackendServiceException e) {
      log.debug("Update operation (ajax) failed for {}: {}", id, e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Update operation (ajax) failed for {}", id, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * AJAX twin of {@link #deleteOperation} (#576): deletes an operation in place. Routed by the
   * {@code X-Requested-With} header (the classic {@code POST /operations/{id}/delete} stays the
   * no-JavaScript fallback). Admin-only, mirroring the classic handler. On success returns {@code
   * 200}; the list page swaps the results fragment and the detail page navigates back to the list.
   * A backend failure (e.g. the operation still has missions) is propagated as {@code problem+json}
   * so the client keeps the page and surfaces a toast.
   *
   * @param id the operation id
   * @return {@code 200} on success, or the propagated backend error
   */
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @ResponseBody
  public ResponseEntity<Object> deleteOperationAjax(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete("/api/v1/operations/" + id, Void.class);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.debug("Delete operation (ajax) failed for {}: {}", id, e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Delete operation (ajax) failed for {}", id, e);
      return ResponseEntity.internalServerError().build();
    }
  }
}
