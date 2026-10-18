package it.ayngallery
import org.junit.Test
import org.junit.Assert.*
class GifAnimationBudgetTest {
 @Test fun countAndEstimatedMemoryBothBoundAnimations() {
  val small = (0 until 40).map { "gif$it" to 4096L }
  assertEquals(40, animatedGifIds(small, 40, 512L * 1024 * 1024).size)
  assertEquals(4, animatedGifIds(small, 4, 512L * 1024 * 1024).size)
  assertTrue(animatedGifIds((0 until 40).map { "$it" to 1000000L }, 40, 512L * 1024 * 1024).size < 4)
  assertTrue(animatedGifIds(listOf("huge" to Long.MAX_VALUE), 40).isEmpty())
  assertEquals(1, animatedGifIds(listOf("same" to 4096L, "same" to 4096L), 40).size)
 }
}
