package com.gu.mediaservice.lib.metrics

import scala.collection.JavaConverters._
import org.slf4j.LoggerFactory

import com.amazonaws.auth.AWSCredentials
import com.amazonaws.services.cloudwatch.{AmazonCloudWatch, AmazonCloudWatchClient}
import com.amazonaws.services.cloudwatch.model.{Dimension, StandardUnit, MetricDatum, PutMetricDataRequest}

import scalaz.concurrent.Task
import scalaz.stream.async, async.mutable.Topic
import scalaz.stream.Process.{Sink, constant, emitAll}
import scalaz.syntax.id._


trait Metric[A] {

  def recordOne(a: A): Unit

  def recordMany(as: Seq[A]): Unit

}

abstract class CloudWatchMetrics(namespace: String, credentials: AWSCredentials) {

  /** The number of data points to report in one batch
    * (Each batch costs 1 HTTP request to CloudWatch)
    */
  val chunkSize: Int = 10

  class CountMetric(name: String, dimensions: Seq[(String, String)] = Nil)
    extends CloudWatchMetric[Long](name, dimensions) {

    protected def toDatum(a: Long) = datum(StandardUnit.Count, a)

    def increment(): Unit = recordOne(1)
  }

  class TimeMetric(name: String, dimensions: Seq[(String, String)] = Nil)
    extends CloudWatchMetric[Long](name, dimensions) {

    protected def toDatum(a: Long) = datum(StandardUnit.Milliseconds, a)
  }

  private lazy val logger = LoggerFactory.getLogger(getClass)

  private val topic: Topic[MetricDatum] = async.topic[MetricDatum]

  private val sink: Sink[Task, Seq[MetricDatum]] = constant { data =>
    putData(data).handle { case e: RuntimeException => logger.error(s"Error while publishing metrics", e) }
  }

  private val client: AmazonCloudWatch =
    new AmazonCloudWatchClient(credentials) <| (_ setEndpoint "monitoring.eu-west-1.amazonaws.com")

  private def putData(data: Seq[MetricDatum]): Task[Unit] = Task {
    client.putMetricData(new PutMetricDataRequest()
      .withNamespace(namespace)
      .withMetricData(data.asJava))
  }

  import scalaz.{\/, -\/, \/-}
  private val loggingErrors: Throwable \/ Unit => Unit = {
    case -\/(e) => logger.error(s"Error while publishing metrics", e)
    case \/-(_) =>
  }

  abstract class CloudWatchMetric[A](val name: String, val dimensions: Seq[(String, String)] = Nil)
    extends Metric[A] {

    final def recordOne(a: A): Unit =
      topic.publishOne(toDatum(a).withTimestamp(new java.util.Date)).runAsync(loggingErrors)

    final def recordMany(as: Seq[A]): Unit =
      emitAll(as map (a => toDatum(a).withTimestamp(new java.util.Date)))
        .toSource.to(topic.publish).run.runAsync(loggingErrors)

    /** Must be implemented to provide a way to turn an `A` into a `MetricDatum` */
    protected def toDatum(a: A): MetricDatum

    protected val _dimensions: java.util.Collection[Dimension] =
      dimensions.map { case (k, v) => new Dimension().withName(k).withValue(v) }.asJava

    /** Convenience method for instantiating a `MetricDatum` with this metric's `name` and `dimension` */
    protected def datum(unit: StandardUnit, value: Double): MetricDatum =
      new MetricDatum()
        .withMetricName(name)
        .withUnit(unit)
        .withValue(value)
        .withDimensions(_dimensions)

  }

  /** Subscribe the metric publishing sink to the topic */
  topic.subscribe.chunk(chunkSize).to(sink).run.runAsync(loggingErrors)

}