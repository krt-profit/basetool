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

import static de.greluc.krt.profit.basetool.frontend.support.BackendErrorResponses.relay;

import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging;
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
import de.greluc.krt.profit.basetool.frontend.service.MarkdownRenderer;
import de.greluc.krt.profit.basetool.frontend.service.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.format.annotation.DateTimeFormat;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the operations list ({@code /operations}) and detail ({@code
 * /operations/{id}}) pages, including their AJAX fragments and write twins.
 *
 * <p>The class-level {@code @PreAuthorize("isAuthenticated()")} is the floor (REQ-SEC-052); a
 * method-level gate takes precedence where present.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/operations")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class OperationPageController {

  private final BackendApiClient backendApiClient;
  private final MarkdownRenderer markdown;
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
   * Renders the paginated, filtered operations list. {@code search} matches name and description,
   * {@code showPast} widens the default {@code PLANNED}/{@code ACTIVE} filter to all statuses, and
   * {@code start}/{@code end} filter on the span of the linked missions. {@code fragment=results}
   * returns only the results fragment for an in-place AJAX swap.
   *
   * @param search free-text query, may be {@code null}
   * @param start inclusive lower bound (ISO-8601 instant) on the earliest linked mission's planned
   *     start, may be {@code null}; a non-instant value is a {@code 400}
   * @param end inclusive upper bound (ISO-8601 instant) on the latest linked mission's planned end,
   *     may be {@code null}; a non-instant value is a {@code 400}
   * @param showPast when {@code true}, include COMPLETED and CANCELED
   * @param page zero-based page index
   * @param size page size (default 20)
   * @param fragment when equal to {@code "results"}, render only the results fragment
   * @param model Thymeleaf model populated with the page content and metadata
   * @param principal current OIDC user
   * @return the {@code operations-index} view name, or the results fragment for AJAX
   */
  @NotNull
  @GetMapping
  public String listOperations(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant start,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          Instant end,
      @RequestParam(required = false, defaultValue = "false") boolean showPast,
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "20") Integer size,
      @RequestParam(required = false) String fragment,
      Model model,
      @AuthenticationPrincipal OidcUser principal) {
    StringBuilder uri = new StringBuilder("/api/v1/operations/search?");
    List<Object> uriVariables = new ArrayList<>();
    if (search != null && !search.isBlank()) {
      uri.append("query={query}&");
      uriVariables.add(search);
    }
    if (start != null) {
      uri.append("start={start}&");
      uriVariables.add(start);
    }
    if (end != null) {
      uri.append("end={end}&");
      uriVariables.add(end);
    }
    uri.append("page=").append(page).append("&");
    uri.append("size=").append(size).append("&");
    uri.append("sort=createdAt,desc&");

    if (showPast) {
      uri.append("status=PLANNED&status=ACTIVE&status=COMPLETED&status=CANCELED&");
    } else {
      uri.append("status=PLANNED&status=ACTIVE&");
    }

    try {
      PageResponse<OperationDto> operationsPage =
          uriVariables.isEmpty()
              ? backendApiClient.get(uri.toString(), OPERATION_PAGE_TYPE)
              : backendApiClient.get(uri.toString(), OPERATION_PAGE_TYPE, uriVariables.toArray());
      model.addAttribute("operations", operationsPage.content());
      model.addAttribute("operationsPage", operationsPage);
      model.addAttribute("search", search);
      model.addAttribute("start", start);
      model.addAttribute("end", end);
      model.addAttribute("showPast", showPast);
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
   * Fetches the caller's org-unit memberships for the owner picker of the operation-create modal.
   *
   * @return picker options, or an empty list for anonymous callers or on backend failure; never
   *     {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchCallerMembershipOptions() {
    try {
      List<OrgUnitMembershipOptionDto> options =
          backendApiClient.get("/api/v1/users/me/pickable-org-units", PICKABLE_ORG_UNIT_LIST_TYPE);
      return options != null ? options : List.of();
    } catch (Exception e) {
      log.warn("Failed to fetch pickable org units for operation-create owner-picker", e);
      return List.of();
    }
  }

  /**
   * Renders the operation detail page, loading the operation, its missions, the finance roll-up and
   * the payouts concurrently via {@link ParallelPageLoader}. A backend failure redirects to the
   * list with a flash error. {@code canEdit} is derived from the {@link Authentication}.
   *
   * @param id operation id
   * @param page zero-based page index for the embedded missions table
   * @param size page size for the embedded missions table (default 10)
   * @param fragment when {@code "missions"} only the embedded missions fragment is rendered
   *     (REQ-FE-002); otherwise the full page
   * @param authentication current user's authentication (used for {@code canEdit})
   * @param model Thymeleaf model populated with operation, missions, finance and payouts
   * @return the {@code operation-detail} view name, its {@code missions} fragment, or a redirect on
   *     backend failure of the full-page load
   */
  @NotNull
  @GetMapping("/{id}")
  public String operationDetails(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false, defaultValue = "0") Integer page,
      @RequestParam(required = false, defaultValue = "10") Integer size,
      @RequestParam(required = false) String fragment,
      Authentication authentication,
      Model model) {
    if (fragment != null) {
      return operationFragment(id, page, size, fragment, authentication, model);
    }
    try {
      loadFullModel(id, page, size, authentication, model);
    } catch (Exception e) {
      log.error("Error loading operation details", e);
      model.addAttribute("error", "error.operation.load");
      return "redirect:/operations";
    }
    return "operation-detail";
  }

  /**
   * Renders one operation-detail section fragment for an AJAX swap: the missions pager (REQ-FE-002)
   * or the overview, payout and finance peer-refresh targets (REQ-FE-015). Each case loads only the
   * data its fragment needs; an unknown fragment or a backend failure renders an inline error.
   *
   * @param id operation id
   * @param page zero-based page index for the embedded missions table
   * @param size page size for the embedded missions table
   * @param fragment the requested fragment name ({@code overview}/{@code payout}/{@code
   *     finance}/{@code missions})
   * @param authentication current user's authentication (used for {@code canEdit} / {@code
   *     canUnsetPaidOut})
   * @param model Thymeleaf model populated with the fragment's data
   * @return the {@code operation-detail :: <fragment>} view, or {@code :: fragmentError} on an
   *     unknown fragment or a backend failure
   */
  @NotNull
  private String operationFragment(
      UUID id,
      Integer page,
      Integer size,
      String fragment,
      Authentication authentication,
      Model model) {
    if ("missions".equals(fragment)) {
      return missionsFragment(id, page, size, model);
    }
    try {
      return switch (fragment.toLowerCase(java.util.Locale.ROOT)) {
        case "overview" -> {
          loadFullModel(id, page, size, authentication, model);
          yield "operation-detail :: overviewSection";
        }
        case "payout" -> {
          loadPayoutModel(id, authentication, model);
          yield "operation-detail :: payoutSection";
        }
        case "finance" -> {
          loadFinanceModel(id, page, size, model);
          yield "operation-detail :: financeSection";
        }
        default -> "operation-detail :: fragmentError";
      };
    } catch (Exception e) {
      log.error("Error loading operation fragment '{}' for {}", fragment, id, e);
      return "operation-detail :: fragmentError";
    }
  }

  /**
   * Loads the full operation-detail model (the overview fragment needs all of it): the four
   * independent reads fanned out on virtual threads via {@link ParallelPageLoader} plus the derived
   * {@code operationMaxMissionResult}, {@code canEdit} and {@code canUnsetPaidOut}. Propagates a
   * backend failure to the caller (the full page redirects, the overview fragment shows its inline
   * error).
   *
   * @param id operation id
   * @param page zero-based page index for the embedded missions table
   * @param size page size for the embedded missions table
   * @param authentication current user's authentication
   * @param model Thymeleaf model to populate
   */
  private void loadFullModel(
      UUID id, Integer page, Integer size, Authentication authentication, Model model) {
    CompletableFuture<OperationDto> operationF =
        parallelPageLoader.loadAsync(
            () -> backendApiClient.get("/api/v1/operations/" + id, OperationDto.class));
    CompletableFuture<PageResponse<MissionListDto>> missionsF =
        parallelPageLoader.loadAsync(() -> fetchMissionsPage(id, page, size));
    CompletableFuture<OperationFinanceSummaryDto> financeF =
        parallelPageLoader.loadAsync(
            () ->
                backendApiClient.get(
                    "/api/v1/operations/" + id + "/finance-summary",
                    OperationFinanceSummaryDto.class));
    CompletableFuture<OperationPayoutSummaryDto> payoutsF =
        parallelPageLoader.loadAsync(
            () ->
                backendApiClient.get(
                    "/api/v1/operations/" + id + "/payouts", OperationPayoutSummaryDto.class));
    CompletableFuture.allOf(operationF, missionsF, financeF, payoutsF).join();

    model.addAttribute("operation", operationF.join());

    PageResponse<MissionListDto> missionsPage = missionsF.join();
    model.addAttribute("missions", missionsPage.content());
    model.addAttribute("missionsPage", missionsPage);

    OperationFinanceSummaryDto operationFinance = financeF.join();
    model.addAttribute("operationFinance", operationFinance);

    OperationPayoutSummaryDto payoutSummary = payoutsF.join();
    model.addAttribute("operationPayouts", payoutSummary.payouts());
    model.addAttribute("operationDonationTotal", payoutSummary.totalDonations());

    BigDecimal maxMissionResult =
        operationFinance.missions() == null
            ? BigDecimal.ZERO
            : operationFinance.missions().stream()
                .map(OperationMissionFinanceDto::totalSum)
                .filter(Objects::nonNull)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    model.addAttribute("operationMaxMissionResult", maxMissionResult);

    model.addAttribute("canEdit", hasMissionManagerRole(authentication));
    model.addAttribute("canUnsetPaidOut", hasOfficerOrAdminRole(authentication));
  }

  /**
   * Loads only the model the {@code payoutSection} fragment needs (fragment-gating): the operation
   * (for the preliminary-values flag and the checkbox operation id), the payout summary (rows +
   * donation total) and the two role flags. Deliberately issues neither the finance-summary nor the
   * missions read.
   *
   * @param id operation id
   * @param authentication current user's authentication
   * @param model Thymeleaf model to populate
   */
  private void loadPayoutModel(UUID id, Authentication authentication, @NotNull Model model) {
    model.addAttribute(
        "operation", backendApiClient.get("/api/v1/operations/" + id, OperationDto.class));
    OperationPayoutSummaryDto payoutSummary =
        backendApiClient.get(
            "/api/v1/operations/" + id + "/payouts", OperationPayoutSummaryDto.class);
    model.addAttribute("operationPayouts", payoutSummary.payouts());
    model.addAttribute("operationDonationTotal", payoutSummary.totalDonations());
    model.addAttribute("canEdit", hasMissionManagerRole(authentication));
    model.addAttribute("canUnsetPaidOut", hasOfficerOrAdminRole(authentication));
  }

  /**
   * Loads only the model the {@code financeSection} fragment needs (fragment-gating): the finance
   * roll-up, the donation total (from the payout summary) and the missions page (its total feeds
   * the sum-strip's per-operation count). Deliberately does not issue the operation-detail read.
   *
   * @param id operation id
   * @param page zero-based page index for the embedded missions table
   * @param size page size for the embedded missions table
   * @param model Thymeleaf model to populate
   */
  private void loadFinanceModel(UUID id, Integer page, Integer size, @NotNull Model model) {
    model.addAttribute(
        "operationFinance",
        backendApiClient.get(
            "/api/v1/operations/" + id + "/finance-summary", OperationFinanceSummaryDto.class));
    OperationPayoutSummaryDto payoutSummary =
        backendApiClient.get(
            "/api/v1/operations/" + id + "/payouts", OperationPayoutSummaryDto.class);
    model.addAttribute("operationDonationTotal", payoutSummary.totalDonations());
    model.addAttribute("missionsPage", fetchMissionsPage(id, page, size));
  }

  /**
   * Renders the embedded missions sub-table for an AJAX pager swap (REQ-FE-002), loading only the
   * operation and the requested missions page. A backend failure yields an empty list, never a
   * redirect.
   *
   * @param id operation id
   * @param page zero-based page index for the embedded missions table
   * @param size page size for the embedded missions table
   * @param model Thymeleaf model populated with {@code operation}, {@code missions} and {@code
   *     missionsPage}
   * @return the {@code operation-detail :: missions} fragment view
   */
  @NotNull
  private String missionsFragment(UUID id, Integer page, Integer size, Model model) {
    try {
      model.addAttribute(
          "operation", backendApiClient.get("/api/v1/operations/" + id, OperationDto.class));
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
        MISSION_PAGE_TYPE);
  }

  /**
   * Renders one mission's finance breakdown fragment, loaded when the operation-detail finance
   * panel expands that mission. The backend checks visibility and that the mission belongs to the
   * operation; a backend failure renders an inline error.
   *
   * @param id operation id
   * @param missionId the mission whose breakdown to load (must belong to the operation)
   * @param model Thymeleaf model populated with {@code financeDetail} (or {@code
   *     financeDetailError})
   * @return the {@code operation-detail :: financeDetail} fragment view
   */
  @NotNull
  @GetMapping("/{id}/finance/{missionId}")
  public String operationMissionFinance(
      @PathVariable @NotNull UUID id, @PathVariable @NotNull UUID missionId, Model model) {
    try {
      MissionFinanceSummaryDto detail =
          backendApiClient.get(
              "/api/v1/operations/" + id + "/finances/" + missionId,
              MissionFinanceSummaryDto.class);
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
  @NotNull
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
  @NotNull
  @PostMapping("/{id}/update")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  public String updateOperation(
      @PathVariable @NotNull UUID id,
      @ModelAttribute OperationForm form,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.put("/api/v1/operations/" + id, form, Void.class);
      redirectAttributes.addFlashAttribute("successMessage", "operation.update.success");
    } catch (BackendServiceException e) {
      if (e.getStatusCode() == 409) {
        log.warn("Optimistic locking failure updating operation: {}", id);
        redirectAttributes.addFlashAttribute("errorMessage", "error.optimistic.locking");
      } else {
        BackendErrorLogging.warn(log, "updateOperation", id, e);
        redirectAttributes.addFlashAttribute("errorMessage", "operation.update.error");
      }
    } catch (Exception e) {
      log.error("Error updating operation", e);
      redirectAttributes.addFlashAttribute("errorMessage", "operation.update.error");
    }
    return "redirect:/operations";
  }

  /**
   * AJAX endpoint behind the per-row "Bezahlt" checkbox, proxying to {@code PUT
   * /api/v1/operations/{id}/payouts/paid-out}. Any mission manager may set {@code paidOut=true};
   * only ADMIN or OFFICER may clear it.
   *
   * @param id operation id (from the URL)
   * @param request participant key + new {@code paidOut} value
   * @return the refreshed paid-out status block, or a 403 / 404 / 409 / 500 mirroring the backend
   *     status
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
              OperationPayoutStatusDto.class);
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
        return ResponseEntity.status(HttpStatus.CONFLICT).build();
      }
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    } catch (Exception e) {
      log.error("Update payout paid-out flag failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * Renders a Markdown preview for the description editor's "Vorschau" tab through the same
   * sanitising {@link MarkdownRenderer} the detail page uses, so the result is safe to inject via
   * {@code innerHTML}.
   *
   * @param request JSON body carrying the raw Markdown under the {@code markdown} key
   * @return the sanitized rendered HTML (text/html)
   */
  @PostMapping(
      value = "/markdown-preview",
      produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
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
  @NotNull
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
   * AJAX twin of {@link #createOperation}, selected by the {@code X-Requested-With} header.
   *
   * @param form the bound operation form (JSON body)
   * @return {@code 200} with no body on success, or the backend error as {@code problem+json}
   */
  @PostMapping(value = "/create", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.MISSION_MANAGER + "')")
  @ResponseBody
  public ResponseEntity<Object> createOperationAjax(@RequestBody OperationForm form) {
    return relay(
        log,
        "create operation (ajax)",
        () -> {
          backendApiClient.post("/api/v1/operations", form, Void.class);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * AJAX twin of {@link #updateOperation}, selected by the {@code X-Requested-With} header. Returns
   * the fresh {@code {version, name, status}} from the backend's {@code PUT} response so the client
   * can update the form's version in place; a {@code 409} keeps its {@code OPTIMISTIC_LOCK} code.
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
    return relay(
        log,
        "update operation (ajax) for " + id,
        () -> {
          OperationDto updated =
              backendApiClient.put("/api/v1/operations/" + id, form, OperationDto.class);
          java.util.Map<String, Object> result = new java.util.LinkedHashMap<>();
          result.put("version", updated.version());
          result.put("name", updated.name());
          result.put("status", updated.status());
          return ResponseEntity.ok(result);
        });
  }

  /**
   * AJAX twin of {@link #deleteOperation}, selected by the {@code X-Requested-With} header;
   * admin-only.
   *
   * @param id the operation id
   * @return {@code 200} on success, or the backend error as {@code problem+json}
   */
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  @PreAuthorize("hasRole('" + Roles.ADMIN + "')")
  @ResponseBody
  public ResponseEntity<Object> deleteOperationAjax(@PathVariable @NotNull UUID id) {
    return relay(
        log,
        "delete operation (ajax) for " + id,
        () -> {
          backendApiClient.delete("/api/v1/operations/" + id, Void.class);
          return ResponseEntity.ok().build();
        });
  }
}
