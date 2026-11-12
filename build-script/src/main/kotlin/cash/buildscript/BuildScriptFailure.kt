package cash.buildscript

/** Why a [BuildScript] operation could not do what was asked. The script is left unchanged. */
public sealed class BuildScriptFailure(message: String) : RuntimeException(message)

/** `pin` or `remove` named a dependency that the script does not declare. */
public class UndeclaredDependency(message: String) : BuildScriptFailure(message)

/** `pin` or `remove` named a plugin that the script does not declare. */
public class UndeclaredPlugin(message: String) : BuildScriptFailure(message)

/** `add` named something the script already declares at a different version; `pin` is how to change it. */
public class ConflictingVersion(message: String) : BuildScriptFailure(message)

/** `pin` was given a type-safe accessor: its version is in the version catalog, which the layer does not edit. */
public class VersionInCatalog(message: String) : BuildScriptFailure(message)

/** `pin` was given no version, or the plugin it named is declared without one. */
public class MissingVersion(message: String) : BuildScriptFailure(message)

/** A dependency could not be read or written: a string of no known shape, a version given twice, or a bad name. */
public class UnreadableDependency(message: String) : BuildScriptFailure(message)
