package zio.sqs

package object producer {

  /**
   * Specifies the result of publishing en event.
   * The result either an error [[zio.sqs.producer.ProducerError]] or the event itself [[zio.sqs.producer.ProducerEvent]]
   * @tparam T type of the event to publish
   */
  type ErrorOrEvent[T] = Either[ProducerError[T], ProducerEvent[T]]

}
