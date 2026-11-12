package cash.recipes.dependencies

import cash.grammar.kotlindsl.parse.KotlinParseException
import cash.recipes.dependencies.DependencyRemover.Removal
import cash.recipes.dependencies.transform.Transform.Element.Accessor
import cash.recipes.dependencies.transform.Transform.Element.StringLiteral
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

internal class DependencyRemoverSpec {

  private val okhttp = Removal(StringLiteral("com.squareup.okhttp3:okhttp"))

  @Nested inner class `removing from the dependencies block` {

    @Test fun `removes a string-literal declaration matched by group and name, whatever its version`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(buildScript, listOf(okhttp))

      // Then
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `removes a version catalog accessor declaration`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation(libs.okhttp)
            implementation(libs.picocli)
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(buildScript, listOf(Removal(Accessor("libs.picocli"))))

      // Then
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies {
            implementation(libs.okhttp)
          }
        """.trimIndent()
      )
    }

    @Test fun `removes a declaration with a trailing lambda, lambda included`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("org.xerial:sqlite-jdbc:3.54.1.0") {
              exclude(group = "org.slf4j")
            }
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(buildScript, listOf(Removal(StringLiteral("org.xerial:sqlite-jdbc"))))

      // Then
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `removes only from the given configuration, leaving the same dependency on others`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            testImplementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(
        buildScript,
        listOf(Removal(StringLiteral("com.squareup.okhttp3:okhttp"), configuration = "testImplementation")),
      )

      // Then
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      )
    }

    @Test fun `removes from every configuration when no configuration is given`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            implementation("com.squareup.okio:okio:3.9.0")
            testImplementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(buildScript, listOf(okhttp))

      // Then
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `removes several declarations in one pass`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            implementation(libs.picocli)
            implementation("com.squareup.okio:okio:3.9.0")
            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(
        buildScript,
        listOf(okhttp, Removal(Accessor("libs.picocli")), Removal(StringLiteral("org.junit.jupiter:junit-jupiter"))),
      )

      // Then
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `ignores dependencies blocks that are not top-level, such as buildscript or a task`() {
      // Given okhttp on the buildscript classpath and in the real block
      val buildScript =
        """
          buildscript {
            dependencies {
              classpath("com.squareup.okhttp3:okhttp:5.5.1")
            }
          }

          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(buildScript, listOf(okhttp))

      // Then only the top-level declaration goes
      assertThat(remover.rewritten()).isEqualTo(
        """
          buildscript {
            dependencies {
              classpath("com.squareup.okhttp3:okhttp:5.5.1")
            }
          }

          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
          }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `keeping the rest of the file intact` {

    @Test fun `removes the whole line, leaving no blank line behind`() {
      // Given groups separated by a blank line
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")
            implementation("com.squareup.okhttp3:okhttp:5.5.1")

            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(buildScript, listOf(okhttp))

      // Then the declaration's line goes, and the existing blank line stays
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies {
            implementation("com.squareup.okio:okio:3.9.0")

            testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
          }
        """.trimIndent()
      )
    }

    @Test fun `removes an end-of-line comment with its declaration but keeps comment lines above it`() {
      // Given
      val buildScript =
        """
          dependencies {
            // networking
            implementation("com.squareup.okhttp3:okhttp:5.5.1") // http client
            implementation("com.squareup.okio:okio:3.9.0") // io
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(buildScript, listOf(okhttp))

      // Then
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies {
            // networking
            implementation("com.squareup.okio:okio:3.9.0") // io
          }
        """.trimIndent()
      )
    }

    @Test fun `leaves an empty dependencies block when the last declaration is removed`() {
      // Given
      val buildScript =
        """
          plugins {
            id("java-library")
          }

          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()

      // When
      val remover = DependencyRemover.of(buildScript, listOf(okhttp))

      // Then
      assertThat(remover.rewritten()).isEqualTo(
        """
          plugins {
            id("java-library")
          }

          dependencies {
          }
        """.trimIndent()
      )
    }

    @Test fun `removes from a one-line dependencies block`() {
      // Given
      val buildScript =
        """
          dependencies { implementation("com.squareup.okio:okio:3.9.0"); implementation("com.squareup.okhttp3:okhttp:5.5.1"); api(libs.picocli) }
        """.trimIndent()

      // When the middle and last ones go
      val remover = DependencyRemover.of(buildScript, listOf(okhttp, Removal(Accessor("libs.picocli"))))

      // Then the separators go with them
      assertThat(remover.rewritten()).isEqualTo(
        """
          dependencies { implementation("com.squareup.okio:okio:3.9.0") }
        """.trimIndent()
      )
    }
  }

  @Nested inner class `reporting what changed` {

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
      val remover = DependencyRemover.of(buildScript, listOf(okhttp))

      // Then
      assertThat(remover.isChanged()).isFalse()
      assertThat(remover.rewritten()).isEqualTo(buildScript)
    }

    @Test fun `reports which removals matched and which were not found`() {
      // Given
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1")
          }
        """.trimIndent()
      val moshi = Removal(StringLiteral("com.squareup.moshi:moshi"))

      // When
      val remover = DependencyRemover.of(buildScript, listOf(okhttp, moshi))

      // Then
      assertThat(remover.isChanged()).isTrue()
      assertThat(remover.removed()).containsExactly(okhttp)
      assertThat(remover.notFound()).containsExactly(moshi)
    }
  }

  @Nested inner class `rejecting bad input` {

    @Test fun `throws when the script does not parse`() {
      // Given a script with an unclosed call
      val buildScript =
        """
          dependencies {
            implementation("com.squareup.okhttp3:okhttp:5.5.1"
          }
        """.trimIndent()

      // Then the parse errors surface when the result is asked for
      assertThatThrownBy { DependencyRemover.of(buildScript, listOf(okhttp)).rewritten() }
        .isInstanceOf(KotlinParseException::class.java)
    }

    @Test fun `throws when a module is not group-colon-name`() {
      val buildScript = "dependencies {}"

      listOf("okhttp", "com.squareup.okhttp3:okhttp:5.5.1", "com.squareup okhttp3:okhttp", "").forEach { bad ->
        assertThatThrownBy { DependencyRemover.of(buildScript, listOf(Removal(StringLiteral(bad)))) }
          .describedAs("module '%s'", bad)
          .isInstanceOf(IllegalArgumentException::class.java)
          .hasMessageContaining("Invalid module")
      }
    }
  }
}
