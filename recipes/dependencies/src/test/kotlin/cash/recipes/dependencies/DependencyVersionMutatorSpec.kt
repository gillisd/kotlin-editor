package cash.recipes.dependencies

import cash.grammar.kotlindsl.parse.KotlinParseException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class DependencyVersionMutatorSpec {

  @Nested inner class `changing a version` {

    @Test fun `changes the version of every declaration of the module, on whatever configuration`() {
      // Given
      val buildScript =
        """
          plugins {
            application
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("com.squareup.okio:okio:3.8.0")
          }
        """.trimIndent()

      // When
      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.squareup.okio:okio" to "3.18.2"))

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          plugins {
            application
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.18.2")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("com.squareup.okio:okio:3.18.2")
          }
        """.trimIndent()
      )
      assertThat(mutator.changed()).containsExactly("com.squareup.okio:okio")
    }

    @Test fun `changes several modules in one pass`() {
      val buildScript =
        """
          dependencies {
            implementation("com.foo:bar:1.0")
            implementation("com.foo:baz:1.0")
          }
        """.trimIndent()

      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.foo:bar" to "2.0", "com.foo:baz" to "3.0"))

      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.foo:bar:2.0")
            implementation("com.foo:baz:3.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `keeps a wrapper, a trailing block, a custom configuration and comments`() {
      // Given
      val buildScript =
        """
          dependencies {
            // the bill of materials
            api(platform("com.foo:bom:1.0")) // pinned
            testImplementation(testFixtures("com.foo:bar:1.0"))
            implementation("com.foo:bar:1.0") {
              exclude(group = "org.unwanted", module = "thing")
            }
            "custom"("com.foo:bar:1.0")
          }
        """.trimIndent()

      // When
      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.foo:bom" to "2.0", "com.foo:bar" to "2.0"))

      // Then
      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            // the bill of materials
            api(platform("com.foo:bom:2.0")) // pinned
            testImplementation(testFixtures("com.foo:bar:2.0"))
            implementation("com.foo:bar:2.0") {
              exclude(group = "org.unwanted", module = "thing")
            }
            "custom"("com.foo:bar:2.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `keeps a classifier and an extension`() {
      val buildScript =
        """
          dependencies {
            implementation("com.foo:bar:1.0:sources")
            implementation("com.foo:bar:1.0@zip")
            implementation("com.foo:bar:1.0:linux@tar.gz")
          }
        """.trimIndent()

      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.foo:bar" to "2.0"))

      assertThat(mutator.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.foo:bar:2.0:sources")
            implementation("com.foo:bar:2.0@zip")
            implementation("com.foo:bar:2.0:linux@tar.gz")
          }
        """.trimIndent()
      )
    }

    @Test fun `gives a version to a declaration that has none`() {
      val buildScript = "dependencies {\n  implementation(\"com.foo:bar\")\n}\n"

      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.foo:bar" to "2.0"))

      assertThat(mutator.rewritten()).isEqualTo("dependencies {\n  implementation(\"com.foo:bar:2.0\")\n}\n")
    }
  }

  @Nested inner class `leaving things alone` {

    @Test fun `leaves a module already at that version, and reports it`() {
      val buildScript = "dependencies {\n  implementation(\"com.foo:bar:2.0\")\n}\n"

      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.foo:bar" to "2.0"))

      assertThat(mutator.isChanged()).isFalse()
      assertThat(mutator.rewritten()).isEqualTo(buildScript)
      assertThat(mutator.skipped()).containsEntry("com.foo:bar", "already at 2.0")
    }

    @Test fun `leaves a template string, and reports it`() {
      val buildScript =
        """
          val barVersion = "1.0"

          dependencies {
            implementation("com.foo:bar:${'$'}barVersion")
          }
        """.trimIndent()

      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.foo:bar" to "2.0"))

      assertThat(mutator.rewritten()).isEqualTo(buildScript)
      assertThat(mutator.skipped()).containsEntry("com.foo:bar", "version is not a string literal")
      assertThat(mutator.notFound()).isEmpty()
    }

    @Test fun `leaves other modules, accessors and project dependencies`() {
      val buildScript =
        """
          dependencies {
            implementation("com.foo:barrel:1.0")
            implementation("org.foo:bar:1.0")
            implementation(libs.bar)
            implementation(project(":bar"))
          }
        """.trimIndent()

      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.foo:bar" to "2.0"))

      assertThat(mutator.rewritten()).isEqualTo(buildScript)
      assertThat(mutator.notFound()).containsExactly("com.foo:bar")
    }

    @Test fun `leaves a dependencies block that is not the script's own`() {
      val buildScript =
        """
          buildscript {
            dependencies {
              classpath("com.foo:bar:1.0")
            }
          }

          dependencies {
            implementation("com.foo:bar:1.0")
          }
        """.trimIndent()

      val mutator = DependencyVersionMutator.of(buildScript, mapOf("com.foo:bar" to "2.0"))

      assertThat(mutator.rewritten()).isEqualTo(
        """
          buildscript {
            dependencies {
              classpath("com.foo:bar:1.0")
            }
          }

          dependencies {
            implementation("com.foo:bar:2.0")
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `reporting and rejecting` {

    @Test fun `reports the modules that are not declared`() {
      val mutator = DependencyVersionMutator.of(
        "dependencies {\n  implementation(\"com.foo:bar:1.0\")\n}\n",
        mapOf("com.foo:bar" to "2.0", "com.foo:absent" to "1.0"),
      )

      assertThat(mutator.changed()).containsExactly("com.foo:bar")
      assertThat(mutator.notFound()).containsExactly("com.foo:absent")
    }

    @Test fun `throws when the script does not parse`() {
      assertThatThrownBy {
        DependencyVersionMutator.of("dependencies {\n  implementation(\"x\"\n}", mapOf("com.foo:bar" to "2.0")).rewritten()
      }.isInstanceOf(KotlinParseException::class.java)
    }

    @Test fun `throws when a module or a version is malformed`() {
      listOf("bar" to "1.0", "com.foo:bar:1.0" to "2.0", "com.foo:bar" to "2 .0", "com.foo:bar" to "").forEach { bad ->
        assertThatThrownBy { DependencyVersionMutator.of("dependencies {}", mapOf(bad)) }
          .describedAs("%s", bad)
          .isInstanceOf(IllegalArgumentException::class.java)
      }
    }
  }
}
