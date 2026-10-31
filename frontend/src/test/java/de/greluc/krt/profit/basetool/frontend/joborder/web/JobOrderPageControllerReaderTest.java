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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Pins how {@link JobOrderPageController} maps the page records of a stubbed {@link
 * JobOrderPageReader} into the model (REQ-FE-036, ADR-0291).
 */
class JobOrderPageControllerReaderTest {

  private static final UUID ID = UUID.randomUUID();

  private final JobOrderPageReader reader = mock(JobOrderPageReader.class);

  private final QualityTierCatalog qualityTiers = mock(QualityTierCatalog.class);

  private final JobOrderPageController controller =
      new JobOrderPageController(
          mock(JobOrderBackendClient.class),
          reader,
          mock(RoleHierarchy.class),
          qualityTiers,
          mock(JobOrderItemLines.class));

  private static JobOrderDto order(String type, boolean redacted) {
    return new JobOrderDto(
        ID,
        7,
        null,
        null,
        "Handle",
        null,
        1,
        "OPEN",
        type,
        true,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        Instant.now(),
        1L,
        null,
        null,
        redacted);
  }

  private static OrgUnitMembershipOptionDto option(boolean eligible) {
    return new OrgUnitMembershipOptionDto(UUID.randomUUID(), "A", "A", "SQUADRON", eligible);
  }

  private String viewList(Model model) {
    return controller.viewOrders(null, null, null, true, false, null, null, null, model);
  }

  @Test
  void theListModelCarriesTheReadersOrdersThresholdsAndSquadrons() {
    JobOrderDto o = order("MATERIAL", false);
    when(reader.readOrderList(any()))
        .thenReturn(
            new JobOrderPageReader.OrderListPage(
                true, List.of(o), Optional.empty(), 11, 22, List.of()));
    Model model = new ExtendedModelMap();

    assertThat(viewList(model)).isEqualTo("joborder/orders-index");

    assertThat(model.asMap())
        .containsEntry("orders", List.of(o))
        .containsEntry("ageYellowDays", 11)
        .containsEntry("ageRedDays", 22)
        .containsEntry("squadrons", List.of())
        .doesNotContainKey("error");
  }

  @Test
  void anUnavailableListShowsTheLoadErrorWithTheReadersDefaults() {
    when(reader.readOrderList(any()))
        .thenReturn(
            new JobOrderPageReader.OrderListPage(
                false, List.of(), Optional.empty(), 30, 90, List.of()));
    Model model = new ExtendedModelMap();

    viewList(model);

    assertThat(model.asMap())
        .containsEntry("error", "error.joborder.load")
        .containsEntry("orders", List.of())
        .containsEntry("ageYellowDays", 30)
        .containsEntry("ageRedDays", 90)
        .containsEntry("ordersPage", null);
  }

  @Test
  void theDetailModelCarriesTheReadersOptionalParts() {
    JobOrderDto o = order("MATERIAL", false);
    List<OrgUnitMembershipOptionDto> options = List.of(option(true), option(false));
    when(reader.readOrderDetail(any(), any(), any()))
        .thenReturn(
            new JobOrderPageReader.OrderDetailPage(
                o,
                true,
                Optional.of(ID),
                Optional.of(List.of()),
                Optional.of(List.of()),
                Optional.of(options),
                JobOrderPageReader.Part.of(null),
                5,
                6,
                JobOrderPageReader.Part.absent(),
                JobOrderPageReader.Part.absent(),
                JobOrderPageReader.Part.of(null)));
    Model model = new ExtendedModelMap();

    assertThat(controller.viewOrderDetail(ID, model, null, null))
        .isEqualTo("joborder/orders-detail");

    assertThat(model.asMap())
        .containsEntry("order", o)
        .containsEntry("isLogistician", true)
        .containsEntry("currentUserId", ID)
        .containsEntry("ageYellowDays", 5)
        .containsEntry("requestingOptions", options)
        .containsKeys("materials", "squadrons", "jobOrderForm", "kpi")
        .containsEntry("actingUser", null)
        .containsEntry("orphanedInventory", null)
        .doesNotContainKeys("itemBlueprintOwners", "itemStockByGameItem");
    assertThat((List<?>) model.asMap().get("responsibleOptions")).hasSize(1);
  }

  @Test
  void theRequestedFragmentIsPassedToTheReader() {
    when(reader.readOrderDetail(any(), any(), any()))
        .thenReturn(
            new JobOrderPageReader.OrderDetailPage(
                order("ITEM", false),
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                JobOrderPageReader.Part.absent(),
                5,
                6,
                JobOrderPageReader.Part.absent(),
                JobOrderPageReader.Part.absent(),
                JobOrderPageReader.Part.absent()));

    assertThat(controller.viewOrderDetail(ID, new ExtendedModelMap(), null, "items"))
        .isEqualTo("joborder/orders-detail :: itemsSection");

    verify(reader).readOrderDetail(eq(ID), any(), eq("items"));
  }

  @Test
  void aFailedDetailReadSendsTheUserBackToTheList() {
    when(reader.readOrderDetail(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

    assertThat(controller.viewOrderDetail(ID, new ExtendedModelMap(), null, null))
        .isEqualTo("redirect:/orders");
  }

  @Test
  void aFailedDetailFragmentReadAnswersTheErrorFragment() {
    when(reader.readOrderDetail(any(), any(), any())).thenThrow(new IllegalStateException("boom"));

    assertThat(controller.viewOrderDetail(ID, new ExtendedModelMap(), null, "kpi"))
        .isEqualTo("joborder/orders-detail :: fragmentError");
  }

  @Test
  void theCreateModelCarriesTheReadersCatalogs() {
    List<OrgUnitMembershipOptionDto> options = List.of(option(true));
    when(reader.readOrderCreate())
        .thenReturn(new JobOrderPageReader.OrderCreatePage(List.of(), false, List.of(), options));
    Model model = new ExtendedModelMap();

    assertThat(controller.viewCreateForm(null, model)).isEqualTo("joborder/orders-create");

    assertThat(model.asMap())
        .containsEntry("hasOrderableItems", false)
        .containsEntry("requestingOptions", options)
        .containsKeys("materials", "squadrons", "qualityTiers", "jobOrderForm");
  }

  @Test
  void aCreateFormWithDefaultedCatalogsStillCarriesEveryAttribute() {
    when(reader.readOrderCreate())
        .thenReturn(new JobOrderPageReader.OrderCreatePage(List.of(), true, List.of(), List.of()));
    Model model = new ExtendedModelMap();

    controller.viewCreateForm(null, model);

    assertThat(model.asMap())
        .containsEntry("materials", List.of())
        .containsEntry("squadrons", List.of())
        .containsEntry("hasOrderableItems", true)
        .containsEntry("requestingOptions", List.of())
        .containsEntry("responsibleOptions", List.of());
  }

  @Test
  void theEditFormMapsEachBlockedAccessToItsRedirect() {
    assertEditRedirect(
        JobOrderPageReader.ItemEditAccess.ORDER_UNAVAILABLE,
        "redirect:/orders",
        "error.joborder.load.details");
    assertEditRedirect(
        JobOrderPageReader.ItemEditAccess.NOT_AN_ITEM_ORDER,
        "redirect:/orders/" + ID,
        "error.joborder.item.edit.notItem");
    assertEditRedirect(
        JobOrderPageReader.ItemEditAccess.HAS_ITEM_HANDOVERS,
        "redirect:/orders/" + ID,
        "error.joborder.item.edit.hasHandovers");
  }

  private void assertEditRedirect(
      JobOrderPageReader.ItemEditAccess access, String redirect, String toast) {
    when(reader.readItemEdit(ID))
        .thenReturn(
            new JobOrderPageReader.ItemEditPage(
                access, Optional.empty(), List.of(), true, List.of(), List.of()));
    RedirectAttributesModelMap flash = new RedirectAttributesModelMap();

    assertThat(controller.viewEditItemForm(ID, new ExtendedModelMap(), flash)).isEqualTo(redirect);
    assertThat(flash.getFlashAttributes().get("errorToast")).isEqualTo(toast);
  }

  @Test
  void theEditFormModelCarriesTheReadersOrderAndCatalogs() {
    JobOrderDto o = order("ITEM", false);
    when(reader.readItemEdit(ID))
        .thenReturn(
            new JobOrderPageReader.ItemEditPage(
                JobOrderPageReader.ItemEditAccess.EDITABLE,
                Optional.of(o),
                List.of(),
                true,
                List.of(),
                List.of()));
    Model model = new ExtendedModelMap();

    assertThat(controller.viewEditItemForm(ID, model, new RedirectAttributesModelMap()))
        .isEqualTo("joborder/orders-create");

    assertThat(model.asMap())
        .containsEntry("editOrderId", ID)
        .containsKeys("jobOrderItemForm", "materials", "squadrons", "requestingOptions");
  }

  @Test
  void theBlueprintProxyReturnsTheReadersAnswer() {
    UUID gameItemId = UUID.randomUUID();
    List<BlueprintReferenceDto> blueprints =
        List.of(new BlueprintReferenceDto(UUID.randomUUID(), "B", "BP"));
    when(reader.readItemBlueprints(gameItemId)).thenReturn(blueprints);

    assertThat(controller.itemBlueprints(gameItemId)).isSameAs(blueprints);
  }

  @Test
  void aLostSessionFromTheReaderPropagatesOutOfTheDetailPage() {
    when(reader.readOrderDetail(any(), any(), any()))
        .thenThrow(
            new ReauthenticationRequiredException("lost", new IllegalStateException("expired")));

    assertThatThrownBy(() -> controller.viewOrderDetail(ID, new ExtendedModelMap(), null, null))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }
}
