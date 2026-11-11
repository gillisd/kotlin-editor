package cash.buildscript

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Skeleton for the object layer. Each spec stays @Disabled("pending") until it is written.
//
// Wording: a name in CamelCase (ExternalModuleDependency, ModuleVersion) is a type in the layer. A capitalized
// word that is not a type (Group, Name) is a domain term. Kotlin does not allow a period or a colon in a test
// name, so "the version catalog" stands for libs.versions.toml and strings are described, not quoted.
//
// Not covered yet: a string form for plugin dependencies, which is still an open question in the plan.
internal class BuildScriptSpec {

  @Nested inner class `opening a build script` {

    @Disabled("pending") @Test fun `lists each declaration in the dependencies block as a Dependency`() {}

    @Disabled("pending") @Test fun `reads a declaration with a Group, a Name and a version as an ExternalModuleDependency on a ModuleVersion with a RequiredVersion`() {}

    @Disabled("pending") @Test fun `reads a declaration with a Group and a Name only as a ModuleVersion with UnspecifiedVersion`() {}

    @Disabled("pending") @Test fun `reads a declaration written through the version catalog as an ExternalModuleDependency with a TypeSafeAccessor`() {}

    @Disabled("pending") @Test fun `reads project() as a ProjectDependency with its ProjectPath`() {}

    @Disabled("pending") @Test fun `reads platform(), enforcedPlatform() and testFixtures() as the DependencyModifier`() {}

    @Disabled("pending") @Test fun `reads a declaration with no modifier as Unmodified`() {}

    @Disabled("pending") @Test fun `reads the Configuration of each Dependency`() {}

    @Disabled("pending") @Test fun `lists each entry in the plugins block as a PluginDependency`() {}

    @Disabled("pending") @Test fun `reads id() with a version as a PluginId and a RequiredVersion`() {}

    @Disabled("pending") @Test fun `reads a plugin declared without a version as UnspecifiedVersion`() {}

    @Disabled("pending") @Test fun `reads alias() as a PluginDependency with a TypeSafeAccessor`() {}

    @Disabled("pending") @Test fun `ignores dependencies blocks and plugins blocks that are not top-level`() {}

    @Disabled("pending") @Test fun `returns the source byte-for-byte when nothing has changed`() {}

    @Disabled("pending") @Test fun `fails when the script does not parse`() {}
  }

  @Nested inner class `adding a Dependency` {

    @Disabled("pending") @Test fun `adds an ExternalModuleDependency after the last declaration on the same Configuration`() {}

    @Disabled("pending") @Test fun `adds an ExternalModuleDependency written through a TypeSafeAccessor`() {}

    @Disabled("pending") @Test fun `adds a ProjectDependency`() {}

    @Disabled("pending") @Test fun `writes the DependencyModifier around the dependency`() {}

    @Disabled("pending") @Test fun `creates the dependencies block when the script has none`() {}

    @Disabled("pending") @Test fun `adds the same Module again on a different Configuration`() {}

    @Disabled("pending") @Test fun `succeeds and reports already declared when the Module is on that Configuration at the same Version`() {}

    @Disabled("pending") @Test fun `fails, pointing to pin, when the Module is on that Configuration at a different Version`() {}

    @Disabled("pending") @Test fun `returns what was added`() {}
  }

  @Nested inner class `adding a Dependency from a string` {

    @Disabled("pending") @Test fun `reads three segments as a Group, a Name and a RequiredVersion`() {}

    @Disabled("pending") @Test fun `reads two segments as a Group and a Name with UnspecifiedVersion`() {}

    @Disabled("pending") @Test fun `reads a dotted string with no colon as a TypeSafeAccessor`() {}

    @Disabled("pending") @Test fun `reads a string that starts with a colon as a ProjectPath`() {}

    @Disabled("pending") @Test fun `uses the implementation Configuration when none is named`() {}

    @Disabled("pending") @Test fun `uses the Configuration that is named`() {}

    @Disabled("pending") @Test fun `uses Unmodified when no DependencyModifier is named`() {}

    @Disabled("pending") @Test fun `uses the DependencyModifier that is named`() {}

    @Disabled("pending") @Test fun `writes the string as given without checking that it resolves`() {}

    @Disabled("pending") @Test fun `fails when the string has none of the four shapes`() {}
  }

  @Nested inner class `pinning a ModuleVersion` {

    @Disabled("pending") @Test fun `sets the Version of that Module on every Configuration it is declared on`() {}

    @Disabled("pending") @Test fun `accepts a ModuleVersion or its string form`() {}

    @Disabled("pending") @Test fun `keeps the DependencyModifier, a trailing block and comments`() {}

    @Disabled("pending") @Test fun `succeeds and reports unchanged when the Module is already at that Version`() {}

    @Disabled("pending") @Test fun `fails, naming the Module, when it is not declared`() {}

    @Disabled("pending") @Test fun `fails, saying the Version is in the version catalog, when the Module is declared through a TypeSafeAccessor`() {}

    @Disabled("pending") @Test fun `fails when the string form has no Version`() {}

    @Disabled("pending") @Test fun `returns what was pinned`() {}
  }

  @Nested inner class `removing a Dependency` {

    @Disabled("pending") @Test fun `removes the declaration and its line`() {}

    @Disabled("pending") @Test fun `matches on the Group and the Name, whatever the Version`() {}

    @Disabled("pending") @Test fun `removes from the named Configuration only`() {}

    @Disabled("pending") @Test fun `removes from every Configuration when none is named`() {}

    @Disabled("pending") @Test fun `removes an ExternalModuleDependency written through a TypeSafeAccessor`() {}

    @Disabled("pending") @Test fun `removes a ProjectDependency`() {}

    @Disabled("pending") @Test fun `fails, naming what was not found, when it is not declared`() {}

    @Disabled("pending") @Test fun `returns what was removed`() {}
  }

  @Nested inner class `adding a PluginDependency` {

    @Disabled("pending") @Test fun `adds the entry to the plugins block`() {}

    @Disabled("pending") @Test fun `creates the plugins block when the script has none`() {}

    @Disabled("pending") @Test fun `succeeds and reports already declared when that PluginId is there at the same Version`() {}

    @Disabled("pending") @Test fun `fails, pointing to pin, when that PluginId is there at a different Version`() {}

    @Disabled("pending") @Test fun `returns what was added`() {}
  }

  @Nested inner class `pinning a PluginDependency` {

    @Disabled("pending") @Test fun `sets the Version of that PluginId`() {}

    @Disabled("pending") @Test fun `keeps apply false and everything else on the line`() {}

    @Disabled("pending") @Test fun `succeeds and reports unchanged when the plugin is already at that Version`() {}

    @Disabled("pending") @Test fun `fails, naming the PluginId, when it is not declared`() {}

    @Disabled("pending") @Test fun `fails, saying there is no Version to change, when the plugin is declared without one`() {}

    @Disabled("pending") @Test fun `fails, saying the Version is in the version catalog, when the plugin is declared through a TypeSafeAccessor`() {}

    @Disabled("pending") @Test fun `returns what was pinned`() {}
  }

  @Nested inner class `removing a PluginDependency` {

    @Disabled("pending") @Test fun `removes the entry and its line`() {}

    @Disabled("pending") @Test fun `fails, naming the PluginId, when it is not declared`() {}

    @Disabled("pending") @Test fun `returns what was removed`() {}
  }

  @Nested inner class `keeping the rest of the script as written` {

    @Disabled("pending") @Test fun `leaves every line an operation did not change byte-for-byte`() {}

    @Disabled("pending") @Test fun `leaves file dependencies, rich versions and trailing blocks as written`() {}

    @Disabled("pending") @Test fun `leaves the script unchanged when an operation fails`() {}
  }

  @Nested inner class `saving` {

    @Disabled("pending") @Test fun `writes the source back to the file it was opened from`() {}

    @Disabled("pending") @Test fun `leaves the file untouched when nothing has changed`() {}

    @Disabled("pending") @Test fun `applies several operations in the order they were called`() {}

    @Disabled("pending") @Test fun `reads the same parts after saving and opening again`() {}
  }
}
