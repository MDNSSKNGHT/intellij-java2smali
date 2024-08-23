package com.mdnssknght.java2smali

import org.jf.baksmali.Baksmali
import org.jf.baksmali.BaksmaliOptions
import org.jf.dexlib2.DexFileFactory
import org.jf.dexlib2.Opcodes
import java.io.File
import java.io.IOException
import kotlin.jvm.Throws

object Dex2SmaliHelper {

    /**
     * Uses baksmali, a disassembler for Android's dex format.
     * Source code and more information: https://github.com/JesusFreke/smali
     *
     * @param dexFilePath
     * @param outputDir
     * @throws IOException
     */
    @Throws(IOException::class)
    fun disassembleDexFile(dexFilePath: String, outputDir: String) {
        val opCodes = Opcodes.forApi(28)
        val dexBackendDexFile = DexFileFactory.loadDexFile(dexFilePath, opCodes)

        val options = BaksmaliOptions()
        options.apiLevel = opCodes.api
        Baksmali.disassembleDexFile(dexBackendDexFile, File(outputDir), 6, options)
    }
}