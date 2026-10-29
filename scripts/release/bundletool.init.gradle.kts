// Prints the jars bundletool runs from, one per line: bundletool and everything it depends on, at the versions
// AGP resolves, each pinned by sha256 in gradle/verification-metadata.xml. kit.sh copies them into a signing kit
// (D140, D141). An init script rather than a task, so the build that is reproduced does not carry it.
rootProject {
    val classpath = buildscript.configurations.getByName("classpath")
    tasks.register("printBundletoolJars") {
        doLast {
            val seen = mutableSetOf<ResolvedDependency>()
            val bundletool = mutableListOf<ResolvedDependency>()
            fun walk(dependency: ResolvedDependency) {
                if (!seen.add(dependency)) return
                if (dependency.moduleGroup == "com.android.tools.build" && dependency.moduleName == "bundletool") {
                    bundletool += dependency
                }
                dependency.children.forEach(::walk)
            }
            classpath.resolvedConfiguration.firstLevelModuleDependencies.forEach(::walk)
            bundletool.flatMap { it.allModuleArtifacts }.map { it.file }.distinct().forEach { println(it) }
        }
    }
}
