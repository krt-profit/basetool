import de.greluc.krt.profit.basetool.conventions.catalog
import de.greluc.krt.profit.basetool.conventions.requiredVersion

plugins { id("basetool.dependency-management-conventions") }

dependencyManagement {
  imports {
    mavenBom(
      "org.springframework.boot:spring-boot-dependencies:${project.catalog().requiredVersion("springBoot")}"
    )
  }
}
