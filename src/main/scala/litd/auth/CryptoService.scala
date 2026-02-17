package litd.auth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}

final class CryptoService(encryptionKeyBase64: String) {
  private val secureRandom = new SecureRandom()
  private val keyBytes = Base64.getDecoder.decode(encryptionKeyBase64)
  require(
    keyBytes.length == 32,
    s"auth.encryptionKeyBase64 must decode to 32 bytes (got ${keyBytes.length})"
  )
  private val keySpec = new SecretKeySpec(keyBytes, "AES")

  def encrypt(plainText: String): String = {
    val iv = new Array[Byte](12)
    secureRandom.nextBytes(iv)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.ENCRYPT_MODE, keySpec, new GCMParameterSpec(128, iv))
    val encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8))
    val combined = iv ++ encrypted
    Base64.getEncoder.encodeToString(combined)
  }

  def decrypt(cipherTextBase64: String): String = {
    val combined = Base64.getDecoder.decode(cipherTextBase64)
    require(combined.length > 12, "Ciphertext payload is invalid")
    val (iv, encrypted) = combined.splitAt(12)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, keySpec, new GCMParameterSpec(128, iv))
    new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
  }

  def generateSessionToken(): String = {
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding().encodeToString(bytes)
  }

  def sha256Hex(value: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8))
    bytes.map("%02x".format(_)).mkString
  }
}

