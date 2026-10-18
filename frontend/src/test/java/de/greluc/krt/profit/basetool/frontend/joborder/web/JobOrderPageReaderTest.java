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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemHandoverDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderSettingsDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Pins the page reads of {@link JobOrderPageReader} against a stubbed {@link JobOrderBackendClient}
 * (REQ-FE-036, ADR-0291).
 */
class JobOrderPageReaderTest {

  private static final UUID ID = UUID.randomUUID();

  private final JobOrderBackendClient client = mock(JobOrderBackendClient.class);

  private final JobOrderPageReader reader =
      new JobOrderPageReader(client, new ParallelPageLoader());

  private final Logger log = (Logger) LoggerFactory.getLogger(JobOrderPageReader.class);

  private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

  private Level previousLevel;

  @BeforeEach
  void attach() {
    appender.start();
    log.addAppender(appender);
    previousLevel = log.getLevel();
    log.setLevel(Level.DEBUG);
  }

  @AfterEach
  void detach() {
    log.detachAppender(appender);
    log.setLevel(previousLevel);
  }

  private static JobOrderDto order(
      String type, boolean redacted, List<JobOrderItemHandoverDto> handovers) {
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
        handovers,
        Instant.now(),
        1L,
        null,
        redacted);
  }

  private static JobOrderPageReader.OrderCaller logistician() {
    return new JobOrderPageReader.OrderCaller(true, UUID.randomUUID(), true);
  }

  private static SquadronDto squadron(String name, boolean active) {
    return new SquadronDto(UUID.randomUUID(), name, name, null, active, true, true, 1L);
  }

  private static MaterialDto material() {
    return new MaterialDto(
        UUID.randomUUID(),
        "Iron",
        null,
        "SCU",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        1L);
  }

  private static OrgUnitMembershipOptionDto option(boolean eligible) {
    return new OrgUnitMembershipOptionDto(UUID.randomUUID(), "A", "A", "SQUADRON", eligible);
  }

  private static <T> PageResponse<T> page(List<T> content) {
    return new PageResponse<>(content, 0, 100, content.size(), 1, List.of());
  }

  private static RuntimeException backendDown() {
    return new BackendServiceException("down", null, 503);
  }

  private static ReauthenticationRequiredException lostSession() {
    return new ReauthenticationRequiredException("lost", new IllegalStateException("expired"));
  }

  private void assertOneLogLine(String section) {
    assertThat(appender.list)
        .singleElement()
        .extracting(ILoggingEvent::getFormattedMessage)
        .isEqualTo("Page section '" + section + "' unavailable");
  }

  private static JobOrderPageReader.OrderListQuery listQuery(boolean withSquadronFilter) {
    return new JobOrderPageReader.OrderListQuery(
        false, false, 0, 100, List.of("OPEN"), List.of(), withSquadronFilter);
  }

  private void stubList(PageResponse<JobOrderDto> p) {
    when(client.orders(anyBoolean(), anyBoolean(), anyInt(), anyInt(), any(), any())).thenReturn(p);
  }

  @Test
  void orderListReadsOrdersSettingsAndSquadrons() {
    JobOrderDto o = order("MATERIAL", false, List.of());
    stubList(page(List.of(o)));
    when(client.settings()).thenReturn(new JobOrderSettingsDto(10, 20));
    when(client.squadrons())
        .thenReturn(
            page(List.of(squadron("b", true), squadron("a", true), squadron("gone", false))));

    JobOrderPageReader.OrderListPage list = reader.readOrderList(listQuery(true));

    assertThat(list.ordersAvailable()).isTrue();
    assertThat(list.orders()).containsExactly(o);
    assertThat(list.ordersPage()).isPresent();
    assertThat(list.ageYellowDays()).isEqualTo(10);
    assertThat(list.ageRedDays()).isEqualTo(20);
    assertThat(list.activeSquadrons()).extracting(SquadronDto::name).containsExactly("a", "b");
    assertThat(appender.list).isEmpty();
  }

  @Test
  void orderListSkipsTheSquadronPickerWhenNotAskedFor() {
    stubList(page(List.of()));
    when(client.settings()).thenReturn(new JobOrderSettingsDto(10, 20));

    assertThat(reader.readOrderList(listQuery(false)).activeSquadrons()).isEmpty();
  }

  @Test
  void orderListFallsBackToDefaultThresholdsWhenTheSettingsFail() {
    stubList(page(List.of()));
    when(client.settings()).thenThrow(backendDown());

    JobOrderPageReader.OrderListPage list = reader.readOrderList(listQuery(false));

    assertThat(list.ordersAvailable()).isTrue();
    assertThat(list.ageYellowDays()).isEqualTo(JobOrderPageReader.DEFAULT_AGE_YELLOW_DAYS);
    assertThat(list.ageRedDays()).isEqualTo(JobOrderPageReader.DEFAULT_AGE_RED_DAYS);
    assertOneLogLine("job-order-age-settings");
  }

  @Test
  void orderListIsUnavailableWhenTheOrdersFail() {
    when(client.orders(anyBoolean(), anyBoolean(), anyInt(), anyInt(), any(), any()))
        .thenThrow(backendDown());

    JobOrderPageReader.OrderListPage list = reader.readOrderList(listQuery(false));

    assertThat(list.ordersAvailable()).isFalse();
    assertThat(list.orders()).isEmpty();
    assertThat(list.ordersPage()).isEmpty();
    assertThat(list.ageYellowDays()).isEqualTo(JobOrderPageReader.DEFAULT_AGE_YELLOW_DAYS);
    assertThat(list.ageRedDays()).isEqualTo(JobOrderPageReader.DEFAULT_AGE_RED_DAYS);
    assertOneLogLine("job-order-list");
  }

  @Test
  void orderListDefaultsTheSquadronPickerWhenTheSquadronsFail() {
    stubList(page(List.of()));
    when(client.settings()).thenReturn(new JobOrderSettingsDto(10, 20));
    when(client.squadrons()).thenThrow(backendDown());

    assertThat(reader.readOrderList(listQuery(true)).activeSquadrons()).isEmpty();
    assertOneLogLine("job-order-squadrons");
  }

  @Test
  void orderListPropagatesALostSession() {
    when(client.orders(anyBoolean(), anyBoolean(), anyInt(), anyInt(), any(), any()))
        .thenThrow(lostSession());

    assertThatThrownBy(() -> reader.readOrderList(listQuery(false)))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void aLogisticianReadsTheFullItemOrderDetail() {
    JobOrderDto o = order("ITEM", false, List.of());
    UUID gameItemId = UUID.randomUUID();
    JobOrderItemStockGroupDto group =
        new JobOrderItemStockGroupDto(
            new InventoryGameItemReferenceDto(gameItemId, "G", null, "WEAPON"),
            1,
            0,
            0L,
            List.of());
    JobOrderItemBlueprintOwnersDto owners =
        new JobOrderItemBlueprintOwnersDto(List.of(), List.of());
    when(client.order(ID)).thenReturn(o);
    when(client.jobOrderMaterials()).thenReturn(List.of(material()));
    when(client.squadrons()).thenReturn(page(List.of(squadron("a", true))));
    when(client.activeOrgUnitsAllKinds()).thenReturn(List.of(option(true)));
    when(client.currentUser()).thenReturn(mock(UserDto.class));
    when(client.settings()).thenReturn(new JobOrderSettingsDto(5, 6));
    when(client.itemBlueprintOwners(ID)).thenReturn(owners);
    when(client.itemStock(ID)).thenReturn(List.of(group));
    when(client.orphanedInventory(ID)).thenReturn(List.<InventoryItemDto>of());

    JobOrderPageReader.OrderDetailPage detail = reader.readOrderDetail(ID, logistician(), null);

    assertThat(detail.order()).isSameAs(o);
    assertThat(detail.canAssign()).isTrue();
    assertThat(detail.currentUserId()).isPresent();
    assertThat(detail.materials()).hasValueSatisfying(m -> assertThat(m).hasSize(1));
    assertThat(detail.squadrons()).hasValueSatisfying(s -> assertThat(s).hasSize(1));
    assertThat(detail.requestingOptions()).hasValueSatisfying(r -> assertThat(r).hasSize(1));
    assertThat(detail.actingUser()).isPresent();
    assertThat(detail.ageYellowDays()).isEqualTo(5);
    assertThat(detail.ageRedDays()).isEqualTo(6);
    assertThat(detail.blueprintOwners()).containsSame(owners);
    assertThat(detail.itemStockByGameItem())
        .hasValueSatisfying(m -> assertThat(m).containsEntry(gameItemId, group));
    assertThat(detail.orphanedInventory()).hasValue(List.of());
    assertThat(appender.list).isEmpty();
  }

  @Test
  void aRequesterReadsOnlyTheMaterialCatalogBesidesTheOrder() {
    when(client.order(ID)).thenReturn(order("MATERIAL", true, List.of()));
    when(client.jobOrderMaterials()).thenReturn(List.of(material()));
    when(client.settings()).thenReturn(new JobOrderSettingsDto(5, 6));
    when(client.orphanedInventory(ID)).thenReturn(List.of());

    JobOrderPageReader.OrderDetailPage detail = reader.readOrderDetail(ID, logistician(), null);

    assertThat(detail.canAssign()).isFalse();
    assertThat(detail.materials()).isPresent();
    assertThat(detail.squadrons()).isEmpty();
    assertThat(detail.requestingOptions()).isEmpty();
    assertThat(detail.actingUser()).isEmpty();
    assertThat(detail.blueprintOwners()).isEmpty();
    assertThat(detail.itemStockByGameItem()).isEmpty();
  }

  @Test
  void aFragmentReadSkipsTheSectionsItDoesNotRender() {
    when(client.order(ID)).thenReturn(order("ITEM", false, List.of()));
    when(client.jobOrderMaterials()).thenReturn(List.of());
    when(client.squadrons()).thenReturn(page(List.of()));
    when(client.activeOrgUnitsAllKinds()).thenReturn(List.of());
    when(client.settings()).thenReturn(new JobOrderSettingsDto(5, 6));
    when(client.itemBlueprintOwners(ID))
        .thenReturn(new JobOrderItemBlueprintOwnersDto(List.of(), List.of()));

    JobOrderPageReader.OrderDetailPage detail =
        reader.readOrderDetail(ID, logistician(), "blueprint-owners");

    assertThat(detail.blueprintOwners()).isPresent();
    assertThat(detail.actingUser()).isEmpty();
    assertThat(detail.itemStockByGameItem()).isEmpty();
    assertThat(detail.orphanedInventory()).isEmpty();
  }

  @Test
  void everyOptionalDetailPartFailsAloneToItsDefaultWithOneLogLine() {
    when(client.order(ID)).thenReturn(order("ITEM", false, List.of()));
    when(client.jobOrderMaterials()).thenThrow(backendDown());
    when(client.squadrons()).thenThrow(backendDown());
    when(client.activeOrgUnitsAllKinds()).thenThrow(backendDown());
    when(client.activeOrgUnits()).thenThrow(backendDown());
    when(client.currentUser()).thenThrow(backendDown());
    when(client.settings()).thenThrow(backendDown());
    when(client.itemBlueprintOwners(ID)).thenThrow(backendDown());
    when(client.itemStock(ID)).thenThrow(backendDown());
    when(client.orphanedInventory(ID)).thenThrow(backendDown());

    JobOrderPageReader.OrderDetailPage detail =
        reader.readOrderDetail(ID, new JobOrderPageReader.OrderCaller(true, null, true), null);

    assertThat(detail.currentUserId()).isEmpty();
    assertThat(detail.materials()).hasValue(List.of());
    assertThat(detail.squadrons()).hasValue(List.of());
    assertThat(detail.requestingOptions()).hasValue(List.of());
    assertThat(detail.actingUser()).isEmpty();
    assertThat(detail.ageYellowDays()).isEqualTo(JobOrderPageReader.DEFAULT_AGE_YELLOW_DAYS);
    assertThat(detail.ageRedDays()).isEqualTo(JobOrderPageReader.DEFAULT_AGE_RED_DAYS);
    assertThat(detail.blueprintOwners()).isEmpty();
    assertThat(detail.itemStockByGameItem()).isEmpty();
    assertThat(detail.orphanedInventory()).isEmpty();
    assertThat(appender.list)
        .extracting(ILoggingEvent::getFormattedMessage)
        .containsExactlyInAnyOrder(
            "Page section 'job-order-current-user' unavailable",
            "Page section 'job-order-materials' unavailable",
            "Page section 'job-order-squadrons' unavailable",
            "Page section 'job-order-requesting-org-units' unavailable",
            "Page section 'job-order-active-org-units' unavailable",
            "Page section 'job-order-acting-user' unavailable",
            "Page section 'job-order-age-settings' unavailable",
            "Page section 'job-order-blueprint-owners' unavailable",
            "Page section 'job-order-item-stock' unavailable",
            "Page section 'job-order-orphaned-inventory' unavailable");
  }

  @Test
  void anOrderThatCannotBeReadFailsTheDetailPage() {
    when(client.order(ID)).thenThrow(backendDown());

    assertThatThrownBy(() -> reader.readOrderDetail(ID, logistician(), null))
        .isInstanceOf(BackendServiceException.class);
  }

  @Test
  void aLostSessionOnTheOrderPropagates() {
    when(client.order(ID)).thenThrow(lostSession());

    assertThatThrownBy(() -> reader.readOrderDetail(ID, logistician(), null))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void aLostSessionInAParallelDetailPartPropagatesUnwrapped() {
    when(client.order(ID)).thenReturn(order("MATERIAL", false, List.of()));
    when(client.jobOrderMaterials()).thenThrow(lostSession());
    when(client.squadrons()).thenReturn(page(List.of()));
    when(client.activeOrgUnitsAllKinds()).thenReturn(List.of());

    assertThatThrownBy(() -> reader.readOrderDetail(ID, logistician(), null))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void aLostSessionOnTheCurrentUserLookupPropagates() {
    when(client.order(ID)).thenReturn(order("MATERIAL", false, List.of()));
    when(client.currentUser()).thenThrow(lostSession());

    assertThatThrownBy(
            () ->
                reader.readOrderDetail(
                    ID, new JobOrderPageReader.OrderCaller(true, null, false), null))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void createFormReadsEveryCatalog() {
    when(client.jobOrderMaterials()).thenReturn(List.of(material()));
    when(client.orderableItemProbe()).thenReturn(page(List.<InventoryGameItemReferenceDto>of()));
    when(client.squadrons()).thenReturn(page(List.of(squadron("a", true))));
    when(client.activeOrgUnitsAllKinds()).thenReturn(List.of(option(true)));

    JobOrderPageReader.OrderCreatePage create = reader.readOrderCreate();

    assertThat(create.materials()).hasSize(1);
    assertThat(create.hasOrderableItems()).isFalse();
    assertThat(create.squadrons()).hasSize(1);
    assertThat(create.requestingOptions()).hasSize(1);
    assertThat(appender.list).isEmpty();
  }

  @Test
  void createFormDefaultsEveryCatalogAndFailsOpenOnTheProbe() {
    when(client.jobOrderMaterials()).thenThrow(backendDown());
    when(client.orderableItemProbe()).thenThrow(backendDown());
    when(client.squadrons()).thenThrow(backendDown());
    when(client.activeOrgUnitsAllKinds()).thenThrow(backendDown());
    when(client.activeOrgUnits()).thenThrow(backendDown());

    JobOrderPageReader.OrderCreatePage create = reader.readOrderCreate();

    assertThat(create.materials()).isEmpty();
    assertThat(create.hasOrderableItems()).isTrue();
    assertThat(create.squadrons()).isEmpty();
    assertThat(create.requestingOptions()).isEmpty();
    assertThat(appender.list).hasSize(5);
  }

  @Test
  void createFormFallsBackToTheActiveUnitsWhenTheAllKindsReadFails() {
    when(client.jobOrderMaterials()).thenReturn(List.of());
    when(client.orderableItemProbe()).thenReturn(null);
    when(client.squadrons()).thenReturn(null);
    when(client.activeOrgUnitsAllKinds()).thenThrow(backendDown());
    when(client.activeOrgUnits()).thenReturn(List.of(option(false)));

    JobOrderPageReader.OrderCreatePage create = reader.readOrderCreate();

    assertThat(create.requestingOptions()).hasSize(1);
    assertThat(create.hasOrderableItems()).isTrue();
    assertOneLogLine("job-order-requesting-org-units");
  }

  @Test
  void createFormPropagatesALostSession() {
    when(client.jobOrderMaterials()).thenThrow(lostSession());

    assertThatThrownBy(reader::readOrderCreate)
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void itemEditReadsTheCatalogsOfAnEditableOrder() {
    JobOrderDto o = order("ITEM", false, List.of());
    when(client.order(ID)).thenReturn(o);
    when(client.jobOrderMaterials()).thenReturn(List.of(material()));
    when(client.orderableItemProbe()).thenReturn(null);
    when(client.squadrons()).thenReturn(page(List.of()));
    when(client.activeOrgUnitsAllKinds()).thenReturn(List.of(option(true)));

    JobOrderPageReader.ItemEditPage edit = reader.readItemEdit(ID);

    assertThat(edit.access()).isEqualTo(JobOrderPageReader.ItemEditAccess.EDITABLE);
    assertThat(edit.order()).containsSame(o);
    assertThat(edit.materials()).hasSize(1);
    assertThat(edit.requestingOptions()).hasSize(1);
  }

  @Test
  void itemEditReportsAnUnreadableOrderAndReadsNothingElse() {
    when(client.order(ID)).thenThrow(backendDown());

    JobOrderPageReader.ItemEditPage edit = reader.readItemEdit(ID);

    assertThat(edit.access()).isEqualTo(JobOrderPageReader.ItemEditAccess.ORDER_UNAVAILABLE);
    assertThat(edit.order()).isEmpty();
    assertOneLogLine("job-order-item-edit");
    verify(client).order(ID);
    verifyNoMoreInteractions(client);
  }

  @Test
  void itemEditBlocksAnOrderThatIsNoItemOrder() {
    when(client.order(ID)).thenReturn(order("MATERIAL", false, List.of()));

    assertThat(reader.readItemEdit(ID).access())
        .isEqualTo(JobOrderPageReader.ItemEditAccess.NOT_AN_ITEM_ORDER);
  }

  @Test
  void itemEditBlocksAMissingOrder() {
    when(client.order(ID)).thenReturn(null);

    assertThat(reader.readItemEdit(ID).access())
        .isEqualTo(JobOrderPageReader.ItemEditAccess.NOT_AN_ITEM_ORDER);
  }

  @Test
  void itemEditBlocksAnOrderWithItemHandovers() {
    JobOrderItemHandoverDto handover =
        new JobOrderItemHandoverDto(
            UUID.randomUUID(), ID, Instant.now(), "R", null, null, List.of(), 1L);
    when(client.order(ID)).thenReturn(order("ITEM", false, List.of(handover)));

    JobOrderPageReader.ItemEditPage edit = reader.readItemEdit(ID);

    assertThat(edit.access()).isEqualTo(JobOrderPageReader.ItemEditAccess.HAS_ITEM_HANDOVERS);
    assertThat(edit.materials()).isEmpty();
  }

  @Test
  void itemEditPropagatesALostSession() {
    when(client.order(ID)).thenThrow(lostSession());

    assertThatThrownBy(() -> reader.readItemEdit(ID))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }

  @Test
  void itemBlueprintsAreReadOrDefaultedToEmpty() {
    UUID gameItemId = UUID.randomUUID();
    BlueprintReferenceDto blueprint = new BlueprintReferenceDto(UUID.randomUUID(), "B", "BP");
    when(client.itemBlueprints(gameItemId)).thenReturn(List.of(blueprint));

    assertThat(reader.readItemBlueprints(gameItemId)).containsExactly(blueprint);
    assertThat(appender.list).isEmpty();

    when(client.itemBlueprints(gameItemId)).thenThrow(backendDown());

    assertThat(reader.readItemBlueprints(gameItemId)).isEmpty();
    assertOneLogLine("job-order-item-blueprints");
  }

  @Test
  void itemBlueprintsPropagateALostSession() {
    UUID gameItemId = UUID.randomUUID();
    when(client.itemBlueprints(gameItemId)).thenThrow(lostSession());

    assertThatThrownBy(() -> reader.readItemBlueprints(gameItemId))
        .isInstanceOf(ReauthenticationRequiredException.class);
  }
}
