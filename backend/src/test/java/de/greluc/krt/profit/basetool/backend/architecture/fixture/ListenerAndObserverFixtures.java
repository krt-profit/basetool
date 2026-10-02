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

package de.greluc.krt.profit.basetool.backend.architecture.fixture;

import de.greluc.krt.profit.basetool.backend.annotation.ObserverSpi;
import de.greluc.krt.profit.basetool.backend.model.AuditEventType;
import de.greluc.krt.profit.basetool.backend.service.AuditService;
import de.greluc.krt.profit.basetool.backend.service.AuthHelperService;
import de.greluc.krt.profit.basetool.backend.service.RequestScopeResolver;
import java.util.UUID;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Planted violations of the listener and observer rules of REQ-AUDIT-007, each beside a compliant
 * twin, so the rules are proven able to fail. None of these types is a Spring bean.
 */
public final class ListenerAndObserverFixtures {

  /** Not instantiable. */
  private ListenerAndObserverFixtures() {}

  /** Listeners that record audit rows after commit or on another thread. */
  public static final class PlantedAuditingListener {

    private final AuditService auditService;

    /**
     * Creates the fixture.
     *
     * @param auditService the recorder the planted methods call
     */
    public PlantedAuditingListener(AuditService auditService) {
      this.auditService = auditService;
    }

    /**
     * Records an audit row directly after commit.
     *
     * @param event the event
     */
    @TransactionalEventListener
    public void recordsDirectly(Object event) {
      auditService.record(AuditEventType.USER_DELETED, null, null, null, null);
    }

    /** Records an audit row on another thread through a private helper. */
    @Async
    public void recordsThroughAHelper() {
      helper();
    }

    /**
     * Records an audit row after commit inside a lambda.
     *
     * @param event the event
     */
    @TransactionalEventListener
    public void recordsInALambda(Object event) {
      Runnable work =
          () -> auditService.record(AuditEventType.USER_DELETED, null, null, null, null);
      work.run();
    }

    /**
     * Records nothing.
     *
     * @param event the event
     */
    @TransactionalEventListener
    public void recordsNothing(Object event) {
      event.hashCode();
    }

    private void helper() {
      auditService.record(AuditEventType.USER_DELETED, null, null, null, null);
    }
  }

  /** Listeners that read the request-bound scope or security context. */
  public static final class PlantedScopeReadingListener {

    private final RequestScopeResolver requestScopeResolver;

    private final AuthHelperService authHelperService;

    /**
     * Creates the fixture.
     *
     * @param requestScopeResolver the request-bound scope the planted methods read
     * @param authHelperService the security-context seam the planted methods read
     */
    public PlantedScopeReadingListener(
        RequestScopeResolver requestScopeResolver, AuthHelperService authHelperService) {
      this.requestScopeResolver = requestScopeResolver;
      this.authHelperService = authHelperService;
    }

    /** Reads the active org unit on another thread. */
    @Async
    public void readsTheActiveOrgUnit() {
      requestScopeResolver.currentOrgUnitId();
    }

    /**
     * Reads the current user id in an event listener.
     *
     * @param event the event
     */
    @EventListener
    public void readsTheCurrentUser(Object event) {
      authHelperService.currentUserId();
    }

    /**
     * Reads the security context holder after commit.
     *
     * @param event the event
     */
    @TransactionalEventListener
    public void readsTheSecurityContext(Object event) {
      SecurityContextHolder.getContext();
    }

    /**
     * Binds an explicit authentication, which is the sanctioned way for asynchronous work.
     *
     * @param event the event
     */
    @EventListener
    public void runsAsAnExplicitPrincipal(Object event) {
      authHelperService.runAs(null, event::hashCode);
    }
  }

  /** A planted observer SPI. */
  @ObserverSpi
  public interface PlantedStockObserver {

    /**
     * Reacts to a stock change inside the caller's transaction.
     *
     * @param itemId the changed item
     */
    void stockChanged(UUID itemId);
  }

  /** An observer-named interface without the marker. */
  public interface PlantedUnmarkedObserver {

    /** Reacts to a change. */
    void changed();
  }

  /** An implementation that opens its own transaction instead of joining the caller's. */
  public static final class PlantedRequiredObserver implements PlantedStockObserver {

    /**
     * {@inheritDoc}
     *
     * @param itemId the changed item
     */
    @Override
    @Transactional
    public void stockChanged(UUID itemId) {
      itemId.hashCode();
    }
  }

  /** An implementation without any transaction annotation. */
  public static final class PlantedUnannotatedObserver implements PlantedStockObserver {

    /**
     * {@inheritDoc}
     *
     * @param itemId the changed item
     */
    @Override
    public void stockChanged(UUID itemId) {
      itemId.hashCode();
    }
  }

  /** A compliant implementation, mandatory on the method. */
  public static final class PlantedMandatoryObserver implements PlantedStockObserver {

    /**
     * {@inheritDoc}
     *
     * @param itemId the changed item
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void stockChanged(UUID itemId) {
      itemId.hashCode();
    }
  }

  /** A compliant implementation, mandatory on the class. */
  @Transactional(propagation = Propagation.MANDATORY)
  public static final class PlantedClassMandatoryObserver implements PlantedStockObserver {

    /**
     * {@inheritDoc}
     *
     * @param itemId the changed item
     */
    @Override
    public void stockChanged(UUID itemId) {
      itemId.hashCode();
    }
  }
}
