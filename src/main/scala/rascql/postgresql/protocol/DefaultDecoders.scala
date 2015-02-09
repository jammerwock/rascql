/*
 * Copyright 2015 Philip L. McMahon
 *
 * Philip L. McMahon licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package rascql.postgresql.protocol

import java.nio.charset.Charset
import java.text.SimpleDateFormat
import akka.util.ByteString

/**
 * @author Philip L. McMahon
 */
trait DefaultDecoders {

  type Decoder[T] = Column => Option[T]

  private val UTC = java.util.TimeZone.getTimeZone("UTC")

  object Decoder {

    def apply[T](fn: (ByteString, Charset) => T): Decoder[T] = {
      case Column(b, c) => b.map(fn(_, c))
    }

  }

  implicit class RichColumn(c: Column) {

    // TODO Support a "retry" with a different decoder if this attempt fails?
    // Eg, val r = Either[Y, X] = d.as[X].orElse[Y]
    def as[T](implicit d: Decoder[T]): T = d(c).get

    def asOpt[T](implicit d: Decoder[T]): Option[T] = d(c)

  }

  implicit val StringDecoder: Decoder[String] =
    Decoder { (b, c) => new String(b.toArray, c) }

  protected object FromStringDecoder {

    def apply[T](fn: String => T): Decoder[T] =
      StringDecoder.andThen(_.map(fn))

  }

  implicit val BigDecimalDecoder: Decoder[BigDecimal] =
    FromStringDecoder { BigDecimal(_) }

  implicit val BigIntDecoder: Decoder[BigInt] =
    FromStringDecoder { BigInt(_) }

  implicit val BooleanDecoder: Decoder[Boolean] =
    FromStringDecoder { _ == "t" }

  implicit val BytesDecoder: Decoder[Array[Byte]] =
    FromStringDecoder {
      _.stripPrefix("\\x").
        grouped(2).
        map(java.lang.Integer.parseInt(_, 16).toByte).
        toArray
    }

  protected object FromBytesDecoder {

    def apply[T](fn: Array[Byte] => T): Decoder[T] =
      BytesDecoder.andThen(_.map(fn))

  }

  implicit val ByteDecoder: Decoder[Byte] =
    FromBytesDecoder { _.head } // FIXME Fail if more than one byte

  implicit val CharDecoder: Decoder[Char] =
    FromStringDecoder { _.head } // FIXME Fail if more than one character

  // FIXME Poor performance
  implicit val DateDecoder: Decoder[java.util.Date] =
    FromStringDecoder {
      val sdf = new SimpleDateFormat("yyyy-MM-dd")
      sdf.setTimeZone(UTC)
      sdf.parse(_)
    }

  implicit val DoubleDecoder: Decoder[Double] =
    FromStringDecoder { _.toDouble }

  implicit val FloatDecoder: Decoder[Float] =
    FromStringDecoder { _.toFloat }

  implicit val IntDecoder: Decoder[Int] =
    FromStringDecoder { _.toInt }

  implicit val LongDecoder: Decoder[Long] =
    FromStringDecoder { _.toLong }

  implicit val ShortDecoder: Decoder[Short] =
    FromStringDecoder { _.toShort }

  // TODO Add Decoder[T] to Decoder[Option[T]] and other conversions?

}

object DefaultDecoders extends DefaultDecoders
