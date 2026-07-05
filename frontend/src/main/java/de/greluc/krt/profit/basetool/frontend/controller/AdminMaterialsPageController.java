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

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCreateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialUpdateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller for the admin material catalog ({@code /admin/materials}).
 *
 * <p>The materials list is the heaviest reference-data table in the app — every UEX commodity plus
 * the project-specific job-order materials. The page renders the list once (sorted
 * case-insensitively) and uses a dedicated AJAX endpoint for the field-by-field admin edits so a
 * single category re-assignment doesn't reload the whole table. Category create/delete still goes
 * through full-page redirects because both invalidate the materials cache.
 */
@Controller
@RequestMapping("/admin/materials")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminMaterialsPageController {

  /** Response type for the paginated materials pull ({@code GET /api/v1/materials}). */
  private static final ParameterizedTypeReference<PageResponse<MaterialDto>> MATERIAL_PAGE_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the material-category list ({@code GET /api/v1/material-categories}). */
  private static final ParameterizedTypeReference<List<MaterialCategoryDto>>
      MATERIAL_CATEGORY_LIST_TYPE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Loads the full materials list (size=1000, sorted by name asc) plus the category dropdown
   * source. The "refined materials" model attribute is the same list sorted again
   * case-insensitively — admins assign raw materials to a refined one even when the UEX flag is
   * wrong, so the dropdown intentionally includes every material rather than filtering by {@code
   * isRefined}.
   *
   * @param model Thymeleaf model populated with materials, refined-materials and categories
   * @return the {@code admin/materials} view name
   */
  @GetMapping
  public String listMaterials(Model model) {
    try {
      // includeHidden=true: the admin catalog must show wiki-only commodities imported invisible
      // (§4.3) so they can be reviewed and unhidden. Trading pages call the same endpoint without
      // the flag and get only visible rows.
      PageResponse<MaterialDto> materialsPage =
          backendApiClient.get(
              "/api/v1/materials?size=1000&sort=name,asc&includeHidden=true", MATERIAL_PAGE_TYPE);

      List<MaterialDto> materials = new ArrayList<>();
      if (materialsPage != null && materialsPage.content() != null) {
        materials = new ArrayList<>(materialsPage.content());
      }

      // Provide a list of all materials for assignment to RAW materials
      // (bypass UEX data errors where refined materials are not marked correctly)
      List<MaterialDto> refinedMaterials =
          materials.stream()
              .sorted(
                  Comparator.comparing(
                      m -> m.name() == null ? "" : m.name(), String.CASE_INSENSITIVE_ORDER))
              .toList();
      model.addAttribute("refinedMaterials", refinedMaterials);

      List<MaterialDto> sortedMaterials =
          materials.stream()
              .sorted(
                  Comparator.comparing(
                      m -> m.name() == null ? "" : m.name(), String.CASE_INSENSITIVE_ORDER))
              .toList();
      model.addAttribute("materials", sortedMaterials);

      List<MaterialCategoryDto> categories =
          backendApiClient.get("/api/v1/material-categories", MATERIAL_CATEGORY_LIST_TYPE);
      model.addAttribute("categories", categories);

    } catch (Exception e) {
      log.error("Error loading materials data", e);
      model.addAttribute("error", "error.admin.materials.load");
    }
    return "admin/materials";
  }

  /**
   * Creates a new material category. The backend returns the created record; the page only needs to
   * flash a success/error toast and redirect to refresh the dropdown.
   *
   * @param name category name (must be unique across categories — backend enforces)
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/materials}
   */
  @PostMapping("/categories")
  public String createCategory(@RequestParam String name, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.post(
          "/api/v1/material-categories",
          new MaterialCategoryDto(null, name, null),
          MaterialCategoryDto.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Create category failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.save");
    }
    return "redirect:/admin/materials";
  }

  /**
   * Deletes a material category. The backend's referential-integrity check refuses the call when
   * any material still references the category — the resulting 409 surfaces as a generic delete
   * error toast.
   *
   * @param id category id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/materials}
   */
  @PostMapping("/categories/{id}/delete")
  public String deleteCategory(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete("/api/v1/material-categories/" + id, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (Exception e) {
      log.error("Delete category failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.delete");
    }
    return "redirect:/admin/materials";
  }

  /**
   * In-place (AJAX) twin of {@link #createCategory} — routed here ahead of the classic handler by
   * the {@code X-Requested-With} header so the no-JS form keeps its redirect fallback. Returns the
   * created {@link MaterialCategoryDto} so the page can append it to the category table and to
   * every per-row category dropdown without reloading.
   *
   * @param request JSON body carrying the new category {@code name}
   * @return the created {@link MaterialCategoryDto} on success, {@code 400} when the name is blank,
   *     the relayed backend status on a domain conflict, {@code 500} on an unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/categories", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createCategoryAjax(@RequestBody Map<String, Object> request) {
    Object nameValue = request.get("name");
    if (!(nameValue instanceof String name) || name.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    try {
      MaterialCategoryDto created =
          backendApiClient.post(
              "/api/v1/material-categories",
              new MaterialCategoryDto(null, name, null),
              MaterialCategoryDto.class);
      return ResponseEntity.ok(created);
    } catch (BackendServiceException e) {
      log.debug("Create category (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Create category (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * In-place (AJAX) twin of {@link #deleteCategory}. On success the page removes the category row
   * and its dropdown options in place. The backend refuses the delete with a 409 when any material
   * still references the category; that problem is relayed so the client shows the reason inline
   * (no reload).
   *
   * @param id category id
   * @return {@code 200} on success, the relayed backend status on a referential-integrity conflict,
   *     {@code 500} on an unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/categories/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteCategoryAjax(@PathVariable UUID id) {
    try {
      backendApiClient.delete("/api/v1/material-categories/" + id, Void.class);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.debug("Delete category (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Delete category (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * AJAX endpoint that edits a single field on a material in place. The request's {@code
   * updateType} discriminator selects which field is being touched ({@code CATEGORY}, {@code
   * REFINED}, {@code QUANTITY_TYPE}, {@code MANUAL_RAW}, {@code JOB_ORDER}, {@code VISIBILITY});
   * every other field on the material is preserved from the freshly-fetched current record. A
   * {@code MANUAL_RAW}, {@code JOB_ORDER} or {@code VISIBILITY} change additionally clears the
   * frontend's static-data cache so dependent pages (pickers, lookups) see the change without a
   * full reload — unhiding a reviewed commodity must make it appear in trading flows immediately.
   * Failures collapse to a generic 500 — the AJAX layer in the template renders a toast instead of
   * relying on per-status semantics.
   *
   * @param id material id
   * @param request AJAX patch payload
   * @return the freshly re-fetched material on success, 400 on unknown update type, 500 on backend
   *     failure
   */
  @ResponseBody
  @PutMapping("/{id}/ajax")
  public ResponseEntity<MaterialDto> updateMaterialAjax(
      @PathVariable @NotNull UUID id, @Valid @RequestBody MaterialUpdateAjaxRequest request) {
    try {
      MaterialDto currentMaterial =
          backendApiClient.get("/api/v1/materials/" + id, MaterialDto.class);

      MaterialCategoryDto category = currentMaterial.category();
      MaterialDto refinedMaterial = currentMaterial.refinedMaterial();
      String quantityType = currentMaterial.quantityType();
      Boolean isManualRawMaterial = currentMaterial.isManualRawMaterial();
      Boolean isJobOrder = currentMaterial.isJobOrder();
      Boolean isVisible = currentMaterial.isVisible();

      if ("CATEGORY".equals(request.updateType())) {
        if (request.categoryId() != null) {
          category =
              backendApiClient.get(
                  "/api/v1/material-categories/" + request.categoryId(), MaterialCategoryDto.class);
        } else {
          category = null;
        }
      } else if ("REFINED".equals(request.updateType())) {
        if (request.refinedMaterialId() != null) {
          refinedMaterial =
              backendApiClient.get(
                  "/api/v1/materials/" + request.refinedMaterialId(), MaterialDto.class);
        } else {
          refinedMaterial = null;
        }
      } else if ("QUANTITY_TYPE".equals(request.updateType())) {
        quantityType = request.quantityType();
      } else if ("MANUAL_RAW".equals(request.updateType())) {
        isManualRawMaterial = request.isManualRawMaterial();
      } else if ("JOB_ORDER".equals(request.updateType())) {
        isJobOrder = request.isJobOrder();
      } else if ("VISIBILITY".equals(request.updateType())) {
        isVisible = request.isVisible();
      } else {
        return ResponseEntity.badRequest().build();
      }

      MaterialDto body =
          new MaterialDto(
              id,
              currentMaterial.name(),
              currentMaterial.type(),
              quantityType,
              currentMaterial.description(),
              refinedMaterial,
              category,
              currentMaterial.isIllegal(),
              currentMaterial.isVolatileQt(),
              currentMaterial.isVolatileTime(),
              isManualRawMaterial,
              isJobOrder,
              currentMaterial.isManualEntry(),
              isVisible,
              request.version());

      backendApiClient.put("/api/v1/materials/" + id, body, Void.class);
      if ("JOB_ORDER".equals(request.updateType())
          || "MANUAL_RAW".equals(request.updateType())
          || "VISIBILITY".equals(request.updateType())) {
        backendApiClient.clearStaticDataCache();
      }
      MaterialDto updatedMaterial =
          backendApiClient.get("/api/v1/materials/" + id, MaterialDto.class);
      return ResponseEntity.ok(updatedMaterial);
    } catch (Exception e) {
      log.error("Ajax update material failed", e);
      // In case of OptimisticLocking, backend usually returns 409 Conflict
      return ResponseEntity.status(500).build();
    }
  }

  /**
   * AJAX endpoint that creates a new material manually. Relays the validated request to {@code POST
   * /api/v1/materials}, which stamps {@code isManualEntry=true} server-side. Propagates the
   * backend's HTTP status so the page can distinguish 400 (validation rejected — show problem
   * detail in toast) from 500 (server failure — generic toast). Clears the frontend static-data
   * cache on success so downstream pages (refinery picker, lookups) immediately see the new
   * material.
   *
   * @param request validated create payload
   * @return the persisted material on 200, an empty body with the backend's status on failure
   */
  @ResponseBody
  @PostMapping("/ajax")
  public ResponseEntity<MaterialDto> createMaterialAjax(
      @Valid @RequestBody MaterialCreateAjaxRequest request) {
    try {
      MaterialDto created = backendApiClient.post("/api/v1/materials", request, MaterialDto.class);
      backendApiClient.clearStaticDataCache();
      return ResponseEntity.ok(created);
    } catch (BackendServiceException e) {
      log.warn(
          "Ajax create material rejected by backend: status={}, code={}, detail={}",
          e.getStatusCode(),
          e.getProblemCode(),
          e.getReadableErrorMessage());
      return ResponseEntity.status(e.getStatusCode()).build();
    } catch (Exception e) {
      log.error("Ajax create material failed", e);
      return ResponseEntity.status(500).build();
    }
  }
}
