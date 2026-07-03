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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderBlueprintOwnerDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.backend.model.dto.JobOrderRequiredBlueprintDto;
import de.greluc.krt.profit.basetool.backend.model.projection.BlueprintOwnerProduct;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.PersonalBlueprintRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Mockito unit tests for {@link JobOrderItemBlueprintOwnersService}: the <em>variant-family</em>
 * matching of an item order's required lines against members' owned blueprints (a base item counts
 * owners of its cosmetic variants and vice-versa), the magazine exclusion (an ammo container never
 * fulfils a weapon line and a member's magazine is never shown), the per-family coverage counting,
 * the per-owner surfacing of the concrete owned variant, and the responsible-org-unit-only member
 * resolution. The real {@link BlueprintVariantFamilyResolver} (with the real {@link
 * BlueprintNameNormalizer} and {@link BlueprintVariantAliasOverrides}) is wired in so the family
 * matching is exercised end-to-end; the surrounding members-only authorization is covered by {@code
 * OwnerScopeServiceTest} and {@code JobOrderControllerTest}.
 */
@ExtendWith(MockitoExtension.class)
class JobOrderItemBlueprintOwnersServiceTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private OrgUnitMembershipRepository orgUnitMembershipRepository;
  @Mock private PersonalBlueprintRepository personalBlueprintRepository;
  @Mock private UserRepository userRepository;

  private JobOrderItemBlueprintOwnersService service;

  @BeforeEach
  void setUp() {
    // Wire the service by hand (not @InjectMocks) so the real variant-family resolver drives the
    // family matching end-to-end; constructed here, after Mockito has populated the mocks.
    service =
        new JobOrderItemBlueprintOwnersService(
            jobOrderRepository,
            orgUnitMembershipRepository,
            personalBlueprintRepository,
            userRepository,
            new BlueprintVariantFamilyResolver(
                new BlueprintNameNormalizer(), new BlueprintVariantAliasOverrides()));
  }

  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID ORG_ID = UUID.randomUUID();
  private static final UUID ALICE = UUID.randomUUID();
  private static final UUID BERND = UUID.randomUUID();
  private static final UUID CARLA = UUID.randomUUID();

  private static Blueprint blueprint(String outputName) {
    Blueprint b = new Blueprint();
    b.setOutputName(outputName);
    return b;
  }

  private static GameItem gameItem(String name) {
    GameItem g = new GameItem();
    g.setName(name);
    return g;
  }

  private static JobOrderItem item(String outputName, String displayName) {
    return JobOrderItem.builder()
        .blueprint(blueprint(outputName))
        .gameItem(gameItem(displayName))
        .amount(1)
        .build();
  }

  private static JobOrder order(JobOrderItem... items) {
    Squadron responsible = new Squadron();
    responsible.setId(ORG_ID);
    return JobOrder.builder()
        .id(ORDER_ID)
        .responsibleOrgUnit(responsible)
        .items(new LinkedHashSet<>(List.of(items)))
        .build();
  }

  /**
   * Like {@link #order(JobOrderItem...)} but with the per-order variant-counting toggle
   * <em>off</em> (REQ-ORDERS-021), so coverage matches blueprints by exact normalized name instead
   * of family key.
   */
  private static JobOrder orderWithoutVariants(JobOrderItem... items) {
    Squadron responsible = new Squadron();
    responsible.setId(ORG_ID);
    return JobOrder.builder()
        .id(ORDER_ID)
        .responsibleOrgUnit(responsible)
        .countBlueprintsWithVariants(false)
        .items(new LinkedHashSet<>(List.of(items)))
        .build();
  }

  /**
   * Builds an owned-blueprint row; {@code productName} is the concrete blueprint the member owns.
   */
  private static BlueprintOwnerProduct owned(UUID owner, String productName) {
    return new BlueprintOwnerProduct(owner.toString(), productName);
  }

  private static User user(UUID id, String displayName) {
    User u = new User();
    u.setId(id);
    u.setDisplayName(displayName);
    return u;
  }

  @Test
  void itemOrderWithNoBlueprintProducts_returnsEmptyWithoutMemberLookup() {
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order()));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    assertTrue(result.requiredBlueprints().isEmpty());
    assertTrue(result.owners().isEmpty());
    verify(orgUnitMembershipRepository, never()).findDistinctUserIdsByOrgUnitIdIn(any());
    verify(personalBlueprintRepository, never()).findOwnerProductByOwnerSubIn(any());
  }

  @Test
  void groupsOwnersAndCountsCoverage_matchingByVariantFamily() {
    // Two required items; the blueprint output names are deliberately messy (extra spacing, case)
    // so
    // the run exercises the normalizer. The required display label comes from the requested game
    // item, while each owner's tag shows the concrete blueprint they actually own.
    JobOrder order =
        order(item("Aurora   MR", "Aurora MR Ship"), item("Cutlass Black", "Cutlass Black Ship"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE, BERND));
    // Alice owns both required products; Bernd owns only Aurora.
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(any()))
        .thenReturn(
            List.of(
                owned(ALICE, "Aurora MR"),
                owned(ALICE, "Cutlass Black"),
                owned(BERND, "Aurora MR")));
    when(userRepository.findAllById(any()))
        .thenReturn(List.of(user(ALICE, "Alice"), user(BERND, "Bernd")));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    // Coverage: Aurora owned by 2, Cutlass by 1. Sorted by display name (Aurora < Cutlass).
    List<JobOrderRequiredBlueprintDto> coverage = result.requiredBlueprints();
    assertEquals(2, coverage.size());
    assertEquals("Aurora MR Ship", coverage.get(0).productName());
    assertEquals("aurora mr", coverage.get(0).productKey());
    assertEquals(2, coverage.get(0).ownerCount());
    assertTrue(coverage.get(0).variantInclusive());
    assertEquals("Cutlass Black Ship", coverage.get(1).productName());
    assertEquals(1, coverage.get(1).ownerCount());

    // Owners: person-centric, sorted by name; the owned-product list uses the member's actual owned
    // blueprint names.
    List<JobOrderBlueprintOwnerDto> owners = result.owners();
    assertEquals(2, owners.size());
    assertEquals("Alice", owners.get(0).ownerName());
    assertEquals(List.of("Aurora MR", "Cutlass Black"), owners.get(0).ownedProductNames());
    assertEquals("Bernd", owners.get(1).ownerName());
    assertEquals(List.of("Aurora MR"), owners.get(1).ownedProductNames());
  }

  @Test
  void countsVariantsInBothDirections_andExcludesMagazines() {
    // The order requires a BASE item (Fresnel Energy LMG) and a VARIANT item (Novian "Wildshot").
    JobOrder order =
        order(
            item("Fresnel Energy LMG", "Fresnel Energy LMG"),
            item("Novian \"Wildshot\" Crossbow", "Novian \"Wildshot\" Crossbow"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE, BERND, CARLA));
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(any()))
        .thenReturn(
            List.of(
                // Alice owns a DIFFERENT variant of the required base -> counts toward Fresnel.
                owned(ALICE, "Fresnel \"Molten\" Energy LMG"),
                // Bernd owns the BASE of the required variant + that weapon's magazine -> counts
                // toward Novian via the base, but the magazine must not appear in his tags.
                owned(BERND, "Novian Crossbow"),
                owned(BERND, "Novian Bolt Magazine (5 Cap)"),
                // Carla owns only a magazine of the Fresnel -> must NOT be counted at all.
                owned(CARLA, "Fresnel Energy LMG Magazine (200 cap)")));
    when(userRepository.findAllById(any()))
        .thenReturn(List.of(user(ALICE, "Alice"), user(BERND, "Bernd")));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    JobOrderRequiredBlueprintDto fresnel = coverageFor(result, "fresnel energy lmg");
    assertEquals("Fresnel Energy LMG", fresnel.productName());
    assertEquals(1, fresnel.ownerCount(), "the variant-owner Alice counts toward the base");
    assertTrue(fresnel.variantInclusive());

    JobOrderRequiredBlueprintDto novian = coverageFor(result, "novian crossbow");
    assertEquals("Novian \"Wildshot\" Crossbow", novian.productName(), "ordered name is preserved");
    assertEquals(1, novian.ownerCount(), "the base-owner Bernd counts toward the ordered variant");

    // Carla (magazine only) is absent; Bernd's magazine is not surfaced.
    assertEquals(2, result.owners().size());
    JobOrderBlueprintOwnerDto bernd =
        result.owners().stream()
            .filter(o -> o.ownerName().equals("Bernd"))
            .findFirst()
            .orElseThrow();
    assertEquals(List.of("Novian Crossbow"), bernd.ownedProductNames());
    assertTrue(result.owners().stream().noneMatch(o -> o.ownerName().equals("Carla")));
  }

  // covers REQ-ORDERS-021 — with the per-order toggle OFF, coverage matches blueprints EXACTLY: a
  // member owning a DIFFERENT variant of an ordered base, or the base of an ordered variant, is no
  // longer counted; only owners of the exact ordered blueprint count, and no row is
  // variant-inclusive.
  @Test
  void withoutVariants_countsOnlyTheExactOrderedBlueprint() {
    JobOrder order =
        orderWithoutVariants(
            item("Fresnel Energy LMG", "Fresnel Energy LMG"),
            item("Novian \"Wildshot\" Crossbow", "Novian \"Wildshot\" Crossbow"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE, BERND, CARLA));
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(any()))
        .thenReturn(
            List.of(
                // Alice owns a DIFFERENT variant of the ordered base -> with variants OFF, excluded
                // (in variant mode she WOULD count toward Fresnel).
                owned(ALICE, "Fresnel \"Molten\" Energy LMG"),
                // Bernd owns the EXACT ordered base -> counts.
                owned(BERND, "Fresnel Energy LMG"),
                // Carla owns the EXACT ordered variant -> counts (the base would NOT, in this
                // mode).
                owned(CARLA, "Novian \"Wildshot\" Crossbow")));
    when(userRepository.findAllById(any()))
        .thenReturn(List.of(user(BERND, "Bernd"), user(CARLA, "Carla")));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    // The exact key keeps the variant quote, unlike the family key (which would be "novian
    // crossbow").
    JobOrderRequiredBlueprintDto fresnel = coverageFor(result, "fresnel energy lmg");
    assertEquals(
        1, fresnel.ownerCount(), "only the exact-base owner counts, not the variant owner");
    assertFalse(fresnel.variantInclusive(), "no row is variant-inclusive when the toggle is off");
    JobOrderRequiredBlueprintDto novian = coverageFor(result, "novian \"wildshot\" crossbow");
    assertEquals(1, novian.ownerCount(), "only the exact-variant owner counts");
    assertFalse(novian.variantInclusive());

    // Alice (different variant) is not listed; only the two exact owners are.
    assertEquals(
        List.of("Bernd", "Carla"),
        result.owners().stream().map(JobOrderBlueprintOwnerDto::ownerName).toList());
  }

  @Test
  void requiredMagazine_isMatchedExactly_andNotByItsWeapon() {
    JobOrder order = order(item("Karna Rifle Battery (35 cap)", "Karna Rifle Battery (35 cap)"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE, BERND));
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(any()))
        .thenReturn(
            List.of(
                owned(ALICE, "Karna Rifle Battery (35 cap)"),
                // The weapon does NOT fulfil a magazine order.
                owned(BERND, "Karna Rifle")));
    when(userRepository.findAllById(any())).thenReturn(List.of(user(ALICE, "Alice")));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    assertEquals(1, result.requiredBlueprints().size());
    JobOrderRequiredBlueprintDto mag = result.requiredBlueprints().get(0);
    assertEquals(1, mag.ownerCount());
    assertFalse(mag.variantInclusive(), "a magazine row is matched exactly, not variant-inclusive");
    assertEquals(1, result.owners().size());
    assertEquals("Alice", result.owners().get(0).ownerName());
  }

  @Test
  void productNobodyOwns_isFlaggedAsCoverageGap() {
    JobOrder order = order(item("Aurora MR", "Aurora MR Ship"), item("Idris", "Idris Frigate"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE));
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(any()))
        .thenReturn(List.of(owned(ALICE, "Aurora MR")));
    when(userRepository.findAllById(any())).thenReturn(List.of(user(ALICE, "Alice")));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    JobOrderRequiredBlueprintDto idris = coverageFor(result, "idris");
    assertEquals(0, idris.ownerCount());
    // Only Alice (who owns a required product) is listed; she does not own Idris.
    assertEquals(1, result.owners().size());
    assertEquals(List.of("Aurora MR"), result.owners().get(0).ownedProductNames());
  }

  @Test
  void memberOwningNoneOfTheRequiredProducts_isNotListed() {
    JobOrder order = order(item("Aurora MR", "Aurora MR Ship"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE, BERND));
    // Alice owns the required product; Bernd owns only something unrelated.
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(any()))
        .thenReturn(List.of(owned(ALICE, "Aurora MR"), owned(BERND, "Gladius")));
    when(userRepository.findAllById(any())).thenReturn(List.of(user(ALICE, "Alice")));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    assertEquals(1, result.owners().size());
    assertEquals("Alice", result.owners().get(0).ownerName());
  }

  @Test
  void resolvesMembersOfTheResponsibleOrgUnitOnly() {
    JobOrder order = order(item("Aurora MR", "Aurora MR Ship"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE));
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(eq(Set.of(ALICE.toString()))))
        .thenReturn(List.of(owned(ALICE, "Aurora MR")));
    when(userRepository.findAllById(any())).thenReturn(List.of(user(ALICE, "Alice")));

    service.getBlueprintOwners(ORDER_ID);

    verify(orgUnitMembershipRepository).findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID));
    verify(personalBlueprintRepository).findOwnerProductByOwnerSubIn(eq(Set.of(ALICE.toString())));
  }

  @Test
  void noMembers_yieldsZeroCoverageAndNoOwnersWithoutBlueprintQuery() {
    JobOrder order = order(item("Aurora MR", "Aurora MR Ship"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of());

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    assertEquals(1, result.requiredBlueprints().size());
    assertEquals(0, result.requiredBlueprints().get(0).ownerCount());
    assertTrue(result.owners().isEmpty());
    verify(personalBlueprintRepository, never()).findOwnerProductByOwnerSubIn(any());
  }

  // covers REQ-INV-018 — a user who opted into global sharing is counted in the order's coverage
  // and listed as an owner even when they are NOT a member of the responsible org unit; the service
  // unions their sub into the owner-set passed to the blueprint lookup.
  @Test
  void globalSharerOutsideResponsibleOrgUnit_isCountedAndListed() {
    JobOrder order = order(item("Aurora MR", "Aurora MR Ship"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE));
    // CARLA is not a member of the responsible org unit but opted into global sharing.
    when(userRepository.findIdsBySharingBlueprintsGlobally()).thenReturn(Set.of(CARLA));
    ArgumentCaptor<Collection<String>> ownerSubs = ArgumentCaptor.captor();
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(ownerSubs.capture()))
        .thenReturn(List.of(owned(ALICE, "Aurora MR"), owned(CARLA, "Aurora MR")));
    when(userRepository.findAllById(any()))
        .thenReturn(List.of(user(ALICE, "Alice"), user(CARLA, "Carla")));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    assertTrue(ownerSubs.getValue().contains(ALICE.toString()));
    assertTrue(ownerSubs.getValue().contains(CARLA.toString()));
    assertEquals(2, result.requiredBlueprints().get(0).ownerCount());
    assertEquals(
        List.of("Alice", "Carla"),
        result.owners().stream().map(JobOrderBlueprintOwnerDto::ownerName).toList());
    // ALICE is a member of the responsible org unit; CARLA appears only via global sharing, so she
    // is flagged not-a-member for the discreet UI hint.
    JobOrderBlueprintOwnerDto alice = ownerByName(result, "Alice");
    JobOrderBlueprintOwnerDto carla = ownerByName(result, "Carla");
    assertTrue(alice.orgUnitMember(), "the responsible-unit member is flagged a member");
    assertFalse(carla.orgUnitMember(), "the global sharer is flagged not-a-member");
  }

  // covers REQ-INV-018 — a user who is neither a member of the responsible org unit nor a global
  // sharer is never counted: the global-sharer union is empty, so the owner-set is members-only.
  @Test
  void nonMemberNonSharer_isNotCounted() {
    JobOrder order = order(item("Aurora MR", "Aurora MR Ship"));
    when(jobOrderRepository.findByIdWithItemBlueprints(ORDER_ID)).thenReturn(Optional.of(order));
    when(orgUnitMembershipRepository.findDistinctUserIdsByOrgUnitIdIn(Set.of(ORG_ID)))
        .thenReturn(Set.of(ALICE));
    when(userRepository.findIdsBySharingBlueprintsGlobally()).thenReturn(Set.of());
    ArgumentCaptor<Collection<String>> ownerSubs = ArgumentCaptor.captor();
    when(personalBlueprintRepository.findOwnerProductByOwnerSubIn(ownerSubs.capture()))
        .thenReturn(List.of(owned(ALICE, "Aurora MR")));
    when(userRepository.findAllById(any())).thenReturn(List.of(user(ALICE, "Alice")));

    JobOrderItemBlueprintOwnersDto result = service.getBlueprintOwners(ORDER_ID);

    assertEquals(Set.of(ALICE.toString()), Set.copyOf(ownerSubs.getValue()));
    assertEquals(1, result.owners().size());
    assertEquals("Alice", result.owners().get(0).ownerName());
  }

  /** Finds the coverage row with the given family key, failing the test when it is absent. */
  private static JobOrderRequiredBlueprintDto coverageFor(
      JobOrderItemBlueprintOwnersDto result, String familyKey) {
    return result.requiredBlueprints().stream()
        .filter(rb -> rb.productKey().equals(familyKey))
        .findFirst()
        .orElseThrow();
  }

  /** Finds the owner row with the given display name, failing the test when it is absent. */
  private static JobOrderBlueprintOwnerDto ownerByName(
      JobOrderItemBlueprintOwnersDto result, String name) {
    return result.owners().stream()
        .filter(o -> o.ownerName().equals(name))
        .findFirst()
        .orElseThrow();
  }
}
