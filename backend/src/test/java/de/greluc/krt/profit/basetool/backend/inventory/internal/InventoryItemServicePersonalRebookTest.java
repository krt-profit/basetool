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

package de.greluc.krt.profit.basetool.backend.inventory.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditService;
import de.greluc.krt.profit.basetool.backend.catalogue.api.GameItem;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Location;
import de.greluc.krt.profit.basetool.backend.catalogue.api.LocationRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.identity.api.UserRepository;
import de.greluc.krt.profit.basetool.backend.inventory.api.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeReason;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockOfferLookup;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.scope.api.OwnerScopeService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link InventoryItemService#rebookPersonal} (REQ-INV-007), which splits part or
 * all of a row into a new row with the opposite {@code personal} flag.
 *
 * <p>The direction follows the source row: a personal source is de-personalised onto the picked
 * org-unit pool ({@link AuditEventType#INVENTORY_ITEM_DEPERSONALIZED}); a shared source is
 * personalised, dropping its job-order and mission links ({@link
 * AuditEventType#INVENTORY_ITEM_PERSONALIZED}).
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemServicePersonalRebookTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private StockOfferLookup stockOfferLookup;
  @Mock private StockChangeObserver offerRatchet;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;
  @Mock private ApplicationEventPublisher eventPublisher;
  @InjectMocks private InventoryCheckoutService service;

  private static final UUID ITEM_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final InventoryItemDto SENTINEL = sentinelDto();

  private User owner;
  private Location location;
  private Material material;
  private Squadron sourceOrgUnit;

  @BeforeEach
  void setUpEntities() {
    owner = new User();
    owner.setId(OWNER_ID);
    owner.setUsername("alice");

    location = new Location();
    location.setId(UUID.randomUUID());
    location.setName("ARC-L1");

    material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantanium");

    sourceOrgUnit = new Squadron();
    sourceOrgUnit.setId(UUID.randomUUID());

    lenient().when(inventoryItemMapper.toDto(any(InventoryItem.class))).thenReturn(SENTINEL);
    lenient()
        .when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(inv -> inv.getArgument(0));
  }

  @Nested
  class GuardTests {

    @Test
    void notFound_throws() {
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () -> service.rebookPersonal(ITEM_ID, dto(1.0, 1L, null), OWNER_ID, false));
    }

    @Test
    void versionMismatch_throwsOptimisticLockingFailure() {
      InventoryItem item = newItem(10.0, 5L, true);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      assertThrows(
          ObjectOptimisticLockingFailureException.class,
          () -> service.rebookPersonal(ITEM_ID, dto(1.0, 99L, null), OWNER_ID, false));
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void nonOwnerNonAdmin_throwsAccessDenied() {
      InventoryItem item = newItem(10.0, 1L, true);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      UUID otherUserId = UUID.randomUUID();

      assertThrows(
          AccessDeniedException.class,
          () -> service.rebookPersonal(ITEM_ID, dto(1.0, 1L, null), otherUserId, false));
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).delete(any());
    }

    @Test
    void nonOwnerButAdmin_isAllowed() {
      InventoryItem item = newItem(10.0, 1L, true);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      UUID otherUserId = UUID.randomUUID();

      InventoryItemDto result =
          service.rebookPersonal(ITEM_ID, dto(4.0, 1L, null), otherUserId, true);

      assertSame(SENTINEL, result);
      verify(inventoryItemRepository).save(any(InventoryItem.class));
    }
  }

  @Nested
  class RefusalTests {

    private void assertRefused(InventoryItem item, double amount, String detail) {
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.rebookPersonal(ITEM_ID, dto(amount, 1L, null), OWNER_ID, false));

      assertEquals(detail, ex.getMessage());
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).delete(any());
      verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void amountNotPositive_isABadRequest() {
      assertRefused(newItem(10.0, 1L, true), 0.0, "Rebooked amount must be positive");
    }

    @Test
    void exceedsStock_isABadRequest() {
      assertRefused(newItem(5.0, 1L, true), 10.0, "Cannot rebook more than the available amount");
    }

    @Test
    void fractionalAmount_isABadRequestNamingTheStock() {
      assertRefused(newGameItemRow(10.0), 1.5, "Amount must be a whole number for item stock");
    }

    @Test
    void earmarked_isABadRequest() {
      InventoryItem item = newItem(10.0, 1L, false);
      InventoryAllocations.addJobOrder(item, UUID.randomUUID(), item.getAmount(), false);

      assertRefused(
          item, 4.0, "Stock assigned to a job order or mission cannot be marked personal");
    }
  }

  @Nested
  class DepersonalizeTests {

    @Test
    void partial_savesSharedRowAndDecrementsSource() {
      InventoryItem item = newItem(10.0, 1L, true);
      item.setOwningOrgUnit(sourceOrgUnit);
      Squadron picked = new Squadron();
      picked.setId(UUID.randomUUID());
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      UUID pickedId = UUID.randomUUID();
      when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(owner, pickedId))
          .thenReturn(picked);

      InventoryItemDto result =
          service.rebookPersonal(ITEM_ID, dto(4.0, 1L, pickedId), OWNER_ID, false);

      assertSame(SENTINEL, result);
      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      InventoryItem newRow = saveCaptor.getValue();
      assertFalse(newRow.getPersonal(), "de-personalized row must be shared");
      assertEquals(4.0, newRow.getAmount(), "new row carries the moved amount");
      assertSame(picked, newRow.getOwningOrgUnit(), "new shared row is stamped on the picked pool");
      assertSame(owner, newRow.getUser(), "owner carries over");
      assertSame(material, newRow.getMaterial());
      assertSame(location, newRow.getLocation());

      assertEquals(6.0, item.getAmount(), "source keeps the remainder");
      verify(inventoryItemRepository).saveAndFlush(item);
      verify(inventoryItemRepository, never()).delete(any());
      verify(offerRatchet).lower(ITEM_ID, 6.0, StockChangeReason.REBOOK);

      verify(auditService)
          .record(
              eq(AuditEventType.INVENTORY_ITEM_DEPERSONALIZED),
              eq(ITEM_ID),
              any(),
              eq(OWNER_ID),
              any());
    }

    @Test
    void partial_carriesTheStolenMarkerOntoTheNewRow() {
      InventoryItem item = newItem(10.0, 1L, true);
      item.setStolen(true);
      item.setOwningOrgUnit(sourceOrgUnit);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      UUID pickedId = UUID.randomUUID();
      when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(owner, pickedId))
          .thenReturn(sourceOrgUnit);

      service.rebookPersonal(ITEM_ID, dto(4.0, 1L, pickedId), OWNER_ID, false);

      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      assertEquals(
          Boolean.TRUE,
          saveCaptor.getValue().getStolen(),
          "rebooking never launders stolen stock into legitimate stock (REQ-INV-053)");
    }

    @Test
    void full_deletesSourceAndSavesSharedRow() {
      InventoryItem item = newItem(5.0, 1L, true);
      item.setOwningOrgUnit(sourceOrgUnit);
      Squadron picked = new Squadron();
      picked.setId(UUID.randomUUID());
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));
      UUID pickedId = UUID.randomUUID();
      when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(owner, pickedId))
          .thenReturn(picked);

      service.rebookPersonal(ITEM_ID, dto(5.0, 1L, pickedId), OWNER_ID, false);

      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      assertFalse(saveCaptor.getValue().getPersonal());
      assertEquals(5.0, saveCaptor.getValue().getAmount());
      verify(offerRatchet).beforeDelete(List.of(ITEM_ID), StockChangeReason.REBOOK);
      verify(inventoryItemRepository).delete(item);
      verify(inventoryItemRepository, never()).saveAndFlush(any());
      verify(auditService)
          .record(
              eq(AuditEventType.INVENTORY_ITEM_DEPERSONALIZED),
              eq(ITEM_ID),
              any(),
              eq(OWNER_ID),
              any());
    }
  }

  @Nested
  class PersonalizeTests {

    @Test
    void partial_carriesSourceOrgUnit_dropsAssociations_andDecrementsSource() {
      InventoryItem item = newItem(10.0, 1L, false);
      item.setOwningOrgUnit(sourceOrgUnit);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      InventoryItemDto result =
          service.rebookPersonal(ITEM_ID, dto(4.0, 1L, null), OWNER_ID, false);

      assertSame(SENTINEL, result);
      ArgumentCaptor<InventoryItem> saveCaptor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(saveCaptor.capture());
      InventoryItem newRow = saveCaptor.getValue();
      assertTrue(newRow.getPersonal(), "personalized row must be private");
      assertEquals(4.0, newRow.getAmount());
      assertSame(
          sourceOrgUnit, newRow.getOwningOrgUnit(), "private row carries the source stamp over");
      assertTrue(
          newRow.getJobOrderAllocations().isEmpty(), "personal row never carries a job order");
      assertTrue(newRow.getMissionAllocations().isEmpty(), "personal row never carries a mission");
      assertSame(owner, newRow.getUser());

      verify(ownerScopeService, never()).resolveOrgUnitForPickerOutputNullable(any(), any());

      assertEquals(6.0, item.getAmount(), "source keeps the remainder");
      verify(inventoryItemRepository).saveAndFlush(item);
      verify(inventoryItemRepository, never()).delete(any());

      verify(auditService)
          .record(
              eq(AuditEventType.INVENTORY_ITEM_PERSONALIZED),
              eq(ITEM_ID),
              any(),
              eq(OWNER_ID),
              any());
    }

    @Test
    void full_deletesSourceAndSavesPersonalRow() {
      InventoryItem item = newItem(5.0, 1L, false);
      item.setOwningOrgUnit(sourceOrgUnit);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      InventoryItemDto result =
          service.rebookPersonal(ITEM_ID, dto(5.0, 1L, null), OWNER_ID, false);

      assertSame(SENTINEL, result);
      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(captor.capture());
      assertEquals(5.0, captor.getValue().getAmount());
      assertTrue(captor.getValue().getPersonal(), "the split row flips to the personal pool");
      verify(inventoryItemRepository).delete(item);
      verify(inventoryItemRepository, never()).saveAndFlush(any());
    }

    @Test
    void itemRow_copiesGameItemOntoNewRow() {
      InventoryItem item = newGameItemRow(10.0);
      item.setOwningOrgUnit(sourceOrgUnit);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      service.rebookPersonal(ITEM_ID, dto(4.0, 1L, null), OWNER_ID, false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(captor.capture());
      InventoryItem newRow = captor.getValue();
      assertSame(item.getGameItem(), newRow.getGameItem());
      assertNull(newRow.getMaterial());
      assertNull(newRow.getQuality());
      assertTrue(newRow.getPersonal(), "the split row flips to the personal pool");
      assertEquals(4.0, newRow.getAmount());
    }
  }

  /**
   * Builds a shared game-item row: game item set, material and quality {@code null}.
   *
   * @param amount the row's amount
   * @return the row
   */
  private InventoryItem newGameItemRow(double amount) {
    InventoryItem item = newItem(amount, 1L, false);
    item.setMaterial(null);
    item.setQuality(null);
    GameItem gameItem = new GameItem();
    gameItem.setId(UUID.randomUUID());
    gameItem.setName("Quantum Drive");
    item.setGameItem(gameItem);
    return item;
  }

  private InventoryItem newItem(double amount, Long version, boolean personal) {
    InventoryItem item = new InventoryItem();
    item.setId(ITEM_ID);
    item.setAmount(amount);
    item.setQuality(500);
    item.setUser(owner);
    item.setLocation(location);
    item.setMaterial(material);
    item.setPersonal(personal);
    item.setVersion(version);
    return item;
  }

  /**
   * Builds a rebook request payload.
   *
   * @param amount the quantity to rebook
   * @param version the echoed-back source-row version
   * @param targetOwningOrgUnitId the picked org-unit pool for the de-personalize direction, or
   *     {@code null}
   * @return the request DTO
   */
  private static InventoryItemPersonalRebookDto dto(
      Double amount, Long version, UUID targetOwningOrgUnitId) {
    return new InventoryItemPersonalRebookDto(amount, version, targetOwningOrgUnitId, null);
  }

  /**
   * A fixed sentinel DTO returned by the mapper stub so tests can assert the service echoes the
   * mapped new row.
   *
   * @return the sentinel inventory-item DTO
   */
  private static InventoryItemDto sentinelDto() {
    return new InventoryItemDto(
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        500,
        4.0,
        false,
        false,
        java.util.List.of(),
        0.0,
        java.util.List.of(),
        0.0,
        null,
        null,
        1L,
        null,
        null);
  }
}
