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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.exception.NotFoundException;
import de.greluc.krt.profit.basetool.backend.mapper.DefaultBlueprintMapper;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.model.DefaultBlueprint;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.dto.DefaultBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.repository.DefaultBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

/** Unit tests for {@link DefaultBlueprintService}. */
@ExtendWith(MockitoExtension.class)
class DefaultBlueprintServiceTest {

  @Mock private DefaultBlueprintRepository repository;
  @Mock private DefaultBlueprintMapper mapper;
  @Mock private BlueprintProductService blueprintProductService;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private DefaultBlueprintProvisioningService provisioningService;
  @Mock private DefaultBlueprintKeyService keyService;
  @Mock private AuditService auditService;

  @InjectMocks private DefaultBlueprintService service;

  private static DefaultBlueprintResponse sampleResponse() {
    return new DefaultBlueprintResponse(
        UUID.randomUUID(), "k", "Name", null, null, "system", Instant.EPOCH, 0L);
  }

  @Test
  void list_returnsMappedEntriesSortedByName() {
    DefaultBlueprint entity = new DefaultBlueprint();
    when(repository.findAll(any(Sort.class))).thenReturn(java.util.List.of(entity));
    when(mapper.toResponse(entity)).thenReturn(sampleResponse());

    assertEquals(1, service.list().size());
    verify(repository).findAll(any(Sort.class));
  }

  @Test
  void add_resolvesStampsRefreshesCacheAndGrantsToEveryone() {
    when(blueprintProductService.resolveByProductKey("k"))
        .thenReturn(Optional.of(new ResolvedProduct("k", "S-38 Pistol", null)));
    when(repository.existsByProductKey("k")).thenReturn(false);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(mapper.toResponse(any())).thenReturn(sampleResponse());

    service.add("k", "admin-sub");

    ArgumentCaptor<DefaultBlueprint> captor = ArgumentCaptor.forClass(DefaultBlueprint.class);
    verify(repository).save(captor.capture());
    DefaultBlueprint saved = captor.getValue();
    assertEquals("k", saved.getProductKey());
    assertEquals("S-38 Pistol", saved.getProductName());
    assertEquals("admin-sub", saved.getCreatedBy());
    verify(keyService).refresh();
    verify(provisioningService).grantDefaultsToAllUsers();
    verify(auditService)
        .record(
            eq(AuditEventType.BLUEPRINT_DEFAULT_ADDED), any(), eq("S-38 Pistol"), isNull(), any());
  }

  @Test
  void add_attachesOutputItemReference_whenProductResolvesOne() {
    UUID itemId = UUID.randomUUID();
    GameItem ref = new GameItem();
    when(blueprintProductService.resolveByProductKey("k"))
        .thenReturn(Optional.of(new ResolvedProduct("k", "Name", itemId)));
    when(repository.existsByProductKey("k")).thenReturn(false);
    when(gameItemRepository.getReferenceById(itemId)).thenReturn(ref);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(mapper.toResponse(any())).thenReturn(sampleResponse());

    service.add("k", "admin-sub");

    ArgumentCaptor<DefaultBlueprint> captor = ArgumentCaptor.forClass(DefaultBlueprint.class);
    verify(repository).save(captor.capture());
    assertSame(ref, captor.getValue().getOutputItem());
  }

  @Test
  void add_throwsDuplicate_andDoesNotGrant_whenAlreadyDefault() {
    when(blueprintProductService.resolveByProductKey("k"))
        .thenReturn(Optional.of(new ResolvedProduct("k", "Name", null)));
    when(repository.existsByProductKey("k")).thenReturn(true);

    assertThrows(DuplicateEntityException.class, () -> service.add("k", "admin-sub"));
    verify(repository, never()).save(any());
    verify(provisioningService, never()).grantDefaultsToAllUsers();
  }

  @Test
  void add_throwsNotFound_whenProductKeyUnresolved() {
    when(blueprintProductService.resolveByProductKey("ghost")).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.add("ghost", "admin-sub"));
    verify(repository, never()).save(any());
  }

  @Test
  void remove_deletesAndRefreshesCache() {
    UUID id = UUID.randomUUID();
    DefaultBlueprint entity = new DefaultBlueprint();
    entity.setProductKey("k");
    when(repository.findById(id)).thenReturn(Optional.of(entity));

    service.remove(id);

    verify(repository).delete(entity);
    verify(keyService).refresh();
    verify(auditService)
        .record(eq(AuditEventType.BLUEPRINT_DEFAULT_REMOVED), eq(id), any(), isNull(), any());
  }

  @Test
  void remove_throwsNotFound_whenUnknown() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThrows(NotFoundException.class, () -> service.remove(id));
    verify(repository, never()).delete(any());
  }
}
