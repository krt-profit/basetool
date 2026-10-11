import de.greluc.krt.profit.basetool.build.catalog
import de.greluc.krt.profit.basetool.build.requiredVersion

plugins { id("basetool.dependency-management-conventions") }

dependencyManagement {
  imports {
    mavenBom(
      "org.springframework.boot:spring-boot-dependencies:${project.catalog().requiredVersion("springBoot")}"
    )
  }
}
