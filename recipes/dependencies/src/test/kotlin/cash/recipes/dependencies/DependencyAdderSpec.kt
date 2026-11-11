package cash.recipes.dependencies

import cash.grammar.kotlindsl.model.DependencyDeclaration.Capability
import cash.recipes.dependencies.DependencyAdder.Addition
import cash.recipes.dependencies.transform.Transform.Element.Accessor
import cash.recipes.dependencies.transform.Transform.Element.StringLiteral
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Each spec stays @Disabled("pending") until it is written; remove the marker as it is filled in.
internal class DependencyAdderSpec {

  @Nested inner class `adding to an existing dependencies block` {

    @Test fun `adds a string-literal dependency at the end of the block`() {
      // Given a script with no `api` declarations
      val buildScript =
        """
          plugins {
            id("java-library")
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(
        buildScript,
        listOf(Addition("api", StringLiteral("com.squareup.okhttp3:okhttp:5.5.1"))),
      )

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          plugins {
            id("java-library")
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
            api("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )
    }

    @Test fun `adds a version catalog accessor dependency`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation(libs.okio)
          }
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(buildScript, listOf(Addition("runtimeOnly", Accessor("libs.sqlite.jdbc"))))

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          dependencies {
            implementation(libs.okio)
            runtimeOnly(libs.sqlite.jdbc)
          }
        """.trimIndent()
      )
    }

    @Test fun `adds after the last declaration with the same configuration`() {
      // Given two implementation declarations followed by a test one
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation(libs.picocli)
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(
        buildScript,
        listOf(Addition("implementation", StringLiteral("com.squareup.okhttp3:okhttp:5.5.1"))),
      )

      // Then it lands after the last implementation, not at the end
      assertThat(adder.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation(libs.picocli)
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `adds several dependencies in the order given`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()

      // When two go after the same line and one goes elsewhere
      val adder = DependencyAdder.of(
        buildScript,
        listOf(
          Addition("implementation", StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")),
          Addition("testImplementation", StringLiteral("org.assertj:assertj-core:3.27.7")),
          Addition("implementation", Accessor("libs.picocli")),
        ),
      )

      // Then each group keeps the order given
      assertThat(adder.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            implementation(libs.picocli)
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
            testImplementation("org.assertj:assertj-core:3.27.7")
          }
        """.trimIndent()
      )
    }

    @Test fun `wraps the dependency in platform() or testFixtures() when a capability is given`() {
      // Given
      val buildScript =
        """
          dependencies {
            api("com.squareup.okio:okio:3.9.0")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(
        buildScript,
        listOf(
          Addition("api", Accessor("libs.jackson.bom"), Capability.PLATFORM),
          Addition("testImplementation", StringLiteral("com.example:core:1.0"), Capability.TEST_FIXTURES),
        ),
      )

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          dependencies {
            api("com.squareup.okio:okio:3.9.0")
            api(platform(libs.jackson.bom))
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
            testImplementation(testFixtures("com.example:core:1.0"))
          }
        """.trimIndent()
      )
    }

    @Test fun `adds to an empty multi-line dependencies block`() {
      // Given an empty block whose only content is a comment
      val buildScript =
        """
          plugins {
            id("java-library")
          }

          dependencies {
            // nothing yet
          }
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(
        buildScript,
        listOf(
          Addition("implementation", StringLiteral("com.squareup.okio:okio:3.9.0")),
          Addition("testImplementation", StringLiteral("org.junit.jupiter:junit-jupiter:5.11.0")),
        ),
      )

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          plugins {
            id("java-library")
          }

          dependencies {
            // nothing yet
            implementation("com.squareup.okio:okio:3.9.0")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `adds to an empty one-line dependencies block`() {
      // Given
      val buildScript =
        """
          plugins {
            id("java-library")
          }

          dependencies {}
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(
        buildScript,
        listOf(Addition("implementation", StringLiteral("com.squareup.okio:okio:3.9.0"))),
      )

      // Then the block is opened up
      assertThat(adder.rewritten()).isEqualTo(
        """
          plugins {
            id("java-library")
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `matches the indentation already used in the file`() {
      // Given a script indented with four spaces
      val buildScript =
        """
          plugins {
              id("java-library")
          }

          dependencies {
              implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(
        buildScript,
        listOf(Addition("testImplementation", StringLiteral("org.junit.jupiter:junit-jupiter:5.11.0"))),
      )

      // Then
      assertThat(adder.rewritten()).isEqualTo(
        """
          plugins {
              id("java-library")
          }

          dependencies {
              implementation("com.squareup.okio:okio:3.9.0")
              testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `keeps a comment just before the closing brace above the closing brace`() {
      // Given a note at the end of the block
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            // keep this list sorted
          }
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(
        buildScript,
        listOf(Addition("api", StringLiteral("com.squareup.okhttp3:okhttp:5.5.1"))),
      )

      // Then the new line goes above the note, which stays last
      assertThat(adder.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            api("com.squareup.okhttp3:okhttp:5.5.1")
            // keep this list sorted
          }
        """.trimIndent()
      )
    }

    @Test fun `leaves existing declarations, their trailing lambdas and comments untouched`() {
      // Given declarations with an end-of-line comment, a trailing lambda and odd spacing
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0") // io
            implementation("org.xerial:sqlite-jdbc:3.54.1.0") {
              exclude(group = "org.slf4j")
            }

            testImplementation(  "org.junit.jupiter:junit-jupiter:5.11.0"  )
          }
        """.trimIndent()

      // When one is added after the lambda and one after the end-of-line comment's neighbour
      val adder = DependencyAdder.of(
        buildScript,
        listOf(
          Addition("implementation", StringLiteral("com.squareup.okhttp3:okhttp:5.5.1")),
          Addition("testImplementation", StringLiteral("org.assertj:assertj-core:3.27.7")),
        ),
      )

      // Then every existing character stays as it was
      assertThat(adder.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0") // io
            implementation("org.xerial:sqlite-jdbc:3.54.1.0") {
              exclude(group = "org.slf4j")
            }
            implementation("com.squareup.okhttp3:okhttp:5.5.1")

            testImplementation(  "org.junit.jupiter:junit-jupiter:5.11.0"  )
            testImplementation("org.assertj:assertj-core:3.27.7")
          }
        """.trimIndent()
      )
    }

    @Test fun `ignores dependencies blocks that are not top-level, such as buildscript or a task`() {
      // Given a buildscript block with its own dependencies block, before the real one
      val buildScript =
        """
          buildscript {
            repositories {
              mavenCentral()
            }
            dependencies {
              classpath("com.example:gradle-plugin:1.0")
            }
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()

      // When
      val adder = DependencyAdder.of(
        buildScript,
        listOf(Addition("implementation", StringLiteral("com.squareup.okhttp3:okhttp:5.5.1"))),
      )

      // Then only the top-level block changes
      assertThat(adder.rewritten()).isEqualTo(
        """
          buildscript {
            repositories {
              mavenCentral()
            }
            dependencies {
              classpath("com.example:gradle-plugin:1.0")
            }
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `when the script has no dependencies block` {

    @Disabled("pending") @Test fun `creates a dependencies block at the end of the script`() {}

    @Disabled("pending") @Test fun `creates the block after the plugins and repositories blocks`() {}
  }

  @Nested inner class `when the dependency is already declared` {

    @Disabled("pending") @Test fun `skips a dependency already declared with the same version`() {}

    @Disabled("pending") @Test fun `skips a dependency already declared with a different version`() {}

    @Disabled("pending") @Test fun `still adds the same coordinates on a different configuration`() {}

    @Disabled("pending") @Test fun `recognises an already declared version catalog accessor`() {}
  }

  @Nested inner class `reporting what changed` {

    @Test fun `reports no change and returns the script byte-for-byte when nothing is added`() {
      // Given a script with uneven formatting, a comment and a trailing lambda
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

      // When the only addition is already declared
      val adder = DependencyAdder.of(
        buildScript,
        listOf(Addition("implementation", StringLiteral("com.squareup.okio:okio:3.9.0"))),
      )

      // Then
      assertThat(adder.isChanged()).isFalse()
      assertThat(adder.rewritten()).isEqualTo(buildScript)
    }

    @Test fun `reports which dependencies were added and which were skipped`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      val alreadyThere = Addition("implementation", StringLiteral("com.squareup.okio:okio:3.9.0"))
      val new = Addition("implementation", StringLiteral("com.squareup.okhttp3:okhttp:5.5.1"))

      // When
      val adder = DependencyAdder.of(buildScript, listOf(alreadyThere, new))

      // Then
      assertThat(adder.isChanged()).isTrue()
      assertThat(adder.added()).containsExactly(new)
      assertThat(adder.skipped()).containsExactly(alreadyThere)
    }

    @Test fun `reports the catalog accessors it used so the version catalog can be updated`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation(libs.okio)
          }
        """.trimIndent()

      // When accessors are mixed with a string literal and one accessor is already declared
      val adder = DependencyAdder.of(
        buildScript,
        listOf(
          Addition("implementation", Accessor("libs.okhttp")),
          Addition("implementation", StringLiteral("com.squareup.moshi:moshi:1.15.2")),
          Addition("implementation", Accessor("libs.okio")),
          Addition("api", Accessor("libs.jackson.bom"), Capability.PLATFORM),
        ),
      )

      // Then only the accessors actually inserted are reported
      assertThat(adder.usedAccessors()).containsExactly("libs.okhttp", "libs.jackson.bom")
    }
  }

  @Nested inner class `rejecting bad input` {

    @Disabled("pending") @Test fun `throws when the script does not parse`() {}

    @Disabled("pending") @Test fun `throws when coordinates are not group-colon-name with an optional version`() {}

    @Disabled("pending") @Test fun `throws when the same dependency is requested twice with different versions`() {}
  }
}
