package sttp.tapir.server.zhttp

import sttp.capabilities
import sttp.capabilities.zio.ZioStreams
import sttp.tapir.RawBodyType
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interpreter.{RawValue, RequestBody}
import zhttp.http.{HttpData, Request}
import zio.stream.{Stream, ZStream}
import zio.{RIO, Task}

import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class ZioHttpRequestBody[F, R](request: Request, serverRequest: ServerRequest, serverOptions: ZioHttpServerOptions[R]) extends RequestBody[RIO[F, *], ZioStreams] {
  override val streams: capabilities.Streams[ZioStreams] = ZioStreams

  def asByteArray: Task[Array[Byte]] = request.content match {
    case HttpData.Empty => Task.succeed(Array.emptyByteArray)
    case HttpData.CompleteData(data) => Task.succeed(data.toArray)
    case HttpData.StreamData(data) => data.runCollect.map(_.toArray)
  }

  override def toRaw[R](bodyType: RawBodyType[R]): Task[RawValue[R]] = bodyType match {
    case RawBodyType.StringBody(defaultCharset) => asByteArray.map(new String(_, defaultCharset)).map(RawValue(_))
    case RawBodyType.ByteArrayBody              => asByteArray.map(RawValue(_))
    case RawBodyType.ByteBufferBody             => asByteArray.map(bytes => ByteBuffer.wrap(bytes)).map(RawValue(_))
    case RawBodyType.InputStreamBody => asByteArray.map(new ByteArrayInputStream(_)).map(RawValue(_))
    case RawBodyType.FileBody => serverOptions.createFile(serverRequest).flatMap(file => Task(RawValue(file, Seq(file))))
    case RawBodyType.MultipartBody(_, _) => Task.never
  }

  val stream: Stream[Throwable, Byte] = request.content match {
    case HttpData.Empty              => ZStream.empty
    case HttpData.CompleteData(data) => ZStream.fromChunk(data)
    case HttpData.StreamData(stream) => stream
  }

  override def toStream(): streams.BinaryStream = stream.asInstanceOf[streams.BinaryStream]
}
