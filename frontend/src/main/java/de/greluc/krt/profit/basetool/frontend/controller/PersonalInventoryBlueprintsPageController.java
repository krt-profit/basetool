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

import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintCraftabilityDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.BlueprintProductDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchCreateRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBatchResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintBulkDeleteResultDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintRecipeDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.StringNormalization;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Page controller for the Blueprints sub-page of the personal inventory area (#327, Phase 5).
 * Renders the owned-blueprint list and proxies the type-ahead product search, the multi-select
 * batch add, and the per-row note edit / remove to the backend via {@link BackendApiClient}.
 *
 * <p>The owner is always the authenticated caller — derived from the bearer-relayed JWT on the
 * backend side, never accepted from the request — so a user can only ever see or mutate their own
 * blueprints. Edit / remove use a full redirect so the optimistic-lock {@code version} re-syncs on
 * every related row without per-node DOM patching.
 */
@Controller
@RequestMapping("/personal-inventory/blueprints")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Slf4j
public class PersonalInventoryBlueprintsPageController {

  /**
   * Per-request page size used while pulling the caller's <em>complete</em> owned-blueprint set
   * page by page (issue #823). One row per product, so a generous chunk keeps the common case to a
   * single round-trip while still bounding each backend query.
   */
  private static final int FETCH_PAGE_SIZE = 500;

  /**
   * Hard upper bound on the number of pages assembled into the full owned set, a safety net against
   * an unbounded loop should the backend ever report an inconsistent page count. At {@link
   * #FETCH_PAGE_SIZE} per page this matches the backend's own {@code size} clamp ({@code 100000}).
   */
  private static final int MAX_PAGES = 200;

  /**
   * Response type for the product type-ahead search ({@code /api/v1/blueprints/products/search})
   * backing the multi-select add bar.
   */
  private static final ParameterizedTypeReference<List<BlueprintProductDto>>
      BLUEPRINT_PRODUCT_LIST = new ParameterizedTypeReference<>() {};

  /**
   * Response type for the per-blueprint craftability annotation ({@code
   * /api/v1/personal-blueprints/craftability}, #781).
   */
  private static final ParameterizedTypeReference<List<BlueprintCraftabilityDto>>
      BLUEPRINT_CRAFTABILITY_LIST = new ParameterizedTypeReference<>() {};

  /**
   * Response type for one page of the caller's owned blueprints ({@code
   * /api/v1/personal-blueprints}).
   */
  private static final ParameterizedTypeReference<PageResponse<PersonalBlueprintDto>>
      PERSONAL_BLUEPRINT_PAGE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the owned-blueprint list with the multi-select add bar and the edit / remove modals.
   *
   * @param q optional case-insensitive product-name filter, echoed into the search input
   * @param fragment when {@code "list"}, only the collection card fragment is rendered for an
   *     in-place AJAX swap after a batch add / import / remove (epic #571 / REQ-FE-005); otherwise
   *     the full page
   * @param model Thymeleaf model populated with the blueprint list and the filter query
   * @return the {@code personal-inventory-blueprints} view name, or its {@code blueprintList}
   *     fragment selector
   */
  @GetMapping
  public String view(
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String fragment,
      Model model) {
    model.addAttribute("filterQuery", q == null ? "" : q);
    model.addAttribute("blueprints", fetchAllOwned(q));
    if (fragment != null && "list".equalsIgnoreCase(fragment)) {
      return "personal-inventory-blueprints :: blueprintList";
    }
    return "personal-inventory-blueprints";
  }

  /**
   * Type-ahead proxy backing the multi-select add. Relays the query to the backend product search
   * and returns the hits (each carrying an owned-by-caller flag). Proxied through the frontend so
   * the same Spring Security session and CSRF policy apply; failures collapse to an empty list so
   * the type-ahead never shows a stack trace.
   *
   * @param q optional case-insensitive product-name substring
   * @param limit optional result cap; defaults to 25 and is clamped to {@code [1, 200]}
   * @return the matching products, or an empty list on any backend failure
   */
  @GetMapping("/search")
  @ResponseBody
  public List<BlueprintProductDto> search(
      @RequestParam(required = false) String q, @RequestParam(required = false) Integer limit) {
    try {
      String query = q == null ? "" : q;
      int effectiveLimit = limit == null ? 25 : Math.min(200, Math.max(1, limit));
      String uri =
          "/api/v1/blueprints/products/search?q="
              + URLEncoder.encode(query, StandardCharsets.UTF_8)
              + "&limit="
              + effectiveLimit;
      List<BlueprintProductDto> result = backendApiClient.get(uri, BLUEPRINT_PRODUCT_LIST);
      return result == null ? Collections.emptyList() : result;
    } catch (Exception e) {
      log.warn("Blueprint product type-ahead failed for query='{}': {}", q, e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * Recipe proxy backing the expandable "Zutaten &amp; Stats" detail of an owned blueprint. Relays
   * to the backend owned-blueprint recipe endpoint and returns the ingredients + per-quality stat
   * modifiers as JSON; failures collapse to an empty recipe so the detail panel renders a graceful
   * "no data" state rather than a stack trace.
   *
   * @param id owned-blueprint entry id
   * @return the recipe view, or an empty recipe on any backend failure
   */
  @GetMapping("/{id}/recipe")
  @ResponseBody
  public PersonalBlueprintRecipeDto recipe(@PathVariable @NotNull UUID id) {
    try {
      PersonalBlueprintRecipeDto result =
          backendApiClient.get(
              "/api/v1/personal-blueprints/" + id + "/recipe", PersonalBlueprintRecipeDto.class);
      return result == null ? emptyRecipe() : result;
    } catch (Exception e) {
      log.warn("Failed to fetch blueprint recipe {}: {}", id, e.getMessage());
      return emptyRecipe();
    }
  }

  /**
   * Craftability proxy backing the blueprint view's craftability annotation (#781). Relays to the
   * backend craftability endpoint and returns, per owned blueprint, the craftable counts, effective
   * quality and missing materials as JSON. The frontend requests it once with {@code
   * includeRefinery=true} so both the inventory-only and refinery-included figures are present and
   * the toggle switches client-side. Failures collapse to an empty list so the list/detail simply
   * render without craftability badges rather than a stack trace.
   *
   * @param includeRefinery whether the backend folds the caller's open refinery yield into the
   *     {@code *WithRefinery} figures
   * @return the per-blueprint craftability, or an empty list on any backend failure
   */
  @GetMapping("/craftability")
  @ResponseBody
  public List<BlueprintCraftabilityDto> craftability(
      @RequestParam(required = false, defaultValue = "false") boolean includeRefinery) {
    try {
      List<BlueprintCraftabilityDto> result =
          backendApiClient.get(
              "/api/v1/personal-blueprints/craftability?includeRefinery=" + includeRefinery,
              BLUEPRINT_CRAFTABILITY_LIST);
      return result == null ? Collections.emptyList() : result;
    } catch (Exception e) {
      log.warn("Failed to fetch blueprint craftability: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * Builds an empty recipe view (no product name, no variants, no groups/ingredients) returned when
   * the backend recipe call fails or yields nothing, so the client always receives a well-formed
   * payload to render the "no data" state.
   *
   * @return an empty recipe DTO
   */
  private static PersonalBlueprintRecipeDto emptyRecipe() {
    return new PersonalBlueprintRecipeDto(null, 0, List.of(), List.of());
  }

  /**
   * Multi-select batch add. Relays the staged product keys to the backend batch endpoint and
   * returns the summary so the client can toast added / skipped counts and reload the list. AJAX
   * (CSRF-protected) rather than a form post so the user can keep searching and staging before
   * committing.
   *
   * @param productKeys the normalized product keys staged by the user
   * @return the batch result (added / skipped counts), or a zeroed result on backend failure
   */
  @PostMapping("/add-selected")
  @ResponseBody
  public PersonalBlueprintBatchResultDto addSelected(@RequestBody List<String> productKeys) {
    List<String> keys = productKeys == null ? List.of() : productKeys;
    if (keys.isEmpty()) {
      return new PersonalBlueprintBatchResultDto(0, 0, 0);
    }
    try {
      PersonalBlueprintBatchResultDto result =
          backendApiClient.post(
              "/api/v1/personal-blueprints/batch",
              new PersonalBlueprintBatchCreateRequest(keys),
              PersonalBlueprintBatchResultDto.class);
      return result == null ? new PersonalBlueprintBatchResultDto(0, 0, 0) : result;
    } catch (Exception e) {
      log.error("Batch blueprint add failed for {} key(s)", keys.size(), e);
      return new PersonalBlueprintBatchResultDto(0, 0, 0);
    }
  }

  /**
   * Updates the note of an owned blueprint, preserving the acquisition timestamp. A 409 surfaces as
   * the dedicated optimistic-lock toast.
   *
   * @param id blueprint entry id
   * @param note the new note text
   * @param acquiredAt the preserved acquisition instant (ISO-8601), or blank for none
   * @param version the last seen optimistic-lock version
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the Blueprints page
   */
  @PostMapping("/{id}/update-note")
  public String updateNote(
      @PathVariable @NotNull UUID id,
      @RequestParam(required = false) String note,
      @RequestParam(required = false) String acquiredAt,
      @RequestParam Long version,
      RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.put(
          "/api/v1/personal-blueprints/" + id,
          new PersonalBlueprintUpdateRequest(
              parseInstantOrNull(acquiredAt), StringNormalization.blankToNull(note), version),
          PersonalBlueprintDto.class);
      redirectAttributes.addFlashAttribute(
          "successToast", "personalInventory.blueprints.toast.noteUpdated");
    } catch (Exception e) {
      log.error("Failed to update blueprint note {}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.blueprints.error.update"));
    }
    return "redirect:/personal-inventory/blueprints";
  }

  /**
   * Removes an owned blueprint from the caller's set.
   *
   * @param id blueprint entry id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the Blueprints page
   */
  @PostMapping("/{id}/delete")
  public String delete(@PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/personal-blueprints/" + id, Void.class);
      redirectAttributes.addFlashAttribute(
          "successToast", "personalInventory.blueprints.toast.removed");
    } catch (Exception e) {
      log.error("Failed to remove blueprint {}", id, e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.blueprints.error.remove"));
    }
    return "redirect:/personal-inventory/blueprints";
  }

  /**
   * Clears the caller's entire removable owned-blueprint set — the "delete all my blueprints"
   * action (REQ-INV-023). Auto-granted defaults (REQ-INV-016) are preserved by the backend. No-JS
   * fallback: flashes a countless success toast and redirects (the AJAX twin below shows the
   * removed count).
   *
   * @param redirectAttributes flash attributes carrier
   * @return redirect to the Blueprints page
   */
  @PostMapping("/delete-all")
  public String deleteAll(RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(
          "/api/v1/personal-blueprints", PersonalBlueprintBulkDeleteResultDto.class);
      redirectAttributes.addFlashAttribute(
          "successToast", "personalInventory.blueprints.toast.removedAll");
    } catch (Exception e) {
      log.error("Failed to clear all owned blueprints", e);
      redirectAttributes.addFlashAttribute(
          "errorToast", classifyError(e, "personalInventory.blueprints.error.removeAll"));
    }
    return "redirect:/personal-inventory/blueprints";
  }

  // ----------------------------------------------------- AJAX twins (epic #571 / REQ-FE-005)

  /**
   * Header-gated AJAX twin of {@link #updateNote}: updates an owned blueprint's note and returns
   * the fresh {@link PersonalBlueprintDto} so {@code personal-inventory-blueprints.html} patches
   * the master-row's note + version and the detail pane in place (keeping the current selection and
   * the already-loaded recipe) instead of the classic POST→redirect reload. The optimistic-lock
   * {@code version} travels in the JSON payload; a concurrent edit surfaces as a {@code 409} {@code
   * problem+json} carrying {@code OPTIMISTIC_LOCK}. The classic handler stays the no-JS fallback.
   *
   * @param id blueprint entry id
   * @param request the note payload submitted as JSON ({@code acquiredAt}, {@code note}, {@code
   *     version})
   * @return {@code 200} with the persisted blueprint on success, or the relayed backend {@code
   *     problem+json}
   */
  @PostMapping(value = "/{id}/update-note", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> updateNoteAjax(
      @PathVariable @NotNull UUID id, @RequestBody PersonalBlueprintUpdateRequest request) {
    try {
      PersonalBlueprintDto dto =
          backendApiClient.put(
              "/api/v1/personal-blueprints/" + id,
              new PersonalBlueprintUpdateRequest(
                  request.acquiredAt(),
                  StringNormalization.blankToNull(request.note()),
                  request.version()),
              PersonalBlueprintDto.class);
      return ResponseEntity.ok(dto);
    } catch (BackendServiceException e) {
      log.debug("Failed to update blueprint note {} (ajax): {}", id, e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to update blueprint note {} (ajax)", id, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #delete}: removes an owned blueprint and returns {@code 204}
   * so the page re-renders the collection card via {@code GET
   * /personal-inventory/blueprints?fragment= list} in place (resyncing the counts and the empty
   * state) instead of the classic POST→redirect reload. A backend failure is relayed as {@code
   * problem+json}.
   *
   * @param id blueprint entry id
   * @return {@code 204} on success, or the relayed backend {@code problem+json}
   */
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> deleteAjax(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete("/api/v1/personal-blueprints/" + id, Void.class);
      return ResponseEntity.noContent().build();
    } catch (BackendServiceException e) {
      log.debug("Failed to remove blueprint {} (ajax): {}", id, e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to remove blueprint {} (ajax)", id, e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Header-gated AJAX twin of {@link #deleteAll}: clears the caller's removable owned blueprints
   * (REQ-INV-023) and returns the removed count as JSON so {@code
   * personal-inventory-blueprints.html} re-renders the collection card in place (via {@code GET
   * /personal-inventory/blueprints?fragment=list}) and toasts "{n} Blueprints entfernt" — no
   * full-page reload (REQ-FE-001/005). A backend failure is relayed as {@code problem+json}.
   *
   * @return {@code 200} with the removed count on success, or the relayed backend {@code
   *     problem+json}
   */
  @PostMapping(value = "/delete-all", headers = "X-Requested-With=XMLHttpRequest")
  @ResponseBody
  public ResponseEntity<Object> deleteAllAjax() {
    try {
      PersonalBlueprintBulkDeleteResultDto result =
          backendApiClient.delete(
              "/api/v1/personal-blueprints", PersonalBlueprintBulkDeleteResultDto.class);
      return ResponseEntity.ok(
          result == null ? new PersonalBlueprintBulkDeleteResultDto(0) : result);
    } catch (BackendServiceException e) {
      log.debug("Failed to clear all owned blueprints (ajax): {}", e.getMessage());
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Failed to clear all owned blueprints (ajax)", e);
      return ResponseEntity.internalServerError().build();
    }
  }

  /**
   * Fetches the caller's <em>complete</em> owned-blueprint set — every page, not a capped first
   * page — so the "Meine Blueprints" list shows all blueprints, the facts subtitle and the tab
   * count are accurate, and the client-side filter searches the whole set (issue #823). Pages are
   * pulled in {@link #FETCH_PAGE_SIZE}-sized chunks and concatenated until the last page. A backend
   * failure collapses to whatever was gathered so far (empty on the first call) rather than a 500.
   * Heavy per-row work (recipe + craftability) stays lazy / bulk-async on the client, so loading
   * the full list never blocks the page render.
   *
   * @param q optional case-insensitive product-name filter applied server-side
   * @return the caller's owned blueprints across all pages, alphabetically by product name
   */
  @NotNull
  private List<PersonalBlueprintDto> fetchAllOwned(String q) {
    List<PersonalBlueprintDto> all = new ArrayList<>();
    try {
      int page = 0;
      while (page < MAX_PAGES) {
        PageResponse<PersonalBlueprintDto> response = fetchOwnedPage(q, page);
        if (response == null || response.content() == null || response.content().isEmpty()) {
          break;
        }
        all.addAll(response.content());
        if (page + 1 >= response.totalPages()) {
          break;
        }
        page++;
      }
    } catch (Exception e) {
      log.error("Failed to fetch owned blueprints", e);
    }
    return all;
  }

  /**
   * Fetches one page of the caller's owned blueprints (the backend derives the owner from the
   * relayed JWT, never the request), sorted alphabetically by product name and optionally filtered
   * by a case-insensitive product-name substring.
   *
   * @param q optional case-insensitive product-name filter
   * @param page zero-based page index
   * @return the requested page of owned blueprints
   */
  private PageResponse<PersonalBlueprintDto> fetchOwnedPage(String q, int page) {
    StringBuilder uri =
        new StringBuilder("/api/v1/personal-blueprints?size=")
            .append(FETCH_PAGE_SIZE)
            .append("&page=")
            .append(page)
            .append("&sort=productName,asc");
    if (q != null && !q.isBlank()) {
      uri.append("&q=").append(URLEncoder.encode(q, StandardCharsets.UTF_8));
    }
    return backendApiClient.get(uri.toString(), PERSONAL_BLUEPRINT_PAGE);
  }

  /**
   * Parses an ISO-8601 instant string, tolerating a blank value (returns {@code null}). Invalid
   * input is also treated as {@code null} so a malformed hidden field never blocks a note edit.
   *
   * @param iso the ISO-8601 instant string, or blank
   * @return the parsed instant, or {@code null}
   */
  private static Instant parseInstantOrNull(String iso) {
    if (iso == null || iso.isBlank()) {
      return null;
    }
    try {
      return Instant.parse(iso.trim());
    } catch (Exception e) {
      log.debug("Ignoring unparseable acquiredAt '{}'", iso);
      return null;
    }
  }

  /**
   * Maps backend exceptions to specific toast keys, distinguishing only the 409 optimistic-lock
   * conflict from the supplied generic key.
   *
   * @param e the caught exception
   * @param defaultKey the fallback toast key
   * @return the resolved toast key
   */
  private String classifyError(Exception e, String defaultKey) {
    if (e instanceof de.greluc.krt.profit.basetool.frontend.service.BackendServiceException bse
        && bse.getStatusCode() == 409) {
      return "personalInventory.blueprints.error.conflict";
    }
    return defaultKey;
  }
}
