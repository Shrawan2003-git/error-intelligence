package dev.errorintelligence;

import org.testcontainers.utility.DockerImageName;

/** Build infra/postgres before container tests so contracts use the deployed database image. */
final class TestImages {
  static final DockerImageName POSTGRES =
      DockerImageName.parse(System.getProperty("test.postgres.image", "eip-postgres-test:local"))
          .asCompatibleSubstituteFor("postgres");

  private TestImages() {}
}
