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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import de.greluc.krt.profit.basetool.backend.identity.api.ApprovalStatus;
import de.greluc.krt.profit.basetool.backend.identity.api.RoleRepository;
import de.greluc.krt.profit.basetool.backend.identity.api.User;
import de.greluc.krt.profit.basetool.backend.inventory.api.MaterialCollectionEntryDto;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocationDimension;
import de.greluc.krt.profit.basetool.backend.inventory.internal.InventoryAllocationWriteDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStatus;
import de.greluc.krt.profit.basetool.backend.kernel.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationMuteService;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationService;
import de.greluc.krt.profit.basetool.backend.notification.api.NotificationType;
import de.greluc.krt.profit.basetool.backend.orgunit.api.MembershipRole;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.orgunit.api.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.platform.api.OrgUnitContextualAuthority;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exercises material coordination through the ordinary order write and public member Inbox. */
@SpringBootTest
@WithMockUser(roles = "ADMIN")
class MaterialContributionEffectsIntegrationTest extends MaterialContributionTestSupport {
  @Autowired private OrgUnitMembershipRepository memberships;
  @Autowired private NotificationService inbox;
  @Autowired private NotificationMuteService mutes;
  @Autowired private RoleRepository roles;

  @Test
  void oneDemandRevisionGroupsBothAffectedStaffelsForTheirSharedCurrentWriter() {
    Fixture fixture = seed();
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    UUID writer = sharedWriter(fixture);
    replaceDemand(fixture, List.of(new CreateJobOrderMaterialDto(fixture.materialId(), 650, 6.0)));

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() ->
            assertThat(inbox.listOwn(writer, PageRequest.of(0, 20)).getContent())
                .singleElement()
                .satisfies(notice -> {
                  assertThat(notice.entityId()).isEqualTo(fixture.orderId());
                  assertThat(notice.type()).isEqualTo("JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED");
                  assertThat(notice.params().get("material")).startsWith("ClaimMat-");
                }));
    assertThat(onlyBucket(fixture.orderId()).claimedAmount()).isEqualTo(6.0);
  }

  @Test
  void endingRemovedRequirementsKeepsExistingMuteWithoutGenericFallback() {
    Fixture fixture = seed();
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    UUID muted = sharedWriter(fixture);
    UUID receiving = sharedWriter(fixture);
    mutes.setMuted(muted, NotificationType.JOB_ORDER_CLAIM_WITHDRAWN, true);

    replaceDemand(fixture, List.of());

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() ->
            assertThat(inbox.listOwn(receiving, PageRequest.of(0, 20)).getContent())
                .singleElement()
                .satisfies(notice -> {
                  assertThat(notice.type()).isEqualTo("JOB_ORDER_CLAIM_WITHDRAWN");
                  assertThat(notice.params().get("reasonCode")).isEqualTo("ORDER_CHANGED");
                }));
    await()
        .during(Duration.ofMillis(250))
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> assertThat(inbox.listOwn(muted, PageRequest.of(0, 20)).getContent()).isEmpty());
    assertThat(materialClaimService.getClaimBuckets(fixture.orderId())).isEmpty();
  }

  @Test
  void aRepeatedZeroCreditCorrectionCreatesNoMaterialNotice() throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture stock = stock(fixture, 3.0);
    provide(fixture, original, stock, 3.0);
    UUID writer = sharedWriter(fixture);

    removeAttribution(fixture, original.id(), stock.holderId());

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() ->
            assertThat(inbox.listOwn(writer, PageRequest.of(0, 20)).getContent())
                .singleElement()
                .satisfies(notice ->
                    assertThat(notice.type())
                        .isEqualTo("JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED")));
    inbox.markAllRead(writer);
    inbox.deleteAllRead(writer);
    var before = contributionHistory(fixture.orderId(), original.id());
    assertThat(before.creditedAmount()).isZero();

    removeAttribution(fixture, original.id(), stock.holderId());

    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(inbox.listOwn(writer, PageRequest.of(0, 20)).getContent()).isEmpty());
    var after = contributionHistory(fixture.orderId(), original.id());
    assertThat(after.openAmount()).isEqualTo(before.openAmount());
    assertThat(after.creditedAmount()).isZero();
    assertThat(after.version()).isEqualTo(before.version());
    assertThat(after.changes()).containsExactlyElementsOf(before.changes());
    assertThat(after.provisions()).containsExactlyElementsOf(before.provisions());
  }

  @Test
  void aRequesterRevisionChoosesOriginalEligiblePriorityAfterEachTypeMute() throws Exception {
    Fixture fixture = seed();
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    UUID actor = responsibleWriter(fixture);
    UUID receiving = responsibleWriter(fixture);
    UUID requesterMuted = responsibleWriter(fixture);
    UUID materialMuted = responsibleWriter(fixture);
    UUID bothMuted = responsibleWriter(fixture);
    UUID affectedOnly = writerForUnits(List.of(fixture.squadronBId()));
    mutes.setMuted(requesterMuted, NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER, true);
    mutes.setMuted(materialMuted, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED, true);
    mutes.setMuted(bothMuted, NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER, true);
    mutes.setMuted(bothMuted, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED, true);
    requesterDemand(fixture, 6.0, actor);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertOnlyNotice(receiving, NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER);
          assertOnlyNotice(
              requesterMuted, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED);
          assertOnlyNotice(materialMuted, NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER);
          assertOnlyNotice(affectedOnly, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED);
        });
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertThat(inbox.listOwn(actor, PageRequest.of(0, 20)).getContent()).isEmpty();
          assertThat(inbox.listOwn(bothMuted, PageRequest.of(0, 20)).getContent()).isEmpty();
        });
    assertThat(onlyBucket(fixture.orderId()).claimedAmount()).isEqualTo(6.0);
  }

  @Test
  void aFinishedRevisionKeepsOriginalMutesAndClearsResolvedCoordinationWork() throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    materialClaimService.upsertClaim(
        fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    List<UUID> affected = List.of(fixture.squadronAId(), fixture.squadronBId());
    UUID actor = writerForUnits(affected);
    UUID receiving = writerForUnits(affected);
    UUID finishedMuted = writerForUnits(affected);
    UUID endingMuted = writerForUnits(affected);
    UUID bothMuted = writerForUnits(affected);
    UUID affectedOnly = writerForUnits(List.of(fixture.squadronBId()));
    UUID coordination = responsibleWriter(fixture);
    mutes.setMuted(finishedMuted, NotificationType.JOB_ORDER_FINISHED, true);
    mutes.setMuted(endingMuted, NotificationType.JOB_ORDER_CLAIM_WITHDRAWN, true);
    mutes.setMuted(bothMuted, NotificationType.JOB_ORDER_FINISHED, true);
    mutes.setMuted(bothMuted, NotificationType.JOB_ORDER_CLAIM_WITHDRAWN, true);
    mutes.setMuted(coordination, NotificationType.JOB_ORDER_CLAIM_WITHDRAWN, true);
    requesterDemand(fixture, 8.0, actor);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertOnlyNotice(coordination, NotificationType.JOB_ORDER_UPDATED_BY_REQUESTER);
          assertOnlyNotice(receiving, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED);
        });
    for (UUID recipient : List.of(receiving, finishedMuted, endingMuted, bothMuted, affectedOnly)) {
      inbox.markAllRead(recipient);
      inbox.deleteAllRead(recipient);
    }
    var order = jobOrderQueryService.getJobOrderById(fixture.orderId());

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/orders/{order}/status", fixture.orderId())
                .with(asAdmin(actor))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        new UpdateJobOrderStatusDto(JobOrderStatus.COMPLETED, order.version()))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertOnlyNotice(receiving, NotificationType.JOB_ORDER_FINISHED);
          assertOnlyNotice(finishedMuted, NotificationType.JOB_ORDER_CLAIM_WITHDRAWN);
          assertOnlyNotice(endingMuted, NotificationType.JOB_ORDER_FINISHED);
          assertOnlyNotice(affectedOnly, NotificationType.JOB_ORDER_CLAIM_WITHDRAWN);
        });
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertThat(inbox.listOwn(actor, PageRequest.of(0, 20)).getContent()).isEmpty();
          assertThat(inbox.listOwn(bothMuted, PageRequest.of(0, 20)).getContent()).isEmpty();
          assertThat(inbox.listOwn(coordination, PageRequest.of(0, 20)).getContent()).isEmpty();
        });
    var ended = contributionHistory(fixture.orderId(), original.id());
    assertThat(ended.openAmount()).isZero();
    assertThat(ended.endedAt()).isNotNull();
  }

  @Test
  void provisionAndAttributionNotifyOnlyCurrentWritersOfTheirActualChangedGroups()
      throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    var target =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    StockFixture stock = stock(fixture, 3.0);
    UUID firstUnitWriter = writerForUnits(List.of(fixture.squadronAId()));
    UUID secondUnitWriter = writerForUnits(List.of(fixture.squadronBId()));
    UUID shared = sharedWriter(fixture);
    UUID responsible = responsibleWriter(fixture);
    UUID muted = sharedWriter(fixture);
    ExcludedAudience excluded = excludedAudience(fixture);
    UUID actor = stock.holderId();
    transactionTemplate.executeWithoutResult(_ -> {
      User actingWriter = activateAccount(users.findById(actor).orElseThrow());
      for (UUID unit : List.of(fixture.squadronAId(), fixture.squadronBId())) {
        joinMembership(actingWriter, unit, OrgUnitKind.SQUADRON, MembershipRole.MEMBER, true);
      }
      materialClaimRepository
          .findById(original.id())
          .orElseThrow()
          .setClaimedByUser(users.getReferenceById(excluded.recorder()));
    });
    mutes.setMuted(muted, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED, true);
    var observed =
        onlyBucket(fixture.orderId()).claims().stream()
            .filter(claim -> claim.id().equals(original.id()))
            .findFirst()
            .orElseThrow();
    assertThat(observed.claimedByUserId()).isEqualTo(excluded.recorder());
    var targetBefore = contributionHistory(fixture.orderId(), target.id());
    List<UUID> alwaysSilent =
        List.of(actor, muted, excluded.reader(), excluded.administrator(), excluded.recorder());

    provide(fixture, observed, stock, 3.0);

    var provided = contributionHistory(fixture.orderId(), original.id());
    assertThat(provided.openAmount()).isEqualTo(1.0);
    assertThat(provided.creditedAmount()).isEqualTo(3.0);
    assertThat(contributionHistory(fixture.orderId(), target.id())).isEqualTo(targetBefore);
    List<UUID> provisionRecipients = List.of(firstUnitWriter, shared, responsible);
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          for (UUID recipient : provisionRecipients) {
            assertMaterialNotice(recipient, provided.materialName());
          }
        });
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertNoNotices(alwaysSilent);
          assertNoNotices(List.of(secondUnitWriter));
        });
    for (UUID recipient : provisionRecipients) {
      inbox.markAllRead(recipient);
      inbox.deleteAllRead(recipient);
    }
    var physicalBefore = materialCollection(fixture, actor);
    assertThat(physicalBefore)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.inventoryEntryId()).isEqualTo(stock.rowId());
          assertThat(row.quantity()).isEqualTo(3.0);
          assertThat(row.allocatedQuantity()).isEqualTo(3.0);
        });

    correctAttribution(fixture, original.id(), target.id(), 3.0, actor);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          for (UUID recipient : List.of(firstUnitWriter, secondUnitWriter, shared, responsible)) {
            assertMaterialNotice(recipient, provided.materialName());
          }
        });
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertNoNotices(alwaysSilent));
    var correctedOriginal = contributionHistory(fixture.orderId(), original.id());
    var correctedTarget = contributionHistory(fixture.orderId(), target.id());
    assertThat(correctedOriginal.openAmount()).isEqualTo(4.0);
    assertThat(correctedOriginal.creditedAmount()).isZero();
    assertThat(correctedOriginal.provisions()).isEmpty();
    assertThat(correctedOriginal.changes().getLast().creditDelta()).isEqualTo(-3.0);
    assertThat(correctedTarget.openAmount()).isEqualTo(3.0);
    assertThat(correctedTarget.creditedAmount()).isEqualTo(3.0);
    assertThat(correctedTarget.changes().getLast().creditDelta()).isEqualTo(3.0);
    assertThat(correctedTarget.provisions())
        .singleElement()
        .satisfies(receipt -> {
          assertThat(receipt.id()).isEqualTo(provided.provisions().getFirst().id());
          assertThat(receipt.amount()).isEqualTo(3.0);
          assertThat(receipt.quantityAmount()).isEqualTo(3.0);
        });
    assertThat(materialCollection(fixture, actor)).containsExactlyElementsOf(physicalBefore);
  }

  @Test
  void aRejectedBusinessCommitKeepsStateAndInboxAndLaterRevisionsEachNotifyOnce() throws Exception {
    Fixture fixture = seed();
    var first =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 4.0));
    var second =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronBId(), 6.0));
    UUID actor = responsibleWriter(fixture);
    UUID receiving = sharedWriter(fixture);
    UUID responsible = responsibleWriter(fixture);
    var orderBefore = publicOrder(fixture, actor);
    var firstBefore = contributionHistory(fixture.orderId(), first.id());
    var secondBefore = contributionHistory(fixture.orderId(), second.id());

    assertThatThrownBy(() ->
            transactionTemplate.executeWithoutResult(_ -> {
              try {
                processingDemand(fixture, 6.0, actor);
              } catch (Exception failure) {
                throw new AssertionError(failure);
              }
              TransactionSynchronizationManager.registerSynchronization(new RejectBusinessCommit());
            }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage(RejectBusinessCommit.REASON);

    assertThat(publicOrder(fixture, actor)).isEqualTo(orderBefore);
    assertThat(contributionHistory(fixture.orderId(), first.id())).isEqualTo(firstBefore);
    assertThat(contributionHistory(fixture.orderId(), second.id())).isEqualTo(secondBefore);
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertNoNotices(List.of(actor, receiving, responsible)));

    processingDemand(fixture, 6.0, actor);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertOnlyNotice(receiving, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED);
          assertOnlyNotice(responsible, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED);
        });
    var firstNotice = inbox.listOwn(receiving, PageRequest.of(0, 20)).getContent().getFirst();
    assertThat(onlyBucket(fixture.orderId()).claimedAmount()).isEqualTo(6.0);
    for (UUID recipient : List.of(receiving, responsible)) {
      inbox.markAllRead(recipient);
      inbox.deleteAllRead(recipient);
    }

    processingDemand(fixture, 4.0, actor);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertOnlyNotice(receiving, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED);
          assertOnlyNotice(responsible, NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED);
        });
    assertThat(inbox.listOwn(receiving, PageRequest.of(0, 20)).getContent().getFirst().id())
        .isNotEqualTo(firstNotice.id());
    assertThat(onlyBucket(fixture.orderId()).claimedAmount()).isEqualTo(4.0);
    assertNoNotices(List.of(actor));
  }

  @Test
  void stockWithoutASelectedPromiseChangesCoverageWithoutSendingMaterialNotices() throws Exception {
    Fixture fixture = seed();
    var original =
        materialClaimService.upsertClaim(
            fixture.orderId(), claim(fixture.materialId(), fixture.squadronAId(), 3.0));
    StockFixture creditedStock = stock(fixture, 3.0);
    provide(fixture, original, creditedStock, 3.0);
    StockFixture extraStock = stock(fixture, 1.0);
    UUID actor = extraStock.holderId();
    UUID responsibleUnit = responsibleUnitId(fixture);
    transactionTemplate.executeWithoutResult(_ -> {
      User holder = activateAccount(users.findById(actor).orElseThrow());
      joinMembership(
          holder, responsibleUnit, OrgUnitKind.SPECIAL_COMMAND, MembershipRole.MEMBER, true);
    });
    UUID affected = sharedWriter(fixture);
    UUID responsible = responsibleWriter(fixture);
    var before = contributionHistory(fixture.orderId(), original.id());
    assertThat(before.openAmount()).isZero();
    assertThat(before.creditedAmount()).isEqualTo(3.0);
    var physicalBefore = materialCollection(fixture, actor);
    assertThat(physicalBefore)
        .singleElement()
        .satisfies(row -> {
          assertThat(row.inventoryEntryId()).isEqualTo(creditedStock.rowId());
          assertThat(row.allocatedQuantity()).isEqualTo(3.0);
        });
    long extraVersion = stockReads.findRow(extraStock.rowId()).orElseThrow().getVersion();
    var allocation =
        new InventoryAllocationWriteDto(
            InventoryAllocationDimension.JOB_ORDER, fixture.orderId(), 1.0, extraVersion, null);

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/inventory/{row}/allocation", extraStock.rowId())
                .with(asWriter(fixture, actor))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(allocation)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());

    assertThat(stockReads.findRow(extraStock.rowId()).orElseThrow().getVersion())
        .isGreaterThan(extraVersion);
    var physicalAfter = materialCollection(fixture, actor);
    assertThat(physicalAfter).hasSize(2);
    assertThat(physicalAfter.stream().mapToDouble(row -> row.allocatedQuantity()).sum())
        .isEqualTo(4.0);
    assertThat(physicalAfter)
        .filteredOn(row -> row.inventoryEntryId().equals(creditedStock.rowId()))
        .containsExactlyElementsOf(physicalBefore);
    assertThat(physicalAfter)
        .filteredOn(row -> row.inventoryEntryId().equals(extraStock.rowId()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.quantity()).isEqualTo(1.0);
          assertThat(row.allocatedQuantity()).isEqualTo(1.0);
        });
    assertThat(publicOrder(fixture, actor).materials().getFirst().currentStock()).isEqualTo(4.0);
    assertThat(contributionHistory(fixture.orderId(), original.id())).isEqualTo(before);
    await()
        .during(Duration.ofMillis(400))
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertNoNotices(List.of(actor, affected, responsible)));

    removeAttribution(fixture, original.id(), actor);

    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> {
          assertMaterialNotice(affected, before.materialName());
          assertMaterialNotice(responsible, before.materialName());
        });
    assertThat(contributionHistory(fixture.orderId(), original.id()).creditedAmount()).isZero();
    assertNoNotices(List.of(actor));
  }

  /** Rejects a commit after the ordered business synchronizations have run. */
  private static final class RejectBusinessCommit implements TransactionSynchronization {
    private static final String REASON = "Deliberately rejected business commit";

    /**
     * Rejects the completed public mutation at the default final synchronization order.
     *
     * @param readOnly whether the transaction is read-only
     * @throws IllegalStateException for the deliberately rejected commit
     */
    @Override
    public void beforeCommit(boolean readOnly) {
      throw new IllegalStateException(REASON);
    }
  }

  private JobOrderDto publicOrder(@NotNull Fixture fixture, @NotNull UUID actor) throws Exception {
    String body =
        mvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/orders/{order}", fixture.orderId())
                    .with(asWriter(fixture, actor)))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(body, JobOrderDto.class);
  }

  private void processingDemand(@NotNull Fixture fixture, double amount, @NotNull UUID actor)
      throws Exception {
    var order = publicOrder(fixture, actor);
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/orders/{order}", fixture.orderId())
                .with(asWriter(fixture, actor))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(demandRequest(fixture, amount, order))))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.materials[0].amount")
                .value(amount));
  }

  private UUID responsibleUnitId(@NotNull Fixture fixture) {
    return transactionTemplate.execute(_ ->
        jobOrderRepository
            .findById(fixture.orderId())
            .orElseThrow()
            .getResponsibleOrgUnit()
            .getId());
  }

  private RequestPostProcessor asWriter(@NotNull Fixture fixture, @NotNull UUID actor) {
    UUID responsible = responsibleUnitId(fixture);
    return org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(token -> token.subject(actor.toString()))
        .authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                Roles.authority(Roles.KRT_MEMBER)),
            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                Roles.authority(Roles.LOGISTICIAN)),
            new OrgUnitContextualAuthority(Roles.LOGISTICIAN, responsible));
  }

  private List<MaterialCollectionEntryDto> materialCollection(
      @NotNull Fixture fixture, @NotNull UUID actor) throws Exception {
    String body =
        mvc()
            .perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                        "/api/v1/orders/{order}/material-collection", fixture.orderId())
                    .with(
                        org.springframework.security.test.web.servlet.request
                            .SecurityMockMvcRequestPostProcessors.jwt()
                            .jwt(token -> token.subject(actor.toString()))
                            .authorities(
                                new org.springframework.security.core.authority
                                    .SimpleGrantedAuthority("ROLE_KRT_MEMBER"))))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return json.readValue(
        body, new tools.jackson.core.type.TypeReference<List<MaterialCollectionEntryDto>>() {});
  }

  private void assertMaterialNotice(@NotNull UUID recipient, @NotNull String material) {
    assertThat(inbox.listOwn(recipient, PageRequest.of(0, 20)).getContent())
        .singleElement()
        .satisfies(notice -> {
          assertThat(notice.type())
              .isEqualTo(NotificationType.JOB_ORDER_MATERIAL_CONTRIBUTIONS_CHANGED.name());
          assertThat(notice.params()).containsEntry("material", material);
        });
  }

  private void assertNoNotices(@NotNull List<UUID> recipients) {
    for (UUID recipient : recipients) {
      assertThat(inbox.listOwn(recipient, PageRequest.of(0, 20)).getContent()).isEmpty();
    }
  }

  /** The current reader, administrator without a writer grant, and historical recorder. */
  private record ExcludedAudience(UUID reader, UUID administrator, UUID recorder) {}

  private ExcludedAudience excludedAudience(@NotNull Fixture fixture) {
    return transactionTemplate.execute(_ -> {
      User reader = writerAccount();
      reader.getRoles().add(roles.findByCode("KRT_MEMBER").orElseThrow());
      joinMembership(
          reader, fixture.squadronAId(), OrgUnitKind.SQUADRON, MembershipRole.MEMBER, false);
      User administrator = writerAccount();
      administrator.getRoles().add(roles.findByCode("ADMIN").orElseThrow());
      User recorder = writerAccount();
      recorder.getRoles().add(roles.findByCode("KRT_MEMBER").orElseThrow());
      return new ExcludedAudience(reader.getId(), administrator.getId(), recorder.getId());
    });
  }

  private void requesterDemand(@NotNull Fixture fixture, double amount, @NotNull UUID actor)
      throws Exception {
    var order = jobOrderQueryService.getJobOrderById(fixture.orderId());
    var request = demandRequest(fixture, amount, order);

    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                    "/api/v1/orders/{order}/requested", fixture.orderId())
                .with(asAdmin(actor))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }

  private CreateJobOrderDto demandRequest(
      @NotNull Fixture fixture, double amount, @NotNull JobOrderDto order) {
    return new CreateJobOrderDto(
        null,
        null,
        order.handle(),
        null,
        List.of(new CreateJobOrderMaterialDto(fixture.materialId(), 650, amount)),
        order.version());
  }

  private RequestPostProcessor asAdmin(@NotNull UUID actor) {
    return org.springframework.security.test.web.servlet.request
        .SecurityMockMvcRequestPostProcessors.jwt()
        .jwt(token -> token.subject(actor.toString()))
        .authorities(
            new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ADMIN"));
  }

  private void assertOnlyNotice(@NotNull UUID recipient, @NotNull NotificationType expected) {
    assertThat(inbox.listOwn(recipient, PageRequest.of(0, 20)).getContent())
        .singleElement()
        .satisfies(notice -> assertThat(notice.type()).isEqualTo(expected.name()));
  }

  private void removeAttribution(@NotNull Fixture fixture, UUID contributionId, @NotNull UUID actor)
      throws Exception {
    correctAttribution(fixture, contributionId, null, 0.0, actor);
  }

  private void correctAttribution(
      @NotNull Fixture fixture,
      UUID contributionId,
      @Nullable UUID targetId,
      double amount,
      @NotNull UUID actor)
      throws Exception {
    var contribution = contributionHistory(fixture.orderId(), contributionId);
    var target = targetId == null ? null : contributionHistory(fixture.orderId(), targetId);
    var receipt = contribution.provisions().getFirst();
    var correction =
        new MaterialProvisionCorrectionDto(
            MaterialProvisionCorrectionDto.Kind.ATTRIBUTION,
            receipt.version(),
            contribution.version(),
            amount,
            target == null ? null : target.id(),
            target == null ? null : target.version(),
            List.of(),
            null);
    mvc()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/v1/orders/{order}/claims/provisions/{receipt}/correction",
                    fixture.orderId(),
                    receipt.id())
                .with(asAdmin(actor))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(correction)))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
  }

  private void replaceDemand(Fixture fixture, List<CreateJobOrderMaterialDto> materials) {
    var order = jobOrderQueryService.getJobOrderById(fixture.orderId());
    jobOrderService.updateJobOrder(
        fixture.orderId(),
        new CreateJobOrderDto(null, null, order.handle(), null, materials, order.version()));
  }

  private UUID sharedWriter(Fixture fixture) {
    return writerForUnits(List.of(fixture.squadronAId(), fixture.squadronBId()));
  }

  private UUID writerForUnits(@NotNull List<UUID> units) {
    return transactionTemplate.execute(_ -> {
      User user = writerAccount();
      for (UUID unit : units) {
        joinMembership(user, unit, OrgUnitKind.SQUADRON, MembershipRole.MEMBER, true);
      }
      return user.getId();
    });
  }

  private UUID responsibleWriter(@NotNull Fixture fixture) {
    UUID responsible =
        jobOrderQueryService.getJobOrderById(fixture.orderId()).responsibleOrgUnit().id();
    return transactionTemplate.execute(_ -> {
      User user = writerAccount();
      joinMembership(user, responsible, OrgUnitKind.SPECIAL_COMMAND, MembershipRole.SK_LEAD, true);
      return user.getId();
    });
  }

  private @NotNull User writerAccount() {
    User user = new User();
    user.setId(UUID.randomUUID());
    user.setUsername("material-coordinator-" + user.getId());
    return activateAccount(user);
  }

  private @NotNull User activateAccount(@NotNull User user) {
    user.setApprovalStatus(ApprovalStatus.ACTIVE);
    user.setInKeycloak(true);
    user.setEnabledInKeycloak(true);
    return users.saveAndFlush(user);
  }

  private void joinMembership(
      @NotNull User user,
      @NotNull UUID unit,
      @NotNull OrgUnitKind kind,
      @NotNull MembershipRole role,
      boolean logistician) {
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(user.getId(), unit));
    membership.setUser(user);
    membership.setKind(kind);
    membership.setRole(role);
    membership.setLogistician(logistician);
    membership.setJoinedAt(Instant.now());
    memberships.saveAndFlush(membership);
  }
}
