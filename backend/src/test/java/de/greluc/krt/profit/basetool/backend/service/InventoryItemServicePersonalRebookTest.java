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

package de.greluc.krt.profit.basetool.backend.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.mapper.MaterialMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.Mission;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryItemPersonalRebookDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/**
 * Coverage for {@link InventoryItemService#rebookPersonal} — the personal-marker rebooking
 * ("Umbuchung", REQ-INV-007) flow that splits part or all of an inventory row into a new row with
 * the opposite {@code personal} flag.
 *
 * <p>The direction is derived from the source row's {@code personal} flag, never from the caller: a
 * {@code personal = true} source is de-personalized (new shared row stamped on the picked org-unit
 * pool, audit {@link AuditEventType#INVENTORY_ITEM_DEPERSONALIZED}); a {@code personal = false}
 * source is personalized (new private row carrying the source row's org-unit stamp, job-order /
 * mission dropped, audit {@link AuditEventType#INVENTORY_ITEM_PERSONALIZED}). The split mirrors the
 * book-out {@code TRANSFER} branch: the moved amount is decremented off the source (the source is
 * deleted once depleted below the epsilon) and inserted as a brand-new append-only row. A bug here
 * means wrong ownership decisions, lost stock, or a personal row that illegally carries a job-order
 * / mission link.
 */
@ExtendWith(MockitoExtension.class)
class InventoryItemServicePersonalRebookTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private MaterialRepository materialRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private MissionRepository missionRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private MaterialMapper materialMapper;
  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;
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

    @Test
    void zeroAmount_throwsBadRequest() {
      InventoryItem item = newItem(10.0, 1L, true);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.rebookPersonal(ITEM_ID, dto(0.0, 1L, null), OWNER_ID, false));
      assertEquals("Rebooked amount must be positive", ex.getMessage());
      verify(inventoryItemRepository, never()).save(any());
    }

    @Test
    void negativeAmount_throwsBadRequest() {
      InventoryItem item = newItem(10.0, 1L, true);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.rebookPersonal(ITEM_ID, dto(-5.0, 1L, null), OWNER_ID, false));
      assertEquals("Rebooked amount must be positive", ex.getMessage());
    }

    @Test
    void amountExceedsAvailable_throwsBadRequest() {
      InventoryItem item = newItem(5.0, 1L, true);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.rebookPersonal(ITEM_ID, dto(10.0, 1L, null), OWNER_ID, false));
      assertEquals("Cannot rebook more than the available amount", ex.getMessage());
      verify(inventoryItemRepository, never()).save(any());
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
      verify(materialExchangeOfferRepository).clampOfferedAmountToStock(eq(ITEM_ID), eq(6.0));

      verify(auditService)
          .record(
              eq(AuditEventType.INVENTORY_ITEM_DEPERSONALIZED),
              eq(ITEM_ID),
              any(),
              eq(OWNER_ID),
              any());
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
    void sourceWithJobOrder_throwsBadRequest_beforeCreatingAnyRow() {
      InventoryItem item = newItem(10.0, 1L, false);
      JobOrder jobOrder = new JobOrder();
      jobOrder.setId(UUID.randomUUID());
      InventoryAllocations.addJobOrder(item, jobOrder, item.getAmount(), false);
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.rebookPersonal(ITEM_ID, dto(4.0, 1L, null), OWNER_ID, false));
      assertEquals(
          "Stock assigned to a job order or mission cannot be marked personal", ex.getMessage());
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).delete(any());
      verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void sourceWithMission_throwsBadRequest_beforeCreatingAnyRow() {
      InventoryItem item = newItem(10.0, 1L, false);
      Mission mission = new Mission();
      mission.setId(UUID.randomUUID());
      InventoryAllocations.addMission(item, mission, item.getAmount());
      when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

      BadRequestException ex =
          assertThrows(
              BadRequestException.class,
              () -> service.rebookPersonal(ITEM_ID, dto(4.0, 1L, null), OWNER_ID, false));
      assertEquals(
          "Stock assigned to a job order or mission cannot be marked personal", ex.getMessage());
      verify(inventoryItemRepository, never()).save(any());
      verify(inventoryItemRepository, never()).delete(any());
      verify(auditService, never()).record(any(), any(), any(), any(), any());
    }
  }

  /**
   * Builds a source inventory row owned by {@link #owner} at {@link #location} for {@link
   * #material}, with the given amount, optimistic-lock version and {@code personal} flag.
   *
   * @param amount the source row's available quantity
   * @param version the source row's {@code @Version}
   * @param personal the source row's personal flag (drives the rebook direction)
   * @return the populated transient source row
   */
  @Test
  void rebook_itemRow_copiesGameItemOntoNewRow() {
    InventoryItem item = newItem(10.0, 1L, false);
    item.setMaterial(null);
    item.setQuality(null);
    de.greluc.krt.profit.basetool.backend.model.GameItem gameItem =
        new de.greluc.krt.profit.basetool.backend.model.GameItem();
    gameItem.setId(UUID.randomUUID());
    gameItem.setName("Quantum Drive");
    item.setGameItem(gameItem);
    item.setOwningOrgUnit(sourceOrgUnit);
    when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

    service.rebookPersonal(ITEM_ID, dto(4.0, 1L, null), OWNER_ID, false);

    ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
    InventoryItem newRow =
        captor.getAllValues().stream()
            .filter(i -> i != item)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected the split row to be saved"));
    assertSame(gameItem, newRow.getGameItem());
    org.junit.jupiter.api.Assertions.assertNull(newRow.getMaterial());
    org.junit.jupiter.api.Assertions.assertNull(newRow.getQuality());
    assertTrue(newRow.getPersonal(), "the split row flips to the personal pool");
    assertEquals(4.0, newRow.getAmount());
  }

  @Test
  void rebook_itemRow_fractionalAmount_throwsBadRequest() {
    InventoryItem item = newItem(10.0, 1L, false);
    item.setMaterial(null);
    item.setQuality(null);
    de.greluc.krt.profit.basetool.backend.model.GameItem gameItem =
        new de.greluc.krt.profit.basetool.backend.model.GameItem();
    gameItem.setId(UUID.randomUUID());
    item.setGameItem(gameItem);
    when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> service.rebookPersonal(ITEM_ID, dto(1.5, 1L, null), OWNER_ID, false));
    assertEquals("Amount must be a whole number for item stock", ex.getMessage());
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void rebook_itemRow_legacyFractionalRow_fullDepletion_isAllowed() {
    InventoryItem item = newItem(1.5, 1L, false);
    item.setMaterial(null);
    item.setQuality(null);
    de.greluc.krt.profit.basetool.backend.model.GameItem gameItem =
        new de.greluc.krt.profit.basetool.backend.model.GameItem();
    gameItem.setId(UUID.randomUUID());
    item.setGameItem(gameItem);
    when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

    InventoryItemDto result = service.rebookPersonal(ITEM_ID, dto(1.5, 1L, null), OWNER_ID, false);

    assertSame(SENTINEL, result);
    ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertEquals(1.5, captor.getValue().getAmount());
    verify(inventoryItemRepository).delete(item);
    verify(inventoryItemRepository, never()).saveAndFlush(any());
  }

  @Test
  void rebook_itemRow_legacyFractionalRow_partialFractionalAmount_throwsBadRequest() {
    InventoryItem item = newItem(1.5, 1L, false);
    item.setMaterial(null);
    item.setQuality(null);
    de.greluc.krt.profit.basetool.backend.model.GameItem gameItem =
        new de.greluc.krt.profit.basetool.backend.model.GameItem();
    gameItem.setId(UUID.randomUUID());
    item.setGameItem(gameItem);
    when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> service.rebookPersonal(ITEM_ID, dto(0.5, 1L, null), OWNER_ID, false));
    assertEquals("Amount must be a whole number for item stock", ex.getMessage());
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void rebook_pieceMaterial_legacyFractionalRow_fullDepletion_isAllowed() {
    InventoryItem item = newItem(1.5, 1L, false);
    item.getMaterial().setQuantityType(QuantityType.PIECE);
    item.setOwningOrgUnit(sourceOrgUnit);
    when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

    InventoryItemDto result = service.rebookPersonal(ITEM_ID, dto(1.5, 1L, null), OWNER_ID, false);

    assertSame(SENTINEL, result);
    ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(captor.capture());
    assertEquals(1.5, captor.getValue().getAmount());
    assertTrue(captor.getValue().getPersonal(), "the split row flips to the personal pool");
    verify(inventoryItemRepository).delete(item);
    verify(inventoryItemRepository, never()).saveAndFlush(any());
  }

  @Test
  void rebook_pieceMaterial_legacyFractionalRow_partialFractionalAmount_throwsBadRequest() {
    InventoryItem item = newItem(1.5, 1L, false);
    item.getMaterial().setQuantityType(QuantityType.PIECE);
    when(inventoryItemRepository.findById(ITEM_ID)).thenReturn(Optional.of(item));

    BadRequestException ex =
        assertThrows(
            BadRequestException.class,
            () -> service.rebookPersonal(ITEM_ID, dto(0.5, 1L, null), OWNER_ID, false));
    assertEquals("Amount must be a whole number for PIECE materials", ex.getMessage());
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
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
