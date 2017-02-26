package fr.acinq.eclair.db

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import fr.acinq.bitcoin.BinaryData


/**
  * Created by fabrice on 17/02/17.
  */
object JavaSerializer {
  def serialize[T](cs: T): BinaryData = {
    val bos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(bos)
    oos.writeObject(cs)
    bos.toByteArray
  }

  def deserialize[T](input: BinaryData): T = {
    val bis = new ByteArrayInputStream(input)
    val osi = new ObjectInputStream(bis)
    osi.readObject().asInstanceOf[T]
  }
}