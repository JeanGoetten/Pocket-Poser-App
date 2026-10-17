package com.posepocket.nvidia

import io.grpc.Status
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

enum class TransportIssue { DNS, TLS, CONNECTION, TIMEOUT, SERVICE_OR_CONNECTION }

/** Only stable classifications escape the transport; server descriptions/cause
 * strings can contain credentials and must never reach UI, files or logs. */
class NvidiaFailure(val code:Status.Code,val issue:TransportIssue):IllegalStateException(
    if(code==Status.Code.UNAVAILABLE)when(issue) {
        TransportIssue.DNS -> "Não foi possível localizar o servidor NVIDIA (DNS). Tente reenviar em instantes ou por outra rede."
        TransportIssue.TLS -> "Não foi possível estabelecer uma conexão segura com a NVIDIA (TLS). Verifique data/hora do aparelho ou tente outra rede."
        TransportIssue.CONNECTION -> "A conexão com o servidor NVIDIA foi recusada ou interrompida. Tente reenviar em instantes."
        TransportIssue.TIMEOUT -> "A conexão com o servidor NVIDIA excedeu o tempo de espera. Tente reenviar em instantes."
        TransportIssue.SERVICE_OR_CONNECTION -> "O serviço NVIDIA ficou indisponível ou interrompeu a conexão (UNAVAILABLE). Isso pode ocorrer mesmo com a internet funcionando. Tente reenviar em instantes."
    } else DirectNvidiaClient.statusMessage(code)
) {
    companion object {
        fun from(error:Throwable):NvidiaFailure {
            val causes=generateSequence(error){it.cause}.take(12).toList()
            val issue=when {
                causes.any{it is UnknownHostException}->TransportIssue.DNS
                causes.any{it is SSLException}->TransportIssue.TLS
                causes.any{it is SocketTimeoutException}->TransportIssue.TIMEOUT
                causes.any{it is ConnectException}->TransportIssue.CONNECTION
                else->TransportIssue.SERVICE_OR_CONNECTION
            }
            return NvidiaFailure(Status.fromThrowable(error).code,issue)
        }
    }
}
