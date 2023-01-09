package zio.sqs

import zio.Chunk
import zio.test.{ Gen, Sized }

object Util {

  def chunkOfStringsN(n: Int): Gen[Sized, Chunk[String]] = Gen.chunkOfN(n)(Gen.string(Gen.printableChar))

}
