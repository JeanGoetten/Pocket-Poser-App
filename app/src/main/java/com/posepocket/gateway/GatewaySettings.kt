package com.posepocket.gateway

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.posepocket.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class GatewaySettings(val url:String,val token:String,val enabled:Boolean) {
    fun validate() {
        val endpoint=URL(url)
        require(endpoint.userInfo==null&&endpoint.query==null&&endpoint.ref==null&&endpoint.path in listOf("","/"))
        require(endpoint.protocol=="https" || BuildConfig.DEBUG&&endpoint.protocol=="http"&&endpoint.host in listOf("127.0.0.1","localhost","10.0.2.2")) {
            "Use HTTPS. HTTP só é permitido no debug para localhost/emulador."
        }
        require(token.length in 32..1024 && token.none { it.isWhitespace() }) { "Token individual do gateway inválido." }
    }
}

class GatewaySettingsStore(directory:File) {
    private val file=AtomicFile(File(directory,"gateway.enc"))
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias="pose-pocket-gateway-v1"
        (store.getKey(alias,null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read():GatewaySettings?=try {
        val bytes=file.readFully()
        require(bytes.size>12)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,bytes.copyOfRange(0,12)))
        val json=JSONObject(String(cipher.doFinal(bytes.copyOfRange(12,bytes.size)),Charsets.UTF_8))
        GatewaySettings(json.getString("url"),json.getString("token"),json.getBoolean("enabled")).also { it.validate() }
    }catch(_:Exception){null}

    fun write(settings:GatewaySettings) {
        settings.validate()
        val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE,key())
        val json=JSONObject().put("url",settings.url.trimEnd('/')).put("token",settings.token).put("enabled",settings.enabled)
        val bytes=cipher.iv+cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        val stream=file.startWrite()
        try { stream.write(bytes); stream.fd.sync(); file.finishWrite(stream) }
        catch(error:Exception){file.failWrite(stream);throw error}
        check(read()==settings.copy(url=settings.url.trimEnd('/')))
    }
    fun clear()=file.delete()
}
