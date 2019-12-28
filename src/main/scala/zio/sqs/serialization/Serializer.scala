package zio.sqs.serialization

/**
 * A type class to convert a value of type `A` to a [[String]] value.
 */
trait Serializer[A] { self =>

  def apply(a: A): String

  /**
   * Creates a new [[Serializer]] by applying a function to value of type `B` before serializing as an `A`.
   */
  final def contramap[B](f: B => A): Serializer[B] = (b: B) => self(f(b))
}

object Serializer {

  final val serializeString: Serializer[String] = (a: String) => a

}
