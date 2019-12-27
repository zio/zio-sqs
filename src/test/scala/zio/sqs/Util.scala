package zio.sqs

import zio.random.Random
import zio.test.{ Gen, Sized }

object Util {

  def listOfStringsN(n: Int): Gen[Random with Sized, List[String]] = Gen.listOfN(n)(Gen.string(Gen.printableChar))

}
