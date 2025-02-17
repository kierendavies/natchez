// Copyright (c) 2019-2020 by Rob Norris and Contributors
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package natchez
package opentelemetry

import cats.data.Nested
import cats.effect.{Resource, Sync}
import cats.effect.kernel.Resource.ExitCase
import cats.effect.kernel.Resource.ExitCase.{Canceled, Errored, Succeeded}
import cats.syntax.all._
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.propagation.{TextMapGetter, TextMapSetter}
import io.opentelemetry.context.Context

import java.lang
import io.opentelemetry.api.trace.{Tracer, Span => TSpan}
import io.opentelemetry.api.{OpenTelemetry => OTel}
import TraceValue.{BooleanValue, NumberValue, StringValue}

import java.net.URI
import scala.collection.mutable
import io.opentelemetry.api.common.Attributes

private[opentelemetry] final case class OpenTelemetrySpan[F[_]: Sync](
    otel: OTel,
    tracer: Tracer,
    span: TSpan,
    prefix: Option[URI]
) extends Span[F] {

  import OpenTelemetrySpan._

  override def put(fields: (String, TraceValue)*): F[Unit] =
    Sync[F].delay(span.setAllAttributes(fieldsToAttributes(fields: _*))).void

  private def fieldsToAttributes(fields: (String, TraceValue)*): Attributes = {
    val bldr = Attributes.builder()
    fields.foreach {
      case (k, StringValue(v)) =>
        val safeString = if (v == null) "null" else v
        bldr.put(k, safeString)
      // all integer types are cast up to Long, since that's all OpenTelemetry lets us use
      case (k, NumberValue(n: java.lang.Byte))    => bldr.put(k, n.toLong)
      case (k, NumberValue(n: java.lang.Short))   => bldr.put(k, n.toLong)
      case (k, NumberValue(n: java.lang.Integer)) => bldr.put(k, n.toLong)
      case (k, NumberValue(n: java.lang.Long))    => bldr.put(k, n)
      // and all float types are changed to Double
      case (k, NumberValue(n: java.lang.Float))  => bldr.put(k, n.toDouble)
      case (k, NumberValue(n: java.lang.Double)) => bldr.put(k, n)
      // anything which could be too big to put in a Long is converted to a String
      case (k, NumberValue(n: java.math.BigDecimal)) => bldr.put(k, n.toString)
      case (k, NumberValue(n: java.math.BigInteger)) => bldr.put(k, n.toString)
      case (k, NumberValue(n: BigDecimal))           => bldr.put(k, n.toString)
      case (k, NumberValue(n: BigInt))               => bldr.put(k, n.toString)
      // and any other Number can fall back to a Double
      case (k, NumberValue(v))  => bldr.put(k, v.doubleValue())
      case (k, BooleanValue(v)) => bldr.put(k, v)
    }
    bldr.build()
  }

  override def kernel: F[Kernel] =
    Sync[F].delay {
      val headers: mutable.Map[String, String] = mutable.Map.empty[String, String]
      otel.getPropagators.getTextMapPropagator.inject(
        Context.current().`with`(span),
        headers,
        spanContextSetter
      )
      Kernel(headers.toMap)
    }

  override def attachError(err: Throwable): F[Unit] =
    put("error.message" -> err.getMessage, "error.class" -> err.getClass.getSimpleName) *>
      Sync[F].delay(span.recordException(err)).void

  override def log(fields: (String, TraceValue)*): F[Unit] =
    Sync[F].delay(span.addEvent("event", fieldsToAttributes(fields: _*))).void

  override def log(event: String): F[Unit] =
    Sync[F].delay(span.addEvent(event)).void

  override def span(name: String, kernel: Kernel): Resource[F, Span[F]] = Span.putErrorFields(
    Resource
      .makeCase(OpenTelemetrySpan.fromKernelWithSpan(otel, tracer, name, kernel, span, prefix))(
        OpenTelemetrySpan.finish
      )
      .widen
  )

  override def span(name: String): Resource[F, Span[F]] =
    Span.putErrorFields(
      Resource.makeCase(OpenTelemetrySpan.child(this, name))(OpenTelemetrySpan.finish).widen
    )

  override def spanId: F[Option[String]] =
    Sync[F].pure {
      val rawId = span.getSpanContext.getSpanId
      if (rawId.nonEmpty) rawId.some else none
    }

  override def traceId: F[Option[String]] =
    Sync[F].pure {
      val rawId = span.getSpanContext.getTraceId
      if (rawId.nonEmpty) rawId.some else none
    }

  override def traceUri: F[Option[URI]] =
    (Nested(prefix.pure[F]), Nested(traceId)).mapN { (uri, id) =>
      uri.resolve(s"/trace/$id")
    }.value
}

private[opentelemetry] object OpenTelemetrySpan {
  def finish[F[_]: Sync]: (OpenTelemetrySpan[F], ExitCase) => F[Unit] = { (outer, exitCase) =>
    for {
      // collect error details, if any
      _ <-
        exitCase.some
          .collect { case Errored(t: Fields) =>
            t.fields.toList
          }
          .traverse(outer.put)
      _ <- Sync[F].delay {
        exitCase match {
          case Succeeded => outer.span.setStatus(StatusCode.OK)
          case Canceled  => outer.span.setStatus(StatusCode.UNSET)
          case Errored(ex) =>
            outer.span.setStatus(StatusCode.ERROR, ex.getMessage)
            outer.span.recordException(ex)
        }
      }
      _ <- Sync[F].delay(outer.span.end())
    } yield ()
  }

  def child[F[_]: Sync](
      parent: OpenTelemetrySpan[F],
      name: String
  ): F[OpenTelemetrySpan[F]] =
    Sync[F]
      .delay(
        parent.tracer
          .spanBuilder(name)
          .setParent(Context.current().`with`(parent.span))
          .startSpan()
      )
      .map(OpenTelemetrySpan(parent.otel, parent.tracer, _, parent.prefix))

  def root[F[_]: Sync](
      otel: OTel,
      tracer: Tracer,
      prefix: Option[URI],
      name: String
  ): F[OpenTelemetrySpan[F]] =
    Sync[F]
      .delay(
        tracer
          .spanBuilder(name)
          .startSpan()
      )
      .map(OpenTelemetrySpan(otel, tracer, _, prefix))

  def fromKernelWithSpan[F[_]: Sync](
      sdk: OTel,
      tracer: Tracer,
      name: String,
      kernel: Kernel,
      span: TSpan,
      prefix: Option[URI]
  ): F[OpenTelemetrySpan[F]] = Sync[F]
    .delay {
      val ctx = sdk.getPropagators.getTextMapPropagator
        .extract(Context.current(), kernel, spanContextGetter)
      tracer.spanBuilder(name).setParent(ctx).addLink(span.getSpanContext).startSpan
    }
    .map(OpenTelemetrySpan(sdk, tracer, _, prefix))

  def fromKernel[F[_]: Sync](
      otel: OTel,
      tracer: Tracer,
      prefix: Option[URI],
      name: String,
      kernel: Kernel
  ): F[OpenTelemetrySpan[F]] =
    Sync[F]
      .delay {
        val ctx = otel.getPropagators.getTextMapPropagator
          .extract(Context.current(), kernel, spanContextGetter)
        tracer.spanBuilder(name).setParent(ctx).startSpan()
      }
      .map(OpenTelemetrySpan(otel, tracer, _, prefix))

  def fromKernelOrElseRoot[F[_]](
      otel: OTel,
      tracer: Tracer,
      prefix: Option[URI],
      name: String,
      kernel: Kernel
  )(implicit ev: Sync[F]): F[OpenTelemetrySpan[F]] =
    fromKernel(otel, tracer, prefix, name, kernel).recoverWith {
      case _: NoSuchElementException =>
        root(otel, tracer, prefix, name) // means headers are incomplete or invalid
      case _: NullPointerException =>
        root(otel, tracer, prefix, name) // means headers are incomplete or invalid
    }

  private val spanContextGetter: TextMapGetter[Kernel] = new TextMapGetter[Kernel] {

    import scala.jdk.CollectionConverters._

    override def keys(carrier: Kernel): lang.Iterable[String] = carrier.toHeaders.keys.asJava

    override def get(carrier: Kernel, key: String): String =
      carrier.toHeaders(key)
  }

  private val spanContextSetter = new TextMapSetter[mutable.Map[String, String]] {
    override def set(carrier: mutable.Map[String, String], key: String, value: String): Unit = {
      carrier.put(key, value)
      ()
    }
  }
}
