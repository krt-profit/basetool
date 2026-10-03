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

import de.greluc.krt.profit.basetool.backend.model.MembershipRole;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitKind;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembership;
import de.greluc.krt.profit.basetool.backend.model.OrgUnitMembershipId;
import de.greluc.krt.profit.basetool.backend.model.Organisationsleitung;
import de.greluc.krt.profit.basetool.backend.model.User;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitMembershipRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrgUnitRepository;
import de.greluc.krt.profit.basetool.backend.repository.OrganisationsleitungRepository;
import de.greluc.krt.profit.basetool.backend.repository.UserRepository;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reproduces the Hibernate-proxy case of {@link OrgUnitMembershipService#removeOlMember}: when the
 * Organisationsleitung is already in the persistence context as an uninitialised {@code OrgUnit}
 * proxy, removing the Grand Admiral from the OL must still clear the designation.
 */
@SpringBootTest
@Transactional
class OrgUnitMembershipServiceProxyIntegrationTest {

  @Autowired private OrgUnitMembershipService membershipService;
  @Autowired private OrganisationsleitungRepository organisationsleitungRepository;
  @Autowired private OrgUnitRepository orgUnitRepository;
  @Autowired private OrgUnitMembershipRepository membershipRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private EntityManager entityManager;

  private Organisationsleitung organisationsleitung() {
    return organisationsleitungRepository.findAll().stream()
        .findFirst()
        .orElseGet(
            () -> {
              Organisationsleitung ol = new Organisationsleitung();
              ol.setName("OL proxy probe");
              ol.setShorthand("OLP");
              return organisationsleitungRepository.save(ol);
            });
  }

  @Test
  void removeGrandAdmiral_worksWhenTheOlIsLoadedAsAProxy() {
    UUID[] ids = seedOlWithGrandAdmiral();

    Object proxy = orgUnitRepository.getReferenceById(ids[0]);
    assertThat(Hibernate.isInitialized(proxy)).isFalse();

    membershipService.removeGrandAdmiral(ids[0]);
    entityManager.flush();
    entityManager.clear();

    assertThat(
            organisationsleitungRepository.findById(ids[0]).orElseThrow().getGrandAdmiralUserId())
        .isNull();
  }

  @Test
  void removeOlMember_clearsTheGrandAdmiralPointerWhenTheOlIsLoadedAsAProxy() {
    UUID[] ids = seedOlWithGrandAdmiral();
    UUID olId = ids[0];
    UUID userId = ids[1];

    Object proxy = orgUnitRepository.getReferenceById(olId);
    assertThat(Hibernate.isInitialized(proxy)).isFalse();

    membershipService.removeOlMember(olId, userId);
    entityManager.flush();
    entityManager.clear();

    Organisationsleitung reloaded = organisationsleitungRepository.findById(olId).orElseThrow();
    assertThat(reloaded.getGrandAdmiralUserId()).isNull();
  }

  private UUID[] seedOlWithGrandAdmiral() {
    UUID userId = UUID.randomUUID();
    User user = new User();
    user.setId(userId);
    user.setUsername("ol-proxy-" + userId);
    userRepository.saveAndFlush(user);
    Organisationsleitung ol = organisationsleitung();
    UUID olId = ol.getId();
    OrgUnitMembership membership = new OrgUnitMembership();
    membership.setId(new OrgUnitMembershipId(userId, olId));
    membership.setUser(user);
    membership.setKind(OrgUnitKind.ORGANISATIONSLEITUNG);
    membership.setRole(MembershipRole.OL_MEMBER);
    membership.setJoinedAt(Instant.now());
    membershipRepository.saveAndFlush(membership);
    ol.setGrandAdmiralUserId(userId);
    ol.setGrandAdmiralDisplayName(null);
    organisationsleitungRepository.saveAndFlush(ol);
    entityManager.clear();
    return new UUID[] {olId, userId};
  }
}
