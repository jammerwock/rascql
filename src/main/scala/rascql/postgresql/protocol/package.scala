/*
 * Copyright 2014 Philip L. McMahon
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

package rascql.postgresql

import java.nio.ByteOrder
import java.nio.charset.Charset
import java.security.MessageDigest
import scala.annotation.switch
import scala.collection.immutable
import scala.util.control.NoStackTrace
import scala.util.Try
import akka.util._

package object protocol {

  type OID = Int
  type ProcessID = Int
  type SecretKey = Int

  // Type aliases to simplify `isInstanceOf[T]` checks
  type BindComplete = BindComplete.type
  type CloseComplete = CloseComplete.type
  type CopyDone = CopyDone.type
  type EmptyQueryResponse = EmptyQueryResponse.type
  type Flush = Flush.type
  type NoData = NoData.type
  type ParseComplete = ParseComplete.type
  type PortalSuspended = PortalSuspended.type
  type SSLRequest = SSLRequest.type
  type Sync = Sync.type
  type Terminate = Terminate.type

  private[protocol] implicit val order = ByteOrder.BIG_ENDIAN

  private[protocol] val NUL = 0x0.toByte
  private[protocol] val HexChunks = 0x0.to(0xFF).map("%02x".format(_).toLowerCase).map(ByteString(_))

  private[protocol] implicit class RichByte(val b: Byte) extends AnyVal {

    @inline def asHex: ByteString = HexChunks(b & 0xFF)

  }

  private[protocol] implicit class RichArrayOfByte(val a: Array[Byte]) extends AnyVal {

    @inline def asHex: ByteString = a.foldLeft(ByteString.empty)(_ ++ _.asHex)

  }

  private[protocol] implicit class RichByteIterator(val b: ByteIterator) extends AnyVal {

    def getCString(c: Charset): String = {
      val iter = b.clone
      val result = iter.takeWhile(_ != NUL) // FIXME Throw error if no NUL found (result length equals iter length)
      b.drop(result.len + 1) // Consume up to and including NUL byte
      new String(result.toArray[Byte], c)
    }

    // Since take/slice both truncate the iterator and we want to return a sub-iterator for a given range, we do this instead.
    def nextBytes(n: Int): ByteIterator = {
      val iter = b.clone
      b.drop(n)
      iter.take(n)
    }

    def splitAt(n: Int): (ByteIterator, ByteIterator) =
      b.clone.take(n) -> b.drop(n)

  }

  private[protocol] implicit class RichByteStringBuilder(val b: ByteStringBuilder) extends AnyVal {

    @inline def putCString(content: String, charset: Charset): ByteStringBuilder =
      b.putBytes(content.getBytes(charset)).putNUL

    @inline def putNUL: ByteStringBuilder = b.putByte(NUL)

    def prependLength: ByteString =
      ByteString.newBuilder.putInt(b.length + 4).result ++ b.result // Include length of int

  }

  private[protocol] implicit class RichByteString(val b: ByteString) extends AnyVal {

    def prependLength: ByteString =
      ByteString.newBuilder.putInt(b.length).result ++ b

  }

  private[protocol] implicit class RichOptionOfFieldFormats(val f: Option[FieldFormats]) extends AnyVal {
    def encoded: ByteString = f.fold(FieldFormats.Default)(_.encoded)
  }

  private[protocol] implicit class RichSeqOfParameter(val s: Seq[Parameter]) extends AnyVal {
    def encode(c: Charset): ByteString = {
      val size = ByteString.newBuilder.putShort(s.size).result
      val (formats, values) = s.unzip { p => p.format.toShort -> p.encode(c) }
      size ++
        formats.foldLeft(ByteString.newBuilder)(_.putShort(_)).result ++
        values.foldLeft(size)(_ ++ _)
    }
  }

  sealed trait FrontendMessage {

    def encode(charset: Charset): ByteString

  }

  object FrontendMessage {

    import scala.language.implicitConversions

    sealed abstract class Fixed(private val bytes: ByteString) extends FrontendMessage {

      def encode(c: Charset) = bytes

    }

    object Fixed {

      implicit def toByteString(f: Fixed): ByteString = f.bytes

    }

    sealed abstract class Empty(typ: Char)
      extends FrontendMessage.Fixed(ByteString.newBuilder.putByte(typ.toByte).putInt(4).result)

    sealed abstract class NonEmpty(typ: Byte) extends FrontendMessage {

      final def encode(c: Charset) = ByteString(typ) ++
        ByteString.newBuilder.append(encodeContent(c)).prependLength

      protected def encodeContent(c: Charset): ByteString

    }

    object NonEmpty {

      implicit def toByteString(m: NonEmpty)(implicit c: Charset): ByteString =
        m.encode(c)

    }

  }

  sealed trait BackendMessage

  object BackendMessage {

    sealed trait Empty extends BackendMessage with Decoder {

      protected def decodeContent(c: Charset, b: ByteIterator) = this

    }

  }

  sealed trait AsyncOperation { _: BackendMessage => }

  sealed trait Decoder {

    import Decoder._

    def decode(charset: Charset, bytes: ByteString): Result =
      if (bytes.length < 4) NeedBytes(4 - bytes.length)
      else {
        val iter = bytes.iterator
        val length = iter.getInt - 4 // Drop four bytes for int
        if (iter.len < length) NeedBytes(length - iter.len) // TODO This could be a "LengthKnown" intermediate state
        else {
          val (content, rest) = iter.splitAt(length)
          MessageDecoded(decodeContent(charset, content), rest.toByteString)
        }
      }

    protected def decodeContent(c: Charset, b: ByteIterator): BackendMessage

  }

  object Decoder {

    sealed trait Result

    case class NeedBytes(count: Int) extends Result

    case class MessageDecoded(message: BackendMessage, tail: ByteString) extends Result

    def apply(code: Byte): Decoder = (code: @switch) match {
      case 'R' => AuthenticationRequest
      case 'K' => BackendKeyData
      case '2' => BindComplete
      case '3' => CloseComplete
      case 'C' => CommandComplete
      case 'd' => CopyData
      case 'c' => CopyDone
      case 'G' => CopyInResponse
      case 'H' => CopyOutResponse
      case 'W' => CopyBothResponse
      case 'D' => DataRow
      case 'I' => EmptyQueryResponse
      case 'E' => ErrorResponse
      case 'V' => FunctionCallResponse
      case 'n' => NoData
      case 'N' => NoticeResponse
      case 'A' => NotificationResponse
      case 't' => ParameterDescription
      case 'S' => ParameterStatus
      case '1' => ParseComplete
      case 's' => PortalSuspended
      case 'Z' => ReadyForQuery
      case 'T' => RowDescription
      case _ => throw UnsupportedMessageType(code)
    }

  }

  sealed trait AuthenticationRequest extends BackendMessage

  object AuthenticationRequest extends Decoder {

    protected def decodeContent(c: Charset, b: ByteIterator) = {
      (b.getInt: @switch) match {
        case 0 => AuthenticationOk
        case 2 => AuthenticationKerberosV5
        case 3 => AuthenticationCleartextPassword
        case 5 => AuthenticationMD5Password(b.toByteString) // TODO compact?
        case 6 => AuthenticationSCMCredential
        case 7 => AuthenticationGSS
        case 8 => AuthenticationGSSContinue(b.toByteString) // TODO compact?
        case 9 => AuthenticationSSPI
        case m => throw UnsupportedAuthenticationMethod(m)
      }
    }

  }

  case object AuthenticationOk extends AuthenticationRequest
  case object AuthenticationKerberosV5 extends AuthenticationRequest
  case object AuthenticationCleartextPassword extends AuthenticationRequest
  case class AuthenticationMD5Password(salt: ByteString) extends AuthenticationRequest
  case object AuthenticationSCMCredential extends AuthenticationRequest
  case object AuthenticationGSS extends AuthenticationRequest
  case class AuthenticationGSSContinue(data: ByteString) extends AuthenticationRequest
  case object AuthenticationSSPI extends AuthenticationRequest

  case class BackendKeyData(processId: ProcessID, secretKey: SecretKey) extends BackendMessage

  object BackendKeyData extends Decoder {

    protected def decodeContent(c: Charset, b: ByteIterator) =
      BackendKeyData(b.getInt, b.getInt)

  }

  case class Bind(parameters: Seq[Parameter],
                  destination: Portal = Portal.Unnamed,
                  source: PreparedStatement = PreparedStatement.Unnamed,
                  resultFormats: Option[FieldFormats] = None) extends FrontendMessage.NonEmpty('B') {

    protected def encodeContent(c: Charset) =
      ByteString.newBuilder.
        putCString(destination.name, c).
        putCString(source.name, c).
        append(parameters.encode(c)).
        append(resultFormats.encoded).
        result

  }

  case object BindComplete extends BackendMessage.Empty

  case class CancelRequest(processId: ProcessID, secretKey: SecretKey)
    extends FrontendMessage.Fixed(CancelRequest.Prefix ++
      ByteString.newBuilder.putInt(processId).putInt(secretKey).result)

  object CancelRequest {

    private[protocol] val Prefix =
      ByteString.newBuilder.putInt(16).putInt(80877102).result

  }

  case class Close(target: Closable) extends FrontendMessage.NonEmpty('C') {

    protected def encodeContent(c: Charset) = target.encode(c)

  }

  case object CloseComplete extends BackendMessage.Empty

  case class CommandComplete(tag: CommandTag) extends BackendMessage

  object CommandComplete extends Decoder {

    import CommandTag._

    // TODO Use a Try to avoid exceptions/invalid data
    protected def decodeContent(c: Charset, b: ByteIterator) = {
      CommandComplete(b.getCString(c).split(' ') match {
        case Array(name, oid, rows) =>
          // TODO Verify large unsigned OID parses properly
          OIDWithRows(name, oid.toInt, rows.toInt)
        case Array(name, rows) =>
          RowsAffected(name, rows.toInt)
        case Array(name) =>
          NameOnly(name)
      })
    }

  }

  case class CopyData(value: ByteString) extends FrontendMessage.NonEmpty('d') with BackendMessage {

    protected def encodeContent(c: Charset) = value

  }

  object CopyData extends Decoder {

    protected def decodeContent(c: Charset, b: ByteIterator) =
      CopyData(b.toByteString) // TODO compact?

  }

  case object CopyDone extends FrontendMessage.Empty('c') with BackendMessage.Empty

  case class CopyFail(error: String) extends FrontendMessage.NonEmpty('f') {

    protected def encodeContent(c: Charset) =
      ByteString.newBuilder.
        putCString(error, c).
        result

  }

  abstract class CopyResponse extends BackendMessage {

    def format: FieldFormats

  }

  sealed abstract class CopyResponseDecoder(typ: Byte) extends Decoder {

    import Format._
    import FieldFormats._

    protected def decodeContent(c: Charset, b: ByteIterator) = {
      val format = Format.decode(b.getByte)
      val size = b.getShort
      val types = Vector.fill(size)(b.getByte).map(Format.decode(_))
      apply(format match {
        case Text =>
          // All columns must have format text
          types.zipWithIndex.
            collect {
              case (Format.Binary, idx) => idx
            } match {
              case Vector() =>
                Matched(format, size)
              case indices =>
                throw UnexpectedBinaryColumnFormat(indices)
            }
        case Binary =>
          Mixed(types)
      })
    }

    def apply(format: FieldFormats): CopyResponse

  }

  case class CopyInResponse(format: FieldFormats) extends CopyResponse

  object CopyInResponse extends CopyResponseDecoder('G')

  case class CopyOutResponse(format: FieldFormats) extends CopyResponse

  object CopyOutResponse extends CopyResponseDecoder('H')

  case class CopyBothResponse(format: FieldFormats) extends CopyResponse

  object CopyBothResponse extends CopyResponseDecoder('W')

  case class DataRow(columns: DataRow.Columns) extends BackendMessage

  object DataRow extends Decoder {

    case class Column(value: Option[ByteString], charset: Charset)

    type Columns = immutable.IndexedSeq[Column]

    protected def decodeContent(c: Charset, b: ByteIterator) =
      DataRow((0 until b.getShort) map { _ =>
        Column(
          Option(b.getInt).
            filterNot(_ < 0).
            map(b.nextBytes(_).toByteString), // TODO compact?
          c
        )
      })

  }

  case class Describe(target: Closable)  extends FrontendMessage.NonEmpty('D') {

    protected def encodeContent(c: Charset) = target.encode(c)

  }

  case object EmptyQueryResponse extends BackendMessage.Empty

  case class ErrorResponse(fields: ErrorResponse.Fields) extends BackendMessage

  object ErrorResponse extends Decoder with ResponseFields {

    protected def decodeContent(c: Charset, b: ByteIterator) =
      ErrorResponse(decodeAll(c, b))

  }

  case class Execute(portal: Portal, maxRows: Option[Int] = None) extends FrontendMessage.NonEmpty('E') {

    protected def encodeContent(c: Charset) =
      ByteString.newBuilder.
        putCString(portal.name, c).
        putInt(maxRows.getOrElse(0)).
        result

  }

  case object Flush extends FrontendMessage.Empty('H')

  case class FunctionCall(target: OID,
                          arguments: immutable.Seq[Parameter],
                          result: Format) extends FrontendMessage.NonEmpty('F') {

    protected def encodeContent(c: Charset) =
      ByteString.newBuilder.
        putInt(target).
        append(arguments.encode(c)).
        putShort(result.toShort).
        result

  }

  case class FunctionCallResponse(value: Option[ByteString]) extends BackendMessage

  object FunctionCallResponse extends Decoder {

    protected def decodeContent(c: Charset, b: ByteIterator) =
      FunctionCallResponse(
        Option(b.getInt).
          filter(_ > 0).
          map(b.nextBytes(_).toByteString) // TODO compact?
      )

  }

  case object NoData extends BackendMessage.Empty

  case class NoticeResponse(fields: NoticeResponse.Fields) extends BackendMessage with AsyncOperation

  object NoticeResponse extends Decoder with ResponseFields {

    protected def decodeContent(c: Charset, b: ByteIterator) =
      NoticeResponse(decodeAll(c, b))

  }

  case class NotificationResponse(processId: Int, channel: String, payload: String) extends BackendMessage with AsyncOperation

  object NotificationResponse extends Decoder {

    protected def decodeContent(c: Charset, b: ByteIterator) =
      NotificationResponse(b.getInt, b.getCString(c), b.getCString(c))

  }

  case class ParameterDescription(types: immutable.IndexedSeq[OID]) extends BackendMessage

  object ParameterDescription extends Decoder {

    protected def decodeContent(c: Charset, b: ByteIterator) =
      ParameterDescription(Vector.fill(b.getShort)(b.getInt))

  }

  case class ParameterStatus(key: String, value: String) extends BackendMessage with AsyncOperation

  object ParameterStatus extends Decoder {

    protected def decodeContent(c: Charset, b: ByteIterator) =
      ParameterStatus(b.getCString(c), b.getCString(c))

  }

  case class Parse(query: String,
                   types: immutable.Seq[OID] = Nil,
                   destination: PreparedStatement = PreparedStatement.Unnamed) extends FrontendMessage.NonEmpty('P') {

    protected def encodeContent(c: Charset) =
      ByteString.newBuilder.
        putCString(destination.name, c).
        putCString(query, c).
        putShort(types.size).
        putInts(types.toArray).
        result

  }

  case object ParseComplete extends BackendMessage.Empty

  case class PasswordMessage(password: Password) extends FrontendMessage.NonEmpty('p') {

    protected def encodeContent(c: Charset) = password.encode(c)

  }

  case object PortalSuspended extends BackendMessage.Empty

  case class Query(str: String) extends FrontendMessage.NonEmpty('Q') {

    protected def encodeContent(c: Charset) =
      ByteString.newBuilder.
        putCString(str, c).
        result

  }

  case class ReadyForQuery(status: TransactionStatus) extends BackendMessage

  object ReadyForQuery extends Decoder {

    import TransactionStatus._

    protected def decodeContent(c: Charset, b: ByteIterator) =
      ReadyForQuery(
        (b.getByte: @switch) match {
          case 'I' => Idle
          case 'T' => Open
          case 'E' => Failed
          case s => throw UnsupportedTransactionStatus(s)
        }
      )

  }

  case class RowDescription(fields: RowDescription.Fields) extends BackendMessage

  object RowDescription extends Decoder {

    case class Field(name: String,
                     tableOid: OID,
                     column: Int,
                     dataType: DataType,
                     format: Format)

    type Fields = immutable.IndexedSeq[Field]

    case class DataType(oid: OID, size: Long, modifier: Int)

    protected def decodeContent(c: Charset, b: ByteIterator) = {
      RowDescription(
        (0 until b.getShort).map { index =>
          Field(
            name = b.getCString(c),
            tableOid = b.getInt,
            column = b.getShort,
            dataType = DataType(
              oid = b.getInt,
              size = b.getShort,
              modifier = b.getInt
            ),
            format = Format.decode(b.getShort) // FIXME When returned after describe, will always be zero for "unknown"
          )
        }
      )
    }

  }

  // Like StartupMessage, this has no type byte
  case object SSLRequest
    extends FrontendMessage.Fixed(ByteString.newBuilder.putInt(80877103).prependLength) {

    sealed trait Reply

    case object Accepted extends Reply

    case object Rejected extends Reply

    object Reply {

      def decode(b: Byte): Reply = (b: @switch) match {
        case 'S' => Accepted
        case 'N' => Rejected
        case _ =>
          throw UnsupportedSSLReply(b)
      }

    }

  }

  // Can't extend FrontentMessage.NonEmpty since there is no message type byte
  case class StartupMessage(user: String, parameters: immutable.Map[String, String]) extends FrontendMessage {

    import StartupMessage._

    def encode(c: Charset) =
      parameters.+(User -> user). // Override any "user" parameter
        foldLeft(ByteString.newBuilder.append(Version)) {
          case (b, (k, v)) =>
              b.putCString(k, c).
                putCString(v, c)
        }.
        putNUL.
        prependLength

  }

  object StartupMessage {

    private val Version = ByteString.newBuilder.putInt(196608).result // 3.0
    private val User = "user"

  }

  case object Sync extends FrontendMessage.Empty('S')

  case object Terminate extends FrontendMessage.Empty('X')

  // Note that the PostgreSQL documentation recommends only encoding parameters
  // using the text format, since it is portable across versions.
  sealed trait Parameter {

    def format: Format

    // Fully encode parameter including 4-byte length prefix
    def encode(c: Charset): ByteString

  }

  // TODO Make inner class of Bind/FunctionCall companion objects?
  object Parameter {

    private case class Raw(format: Format, bytes: ByteString) extends Parameter {
      def encode(c: Charset) = bytes
    }

    private case class Dynamic(format: Format)(fn: Charset => ByteString) extends Parameter {
      def encode(c: Charset) = fn(c).prependLength
    }

    val NULL: Parameter = Raw(Format.Text, ByteString.newBuilder.putInt(-1).result)

    def apply(f: Format)(fn: Charset => ByteString): Parameter = Dynamic(f)(fn)

    def apply(fn: Charset => ByteString): Parameter = Dynamic(Format.Text)(fn)

    def apply(bytes: ByteString, f: Format = Format.Text): Parameter =
      Raw(f, bytes.prependLength)

  }

  sealed trait CommandTag

  object CommandTag {

    // FIXME Should the rows count be a long?
    case class OIDWithRows(name: String, oid: OID, rows: Int) extends CommandTag
    case class RowsAffected(name: String, rows: Int) extends CommandTag
    case class NameOnly(name: String) extends CommandTag

  }

  sealed abstract class Closable(typ: Byte) {

    def name: String

    def encode(c: Charset): ByteString =
      ByteString.newBuilder.
        putByte(typ).
        putCString(name, c).
        result

  }

  sealed abstract class Portal extends Closable('P')

  object Portal {

    case class Named(name: String) extends Portal {
      require(name.nonEmpty)
    }

    case object Unnamed extends Portal {
      val name = ""
    }

    def apply(name: String): Portal =
      if (name.isEmpty) Unnamed else Named(name)

    def unapply(p: Portal): Option[String] = Some(p.name)

  }

  // TODO Duplicates format of Portal -- DRY using macro?
  sealed abstract class PreparedStatement extends Closable('S')

  object PreparedStatement {

    case class Named(name: String) extends PreparedStatement {
      require(name.nonEmpty)
    }

    case object Unnamed extends PreparedStatement {
      val name = ""
    }

    def apply(name: String): PreparedStatement =
      if (name.isEmpty) Unnamed else Named(name)

    def unapply(p: PreparedStatement): Option[String] = Some(p.name)

  }

  sealed abstract class Format(typ: Byte) {

    def toShort = typ.toShort

    def toByte = typ

  }

  object Format {

    case object Text extends Format(0)
    case object Binary extends Format(1)

    def decode(typ: Short) = (typ: @switch) match {
      case 0 => Text
      case 1 => Binary
      case _ => throw UnsupportedFormatType(typ)
    }

  }

  sealed trait FieldFormats {

    def encoded: ByteString

    def apply(index: Short): Format

  }

  object FieldFormats {

    private[protocol] val Default = ByteString.newBuilder.putShort(0).result

    case class Matched(format: Format, count: Short) extends FieldFormats {
      def apply(index: Short) =
        if (index < count) format
        else throw new IndexOutOfBoundsException
      def encoded = ByteString.newBuilder.putShort(1).putShort(format.toShort).result
    }

    case class Mixed(types: immutable.IndexedSeq[Format]) extends FieldFormats {
      def apply(index: Short) = types(index)
      def encoded = ByteString.newBuilder.putShort(types.size).putShorts(types.map(_.toShort).toArray).result
    }

    // TODO Try to detect if Matched can be used
    def apply(types: Iterable[Format]): FieldFormats = Mixed(types.toVector)

  }

  sealed trait Password {

    def encode(c: Charset): ByteString

  }

  object Password {

    case class ClearText(value: String) extends Password {
      def encode(c: Charset) =
        ByteString.newBuilder.
          putCString(value, c).
          result
    }

    case class MD5(username: String, password: String, salt: ByteString) extends Password {

      import MD5._

      // Encoded bytes must be in lower case
      def encode(c: Charset) = {
        val md = MessageDigest.getInstance("MD5")
        md.update(password.getBytes(c))
        md.update(username.getBytes(c))
        md.update(md.digest().asHex.toArray)
        md.update(salt.toArray)
        Prefix ++ md.digest().asHex ++ Suffix
      }

    }

    object MD5 {

      private val Prefix = ByteString("md5")
      private val Suffix = ByteString(NUL)

    }

  }

  // TODO Make this an inner class of ReadyForQuery object?
  sealed trait TransactionStatus

  object TransactionStatus {

    case object Idle extends TransactionStatus
    case object Open extends TransactionStatus
    case object Failed extends TransactionStatus

  }

  sealed trait ResponseFields {

    sealed trait Field

    case class Severity(level: String) extends Field
    case class SQLState(code: String) extends Field
    case class Message(text: String) extends Field
    case class Detail(text: String) extends Field
    case class Hint(text: String) extends Field
    case class Position(index: Int) extends Field
    case class InternalPosition(index: Int) extends Field
    case class InternalQuery(text: String) extends Field
    case class Where(trace: immutable.IndexedSeq[String]) extends Field
    case class Schema(name: String) extends Field
    case class Table(name: String) extends Field
    case class Column(name: String) extends Field
    case class DataType(name: String) extends Field
    case class Constraint(name: String) extends Field
    case class File(path: String) extends Field
    case class Line(index: Int) extends Field
    case class Routine(name: String) extends Field

    type Fields = immutable.Seq[Field]

    protected def decodeAll(c: Charset, b: ByteIterator): Fields =
      Iterator.continually(b.getByte).
        takeWhile(_ != NUL).
        foldLeft(Vector.empty[Field]) { (fields, typ) =>
        val value = b.getCString(c)
        (typ: @switch) match {
          case 'S' => fields :+ Severity(value)
          case 'C' => fields :+ SQLState(value)
          case 'M' => fields :+ Message(value)
          case 'D' => fields :+ Detail(value)
          case 'H' => fields :+ Hint(value)
          case 'P' => fields :+ Position(value.toInt)
          case 'p' => fields :+ InternalPosition(value.toInt)
          case 'q' => fields :+ InternalQuery(value)
          case 'W' => fields :+ Where(value.split('\n').toVector)
          case 's' => fields :+ Schema(value)
          case 't' => fields :+ Table(value)
          case 'c' => fields :+ Column(value)
          case 'd' => fields :+ DataType(value)
          case 'n' => fields :+ Constraint(value)
          case 'F' => fields :+ File(value)
          case 'L' => fields :+ Line(value.toInt)
          case 'R' => fields :+ Routine(value)
          case _ => fields // Ignore, per documentation recommendation
        }
      }

  }

  sealed abstract class DecoderException(msg: String)
    extends RuntimeException(msg) with NoStackTrace

  @SerialVersionUID(1)
  case class UnsupportedMessageType(typ: Byte)
    extends DecoderException(s"Message type ${Integer.toHexString(typ)} is not supported")

  @SerialVersionUID(1)
  case class UnsupportedAuthenticationMethod(method: Int)
    extends DecoderException(s"Authentication method $method is not supported")

  @SerialVersionUID(1)
  case class UnsupportedSSLReply(typ: Byte)
    extends DecoderException(s"SSL reply ${Integer.toHexString(typ)} is not supported")

  @SerialVersionUID(1)
  case class UnsupportedFormatType(typ: Short)
    extends DecoderException(s"Format type ${Integer.toHexString(typ)} is not supported")

  @SerialVersionUID(1)
  case class UnexpectedBinaryColumnFormat(columns: Iterable[Int])
    extends DecoderException(s"Text COPY format does not allow binary column types in columns ${columns.mkString(", ")}")

  @SerialVersionUID(1)
  case class UnsupportedTransactionStatus(typ: Byte)
    extends DecoderException(s"Transaction status ${Integer.toHexString(typ)} is not supported")

}
