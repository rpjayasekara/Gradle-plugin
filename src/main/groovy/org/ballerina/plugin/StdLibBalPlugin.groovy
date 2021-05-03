package org.ballerina.plugin

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.Delete
import org.gradle.api.tasks.bundling.Zip

class BallerinaExtension {
    String module
    String langVersion
}

class StdLibBalPlugin implements Plugin<Project> {

    @Override
    void apply(Project project) {

        project.extensions.create("ballerina", BallerinaExtension)

        def packageOrg = "ballerina"
        def platform = "java11"
        def tomlVersion
        def ballerinaConfigFile = new File("$project.projectDir/Ballerina.toml")
        def artifactBallerinaDocs = new File("$project.projectDir/build/docs_parent/")
        def artifactCacheParent = new File("$project.projectDir/build/cache_parent/")
        def artifactLibParent = new File("$project.projectDir/build/lib_parent/")
        def projectDirectory = new File("$project.projectDir")
        def ballerinaCentralAccessToken = System.getenv('BALLERINA_CENTRAL_ACCESS_TOKEN')
        def originalConfig = ballerinaConfigFile.text
        def groupParams = ""
        def disableGroups = ""
        def debugParams = ""
        def balJavaDebugParam = ""
        def testParams = ""
        def needSeparateTest = false
        def needBuildWithTest = false
        def needPublishToCentral = false
        def needPublishToLocalCentral = false

        if (project.version.matches(project.ext.timestampedVersionRegex)) {
            def splitVersion = project.version.split('-');
            if (splitVersion.length > 3) {
                def strippedValues = splitVersion[0..-4]
                tomlVersion = strippedValues.join('-')
            } else {
                tomlVersion = project.version
            }
        } else {
            tomlVersion = project.version.replace("${project.ext.snapshotVersion}", "")
        }

        project.tasks.register("unpackJballerinaTools"){
            project.copy{
                project.configurations.jbalTools.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                    from project.zipTree(artifact.getFile())
                    into new File("${project.buildDir}/target/extracted-distributions", "jballerina-tools-zip")
                }
            }
        }

        project.tasks.register("updateTomlVersions"){
            dependsOn(project.unpackJballerinaTools)
            doLast {
                def newConfig = ballerinaConfigFile.text.replace("@project.version@", project.version)
                newConfig = newConfig.replace("@toml.version@", tomlVersion)
                ballerinaConfigFile.text = newConfig
            }
        }

        project.tasks.register("revertTomlFile"){
            doLast {
                ballerinaConfigFile.text = originalConfig
            }
        }

        project.tasks.register("initializeVariables"){

            String packageName = project.extensions.ballerina.module

            if (project.hasProperty("groups")) {
                groupParams = "--groups ${project.findProperty("groups")}"
            }
            if (project.hasProperty("disable")) {
                disableGroups = "--disable-groups ${project.findProperty("disable")}"
            }
            if (project.hasProperty("debug")) {
                debugParams = "--debug ${project.findProperty("debug")}"
            }
            if (project.hasProperty("balJavaDebug")) {
                balJavaDebugParam = "BAL_JAVA_DEBUG=${project.findProperty("balJavaDebug")}"
            }
            if (project.hasProperty("publishToLocalCentral") && (project.findProperty("publishToLocalCentral") == "true")) {
                needPublishToLocalCentral = true
            }
            if (project.hasProperty("publishToCentral") && (project.findProperty("publishToCentral") == "true")) {
                needPublishToCentral = true
            }

            project.gradle.taskGraph.whenReady { graph ->
                if (graph.hasTask(":${packageName}-ballerina:build") || graph.hasTask(":${packageName}-ballerina:publish") ||
                        graph.hasTask(":${packageName}-ballerina:publishToMavenLocal")) {
                    needSeparateTest = false
                    needBuildWithTest = true
                    if (graph.hasTask(":${packageName}-ballerina:publish")) {
                        needPublishToCentral = true
                    }
                } else {
                    needSeparateTest = true
                }

                if (graph.hasTask(":${packageName}-ballerina:test")) {
                    testParams = "--code-coverage --includes=*"
                } else {
                    testParams = "--skip-tests"
                }
            }
        }

        project.tasks.register("build"){

            finalizedBy(project.revertTomlFile)
            dependsOn(project.initializeVariables)
            dependsOn(project.updateTomlVersions)

            inputs.dir projectDirectory
            doLast {
                String distributionBinPath = project.projectDir.absolutePath + "/build/target/extracted-distributions/jballerina-tools-zip/jballerina-tools-${project.extensions.ballerina.langVersion}/bin"
                String packageName = project.extensions.ballerina.module
                if (needSeparateTest) {
                    project.exec {
                        workingDir project.projectDir
                        environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "${balJavaDebugParam} ${distributionBinPath}/bal.bat test --code-coverage --includes=* ${groupParams} ${disableGroups} ${debugParams} && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "${balJavaDebugParam} ${distributionBinPath}/bal test --code-coverage --includes=* ${groupParams} ${disableGroups} ${debugParams}"
                        }
                    }
                } else if (needBuildWithTest) {
                    project.exec {
                        workingDir project.projectDir
                        environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "$balJavaDebugParam $distributionBinPath/bal.bat build -c ${testParams} ${debugParams} && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "$balJavaDebugParam $distributionBinPath/bal build -c ${testParams} ${debugParams}"
                        }
                    }
                    // extract bala file to artifact cache directory
                    new File("$project.projectDir/target/bala").eachFileMatch(~/.*.bala/) { balaFile ->
                        project.copy {
                            from project.zipTree(balaFile)
                            into new File("$artifactCacheParent/bala/${packageOrg}/${packageName}/${tomlVersion}/${platform}")
                        }
                    }
                    project.copy {
                        from new File("$project.projectDir/target/cache")
                        exclude '**/*-testable.jar'
                        exclude '**/tests_cache/'
                        into new File("$artifactCacheParent/cache/")
                    }
                    // Doc creation and packing
                    project.exec {
                        workingDir project.projectDir
                        environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                        if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                            commandLine 'cmd', '/c', "$distributionBinPath/bal.bat doc && exit %%ERRORLEVEL%%"
                        } else {
                            commandLine 'sh', '-c', "$distributionBinPath/bal doc"
                        }
                    }
                    project.copy {
                        from new File("$project.projectDir/target/apidocs/${packageName}")
                        into new File("$project.projectDir/build/docs_parent/docs/${packageName}")
                    }
                    if (needPublishToCentral) {
                        if (project.version.endsWith('-SNAPSHOT') ||
                                project.version.matches(project.ext.timestampedVersionRegex)) {
                            return
                        }
                        if (ballerinaCentralAccessToken != null) {
                            println("Publishing to the ballerina central...")
                            project.exec {
                                workingDir project.projectDir
                                environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                                if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                    commandLine 'cmd', '/c', "$distributionBinPath/bal.bat push && exit %%ERRORLEVEL%%"
                                } else {
                                    commandLine 'sh', '-c', "$distributionBinPath/bal push"
                                }
                            }
                        } else {
                            throw new InvalidUserDataException("Central Access Token is not present")
                        }
                    } else if (needPublishToLocalCentral) {
                        println("Publishing to the ballerina local central repository..")
                        project.exec {
                            workingDir project.projectDir
                            environment "JAVA_OPTS", "-DBALLERINA_DEV_COMPILE_BALLERINA_ORG=true"
                            if (Os.isFamily(Os.FAMILY_WINDOWS)) {
                                commandLine 'cmd', '/c', "$distributionBinPath/bal.bat push && exit %%ERRORLEVEL%% --repository=local"
                            } else {
                                commandLine 'sh', '-c', "$distributionBinPath/bal push --repository=local"
                            }
                        }
                    }
                }
            }

            outputs.dir artifactCacheParent
            outputs.dir artifactBallerinaDocs
            outputs.dir artifactLibParent
        }

        project.tasks.register("createArtifactZip", Zip.class){
            destinationDirectory = new File("$project.buildDir/distributions")
            from project.build
        }

        project.tasks.register("test"){
            dependsOn(project.build)
        }

        project.tasks.register("clean", Delete.class){
            delete "$project.projectDir/target"
            delete "$project.projectDir/build"
        }

//        project.tasks.named("jar"){
//            manifest {
//                attributes('Implementation-Title': project.name,
//                        'Implementation-Version': project.version)
//            }
//        }

    }
}