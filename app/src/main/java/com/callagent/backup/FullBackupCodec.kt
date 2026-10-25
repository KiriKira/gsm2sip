package com.callagent.backup

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A full backup that is not ours, was damaged, or was sealed with another password. */
class FullBackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * The password seal of a full backup: a header, then the plaintext (a ZIP
 * stream) in AES-256-GCM chunks. Each chunk authenticates the header, its
 * index and whether it is the last one, so chunks cannot be dropped,
 * reordered or cut off. Chunking keeps memory flat for large databases.
 *
 * Header: magic, version, PBKDF2 iterations, salt, base nonce.
 * Chunk: last flag (1 byte), ciphertext length (4 bytes), ciphertext.
 */
object FullBackupCodec {
    const val MIN_PASSWORD_CHARS = 8
    private val MAGIC = "MAHOGSM-FULL".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val ITERATIONS = 310_000
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private const val CHUNK_BYTES = 64 * 1024
    private const val MAX_CIPHERTEXT = CHUNK_BYTES + TAG_BITS / 8

    /** Plaintext written to the returned stream is sealed into [output]; close it to finish. */
    fun seal(output: OutputStream, password: CharArray, iterations: Int = ITERATIONS): OutputStream {
        require(password.size >= MIN_PASSWORD_CHARS) { "password too short" }
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(iterations, salt, nonce)
        output.write(header)
        return SealedOutput(output, key(password, salt, iterations), header, nonce)
    }

    /**
     * The plaintext of a sealed backup. The first chunk is opened at once, so
     * a wrong password fails here rather than halfway through a restore.
     */
    fun open(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try { data.readFully(magic) } catch (e: EOFException) { throw FullBackupException("不是完整备份文件", e) }
        if (!magic.contentEquals(MAGIC)) throw FullBackupException("不是完整备份文件")
        val version = data.readUnsignedByte()
        if (version != VERSION) throw FullBackupException("备份文件版本不受支持")
        val iterations = data.readInt()
        if (iterations !in 100_000..10_000_000) throw FullBackupException("备份文件已损坏")
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val nonce = ByteArray(NONCE_BYTES).also(data::readFully)
        val stream = SealedInput(data, key(password, salt, iterations), header(iterations, salt, nonce), nonce)
        stream.prime()
        return stream
    }

    private fun header(iterations: Int, salt: ByteArray, nonce: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(MAGIC); write(VERSION)
            write(ByteBuffer.allocate(4).putInt(iterations).array())
            write(salt); write(nonce)
        }.toByteArray()

    private fun key(password: CharArray, salt: ByteArray, iterations: Int): SecretKey {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun chunkCipher(mode: Int, key: SecretKey, header: ByteArray, baseNonce: ByteArray, index: Long, last: Boolean): Cipher {
        val nonce = baseNonce.copyOf()
        for (i in 0 until 8) nonce[NONCE_BYTES - 1 - i] = (nonce[NONCE_BYTES - 1 - i].toInt() xor (index ushr (8 * i)).toInt()).toByte()
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(header)
            updateAAD(ByteBuffer.allocate(9).putLong(index).put(if (last) 1 else 0).array())
        }
    }

    private class SealedOutput(
        output: OutputStream,
        private val key: SecretKey,
        private val header: ByteArray,
        private val nonce: ByteArray
    ) : OutputStream() {
        private val out = DataOutputStream(output)
        private val buffer = ByteArray(CHUNK_BYTES)
        private var filled = 0
        private var index = 0L
        private var closed = false

        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            check(!closed) { "closed" }
            var offset = off
            var remaining = len
            while (remaining > 0) {
                if (filled == CHUNK_BYTES) emit(last = false)
                val n = minOf(remaining, CHUNK_BYTES - filled)
                System.arraycopy(b, offset, buffer, filled, n)
                filled += n; offset += n; remaining -= n
            }
        }

        private fun emit(last: Boolean) {
            val sealed = chunkCipher(Cipher.ENCRYPT_MODE, key, header, nonce, index++, last).doFinal(buffer, 0, filled)
            out.writeByte(if (last) 1 else 0)
            out.writeInt(sealed.size)
            out.write(sealed)
            filled = 0
        }

        override fun flush() = out.flush()

        override fun close() {
            if (closed) return
            emit(last = true)
            closed = true
            out.close()
        }
    }

    private class SealedInput(
        private val data: DataInputStream,
        private val key: SecretKey,
        private val header: ByteArray,
        private val nonce: ByteArray
    ) : InputStream() {
        private var chunk = ByteArray(0)
        private var position = 0
        private var index = 0L
        private var finished = false

        fun prime() {
            try { next() } catch (e: FullBackupException) { throw FullBackupException("密码错误，或备份文件已损坏", e) }
        }

        private fun next(): Boolean {
            if (finished) return false
            val last = try { data.readUnsignedByte() } catch (e: EOFException) { throw FullBackupException("备份文件不完整", e) }
            if (last > 1) throw FullBackupException("备份文件已损坏")
            val size = data.readInt()
            if (size < TAG_BITS / 8 || size > MAX_CIPHERTEXT) throw FullBackupException("备份文件已损坏")
            val sealed = ByteArray(size)
            try { data.readFully(sealed) } catch (e: EOFException) { throw FullBackupException("备份文件不完整", e) }
            chunk = try {
                chunkCipher(Cipher.DECRYPT_MODE, key, header, nonce, index++, last == 1).doFinal(sealed)
            } catch (e: GeneralSecurityException) {
                throw FullBackupException("备份文件已损坏", e)
            }
            position = 0
            if (last == 1) {
                finished = true
                if (data.read() != -1) throw FullBackupException("备份文件末尾有多余数据")
            }
            return true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (position == chunk.size) {
                if (!next()) return -1
            }
            val n = minOf(len, chunk.size - position)
            System.arraycopy(chunk, position, b, off, n)
            position += n
            return n
        }

        override fun close() = data.close()
    }
}
