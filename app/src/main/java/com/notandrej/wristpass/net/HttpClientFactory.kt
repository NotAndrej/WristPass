package com.notandrej.wristpass.net

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClientFactory {

    /**
     * Builds the OkHttp client used for one configured server.
     *
     * With [allowInvalidTls] off (the default) this is a completely ordinary
     * HTTPS client. When the user explicitly turns the setting on — for a
     * self-hosted instance behind a self-signed certificate — the client gets
     * an all-trusting [X509TrustManager] and a permissive hostname verifier.
     * That deliberately disables certificate validation, so it must stay opt-in.
     */
    fun create(allowInvalidTls: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)

        if (allowInvalidTls) {
            val trustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            val context = SSLContext.getInstance("TLS")
            context.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
            builder.sslSocketFactory(context.socketFactory, trustManager)
            builder.hostnameVerifier { _, _ -> true }
        }

        return builder.build()
    }
}
