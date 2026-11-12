package cash.buildscript

import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Maven Central repository: the versions it has published of a [Module], and a search of its index by keyword.
 *
 * Both read XML over HTTP and block until Maven Central has answered.
 */
public class MavenCentral @JvmOverloads constructor(
  private val repository: URI = URI("https://repo1.maven.org/maven2/"),
  private val searchIndex: URI = URI("https://central.sonatype.com/solrsearch/select"),
) {

  private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

  /**
   * Every version of [module] the repository lists, in the order it lists them, unsorted. That order follows version
   * numbers only roughly: a pre-release can come after its release (`2.10.0`, then `2.10.0.pr1`), so the last entry
   * is not always the newest. Pre-release versions are included as listed. Which one to use is the caller's choice.
   *
   * @throws UnpublishedModule if the repository has no such module.
   * @throws UnreachableRepository if Maven Central does not answer.
   * @throws RejectedRequest if it answers with an error status.
   * @throws UnreadableReply if its answer is not a list of versions.
   */
  public fun listVersions(module: Module): List<ModuleVersion> {
    val name = "${module.group}:${module.name}"
    val base = repository.toString().trimEnd('/')
    val address = try {
      URI("$base/${module.group.replace('.', '/')}/${module.name}/maven-metadata.xml")
    } catch (e: URISyntaxException) {
      throw UnpublishedModule("$name cannot be a published module: ${e.message}")
    }
    val unreadable = "Maven Central's answer for $name is not a list of versions"

    val metadata = ask(address, unreadable) { UnpublishedModule("Maven Central has no module $name.") }
    val versions = metadata.takeIf { it.tagName == "metadata" }?.child("versioning")?.child("versions")
      ?: throw UnreadableReply("$unreadable.")
    return versions.children("version").map { ModuleVersion(module, RequiredVersion(it.textContent.trim())) }
  }

  /**
   * The modules whose Group or Name matches [keyword], in the order Maven Central gives them, each once. No more
   * than [limit] are returned, 20 unless another is named, as `npm search` does; none when nothing matches. A match
   * carries no version: [listVersions] supplies those.
   *
   * The keyword is sent as typed. Maven Central rejects one that contains a space.
   *
   * @throws IllegalArgumentException if [keyword] is blank or [limit] is below one.
   * @throws UnreachableRepository if Maven Central does not answer.
   * @throws RejectedRequest if it answers with an error status.
   * @throws UnreadableReply if its answer is not a search reply.
   */
  @JvmOverloads
  public fun search(keyword: String, limit: Int = 20): List<Module> {
    require(keyword.isNotBlank()) { "A search needs a keyword." }
    require(limit >= 1) { "A search needs a limit of at least one, not $limit." }
    val address = URI("$searchIndex?q=${URLEncoder.encode(keyword, Charsets.UTF_8)}&rows=$limit&wt=xml")
    val unreadable = "Maven Central's answer to a search for '$keyword' is not a search reply"

    val reply = ask(address, unreadable) { RejectedRequest(404, "Maven Central answered 404 at $address.") }
    val matches = reply.takeIf { it.tagName == "SolrWrappedResponse" }?.child("response")?.child("docs")
      ?: throw UnreadableReply("$unreadable.")
    return matches.children("docs")
      .map { match ->
        val group = match.child("g")?.textContent?.trim().orEmpty()
        val name = match.child("a")?.textContent?.trim().orEmpty()
        if (group.isEmpty() || name.isEmpty()) throw UnreadableReply("$unreadable: a match has no Group or no Name.")
        Module(group, name)
      }
      .distinct()
      .take(limit)
  }

  /** The root element of the XML that Maven Central answers with at [address]. */
  private fun ask(address: URI, unreadable: String, missing: () -> MavenCentralFailure): Element {
    val answer = try {
      http.send(HttpRequest.newBuilder(address).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    } catch (e: IOException) {
      throw UnreachableRepository("Maven Central did not answer at $address: ${e.message ?: e.javaClass.simpleName}")
    }
    answer.body().use { body ->
      when {
        answer.statusCode() == 404 -> throw missing()
        answer.statusCode() !in 200..299 ->
          throw RejectedRequest(answer.statusCode(), "Maven Central answered ${answer.statusCode()} at $address.")
      }
      return try {
        val parsers = DocumentBuilderFactory.newInstance()
        parsers.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        val parser = parsers.newDocumentBuilder()
        parser.setErrorHandler(null) // failures are thrown, not printed
        parser.parse(body).documentElement
      } catch (e: SAXException) {
        throw UnreadableReply("$unreadable: ${e.message}")
      } catch (e: IOException) {
        throw UnreachableRepository("Maven Central stopped answering at $address: ${e.message ?: e.javaClass.simpleName}")
      }
    }
  }

  private fun Element.children(tag: String): List<Element> =
    (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }

  private fun Element.child(tag: String): Element? = children(tag).firstOrNull()
}

/** Why [MavenCentral] could not answer. */
public sealed class MavenCentralFailure(message: String) : RuntimeException(message)

/** The repository has no such module. */
public class UnpublishedModule(message: String) : MavenCentralFailure(message)

/** Maven Central did not answer. */
public class UnreachableRepository(message: String) : MavenCentralFailure(message)

/** Maven Central answered with an error [status], such as 429 when it is limiting requests. */
public class RejectedRequest(public val status: Int, message: String) : MavenCentralFailure(message)

/** Maven Central answered with something that could not be read as what was asked for. */
public class UnreadableReply(message: String) : MavenCentralFailure(message)
