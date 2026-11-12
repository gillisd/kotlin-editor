package cash.buildscript

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

// Specs for Maven Central. A spec stays @Disabled("pending") until it is written.
//
// Wording follows BuildScriptSpec: a name in CamelCase is a type in the layer; a capitalized word that is not a type
// (Group, Name) is a domain term. Every spec runs against a recorded reply, never the live service.
internal class MavenCentralSpec {

  @Nested inner class `listing the versions of a Module` {
    @Disabled("pending") @Test fun `returns a ModuleVersion for every version the repository lists`() {}

    @Disabled("pending") @Test fun `keeps the order the repository lists them in, without sorting`() {}

    @Disabled("pending") @Test fun `includes pre-release versions as listed`() {}

    @Disabled("pending") @Test fun `leaves a pre-release that the repository lists after its release where it is`() {}

    @Disabled("pending") @Test fun `asks the repository at the path made from the Group and the Name`() {}

    @Disabled("pending") @Test fun `fails with UnpublishedModule when the repository has no such Module`() {}
  }

  @Nested inner class `using what is listed in a BuildScript` {
    @Disabled("pending") @Test fun `pins a declared Module to a listed ModuleVersion`() {}

    @Disabled("pending") @Test fun `adds a listed ModuleVersion as an ExternalModuleDependency`() {}
  }

  @Nested inner class `searching by keyword` {
    @Disabled("pending") @Test fun `returns a Module for each match, with no Version`() {}

    @Disabled("pending") @Test fun `returns each Module once`() {}

    @Disabled("pending") @Test fun `keeps the order Maven Central ranks them in`() {}

    @Disabled("pending") @Test fun `returns an empty list when nothing matches`() {}

    @Disabled("pending") @Test fun `sends the keyword as typed, encoding spaces and symbols`() {}

    @Disabled("pending") @Test fun `asks for 20 when no limit is named`() {}

    @Disabled("pending") @Test fun `asks for the limit that is named`() {}
  }

  @Nested inner class `when Maven Central cannot answer` {
    @Disabled("pending") @Test fun `fails with UnreachableRepository when it does not answer`() {}

    @Disabled("pending") @Test fun `fails with RejectedRequest, carrying the status, when it answers with an error status`() {}

    @Disabled("pending") @Test fun `fails with UnreadableReply when the answer is not a list of versions`() {}

    @Disabled("pending") @Test fun `fails with UnreadableReply when the answer is not a search reply`() {}

    @Disabled("pending") @Test fun `refuses a blank keyword and a limit below one without asking`() {}
  }
}
