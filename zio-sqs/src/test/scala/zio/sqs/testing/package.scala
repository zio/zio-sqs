package zio.sqs

import zio.test._
import zio._

package object testing {

  val withFastClock =
    Live.withLive(TestClock.adjust(1.seconds))(_.repeat(Schedule.spaced(10.millis)))

  def chunkOfStringsN(n: Int): Gen[Sized, Chunk[String]] = Gen.chunkOfN(n)(Gen.string(Gen.printableChar))

}
