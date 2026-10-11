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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInPolicy;
import de.greluc.krt.profit.basetool.backend.inventory.api.BookInRule;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockChangeObserver;
import de.greluc.krt.profit.basetool.backend.inventory.api.StockLot;
import de.greluc.krt.profit.basetool.backend.model.InventoryItem;
import de.greluc.krt.profit.basetool.backend.model.Location;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.InventoryItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

/**
 * Pins the ADR-0229 lot-lock protocol as the Lager's stock commands take it: the key derivation is
 * unchanged by its move out of the exchange, the locks go in ascending key order, once per lot, and
 * a book-in records its audit event.
 */
@ExtendWith(MockitoExtension.class)
class InventoryStockCommandsTest {

  private static final UUID MEMBER = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Mock private InventoryItemRepository inventoryRepository;
  @Mock private UserRepository userRepository;
  @Mock private InventoryCheckoutService checkoutService;
  @Mock private InventoryStolenMarkService stolenMarkService;
  @Mock private AuditRecorder auditRecorder;
  @Mock private BookInPolicy bookInPolicy;
  @Mock private StockChangeObserver stockChangeObserver;

  @InjectMocks private InventoryStockCommands commands;

  @Test
  void theLockKeyIsTheFirstEightBytesOfTheSha256UnderTheLotPrefix() {
    assertThat(InventoryStockCommands.lotLockKey(MEMBER, "m:x|l:y|q:0|s:0"))
        .isEqualTo(-7584263154457246965L);
    assertThat(InventoryStockCommands.lotLockKey(MEMBER, "i:z|l:y|q:0|s:1"))
        .isEqualTo(2346076593993176679L);
  }

  @Test
  void theLocksAreTakenOncePerLotInAscendingKeyOrder() {
    commands.lockLots(MEMBER, List.of("i:z|l:y|q:0|s:1", "m:x|l:y|q:0|s:0", "i:z|l:y|q:0|s:1"));

    InOrder order = inOrder(inventoryRepository);
    order.verify(inventoryRepository).lockExchangeLot(-7584263154457246965L);
    order.verify(inventoryRepository).lockExchangeLot(2346076593993176679L);
    verify(inventoryRepository, times(2)).lockExchangeLot(anyLong());
  }

  @Test
  void aBookInCreatesAPersonalRowAndRecordsIt() {
    User user = new User();
    user.setId(MEMBER);
    when(userRepository.findById(MEMBER)).thenReturn(Optional.of(user));
    when(inventoryRepository.save(any(InventoryItem.class)))
        .thenAnswer(
            invocation -> {
              InventoryItem item = invocation.getArgument(0);
              item.setId(UUID.randomUUID());
              return item;
            });
    Material material = new Material();
    material.setId(UUID.randomUUID());
    material.setName("Quantainium");
    Location location = new Location();
    location.setId(UUID.randomUUID());
    location.setName("Lorville");

    commands.bookIn(MEMBER, new StockLot(material, null, location, null, false), 2.5);

    verify(auditRecorder)
        .record(eq(AuditEventType.INVENTORY_ITEM_CREATED), any(), any(), eq(MEMBER), any());
    verify(checkoutService).mergeStockIfRequested(any(InventoryItem.class), eq(false));
  }

  private static final UUID OTHER = UUID.randomUUID();

  @Test
  void theCallersOwnLagerPassesWithoutAskingThePolicy() {
    for (BookInRule rule :
        List.of(BookInRule.lager(), BookInRule.production(), BookInRule.refineryStore())) {
      assertThatCode(() -> commands.requireBookIn(MEMBER, MEMBER, false, true, rule))
          .doesNotThrowAnyException();
    }
    verify(bookInPolicy, never()).mayBookInFor(any());
  }

  @Test
  void anotherMembersLagerNeedsTheSharedOwnerCheck() {
    when(bookInPolicy.mayBookInFor(OTHER)).thenReturn(false);

    assertThatThrownBy(
            () -> commands.requireBookIn(OTHER, MEMBER, true, false, BookInRule.production()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("You are not allowed to book produced stock in for this user");
    assertThatThrownBy(
            () -> commands.requireBookIn(OTHER, MEMBER, true, false, BookInRule.refineryStore()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("Access denied: You are not allowed to store refinery output for other users");
  }

  @Test
  void aPersonalBookInForAnotherMemberIsRefusedExceptAtTheRefineryStore() {
    when(bookInPolicy.mayBookInFor(OTHER)).thenReturn(true);

    assertThatThrownBy(() -> commands.requireBookIn(OTHER, MEMBER, false, true, BookInRule.lager()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage("You are not allowed to create personal inventory items for other users");
    assertThatThrownBy(
            () -> commands.requireBookIn(OTHER, MEMBER, false, true, BookInRule.production()))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessage(
            "You are not allowed to book produced stock into another user's personal inventory");
    assertThatCode(
            () -> commands.requireBookIn(OTHER, MEMBER, false, true, BookInRule.refineryStore()))
        .doesNotThrowAnyException();
  }

  @Test
  void aBookInWithoutANamedOwnerNeedsALogisticianOnlyAtTheRefineryStore() {
    assertThatThrownBy(
            () -> commands.requireBookIn(null, MEMBER, false, false, BookInRule.refineryStore()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatCode(
            () -> commands.requireBookIn(null, MEMBER, true, false, BookInRule.refineryStore()))
        .doesNotThrowAnyException();
    assertThatCode(() -> commands.requireBookIn(null, MEMBER, false, true, BookInRule.production()))
        .doesNotThrowAnyException();
    verify(bookInPolicy, never()).mayBookInFor(any());
  }
}
