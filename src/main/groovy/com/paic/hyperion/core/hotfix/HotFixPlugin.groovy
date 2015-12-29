package com.paic.hyperion.core.hotfix;

import org.apache.commons.codec.digest.DigestUtils
import org.apache.commons.io.FileUtils
import org.gradle.api.Plugin
import org.gradle.api.Project
import com.paic.hyperion.core.hotfix.util.*;

/**
 * shamelessly stolen from nuwa
 */
class HotFixPlugin implements Plugin<Project> {
    HashSet<String> includePackage
    HashSet<String> excludeClass
    def debugOn
    def patchList = []
    def beforeDexTasks = []
    private static final String HOT_FIX_DIR = "HotFixDir"
    private static final String HOT_FIX_PATCHES = "hotfixPatches"

    private static final String MAPPING_TXT = "mapping.txt"
    private static final String HASH_TXT = "hash.txt"

    private static final String DEBUG = "debug"

    private static final String PLUGIN_NAME = "hotfix"


    @Override
    void apply(Project project) {

        project.extensions.create(PLUGIN_NAME, HotFixExtension, project)



        project.afterEvaluate {
            def extension = project.extensions.findByName(PLUGIN_NAME) as HotFixExtension
            includePackage = extension.includePackage
            excludeClass = extension.excludeClass
            debugOn = extension.debugOn

            project.android.applicationVariants.each { variant ->

                if (!variant.name.contains(DEBUG) || (variant.name.contains(DEBUG) && debugOn)) {

                    Map hashMap
                    File hotfixDir
                    File patchDir

                    def preDexTask = project.tasks.findByName("preDex${variant.name.capitalize()}")
                    def dexTask = project.tasks.findByName("dex${variant.name.capitalize()}")
                    def proguardTask = project.tasks.findByName("proguard${variant.name.capitalize()}")

                    def processManifestTask = project.tasks.findByName("process${variant.name.capitalize()}Manifest")
                    def manifestFile = processManifestTask.outputs.files.files[0]

                    def oldHotfixDir = FilePropUtils.getFileFromProperty(project, HOT_FIX_DIR)
                    if (oldHotfixDir) {
                        def mappingFile = FilePropUtils.getVariantFile(oldHotfixDir, variant, MAPPING_TXT)
                        AndroidUtils.applymapping(proguardTask, mappingFile)
                    }
                    if (oldHotfixDir) {
                        def hashFile = FilePropUtils.getVariantFile(oldHotfixDir, variant, HASH_TXT)
                        hashMap = MapUtils.parseMap(hashFile)
                    }

                    def dirName = variant.dirName
                     hotfixDir = new File("${project.buildDir}/outputs/"+PLUGIN_NAME)
                    def outputDir = new File("${ hotfixDir}/${dirName}")
                    def hashFile = new File(outputDir, "hash.txt")

                    Closure nuwaPrepareClosure = {
                        def applicationName = AndroidUtils.getApplication(manifestFile)
                        if (applicationName != null) {
                            excludeClass.add(applicationName)
                        }

                        outputDir.mkdirs()
                        if (!hashFile.exists()) {
                            hashFile.createNewFile()
                        }

                        if (oldHotfixDir) {
                            patchDir = new File("${ hotfixDir}/${dirName}/patch")
                            patchDir.mkdirs()
                            patchList.add(patchDir)
                        }
                    }

                    def hotfixPatch = "hotfix${variant.name.capitalize()}Patch"
                    project.task(hotfixPatch) << {
                        if (patchDir) {
                            AndroidUtils.dex(project, patchDir)
                        }
                    }
                    def hotfixPatchTask = project.tasks[hotfixPatch]

                    Closure copyMappingClosure = {
                        if (proguardTask) {
                            def mapFile = new File("${project.buildDir}/outputs/mapping/${variant.dirName}/mapping.txt")
                            def newMapFile = new File("${ hotfixDir}/${variant.dirName}/mapping.txt");
                            FileUtils.copyFile(mapFile, newMapFile)
                        }
                    }

                    if (preDexTask) {
                        def hotfixJarBeforePreDex = "hotfixJarBeforePreDex${variant.name.capitalize()}"
                        project.task( hotfixJarBeforePreDex) << {
                            Set<File> inputFiles = preDexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (Processor.shouldProcessPreDexJar(path)) {
                                    Processor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def hotfixJarBeforePreDexTask = project.tasks[ hotfixJarBeforePreDex]
                        hotfixJarBeforePreDexTask.dependsOn preDexTask.taskDependencies.getDependencies(preDexTask)
                        preDexTask.dependsOn hotfixJarBeforePreDexTask

                        hotfixJarBeforePreDexTask.doFirst(nuwaPrepareClosure)

                        def hotfixClassBeforeDex = "hotfixClassBeforeDex${variant.name.capitalize()}"
                        project.task(hotfixClassBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".class") && !path.contains("/R\$") && !path.endsWith("/R.class") && !path.endsWith("/BuildConfig.class")) {
                                    if (SetUtils.isIncluded(path, includePackage)) {
                                        if (!SetUtils.isExcluded(path, excludeClass)) {
                                            def bytes = Processor.processClass(inputFile)
                                            path = path.split("${dirName}/")[1]
                                            def hash = DigestUtils.shaHex(bytes)
                                            hashFile.append(MapUtils.format(path, hash))

                                            if (MapUtils.notSame(hashMap, path, hash)) {
                                                FilePropUtils.copyBytesToFile(inputFile.bytes, FilePropUtils.touchFile(patchDir, path))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        def hotfixClassBeforeDexTask = project.tasks[hotfixClassBeforeDex]
                        hotfixClassBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn hotfixClassBeforeDexTask

                        hotfixClassBeforeDexTask.doLast(copyMappingClosure)

                        hotfixPatchTask.dependsOn hotfixClassBeforeDexTask
                        beforeDexTasks.add(hotfixClassBeforeDexTask)
                    } else {
                        def hotfixJarBeforeDex = "hotfixJarBeforeDex${variant.name.capitalize()}"
                        project.task(hotfixJarBeforeDex) << {
                            Set<File> inputFiles = dexTask.inputs.files.files
                            inputFiles.each { inputFile ->
                                def path = inputFile.absolutePath
                                if (path.endsWith(".jar")) {
                                    Processor.processJar(hashFile, inputFile, patchDir, hashMap, includePackage, excludeClass)
                                }
                            }
                        }
                        def hotfixJarBeforeDexTask = project.tasks[hotfixJarBeforeDex]
                        hotfixJarBeforeDexTask.dependsOn dexTask.taskDependencies.getDependencies(dexTask)
                        dexTask.dependsOn hotfixJarBeforeDexTask

                        hotfixJarBeforeDexTask.doFirst(nuwaPrepareClosure)
                        hotfixJarBeforeDexTask.doLast(copyMappingClosure)

                        hotfixPatchTask.dependsOn hotfixJarBeforeDexTask
                        beforeDexTasks.add(hotfixJarBeforeDexTask)
                    }

                }
            }

            project.task(HOT_FIX_PATCHES) << {
                patchList.each { patchDir ->
                    AndroidUtils.dex(project, patchDir)
                }
            }
            beforeDexTasks.each {
                project.tasks[HOT_FIX_PATCHES].dependsOn it
            }
        }
    }
}