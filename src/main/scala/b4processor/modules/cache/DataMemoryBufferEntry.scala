package b4processor.modules.cache

import b4processor.Parameters
import b4processor.utils.operations._
import b4processor.utils.Tag
import chisel3._
import chisel3.util._

class DataMemoryBufferEntry(implicit params: Parameters) extends Bundle {

  /** アドレス値 */
  val address = UInt(64.W)

  /** 命令を識別するためのタグ(Destination Tag) */
  val tag = new Tag

  /** ストアデータ */
  val data = UInt(64.W)

  /** メモリアクセスの情報 */
  val operation = LoadStoreOperation()
  val operationWidth = LoadStoreWidth()

  /** ベクトルディスティネーションレジスタ */
  val destVecReg = Valid(UInt(5.W))

  /** ベクトル拡張メモリアクセス */
  val mopOperation = MopOperation()

  /** ベクトル拡張ユニットストライドメモリアクセス */
  val umopOperation = UmopOperation()
}

object DataMemoryBufferEntry {
  def validEntry(
    address: UInt,
    tag: Tag,
    data: UInt,
    operation: LoadStoreOperation.Type,
    operationWidth: LoadStoreWidth.Type,
  )(implicit params: Parameters): DataMemoryBufferEntry = {
    val entry = DataMemoryBufferEntry.default
    entry.address := address
    entry.tag := tag
    entry.data := data
    entry.operation := operation
    entry.operationWidth := operationWidth

    entry
  }

  def default(implicit params: Parameters): DataMemoryBufferEntry = {
    val entry = Wire(new DataMemoryBufferEntry)
    entry := DontCare

    entry
  }
}
