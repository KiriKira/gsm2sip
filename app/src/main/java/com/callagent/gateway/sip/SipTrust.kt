package com.callagent.gateway.sip

import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

object SipTrust {
    /** An authenticated HTTPS provisioning response may supply a private CA.
     * Chain and hostname validation still happen in TlsSipTransport. */
    fun socketFactory(caPem: String?): SSLSocketFactory {
        if (caPem.isNullOrBlank()) return SSLSocketFactory.getDefault() as SSLSocketFactory
        val certificates=CertificateFactory.getInstance("X.509").generateCertificates(caPem.byteInputStream(Charsets.US_ASCII))
        require(certificates.isNotEmpty()) { "SIP CA contains no certificates" }
        val store=KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null,null) }
        certificates.forEachIndexed { index, certificate -> store.setCertificateEntry("sip-ca-$index",certificate) }
        val trust=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {init(store)}
        return SSLContext.getInstance("TLS").apply {init(null,trust.trustManagers,null)}.socketFactory
    }
}
