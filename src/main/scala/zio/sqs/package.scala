package zio

package object sqs {

  type SqsPublishErrorOrEvent = Either[SqsPublishEventError, SqsPublishEventResult]

}
