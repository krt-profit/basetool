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

import de.greluc.krt.profit.basetool.backend.exception.BadRequestException;
import de.greluc.krt.profit.basetool.backend.model.JobOrder;
import de.greluc.krt.profit.basetool.backend.model.JobOrderMaterial;
import de.greluc.krt.profit.basetool.backend.model.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.model.Material;
import de.greluc.krt.profit.basetool.backend.model.MaterialClaim;
import de.greluc.krt.profit.basetool.backend.model.MaterialType;
import de.greluc.krt.profit.basetool.backend.model.QualityRequirement;
import de.greluc.krt.profit.basetool.backend.model.SpecialCommand;
import de.greluc.krt.profit.basetool.backend.model.Squadron;
import de.greluc.krt.profit.basetool.backend.model.dto.CreateClaimDto;
import de.greluc.krt.profit.basetool.backend.repository.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialClaimRepository;
import de.greluc.krt.profit.basetool.backend.repository.MaterialRepository;
import de.greluc.krt.profit.basetool.backend.repository.SpecialCommandRepository;
import de.greluc.krt.profit.basetool.backend.repository.SquadronRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reproduces the two concurrent <em>first-claim</em> races on the squadron sign-up path against the
 * real Postgres test container and pins their guarantees:
 *
 * <ul>
 *   <li><b>Same squadron (last-writer-wins).</b> Two logisticians of the same squadron lodging the
 *       first {@link MaterialClaim} on one material bucket at the same instant must <b>both</b>
 *       complete without any thread seeing a 500 / propagated conflict, and the DB must end up with
 *       exactly one row for the {@code (bucket, squadron)} pair.
 *   <li><b>Different squadrons (no overclaim).</b> Two different squadrons racing their first claim
 *       past the bucket's required amount must resolve to exactly one winner and one overclaim
 *       rejection ({@link BadRequestException}), so the committed sum never exceeds the requirement
 *       (REQ-ORDERS-024, ADR-0092).
 * </ul>
 *
 * <p>The row carries a JPA {@code @Version} (via {@code AbstractEntity}) <em>and</em> the unique
 * index {@code uq_material_claim_bucket_org_unit} (V131), so the loser of a same-squadron parallel
 * INSERT hits a {@code DataIntegrityViolationException} (or, on the repeat edit, an {@code
 * ObjectOptimisticLockingFailureException}). Before the fix that violation poisoned the writer's
 * single transaction and surfaced as an HTTP 500; {@link MaterialClaimService#upsertClaim} is now a
 * non-transactional orchestrator that retries each attempt in its own {@code REQUIRES_NEW}
 * transaction, so the loser reloads the winner's committed row and UPDATEs it in place. The
 * cross-squadron overclaim is a different race the unique index cannot catch — it is guarded by a
 * pessimistic {@code PESSIMISTIC_WRITE} lock on the order taken before the claim sum is read
 * ({@code JobOrderRepository.lockForClaimUpsert}). These tests are the dynamic regression guards
 * for both behaviours; the deterministic retry-count contract lives in {@code
 * MaterialClaimServiceTest.UpsertClaimConcurrencyTests}.
 *
 * <p>Deliberately <strong>not</strong> {@code @Transactional}: each worker runs in its own session
 * so the versions actually race, and the seed rows are removed via {@code @AfterEach}. Runs as an
 * ADMIN so the service permission matrix short-circuits to "allowed"; the {@code @WithMockUser}
 * context is captured on the test thread and re-applied inside each worker because {@code
 * SecurityContextHolder}'s default strategy does not inherit into a thread pool.
 */
@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(roles = {"ADMIN"})
class MaterialClaimConcurrencyTest {

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
  @Autowired private TransactionTemplate transactionTemplate;

  @MockitoBean private JwtDecoder jwtDecoder;

  /**
   * The seeded fixture ids for one test run — the SK order, its single material bucket, the
   * claiming squadron and the responsible SK — captured so {@code @AfterEach} can delete the rows.
   *
   * @param orderId the seeded SK job order
   * @param materialId the bucket's material
   * @param squadronId the first claiming squadron
   * @param squadronBId the second claiming squadron for the cross-squadron race, or {@code null}
   *     for the single-squadron fixture
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
    transactionTemplate.executeWithoutResult(
        st -> {
          materialClaimRepository.deleteAll(
              materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(fixture.orderId()));
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
   * Two threads lodge the first claim for the <em>same</em> squadron on the same bucket in
   * lockstep, with distinct amounts. The {@code go} latch releases both workers together so their
   * find-or-create + INSERT statements race against the unique index. The guarantee: no worker
   * throws (the loser retries in a fresh transaction instead of surfacing a 500), both upserts
   * report success, and the bucket ends up with exactly one claim carrying one of the two amounts
   * (last-writer-wins).
   *
   * @throws Exception if a worker future fails to complete within the finish timeout
   */
  @Test
  void firstClaimRace_sameSquadron_lastWriterWins_noServerError() throws Exception {
    fixture = seed();
    final List<CreateClaimDto> payloads = List.of(claim(AMOUNT_A), claim(AMOUNT_B));

    // Capture the @WithMockUser admin context on the test thread so each worker can re-apply it —
    // the service permission gate (assertCanManage → isAdmin) reads SecurityContextHolder, whose
    // default MODE_THREADLOCAL strategy does not propagate into a thread pool.
    final SecurityContext adminContext = SecurityContextHolder.getContext();

    CountDownLatch ready = new CountDownLatch(THREADS);
    CountDownLatch go = new CountDownLatch(1);
    AtomicInteger successCount = new AtomicInteger();
    AtomicInteger otherErrorCount = new AtomicInteger();
    List<Throwable> unexpectedErrors = new CopyOnWriteArrayList<>();

    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    List<Future<?>> futures = new ArrayList<>(THREADS);
    try {
      for (int i = 0; i < THREADS; i++) {
        final CreateClaimDto payload = payloads.get(i);
        futures.add(
            pool.submit(
                () -> {
                  SecurityContextHolder.setContext(adminContext);
                  try {
                    ready.countDown();
                    if (!go.await(START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                      otherErrorCount.incrementAndGet();
                      return;
                    }
                    materialClaimService.upsertClaim(fixture.orderId(), payload);
                    successCount.incrementAndGet();
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
        .as(
            "the losing first-claim writer must retry in a fresh transaction, never surface a"
                + " 500 / propagated conflict — got: %s",
            unexpectedErrors)
        .isZero();
    assertThat(successCount.get())
        .as("both concurrent same-squadron upserts must complete (last-writer-wins)")
        .isEqualTo(THREADS);

    List<MaterialClaim> rows =
        materialClaimRepository.findByJobOrderIdOrderByCreatedAtDesc(fixture.orderId());
    assertThat(rows)
        .as("the same-squadron upsert must collapse to exactly one row for the bucket")
        .hasSize(1);
    assertThat(rows.get(0).getAmount())
        .as("the surviving row carries one of the two racing writers' amounts (last-writer-wins)")
        .isIn(AMOUNT_A, AMOUNT_B);
  }

  /**
   * Seeds an open SK order with a single {@code GOOD}-bucket material requiring {@link
   * #REQUIRED_AMOUNT}, plus the one profit-eligible squadron both writers claim for. Both racing
   * amounts stay well under the required amount so the no-overclaim guard never rejects them.
   *
   * @return the created fixture ids
   */
  private Fixture seed() {
    return transactionTemplate.execute(
        status -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          SpecialCommand sk = new SpecialCommand();
          sk.setName("ClaimRace-SK-" + tag);
          sk.setShorthand("S" + tag);
          sk.setProfitEligible(true);
          sk = specialCommandRepository.save(sk);

          Squadron sq = new Squadron();
          sq.setName("ClaimRace-A-" + tag);
          sq.setShorthand("A" + tag);
          sq.setProfitEligible(true);
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
                  .minQuality(700)
                  .amount(REQUIRED_AMOUNT)
                  .build());
          order = jobOrderRepository.save(order);

          return new Fixture(order.getId(), mat.getId(), sq.getId(), null, sk.getId());
        });
  }

  /**
   * Two different profit-eligible squadrons lodge their first claim on the same bucket at once,
   * each requesting {@link #OVERCLAIM_EACH} of a {@link #REQUIRED_AMOUNT} bucket (70 + 70 = 140
   * &gt; 100). The no-overclaim invariant is a cross-row aggregate the per-(bucket, squadron)
   * unique index cannot protect, so the guarantee rests entirely on the pessimistic order-row lock
   * (REQ-ORDERS-024, ADR-0092): claimants of the order serialise, so <b>exactly one</b> squadron
   * wins and the other reads the winner's committed 70, fails the guard and is rejected with a
   * {@link BadRequestException}. The committed row-sum must never exceed 100.
   *
   * <p>Before the lock both writers read a zero already-claimed sum under {@code READ COMMITTED}
   * and both committed a 70-claim (sum 140) with no error — this test is red against that state.
   *
   * @throws Exception if a worker future fails to complete within the finish timeout
   */
  @Test
  void firstClaimRace_differentSquadrons_neverOverclaims() throws Exception {
    // covers REQ-ORDERS-024 — cross-squadron no-overclaim is concurrency-safe (ADR-0092)
    fixture = seedTwoSquadrons();
    final List<CreateClaimDto> payloads =
        List.of(
            claimFor(fixture.squadronId(), OVERCLAIM_EACH),
            claimFor(fixture.squadronBId(), OVERCLAIM_EACH));

    // See the same-squadron test: the admin permission context is captured here and re-applied
    // inside each worker because SecurityContextHolder does not propagate into a thread pool.
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
            pool.submit(
                () -> {
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
    return new CreateClaimDto(
        fixture.materialId(), QualityRequirement.GOOD, fixture.squadronId(), amount);
  }

  /**
   * Builds a claim payload for the seeded bucket at the given amount for an explicit squadron — the
   * cross-squadron race needs the two writers to name different claiming squadrons.
   *
   * @param squadronId the claiming squadron
   * @param amount the claimed partial quantity
   * @return the create-claim payload
   */
  private CreateClaimDto claimFor(UUID squadronId, double amount) {
    return new CreateClaimDto(fixture.materialId(), QualityRequirement.GOOD, squadronId, amount);
  }

  /**
   * Seeds the same open SK order + {@code GOOD}-bucket material as {@link #seed()} but with
   * <b>two</b> profit-eligible claiming squadrons, for the cross-squadron overclaim race.
   *
   * @return the created fixture ids, both squadron ids populated
   */
  private Fixture seedTwoSquadrons() {
    return transactionTemplate.execute(
        status -> {
          String tag = UUID.randomUUID().toString().substring(0, 8);
          SpecialCommand sk = new SpecialCommand();
          sk.setName("ClaimRace-SK-" + tag);
          sk.setShorthand("S" + tag);
          sk.setProfitEligible(true);
          sk = specialCommandRepository.save(sk);

          Squadron sqA = new Squadron();
          sqA.setName("ClaimRace-A-" + tag);
          sqA.setShorthand("A" + tag);
          sqA.setProfitEligible(true);
          sqA = squadronRepository.save(sqA);

          Squadron sqB = new Squadron();
          sqB.setName("ClaimRace-B-" + tag);
          sqB.setShorthand("B" + tag);
          sqB.setProfitEligible(true);
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
                  .minQuality(700)
                  .amount(REQUIRED_AMOUNT)
                  .build());
          order = jobOrderRepository.save(order);

          return new Fixture(order.getId(), mat.getId(), sqA.getId(), sqB.getId(), sk.getId());
        });
  }
}
