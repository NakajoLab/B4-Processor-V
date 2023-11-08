package b4processor.modules.inOrderLdstUnit

import b4processor.Parameters
import b4processor.connections.{CollectedOutput, Decoder2LoadStoreQueue, LoadStoreQueue2Memory, LoadStoreQueue2ReorderBuffer}
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import b4processor.modules.lsq.LoadStoreQueueEntry
import b4processor.utils.{FormalTools, Tag}
import b4processor.utils.operations.LoadStoreOperation

class InOrderLdstUnit(implicit params: Parameters) extends Module with FormalTools {
  val io = IO(new Bundle {
    val decoders =
      Vec(
        params.decoderPerThread,
        Flipped(Decoupled(new Decoder2LoadStoreQueue())),
      )
    val outputCollector = Flipped(new CollectedOutput)
    // val vCsrOutput = Input(new VCsrBundle())
    val reorderBuffer = Flipped(
      Vec(
        params.maxRegisterFileCommitCount,
        Valid(new LoadStoreQueue2ReorderBuffer),
      ),
    )
    val memory = Decoupled(new LoadStoreQueue2Memory)
    val empty = Output(Bool())
    val full = Output(Bool())

    val head =
      if (params.debug) Some(Output(UInt(params.loadStoreQueueIndexWidth.W)))
      else None
    val tail =
      if (params.debug) Some(Output(UInt(params.loadStoreQueueIndexWidth.W)))
      else None
    // LSQのエントリ数はこのままでいいのか
  })
  val defaultEntry = LoadStoreQueueEntry.default

  val head = RegInit(0.U(params.loadStoreQueueIndexWidth.W))
  val tail = RegInit(0.U(params.loadStoreQueueIndexWidth.W))
  io.empty := head === tail
  io.full := head + 1.U === tail

  val buffer = RegInit(
    VecInit(
      Seq.fill(math.pow(2, params.loadStoreQueueIndexWidth).toInt)(defaultEntry),
    ),
  )

  // デコード結果がAMO以外のメモリアクセス命令ならばバッファに追加
  var insertIndex = head

  for((dec, i) <- io.decoders.zipWithIndex) {
    dec.ready := (tail =/= (insertIndex + 1.U))
    when(dec.ready && dec.valid) {
      buffer(insertIndex) := LoadStoreQueueEntry.validEntry(
        operation = dec.bits.operation,
        operationWidth = dec.bits.operationWidth,
        destinationTag = dec.bits.destinationTag,
        address = dec.bits.address,
        addressValid = dec.bits.addressValid,
        addressOffset = dec.bits.addressOffset,
        addressTag = dec.bits.addressTag,
        storeDataTag = dec.bits.storeDataTag,
        storeData = dec.bits.storeData,
        storeDataValid = dec.bits.storeDataValid,
        mOpOperation = dec.bits.mopOperation,
        umopOperation = dec.bits.umopOperation,
      )
      insertIndex = insertIndex + 1.U
    }
  }

  head := insertIndex
}
