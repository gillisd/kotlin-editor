plugins {
  id("cash.lib")
}

dependencies {
  api(project(":core"))
  api(project(":recipes:dependencies"))
  api(project(":recipes:plugins"))
  api(libs.kotlinStdLib)
}
