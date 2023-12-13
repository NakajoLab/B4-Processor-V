package b4processor

import scala.math.pow

/** プロセッサを生成する際のパラメータ
  *
  * @param tagWidth
  *   リオーダバッファで使用するタグのビット数
  * @param loadStoreQueueIndexWidth
  *   ロードストアキューに使うインデックスのビット幅
  * @param maxRegisterFileCommitCount
  *   リオーダバッファからレジスタファイルに1クロックでコミットする命令の数(Max)
  * @param maxDataMemoryCommitCount
  *   メモリバッファに出力する最大数
  * @param debug
  *   デバッグ機能を使う
  * @param fetchWidth
  *   命令フェッチ時にメモリから取出す命令数
  * @param branchPredictionWidth
  *   分岐予測で使う下位ビット数
  * @param instructionStart
  *   プログラムカウンタの初期値
  * @param xprlen
  *   整数レジスタ長
  * @param fuckVectorMechanics
  *   ベクトルに必要な全メモリアクセスのインオーダ化を破壊し，スカラメモリアクセスを高速化するか否か
  */
case class Parameters(
  tagWidth: Int = 4,
  executors: Int = 2,
  loadStoreQueueIndexWidth: Int = 3,
  loadStoreQueueCheckLength: Int = 3,
  decoderPerThread: Int = 2,
  threads: Int = 2,
  maxRegisterFileCommitCount: Int = 1,
  maxDataMemoryCommitCount: Int = 1,
  fetchWidth: Int = 2,
  branchPredictionWidth: Int = 4,
  parallelOutput: Int = 2,
  instructionStart: Long = 0x8010_0000L,
  debug: Boolean = false,
  enablePExt: Boolean = false,
  pextExecutors: Int = 1,
  xprlen: Int = 64,
  vlen: Int = 256,
  vecAluExecUnitNum: Int = 2,
  physicalVrfFor1Thread: Int = 48,
  fuckVectorMechanics: Boolean = false,
) {
  def vlenb: Int = vlen/8
  def physicalVrfEntries: Int = physicalVrfFor1Thread * threads
}
