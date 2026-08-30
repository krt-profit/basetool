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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.model.City;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.RefineryGood;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrder;
import de.greluc.krt.profit.basetool.backend.model.RefineryOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.RefineryYield;
import de.greluc.krt.profit.basetool.backend.model.SpaceStation;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryOrderStoreItemDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.RefineryYieldRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for {@link RefineryOrderService#storeRefineryOrder} — the bulk-update + multi-item
 * flow CLAUDE.md flags as a 409 trap. The Lager is append-only: every stored refinery output
 * becomes its own brand-new {@code InventoryItem} row and is never folded into an existing
 * identical stack, so there is no match-and-merge branch and no note-merge to exercise. This suite
 * covers:
 *
 * <ul>
 *   <li>access-control (owner vs non-owner vs logistician bypass)
 *   <li>per-item lookups (material / location / user / job-order {@link NotFoundException}s)
 *   <li>assignee resolution (explicit user vs order-owner fallback)
 *   <li>always inserting a fresh InventoryItem carrying the incoming amount (rounded to SCU scale)
 *   <li>note normalisation on the new row (trimmed value stored; null / blank stored as null)
 *   <li>{@code updateGoodOutputQuantity}: SCU vs PIECE conversion, {@code @Min(1)} clamp at zero,
 *       no-match silent skip, missing output-material guard
 *   <li>final state transition to {@link RefineryOrderStatus#COMPLETED}
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RefineryOrderServiceTest {

  @Mock private RefineryOrderRepository refineryOrderRepository;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;

  @Mock
  private de.greluc.krt.profit.basetool.backend.repository.MissionRepository missionRepository;

  @Mock
  private de.greluc.krt.profit.basetool.backend.repository.RefiningMethodRepository
      refiningMethodRepository;

  @Mock private MaterialRepository materialRepository;
  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private RefineryYieldRepository refineryYieldRepository;
  @Mock private OwnerScopeService ownerScopeService;

  @Mock private AuditService auditService;
  @InjectMocks private RefineryOrderService refineryOrderService;

  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final UUID OTHER_USER_ID = UUID.randomUUID();
  private static final UUID MATERIAL_ID = UUID.randomUUID();
  private static final UUID LOCATION_ID = UUID.randomUUID();
  private static final UUID JOB_ORDER_ID = UUID.randomUUID();
  private static final UUID OWNING_OU_ID = UUID.randomUUID();

  private RefineryOrder order;
  private User owner;
  private Material material;
  private Location location;

  @BeforeEach
  void setUpEntities() {
    owner = new User();
    owner.setId(OWNER_ID);
    owner.setUsername("alice");

    material = new Material();
    material.setId(MATERIAL_ID);
    material.setName("Quantanium");

    location = new Location();
    location.setId(LOCATION_ID);
    location.setName("ARC-L1");

    order = new RefineryOrder();
    order.setId(ORDER_ID);
    order.setOwner(owner);
    order.setStatus(RefineryOrderStatus.IN_PROGRESS);
  }

  // ------------------------------------------------------------------
  // The kept-from-the-original test (regression coverage).
  // ------------------------------------------------------------------

  @Test
  void shouldThrowExceptionWhenStoringCompletedOrder() {
    RefineryOrder completedOrder = new RefineryOrder();
    completedOrder.setId(ORDER_ID);
    completedOrder.setStatus(RefineryOrderStatus.COMPLETED);

    when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(completedOrder));

    RefineryOrderStoreDto dto = new RefineryOrderStoreDto(Collections.emptyList());

    IllegalStateException ex =
        assertThrows(
            IllegalStateException.class,
            () -> refineryOrderService.storeRefineryOrder(OWNER_ID, ORDER_ID, dto, false));

    assertEquals("Refinery order is already completed and stored.", ex.getMessage());
  }

  // ------------------------------------------------------------------
  // Access control
  // ------------------------------------------------------------------

  @Nested
  class AccessControlTests {

    @Test
    void throwsNotFound_whenOrderDoesNotExist() {
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of()), false));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void throwsAccessDenied_whenNonLogisticianIsNotOwner() {
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      assertThrows(
          AccessDeniedException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OTHER_USER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of()), false));
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void throwsAccessDenied_whenOrderOwnerIsNull_andCallerIsNotLogistician() {
      order.setOwner(null);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      assertThrows(
          AccessDeniedException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of()), false));
    }

    @Test
    void throwsAccessDenied_whenOwnerHasNullId_andCallerIsNotLogistician() {
      owner.setId(null);
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      assertThrows(
          AccessDeniedException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of()), false));
    }

    @Test
    void logisticianBypassesOwnershipCheck_evenForSomeoneElsesOrder() {
      // Empty items list means we exit the for-loop without needing repository stubs
      // for material/location lookups. We just need the order to be reachable
      // and the COMPLETED check to pass.
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));

      // No throw: logistician overrides owner mismatch.
      refineryOrderService.storeRefineryOrder(
          OTHER_USER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of()), true);

      assertEquals(RefineryOrderStatus.COMPLETED, order.getStatus());
      verify(refineryOrderRepository, times(1)).save(order);
    }
  }

  // ------------------------------------------------------------------
  // Per-item entity lookup failures
  // ------------------------------------------------------------------

  @Nested
  class ItemLookupFailureTests {

    @Test
    void throwsNotFound_whenMaterialIsMissing() {
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(item(null, null))), false));
    }

    @Test
    void throwsNotFound_whenLocationIsMissing() {
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(item(null, null))), false));
    }

    @Test
    void throwsNotFound_whenAssigneeUserIsMissing() {
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
      when(userRepository.findById(OTHER_USER_ID)).thenReturn(Optional.empty());

      // Driven as a caller who IS in scope for the foreign assignee: since REQ-SEC-039 a caller
      // naming a foreign assignee is refused before the lookup, so the unknown-user path is only
      // reachable for a caller actually allowed to book on that member's behalf. The gate is
      // canManageUserInventory (per target), not the org-unit-less ROLE_LOGISTICIAN it replaced.
      when(ownerScopeService.canManageUserInventory(OTHER_USER_ID)).thenReturn(true);
      assertThrows(
          NotFoundException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID,
                  ORDER_ID,
                  new RefineryOrderStoreDto(List.of(item(OTHER_USER_ID, null))),
                  true));
    }

    @Test
    void throwsNotFound_whenJobOrderIsMissing() {
      when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
      when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
      when(jobOrderRepository.findById(JOB_ORDER_ID)).thenReturn(Optional.empty());

      assertThrows(
          NotFoundException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID,
                  ORDER_ID,
                  new RefineryOrderStoreDto(List.of(item(null, JOB_ORDER_ID))),
                  false));
    }
  }

  // ------------------------------------------------------------------
  // Assignee fallback
  // ------------------------------------------------------------------

  @Nested
  class AssigneeResolutionTests {

    @Test
    void usesOrderOwnerAsAssignee_whenItemUserIdIsNull() {
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(item(null, null))), false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(1)).save(captor.capture());
      assertSame(owner, captor.getValue().getUser());
    }

    @Test
    void usesExplicitlyProvidedUserAsAssignee_whenLogisticianBooksOnBehalfOfSomeoneElse() {
      User other = new User();
      other.setId(OTHER_USER_ID);
      other.setUsername("bob");

      stubLookupsForSingleItem();
      when(userRepository.findById(OTHER_USER_ID)).thenReturn(Optional.of(other));

      // Booking someone else's stock requires editable org-unit scope on THAT member
      // (REQ-SEC-005/-039) — the same gate the Einbuchen path applies to a foreign target user.
      when(ownerScopeService.canManageUserInventory(OTHER_USER_ID)).thenReturn(true);
      refineryOrderService.storeRefineryOrder(
          OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(item(OTHER_USER_ID, null))), true);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(1)).save(captor.capture());
      assertSame(other, captor.getValue().getUser());
    }

    /**
     * REQ-SEC-039: the per-item {@code userId} must not let a plain member write into someone
     * else's ledger.
     *
     * <p>The caller here passes every other gate — they own the order, so the ownership check above
     * is satisfied — and the only thing standing between them and an arbitrary cross-user inventory
     * write is this guard. Without it a member could fabricate any material, at any quality and any
     * amount, as another member's stock (or, with {@code personal}, their private stock) and leave
     * the audit row attributed to the victim.
     */
    @Test
    void throwsAccessDenied_whenNonLogisticianNamesAnotherUserAsAssignee() {
      stubLookupsForSingleItem();

      assertThrows(
          AccessDeniedException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID,
                  ORDER_ID,
                  new RefineryOrderStoreDto(List.of(item(OTHER_USER_ID, null))),
                  false));

      // Nothing was written: neither the victim's inventory row nor the order's completion.
      verify(inventoryItemRepository, never()).save(any());
      verify(refineryOrderRepository, never()).save(any());
    }

    /**
     * REQ-SEC-005 regression: holding LOGISTICIAN is not an answer about a member of a DIFFERENT
     * org unit.
     *
     * <p>The caller here owns the order (so the ownership check passes) and holds the flat {@code
     * ROLE_LOGISTICIAN} - which is exactly the state that used to authorise this write, because the
     * guard was a role boolean with no org-unit context. The receiver is a member of another
     * Staffel, so {@code canManageUserInventory} says no and the write must be refused before the
     * victim's row or the order completion is touched.
     */
    @Test
    void throwsAccessDenied_whenLogisticianNamesAnAssigneeOutsideTheirOrgUnitScope() {
      stubLookupsForSingleItem();
      when(ownerScopeService.canManageUserInventory(OTHER_USER_ID)).thenReturn(false);

      assertThrows(
          AccessDeniedException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID,
                  ORDER_ID,
                  new RefineryOrderStoreDto(List.of(item(OTHER_USER_ID, null))),
                  true));

      verify(inventoryItemRepository, never()).save(any());
      verify(refineryOrderRepository, never()).save(any());
      verify(userRepository, never()).findById(OTHER_USER_ID);
    }

    /**
     * The guard runs on the requested id before the user lookup, so an unauthorised caller cannot
     * tell an existing member id from a non-existent one (no user-existence oracle).
     */
    @Test
    void refusesForeignAssigneeBeforeLookingItUp() {
      stubLookupsForSingleItem();

      assertThrows(
          AccessDeniedException.class,
          () ->
              refineryOrderService.storeRefineryOrder(
                  OWNER_ID,
                  ORDER_ID,
                  new RefineryOrderStoreDto(List.of(item(OTHER_USER_ID, null))),
                  false));

      verify(userRepository, never()).findById(OTHER_USER_ID);
    }

    /** Naming your own id explicitly is the same act as omitting it, and stays allowed. */
    @Test
    void allowsNonLogisticianToNameTheirOwnIdExplicitly() {
      stubLookupsForSingleItem();
      when(userRepository.findById(OWNER_ID)).thenReturn(Optional.of(owner));

      refineryOrderService.storeRefineryOrder(
          OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(item(OWNER_ID, null))), false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(1)).save(captor.capture());
      assertSame(owner, captor.getValue().getUser());
    }
  }

  // ------------------------------------------------------------------
  // Owning-OrgUnit stamping (#596 — per-item picker output)
  // ------------------------------------------------------------------

  @Nested
  class OwningOrgUnitStampingTests {

    @Test
    void threadsItemOwningOrgUnitIdIntoResolver_andStampsResolvedOrgUnit() {
      // A multi-membership receiver supplies an explicit picker output; the service must hand that
      // exact id (not null) to the resolver and stamp the returned OrgUnit on the new Lager row.
      stubLookupsForSingleItem();
      de.greluc.krt.profit.basetool.backend.model.Squadron resolved =
          new de.greluc.krt.profit.basetool.backend.model.Squadron();
      resolved.setId(OWNING_OU_ID);
      when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(owner, OWNING_OU_ID))
          .thenReturn(resolved);

      RefineryOrderStoreItemDto dto =
          new RefineryOrderStoreItemDto(
              MATERIAL_ID, LOCATION_ID, 500, 10.0, null, null, null, OWNING_OU_ID, null);
      refineryOrderService.storeRefineryOrder(
          OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(dto)), false);

      verify(ownerScopeService).resolveOrgUnitForPickerOutputNullable(owner, OWNING_OU_ID);
      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(1)).save(captor.capture());
      assertSame(resolved, captor.getValue().getOwningOrgUnit());
    }

    @Test
    void passesNullPickerOutput_whenItemOmitsOwningOrgUnitId() {
      // No pick: a single-membership / membershipless receiver still flows through with a null
      // picker output so the resolver keeps its auto-stamp / ownerless behaviour.
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID,
          ORDER_ID,
          new RefineryOrderStoreDto(List.of(itemWithAmount(10.0, null))),
          false);

      verify(ownerScopeService).resolveOrgUnitForPickerOutputNullable(owner, null);
    }
  }

  // ------------------------------------------------------------------
  // Always-insert (append-only) + note handling on the new row
  // ------------------------------------------------------------------

  @Nested
  class InsertTests {

    @Test
    void alwaysInsertsNewInventoryItem() {
      // Append-only Lager: every stored output is a fresh row carrying the incoming amount — no
      // match-and-merge. A pre-existing identical row is irrelevant; nothing accumulates into it.
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID,
          ORDER_ID,
          new RefineryOrderStoreDto(List.of(itemWithAmount(50.0, "fresh note"))),
          false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(1)).save(captor.capture());
      InventoryItem saved = captor.getValue();
      assertSame(owner, saved.getUser());
      assertSame(material, saved.getMaterial());
      assertSame(location, saved.getLocation());
      assertEquals(50.0, saved.getAmount(), "the new row carries the incoming amount, not a sum");
      assertEquals(500, saved.getQuality());
      assertEquals("fresh note", saved.getNote());
    }

    @Test
    void roundsNewItemAmountToThreeDecimals() {
      // The store path rounds the incoming amount to SCU scale (three decimals, HALF_UP) on the new
      // row. 2.2 stays 2.2 — there is no summing with any pre-existing stack any more.
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(itemWithAmount(2.2, null))), false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(1)).save(captor.capture());
      assertEquals(
          2.2, captor.getValue().getAmount(), "new row amount is the rounded incoming SCU");
    }

    @Test
    void newItem_storesNormalizedIncomingNote() {
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID,
          ORDER_ID,
          new RefineryOrderStoreDto(List.of(itemWithAmount(10.0, "  trim me  "))),
          false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(captor.capture());
      assertEquals("trim me", captor.getValue().getNote());
    }

    @Test
    void newItem_blankIncomingNote_storedAsNull() {
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID,
          ORDER_ID,
          new RefineryOrderStoreDto(List.of(itemWithAmount(10.0, "   "))),
          false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository).save(captor.capture());
      assertNull(captor.getValue().getNote());
    }
  }

  // ------------------------------------------------------------------
  // updateGoodOutputQuantity (SCU vs PIECE, @Min(1) clamp, no-match)
  // ------------------------------------------------------------------

  @Nested
  class GoodOutputQuantityTests {

    @Test
    void scuMaterial_amountConvertedToUnits_x100() {
      Material scuMaterial = newMaterial(QuantityType.SCU);
      RefineryGood good = newGoodWithOutput(scuMaterial);
      order.setGoods(new HashSet<>(Set.of(good)));

      storeWithMaterial(scuMaterial, 1.234);

      // 1.234 SCU -> round(123.4) -> 123 units
      assertEquals(123, good.getOutputQuantity());
    }

    @Test
    void pieceMaterial_amountUsedDirectly() {
      Material pieceMaterial = newMaterial(QuantityType.PIECE);
      RefineryGood good = newGoodWithOutput(pieceMaterial);
      order.setGoods(new HashSet<>(Set.of(good)));

      storeWithMaterial(pieceMaterial, 42.4);

      // round(42.4) = 42 units
      assertEquals(42, good.getOutputQuantity());
    }

    @Test
    void sameMaterialAtTwoGrades_eachGoodTakesItsOwnAmount() {
      // A run yields Agricium at 733 and Agricium at 874 — one material, two goods. Matching on
      // the material alone put BOTH items on the first good: the later one overwrote the earlier
      // and the second good was never updated at all. Invisible while clients sent the computed
      // amounts back; the Android Einlagern form corrects them per line, which surfaced it.
      Material scuMaterial = newMaterial(QuantityType.SCU);
      RefineryGood low = newGoodWithOutput(scuMaterial);
      low.setQuality(733);
      RefineryGood high = newGoodWithOutput(scuMaterial);
      high.setQuality(874);
      order.setGoods(new HashSet<>(Set.of(low, high)));

      lenient().when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      lenient()
          .when(materialRepository.findById(eq(scuMaterial.getId())))
          .thenReturn(Optional.of(scuMaterial));
      lenient().when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
      refineryOrderService.storeRefineryOrder(
          OWNER_ID,
          ORDER_ID,
          new RefineryOrderStoreDto(
              List.of(
                  new RefineryOrderStoreItemDto(
                      scuMaterial.getId(), LOCATION_ID, 733, 1.9, null, null, null, null, null),
                  new RefineryOrderStoreItemDto(
                      scuMaterial.getId(), LOCATION_ID, 874, 2.88, null, null, null, null, null))),
          false);

      assertEquals(190, low.getOutputQuantity());
      assertEquals(288, high.getOutputQuantity());
    }

    @Test
    void nullQuantityType_amountUsedDirectly() {
      Material untyped = newMaterial(null);
      RefineryGood good = newGoodWithOutput(untyped);
      order.setGoods(new HashSet<>(Set.of(good)));

      storeWithMaterial(untyped, 10.7);

      // The non-SCU branch rounds: round(10.7) = 11.
      assertEquals(11, good.getOutputQuantity());
    }

    @Test
    void zeroAmount_isClampedToOne() {
      // @Min(1) on RefineryGood.outputQuantity makes 0 invalid;
      // the service must clamp upwards rather than persist an invalid value.
      Material pieceMaterial = newMaterial(QuantityType.PIECE);
      RefineryGood good = newGoodWithOutput(pieceMaterial);
      order.setGoods(new HashSet<>(Set.of(good)));

      storeWithMaterial(pieceMaterial, 0.0);

      assertEquals(1, good.getOutputQuantity());
    }

    @Test
    void noMatchingGood_silentlySkipsTheUpdate() {
      // Good's output material is DIFFERENT from the stored item's material;
      // no update should happen, and outputQuantity must keep its previous value.
      Material storedMaterial = newMaterial(QuantityType.SCU);
      Material differentOutputMaterial = newMaterial(QuantityType.SCU);
      RefineryGood good = newGoodWithOutput(differentOutputMaterial);
      good.setOutputQuantity(999);
      order.setGoods(new HashSet<>(Set.of(good)));

      storeWithMaterial(storedMaterial, 50.0);

      assertEquals(
          999,
          good.getOutputQuantity(),
          "no match -> previous outputQuantity is preserved verbatim");
    }

    @Test
    void goodWithNullOutputMaterial_silentlyIgnored() {
      // No NPE even if a malformed RefineryGood has no output material set.
      Material stored = newMaterial(QuantityType.SCU);
      RefineryGood broken = new RefineryGood();
      broken.setOutputMaterial(null);
      RefineryGood good = newGoodWithOutput(stored);
      order.setGoods(new HashSet<>(Set.of(broken, good)));

      storeWithMaterial(stored, 5.0);

      assertEquals(
          500,
          good.getOutputQuantity(),
          "broken sibling RefineryGood with null outputMaterial must not "
              + "crash the loop nor block the legitimate match");
    }

    @Test
    void goodsCollectionIsNull_silentlyReturns() {
      // Edge: order.goods == null. Should not NPE; just no update happens.
      order.setGoods(null);

      // Use a single item with a valid material; the storeRefineryOrder path
      // still calls updateGoodOutputQuantity, which must return on null goods.
      stubLookupsForSingleItem();

      // Just verify it doesn't throw.
      refineryOrderService.storeRefineryOrder(
          OWNER_ID,
          ORDER_ID,
          new RefineryOrderStoreDto(List.of(itemWithAmount(10.0, null))),
          false);

      verify(refineryOrderRepository, times(1)).save(order);
    }
  }

  // ------------------------------------------------------------------
  // Final state transition
  // ------------------------------------------------------------------

  @Test
  void afterAllItemsProcessed_orderStatusIsCOMPLETED_andOrderSaved() {
    stubLookupsForSingleItem();

    refineryOrderService.storeRefineryOrder(
        OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(itemWithAmount(10.0, null))), false);

    assertEquals(RefineryOrderStatus.COMPLETED, order.getStatus());
    verify(refineryOrderRepository, times(1)).save(order);
  }

  @Test
  void multipleItems_allInsertedAndOrderCompletedExactlyOnce() {
    stubLookupsForSingleItem();
    // Two items reuse the same material/location to keep the fixture light; each still inserts its
    // own row (append-only), so save() is expected exactly twice.

    refineryOrderService.storeRefineryOrder(
        OWNER_ID,
        ORDER_ID,
        new RefineryOrderStoreDto(
            List.of(itemWithAmount(10.0, "note1"), itemWithAmount(20.0, "note2"))),
        false);

    verify(inventoryItemRepository, times(2)).save(any(InventoryItem.class));
    verify(refineryOrderRepository, times(1)).save(order); // order saved exactly once
    assertEquals(RefineryOrderStatus.COMPLETED, order.getStatus());
  }

  // ------------------------------------------------------------------
  // Personal marker (REQ-INV-035)
  // ------------------------------------------------------------------

  /**
   * The store dialog may book an output row into the receiver's private pool instead of the shared
   * squadron stock. A personal row never carries an allocation: the contradictory per-item job
   * order is rejected, and the refinery order's automatic mission earmark is not applied.
   */
  @Nested
  class PersonalMarkerTests {

    @Test
    void storesRowAsPersonal_whenItemSetsTheFlag() {
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(personalItem(true, null))), false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(1)).save(captor.capture());
      assertEquals(Boolean.TRUE, captor.getValue().getPersonal());
    }

    @Test
    void storesRowAsShared_whenFlagIsNullOrFalse() {
      // Backward compatibility: an older client omitting the field must keep producing shared
      // stock.
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID,
          ORDER_ID,
          new RefineryOrderStoreDto(List.of(personalItem(null, null), personalItem(false, null))),
          false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(2)).save(captor.capture());
      assertTrue(
          captor.getAllValues().stream().noneMatch(i -> Boolean.TRUE.equals(i.getPersonal())));
    }

    @Test
    void rejectsPersonalCombinedWithAJobOrder_andWritesNothing() {
      lenient().when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
      lenient().when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
      lenient().when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));

      RefineryOrderStoreDto dto =
          new RefineryOrderStoreDto(List.of(personalItem(true, JOB_ORDER_ID)));

      de.greluc.krt.profit.basetool.backend.exception.BadRequestException ex =
          assertThrows(
              de.greluc.krt.profit.basetool.backend.exception.BadRequestException.class,
              () -> refineryOrderService.storeRefineryOrder(OWNER_ID, ORDER_ID, dto, false));

      assertEquals("Personal items cannot be assigned to a mission or job order", ex.getMessage());
      verify(inventoryItemRepository, never()).save(any());
      verify(refineryOrderRepository, never()).save(any());
    }

    @Test
    void dropsTheOrdersMissionEarmark_onAPersonalRow_butKeepsItOnASharedOne() {
      // The mission earmark is derived from the order, not picked per item — marking the batch
      // personal is precisely the act of taking it out of the mission pool, so the personal row
      // carries no mission slice while a shared row in the same call still does.
      de.greluc.krt.profit.basetool.backend.model.Mission mission =
          new de.greluc.krt.profit.basetool.backend.model.Mission();
      mission.setId(UUID.randomUUID());
      order.setMission(mission);
      stubLookupsForSingleItem();

      refineryOrderService.storeRefineryOrder(
          OWNER_ID,
          ORDER_ID,
          new RefineryOrderStoreDto(List.of(personalItem(true, null), personalItem(false, null))),
          false);

      ArgumentCaptor<InventoryItem> captor = ArgumentCaptor.forClass(InventoryItem.class);
      verify(inventoryItemRepository, times(2)).save(captor.capture());
      InventoryItem personalRow = captor.getAllValues().get(0);
      InventoryItem sharedRow = captor.getAllValues().get(1);
      assertTrue(personalRow.getMissionAllocations().isEmpty());
      assertEquals(1, sharedRow.getMissionAllocations().size());
    }
  }

  // ------------------------------------------------------------------
  // Helpers
  // ------------------------------------------------------------------

  /**
   * Builds a store item for the default material/location carrying the given personal marker and
   * optional job-order pick.
   *
   * @param personal the personal marker to send ({@code null} exercises the omitted-field default)
   * @param jobOrderId the job order to earmark, or {@code null} for none
   * @return the store item DTO
   */
  private static RefineryOrderStoreItemDto personalItem(Boolean personal, UUID jobOrderId) {
    return new RefineryOrderStoreItemDto(
        MATERIAL_ID, LOCATION_ID, 500, 10.0, null, jobOrderId, null, null, personal);
  }

  /**
   * Stubs the repositories required by a single-item store call where the item references the
   * default material + location + no explicit user + no job order.
   */
  private void stubLookupsForSingleItem() {
    lenient().when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    lenient().when(materialRepository.findById(MATERIAL_ID)).thenReturn(Optional.of(material));
    lenient().when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));
  }

  private void storeWithMaterial(Material material, double amount) {
    lenient().when(refineryOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    lenient()
        .when(materialRepository.findById(eq(material.getId())))
        .thenReturn(Optional.of(material));
    lenient().when(locationRepository.findById(LOCATION_ID)).thenReturn(Optional.of(location));

    RefineryOrderStoreItemDto dto =
        new RefineryOrderStoreItemDto(
            material.getId(), LOCATION_ID, 500, amount, null, null, null, null, null);
    refineryOrderService.storeRefineryOrder(
        OWNER_ID, ORDER_ID, new RefineryOrderStoreDto(List.of(dto)), false);
  }

  private static RefineryOrderStoreItemDto item(UUID userId, UUID jobOrderId) {
    return itemWithAmount(10.0, null, userId, jobOrderId);
  }

  private static RefineryOrderStoreItemDto itemWithAmount(double amount, String note) {
    return itemWithAmount(amount, note, null, null);
  }

  private static RefineryOrderStoreItemDto itemWithAmount(
      double amount, String note, UUID userId, UUID jobOrderId) {
    return new RefineryOrderStoreItemDto(
        MATERIAL_ID, LOCATION_ID, 500, amount, userId, jobOrderId, note, null, null);
  }

  private static Material newMaterial(QuantityType type) {
    Material m = new Material();
    m.setId(UUID.randomUUID());
    m.setName("Material " + m.getId());
    m.setQuantityType(type);
    return m;
  }

  private static RefineryGood newGoodWithOutput(Material outputMaterial) {
    RefineryGood good = new RefineryGood();
    good.setOutputMaterial(outputMaterial);
    good.setOutputQuantity(1); // initial value to detect when it's overwritten
    return good;
  }

  /**
   * Tests for the UEX-derived yield-bonus lookup. The contract: pick the right name field
   * (city.name vs spaceStation.name) and map back to {@code materialId → yieldBonus}; never
   * fabricate data for a location that has no city/station hook into the universe sync.
   */
  @Nested
  class GetYieldBonusByMaterialForLocationTests {

    @Test
    void nullLocation_returnsEmpty() {
      assertTrue(refineryOrderService.getYieldBonusByMaterialForLocation(null).isEmpty());
    }

    @Test
    void locationWithoutCityAndStation_returnsEmpty() {
      Location naked = new Location();
      naked.setId(UUID.randomUUID());
      naked.setName("Custom");

      Map<UUID, Integer> result = refineryOrderService.getYieldBonusByMaterialForLocation(naked);

      assertTrue(result.isEmpty());
    }

    @Test
    void cityLocation_queriesByCityName_andReturnsMaterialBonusMap() {
      UUID matA = UUID.randomUUID();
      UUID matB = UUID.randomUUID();
      Material a = new Material();
      a.setId(matA);
      Material b = new Material();
      b.setId(matB);

      RefineryYield y1 = new RefineryYield();
      y1.setMaterial(a);
      y1.setYieldBonus(5);
      RefineryYield y2 = new RefineryYield();
      y2.setMaterial(b);
      y2.setYieldBonus(-3);

      City lorville = new City();
      lorville.setName("Lorville");

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      loc.setCity(lorville);

      when(refineryYieldRepository.findAllForLocation(eq("Lorville"), eq(null)))
          .thenReturn(List.of(y1, y2));

      Map<UUID, Integer> result = refineryOrderService.getYieldBonusByMaterialForLocation(loc);

      assertEquals(2, result.size());
      assertEquals(5, result.get(matA));
      assertEquals(-3, result.get(matB));
    }

    @Test
    void spaceStationLocation_queriesByStationName() {
      UUID matA = UUID.randomUUID();
      Material a = new Material();
      a.setId(matA);

      RefineryYield y = new RefineryYield();
      y.setMaterial(a);
      y.setYieldBonus(2);

      SpaceStation arcL1 = new SpaceStation();
      arcL1.setName("ARC-L1 Wide Forest Station");

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      loc.setSpaceStation(arcL1);

      when(refineryYieldRepository.findAllForLocation(eq(null), eq("ARC-L1 Wide Forest Station")))
          .thenReturn(List.of(y));

      Map<UUID, Integer> result = refineryOrderService.getYieldBonusByMaterialForLocation(loc);

      assertEquals(1, result.size());
      assertEquals(2, result.get(matA));
    }

    @Test
    void zeroBonusValue_isPreserved_notTreatedAsMissing() {
      // A 0% yield row is a real UEX-published value (the commodity refines at the baseline
      // yield) — it must end up in the map so the UI can distinguish it from "no data".
      UUID matA = UUID.randomUUID();
      Material a = new Material();
      a.setId(matA);

      RefineryYield y = new RefineryYield();
      y.setMaterial(a);
      y.setYieldBonus(0);

      SpaceStation station = new SpaceStation();
      station.setName("CRU-L1");

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      loc.setSpaceStation(station);

      when(refineryYieldRepository.findAllForLocation(eq(null), eq("CRU-L1")))
          .thenReturn(List.of(y));

      Map<UUID, Integer> result = refineryOrderService.getYieldBonusByMaterialForLocation(loc);

      assertTrue(result.containsKey(matA));
      assertEquals(0, result.get(matA));
    }

    @Test
    void byLocationId_nullId_returnsEmpty() {
      assertTrue(refineryOrderService.getYieldBonusByMaterialForLocationId(null).isEmpty());
    }

    @Test
    void byLocationId_unknownId_returnsEmpty() {
      UUID missing = UUID.randomUUID();
      when(locationRepository.findById(missing)).thenReturn(Optional.empty());

      Map<UUID, Integer> result =
          refineryOrderService.getYieldBonusByMaterialForLocationId(missing);

      assertTrue(result.isEmpty());
    }

    @Test
    void byLocationId_delegatesToLocationVariant() {
      UUID matA = UUID.randomUUID();
      Material a = new Material();
      a.setId(matA);

      RefineryYield y = new RefineryYield();
      y.setMaterial(a);
      y.setYieldBonus(7);

      City lorville = new City();
      lorville.setName("Lorville");

      UUID locId = UUID.randomUUID();
      Location loc = new Location();
      loc.setId(locId);
      loc.setCity(lorville);

      when(locationRepository.findById(locId)).thenReturn(Optional.of(loc));
      when(refineryYieldRepository.findAllForLocation(eq("Lorville"), eq(null)))
          .thenReturn(List.of(y));

      Map<UUID, Integer> result = refineryOrderService.getYieldBonusByMaterialForLocationId(locId);

      assertEquals(1, result.size());
      assertEquals(7, result.get(matA));
    }

    @Test
    void yieldWithNullMaterial_isSkipped() {
      RefineryYield orphan = new RefineryYield();
      orphan.setMaterial(null);
      orphan.setYieldBonus(99);

      City city = new City();
      city.setName("Area18");

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      loc.setCity(city);

      when(refineryYieldRepository.findAllForLocation(eq("Area18"), eq(null)))
          .thenReturn(List.of(orphan));

      Map<UUID, Integer> result = refineryOrderService.getYieldBonusByMaterialForLocation(loc);

      assertTrue(result.isEmpty());
    }
  }

  // --- R5.d.b createRefineryOrder picker delegation -------------------------
  // The membership-validation + Squadron-resolution logic itself is pinned by
  // OwnerScopeServiceTest. These tests verify that createRefineryOrder routes the picker output
  // through the shared resolver instead of stamping the order owner's home Staffel directly.

  @Nested
  class CreateOrderPickerDelegationTests {

    @Test
    void createRefineryOrder_delegatesPickerResolutionToOwnerScopeService() {
      UUID userId = UUID.randomUUID();
      UUID pickedOrgUnitId = UUID.randomUUID();

      User user = new User();
      user.setId(userId);
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));

      de.greluc.krt.profit.basetool.backend.model.Squadron resolved =
          new de.greluc.krt.profit.basetool.backend.model.Squadron();
      resolved.setId(pickedOrgUnitId);
      when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(user, pickedOrgUnitId))
          .thenReturn(resolved);

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      SpaceStation station = new SpaceStation();
      station.setHasRefineryTerminal(true);
      loc.setSpaceStation(station);
      RefineryOrder transientOrder = new RefineryOrder();
      transientOrder.setLocation(loc);
      when(locationRepository.findById(loc.getId())).thenReturn(Optional.of(loc));
      when(refineryOrderRepository.save(any(RefineryOrder.class)))
          .thenAnswer(i -> i.getArgument(0));

      RefineryOrder saved =
          refineryOrderService.createRefineryOrder(userId, transientOrder, pickedOrgUnitId);

      assertSame(
          resolved,
          saved.getOwningOrgUnit(),
          "the picker output must be honoured verbatim, not user.getSquadron()");
    }

    @Test
    void createRefineryOrder_stripsClientSuppliedIdAndVersion() {
      UUID userId = UUID.randomUUID();
      User user = new User();
      user.setId(userId);
      user.setDisplayName("PII Owner Name");
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));

      Location loc = new Location();
      loc.setId(UUID.randomUUID());
      SpaceStation station = new SpaceStation();
      station.setHasRefineryTerminal(true);
      loc.setSpaceStation(station);
      when(locationRepository.findById(loc.getId())).thenReturn(Optional.of(loc));
      when(refineryOrderRepository.save(any(RefineryOrder.class)))
          .thenAnswer(i -> i.getArgument(0));

      // Audit H-2: a malicious client echoes an existing order's id + version in the create body to
      // turn save() into an EntityManager.merge() UPSERT (AbstractEntity.isNew() == id == null),
      // overwriting and re-owning a foreign order. createRefineryOrder must reset both so the
      // persistence provider performs a clean INSERT instead.
      RefineryOrder transientOrder = new RefineryOrder();
      transientOrder.setLocation(loc);
      transientOrder.setId(UUID.randomUUID());
      transientOrder.setVersion(7L);

      refineryOrderService.createRefineryOrder(userId, transientOrder, null);

      ArgumentCaptor<RefineryOrder> captor = ArgumentCaptor.forClass(RefineryOrder.class);
      verify(refineryOrderRepository).save(captor.capture());
      assertNull(
          captor.getValue().getId(),
          "client-supplied id must be nulled so save() does an INSERT, not a merge UPSERT");
      assertNull(
          captor.getValue().getVersion(), "client-supplied version must be nulled on create");

      // REQ-AUDIT-001 + security fix: a refinery create records exactly one REFINERY_ORDER_CREATED,
      // and its subjectLabel must NOT embed the owner's personal display name (the owner is
      // captured
      // separately as the audit row's target user). Pins the non-personal <method> · <location>
      // label so a regression to <owner> · <location> is caught.
      ArgumentCaptor<String> labelCaptor = ArgumentCaptor.forClass(String.class);
      verify(auditService)
          .record(
              eq(de.greluc.krt.profit.basetool.backend.model.AuditEventType.REFINERY_ORDER_CREATED),
              any(),
              labelCaptor.capture(),
              any(),
              any());
      org.junit.jupiter.api.Assertions.assertFalse(
          labelCaptor.getValue().contains("PII Owner Name"),
          "refinery audit subjectLabel must not contain the owner's personal name");
    }
  }
}
