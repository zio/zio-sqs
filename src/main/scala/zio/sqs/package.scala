package zio

package object sqs {

  type SqsPublishErrorOrResult[T] = Either[SqsPublishEventError[T], SqsPublishEvent[T]]

}
