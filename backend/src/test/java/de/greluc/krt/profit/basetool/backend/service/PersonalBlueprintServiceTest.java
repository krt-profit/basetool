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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.greluc.krt.profit.basetool.backend.exception.BusinessConflictException;
import de.greluc.krt.profit.basetool.backend.exception.DuplicateEntityException;
import de.greluc.krt.profit.basetool.backend.mapper.PersonalBlueprintMapper;
import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintBatchResult;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintRecipeResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PersonalBlueprintUpdateRequest;
import de.greluc.krt.profit.basetool.backend.repository.GameItemRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.service.BlueprintProductService.ResolvedProduct;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

/** Unit tests for {@link PersonalBlueprintService}. */
@ExtendWith(MockitoExtension.class)
class PersonalBlueprintServiceTest {

  private static final String SUB = "owner-1";

  @Mock private PersonalBlueprintRepository repository;
  @Mock private PersonalBlueprintMapper mapper;
  @Mock private BlueprintProductService blueprintProductService;
  @Mock private GameItemRepository gameItemRepository;
  @Mock private DefaultBlueprintKeyService defaultBlueprintKeyService;

  private PersonalBlueprintService service;

  @BeforeEach
  void setUp() {
    service =
        new PersonalBlueprintService(
            repository,
            mapper,
            blueprintProductService,
            gameItemRepository,
            defaultBlueprintKeyService);
  }

  private static PersonalBlueprintResponse sampleResponse() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new PersonalBlueprintResponse(
        UUID.randomUUID(), "k", "Name", null, null, null, true, 0L, now, now);
  }

  @Test
  void listOwn_blankQuery_usesUnfilteredOwnerLookup() {
    PersonalBlueprint entity = PersonalBlueprint.builder().productKey("k").build();
    when(repository.findAllByOwnerSub(eq(SUB), any())).thenReturn(new PageImpl<>(List.of(entity)));
    when(mapper.toResponse(eq(entity), anyBoolean())).thenReturn(sampleResponse());

    var page = service.listOwn(SUB, "  ", PageRequest.of(0, 10));

    assertEquals(1, page.getTotalElements());
    verify(repository).findAllByOwnerSub(eq(SUB), any());
  }

  @Test
  void listOwn_withQuery_usesNameContainsFilter() {
    when(repository.findAllByOwnerSubAndProductNameContainingIgnoreCase(eq(SUB), eq("arc"), any()))
        .thenReturn(new PageImpl<>(List.of()));

    service.listOwn(SUB, " arc ", PageRequest.of(0, 10));

    verify(repository)
        .findAllByOwnerSubAndProductNameContainingIgnoreCase(eq(SUB), eq("arc"), any());
  }

  @Test
  void add_stampsResolvedProductAndSaves_whenNotOwned() {
    when(blueprintProductService.resolveByProductKey("k"))
        .thenReturn(Optional.of(new ResolvedProduct("k", "Arclight Pistol", null)));
    when(repository.existsByOwnerSubAndProductKey(SUB, "k")).thenReturn(false);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(mapper.toResponse(any(), anyBoolean())).thenReturn(sampleResponse());

    Instant acquired = Instant.parse("2026-02-03T00:00:00Z");
    service.add(SUB, new PersonalBlueprintCreateRequest("k", acquired, "note"));

    ArgumentCaptor<PersonalBlueprint> captor = ArgumentCaptor.forClass(PersonalBlueprint.class);
    verify(repository).save(captor.capture());
    PersonalBlueprint saved = captor.getValue();
    assertEquals(SUB, saved.getOwnerSub());
    assertEquals("k", saved.getProductKey());
    assertEquals("Arclight Pistol", saved.getProductName());
    assertEquals(acquired, saved.getAcquiredAt());
    assertEquals("note", saved.getNote());
  }

  @Test
  void add_attachesOutputItemReference_whenProductResolvesOne() {
    UUID itemId = UUID.randomUUID();
    GameItem ref = new GameItem();
    when(blueprintProductService.resolveByProductKey("k"))
        .thenReturn(Optional.of(new ResolvedProduct("k", "Name", itemId)));
    when(repository.existsByOwnerSubAndProductKey(SUB, "k")).thenReturn(false);
    when(gameItemRepository.getReferenceById(itemId)).thenReturn(ref);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(mapper.toResponse(any(), anyBoolean())).thenReturn(sampleResponse());

    service.add(SUB, new PersonalBlueprintCreateRequest("k", null, null));

    ArgumentCaptor<PersonalBlueprint> captor = ArgumentCaptor.forClass(PersonalBlueprint.class);
    verify(repository).save(captor.capture());
    assertSame(ref, captor.getValue().getOutputItem());
  }

  /**
   * REQ-OBS-004 / CWE-117: the admin add-on-behalf line echoes the RAW, client-supplied product key
   * (not the resolved product's), so a newline plus a fabricated level prefix would otherwise read
   * as a genuine second log line during triage. The value must go through {@code LogSafe} first.
   */
  @Test
  void addForUser_sanitisesTheClientSuppliedProductKeyBeforeLoggingIt() {
    String forged = "k\nERROR --- forged line";
    when(blueprintProductService.resolveByProductKey(forged))
        .thenReturn(Optional.of(new ResolvedProduct("k", "Arclight Pistol", null)));
    when(repository.existsByOwnerSubAndProductKey(SUB, "k")).thenReturn(false);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    when(mapper.toResponse(any(), anyBoolean())).thenReturn(sampleResponse());

    Logger logger = (Logger) LoggerFactory.getLogger(PersonalBlueprintService.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      service.addForUser(SUB, new PersonalBlueprintCreateRequest(forged, null, null));

      String line =
          appender.list.stream()
              .map(ILoggingEvent::getFormattedMessage)
              .filter(m -> m.contains("Admin added blueprint"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("expected the admin-add log line"));
      assertFalse(line.contains("\n"), "no control character may survive into the log: " + line);
      assertTrue(line.contains("k?ERROR"), "the value is kept, only neutralised: " + line);
    } finally {
      logger.detachAppender(appender);
    }
  }

  @Test
  void add_throwsDuplicate_whenAlreadyOwned() {
    when(blueprintProductService.resolveByProductKey("k"))
        .thenReturn(Optional.of(new ResolvedProduct("k", "Name", null)));
    when(repository.existsByOwnerSubAndProductKey(SUB, "k")).thenReturn(true);

    assertThrows(
        DuplicateEntityException.class,
        () -> service.add(SUB, new PersonalBlueprintCreateRequest("k", null, null)));
    verify(repository, never()).save(any());
  }

  @Test
  void add_throwsNotFound_whenProductKeyUnresolved() {
    when(blueprintProductService.resolveByProductKey("ghost")).thenReturn(Optional.empty());

    assertThrows(
        EntityNotFoundException.class,
        () -> service.add(SUB, new PersonalBlueprintCreateRequest("ghost", null, null)));
  }

  @Test
  void addBatch_countsAddedAlreadyOwnedAndUnresolved() {
    when(blueprintProductService.resolveByProductKey("a"))
        .thenReturn(Optional.of(new ResolvedProduct("a", "A", null)));
    when(blueprintProductService.resolveByProductKey("a-dup"))
        .thenReturn(Optional.of(new ResolvedProduct("a", "A", null)));
    when(blueprintProductService.resolveByProductKey("b"))
        .thenReturn(Optional.of(new ResolvedProduct("b", "B", null)));
    when(blueprintProductService.resolveByProductKey("z")).thenReturn(Optional.empty());
    when(repository.existsByOwnerSubAndProductKey(SUB, "a")).thenReturn(false);
    when(repository.existsByOwnerSubAndProductKey(SUB, "b")).thenReturn(true);
    when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

    PersonalBlueprintBatchResult result =
        service.addBatch(SUB, Arrays.asList("a", "b", "a-dup", "z", null));

    assertEquals(1, result.added());
    assertEquals(2, result.skippedAlreadyOwned());
    assertEquals(2, result.skippedUnresolved());
    verify(repository, org.mockito.Mockito.times(1)).save(any());
  }

  @Test
  void update_appliesChanges_whenVersionMatches() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity =
        PersonalBlueprint.builder().id(id).ownerSub(SUB).productKey("k").productName("N").build();
    entity.setVersion(3L);
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.of(entity));
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toResponse(eq(entity), anyBoolean())).thenReturn(sampleResponse());

    Instant acquired = Instant.parse("2026-03-04T00:00:00Z");
    service.update(SUB, id, new PersonalBlueprintUpdateRequest(acquired, "edited", 3L));

    assertEquals(acquired, entity.getAcquiredAt());
    assertEquals("edited", entity.getNote());
    verify(repository).save(entity);
  }

  @Test
  void update_throwsOptimisticLock_whenVersionStale() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity = PersonalBlueprint.builder().id(id).ownerSub(SUB).build();
    entity.setVersion(3L);
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.of(entity));

    assertThrows(
        ObjectOptimisticLockingFailureException.class,
        () -> service.update(SUB, id, new PersonalBlueprintUpdateRequest(null, null, 2L)));
    verify(repository, never()).save(any());
  }

  @Test
  void update_throwsNotFound_whenMissingOrForeign() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.empty());

    assertThrows(
        EntityNotFoundException.class,
        () -> service.update(SUB, id, new PersonalBlueprintUpdateRequest(null, null, 1L)));
  }

  @Test
  void delete_removesOwnedEntity() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity = PersonalBlueprint.builder().id(id).ownerSub(SUB).build();
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.of(entity));

    service.delete(SUB, id);

    verify(repository).delete(entity);
  }

  @Test
  void delete_throwsNotFound_whenMissingOrForeign() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.empty());

    assertThrows(EntityNotFoundException.class, () -> service.delete(SUB, id));
  }

  @Test
  void delete_throwsConflict_andDoesNotDelete_whenEntryIsDefault() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity =
        PersonalBlueprint.builder().id(id).ownerSub(SUB).productKey("s-38 pistol").build();
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.of(entity));
    when(defaultBlueprintKeyService.isDefault("s-38 pistol")).thenReturn(true);

    assertThrows(BusinessConflictException.class, () -> service.delete(SUB, id));
    verify(repository, never()).delete(any());
  }

  @Test
  void deleteAllOwn_delegatesToOwnerScopedBulkDeleteAndReturnsCount() {
    when(repository.deleteRemovableByOwnerSub(SUB)).thenReturn(4);

    int removed = service.deleteAllOwn(SUB);

    assertEquals(4, removed);
    verify(repository).deleteRemovableByOwnerSub(SUB);
  }

  @Test
  void recipeForOwn_loadsOwnedThenDelegatesToProductService() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity =
        PersonalBlueprint.builder()
            .id(id)
            .ownerSub(SUB)
            .productKey("k")
            .productName("Name")
            .build();
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.of(entity));
    PersonalBlueprintRecipeResponse recipe =
        new PersonalBlueprintRecipeResponse("Name", 2, List.of(), List.of());
    when(blueprintProductService.resolveRecipe("k")).thenReturn(Optional.of(recipe));

    assertSame(recipe, service.recipeForOwn(SUB, id));
  }

  @Test
  void recipeForOwn_fallsBackToEmptyGraphWithOwnedName_whenProductUnresolved() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity =
        PersonalBlueprint.builder()
            .id(id)
            .ownerSub(SUB)
            .productKey("k")
            .productName("Name")
            .build();
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.of(entity));
    when(blueprintProductService.resolveRecipe("k")).thenReturn(Optional.empty());

    PersonalBlueprintRecipeResponse result = service.recipeForOwn(SUB, id);

    assertEquals("Name", result.productName());
    assertEquals(0, result.variantCount());
    assertTrue(result.requirementGroups().isEmpty());
    assertTrue(result.ingredients().isEmpty());
  }

  @Test
  void recipeForOwn_throwsNotFound_whenMissingOrForeign() {
    UUID id = UUID.randomUUID();
    when(repository.findByIdAndOwnerSub(id, SUB)).thenReturn(Optional.empty());

    assertThrows(EntityNotFoundException.class, () -> service.recipeForOwn(SUB, id));
  }

  // --------------------------------------------------------------- admin variants --

  @Test
  void listForUser_delegatesToOwnerLookupWithTargetSub() {
    when(repository.findAllByOwnerSub(eq("target"), any())).thenReturn(new PageImpl<>(List.of()));

    service.listForUser("target", null, PageRequest.of(0, 10));

    verify(repository).findAllByOwnerSub(eq("target"), any());
  }

  @Test
  void updateForUser_appliesByIdAlone_whenVersionMatches() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity =
        PersonalBlueprint.builder().id(id).ownerSub("other-user").productKey("k").build();
    entity.setVersion(5L);
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(repository.save(entity)).thenReturn(entity);
    when(mapper.toResponse(eq(entity), anyBoolean())).thenReturn(sampleResponse());

    service.updateForUser(id, new PersonalBlueprintUpdateRequest(null, "edited", 5L));

    assertEquals("edited", entity.getNote());
    verify(repository).save(entity);
  }

  @Test
  void updateForUser_throwsNotFound_whenIdUnknown() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThrows(
        EntityNotFoundException.class,
        () -> service.updateForUser(id, new PersonalBlueprintUpdateRequest(null, null, 1L)));
  }

  @Test
  void deleteForUser_removesById() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity = PersonalBlueprint.builder().id(id).ownerSub("other-user").build();
    when(repository.findById(id)).thenReturn(Optional.of(entity));

    service.deleteForUser(id);

    verify(repository).delete(entity);
  }

  @Test
  void deleteForUser_throwsNotFound_whenIdUnknown() {
    UUID id = UUID.randomUUID();
    when(repository.findById(id)).thenReturn(Optional.empty());

    assertThrows(EntityNotFoundException.class, () -> service.deleteForUser(id));
  }

  @Test
  void deleteForUser_throwsConflict_andDoesNotDelete_whenEntryIsDefault() {
    UUID id = UUID.randomUUID();
    PersonalBlueprint entity =
        PersonalBlueprint.builder().id(id).ownerSub("other-user").productKey("p4-ar rifle").build();
    when(repository.findById(id)).thenReturn(Optional.of(entity));
    when(defaultBlueprintKeyService.isDefault("p4-ar rifle")).thenReturn(true);

    assertThrows(BusinessConflictException.class, () -> service.deleteForUser(id));
    verify(repository, never()).delete(any());
  }

  @Test
  void deleteAllForAllUsers_delegatesToGlobalBulkDeleteAndReturnsCount() {
    when(repository.deleteAllRemovable()).thenReturn(7);

    int removed = service.deleteAllForAllUsers();

    assertEquals(7, removed);
    verify(repository).deleteAllRemovable();
  }
}
