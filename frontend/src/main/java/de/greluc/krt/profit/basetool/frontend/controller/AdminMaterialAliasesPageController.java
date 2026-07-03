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

import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExternalAliasDto;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialExternalAliasWriteRequest;
import de.greluc.krt.profit.basetool.frontend.model.dto.MaterialReferenceDto;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.service.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.support.Roles;
import de.greluc.krt.profit.basetool.frontend.support.StringNormalization;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Spring MVC controller backing {@code /admin/material-aliases}. The page renders the curated
 * cross-reference list (Wiki / UEX commodity names → local materials) plus three forms — add / edit
 * / delete — that round-trip through the backend's {@code /api/v1/material-external-aliases} REST
 * surface.
 *
 * <p>The page is admin-only. Class-level {@code @PreAuthorize("hasRole('ADMIN')")} matches the
 * backend gate. Mutations use full-page redirects with flash toasts (the backend already enforces
 * its own validation + 409 / 404 mapping, so a per-field inline AJAX flow is overkill for R1).
 */
@Controller
@RequestMapping("/admin/material-aliases")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasRole('" + Roles.ADMIN + "')")
public class AdminMaterialAliasesPageController {

  private static final String BACKEND_BASE = "/api/v1/material-external-aliases";

  /** Response type for the {@code /material-external-aliases} alias list. */
  private static final ParameterizedTypeReference<List<MaterialExternalAliasDto>> ALIAS_LIST_TYPE =
      new ParameterizedTypeReference<>() {};

  /** Response type for the {@code /materials/lookup} material-reference list. */
  private static final ParameterizedTypeReference<List<MaterialReferenceDto>>
      MATERIAL_REFERENCE_LIST_TYPE = new ParameterizedTypeReference<>() {};

  private final BackendApiClient backendApiClient;

  /**
   * Renders the alias list plus the add form. Failures (backend 500, network) collapse to an error
   * banner shown in the page header so the operator sees the alias data is stale.
   *
   * @param model Thymeleaf model populated with the alias list and a material lookup list
   * @return the {@code admin/material-aliases} view name
   */
  @GetMapping
  public String list(Model model) {
    try {
      List<MaterialExternalAliasDto> aliases = backendApiClient.get(BACKEND_BASE, ALIAS_LIST_TYPE);
      model.addAttribute("aliases", aliases == null ? List.of() : aliases);

      List<MaterialReferenceDto> materials =
          backendApiClient.get("/api/v1/materials/lookup", MATERIAL_REFERENCE_LIST_TYPE);
      List<MaterialReferenceDto> sorted =
          new ArrayList<>(materials == null ? List.of() : materials);
      sorted.sort(
          Comparator.comparing(
              m -> m.name() == null ? "" : m.name(), String.CASE_INSENSITIVE_ORDER));
      model.addAttribute("materials", sorted);
    } catch (Exception e) {
      log.error("Failed to load material-alias admin page", e);
      model.addAttribute("error", "error.admin.materialAlias.load");
      model.addAttribute("aliases", List.of());
      model.addAttribute("materials", List.of());
    }
    return "admin/material-aliases";
  }

  /**
   * Resolves a material's display name from its id for the edit-form preview.
   *
   * @param id alias UUID
   * @param model Thymeleaf model populated with the alias and the material picker list
   * @return the {@code admin/material-aliases} view name (the edit form lives on the same page)
   */
  @GetMapping("/{id}")
  public String edit(@PathVariable @NotNull UUID id, Model model) {
    try {
      MaterialExternalAliasDto alias =
          backendApiClient.get(BACKEND_BASE + "/" + id, MaterialExternalAliasDto.class);
      model.addAttribute("aliasToEdit", alias);
    } catch (Exception e) {
      log.error("Failed to load alias {} for edit", id, e);
      model.addAttribute("error", "error.admin.materialAlias.load");
    }
    return list(model);
  }

  /**
   * Creates a new alias. Validation failures bubble back as a generic error toast — the inline form
   * does not surface field-level violations in R1 (a full inline edit experience is deferred to a
   * follow-up).
   *
   * @param materialId linked material UUID
   * @param sourceSystem catalogue identifier
   * @param externalName external commodity name
   * @param externalKey optional external internal key
   * @param externalUuid optional external UUID
   * @param externalCode optional external short code
   * @param note free-form provenance note
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/material-aliases}
   */
  @PostMapping
  public String create(
      @RequestParam UUID materialId,
      @RequestParam String sourceSystem,
      @RequestParam String externalName,
      @RequestParam(required = false) String externalKey,
      @RequestParam(required = false) UUID externalUuid,
      @RequestParam(required = false) String externalCode,
      @RequestParam(required = false) String note,
      RedirectAttributes redirectAttributes) {
    try {
      MaterialExternalAliasWriteRequest body =
          new MaterialExternalAliasWriteRequest(
              materialId,
              sourceSystem,
              externalName,
              StringNormalization.trimToNull(externalKey),
              externalUuid,
              StringNormalization.trimToNull(externalCode),
              StringNormalization.trimToNull(note),
              null);
      backendApiClient.post(BACKEND_BASE, body, MaterialExternalAliasDto.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Create alias failed", e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.save");
    }
    return "redirect:/admin/material-aliases";
  }

  /**
   * Updates an existing alias. Mirrors {@link #create} but carries the optimistic-lock {@code
   * version} from the edit form's hidden input.
   *
   * @param id alias UUID to update
   * @param materialId linked material UUID
   * @param sourceSystem catalogue identifier
   * @param externalName external commodity name
   * @param externalKey optional external internal key
   * @param externalUuid optional external UUID
   * @param externalCode optional external short code
   * @param note free-form provenance note
   * @param version optimistic-lock token from the edit form
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/material-aliases}
   */
  @PostMapping("/{id}")
  public String update(
      @PathVariable @NotNull UUID id,
      @RequestParam UUID materialId,
      @RequestParam String sourceSystem,
      @RequestParam String externalName,
      @RequestParam(required = false) String externalKey,
      @RequestParam(required = false) UUID externalUuid,
      @RequestParam(required = false) String externalCode,
      @RequestParam(required = false) String note,
      @RequestParam Long version,
      RedirectAttributes redirectAttributes) {
    try {
      MaterialExternalAliasWriteRequest body =
          new MaterialExternalAliasWriteRequest(
              materialId,
              sourceSystem,
              externalName,
              StringNormalization.trimToNull(externalKey),
              externalUuid,
              StringNormalization.trimToNull(externalCode),
              StringNormalization.trimToNull(note),
              version);
      backendApiClient.put(BACKEND_BASE + "/" + id, body, MaterialExternalAliasDto.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.save");
    } catch (Exception e) {
      log.error("Update alias {} failed", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.save");
    }
    return "redirect:/admin/material-aliases";
  }

  /**
   * Deletes an alias.
   *
   * @param id alias UUID
   * @param redirectAttributes flash attributes carrier
   * @return redirect to {@code /admin/material-aliases}
   */
  @PostMapping("/{id}/delete")
  public String delete(@PathVariable @NotNull UUID id, RedirectAttributes redirectAttributes) {
    try {
      backendApiClient.delete(BACKEND_BASE + "/" + id, Void.class);
      redirectAttributes.addFlashAttribute("successToast", "notification.success.delete");
    } catch (Exception e) {
      log.error("Delete alias {} failed", id, e);
      redirectAttributes.addFlashAttribute("errorToast", "notification.error.delete");
    }
    return "redirect:/admin/material-aliases";
  }

  /**
   * In-place (AJAX) twin of {@link #create} — routed here ahead of the classic handler by the
   * {@code X-Requested-With} header so the no-JS form keeps its redirect fallback. Binds the form
   * as JSON and returns the created {@link MaterialExternalAliasDto} so the page can append a row
   * without reloading. Optional string fields are normalised exactly like the classic flow.
   *
   * @param request the JSON-bound create payload
   * @return the created alias on success, the relayed backend status on failure, {@code 500} on an
   *     unexpected error
   */
  @ResponseBody
  @PostMapping(headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> createAjax(@RequestBody MaterialExternalAliasWriteRequest request) {
    try {
      MaterialExternalAliasWriteRequest body =
          new MaterialExternalAliasWriteRequest(
              request.materialId(),
              request.sourceSystem(),
              request.externalName(),
              StringNormalization.trimToNull(request.externalKey()),
              request.externalUuid(),
              StringNormalization.trimToNull(request.externalCode()),
              StringNormalization.trimToNull(request.note()),
              null);
      return ResponseEntity.ok(
          backendApiClient.post(BACKEND_BASE, body, MaterialExternalAliasDto.class));
    } catch (BackendServiceException e) {
      log.error("Create alias (ajax) failed", e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Create alias (ajax) failed", e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * In-place (AJAX) twin of {@link #update}. Carries the optimistic-lock {@code version}; a backend
   * conflict is relayed as {@code application/problem+json} so the client surfaces the
   * reload-confirm instead of reloading, and the fresh version is returned so the edit form can
   * keep saving.
   *
   * @param id alias UUID to update
   * @param request the JSON-bound update payload (incl. {@code version})
   * @return the updated alias on success, the relayed backend status on conflict/failure, {@code
   *     500} on an unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/{id}", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> updateAjax(
      @PathVariable @NotNull UUID id, @RequestBody MaterialExternalAliasWriteRequest request) {
    try {
      MaterialExternalAliasWriteRequest body =
          new MaterialExternalAliasWriteRequest(
              request.materialId(),
              request.sourceSystem(),
              request.externalName(),
              StringNormalization.trimToNull(request.externalKey()),
              request.externalUuid(),
              StringNormalization.trimToNull(request.externalCode()),
              StringNormalization.trimToNull(request.note()),
              request.version());
      return ResponseEntity.ok(
          backendApiClient.put(BACKEND_BASE + "/" + id, body, MaterialExternalAliasDto.class));
    } catch (BackendServiceException e) {
      log.error("Update alias {} (ajax) failed", id, e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Update alias {} (ajax) failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * In-place (AJAX) twin of {@link #delete}. On success the page removes the alias row in place.
   *
   * @param id alias UUID
   * @return {@code 200} on success, the relayed backend status on failure, {@code 500} on an
   *     unexpected error
   */
  @ResponseBody
  @PostMapping(value = "/{id}/delete", headers = "X-Requested-With=XMLHttpRequest")
  public ResponseEntity<Object> deleteAjax(@PathVariable @NotNull UUID id) {
    try {
      backendApiClient.delete(BACKEND_BASE + "/" + id, Void.class);
      return ResponseEntity.ok().build();
    } catch (BackendServiceException e) {
      log.error("Delete alias {} (ajax) failed", id, e);
      return propagateBackendError(e);
    } catch (Exception e) {
      log.error("Delete alias {} (ajax) failed", id, e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }
}
