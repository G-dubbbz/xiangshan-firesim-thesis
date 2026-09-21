// Configs.scala
//
// Config fragment to attach an XiangShanTile into a Chipyard subsystem,
// same shape as WithNBigCores / WithNCVA6Cores in Chipyard's own config
// fragment library. [DRAFT, not yet compiled]

package xiangshan

import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tile._

class WithNXiangShanCores(n: Int = 1) extends Config((site, here, up) => {
  case TilesLocated(InSubsystem) => {
    val prev = up(TilesLocated(InSubsystem), site)
    val idOffset = up(NumTiles)
    val xiangshanTileParams = XiangShanTileParams(tileId = idOffset)
    val crossing = RocketCrossingParams() // matches every other InSubsystem tile's default crossing;
                                           // revisit if XiangShan needs an async/rational crossing instead
    prev ++ (0 until n).map { i =>
      XiangShanTileAttachParams(
        tileParams = xiangshanTileParams.copy(
          tileId = idOffset + i,
          uniqueName = s"xiangshan_tile_${idOffset + i}"
        ),
        crossingParams = crossing
      )
    }
  }
  case NumTiles => up(NumTiles) + n
})

/** memory_* is 256 bits / 32 bytes wide — SystemBusKey.beatBytes must
  * match or a TLWidthWidget mismatch will silently narrow/pad every
  * transaction. Baseline doc flags this explicitly as "a concrete,
  * easy-to-miss parameter to get wrong" (CVA6's example config uses 8,
  * matching CVA6's narrower AXI — do not copy that value for XiangShan).
  * Compose this AFTER WithNXiangShanCores in the config chain, e.g.:
  *   class XiangShanMinimalConfig extends Config(
  *     new WithXiangShanSystemBusWidth ++
  *     new WithNXiangShanCores(1) ++
  *     new chipyard.config.AbstractConfig)
  */
class WithXiangShanSystemBusWidth extends Config((site, here, up) => {
  case SystemBusKey => up(SystemBusKey).copy(beatBytes = 32)
})
