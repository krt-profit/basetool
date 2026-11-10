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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.persistedProfitArea;
import static de.greluc.krt.profit.basetool.backend.orgunit.api.ProfitHierarchyFixtures.withinProfit;
import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.catalogue.api.Material;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.catalogue.api.MaterialType;
import de.greluc.krt.profit.basetool.backend.catalogue.api.QualityTierRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.ClaimDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.joborder.api.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.kernel.BadRequestException;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.orgunit.api.Squadron;
import de.greluc.krt.profit.basetool.backend.orgunit.api.SquadronRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verifies serialized first claims and overclaim rejection against real PostgreSQL.
 *
 * <p>Each worker uses its own transaction through the public claim service.
 */
@SpringBootTest
@RequiredArgsConstructor(onConstructor_ = @Autowired)
@WithMockUser(roles = {"ADMIN"})
class MaterialClaimConcurrencyTest {
  /** Removes managed participation dependents before deleting their fixture units. */
  private final JobOrderParticipationRepository participations;

  @Autowired private OrgUnitRepository orgUnitRepository;

  private static final int THREADS = 2;
  private static final int START_TIMEOUT_SECONDS = 5;
  private static final int FINISH_TIMEOUT_SECONDS = 30;
  private static final double REQUIRED_AMOUNT = 100.0;
  private static final double AMOUNT_A = 30.0;
  private static final double AMOUNT_B = 40.0;

  /**
   * Each cross-squadron writer claims this much of the {@link #REQUIRED_AMOUNT} bucket: two at 70
   * sum to 140, so exactly one must win and the other must be rejected as an overclaim.
   */
  private static final double OVERCLAIM_EACH = 70.0;

  @Autowired private MaterialClaimService materialClaimService;
  @Autowired private JobOrderRepository jobOrderRepository;
  @Autowired private MaterialClaimRepository materialClaimRepository;
  @Autowired private SquadronRepository squadronRepository;
  @Autowired private SpecialCommandRepository specialCommandRepository;
  @Autowired private MaterialRepository materialRepository;
  @Autowired private QualityTierRepository qualityTierRepository;
  @Autowired private TransactionTemplate transactionTemplate;

  /**
   * Seeded fixture ids of one test run, deleted in {@code @AfterEach}.
   *
   * @param orderId the seeded SK job order
   * @param materialId the bucket's material
   * @param squadronId the first claiming squadron
   * @param squadronBId the second claiming squadron, or {@code null} for the single-squadron
   *     fixture
   * @param skId the responsible Spezialkommando
   */
  private record Fixture(
      UUID orderId, UUID materialId, UUID squadronId, UUID squadronBId, UUID skId) {}

  private Fixture fixture;

  /** Removes the seeded rows so adjacent tests inherit a clean baseline (no outer transaction). */
  @AfterEach
  void cleanup() {
    if (fixture == null) {
      return;
    }
    transactionTemplate.executeWithoutResult(st -> {
      materialClaimRepository.deleteAll(
          materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(fixture.orderId()));
      participations.deleteAll(
          participations.findByJobOrderIdOrderById(fixture.orderId(), Pageable.unpaged()));
      jobOrderRepository.deleteById(fixture.orderId());
      squadronRepository.deleteById(fixture.squadronId());
      if (fixture.squadronBId() != null) {
        squadronRepository.deleteById(fixture.squadronBId());
      }
      specialCommandRepository.deleteById(fixture.skId());
      materialRepository.deleteById(fixture.materialId());
    });
    fixture = null;
  }

  /**
   * Two first writes create one promise; the losing meaningful edit requires a deliberate retry.
   *
   * @throws Exception if a worker future does not complete within the timeout
   */
  @Test
  void firstClaimsRequireAFreshVersionForTheLosingWriter() throws Exception {
    fixture = seed();
    final List<CreateClaimDto> payloads = List.of(claim(AMOUNT_A), claim(AMOUNT_B));

    final SecurityContext adminContext = SecurityContextHolder.getContext();

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    List<ClaimDto> successes = new CopyOnWriteArrayList<>();
    List<CreateClaimDto> conflicts = new CopyOnWriteArrayList<>();
    AtomicInteger otherErrorCount = new AtomicInteger();
    List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        final CreateClaimDto payload = payloads.get(i);
        futures.add(
            pool.submit(() -> {
              SecurityContextHolder.setContext(adminContext);
              try {
                ready.countDown();
                if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                  otherErrorCount.incrementAndGet();
                  return;
                }
                successes.add(materialClaimService.upsertClaim(fixture.orderId(), payload));
              } catch (ObjectOptimisticLockingFailureException conflict) {
                conflicts.add(payload);
              } catch (Throwable t) {
                otherErrorCount.incrementAndGet();
                unexpectedErrors.add(t);
              } finally {
                SecurityContextHolder.clearContext();
              }
            }));
      }

      assertThat(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("both writers should have entered the race within %ds", START_TIMEOUT_SECONDS)
          .isTrue();
      go.countDown();

      for (Future<?> f : futures) {
        f.get(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    assertThat(otherErrorCount.get()).as("unexpected worker errors: %s", unexpectedErrors).isZero();
    assertThat(successes).hasSize(1);
    assertThat(conflicts).hasSize(1);
    ClaimDto winner = successes.getFirst();
    CreateClaimDto loser = conflicts.getFirst();
    List<MaterialClaim> rows =
        materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(fixture.orderId());
    assertThat(rows).hasSize(1);
    assertThat(rows.getFirst().getId()).isEqualTo(winner.id());
    assertThat(rows.getFirst().getAmount()).isEqualTo(winner.amount());
    assertThat(rows.getFirst().getVersion()).isEqualTo(winner.version());
    assertThat(loser.amount()).isNotEqualTo(winner.amount());

    ClaimDto retried =
        materialClaimService.upsertClaim(
            fixture.orderId(),
            new CreateClaimDto(
                loser.materialId(),
                loser.qualityRequirement(),
                loser.claimingOrgUnitId(),
                loser.amount(),
                winner.version()));
    assertThat(retried.id()).isEqualTo(winner.id());
    assertThat(retried.amount()).isEqualTo(loser.amount());
    assertThat(retried.version()).isGreaterThan(winner.version());
    assertThat(materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(fixture.orderId()))
        .hasSize(1)
        .first()
        .satisfies(row -> {
          assertThat(row.getId()).isEqualTo(retried.id());
          assertThat(row.getAmount()).isEqualTo(retried.amount());
          assertThat(row.getVersion()).isEqualTo(retried.version());
        });
  }

  /**
   * Seeds an open SK order with a single {@code GOOD}-bucket material requiring {@link
   * #REQUIRED_AMOUNT}, plus the one profit-eligible squadron both writers claim for. Both racing
   * amounts stay well under the required amount so the no-overclaim guard never rejects them.
   *
   * @return the created fixture ids
   */
  private Fixture seed() {
    return transactionTemplate.execute(status -> {
      String tag = UUID.randomUUID().toString().substring(0, 8);
      SpecialCommand sk = new SpecialCommand();
      sk.setName("ClaimRace-SK-" + tag);
      sk.setShorthand("S" + tag);
      withinProfit(sk, persistedProfitArea(orgUnitRepository));
      sk = specialCommandRepository.save(sk);

      Squadron sq = new Squadron();
      sq.setName("ClaimRace-A-" + tag);
      sq.setShorthand("A" + tag);
      withinProfit(sq, persistedProfitArea(orgUnitRepository));
      sq = squadronRepository.save(sq);

      Material mat = new Material();
      mat.setName("ClaimRaceMat-" + tag);
      mat.setType(MaterialType.RAW);
      mat = materialRepository.save(mat);

      JobOrder order =
          JobOrder.builder()
              .responsibleOrgUnit(sk)
              .requestingOrgUnit(sq)
              .handle("claim-race")
              .status(JobOrderStatus.OPEN)
              .build();
      order.addMaterial(
          JobOrderMaterial.builder()
              .material(mat)
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .amount(REQUIRED_AMOUNT)
              .build());
      order = jobOrderRepository.save(order);

      return new Fixture(order.getId(), mat.getId(), sq.getId(), null, sk.getId());
    });
  }

  /**
   * Two squadrons claim {@link #OVERCLAIM_EACH} each of a {@link #REQUIRED_AMOUNT} bucket at once;
   * exactly one wins, the other gets a {@link BadRequestException}, and the committed sum never
   * exceeds the requirement.
   *
   * @throws Exception if a worker future does not complete within the timeout
   */
  @Test
  void firstClaimRace_differentSquadrons_neverOverclaims() throws Exception {
    fixture = seedTwoSquadrons();
    final List<CreateClaimDto> payloads =
        List.of(
            claimFor(fixture.squadronId(), OVERCLAIM_EACH),
            claimFor(fixture.squadronBId(), OVERCLAIM_EACH));

    final SecurityContext adminContext = SecurityContextHolder.getContext();

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger overclaimRejectedCount = new AtomicInteger();
    AtomicInteger otherErrorCount = new AtomicInteger();
    List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        final CreateClaimDto payload = payloads.get(i);
        futures.add(
            pool.submit(() -> {
              SecurityContextHolder.setContext(adminContext);
              try {
                ready.countDown();
                if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                  otherErrorCount.incrementAndGet();
                  return;
                }
                materialClaimService.upsertClaim(fixture.orderId(), payload);
                successCount.incrementAndGet();
              } catch (BadRequestException overclaim) {
                overclaimRejectedCount.incrementAndGet();
              } catch (Throwable t) {
                otherErrorCount.incrementAndGet();
                unexpectedErrors.add(t);
              } finally {
                SecurityContextHolder.clearContext();
              }
            }));
      }

      assertThat(ready.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .as("both writers should have entered the race within %ds", START_TIMEOUT_SECONDS)
          .isTrue();
      go.countDown();

      for (Future<?> f : futures) {
        f.get(FINISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
      pool.awaitTermination(5, TimeUnit.SECONDS);
    }

    assertThat(otherErrorCount.get())
        .as("no writer may see a 500 / unexpected error — got: %s", unexpectedErrors)
        .isZero();
    assertThat(successCount.get())
        .as("exactly one squadron may win the 70-of-100 first claim")
        .isEqualTo(1);
    assertThat(overclaimRejectedCount.get())
        .as("the other squadron must be rejected with an overclaim BadRequestException")
        .isEqualTo(1);

    List<MaterialClaim> rows =
        materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(fixture.orderId());
    double committedSum = rows.stream().mapToDouble(MaterialClaim::getAmount).sum();
    assertThat(committedSum)
        .as("the committed claim sum must never exceed the bucket's required amount")
        .isLessThanOrEqualTo(REQUIRED_AMOUNT);
    assertThat(rows).as("only the winning squadron's single claim persists").hasSize(1);
  }

  /**
   * Builds a claim payload for the seeded bucket and the (single-squadron) fixture squadron at the
   * given amount.
   *
   * @param amount the claimed partial quantity
   * @return the create-claim payload
   */
  private CreateClaimDto claim(double amount) {
    return new CreateClaimDto(fixture.materialId(), "GOOD", fixture.squadronId(), amount, null);
  }

  /**
   * Builds a claim payload for the seeded bucket for an explicit squadron.
   *
   * @param squadronId the claiming squadron
   * @param amount the claimed partial quantity
   * @return the create-claim payload
   */
  private CreateClaimDto claimFor(UUID squadronId, double amount) {
    return new CreateClaimDto(fixture.materialId(), "GOOD", squadronId, amount, null);
  }

  /**
   * Seeds the same open SK order + {@code GOOD}-bucket material as {@link #seed()} but with
   * <b>two</b> profit-eligible claiming squadrons, for the cross-squadron overclaim race.
   *
   * @return the created fixture ids, both squadron ids populated
   */
  private Fixture seedTwoSquadrons() {
    return transactionTemplate.execute(status -> {
      String tag = UUID.randomUUID().toString().substring(0, 8);
      SpecialCommand sk = new SpecialCommand();
      sk.setName("ClaimRace-SK-" + tag);
      sk.setShorthand("S" + tag);
      withinProfit(sk, persistedProfitArea(orgUnitRepository));
      sk = specialCommandRepository.save(sk);

      Squadron sqA = new Squadron();
      sqA.setName("ClaimRace-A-" + tag);
      sqA.setShorthand("A" + tag);
      withinProfit(sqA, persistedProfitArea(orgUnitRepository));
      sqA = squadronRepository.save(sqA);

      Squadron sqB = new Squadron();
      sqB.setName("ClaimRace-B-" + tag);
      sqB.setShorthand("B" + tag);
      withinProfit(sqB, persistedProfitArea(orgUnitRepository));
      sqB = squadronRepository.save(sqB);

      Material mat = new Material();
      mat.setName("ClaimRaceMat-" + tag);
      mat.setType(MaterialType.RAW);
      mat = materialRepository.save(mat);

      JobOrder order =
          JobOrder.builder()
              .responsibleOrgUnit(sk)
              .requestingOrgUnit(sqA)
              .handle("claim-race")
              .status(JobOrderStatus.OPEN)
              .build();
      order.addMaterial(
          JobOrderMaterial.builder()
              .material(mat)
              .qualityTier(qualityTierRepository.findByCode("GOOD").orElseThrow())
              .amount(REQUIRED_AMOUNT)
              .build());
      order = jobOrderRepository.save(order);

      return new Fixture(order.getId(), mat.getId(), sqA.getId(), sqB.getId(), sk.getId());
    });
  }
}
