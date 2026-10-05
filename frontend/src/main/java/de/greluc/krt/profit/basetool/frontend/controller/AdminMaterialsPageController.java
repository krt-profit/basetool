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

import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.config.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.logging.BackendErrorLogging;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCategoryDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialCreateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialUpdateAjaxRequest;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.service.CacheDomain;
import de.greluc.krt.profit.basetool.frontend.service.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages;
import de.greluc.krt.profit.basetool.frontend.support.CatalogPages.CompleteCatalog;
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
 * Controller for the admin material catalog ({@code /admin/materials}): renders the full material
 * list and edits single fields in place via AJAX.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/admin/materials")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminMaterialsPageController {

  /** Reads and writes the materials and their categories. */
  private final CatalogueBackendClient catalogueClient;

  /** Evicts the material catalogue after a write. */
  private final CatalogueCacheEviction cacheEviction;

  /**
   * Loads the complete materials list and the category dropdown source (REQ-ADMIN-001). The refined
   * materials dropdown lists every material, not only those flagged refined. Hitting the page-walk
   * cap sets {@code catalogTruncated} (REQ-ADMIN-002).
   *
   * @param model Thymeleaf model populated with materials, refined materials and categories
   * @return the {@code admin/materials} view name
   */
  @NotNull
  @GetMapping
  public String listMaterials(Model model) {
    try {
      CompleteCatalog<MaterialDto> materialsCatalog =
          CatalogPages.fetchAll(catalogueClient::materialPage);
      List<MaterialDto> materials = new ArrayList<>(materialsCatalog.items());
      model.addAttribute("catalogTruncated", materialsCatalog.truncated());

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

      List<MaterialCategoryDto> categories = catalogueClient.materialCategories();
      model.addAttribute("categories", categories);

    } catch (BackendServiceException e) {
      log.debug("Error loading materials data", e);
      model.addAttribute("error", "error.admin.materials.load");
    } catch (Exception e) {
      log.error("Error loading materials data", e);
      model.addAttribute("error", "error.admin.materials.load");
    }
    return "admin/materials";
  }

  /**
   * Creates a material category and redirects back with a flash toast.
   *
   * @param name category name, unique across categories
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/materials}
   */
  @NotNull
  @PostMapping("/categories")
  public String createCategory(@RequestParam String name, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.createMaterialCategory(new MaterialCategoryDto(null, name, null));
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "POST /api/v1/material-categories", e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.save");
    } catch (Exception e) {
      log.error("Create category failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.save");
    }
    return "redirect:/admin/materials";
  }

  /**
   * Deletes a material category; the backend refuses with 409 while a material references it.
   *
   * @param id category id
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/materials}
   */
  @NotNull
  @PostMapping("/categories/{id}/delete")
  public String deleteCategory(@PathVariable UUID id, RedirectAttributes redirectAttributes) {
    try {
      catalogueClient.deleteMaterialCategory(id);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "DELETE /api/v1/material-categories", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.delete");
    } catch (Exception e) {
      log.error("Delete category failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.delete");
    }
    return "redirect:/admin/materials";
  }

  /**
   * In-place twin of {@link #createCategory}: returns the created {@link MaterialCategoryDto} so
   * the page can add it to the table and every category dropdown.
   *
   * @param request body carrying the new category {@code name}
   * @return the created {@link MaterialCategoryDto}, {@code 400} for a blank name, the relayed
   *     backend status on a conflict, or {@code 500} on an unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/categories", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createCategoryAjax(
      @NotNull @RequestBody Map<String, Object> request) {
    Object nameValue = request.get("name");
    if (!(nameValue instanceof String name) || name.isBlank()) {
      return ResponseEntity.badRequest().build();
    }
    return relay(
        log,
        "create category (ajax)",
        () -> {
          MaterialCategoryDto created =
              catalogueClient.createMaterialCategory(new MaterialCategoryDto(null, name, null));
          return ResponseEntity.ok(created);
        });
  }

  /**
   * In-place twin of {@link #deleteCategory}; a 409 for a still-referenced category is relayed so
   * the client shows the reason.
   *
   * @param id category id
   * @return {@code 200} on success, the relayed backend status on a conflict, or {@code 500} on an
   *     unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/categories/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteCategoryAjax(@PathVariable UUID id) {
    return relay(
        log,
        "delete category (ajax)",
        () -> {
          catalogueClient.deleteMaterialCategory(id);
          return ResponseEntity.ok().build();
        });
  }

  /**
   * Edits one field of a material in place, selected by {@code updateType} ({@code CATEGORY},
   * {@code REFINED}, {@code QUANTITY_TYPE}, {@code MANUAL_RAW}, {@code JOB_ORDER}, {@code
   * VISIBILITY}); other fields are copied from the current record. Evicts the {@code
   * CacheDomain.MATERIAL} cache on success.
   *
   * @param id material id
   * @param request AJAX patch payload
   * @return the re-fetched material, 400 on an unknown update type, 500 on backend failure
   */
  @ResponseBody
  @PutMapping("/{id}/ajax")
  public ResponseEntity<MaterialDto> updateMaterialAjax(
      @PathVariable @NotNull UUID id, @Valid @RequestBody MaterialUpdateAjaxRequest request) {
    try {
      MaterialDto currentMaterial = catalogueClient.material(id);

      MaterialCategoryDto category = currentMaterial.category();
      MaterialDto refinedMaterial = currentMaterial.refinedMaterial();
      String quantityType = currentMaterial.quantityType();
      Boolean isManualRawMaterial = currentMaterial.isManualRawMaterial();
      Boolean isJobOrder = currentMaterial.isJobOrder();
      Boolean isVisible = currentMaterial.isVisible();

      if ("CATEGORY".equals(request.updateType())) {
        if (request.categoryId() != null) {
          category = catalogueClient.materialCategory(request.categoryId());
        } else {
          category = null;
        }
      } else if ("REFINED".equals(request.updateType())) {
        if (request.refinedMaterialId() != null) {
          refinedMaterial = catalogueClient.material(request.refinedMaterialId());
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

      catalogueClient.updateMaterial(id, body);
      cacheEviction.evict(CacheDomain.MATERIAL);
      MaterialDto updatedMaterial = catalogueClient.material(id);
      return ResponseEntity.ok(updatedMaterial);
    } catch (BackendServiceException e) {
      BackendErrorLogging.warn(log, "PUT /api/v1/materials", id, e);
      return ResponseEntity.status(500).build();
    } catch (Exception e) {
      log.error("Ajax update material failed", e);
      return ResponseEntity.status(500).build();
    }
  }

  /**
   * Creates a material manually via {@code POST /api/v1/materials}, which marks it as a manual
   * entry, and clears the static-data cache on success.
   *
   * @param request validated create payload
   * @return the persisted material, or an empty body with the backend's status on failure
   */
  @ResponseBody
  @PostMapping("/ajax")
  public ResponseEntity<MaterialDto> createMaterialAjax(
      @Valid @RequestBody MaterialCreateAjaxRequest request) {
    try {
      MaterialDto created = catalogueClient.createMaterial(request);
      cacheEviction.evict(CacheDomain.MATERIAL);
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
