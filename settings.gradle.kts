rootProject.name = "larkbatis-maven-plugin"

// Local development: substitutes io.github.larkbatis:* dependencies with the
// core repo checked out beside this one. Conditional because CI checks out one
// repository at a time, and an includeBuild pointing at a missing directory
// fails configuration outright. `-PuseLocalCore=false` opts out explicitly, so
// a release can be built against the published artifacts without moving the
// directory away.
val coreRepo = file("../larkbatis")
if (coreRepo.isDirectory && providers.gradleProperty("useLocalCore").orNull != "false") {
    includeBuild(coreRepo)
}
