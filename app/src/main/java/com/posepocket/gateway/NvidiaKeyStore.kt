package com.posepocket.gateway

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class NvidiaKeyStore(directory:File) {
    private val file=AtomicFile(File(directory,"nvidia-key.enc"))
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias="pose-pocket-nvidia-v1"
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read():String?=try {
        val bytes=file.readFully()
        require(bytes.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val json=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        json.getString("key").also { require(json.optBoolean("terms_approved",false));validate(it) }
    }catch(_:Exception){null}

    fun write(value:String) {
        validate(value)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val json=JSONObject().put("key",value).put("terms_approved",true)
        val bytes=cipher.iv+cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream=file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch(error:Exception){file.failWrite(stream);throw error}
        check(read()==value)
    }
    fun clear() { file.delete(); check(!file.baseFile.exists()) }
    companion object {
        const val MODEL="NVIDIA Body Pose (Nova-77)"
        const val ENDPOINT="grpc.nvcf.nvidia.com:443"
        fun validate(value:String) {
            require(value.length in 32..4096 && value.all { it.code in 33..126 }) { "Informe uma chave válida, sem espaços ou quebras de linha." }
        }
    }
}
