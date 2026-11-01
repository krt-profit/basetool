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

package de.greluc.krt.profit.basetool.frontend.inventory.web;

import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.inventory.client.InventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryForm;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderGameItemNeedDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialNeedDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.UsesLayoutModel;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Controller for the inventory create form ({@code /inventory/input}) and the order-needs lookup
 * its check-in picker relabels from.
 */
@Controller
@UsesLayoutModel
@RequestMapping("/inventory")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("isAuthenticated()")
public class InventoryInputPageController {

  /** The inventory domain's typed backend client. */
  private final InventoryBackendClient inventoryClient;

  /**
   * Writes the check-in picker's per-order need figures into the page's {@code data-order-needs}
   * attribute (REQ-INV-039). A {@code static} instance — writes are thread-safe and the
   * server-rendered frontend context registers no shared {@code ObjectMapper} bean (the same
   * rationale {@code RefineryImportProxyController} states for its own).
   */
  private static final ObjectMapper NEEDS_MAPPER = JsonMapper.builder().build();

  private final ParallelPageLoader parallelPageLoader;

  private final InventoryCatalogs catalogs;

  /**
   * Renders the inventory create form; {@code source=admin} seeds {@code isGlobal=true} so the
   * admin can pick a target user, otherwise the entry is the caller's own.
   *
   * @param source optional origin marker ({@code admin}, {@code my}, {@code aggregated}) used to
   *     pick the post-save redirect target
   * @param model model populated with the form and dropdown catalogs
   * @return the {@code inventory-input} view name
   */
  @NotNull
  @GetMapping("/input")
  public String viewInputPage(@RequestParam(required = false) String source, Model model) {
    InventoryForm form;
    if (!model.containsAttribute("inventoryForm")) {
      form = new InventoryForm();
      if ("admin".equals(source)) {
        form.setIsGlobal(true);
      } else {
        form.setIsGlobal(false);
      }
      form.setSource(source);
      model.addAttribute("inventoryForm", form);
    } else {
      form = (InventoryForm) model.getAttribute("inventoryForm");
    }

    final InventoryForm boundForm = form;
    var materialsFuture = parallelPageLoader.loadAsync(catalogs::materials);
    var locationsFuture = parallelPageLoader.loadAsync(catalogs::locations);
    var missionsFuture = parallelPageLoader.loadAsync(catalogs::missions);
    var jobOrdersFuture = parallelPageLoader.loadAsync(() -> catalogs.activeJobOrders(true));
    var ownerFuture = parallelPageLoader.loadAsync(() -> fetchOwnerPickerOptions(boundForm));
    var selectedUserFuture = parallelPageLoader.loadAsync(() -> fetchSelectedInputUser(boundForm));
    SectionFallback.run(
        log,
        "input-catalogs",
        () ->
            CompletableFuture.allOf(
                    materialsFuture,
                    locationsFuture,
                    missionsFuture,
                    jobOrdersFuture,
                    ownerFuture,
                    selectedUserFuture)
                .join());
    model.addAttribute("materials", materialsFuture.join());
    model.addAttribute("locations", locationsFuture.join());
    model.addAttribute("missions", missionsFuture.join());
    List<JobOrderReferenceDto> inputJobOrders = jobOrdersFuture.join();
    model.addAttribute("jobOrders", inputJobOrders);
    model.addAttribute("jobOrderNeedsJson", writeOrderNeedsJson(orderNeeds(inputJobOrders)));
    model.addAttribute("ownerOptions", ownerFuture.join());
    model.addAttribute("selectedUser", selectedUserFuture.join());
    return "inventory/inventory-input";
  }

  /**
   * Returns the active-order catalog with its per-material needs as JSON, for the Einbuchen form's
   * live-sync relabelling of its order options (REQ-INV-039, REQ-FE-010).
   *
   * @return the active orders with their needs; an empty list when the lookup fails
   */
  @NotNull
  @ResponseBody
  @GetMapping(value = "/order-needs", headers = "X-Requested-With=XMLHttpRequest")
  public Map<String, Object> orderNeedsAjax() {
    return orderNeeds(catalogs.activeJobOrders(true));
  }

  /**
   * Reduces the order catalog to one envelope of order-id-keyed material and game-item needs;
   * orders needing nothing in a dimension are omitted from that map.
   *
   * @param orders the loaded order catalog, already carrying its needs.
   * @return {@code {"materials": {orderId: [...]}, "gameItems": {orderId: [...]}}}, never {@code
   *     null}.
   */
  @NotNull
  private static Map<String, Object> orderNeeds(List<JobOrderReferenceDto> orders) {
    Map<UUID, List<JobOrderMaterialNeedDto>> materials = new LinkedHashMap<>();
    Map<UUID, List<JobOrderGameItemNeedDto>> gameItems = new LinkedHashMap<>();
    for (JobOrderReferenceDto order : orders) {
      if (order.id() == null) {
        continue;
      }
      if (order.materialNeeds() != null && !order.materialNeeds().isEmpty()) {
        materials.put(order.id(), order.materialNeeds());
      }
      if (order.gameItemNeeds() != null && !order.gameItemNeeds().isEmpty()) {
        gameItems.put(order.id(), order.gameItemNeeds());
      }
    }
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("materials", materials);
    envelope.put("gameItems", gameItems);
    return envelope;
  }

  /**
   * Serialises the need map for the page's {@code data-order-needs} attribute.
   *
   * @param needs the needs by order id.
   * @return the JSON object, or {@code "{}"} when it cannot be written.
   */
  private String writeOrderNeedsJson(Map<String, Object> needs) {
    try {
      return NEEDS_MAPPER.writeValueAsString(needs);
    } catch (JacksonException e) {
      log.warn("Failed to serialise job-order need figures for the check-in picker", e);
      return "{}";
    }
  }

  /**
   * Resolves the admin-chosen target user for the input form's user-picker seed option.
   *
   * @param form the inbound inventory form; may be {@code null} before binding.
   * @return the selected user DTO, or {@code null} when not a global entry, none chosen, or the
   *     lookup fails.
   */
  @Nullable
  private UserDto fetchSelectedInputUser(InventoryForm form) {
    if (form == null || !Boolean.TRUE.equals(form.getIsGlobal()) || form.getUserId() == null) {
      return null;
    }
    return SectionFallback.load(
        log, "input-selected-user", () -> inventoryClient.user(form.getUserId()), null);
  }

  /**
   * Resolves the owner-picker options on the inventory-input form from the memberships of the
   * form's {@code userId} for an admin global entry, otherwise of the caller.
   *
   * @param form the inbound inventory form (may be {@code null} before binding).
   * @return picker options, or an empty list on failure; never {@code null}.
   */
  private List<OrgUnitMembershipOptionDto> fetchOwnerPickerOptions(InventoryForm form) {
    List<OrgUnitMembershipOptionDto> options =
        form != null && Boolean.TRUE.equals(form.getIsGlobal()) && form.getUserId() != null
            ? SectionFallback.load(
                log,
                "owner-picker-memberships",
                () -> inventoryClient.memberships(form.getUserId()),
                null)
            : SectionFallback.load(
                log, "owner-picker-org-units", inventoryClient::pickableOrgUnits, null);
    return options != null ? options : List.of();
  }
}
