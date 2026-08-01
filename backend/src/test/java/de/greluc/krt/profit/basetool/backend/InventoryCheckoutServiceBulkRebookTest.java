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

package de.greluc.krt.profit.basetool.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.InventoryItemMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.BulkRebookMode;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QuantityType;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.BulkRebookResultDto;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.LocationRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialExchangeOfferRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionFinanceEntryRepository;
import de.greluc.krt.profit.basetool.backend.repository.MissionParticipantRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.InventoryCheckoutService;
import de.greluc.krt.profit.basetool.backend.service.OwnerScopeService;
import de.greluc.krt.profit.basetool.backend.support.InventoryAllocations;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Unit tests for the bulk rebooking (Massen-Umbuchen, REQ-INV-036) in {@link
 * InventoryCheckoutService}.
 *
 * <p>The behaviour under test is the skip-vs-abort split that separates this action from the
 * all-or-nothing {@link InventoryCheckoutService#bulkCheckout}: a row already sitting in the
 * requested target state is skipped and counted, while an unknown id, a foreign row or an earmark
 * blocking a personalize aborts the whole transaction so nothing is written.
 *
 * <p>Every row here carries an {@code SCU} material and the requests leave {@code mergeStock}
 * unset, so {@code mergeStockIfRequested} returns at its "SCU without the per-action opt-in stays
 * append-only" branch — the merge path has its own tests and is not re-exercised here.
 */
@ExtendWith(MockitoExtension.class)
class InventoryCheckoutServiceBulkRebookTest {

  @Mock private InventoryItemRepository inventoryItemRepository;
  @Mock private UserRepository userRepository;
  @Mock private LocationRepository locationRepository;
  @Mock private MissionFinanceEntryRepository missionFinanceEntryRepository;
  @Mock private MissionParticipantRepository missionParticipantRepository;
  @Mock private MaterialExchangeOfferRepository materialExchangeOfferRepository;
  @Mock private InventoryItemMapper inventoryItemMapper;
  @Mock private OwnerScopeService ownerScopeService;
  @Mock private AuditService auditService;

  @InjectMocks private InventoryCheckoutService checkoutService;

  private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

  @BeforeEach
  void stubSaveEcho() {
    // The service reads the saved row back (it is the merge survivor), so the mock must echo it
    // instead of Mockito's default null.
    lenient()
        .when(inventoryItemRepository.save(any(InventoryItem.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
  }

  // -------------------------------------------------------------------------
  // Helpers
  // -------------------------------------------------------------------------

  private static User user(UUID id) {
    User u = new User();
    u.setId(id);
    return u;
  }

  private static Location location(UUID id, String name) {
    Location l = new Location();
    l.setId(id);
    l.setName(name);
    return l;
  }

  private static Material scuMaterial() {
    Material m = new Material();
    m.setId(UUID.randomUUID());
    m.setName("Titanium");
    m.setQuantityType(QuantityType.SCU);
    return m;
  }

  /** A shared (non-personal) row owned by {@link #OWNER_ID} sitting at {@code loc}. */
  private InventoryItem row(UUID id, Location loc, double amount) {
    InventoryItem item = new InventoryItem();
    item.setId(id);
    item.setUser(user(OWNER_ID));
    item.setMaterial(scuMaterial());
    item.setLocation(loc);
    item.setAmount(amount);
    item.setPersonal(false);
    return item;
  }

  /** Registers {@code item} with the locking loader so the service can load it. */
  private void given(InventoryItem item) {
    when(inventoryItemRepository.findByIdForRebook(item.getId())).thenReturn(Optional.of(item));
  }

  private static BulkRebookRequest toLocation(List<UUID> ids, UUID targetLocationId) {
    return new BulkRebookRequest(ids, BulkRebookMode.LOCATION, null, targetLocationId, null, null);
  }

  private static BulkRebookRequest personal(List<UUID> ids, BulkRebookMode mode) {
    return new BulkRebookRequest(ids, mode, null, null, null, null);
  }

  // -------------------------------------------------------------------------
  // LOCATION mode
  // -------------------------------------------------------------------------

  @Test
  void bulkRebook_location_movesRowsAndSkipsThoseAlreadyAtTheTarget() {
    // The core REQ-INV-036 contract: "Alle markieren" routinely marks rows that already sit at the
    // destination, and those must be skipped rather than fail the whole action.
    Location here = location(UUID.randomUUID(), "Area18");
    Location there = location(UUID.randomUUID(), "Lorville");
    InventoryItem moving = row(UUID.fromString("00000000-0000-0000-0000-000000000001"), here, 12.0);
    InventoryItem already =
        row(UUID.fromString("00000000-0000-0000-0000-000000000002"), there, 5.0);
    given(moving);
    given(already);
    when(locationRepository.findById(there.getId())).thenReturn(Optional.of(there));

    BulkRebookResultDto result =
        checkoutService.bulkRebook(
            toLocation(List.of(moving.getId(), already.getId()), there.getId()), OWNER_ID);

    assertEquals(1, result.rebooked(), "only the row not yet at the target moves");
    assertEquals(1, result.skipped(), "the row already at the target is skipped, not failed");
    // Exactly one new row was inserted and exactly the moved source removed.
    ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(saved.capture());
    assertEquals(there.getId(), saved.getValue().getLocation().getId());
    assertEquals(12.0, saved.getValue().getAmount(), 1e-9, "the whole quantity moves");
    verify(inventoryItemRepository).delete(moving);
    verify(inventoryItemRepository, never()).delete(already);
  }

  @Test
  void bulkRebook_location_withoutAnyTargetIsRejected() {
    // REQ-INV-025 parity: a target-less transfer would silently move nothing, so it must not be
    // reported as an all-skipped success.
    InventoryItem item = row(UUID.randomUUID(), location(UUID.randomUUID(), "Area18"), 3.0);
    given(item);

    assertThrows(
        BadRequestException.class,
        () -> checkoutService.bulkRebook(toLocation(List.of(item.getId()), null), OWNER_ID));
    verify(inventoryItemRepository, never()).save(any());
  }

  @Test
  void bulkRebook_location_carriesEarmarksOntoTheMovedRow() {
    // "Marken mitnehmen" (REQ-INV-027): a full move with no explicit deduct-from plan resolves to
    // "every slice in full", so the moved row must arrive carrying the source's earmark.
    Location here = location(UUID.randomUUID(), "Area18");
    Location there = location(UUID.randomUUID(), "Lorville");
    InventoryItem item = row(UUID.randomUUID(), here, 8.0);
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    InventoryAllocations.addJobOrder(item, order, 8.0, false);
    given(item);
    when(locationRepository.findById(there.getId())).thenReturn(Optional.of(there));

    checkoutService.bulkRebook(toLocation(List.of(item.getId()), there.getId()), OWNER_ID);

    ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(saved.capture());
    assertEquals(
        1,
        saved.getValue().getJobOrderAllocations().size(),
        "the moved row inherits the source's job-order slice");
    assertEquals(
        8.0,
        saved.getValue().getJobOrderAllocations().getFirst().getAmount(),
        1e-9,
        "the slice moves in full with the quantity");
  }

  @Test
  void bulkRebook_location_stampsTheResolvedOrgUnitOnTheMovedRow() {
    Location here = location(UUID.randomUUID(), "Area18");
    Location there = location(UUID.randomUUID(), "Lorville");
    InventoryItem item = row(UUID.randomUUID(), here, 4.0);
    given(item);
    when(locationRepository.findById(there.getId())).thenReturn(Optional.of(there));
    OrgUnit pool = new Squadron();
    pool.setId(UUID.randomUUID());
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any())).thenReturn(pool);

    checkoutService.bulkRebook(toLocation(List.of(item.getId()), there.getId()), OWNER_ID);

    ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(saved.capture());
    assertEquals(pool.getId(), saved.getValue().getOwningOrgUnit().getId());
  }

  // -------------------------------------------------------------------------
  // Abort paths — every non-"already at target" obstacle rolls the whole action back
  // -------------------------------------------------------------------------

  @Test
  void bulkRebook_unknownIdAbortsBeforeAnyWrite() {
    UUID missing = UUID.randomUUID();
    when(inventoryItemRepository.findByIdForRebook(missing)).thenReturn(Optional.empty());

    assertThrows(
        NotFoundException.class,
        () ->
            checkoutService.bulkRebook(toLocation(List.of(missing), UUID.randomUUID()), OWNER_ID));
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bulkRebook_foreignRowAbortsBeforeAnyWrite() {
    InventoryItem foreign = row(UUID.randomUUID(), location(UUID.randomUUID(), "Area18"), 2.0);
    foreign.setUser(user(UUID.randomUUID()));
    given(foreign);

    assertThrows(
        AccessDeniedException.class,
        () ->
            checkoutService.bulkRebook(
                toLocation(List.of(foreign.getId()), UUID.randomUUID()), OWNER_ID));
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  @Test
  void bulkRebook_personalizeAbortsWhenAnySelectedRowIsEarmarked() {
    // A personal row may never carry a job-order/mission link, so personalizing an earmarked row is
    // impossible rather than already-done: it aborts the whole action instead of being skipped.
    Location here = location(UUID.randomUUID(), "Area18");
    InventoryItem plain = row(UUID.fromString("00000000-0000-0000-0000-000000000001"), here, 3.0);
    InventoryItem earmarked =
        row(UUID.fromString("00000000-0000-0000-0000-000000000002"), here, 6.0);
    JobOrder order = new JobOrder();
    order.setId(UUID.randomUUID());
    InventoryAllocations.addJobOrder(earmarked, order, 6.0, false);
    given(plain);
    given(earmarked);

    BadRequestException thrown =
        assertThrows(
            BadRequestException.class,
            () ->
                checkoutService.bulkRebook(
                    personal(List.of(plain.getId(), earmarked.getId()), BulkRebookMode.PERSONALIZE),
                    OWNER_ID));
    assertTrue(
        thrown.getMessage().contains("1"),
        "the rejection names how many selected rows block it, not just the first");
    verify(inventoryItemRepository, never()).save(any());
    verify(inventoryItemRepository, never()).delete(any());
  }

  // -------------------------------------------------------------------------
  // Personal modes
  // -------------------------------------------------------------------------

  @Test
  void bulkRebook_personalizeSkipsRowsThatAreAlreadyPersonal() {
    Location here = location(UUID.randomUUID(), "Area18");
    InventoryItem shared = row(UUID.fromString("00000000-0000-0000-0000-000000000001"), here, 3.0);
    InventoryItem alreadyPersonal =
        row(UUID.fromString("00000000-0000-0000-0000-000000000002"), here, 7.0);
    alreadyPersonal.setPersonal(true);
    given(shared);
    given(alreadyPersonal);

    BulkRebookResultDto result =
        checkoutService.bulkRebook(
            personal(List.of(shared.getId(), alreadyPersonal.getId()), BulkRebookMode.PERSONALIZE),
            OWNER_ID);

    assertEquals(1, result.rebooked());
    assertEquals(1, result.skipped());
    ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(saved.capture());
    assertTrue(saved.getValue().getPersonal(), "the moved row lands personal");
    verify(inventoryItemRepository).delete(shared);
    verify(inventoryItemRepository, never()).delete(alreadyPersonal);
  }

  @Test
  void bulkRebook_depersonalizeMovesPersonalRowsIntoTheSharedPool() {
    Location here = location(UUID.randomUUID(), "Area18");
    InventoryItem personalRow = row(UUID.randomUUID(), here, 9.0);
    personalRow.setPersonal(true);
    given(personalRow);
    OrgUnit pool = new Squadron();
    pool.setId(UUID.randomUUID());
    when(ownerScopeService.resolveOrgUnitForPickerOutputNullable(any(), any())).thenReturn(pool);

    BulkRebookResultDto result =
        checkoutService.bulkRebook(
            personal(List.of(personalRow.getId()), BulkRebookMode.DEPERSONALIZE), OWNER_ID);

    assertEquals(1, result.rebooked());
    assertEquals(0, result.skipped());
    ArgumentCaptor<InventoryItem> saved = ArgumentCaptor.forClass(InventoryItem.class);
    verify(inventoryItemRepository).save(saved.capture());
    assertEquals(Boolean.FALSE, saved.getValue().getPersonal(), "the moved row lands shared");
    assertEquals(
        pool.getId(),
        saved.getValue().getOwningOrgUnit().getId(),
        "the shared row is stamped onto the picked pool");
  }

  // -------------------------------------------------------------------------
  // Audit (REQ-AUDIT-001)
  // -------------------------------------------------------------------------

  @Test
  void bulkRebook_recordsOneSummaryAuditEventWithModeAndCounts() {
    Location here = location(UUID.randomUUID(), "Area18");
    Location there = location(UUID.randomUUID(), "Lorville");
    InventoryItem moving = row(UUID.fromString("00000000-0000-0000-0000-000000000001"), here, 12.0);
    InventoryItem already =
        row(UUID.fromString("00000000-0000-0000-0000-000000000002"), there, 5.0);
    given(moving);
    given(already);
    when(locationRepository.findById(there.getId())).thenReturn(Optional.of(there));

    checkoutService.bulkRebook(
        toLocation(List.of(moving.getId(), already.getId()), there.getId()), OWNER_ID);

    ArgumentCaptor<CharSequence> details = ArgumentCaptor.forClass(CharSequence.class);
    verify(auditService)
        .record(
            eq(AuditEventType.INVENTORY_BULK_REBOOKED),
            isNull(),
            isNull(),
            eq(OWNER_ID),
            details.capture());
    String payload = details.getValue().toString();
    assertNotNull(payload);
    assertTrue(payload.contains("mode=LOCATION"), payload);
    assertTrue(payload.contains("rebooked=1"), payload);
    assertTrue(payload.contains("skipped=1"), payload);
  }

  @Test
  void bulkRebook_recordsNoAuditEventWhenEverythingWasSkipped() {
    // An all-skipped run mutated no state, so it must not appear in the audit log as a change.
    Location there = location(UUID.randomUUID(), "Lorville");
    InventoryItem already = row(UUID.randomUUID(), there, 5.0);
    given(already);
    when(locationRepository.findById(there.getId())).thenReturn(Optional.of(there));

    BulkRebookResultDto result =
        checkoutService.bulkRebook(toLocation(List.of(already.getId()), there.getId()), OWNER_ID);

    assertEquals(0, result.rebooked());
    assertEquals(1, result.skipped());
    verify(auditService, never()).record(any(), any(), any(), any(), any());
  }

  // -------------------------------------------------------------------------
  // Robustness
  // -------------------------------------------------------------------------

  @Test
  void bulkRebook_deduplicatesRepeatedIds() {
    // The bulk bar holds a Set, but the endpoint must not move a row twice if a duplicate reaches
    // it
    // — the second pass would operate on an already-deleted row.
    Location here = location(UUID.randomUUID(), "Area18");
    Location there = location(UUID.randomUUID(), "Lorville");
    InventoryItem item = row(UUID.randomUUID(), here, 4.0);
    given(item);
    when(locationRepository.findById(there.getId())).thenReturn(Optional.of(there));

    BulkRebookResultDto result =
        checkoutService.bulkRebook(
            toLocation(List.of(item.getId(), item.getId()), there.getId()), OWNER_ID);

    assertEquals(1, result.rebooked(), "the duplicate id is collapsed");
    verify(inventoryItemRepository).save(any(InventoryItem.class));
    verify(inventoryItemRepository).delete(item);
  }
}
