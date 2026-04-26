package com.oftalmocenter.tv.player

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import java.util.concurrent.TimeUnit

/**
 * Implementação do `Downloader` do NewPipe baseada em OkHttp. O NewPipe
 * Extractor não traz um downloader pronto — é responsabilidade do app
 * consumidor fornecer um. É só um adapter HTTP, sem mágica.
 *
 * Singleton: o NewPipe.init(...) só pode ser chamado uma vez por processo,
 * então faz sentido ter uma única instância de downloader.
 */
object NewPipeDownloader : Downloader() {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun execute(request: Request): Response {
        val builder = okhttp3.Request.Builder().url(request.url())

        request.headers().forEach { (key, values) ->
            // Headers vêm como Map<String, List<String>>; cada valor vira uma
            // entrada distinta para preservar headers que aparecem repetidos.
            values.forEach { value -> builder.addHeader(key, value) }
        }

        when (val method = request.httpMethod().uppercase()) {
            "GET" -> builder.get()
            "HEAD" -> builder.head()
            "POST" -> {
                val data = request.dataToSend() ?: ByteArray(0)
                builder.post(data.toRequestBody())
            }
            "PUT" -> {
                val data = request.dataToSend() ?: ByteArray(0)
                builder.put(data.toRequestBody())
            }
            "DELETE" -> builder.delete()
            else -> error("HTTP method não suportado: $method")
        }

        client.newCall(builder.build()).execute().use { response ->
            return Response(
                response.code,
                response.message,
                response.headers.toMultimap(),
                response.body?.string(),
                response.request.url.toString()
            )
        }
    }
}
