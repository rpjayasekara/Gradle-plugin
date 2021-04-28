package org.ballerina.plugin

import org.apache.tools.ant.taskdefs.condition.Os
import org.gradle.api.InvalidUserDataException
import org.gradle.api.Plugin
import org.gradle.api.Project

class StdLibBalPlugin implements Plugin<Project>{

    @Override
    void apply(Project project) {

        def packageName = project.rootProject.name
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
        def distributionBinPath = project.projectDir.absolutePath + "/build/target/extracted-distributions/jballerina-tools-zip/jballerina-tools-${project.ballerinaLangVersion}/bin"
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

        project.configurations{
            jbalTools
        }

        project.dependencies {
            jbalTools ("org.ballerinalang:jballerina-tools:${project.ballerinaLangVersion}") {
                transitive = false
            }
        }

        project.tasks.create("unpackJballerinaTools", Copy.class){
            project.configurations.jbalTools.resolvedConfiguration.resolvedArtifacts.each { artifact ->
                from project.zipTree(artifact.getFile())
                into new File("${project.buildDir}/target/extracted-distributions", "jballerina-tools-zip")
            }
        }

        project.task("updateTomlVersions"){
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

        project.tasks.create("test1", Copy.class){
            println project.rootProject.name
        }

        project.tasks.create("ballerinaBuild"){
            finalizedBy(project.revertTomlFile)
            dependsOn(project.initializeVariables)
            dependsOn(project.updateTomlVersions)
            dependsOn(":${packageName}-native:build")

            inputs.dir projectDirectory
            doLast {
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

        project.tasks.create("createArtifactZip", Zip.class){
            destinationDirectory = new File("$project.buildDir/distributions")
            from project.ballerinaBuild
        }

    }
}