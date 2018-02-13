package fr.acinq.eclair.crypto

import java.math.BigInteger
import java.nio.ByteOrder

import fr.acinq.bitcoin.{BinaryData, Crypto, Protocol}
import fr.acinq.eclair.randomBytes
import grizzled.slf4j.Logging
import org.spongycastle.crypto.digests.SHA256Digest
import org.spongycastle.crypto.macs.HMac
import org.spongycastle.crypto.params.KeyParameter

/**
  * see http://noiseprotocol.org/
  */
object Noise {

  case class KeyPair(pub: BinaryData, priv: BinaryData)

  /**
    * Diffie-Helmann functions
    */
  trait DHFunctions {
    def name: String

    def generateKeyPair(priv: BinaryData): KeyPair

    def dh(keyPair: KeyPair, publicKey: BinaryData): BinaryData

    def dhLen: Int

    def pubKeyLen: Int
  }

  object Secp256k1DHFunctions extends DHFunctions {
    override val name = "secp256k1"

    override def generateKeyPair(priv: BinaryData): KeyPair = {
      require(priv.length == 32)
      KeyPair(Crypto.publicKeyFromPrivateKey(priv :+ 1.toByte), priv)
    }

    /**
      * this is what secp256k1's secp256k1_ecdh() returns
      *
      * @param keyPair
      * @param publicKey
      * @return sha256(publicKey * keyPair.priv in compressed format)
      */
    override def dh(keyPair: KeyPair, publicKey: BinaryData): BinaryData = {
      val point = Crypto.curve.getCurve.decodePoint(publicKey)
      val scalar = new BigInteger(1, keyPair.priv.take(32).toArray)
      val point1 = point.multiply(scalar).normalize()
      Crypto.sha256(point1.getEncoded(true))
    }

    override def dhLen: Int = 32

    override def pubKeyLen: Int = 33
  }

  /**
    * Cipher functions
    */
  trait CipherFunctions {
    def name: String

    //Encrypts plaintext using the cipher key k of 32 bytes and an 8-byte unsigned integer nonce n which must be unique
    // for the key k. Returns the ciphertext. Encryption must be done with an "AEAD" encryption mode with the associated
    // data ad (using the terminology from [1]) and returns a ciphertext that is the same size as the plaintext
    // plus 16 bytes for authentication data. The entire ciphertext must be indistinguishable from random if the key is secret.
    def encrypt(k: BinaryData, n: Long, ad: BinaryData, plaintext: BinaryData): BinaryData

    // Decrypts ciphertext using a cipher key k of 32 bytes, an 8-byte unsigned integer nonce n, and associated data ad.
    // Returns the plaintext, unless authentication fails, in which case an error is signaled to the caller.
    def decrypt(k: BinaryData, n: Long, ad: BinaryData, ciphertext: BinaryData): BinaryData
  }

  object Chacha20Poly1305CipherFunctions extends CipherFunctions {
    override val name = "ChaChaPoly"

    // as specified in BOLT #8
    def nonce(n: Long): BinaryData = BinaryData("00000000") ++ Protocol.writeUInt64(n, ByteOrder.LITTLE_ENDIAN)

    //Encrypts plaintext using the cipher key k of 32 bytes and an 8-byte unsigned integer nonce n which must be unique
    override def encrypt(k: BinaryData, n: Long, ad: BinaryData, plaintext: BinaryData): BinaryData = {
      val (ciphertext, mac) = ChaCha20Poly1305.encrypt(k, nonce(n), plaintext, ad)
      ciphertext ++ mac
    }

    // Decrypts ciphertext using a cipher key k of 32 bytes, an 8-byte unsigned integer nonce n, and associated data ad.
    override def decrypt(k: BinaryData, n: Long, ad: BinaryData, ciphertextAndMac: BinaryData): BinaryData = {
      val ciphertext: BinaryData = ciphertextAndMac.dropRight(16)
      val mac: BinaryData = ciphertextAndMac.takeRight(16)
      ChaCha20Poly1305.decrypt(k, nonce(n), ciphertext, ad, mac)
    }
  }

  /**
    * Hash functions
    */
  trait HashFunctions extends Logging {
    def name: String

    // Hashes some arbitrary-length data with a collision-resistant cryptographic hash function and returns an output of HASHLEN bytes.
    def hash(data: BinaryData): BinaryData

    // A constant specifying the size in bytes of the hash output. Must be 32 or 64.
    def hashLen: Int

    // A constant specifying the size in bytes that the hash function uses internally to divide its input for iterative processing. This is needed to use the hash function with HMAC (BLOCKLEN is B in [2]).
    def blockLen: Int

    // Applies HMAC from [2] using the HASH() function. This function is only called as part of HKDF(), below.
    def hmacHash(key: BinaryData, data: BinaryData): BinaryData

    // Takes a chaining_key byte sequence of length HASHLEN, and an input_key_material byte sequence with length either zero bytes, 32 bytes, or DHLEN bytes. Returns two byte sequences of length HASHLEN, as follows:
    // Sets temp_key = HMAC-HASH(chaining_key, input_key_material).
    // Sets output1 = HMAC-HASH(temp_key, byte(0x01)).
    // Sets output2 = HMAC-HASH(temp_key, output1 || byte(0x02)).
    // Returns the pair (output1, output2).
    def hkdf(chainingKey: BinaryData, inputMaterial: BinaryData): (BinaryData, BinaryData) = {
      val tempkey = hmacHash(chainingKey, inputMaterial)
      val output1 = hmacHash(tempkey, Seq(0x01.toByte))
      val output2 = hmacHash(tempkey, output1 ++ Seq(0x02.toByte))
      logger.debug(s"HKDF($chainingKey, $inputMaterial) = ($output1, $output2)")

      (output1, output2)
    }
  }

  object SHA256HashFunctions extends HashFunctions {
    override val name = "SHA256"

    override val hashLen = 32

    override val blockLen = 64

    override def hash(data: BinaryData) = Crypto.sha256(data)

    override def hmacHash(key: BinaryData, data: BinaryData) = {
      val mac = new HMac(new SHA256Digest())
      mac.init(new KeyParameter(key.toArray))
      mac.update(data.toArray, 0, data.length)
      val out = new Array[Byte](32)
      mac.doFinal(out, 0)
      out
    }
  }

  /**
    * Cipher state
    */
  trait CipherState {
    def cipher: CipherFunctions

    def initializeKey(key: BinaryData): CipherState = CipherState(key, cipher)

    def hasKey: Boolean

    def encryptWithAd(ad: BinaryData, plaintext: BinaryData): (CipherState, BinaryData)

    def decryptWithAd(ad: BinaryData, ciphertext: BinaryData): (CipherState, BinaryData)
  }

  /**
    * Uninitialized cipher state. Encrypt and decrypt do nothing (ciphertext = plaintext)
    *
    * @param cipher cipher functions
    */
  case class UninitializedCipherState(cipher: CipherFunctions) extends CipherState {
    override val hasKey = false

    override def encryptWithAd(ad: BinaryData, plaintext: BinaryData): (CipherState, BinaryData) = (this, plaintext)

    override def decryptWithAd(ad: BinaryData, ciphertext: BinaryData): (CipherState, BinaryData) = (this, ciphertext)
  }

  /**
    * Initialized cipher state
    *
    * @param k      key
    * @param n      nonce
    * @param cipher cipher functions
    */
  case class InitializedCipherState(k: BinaryData, n: Long, cipher: CipherFunctions) extends CipherState {
    require(k.length == 32)

    def hasKey = true

    def encryptWithAd(ad: BinaryData, plaintext: BinaryData): (CipherState, BinaryData) = {
      (this.copy(n = this.n + 1), cipher.encrypt(k, n, ad, plaintext))
    }

    def decryptWithAd(ad: BinaryData, ciphertext: BinaryData): (CipherState, BinaryData) = (this.copy(n = this.n + 1), cipher.decrypt(k, n, ad, ciphertext))
  }

  object CipherState {
    def apply(k: BinaryData, cipher: CipherFunctions): CipherState = k.length match {
      case 0 => UninitializedCipherState(cipher)
      case 32 => InitializedCipherState(k, 0, cipher)
    }

    def apply(cipher: CipherFunctions): CipherState = UninitializedCipherState(cipher)
  }

  /**
    *
    * @param cipherState   cipher state
    * @param ck            chaining key
    * @param h             hash
    * @param hashFunctions hash functions
    */
  case class SymmetricState(cipherState: CipherState, ck: BinaryData, h: BinaryData, hashFunctions: HashFunctions) extends Logging {
    def mixKey(inputKeyMaterial: BinaryData): SymmetricState = {
      logger.debug(s"ss = 0x$inputKeyMaterial")
      val (ck1, tempk) = hashFunctions.hkdf(ck, inputKeyMaterial)
      val tempk1: BinaryData = hashFunctions.hashLen match {
        case 32 => tempk
        case 64 => tempk.take(32)
      }
      this.copy(cipherState = cipherState.initializeKey(tempk1), ck = ck1)
    }

    def mixHash(data: BinaryData): SymmetricState = {
      this.copy(h = hashFunctions.hash(h ++ data))
    }

    def encryptAndHash(plaintext: BinaryData): (SymmetricState, BinaryData) = {
      val (cipherstate1, ciphertext) = cipherState.encryptWithAd(h, plaintext)
      (this.copy(cipherState = cipherstate1).mixHash(ciphertext), ciphertext)
    }

    def decryptAndHash(ciphertext: BinaryData): (SymmetricState, BinaryData) = {
      val (cipherstate1, plaintext) = cipherState.decryptWithAd(h, ciphertext)
      (this.copy(cipherState = cipherstate1).mixHash(ciphertext), plaintext)
    }

    def split: (CipherState, CipherState, BinaryData) = {
      val (tempk1, tempk2) = hashFunctions.hkdf(ck, BinaryData.empty)
      (cipherState.initializeKey(tempk1.take(32)), cipherState.initializeKey(tempk2.take(32)), ck)
    }
  }

  object SymmetricState {
    def apply(protocolName: BinaryData, cipherFunctions: CipherFunctions, hashFunctions: HashFunctions): SymmetricState = {
      val h: BinaryData = if (protocolName.length <= hashFunctions.hashLen)
        protocolName ++ Seq.fill[Byte](hashFunctions.hashLen - protocolName.length)(0)
      else hashFunctions.hash(protocolName)

      new SymmetricState(CipherState(cipherFunctions), ck = h, h = h, hashFunctions)
    }
  }

  sealed trait MessagePattern

  case object S extends MessagePattern

  case object E extends MessagePattern

  case object EE extends MessagePattern

  case object ES extends MessagePattern

  case object SE extends MessagePattern

  case object SS extends MessagePattern

  type MessagePatterns = List[MessagePattern]

  object HandshakePattern {
    val validInitiatorPatterns: Set[MessagePatterns] = Set(Nil, E :: Nil, S :: Nil, E :: S :: Nil)

    def isValidInitiator(initiator: MessagePatterns): Boolean = validInitiatorPatterns.contains(initiator)
  }

  case class HandshakePattern(name: String, initiatorPreMessages: MessagePatterns, responderPreMessages: MessagePatterns, messages: List[MessagePatterns]) {

    import HandshakePattern._

    require(isValidInitiator(initiatorPreMessages))
    require(isValidInitiator(responderPreMessages))
  }

  /**
    * standard handshake patterns
    */

  val handshakePatternNN = HandshakePattern("NN", initiatorPreMessages = Nil, responderPreMessages = Nil, messages = List(E :: Nil, E :: EE :: Nil))
  val handshakePatternXK = HandshakePattern("XK", initiatorPreMessages = Nil, responderPreMessages = S :: Nil, messages = List(E :: ES :: Nil, E :: EE :: Nil, S :: SE :: Nil))

  trait ByteStream {
    def nextBytes(length: Int): BinaryData
  }

  object RandomBytes extends ByteStream {

    override def nextBytes(length: Int) = randomBytes(length)
  }

  sealed trait HandshakeState

  case class HandshakeStateWriter(messages: List[MessagePatterns], state: SymmetricState, s: KeyPair, e: KeyPair, rs: BinaryData, re: BinaryData, dh: DHFunctions, byteStream: ByteStream) extends HandshakeState with Logging {
    def toReader: HandshakeStateReader = HandshakeStateReader(messages, state, s, e, rs, re, dh, byteStream)

    /**
      *
      * @param payload input message (can be empty)
      * @return a (reader, output, Option[(cipherstate, cipherstate)] tuple.
      *         The output will be sent to the other side, and we will read its answer using the returned reader instance
      *         When the handshake is over (i.e. there are no more handshake patterns to process) the last item will
      *         contain 2 cipherstates than can be used to encrypt/decrypt further communication
      */
    def write(payload: BinaryData): (HandshakeStateReader, BinaryData, Option[(CipherState, CipherState, BinaryData)]) = {
      require(!messages.isEmpty)
      logger.debug(s"write($payload)")

      val (writer1, buffer1) = messages.head.foldLeft(this -> BinaryData.empty) {
        case ((writer, buffer), pattern) => pattern match {
          case E =>
            val e1 = dh.generateKeyPair(byteStream.nextBytes(dh.dhLen))
            val state1 = writer.state.mixHash(e1.pub)
            (writer.copy(state = state1, e = e1), buffer ++ e1.pub)
          case S =>
            val (state1, ciphertext) = writer.state.encryptAndHash(s.pub)
            (writer.copy(state = state1), buffer ++ ciphertext)
          case EE =>
            val state1 = writer.state.mixKey(dh.dh(writer.e, writer.re))
            (writer.copy(state = state1), buffer)
          case SS =>
            val state1 = writer.state.mixKey(dh.dh(writer.s, writer.rs))
            (writer.copy(state = state1), buffer)
          case ES =>
            val state1 = writer.state.mixKey(dh.dh(writer.e, writer.rs))
            (writer.copy(state = state1), buffer)
          case SE =>
            val state1 = writer.state.mixKey(dh.dh(writer.s, writer.re))
            (writer.copy(state = state1), buffer)
        }
      }

      val (state1, ciphertext) = writer1.state.encryptAndHash(payload)
      val buffer2 = buffer1 ++ ciphertext
      val writer2 = writer1.copy(messages = messages.tail, state = state1)
      logger.debug(s"h = 0x${state1.h}")
      logger.debug(s"output: 0x${BinaryData(buffer2)}")

      (writer2.toReader, buffer2, if (messages.tail.isEmpty) Some(writer2.state.split) else None)
    }
  }

  object HandshakeStateWriter {
    def apply(messages: List[MessagePatterns], state: SymmetricState, s: KeyPair, e: KeyPair, rs: BinaryData, re: BinaryData, dh: DHFunctions): HandshakeStateWriter = new HandshakeStateWriter(messages, state, s, e, rs, re, dh, RandomBytes)
  }

  case class HandshakeStateReader(messages: List[MessagePatterns], state: SymmetricState, s: KeyPair, e: KeyPair, rs: BinaryData, re: BinaryData, dh: DHFunctions, byteStream: ByteStream) extends HandshakeState with Logging {
    def toWriter: HandshakeStateWriter = HandshakeStateWriter(messages, state, s, e, rs, re, dh, byteStream)

    /** *
      *
      * @param message input message
      * @return a (writer, payload, Option[(cipherstate, cipherstate)] tuple.
      *         The payload contains the original payload used be the sender and a writer that will be used to create the
      *         next message. When the handshake is over (i.e. there are no more handshake patterns to process) the last item will
      *         contain 2 cipherstates than can be used to encrypt/decrypt further communication
      */
    def read(message: BinaryData): (HandshakeStateWriter, BinaryData, Option[(CipherState, CipherState, BinaryData)]) = {
      logger.debug(s"input: 0x$message")
      val (reader1, buffer1) = messages.head.foldLeft(this -> message) {
        case ((reader, buffer), pattern) => pattern match {
          case E =>
            val (re1, buffer1) = buffer.splitAt(dh.pubKeyLen)
            val state1 = reader.state.mixHash(re1)
            (reader.copy(state = state1, re = re1), buffer1)
          case S =>
            val len = if (reader.state.cipherState.hasKey) dh.pubKeyLen + 16 else dh.pubKeyLen
            val (temp, buffer1) = buffer.splitAt(len)
            val (state1, rs1) = reader.state.decryptAndHash(temp)
            logger.debug(s"rs = $rs1")
            logger.debug(s"h = ${state1.h}")
            (reader.copy(state = state1, rs = rs1), buffer1)
          case EE =>
            val state1 = reader.state.mixKey(dh.dh(reader.e, reader.re))
            (reader.copy(state = state1), buffer)
          case SS =>
            val state1 = reader.state.mixKey(dh.dh(reader.s, reader.rs))
            (reader.copy(state = state1), buffer)
          case ES =>
            val ss = dh.dh(reader.s, reader.re)
            val state1 = reader.state.mixKey(ss)
            (reader.copy(state = state1), buffer)
          case SE =>
            val state1 = reader.state.mixKey(dh.dh(reader.e, reader.rs))
            (reader.copy(state = state1), buffer)
        }
      }

      val (state1, payload) = reader1.state.decryptAndHash(buffer1)
      logger.debug(s"h = 0x${state1.h}")
      val reader2 = reader1.copy(messages = messages.tail, state = state1)
      (reader2.toWriter, payload, if (messages.tail.isEmpty) Some(reader2.state.split) else None)
    }
  }

  object HandshakeStateReader {
    def apply(messages: List[MessagePatterns], state: SymmetricState, s: KeyPair, e: KeyPair, rs: BinaryData, re: BinaryData, dh: DHFunctions): HandshakeStateReader = new HandshakeStateReader(messages, state, s, e, rs, re, dh, RandomBytes)
  }

  object HandshakeState {

    private def makeSymmetricState(handshakePattern: HandshakePattern, prologue: BinaryData, dh: DHFunctions, cipher: CipherFunctions, hash: HashFunctions): SymmetricState = {
      val name = "Noise_" + handshakePattern.name + "_" + dh.name + "_" + cipher.name + "_" + hash.name
      val symmetricState = SymmetricState(name.getBytes("UTF-8"), cipher, hash)
      symmetricState.mixHash(prologue)
    }

    def initializeWriter(handshakePattern: HandshakePattern, prologue: BinaryData, s: KeyPair, e: KeyPair, rs: BinaryData, re: BinaryData, dh: DHFunctions, cipher: CipherFunctions, hash: HashFunctions, byteStream: ByteStream = RandomBytes): HandshakeStateWriter = {
      val symmetricState = makeSymmetricState(handshakePattern, prologue, dh, cipher, hash)
      val symmetricState1 = (handshakePattern.initiatorPreMessages).foldLeft(symmetricState) {
        case (state, E) => state.mixHash(e.pub)
        case (state, S) => state.mixHash(s.pub)
        case _ => throw new RuntimeException("invalid pre-message")
      }
      val symmetricState2 = (handshakePattern.responderPreMessages).foldLeft(symmetricState1) {
        case (state, E) => state.mixHash(re)
        case (state, S) => state.mixHash(rs)
        case _ => throw new RuntimeException("invalid pre-message")
      }
      HandshakeStateWriter(handshakePattern.messages, symmetricState2, s, e, rs, re, dh, byteStream)
    }

    def initializeReader(handshakePattern: HandshakePattern, prologue: BinaryData, s: KeyPair, e: KeyPair, rs: BinaryData, re: BinaryData, dh: DHFunctions, cipher: CipherFunctions, hash: HashFunctions, byteStream: ByteStream = RandomBytes): HandshakeStateReader = {
      val symmetricState = makeSymmetricState(handshakePattern, prologue, dh, cipher, hash)
      val symmetricState1 = handshakePattern.initiatorPreMessages.foldLeft(symmetricState) {
        case (state, E) => state.mixHash(re)
        case (state, S) => state.mixHash(rs)
        case _ => throw new RuntimeException("invalid pre-message")
      }
      val symmetricState2 = handshakePattern.responderPreMessages.foldLeft(symmetricState1) {
        case (state, E) => state.mixHash(e.pub)
        case (state, S) => state.mixHash(s.pub)
        case _ => throw new RuntimeException("invalid pre-message")
      }
      HandshakeStateReader(handshakePattern.messages, symmetricState2, s, e, rs, re, dh, byteStream)
    }
  }

}

