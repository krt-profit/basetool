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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.audit.api.AuditDetails;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditEventType;
import de.greluc.krt.profit.basetool.backend.audit.api.AuditRecorder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderDto;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderRepository;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderStockProjectionService;
import de.greluc.krt.profit.basetool.backend.kernel.NotFoundException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;

/** The job-order edit recipe, tested once for every order edit. */
@ExtendWith(MockitoExtension.class)
class JobOrderEditorTest {

  @Mock private JobOrderRepository jobOrderRepository;
  @Mock private JobOrderAccessPolicy jobOrderAccessPolicy;
  @Mock private AuditRecorder auditRecorder;
  @Mock private ApplicationEventPublisher eventPublisher;
  @Mock private JobOrderStockProjectionService jobOrderStockProjectionService;
  @Mock private JobOrderDto projected;

  private JobOrderEditor editor;
  private JobOrder order;
  private UUID id;
  private AtomicInteger mutations;
  private final Object firstNotice = new Object();
  private final Object secondNotice = new Object();

  @BeforeEach
  void setUp() {
    editor =
        new JobOrderEditor(
            jobOrderRepository,
            jobOrderAccessPolicy,
            auditRecorder,
            eventPublisher,
            jobOrderStockProjectionService);
    id = UUID.randomUUID();
    order = new JobOrder();
    order.setId(id);
    order.setDisplayId(42);
    order.setVersion(5L);
    mutations = new AtomicInteger();
  }

  private Function<JobOrder, JobOrderEdit> statusChanged() {
    return o -> {
      mutations.incrementAndGet();
      return JobOrderEdit.audited(
              o, AuditEventType.JOB_ORDER_STATUS_CHANGED, AuditDetails.of("to", "IN_PROGRESS"))
          .notifying(List.of(firstNotice, secondNotice));
    };
  }

  private static CharSequence sameDetails(AuditDetails expected) {
    return argThat(d -> d != null && d.toString().equals(expected.toString()));
  }

  @Test
  void aSuccessfulEditAuditsOnceThenPublishesTheNoticesThenProjects() {
    when(jobOrderRepository.findById(id)).thenReturn(Optional.of(order));
    when(jobOrderAccessPolicy.canEdit(order, JobOrderGate.EDIT)).thenReturn(true);
    when(jobOrderStockProjectionService.mapToDtoWithStock(order)).thenReturn(projected);

    JobOrderDto result = editor.edit(id, JobOrderGate.EDIT, 5L, statusChanged());

    assertThat(result).isSameAs(projected);
    assertThat(mutations).hasValue(1);
    InOrder sequence = inOrder(auditRecorder, eventPublisher, jobOrderStockProjectionService);
    sequence
        .verify(auditRecorder)
        .record(
            eq(AuditEventType.JOB_ORDER_STATUS_CHANGED),
            eq(id),
            eq("#42"),
            isNull(),
            sameDetails(AuditDetails.of("to", "IN_PROGRESS")));
    sequence.verify(eventPublisher).publishEvent(firstNotice);
    sequence.verify(eventPublisher).publishEvent(secondNotice);
    sequence.verify(jobOrderStockProjectionService).mapToDtoWithStock(order);
    sequence.verifyNoMoreInteractions();
  }

  @Test
  void aStaleVersionIsAConflictWithoutMutationAuditNoticeOrSave() {
    when(jobOrderRepository.findById(id)).thenReturn(Optional.of(order));
    when(jobOrderAccessPolicy.canEdit(order, JobOrderGate.EDIT)).thenReturn(true);

    assertThatThrownBy(() -> editor.edit(id, JobOrderGate.EDIT, 4L, statusChanged()))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(mutations).hasValue(0);
    verify(jobOrderRepository, never()).save(any());
    verify(jobOrderRepository, never()).saveAndFlush(any());
    verifyNoInteractions(auditRecorder, eventPublisher, jobOrderStockProjectionService);
  }

  @Test
  void aMissingVersionIsAConflictToo() {
    when(jobOrderRepository.findById(id)).thenReturn(Optional.of(order));
    when(jobOrderAccessPolicy.canEdit(order, JobOrderGate.REQUESTER)).thenReturn(true);

    assertThatThrownBy(() -> editor.edit(id, JobOrderGate.REQUESTER, null, statusChanged()))
        .isInstanceOf(ObjectOptimisticLockingFailureException.class);

    assertThat(mutations).hasValue(0);
    verifyNoInteractions(auditRecorder, eventPublisher);
  }

  @Test
  void aDeniedCallerIsRefusedBeforeTheVersionIsChecked() {
    when(jobOrderRepository.findById(id)).thenReturn(Optional.of(order));
    when(jobOrderAccessPolicy.canEdit(order, JobOrderGate.EDIT)).thenReturn(false);

    assertThatThrownBy(() -> editor.edit(id, JobOrderGate.EDIT, 4L, statusChanged()))
        .isInstanceOf(AccessDeniedException.class);

    assertThat(mutations).hasValue(0);
    verifyNoInteractions(auditRecorder, eventPublisher, jobOrderStockProjectionService);
  }

  @Test
  void anUnknownOrderIsNotFoundBeforeTheGateIsAsked() {
    when(jobOrderRepository.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> editor.editUnversioned(id, JobOrderGate.EDIT, statusChanged()))
        .isInstanceOf(NotFoundException.class);

    verifyNoInteractions(jobOrderAccessPolicy, auditRecorder, eventPublisher);
  }

  @Test
  void anEditWithoutAClientVersionSkipsTheVersionCheck() {
    when(jobOrderRepository.findById(id)).thenReturn(Optional.of(order));
    when(jobOrderAccessPolicy.canEdit(order, JobOrderGate.EDIT)).thenReturn(true);

    editor.editWithoutProjection(id, JobOrderGate.EDIT, statusChanged());

    assertThat(mutations).hasValue(1);
    verify(auditRecorder)
        .record(
            eq(AuditEventType.JOB_ORDER_STATUS_CHANGED),
            eq(id),
            eq("#42"),
            isNull(),
            sameDetails(AuditDetails.of("to", "IN_PROGRESS")));
    verifyNoInteractions(jobOrderStockProjectionService);
  }

  @Test
  void anUnchangedEditRecordsAndPublishesNothingButStillProjects() {
    when(jobOrderRepository.findById(id)).thenReturn(Optional.of(order));
    when(jobOrderAccessPolicy.canEdit(order, JobOrderGate.EDIT)).thenReturn(true);
    when(jobOrderStockProjectionService.mapToDtoWithStock(order)).thenReturn(projected);

    JobOrderDto result = editor.edit(id, JobOrderGate.EDIT, 5L, JobOrderEdit::unchanged);

    assertThat(result).isSameAs(projected);
    verifyNoInteractions(auditRecorder, eventPublisher);
  }

  @Test
  void theAuditSubjectIsTheOrderTheMutationReturns() {
    JobOrder refetched = new JobOrder();
    refetched.setId(id);
    refetched.setDisplayId(42);
    when(jobOrderRepository.findById(id)).thenReturn(Optional.of(order));
    when(jobOrderAccessPolicy.canEdit(order, JobOrderGate.EDIT)).thenReturn(true);
    when(jobOrderStockProjectionService.mapToDtoWithStock(refetched)).thenReturn(projected);

    JobOrderDto result =
        editor.edit(
            id,
            JobOrderGate.EDIT,
            5L,
            o -> JobOrderEdit.audited(refetched, AuditEventType.JOB_ORDER_UPDATED, null));

    assertThat(result).isSameAs(projected);
    verify(auditRecorder).record(AuditEventType.JOB_ORDER_UPDATED, id, "#42", null, null);
  }
}
