package zio.sqs

package object producer {

  type ErrorOrEvent[T] = Either[ProducerError[T], ProducerEvent[T]]

}
