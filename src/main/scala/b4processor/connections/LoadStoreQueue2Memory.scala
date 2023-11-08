package b4processor.connections

import b4processor.Parameters
import b4processor.utils.operations._
import b4processor.utils.Tag
import chisel3._

/** LSQとメモリをつなぐ
  */
class LoadStoreQueue2Memory(implicit params: Parameters) extends Bundle {
  val address = UInt(64.W)
  val tag = new Tag()
  val data = UInt(64.W)
  val operation = LoadStoreOperation()
  val operationWidth = LoadStoreWidth()

  /** ベクトル拡張メモリアクセス */
  val mopOperation = MopOperation()

  /** ベクトル拡張ユニットストライドメモリアクセス */
  val umopOperation = UmopOperation()
}
