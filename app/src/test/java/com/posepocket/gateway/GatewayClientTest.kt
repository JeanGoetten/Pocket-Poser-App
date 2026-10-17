package com.posepocket.gateway

import com.posepocket.privacy.*
import com.posepocket.media.PreparedVideo
import com.posepocket.pose.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.Dispatcher
import okio.Buffer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class GatewayClientTest {
    @get:Rule val temp=TemporaryFolder()
    private val policy=Policy("test-v1","video-v1","Política técnica de teste")
    private fun gate():ConsentGate {
        val store=object:ConsentStore {
            var receipt:ConsentReceipt?=null
            override fun read()=receipt
            override fun write(receipt:ConsentReceipt){this.receipt=receipt}
            override fun clear(){receipt=null}
        }
        return ConsentGate(policy,store).apply { accept(true,true) }
    }

    @Test fun rejectsUnsafeTargetsAndEmbeddedCredentials() {
        listOf("http://example.com","https://user:password@example.com","https://example.com?token=x","ftp://example.com").forEach {
            assertThrows(IllegalArgumentException::class.java){GatewaySettings(it,"a".repeat(40),true).validate()}
        }
        GatewaySettings("https://example.com","a".repeat(40),true).validate()
    }

    @Test fun completeHttpTransportVerifiesPolicyResultHashAndProvenance()= runBlocking {
        val fixture=syntheticDemo(temp.newFile("fixture.jsonl"))
        // A protocol fixture without a synthetic-provider flag, never evidence of real inference.
        fixture.raw.writeText(fixture.raw.useLines { lines->lines.map { line->JSONObject(line).apply { remove("provenance") }.toString() }.joinToString("\n",postfix="\n") })
        val raw=fixture.raw.readBytes();val rawHash=fileHash(fixture.raw)
        val source=temp.newFile("video.mp4").apply { writeBytes(byteArrayOf(1,2,3)) }
        val requests=mutableListOf<String>()
        val server=MockWebServer()
        server.dispatcher=object:Dispatcher() {
            override fun dispatch(request:RecordedRequest):MockResponse {
                assertEquals("Bearer "+"a".repeat(40),request.getHeader("Authorization"))
                requests.add(request.method+" "+request.path)
                val response=MockResponse()
                val body=when(request.path) {
                    "/v1/policy"->JSONObject().put("policy_version",policy.version).put("document_hash",policy.hash).put("scope",policy.scope).toString().toByteArray()
                    "/v1/health"->"{\"nvidia_configured\":true}".toByteArray()
                    "/v1/consent"->byteArrayOf()
                    "/v1/jobs/prepare"->{
                        val upload=request.body.readUtf8()
                        assertTrue(upload.contains(fileHash(source)))
                        "{\"job_id\":\"00000000-0000-0000-0000-000000000001\"}".toByteArray()
                    }
                    "/v1/jobs/00000000-0000-0000-0000-000000000001"->JSONObject().put("status","completed").put("media",JSONObject()
                        .put("fps_numerator",30).put("fps_denominator",1).put("frame_count",120).put("sha256","final-normalized-hash")).toString().toByteArray()
                    else-> {response.setHeader("X-Content-SHA256",rawHash);raw}
                }
                return if(body.isEmpty())response.setResponseCode(204) else response.setResponseCode(200).setBody(Buffer().write(body))
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"),0)
        try {
            val client=GatewayClient(gate(),temp.newFolder("results"))
            val config=GatewaySettings("http://127.0.0.1:${server.port}","a".repeat(40),true)
            val clip=client.infer(config,PreparedVideo(source,fileHash(source),"attachment"),UUID.randomUUID().toString()){}
            assertEquals(rawHash,fileHash(clip.raw))
            assertEquals(120,clip.frameCount)
            assertFalse(clip.demo) // This test server is a transport fixture, not NVIDIA evidence.
            assertEquals("attachment",clip.inputSource)
            assertTrue(requests.contains("POST /v1/consent"))
        }finally{server.shutdown()}
    }
}
