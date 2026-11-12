package cash.buildscript

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

// Specs for Maven Central.
//
// Wording follows BuildScriptSpec: a name in CamelCase is a type in the layer; a capitalized word that is not a type
// (Group, Name) is a domain term. Every spec runs against a recorded reply, never the live service: the replies in
// src/test/resources/maven-central were recorded from repo1.maven.org and central.sonatype.com on 12 November 2026 and
// are served from a local address that stands in for both.
internal class MavenCentralSpec {

  @TempDir lateinit var directory: Path

  private val okio = Module("com.squareup.okio", "okio")
  private val jacksonDatabind = Module("com.fasterxml.jackson.core", "jackson-databind")

  private val index = "/solrsearch/select"

  private lateinit var stand: HttpServer
  private lateinit var central: MavenCentral
  private val asked = mutableListOf<String>()

  // What the stand answers at each path: a status and a body. Any other path is answered 404, as the repository does.
  private val answers = mutableMapOf<String, Pair<Int, String>>()

  @BeforeEach fun startStand() {
    stand = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    stand.createContext("/") { exchange ->
      asked += exchange.requestURI.toString()
      val (status, body) = answers[exchange.requestURI.path] ?: (404 to "")
      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
      if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
      exchange.close()
    }
    stand.start()
    central = MavenCentral(
      repository = URI("http://127.0.0.1:${stand.address.port}/maven2/"),
      searchIndex = URI("http://127.0.0.1:${stand.address.port}/solrsearch/select"),
    )
    answers[index] = 200 to recorded("search-okhttp.xml")
    answers["/maven2/com/squareup/okio/okio/maven-metadata.xml"] = 200 to recorded("okio.xml")
    answers["/maven2/com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml"] =
      200 to recorded("jackson-databind.xml")
  }

  @AfterEach fun stopStand() {
    stand.stop(0)
  }

  private fun recorded(name: String): String =
    checkNotNull(javaClass.getResource("/maven-central/$name")) { "no recorded reply $name" }.readText()

  private fun List<ModuleVersion>.texts(): List<String> = map { (it.version as RequiredVersion).text }

  @Nested inner class `listing the versions of a Module` {
    @Test fun `returns a ModuleVersion for every version the repository lists`() {
      val published = central.listVersions(okio)

      assertThat(published).hasSize(90)
      assertThat(published.first()).isEqualTo(ModuleVersion(okio, RequiredVersion("0.5.0")))
      assertThat(published.last()).isEqualTo(ModuleVersion(okio, RequiredVersion("3.18.2")))
      assertThat(published.map { it.module }).containsOnly(okio)
    }

    @Test fun `keeps the order the repository lists them in, without sorting`() {
      val texts = central.listVersions(jacksonDatabind).texts()

      // The repository lists 2.6.7 after its own patch versions; so does the list.
      assertThat(texts.subList(texts.indexOf("2.6.7.1"), texts.indexOf("2.6.7") + 1))
        .containsExactly("2.6.7.1", "2.6.7.2", "2.6.7.3", "2.6.7.4", "2.6.7.5", "2.6.7")
      assertThat(texts).hasSize(239).doesNotHaveDuplicates()
    }

    @Test fun `includes pre-release versions as listed`() {
      val texts = central.listVersions(jacksonDatabind).texts()

      assertThat(texts).contains("2.0.0-RC1", "2.9.0.pr1", "2.10.0.pr3")
    }

    @Test fun `leaves a pre-release that the repository lists after its release where it is`() {
      val texts = central.listVersions(jacksonDatabind).texts()

      assertThat(texts.subList(texts.indexOf("2.10.0"), texts.indexOf("2.10.0") + 4))
        .containsExactly("2.10.0", "2.10.0.pr1", "2.10.0.pr2", "2.10.0.pr3")
    }

    @Test fun `asks the repository at the path made from the Group and the Name`() {
      central.listVersions(jacksonDatabind)

      // Each part of the Group is a folder; the Name is one folder, periods and all.
      assertThat(asked).containsExactly("/maven2/com/fasterxml/jackson/core/jackson-databind/maven-metadata.xml")

      // A repository address without its closing slash reaches the same place.
      MavenCentral(repository = URI("http://127.0.0.1:${stand.address.port}/maven2")).listVersions(okio)
      assertThat(asked.last()).isEqualTo("/maven2/com/squareup/okio/okio/maven-metadata.xml")
    }

    @Test fun `fails with UnpublishedModule when the repository has no such Module`() {
      assertThatThrownBy { central.listVersions(Module("com.example", "not-there")) }
        .isInstanceOf(UnpublishedModule::class.java)
        .hasMessageContaining("com.example:not-there")
      assertThatThrownBy { central.listVersions(Module("com example", "not there")) }
        .isInstanceOf(UnpublishedModule::class.java)
    }
  }

  @Nested inner class `using what is listed in a BuildScript` {
    @Test fun `pins a declared Module to a listed ModuleVersion`() {
      val file = directory.resolve("build.gradle.kts")
      Files.writeString(file, "dependencies {\n  implementation(\"com.squareup.okio:okio:3.9.0\")\n}\n")
      val script = BuildScript.open(file)

      script.pin(central.listVersions(okio).last())

      assertThat(script.source).isEqualTo("dependencies {\n  implementation(\"com.squareup.okio:okio:3.18.2\")\n}\n")
    }

    @Test fun `adds a listed ModuleVersion as an ExternalModuleDependency`() {
      val file = directory.resolve("build.gradle.kts")
      Files.writeString(file, "dependencies {\n}\n")
      val script = BuildScript.open(file)

      script.add(ExternalModuleDependency(Configuration("implementation"), central.listVersions(okio).first()))

      assertThat(script.source).isEqualTo("dependencies {\n  implementation(\"com.squareup.okio:okio:0.5.0\")\n}\n")
    }
  }

  @Nested inner class `searching by keyword` {
    @Test fun `returns a Module for each match, with no Version`() {
      val found: List<Module> = central.search("okhttp")

      assertThat(found).hasSize(20).contains(Module("com.squareup.okhttp3", "okhttp"))
    }

    @Test fun `returns each Module once`() {
      val twice = "<docs><g>com.foo</g><a>bar</a></docs>"
      answers[index] = 200 to "<SolrWrappedResponse><response><docs>$twice$twice</docs></response></SolrWrappedResponse>"

      assertThat(central.search("bar")).containsExactly(Module("com.foo", "bar"))
    }

    @Test fun `keeps the order Maven Central gives them in`() {
      val found = central.search("okhttp")

      assertThat(found.take(3)).containsExactly(
        Module("com.squareup.okhttp", "benchmarks"),
        Module("com.commsen.wedeploy", "com.commsen.wedeploy.transport.rest.okhttp"),
        Module("eu.the4thfloor.mockwebserver", "com.squareup.okhttp.mockwebserver"),
      )
      // Square's own library is the eighteenth of the twenty recorded: the order is Maven Central's, not ours.
      assertThat(found.indexOf(Module("com.squareup.okhttp3", "okhttp"))).isEqualTo(17)
    }

    @Test fun `returns an empty list when nothing matches`() {
      answers[index] = 200 to recorded("search-no-match.xml")

      assertThat(central.search("zzzqqqxxnomatch")).isEmpty()
    }

    @Test fun `sends the keyword as typed, encoding symbols`() {
      central.search("a&b=c")

      assertThat(asked.single()).startsWith("$index?q=a%26b%3Dc&")
    }

    @Test fun `asks for 20 when no limit is named`() {
      central.search("okhttp")

      assertThat(asked.single()).isEqualTo("$index?q=okhttp&rows=20&wt=xml")
    }

    @Test fun `asks for the limit that is named`() {
      val found = central.search("okhttp", limit = 5)

      assertThat(asked.single()).contains("rows=5")
      // No more than the limit comes back, whatever Maven Central sends.
      assertThat(found).hasSize(5)
    }
  }

  @Nested inner class `when Maven Central cannot answer` {
    @Test fun `fails with UnreachableRepository when it does not answer`() {
      stand.stop(0)

      assertThatThrownBy { central.listVersions(okio) }
        .isInstanceOf(UnreachableRepository::class.java)
        .hasMessageContaining("maven-metadata.xml")
      assertThatThrownBy { central.search("okhttp") }.isInstanceOf(UnreachableRepository::class.java)
    }

    @Test fun `fails with RejectedRequest, carrying the status, when it answers with an error status`() {
      val path = "/maven2/com/squareup/okio/okio/maven-metadata.xml"

      listOf(429, 500, 503).forEach { status ->
        answers[path] = status to "slow down"
        assertThatThrownBy { central.listVersions(okio) }
          .isInstanceOfSatisfying(RejectedRequest::class.java) { assertThat(it.status).isEqualTo(status) }
        answers[index] = status to "slow down"
        assertThatThrownBy { central.search("okhttp") }
          .isInstanceOfSatisfying(RejectedRequest::class.java) { assertThat(it.status).isEqualTo(status) }
      }
    }

    @Test fun `fails with RejectedRequest when Maven Central rejects the keyword, as it does one with a space`() {
      // Recorded behaviour: central.sonatype.com answers 400 to q=json+parser.
      answers[index] = 400 to ""

      assertThatThrownBy { central.search("json parser") }
        .isInstanceOfSatisfying(RejectedRequest::class.java) { assertThat(it.status).isEqualTo(400) }
      assertThat(asked.single()).startsWith("$index?q=json+parser&")
    }

    @Test fun `fails with UnreadableReply when the answer is not a list of versions`() {
      val path = "/maven2/com/squareup/okio/okio/maven-metadata.xml"

      listOf(
        "<html><body>Be right back</body></html>",
        "not xml at all",
        "<metadata><versioning></versioning></metadata>",
        "<versions><version>1.0</version></versions>",
        "<project><versioning><versions><version>1.0</version></versions></versioning></project>",
        "<!DOCTYPE metadata [<!ENTITY x \"y\">]><metadata><versioning><versions/></versioning></metadata>",
      ).forEach { unreadable ->
        answers[path] = 200 to unreadable
        assertThatThrownBy { central.listVersions(okio) }
          .describedAs(unreadable)
          .isInstanceOf(UnreadableReply::class.java)
      }

      // A module with no versions listed is readable: it has none.
      answers[path] = 200 to "<metadata><versioning><versions/></versioning></metadata>"
      assertThat(central.listVersions(okio)).isEmpty()
    }

    @Test fun `fails with UnreadableReply when the answer is not a search reply`() {
      listOf(
        "<html><body>Be right back</body></html>",
        "{\"response\":{\"docs\":[]}}",
        "<response><docs/></response>",
        "<html><response><docs/></response></html>",
        "<SolrWrappedResponse><response/></SolrWrappedResponse>",
        "<SolrWrappedResponse><response><docs><docs><g>com.foo</g></docs></docs></response></SolrWrappedResponse>",
        "<SolrWrappedResponse><response><docs><docs><a>bar</a></docs></docs></response></SolrWrappedResponse>",
      ).forEach { unreadable ->
        answers[index] = 200 to unreadable
        assertThatThrownBy { central.search("okhttp") }
          .describedAs(unreadable)
          .isInstanceOf(UnreadableReply::class.java)
      }
    }

    @Test fun `refuses a blank keyword and a limit below one without asking`() {
      assertThatThrownBy { central.search("") }.isInstanceOf(IllegalArgumentException::class.java)
      assertThatThrownBy { central.search("   ") }.isInstanceOf(IllegalArgumentException::class.java)
      assertThatThrownBy { central.search("okhttp", limit = 0) }.isInstanceOf(IllegalArgumentException::class.java)

      assertThat(asked).isEmpty()
    }
  }
}
