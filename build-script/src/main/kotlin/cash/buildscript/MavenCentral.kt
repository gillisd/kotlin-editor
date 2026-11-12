package cash.buildscript

import java.net.URI

/**
 * The Maven Central repository: the versions it has published of a [Module], and a search of its index by keyword.
 *
 * Skeleton: the surface is proposed and nothing is implemented yet. See MavenCentralSpec for what it will do.
 */
public class MavenCentral @JvmOverloads constructor(
  private val repository: URI = URI("https://repo1.maven.org/maven2/"),
  private val searchIndex: URI = URI("https://central.sonatype.com/solrsearch/select"),
) {

  /**
   * Every version of [module] the repository lists, in the order it lists them, oldest first. Pre-release versions
   * are included as listed: which one to use is the caller's choice.
   *
   * @throws UnpublishedModule if the repository has no such module.
   * @throws UnreachableRepository if Maven Central does not answer.
   * @throws RejectedRequest if it answers with an error status.
   * @throws UnreadableReply if its answer is not a list of versions.
   */
  public fun listVersions(module: Module): List<ModuleVersion> = TODO("pending: $module $repository")

  /**
   * The modules whose Group or Name matches [keyword], in the order Maven Central ranks them. No more than [limit]
   * are returned; none when nothing matches. A match carries no version: [listVersions] supplies those.
   *
   * @throws UnreachableRepository if Maven Central does not answer.
   * @throws RejectedRequest if it answers with an error status.
   * @throws UnreadableReply if its answer is not a search reply.
   */
  @JvmOverloads
  public fun search(keyword: String, limit: Int = 20): List<Module> = TODO("pending: $keyword $limit $searchIndex")
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
