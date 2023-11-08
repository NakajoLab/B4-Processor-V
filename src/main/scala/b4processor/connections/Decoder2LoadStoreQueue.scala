package b4processor.connections

import b4processor.Parameters
import b4processor.utils.operations._
import b4processor.utils.Tag
import chisel3._

/** デコーダとLSQをつなぐ
  *
  * @param params
  *   パラメータ
  */
class Decoder2LoadStoreQueue(implicit params: Parameters) extends Bundle {

  /** メモリアクセスの情報 */
  val operation = LoadStoreOperation()
  val operationWidth = LoadStoreWidth()

  val destinationTag = new Tag

  val addressTag = new Tag

  /** アドレス値が有効である */
  val address = UInt(64.W)

  /** アドレス値が有効である */
  val addressOffset = SInt(12.W)

  /** アドレス値が有効である */
  val addressValid = Bool()

  /** ストアに使用するデータが格納されるタグ(SourceRegister2 Tag) */
  val storeDataTag = new Tag

  /** ストアデータ */
  val storeData = UInt(64.W)

  /** ストアデータの値が有効か */
  val storeDataValid = Bool()

  /** ベクトル拡張メモリアクセス */
  val mopOperation = MopOperation()

  /** ベクトル拡張ユニットストライドメモリアクセス */
  val umopOperation = UmopOperation()
}
