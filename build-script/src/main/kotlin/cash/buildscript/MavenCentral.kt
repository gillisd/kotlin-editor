package cash.buildscript

import java.net.URI

/**
 * The Maven Central repository, searched by keyword.
 *
 * Skeleton: the surface is proposed and nothing is implemented yet. See MavenCentralSpec for what it will do.
 */
public class MavenCentral @JvmOverloads constructor(
  private val searchEndpoint: URI = URI("https://search.maven.org/solrsearch/select"),
) {

  /**
   * The modules whose Group or Name matches [keyword], each at its latest version, in the order Maven Central ranks
   * them. No more than [limit] are returned; none when nothing matches.
   *
   * @throws UnreachableRepository if Maven Central does not answer, or answers with an error.
   * @throws UnreadableReply if its answer is not a search reply.
   */
  @JvmOverloads
  public fun search(keyword: String, limit: Int = 20): List<ModuleVersion> = TODO("pending: $keyword $limit $searchEndpoint")
}

/** Why a [MavenCentral] search could not be answered. */
public sealed class MavenCentralFailure(message: String) : RuntimeException(message)

/** Maven Central did not answer, or answered with an error. */
public class UnreachableRepository(message: String) : MavenCentralFailure(message)

/** Maven Central answered with something that is not a search reply. */
public class UnreadableReply(message: String) : MavenCentralFailure(message)
