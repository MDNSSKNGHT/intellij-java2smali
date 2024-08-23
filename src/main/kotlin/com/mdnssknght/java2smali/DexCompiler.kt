package com.mdnssknght.java2smali

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.*
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiClassOwner
import com.intellij.psi.PsiManager
import com.intellij.task.ProjectTaskManager
import com.intellij.util.SmartList
import com.intellij.util.containers.OrderedSet
import com.intellij.util.io.URLUtil
import java.io.File
import java.nio.file.Paths

class DexCompiler(private val vFile: VirtualFile, private val project: Project, private val module: Module) {

    fun run() {
        buildModule {
            onProjectBuildComplete()
        }
    }

    /**
     * To create a dex or smali file from the virtual file we need a compiled .class file of the given
     * virtual file to be present.
     *
     * Project structures and builds vary a lot. (Android, Java, Kotlin, directory structure) Instead of using
     * the CompileManager, we trigger a general build task.
     */
    private fun buildModule(callback: () -> Unit) {
        val projectTaskManager = ProjectTaskManager.getInstance(project)
        val buildTask = projectTaskManager.createModulesBuildTask(module, true, true, true)
        projectTaskManager.run(buildTask).onSuccess {
            if (it.hasErrors()) {
                LOG.warn("Module build failed, aborting dex/smali build.")
            } else {
                callback()
            }
        }
    }

    private fun onProjectBuildComplete() {
        val file = PsiManager.getInstance(project).findFile(vFile) as PsiClassOwner

        val fileOutputDirectory = getFileOutputDirectory(file)
        fileOutputDirectory.refresh(false, false)

        val fileName = vFile.nameWithoutExtension
        val dexFilePath = Paths.get(fileOutputDirectory.path)

        // class to dex
        val targetFiles = getClassFiles(fileOutputDirectory, fileName)
        val successfulDex = D8Class2DexHelper.dexClassFile(targetFiles, dexFilePath)
        if (!successfulDex) {
            return
        }

        val dexFile = dexFilePath.resolve("classes$DEX_EXTENSION").toString()

        // dex to smali
        val outputDir = getSourceRootFile().path
        WriteCommandAction.runWriteCommandAction(project) {
            Dex2SmaliHelper.disassembleDexFile(dexFile, outputDir)

            // We've created the smali file(s) in our source file's directory.
            // Refresh directory synchronously and access children to let IDEA detect the file(s).
            val parent = vFile.parent
            parent.refresh(false, false)
            parent.children
        }

        // Get a VirtualFile by the IO path.
        val smaliPath = vFile.path.substringBeforeLast('.') + SMALI_EXTENSION
        val virtualDexFile = LocalFileSystem.getInstance().findFileByIoFile(File(smaliPath)) ?: return

        // Use the VirtualFile to show the smali in IDEA editor.
        val openFileDescriptor = OpenFileDescriptor(project, virtualDexFile)
        openFileDescriptor.navigate(true)
    }

    private fun getClassFiles(fileOutputDirectory: VirtualFile, fileName: String): Array<String> {
        val children = fileOutputDirectory.children ?: arrayOf()
        return children.filter {
            val baseName = it.nameWithoutExtension
            (baseName == fileName || baseName.startsWith("$fileName$")) && it.extension == CLASS
        }.map {
            it.path
        }.toTypedArray()
    }

    private fun getFileOutputDirectory(file: PsiClassOwner): VirtualFile {
        // Determine whether this is a production file or test file.
        val isProduction = module.getModuleScope(false).contains(vFile)

        val pkg = file.packageName.replace('.', File.separatorChar)

        // Find the general output directory of the file's module (target, app/build/intermediates/javac/$variant/classes, ...)
        val possibleOutputDirectories = findModuleOutputDirectories(isProduction)
        LOG.debug("Possible output directories: ", possibleOutputDirectories.joinToString(","))

        val virtualFileManager = VirtualFileManager.getInstance().getFileSystem(URLUtil.FILE_PROTOCOL)

        val fileOutputDirectory = possibleOutputDirectories
            .asSequence()
            .map {
                val classFile = vFile.nameWithoutExtension + CLASS_EXTENSION
                val path = Paths.get(it, pkg, classFile).toString()
                virtualFileManager.refreshAndFindFileByPath(path)?.parent
            }
            .firstOrNull { it != null }

        LOG.debug("Found output directory: $fileOutputDirectory")
        return fileOutputDirectory ?: throw IllegalStateException("Output directory not found")
    }

    private fun findModuleOutputDirectories(production: Boolean): OrderedSet<String> {
        val outputPaths: MutableList<String> = mutableListOf()

        val compilerExtension = CompilerModuleExtension.getInstance(module)
        if (production) {
            compilerExtension?.compilerOutputPath?.path?.let { outputPaths.add(it) }
        } else {
            compilerExtension?.compilerOutputPathForTests?.path?.let { outputPaths.add(it) }
        }

        val moduleRootManager = ModuleRootManager.getInstance(module)
        for (handlerFactory in OrderEnumerationHandler.EP_NAME.extensions) {
            if (handlerFactory.isApplicable(module)) {
                val handler = handlerFactory.createHandler(module)
                val outputUrls: List<String> = SmartList()
                handler.addCustomModuleRoots(OrderRootType.CLASSES, moduleRootManager, outputUrls, production, !production)
                for (outputUrl in outputUrls) {
                    outputPaths.add(VirtualFileManager.extractPath(outputUrl).replace('/', File.separatorChar))
                }
            }
        }
        return OrderedSet(outputPaths)
    }

    private fun getSourceRootFile(): VirtualFile {
        return ProjectRootManager.getInstance(project).fileIndex.getSourceRootForFile(vFile) as VirtualFile
    }

    companion object {
        private val LOG = Logger.getInstance(DexCompiler::class.java)

        private const val CLASS_EXTENSION = ".class"
        private const val DEX_EXTENSION = ".dex"
        private const val SMALI_EXTENSION = ".smali"
        private const val CLASS = "class"
    }
}