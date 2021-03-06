package com.ank.drift.client.integration.http

import com.ank.drift.client.config.ClientConfig
import com.ank.drift.client.exception.BadServerRequestException
import com.ank.drift.common.contants.TEN_SECONDS
import com.ank.drift.common.model.Gossip
import com.ank.drift.common.model.Payload
import com.ank.drift.common.util.toFlux
import com.ank.drift.common.util.toMultiValueMap
import io.netty.channel.ChannelOption
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.timeout.ReadTimeoutHandler
import io.netty.handler.timeout.WriteTimeoutHandler
import org.slf4j.LoggerFactory
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.toFlux
import reactor.core.publisher.toMono
import reactor.core.scheduler.Scheduler
import reactor.netty.http.client.HttpClient
import reactor.netty.tcp.TcpClient
import java.net.URLDecoder
import java.time.Duration
import java.util.Date
import javax.inject.Inject

@Service
class ClientWebHttpService @Inject constructor(
    val clientConfig: ClientConfig,
    val clientRequestElasticScheduler: Scheduler
) {
    companion object {
        val log = LoggerFactory.getLogger(ClientWebHttpService::class.java)
    }

    val webHttpClient: WebClient by lazy {
        val tcpClient = TcpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Duration.ofSeconds(clientConfig.localServer?.connectTimeoutInSec ?: TEN_SECONDS).toMillis().toInt())
                .doOnConnected { connection ->
                    connection.addHandlerLast(ReadTimeoutHandler((clientConfig.localServer?.readTimeoutInSec ?: TEN_SECONDS).toInt()))
                            .addHandlerLast(WriteTimeoutHandler((clientConfig.localServer?.readTimeoutInSec ?: TEN_SECONDS).toInt()))
                }
        WebClient.builder()
                .baseUrl(clientConfig.localServer?.url ?: "localhost")
                .clientConnector(ReactorClientHttpConnector(HttpClient.from(tcpClient)))
                .build()
    }

    fun getResponseFromLocalServer(payload: Payload?): Flux<Payload> {
        val startedTime = Date()
        return payload?.method?.let {
            webHttpClient.method(payload.method).uri {
                it.path(URLDecoder.decode(payload.url.orEmpty(), "UTF-8"))
                        .queryParams(payload.queryParams.toMultiValueMap())
                        .build()
            }.headers { headers ->
                payload.headers?.forEach { header ->
                    if (header.key.equals("host", true).not()) {
                        headers.set(header.key, header.value)
                    }
                }
            }.body(BodyInserters.fromObject(payload.body))
                    .exchange()
                    .toFlux()
                    .publishOn(clientRequestElasticScheduler)
                    .subscribeOn(clientRequestElasticScheduler)
                    .flatMap { response ->
                        response.body { inputMessage, _ ->
                            inputMessage.body
                                    .map {
                                        val byteBuffer = it.asByteBuffer()
                                        val bytes = ByteArray(byteBuffer.capacity())
                                        byteBuffer.get(bytes, 0, bytes.size)
                                        byteBuffer.clear()
                                        bytes
                            }.flatMap {
                                payload.copy(headers = response.headers().asHttpHeaders().toMultiValueMap(), body = it, status = response.rawStatusCode()).toFlux()
                            }
                        }
                    }.doOnError {
                        log.error("${ClientWebHttpService::getResponseFromLocalServer.name}, Error=${it.message}", it)
                    }.doOnNext {
//                        log.info("${ClientWebHttpService::getResponseFromLocalServer.name}, Response=$it")
                    }.onErrorResume {
                        Payload(status = HttpResponseStatus.INTERNAL_SERVER_ERROR.code(), body = "INTERNAL_SERVER_ERROR".toByteArray()).toMono()
                    }.doFinally {
                        log.info("${::getResponseFromLocalServer.name} url=${payload.url} is ${it.name} with ${Date().toInstant().minusMillis(startedTime.toInstant().toEpochMilli()).toEpochMilli()} milli seconds")
                    }
        } ?: Flux.error(BadServerRequestException(Gossip(message = "Invalid HTTP Method")))
    }
}