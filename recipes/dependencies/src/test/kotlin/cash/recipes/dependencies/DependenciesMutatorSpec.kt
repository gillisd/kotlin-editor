package cash.recipes.dependencies

import cash.recipes.dependencies.transform.Transform
import cash.recipes.dependencies.transform.Transform.Element.Accessor
import cash.recipes.dependencies.transform.Transform.Element.StringLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/** Pins the current behaviour of [DependenciesMutator], beyond the accessor swap its original test covers. */
internal class DependenciesMutatorSpec {

  private fun mutate(buildScript: String, vararg transforms: Transform): DependenciesMutator =
    DependenciesMutator.of(buildScript, transforms.toList())

  @Nested inner class `changing a version` {

    @Test fun `changes a version when matched by group and name, whatever the current version`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.0")
          }
        """.trimIndent()

      // When
      val mutator = mutate(
        buildScript,
        Transform(StringLiteral("com.squareup.okhttp3:okhttp"), StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")),
      )

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )
    }

    @Test fun `changes a version when matched by the full module version`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.0")
          }
        """.trimIndent()

      // When
      val mutator = mutate(
        buildScript,
        Transform(StringLiteral("com.squareup.okhttp3:okhttp:5.5.0"), StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")),
      )

      // Then
      assertThat(mutator.rewritten()).contains("""implementation("com.squareup.okhttp3:okhttp:5.5.1")""")
    }

    @Test fun `leaves a declaration alone when the full module version names a different version`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.0")
          }
        """.trimIndent()

      // When the transform names 5.4.0, which is not what is declared
      val mutator = mutate(
        buildScript,
        Transform(StringLiteral("com.squareup.okhttp3:okhttp:5.4.0"), StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")),
      )

      // Then
      assertThat(mutator.isChanged()).isFalse()
      assertThat(mutator.rewritten()).isEqualTo(buildScript)
    }

    @Test fun `changes the dependency on every configuration it is declared on`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.0")
            testImplementation("com.squareup.okhttp3:okhttp:5.5.0")
          }
        """.trimIndent()

      // When
      val mutator = mutate(
        buildScript,
        Transform(StringLiteral("com.squareup.okhttp3:okhttp"), StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")),
      )

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )
    }

    @Test fun `keeps a trailing lambda and end-of-line comment`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("org.xerial:sqlite-jdbc:3.54.0.0") { // native driver
              exclude(group = "org.slf4j")
            }
          }
        """.trimIndent()

      // When
      val mutator = mutate(
        buildScript,
        Transform(StringLiteral("org.xerial:sqlite-jdbc"), StringLiteral("org.xerial:sqlite-jdbc:3.54.1.0")),
      )

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("org.xerial:sqlite-jdbc:3.54.1.0") { // native driver
              exclude(group = "org.slf4j")
            }
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `switching between strings and accessors` {

    @Test fun `replaces an accessor with a string literal`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation(libs.okhttp)
          }
        """.trimIndent()

      // When
      val mutator = mutate(buildScript, Transform(Accessor("libs.okhttp"), StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")))

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )
    }

    @Test fun `keeps platform() around the dependency it changes`() {
      // Given
      val buildScript =
        """
          dependencies {
            api(platform("tools.jackson:jackson-bom:3.2.0"))
          }
        """.trimIndent()

      // When
      val mutator = mutate(
        buildScript,
        Transform(StringLiteral("tools.jackson:jackson-bom"), StringLiteral("tools.jackson:jackson-bom:3.3.0")),
      )

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            api(platform("tools.jackson:jackson-bom:3.3.0"))
          }
        """.trimIndent()
      )
    }

    @Test fun `keeps testFixtures() and a trailing lambda when swapping in an accessor`() {
      // Given
      val buildScript =
        """
          dependencies {
            testImplementation(testFixtures("com.example:core:1.0")) {
              isTransitive = false
            }
          }
        """.trimIndent()

      // When
      val mutator = mutate(buildScript, Transform(StringLiteral("com.example:core"), Accessor("libs.example.core")))

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            testImplementation(testFixtures(libs.example.core)) {
              isTransitive = false
            }
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `leaving things alone` {

    @Test fun `reports no change and returns the script byte-for-byte when nothing matches`() {
      // Given
      val buildScript =
        """
          // the app's dependencies
          dependencies {
              implementation("com.squareup.okio:okio:3.9.0")   // io

            runtimeOnly("org.xerial:sqlite-jdbc:3.54.1.0") {
              isTransitive = false
            }
          }

        """.trimIndent()

      // When
      val mutator = mutate(
        buildScript,
        Transform(StringLiteral("com.squareup.okhttp3:okhttp"), StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")),
      )

      // Then
      assertThat(mutator.isChanged()).isFalse()
      assertThat(mutator.usedTransforms()).isEmpty()
      assertThat(mutator.rewritten()).isEqualTo(buildScript)
    }

    @Test fun `ignores dependencies blocks that are not top-level, such as buildscript`() {
      // Given
      val buildScript =
        """
          buildscript {
            dependencies {
              classpath("com.squareup.okhttp3:okhttp:5.5.0")
            }
          }

          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.0")
          }
        """.trimIndent()

      // When
      val mutator = mutate(
        buildScript,
        Transform(StringLiteral("com.squareup.okhttp3:okhttp"), StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")),
      )

      // Then only the top-level declaration changes
      assertThat(mutator.rewritten()).isEqualTo(
        """
          buildscript {
            dependencies {
              classpath("com.squareup.okhttp3:okhttp:5.5.0")
            }
          }

          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `reporting what changed` {

    @Test fun `reports a used transform once per declaration it changed`() {
      // Given okhttp on two configurations, and a transform that matches nothing
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.0")
            testImplementation("com.squareup.okhttp3:okhttp:5.5.0")
          }
        """.trimIndent()
      val okhttp = Transform(StringLiteral("com.squareup.okhttp3:okhttp"), Accessor("libs.okhttp"))
      val moshi = Transform(StringLiteral("com.squareup.moshi:moshi"), Accessor("libs.moshi"))

      // When
      val mutator = mutate(buildScript, okhttp, moshi)

      // Then okhttp is listed twice (once per configuration) and moshi not at all; dedupe before using the list
      assertThat(mutator.isChanged()).isTrue()
      assertThat(mutator.usedTransforms()).containsExactly(okhttp, okhttp)
    }
  }
}
