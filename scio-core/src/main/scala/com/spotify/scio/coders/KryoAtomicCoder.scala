/*
 * Copyright 2016 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.spotify.scio.coders

import java.io.{ByteArrayOutputStream, IOException, InputStream, OutputStream}

import com.google.cloud.dataflow.sdk.coders.Coder.Context
import com.google.cloud.dataflow.sdk.coders._
import com.google.cloud.dataflow.sdk.util.VarInt
import com.google.common.io.ByteStreams
import com.google.common.reflect.ClassPath
import com.google.protobuf.Message
import com.twitter.chill._
import com.twitter.chill.algebird.AlgebirdRegistrar
import com.twitter.chill.protobuf.ProtobufSerializer
import org.apache.avro.generic.GenericRecord
import org.apache.avro.specific.SpecificRecordBase
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.convert.Wrappers.JIterableWrapper

private object KryoRegistrarLoader {

  private val logger = LoggerFactory.getLogger(this.getClass)

  def load(k: Kryo): Unit = {
    logger.debug("Loading KryoRegistrars: " + registrars.mkString(", "))
    registrars.foreach(_(k))
  }

  private val registrars: Seq[IKryoRegistrar] = {
    logger.debug("Initializing KryoRegistrars")
    val classLoader = Thread.currentThread().getContextClassLoader
    ClassPath.from(classLoader).getAllClasses.asScala.toSeq
      .filter(_.getName.endsWith("KryoRegistrar"))
      .flatMap { clsInfo =>
        val optCls: Option[IKryoRegistrar] = try {
          val cls = clsInfo.load()
          if (classOf[AnnotatedKryoRegistrar] isAssignableFrom cls) {
            Some(cls.newInstance().asInstanceOf[IKryoRegistrar])
          } else {
            None
          }
        } catch {
          case _: Throwable => None
        }
        optCls
      }
  }

}

private[scio] class KryoAtomicCoder[T] extends AtomicCoder[T] {

  @transient
  private lazy val kryo: ThreadLocal[Kryo] = new ThreadLocal[Kryo] {
    override def initialValue(): Kryo = {
      val k = KryoSerializer.registered.newKryo()

      k.forClass(new CoderSerializer(InstantCoder.of()))
      k.forClass(new CoderSerializer(TableRowJsonCoder.of()))

      // java.lang.Iterable.asScala returns JIterableWrapper which causes problem.
      // Treat it as standard Iterable instead.
      k.register(classOf[JIterableWrapper[_]], new JIterableWrapperSerializer)

      k.forSubclass[SpecificRecordBase](new SpecificAvroSerializer)
      k.forSubclass[GenericRecord](new GenericAvroSerializer)
      k.forSubclass[Message](new ProtobufSerializer)

      k.forClass(new KVSerializer)
      // TODO:
      // TimestampedValueCoder

      new AlgebirdRegistrar()(k)
      KryoRegistrarLoader.load(k)

      k
    }
  }

  override def encode(value: T, outStream: OutputStream, context: Context): Unit = {
    if (value == null) {
      throw new CoderException("cannot encode a null value")
    }
    if (context.isWholeStream) {
      val output = new Output(outStream)
      kryo.get().writeClassAndObject(output, value)
      output.flush()
    } else {
      val s = new ByteArrayOutputStream()
      val output = new Output(s)
      kryo.get().writeClassAndObject(output, value)
      output.flush()

      VarInt.encode(s.size(), outStream)
      outStream.write(s.toByteArray)
    }
  }

  override def decode(inStream: InputStream, context: Context): T = {
    val o = if (context.isWholeStream) {
      kryo.get().readClassAndObject(new Input(inStream))
    } else {
      val length = VarInt.decodeInt(inStream)
      if (length < 0) {
        throw new IOException("invalid length " + length)
      }

      val value = Array.ofDim[Byte](length)
      ByteStreams.readFully(inStream, value)
      kryo.get().readClassAndObject(new Input(value))
    }
    o.asInstanceOf[T]
  }

}

private[scio] object KryoAtomicCoder {
  def apply[T]: Coder[T] = new KryoAtomicCoder[T]
}
