/**
 * The navigation module: each member's own navigation preferences, owner-only and unaudited
 * (ADR-0298). A leaf module that publishes no {@code api} package.
 */
@ApplicationModule(allowedDependencies = {"kernel", "platform::api"})
package de.greluc.krt.profit.basetool.backend.navigation;

import org.springframework.modulith.ApplicationModule;
