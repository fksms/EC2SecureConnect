package com.example.ec2secureconnect

import android.content.Context
import android.os.Build
import java.io.File

object GoBinaryManager {

    private const val binaryName = "lib_ssm_client_exec.so"
    private val supportedAbis = setOf("arm64-v8a")

    fun prepareExecutable(context: Context): File {
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it in supportedAbis }
            ?: error("No bundled ssm-client binary for device ABI: ${Build.SUPPORTED_ABIS.joinToString()}")
        val nativeLibraryDir = context.applicationInfo.nativeLibraryDir
        val outputFile = File(nativeLibraryDir, binaryName)
        check(outputFile.exists()) {
            "Bundled ssm-client binary was not found for ABI $abi in $nativeLibraryDir"
        }
        return outputFile
    }
}
