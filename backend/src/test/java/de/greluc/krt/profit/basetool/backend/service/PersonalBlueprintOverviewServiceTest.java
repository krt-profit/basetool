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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.PersonalBlueprint;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintOverviewEntryDto;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintOverviewOwnerDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Mockito unit tests for {@link PersonalBlueprintOverviewService} — the in-Java
 * <em>variant-family</em> aggregation (a base item and its cosmetic variants collapse onto one
 * availability row) and the family-aware, scope-driven owner drill-down (the family key is expanded
 * to its product keys via a mocked {@link BlueprintVariantFamilyCatalog}, then owners are fetched
 * by that product-key set). The real {@link BlueprintVariantFamilyResolver} is wired in so the
 * family grouping runs end-to-end; the oversight {@link ScopePredicate} is supplied by a mocked
 * {@link OwnerScopeService} (its persona-specific construction is covered by {@code
 * OwnerScopeServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class PersonalBlueprintOverviewServiceTest {

  @Mock private OwnerScopeService ownerScopeService;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private PersonalBlueprintRepository personalBlueprintRepository;
  @Mock private UserRepository userRepository;
  @Mock private BlueprintVariantFamilyCatalog familyCatalog;

  private PersonalBlueprintOverviewService service;

  @BeforeEach
  void setUp() {
    service =
        new PersonalBlueprintOverviewService(
            ownerScopeService,
            orgUnitMembershipRepository,
            personalBlueprintRepository,
            userRepository,
            new BlueprintVariantFamilyResolver(
                new BlueprintNameNormalizer(), new BlueprintVariantAliasOverrides()),
            familyCatalog);
  }

  private static final UUID USER_1 = UUID.randomUUID();
  private static final UUID USER_2 = UUID.randomUUID();
  private static final UUID ORG_A = UUID.randomUUID();

  private static PersonalBlueprint bp(String key, String name, UUID owner) {
    PersonalBlueprint b = new PersonalBlueprint();
    b.setProductKey(key);
    b.setProductName(name);
    b.setOwnerUserId(owner);
    return b;
  }

  private static BlueprintOwnerProduct op(String name, UUID owner) {
    return new BlueprintOwnerProduct(owner, name);
  }

  private static User user(UUID id, String displayName) {
    User u = new User();
    u.setId(id);
    u.setDisplayName(displayName);
    return u;
  }

  private static Pageable byName() {
    return PageRequest.of(0, 50, Sort.by("productName"));
  }

  @Test
  void list_adminAllScope_aggregatesEveryOwner_includingOrgUnitLess() {
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(personalBlueprintRepository.findAllDistinctOwnerUserIds())
        .thenReturn(Set.of(USER_1, USER_2));
    when(personalBlueprintRepository.findOwnerProductByOwnerUserIdIn(any()))
        .thenReturn(
            List.of(op("Aurora MR", USER_1), op("Aurora MR", USER_2), op("Cutlass Black", USER_1)));

    Page<BlueprintOverviewEntryDto> page = service.listAvailableBlueprints(byName(), null);

    assertEquals(2, page.getTotalElements());
    assertEquals("Aurora MR", page.getContent().get(0).productName());
    assertEquals(2L, page.getContent().get(0).ownerCount());
    assertEquals("Cutlass Black", page.getContent().get(1).productName());
    assertEquals(1L, page.getContent().get(1).ownerCount());
    verify(orgUnitMembershipRepository, never()).findDistinctUserIdsByOrgUnitIdIn(any());
  }

  @Test
  void list_collapsesCosmeticVariantsIntoOneFamilyRow() {
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(personalBlueprintRepository.findAllDistinctOwnerUserIds())
        .thenReturn(Set.of(USER_1, USER_2));
    when(personalBlueprintRepository.findOwnerProductByOwnerUserIdIn(any()))
        .thenReturn(
            List.of(op("Fresnel Energy LMG", USER_1), op("Fresnel \"Molten\" Energy LMG", USER_2)));

    Page<BlueprintOverviewEntryDto> page = service.listAvailableBlueprints(byName(), null);

    assertEquals(1, page.getTotalElements());
    BlueprintOverviewEntryDto row = page.getContent().get(0);
    assertEquals("fresnel energy lmg", row.productKey());
    assertEquals("Fresnel Energy LMG", row.productName());
    assertEquals(2L, row.ownerCount());
  }

  @Test
  void list_pinnedScope_resolvesMembersOfPinnedOrgUnit() {
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, ORG_A, Set.of()));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_A)))
        .thenReturn(Set.of(USER_1));
    when(personalBlueprintRepository.findOwnerProductByOwnerUserIdIn(any()))
        .thenReturn(List.of(op("Aurora MR", USER_1)));

    Page<BlueprintOverviewEntryDto> page = service.listAvailableBlueprints(byName(), null);

    assertEquals(1, page.getTotalElements());
    verify(orgUnitMembershipRepository).findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_A));
  }

  @Test
  void list_emptyOversight_returnsEmptyWithoutQueryingBlueprints() {
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));

    Page<BlueprintOverviewEntryDto> page = service.listAvailableBlueprints(byName(), null);

    assertTrue(page.getContent().isEmpty());
    assertEquals(0, page.getTotalElements());
    verify(personalBlueprintRepository, never()).findOwnerProductByOwnerUserIdIn(any());
  }

  @Test
  void list_descendingSort_reversesByName() {
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(personalBlueprintRepository.findAllDistinctOwnerUserIds()).thenReturn(Set.of(USER_1));
    when(personalBlueprintRepository.findOwnerProductByOwnerUserIdIn(any()))
        .thenReturn(List.of(op("Aurora MR", USER_1), op("Cutlass Black", USER_1)));

    Page<BlueprintOverviewEntryDto> page =
        service.listAvailableBlueprints(
            PageRequest.of(0, 50, Sort.by("productName").descending()), null);

    assertEquals("Cutlass Black", page.getContent().get(0).productName());
    assertEquals("Aurora MR", page.getContent().get(1).productName());
  }

  @Test
  void list_search_filtersByProductNameCaseInsensitive_beforePagination() {
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(personalBlueprintRepository.findAllDistinctOwnerUserIds()).thenReturn(Set.of(USER_1));
    when(personalBlueprintRepository.findOwnerProductByOwnerUserIdIn(any()))
        .thenReturn(
            List.of(op("Aurora MR", USER_1), op("Scattergun", USER_1), op("Caterpillar", USER_1)));

    Page<BlueprintOverviewEntryDto> page =
        service.listAvailableBlueprints(PageRequest.of(0, 1, Sort.by("productName")), "CAT");

    assertEquals(2, page.getTotalElements());
    assertEquals(1, page.getContent().size());
    assertEquals("Caterpillar", page.getContent().get(0).productName());
  }

  @Test
  void owners_scoped_expandsFamilyAndListsVariantOwnersByDisplayNameSorted() {
    when(familyCatalog.familyIndex())
        .thenReturn(Map.of("custodian smg", Set.of("custodian smg", "custodian \"midnight\" smg")));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(ORG_A)));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_A)))
        .thenReturn(Set.of(USER_1, USER_2));
    when(personalBlueprintRepository.findAllByProductKeyInAndOwnerUserIdIn(any(), any()))
        .thenReturn(
            List.of(
                bp("custodian smg", "Custodian SMG", USER_1),
                bp("custodian \"midnight\" smg", "Custodian \"Midnight\" SMG", USER_2)));
    when(userRepository.findAllById(any()))
        .thenReturn(List.of(user(USER_1, "Bravo"), user(USER_2, "Alpha")));

    List<BlueprintOverviewOwnerDto> owners = service.listOwnersForProduct("custodian smg");

    assertEquals(
        List.of("Alpha", "Bravo"),
        owners.stream().map(BlueprintOverviewOwnerDto::ownerName).toList());
  }

  @Test
  void owners_unknownFamily_returnsEmptyWithoutScopeOrQueries() {
    when(familyCatalog.familyIndex()).thenReturn(Map.of());

    assertTrue(service.listOwnersForProduct("gone").isEmpty());
    verify(ownerScopeService, never()).currentOversightScope();
    verify(personalBlueprintRepository, never())
        .findAllByProductKeyInAndOwnerUserIdIn(any(), any());
    verify(userRepository, never()).findAllById(any());
  }

  @Test
  void owners_emptyOversight_returnsEmptyWithoutOwnerQueries() {
    when(familyCatalog.familyIndex()).thenReturn(Map.of("aurora mr", Set.of("aurora mr")));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of()));

    assertTrue(service.listOwnersForProduct("aurora mr").isEmpty());
    verify(personalBlueprintRepository, never())
        .findAllByProductKeyInAndOwnerUserIdIn(any(), any());
    verify(userRepository, never()).findAllById(any());
  }

  @Test
  void owners_nobodyInScopeOwnsFamily_returnsEmpty() {
    when(familyCatalog.familyIndex()).thenReturn(Map.of("aurora mr", Set.of("aurora mr")));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(ORG_A)));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_A)))
        .thenReturn(Set.of(USER_1));
    when(personalBlueprintRepository.findAllByProductKeyInAndOwnerUserIdIn(any(), any()))
        .thenReturn(List.of());

    assertTrue(service.listOwnersForProduct("aurora mr").isEmpty());
    verify(userRepository, never()).findAllById(any());
  }

  @Test
  void owners_adminAllScope_listsOwnersAcrossEveryOrgUnit_byFamilyProductKeys() {
    when(familyCatalog.familyIndex()).thenReturn(Map.of("aurora mr", Set.of("aurora mr")));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(true, null, Set.of()));
    when(personalBlueprintRepository.findAllByProductKeyIn(any()))
        .thenReturn(
            List.of(bp("aurora mr", "Aurora MR", USER_1), bp("aurora mr", "Aurora MR", USER_2)));
    when(userRepository.findAllById(any()))
        .thenReturn(List.of(user(USER_1, "Bravo"), user(USER_2, "Alpha")));

    List<BlueprintOverviewOwnerDto> owners = service.listOwnersForProduct("aurora mr");

    assertEquals(
        List.of("Alpha", "Bravo"),
        owners.stream().map(BlueprintOverviewOwnerDto::ownerName).toList());
    assertTrue(owners.stream().allMatch(BlueprintOverviewOwnerDto::orgUnitMember));
    verify(orgUnitMembershipRepository, never()).findDistinctUserIdsByOrgUnitIdIn(any());
    verify(personalBlueprintRepository, never()).findAllDistinctOwnerUserIds();
    verify(personalBlueprintRepository, never())
        .findAllByProductKeyInAndOwnerUserIdIn(any(), any());
  }

  @Test
  void list_globalSharerOutsideOversight_isUnionedIntoOwnerSet() {
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, ORG_A, Set.of()));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_A)))
        .thenReturn(Set.of(USER_1));
    when(userRepository.findIdsBySharingBlueprintsGlobally()).thenReturn(Set.of(USER_2));
    ArgumentCaptor<Collection<UUID>> ownerUserIds = ArgumentCaptor.captor();
    when(personalBlueprintRepository.findOwnerProductByOwnerUserIdIn(ownerUserIds.capture()))
        .thenReturn(List.of(op("Aurora MR", USER_1), op("Aurora MR", USER_2)));

    Page<BlueprintOverviewEntryDto> page = service.listAvailableBlueprints(byName(), null);

    assertEquals(1, page.getTotalElements());
    assertEquals(2L, page.getContent().get(0).ownerCount());
    assertTrue(ownerUserIds.getValue().contains(USER_1));
    assertTrue(ownerUserIds.getValue().contains(USER_2));
  }

  @Test
  void owners_globalSharerOutsideOversight_appearsInDrillDown() {
    when(familyCatalog.familyIndex()).thenReturn(Map.of("aurora mr", Set.of("aurora mr")));
    when(ownerScopeService.currentOversightScope())
        .thenReturn(new ScopePredicate(false, null, Set.of(ORG_A)));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_A)))
        .thenReturn(Set.of(USER_1));
    when(userRepository.findIdsBySharingBlueprintsGlobally()).thenReturn(Set.of(USER_2));
    ArgumentCaptor<Collection<UUID>> ownerUserIds = ArgumentCaptor.captor();
    when(personalBlueprintRepository.findAllByProductKeyInAndOwnerUserIdIn(
            any(), ownerUserIds.capture()))
        .thenReturn(
            List.of(bp("aurora mr", "Aurora MR", USER_1), bp("aurora mr", "Aurora MR", USER_2)));
    when(userRepository.findAllById(any()))
        .thenReturn(List.of(user(USER_1, "Bravo"), user(USER_2, "Alpha")));

    List<BlueprintOverviewOwnerDto> owners = service.listOwnersForProduct("aurora mr");

    assertEquals(
        List.of("Alpha", "Bravo"),
        owners.stream().map(BlueprintOverviewOwnerDto::ownerName).toList());
    assertTrue(ownerUserIds.getValue().contains(USER_2));
    BlueprintOverviewOwnerDto alpha = ownerByName(owners, "Alpha");
    BlueprintOverviewOwnerDto bravo = ownerByName(owners, "Bravo");
    assertFalse(alpha.orgUnitMember(), "the global sharer is not an oversight member");
    assertTrue(bravo.orgUnitMember(), "the oversight member is flagged a member");
  }

  /** Finds the owner row with the given display name, failing the test when absent. */
  private static BlueprintOverviewOwnerDto ownerByName(
      List<BlueprintOverviewOwnerDto> owners, String name) {
    return owners.stream().filter(o -> o.ownerName().equals(name)).findFirst().orElseThrow();
  }
}
