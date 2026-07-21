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

package de.greluc.krt.profit.basetool.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.model.GameItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandover;
import de.greluc.krt.profit.basetool.backend.model.JobOrderHandoverItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItem;
import de.greluc.krt.profit.basetool.backend.model.JobOrderItemMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.JobOrderType;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.OrgUnit;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.scwiki.Blueprint;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verifies the result contract of {@link JobOrderRepository#findAllActiveWithMaterials} — the query
 * behind {@code /api/v1/orders/lookup} that feeds the Auftrag (job-order) filter and the per-row
 * job-order selects of the warehouse (Lager) views. Two invariants are covered:
 *
 * <ul>
 *   <li><b>Ordering.</b> The pickers must rank orders the same way the Auftragsverwaltung does
 *       (default {@code priority,asc}): most-important priority first, orders without a priority
 *       last, with a stable {@code displayId DESC} tiebreaker.
 *   <li><b>Distinct roots (REQ-ORDERS-018).</b> Because the lookup eager-fetches the order's
 *       material requirements, an active order carrying several child rows across the fetched
 *       collections — material or item lines plus a handover — must still be returned <em>exactly
 *       once</em>, so a picker never renders a duplicated {@code <option>}. Hibernate de-duplicates
 *       fetch-join roots automatically; the query additionally no longer eager-fetches the unused
 *       MATERIAL handover collections, which on a MATERIAL order formed a {@code materials ×
 *       handovers} SQL cartesian. The two cases below lock the exactly-once invariant for both
 *       order kinds and confirm the reduced fetch graph still loads the lines it must.
 * </ul>
 *
 * <p>Run against the real Postgres test container (Flyway-migrated schema), so the {@code NULLS
 * LAST} semantics and the DB-generated {@code display_id} sequence are exercised at production
 * parity. The de-duplication cases {@link jakarta.persistence.EntityManager#clear() clear} the
 * persistence context before querying, so the lookup reads cold (as production does) instead of
 * resolving every result-set row back to the just-saved managed instance. The Testcontainer is
 * shared and other suites commit job-order rows, so each assertion filters the result down to the
 * ids created here rather than asserting a suite-global list.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JobOrderRepositoryActiveLookupOrderingTest {

  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private GameItemRepository gameItemRepository;
  @Autowired private BlueprintRepository blueprintRepository;
  @PersistenceContext private EntityManager entityManager;

  /**
   * Priorities are persisted out of order (30, 10, 20) so the result cannot pass by coincidence of
   * insertion order, and two priority-less orders pin down the NULLS-LAST branch plus the {@code
   * displayId DESC} tiebreaker: each is saved-and-flushed in turn, so the later one gets the higher
   * generated {@code display_id} and must therefore sort ahead of the earlier one.
   */
  @Test
  void findAllActiveWithMaterials_ordersByPriorityAscNullsLastThenDisplayIdDesc() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    Squadron squadron = new Squadron();
    squadron.setName("Prio-Order-" + tag);
    squadron.setShorthand("PO" + tag);
    OrgUnit owner = squadronRepository.save(squadron);

    UUID prio30 = saveActiveOrder(owner, 30);
    UUID prio10 = saveActiveOrder(owner, 10);
    UUID prio20 = saveActiveOrder(owner, 20);
    UUID noPrioEarly = saveActiveOrder(owner, null);
    UUID noPrioLate = saveActiveOrder(owner, null);

    Set<UUID> mine = Set.of(prio30, prio10, prio20, noPrioEarly, noPrioLate);
    List<UUID> mineInResultOrder =
        jobOrderRepository.findAllActiveWithMaterials().stream()
            .map(JobOrder::getId)
            .filter(mine::contains)
            .toList();

    assertThat(mineInResultOrder).containsExactly(prio10, prio20, prio30, noPrioLate, noPrioEarly);
  }

  /**
   * A {@code MATERIAL} order that carries several material lines <em>and</em> a handover is the
   * case the removed {@code handovers} branch made wasteful: eager-fetching both the {@code
   * materials} and the {@code handovers} collections multiplied the SQL result set into a {@code
   * materials × handovers} cartesian. Hibernate de-duplicates the fetch-join roots, so the order
   * must appear exactly once — and now that the handover branch is gone, its two material lines
   * must still be fetched for the picker's required-material projection.
   */
  @Test
  void findAllActiveWithMaterials_materialOrderWithLinesAndHandover_returnsRootExactlyOnce() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    OrgUnit owner = newSquadron(tag);
    Material matA = newMaterial("DedupMatA-" + tag);
    Material matB = newMaterial("DedupMatB-" + tag);

    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(owner)
            .requestingOrgUnit(owner)
            .handle("dedup-material")
            .status(JobOrderStatus.OPEN)
            .type(JobOrderType.MATERIAL)
            .build();
    order.addMaterial(JobOrderMaterial.builder().material(matA).amount(10.0).build());
    order.addMaterial(JobOrderMaterial.builder().material(matB).amount(5.0).build());
    attachHandover(order, matA);
    UUID orderId = jobOrderRepository.saveAndFlush(order).getId();
    // Read the lookup cold: without clearing, the just-saved order sits in the L1 persistence cache
    // and every result-set row resolves back to that single managed instance, hiding the duplicate
    // roots a fresh production read (findAllActiveReference, cold context) would otherwise return.
    entityManager.clear();

    List<JobOrder> active = jobOrderRepository.findAllActiveWithMaterials();

    assertThat(occurrences(active, orderId)).isEqualTo(1);
    assertThat(single(active, orderId).getMaterials()).hasSize(2);
  }

  /**
   * The {@code ITEM}-order counterpart of {@link
   * #findAllActiveWithMaterials_materialOrderWithLinesAndHandover_returnsRootExactlyOnce}: an item
   * order with two ordered lines (each nesting its derived material), plus a handover. The fetched
   * {@code items}/{@code items.materials} nesting is a cartesian too, but a necessary one (the
   * derived materials feed the picker); the order must be returned exactly once with both item
   * lines fetched, so {@code JobOrderItemService.requiredMaterialIds}/{@code requiredGameItemIds}
   * resolve without an N+1.
   */
  @Test
  void findAllActiveWithMaterials_itemOrderWithLinesAndHandover_returnsRootExactlyOnce() {
    String tag = UUID.randomUUID().toString().substring(0, 8);
    OrgUnit owner = newSquadron(tag);
    GameItem gameItem = newGameItem("DedupItem-" + tag);
    Blueprint blueprint = newBlueprint();
    Material matA = newMaterial("DedupItemMatA-" + tag);
    Material matB = newMaterial("DedupItemMatB-" + tag);

    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(owner)
            .requestingOrgUnit(owner)
            .handle("dedup-item")
            .status(JobOrderStatus.IN_PROGRESS)
            .type(JobOrderType.ITEM)
            .build();
    order.addItem(newItemLine(gameItem, blueprint, matA));
    order.addItem(newItemLine(gameItem, blueprint, matB));
    attachHandover(order, matA);
    UUID orderId = jobOrderRepository.saveAndFlush(order).getId();
    // Read the lookup cold — see the material-order case: an uncleared context would resolve every
    // row to the cached managed instance and mask the duplicate roots.
    entityManager.clear();

    List<JobOrder> active = jobOrderRepository.findAllActiveWithMaterials();

    assertThat(occurrences(active, orderId)).isEqualTo(1);
    assertThat(single(active, orderId).getItems()).hasSize(2);
  }

  /**
   * Persists one {@code OPEN} (hence active) job order owned by {@code owner} on both org-unit FKs
   * and flushes immediately, so the DB assigns its {@code display_id} in call order.
   *
   * @param owner the responsible and requesting org unit (both {@code NOT NULL} FKs).
   * @param priority the manual priority rank, or {@code null} to exercise the NULLS-LAST branch.
   * @return the generated job-order id.
   */
  private UUID saveActiveOrder(OrgUnit owner, Integer priority) {
    JobOrder order =
        JobOrder.builder()
            .responsibleOrgUnit(owner)
            .requestingOrgUnit(owner)
            .handle("prio-order")
            .priority(priority)
            .status(JobOrderStatus.OPEN)
            .build();
    return jobOrderRepository.saveAndFlush(order).getId();
  }

  /**
   * Persists a fresh profit-eligibility-agnostic squadron tagged with {@code tag} to own the
   * de-duplication fixtures on both order FKs.
   *
   * @param tag the per-test uniqueness suffix.
   * @return the persisted squadron as an {@link OrgUnit}.
   */
  private OrgUnit newSquadron(String tag) {
    Squadron squadron = new Squadron();
    squadron.setName("Dedup-Order-" + tag);
    squadron.setShorthand("DO" + tag);
    return squadronRepository.save(squadron);
  }

  /**
   * Persists a raw {@link Material} with the given (per-test unique) name.
   *
   * @param name the unique material name.
   * @return the persisted material.
   */
  private Material newMaterial(String name) {
    Material material = new Material();
    material.setName(name);
    material.setType(MaterialType.RAW);
    return materialRepository.save(material);
  }

  /**
   * Persists a minimal {@link GameItem} (name only; kind/source default) so an item line can
   * reference a resolvable {@code game_item} row.
   *
   * @param name the unique item name.
   * @return the persisted game item.
   */
  private GameItem newGameItem(String name) {
    GameItem gameItem = new GameItem();
    gameItem.setName(name);
    return gameItemRepository.save(gameItem);
  }

  /**
   * Persists a minimal {@link Blueprint} (only its mandatory unique {@code scwiki_uuid}) so an item
   * line has a non-null blueprint FK.
   *
   * @return the persisted blueprint.
   */
  private Blueprint newBlueprint() {
    Blueprint blueprint = new Blueprint();
    blueprint.setScwikiUuid(UUID.randomUUID());
    return blueprintRepository.save(blueprint);
  }

  /**
   * Builds a detached {@link JobOrderItem} line requesting one unit of {@code gameItem} via {@code
   * blueprint}, nesting a single derived {@link JobOrderItemMaterial} for {@code material}.
   *
   * @param gameItem the requested finished item.
   * @param blueprint the chosen recipe.
   * @param material the derived material requirement.
   * @return the populated, not-yet-attached item line.
   */
  private JobOrderItem newItemLine(GameItem gameItem, Blueprint blueprint, Material material) {
    JobOrderItem item =
        JobOrderItem.builder().gameItem(gameItem).blueprint(blueprint).amount(1).build();
    item.addMaterial(
        JobOrderItemMaterial.builder()
            .material(material)
            .requiredQuantity(1.0)
            .qualityRequirement(QualityRequirement.NONE)
            .build());
    return item;
  }

  /**
   * Attaches a single MATERIAL {@link JobOrderHandover} (with one handover item for {@code
   * material}) to {@code order}, keeping the bidirectional link in sync so the cascade persists it.
   * A structurally valid handover is enough to reproduce the former cartesian product with the
   * order's other fetched collections.
   *
   * @param order the order to attach the handover to.
   * @param material the delivered material.
   */
  private void attachHandover(JobOrder order, Material material) {
    JobOrderHandover handover =
        JobOrderHandover.builder()
            .handoverTime(Instant.now())
            .recipientHandle("dedup-recipient")
            .build();
    handover.addItem(
        JobOrderHandoverItem.builder().material(material).quality(100).amount(1.0).build());
    handover.setJobOrder(order);
    order.getHandovers().add(handover);
  }

  /**
   * Counts how many times the order {@code id} occurs in {@code orders} (root-reference
   * multiplicity).
   *
   * @param orders the query result to scan.
   * @param id the order id to count.
   * @return the number of occurrences of {@code id}.
   */
  private static long occurrences(List<JobOrder> orders, UUID id) {
    return orders.stream().map(JobOrder::getId).filter(id::equals).count();
  }

  /**
   * Returns the single {@link JobOrder} with id {@code id} from {@code orders}, failing if it is
   * absent.
   *
   * @param orders the query result to scan.
   * @param id the order id to locate.
   * @return the matching order.
   */
  private static JobOrder single(List<JobOrder> orders, UUID id) {
    return orders.stream()
        .filter(o -> o.getId().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("order " + id + " not returned by the lookup"));
  }
}
