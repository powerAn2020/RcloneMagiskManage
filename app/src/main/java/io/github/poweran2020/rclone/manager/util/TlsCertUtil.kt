package io.github.poweran2020.rclone.manager.util

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object TlsCertUtil {
    data class CertKeyPair(val certPem: String, val keyPem: String)

    fun generateSelfSignedCert(cn: String = "Rclone Gateway"): CertKeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val kp = kpg.generateKeyPair()

        val certDer = createSelfSignedCertDer(kp, cn)

        val certPem = "-----BEGIN CERTIFICATE-----\n" +
                Base64.encodeToString(certDer, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"

        val keyPem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP).chunked(64).joinToString("\n") +
                "\n-----END PRIVATE KEY-----\n"

        return CertKeyPair(certPem, keyPem)
    }

    private fun createSelfSignedCertDer(kp: KeyPair, cn: String): ByteArray {
        val tbs = encodeTbs(kp, cn)
        val sig = Signature.getInstance("SHA256withRSA").apply {
            initSign(kp.private)
            update(tbs)
        }.sign()

        val sha256RsaOid = byteArrayOf(
            0x06, 0x09,
            0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D.toByte(), 0x01, 0x01, 0x0B,
            0x05, 0x00
        )
        val sigAlg = seq(sha256RsaOid)
        val sigBitString = bitString(sig)

        val certOut = ByteArrayOutputStream()
        certOut.write(tbs)
        certOut.write(sigAlg)
        certOut.write(sigBitString)
        return seq(certOut.toByteArray())
    }

    private fun encodeTbs(kp: KeyPair, cn: String): ByteArray {
        val out = ByteArrayOutputStream()

        // Version 3: [0] EXPLICIT INTEGER 2
        out.write(byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02))

        // Serial Number: INTEGER (random positive long)
        val serial = kotlin.random.Random.nextLong(1, Long.MAX_VALUE)
        out.write(integer(serial))

        // Signature Algorithm: SHA256withRSA
        val sha256RsaOid = byteArrayOf(
            0x06, 0x09,
            0x2A.toByte(), 0x86.toByte(), 0x48.toByte(), 0x86.toByte(), 0xF7.toByte(), 0x0D.toByte(), 0x01, 0x01, 0x0B,
            0x05, 0x00
        )
        out.write(seq(sha256RsaOid))

        // Issuer & Subject: RDN SEQUENCE with CN
        val nameSeq = rdnSequence(cn)
        out.write(nameSeq) // Issuer

        // Validity: SEQUENCE of UTCTime
        val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val notBefore = utcTime(sdf.format(Date(System.currentTimeMillis() - 60_000L)))
        val notAfter = utcTime(sdf.format(Date(System.currentTimeMillis() + 10L * 365 * 24 * 3600 * 1000L)))
        out.write(seq(notBefore + notAfter)) // Validity

        out.write(nameSeq) // Subject (self-signed, identical to Issuer)

        // SubjectPublicKeyInfo (kp.public.encoded is already DER encoded SubjectPublicKeyInfo)
        out.write(kp.public.encoded)

        return seq(out.toByteArray())
    }

    private fun seq(content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x30)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun integer(v: Long): ByteArray {
        val bytes = ByteArrayOutputStream()
        var cur = v
        val list = mutableListOf<Byte>()
        while (cur > 0) {
            list.add((cur and 0xFF).toByte())
            cur = cur ushr 8
        }
        list.reverse()
        if (list.isEmpty() || (list[0].toInt() and 0x80) != 0) {
            list.add(0, 0)
        }
        bytes.write(0x02)
        writeLength(bytes, list.size)
        bytes.write(list.toByteArray())
        return bytes.toByteArray()
    }

    private fun rdnSequence(cn: String): ByteArray {
        // OID: 2.5.4.3 (commonName)
        val cnOid = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
        val cnVal = utf8String(cn)
        val atv = seq(cnOid + cnVal)
        val rdn = set(atv)
        return seq(rdn)
    }

    private fun utf8String(str: String): ByteArray {
        val b = str.toByteArray(Charsets.UTF_8)
        val out = ByteArrayOutputStream()
        out.write(0x0C)
        writeLength(out, b.size)
        out.write(b)
        return out.toByteArray()
    }

    private fun set(content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x31)
        writeLength(out, content.size)
        out.write(content)
        return out.toByteArray()
    }

    private fun utcTime(timeStr: String): ByteArray {
        val b = timeStr.toByteArray(Charsets.US_ASCII)
        val out = ByteArrayOutputStream()
        out.write(0x17)
        writeLength(out, b.size)
        out.write(b)
        return out.toByteArray()
    }

    private fun bitString(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x03)
        writeLength(out, bytes.size + 1)
        out.write(0x00)
        out.write(bytes)
        return out.toByteArray()
    }

    private fun writeLength(out: ByteArrayOutputStream, len: Int) {
        if (len < 128) {
            out.write(len)
        } else if (len < 256) {
            out.write(0x81)
            out.write(len)
        } else if (len < 65536) {
            out.write(0x82)
            out.write(len ushr 8)
            out.write(len and 0xFF)
        } else {
            out.write(0x83)
            out.write(len ushr 16)
            out.write((len ushr 8) and 0xFF)
            out.write(len and 0xFF)
        }
    }
}
