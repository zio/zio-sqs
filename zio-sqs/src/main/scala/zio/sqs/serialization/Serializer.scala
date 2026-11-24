package zio.sqs.serialization

/**
 * A type class to convert a value of type `A` to a `String` value.
 */
trait Serializer[A] { self =>

  /**
   * Converts a value to the string that is sent as the SQS message body.
   */
  def apply(a: A): String

  /**
   * Creates a new [[Serializer]] by applying a function to value of type `B` before serializing as an `A`.
   */
  final def contramap[B](f: B => A): Serializer[B] = (b: B) => self(f(b))
}

/**
 * Built-in [[Serializer]] instances.
 */
object Serializer {

  /**
   * Returns the string unchanged, so the message body is the string itself.
   */
  final val serializeString: Serializer[String] = (a: String) => a

}
