package b4processor.modules.decoder

import b4processor.Parameters
import b4processor.connections._
import b4processor.utils.{
  ALUOperation,
  CSROperation,
  DecodingMod,
  LoadStoreOperation,
  Tag
}
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage

/** デコーダ
  */
class Decoder(implicit params: Parameters) extends Module {
  val io = IO(new Bundle {
    val instructionFetch = Flipped(new Uncompresser2Decoder())
    val reorderBuffer = new Decoder2ReorderBuffer
    val outputCollector = Flipped(new CollectedOutput())
    val registerFile = new Decoder2RegisterFile()

    val reservationStation = new Decoder2ReservationStation

    val loadStoreQueue = Decoupled(new Decoder2LoadStoreQueue)
    val csr = Decoupled(new Decoder2CSRReservationStation())
    val threadId = Input(UInt(log2Up(params.threads).W))
  })

  val operations = DecodingMod(
    io.instructionFetch.bits.instruction,
    io.instructionFetch.bits.programCounter
  )

  val operationIsStore = Seq(
    LoadStoreOperation.Store8,
    LoadStoreOperation.Store16,
    LoadStoreOperation.Store32,
    LoadStoreOperation.Store64
  ).map(_ === operations.loadStoreOp).reduce(_ || _)

  // リオーダバッファへの入力
  io.reorderBuffer.source1.sourceRegister := operations.rs1
  io.reorderBuffer.source2.sourceRegister := operations.rs2
  io.reorderBuffer.destination.destinationRegister := operations.rd
  io.reorderBuffer.destination.storeSign := operationIsStore
  io.reorderBuffer.programCounter := io.instructionFetch.bits.programCounter

  // レジスタファイルへの入力
  io.registerFile.sourceRegister1 := operations.rs1
  io.registerFile.sourceRegister2 := operations.rs2

  // リオーダバッファから一致するタグを取得する
  val sourceTag1 = io.reorderBuffer.source1.matchingTag
  val sourceTag2 = io.reorderBuffer.source2.matchingTag

  // Valueの選択
  val value1 = ValueSelector.getValue(
    io.reorderBuffer.source1.value,
    io.registerFile.value1,
    io.outputCollector,
    sourceTag1
  )
  val value2 = ValueSelector.getValue(
    io.reorderBuffer.source2.value,
    io.registerFile.value2,
    io.outputCollector,
    sourceTag2
  )

  // 命令をデコードするのはリオーダバッファにエントリの空きがあり、リザベーションステーションにも空きがあるとき
  io.instructionFetch.ready := io.reservationStation.ready && io.reorderBuffer.ready && io.loadStoreQueue.ready && io.csr.ready
  // リオーダバッファやリザベーションステーションに新しいエントリを追加するのは命令がある時
  io.reorderBuffer.valid := io.instructionFetch.ready &&
    io.instructionFetch.valid &&
    (operations.aluOp =/= ALUOperation.None ||
      operations.loadStoreOp =/= LoadStoreOperation.None ||
      operations.csrOp =/= CSROperation.None)
  io.reservationStation.entry.valid := io.instructionFetch.ready &&
    io.instructionFetch.valid && operations.aluOp =/= ALUOperation.None

  // RSへの出力を埋める
  val rs = io.reservationStation.entry
  rs.operation := operations.aluOp
  rs.destinationTag := io.reorderBuffer.destination.destinationTag
  rs.sourceTag1 := Mux(
    value1.valid || operations.rs1ValueValid,
    Tag(io.threadId, 0.U),
    sourceTag1.bits
  )
  rs.sourceTag2 := Mux(
    value2.valid || operations.rs2ValueValid,
    Tag(io.threadId, 0.U),
    sourceTag2.bits
  )
  rs.ready1 := value1.valid || operations.rs1ValueValid
  rs.ready2 := value2.valid || operations.rs2ValueValid
  rs.value1 := MuxCase(
    0.U,
    Seq(
      operations.rs1ValueValid -> operations.rs1Value,
      value1.valid -> value1.bits
    )
  )
  rs.value2 := MuxCase(
    0.U,
    Seq(
      operations.rs2ValueValid -> operations.rs2Value,
      value2.valid -> value2.bits
    )
  )
  rs.branchOffset := operations.branchOffset
  rs.wasCompressed := io.instructionFetch.bits.wasCompressed

  // load or store命令の場合，LSQへ発送
  io.loadStoreQueue.valid := io.loadStoreQueue.ready && io.instructionFetch.ready && io.instructionFetch.valid && operations.loadStoreOp =/= LoadStoreOperation.None

  when(io.loadStoreQueue.valid) {
    io.loadStoreQueue.bits.operation := operations.loadStoreOp
    io.loadStoreQueue.bits.addressAndLoadResultTag := rs.destinationTag
    io.loadStoreQueue.bits.addressValid := false.B
    io.loadStoreQueue.bits.address := 0.U
    io.loadStoreQueue.bits.addressOffset := operations.rs2Value.asSInt
    when(operationIsStore) {
      io.loadStoreQueue.bits.storeDataTag := sourceTag2.bits
      io.loadStoreQueue.bits.storeData := value2.bits
      io.loadStoreQueue.bits.storeDataValid := value2.valid
    }.otherwise {
      io.loadStoreQueue.bits.storeDataTag := Tag(io.threadId, 0.U)
      io.loadStoreQueue.bits.storeData := 0.U
      io.loadStoreQueue.bits.storeDataValid := true.B
    }
  }.otherwise {
    io.loadStoreQueue.bits := DontCare
  }

  io.csr.valid := io.csr.ready && io.instructionFetch.ready && io.instructionFetch.valid & operations.csrOp =/= CSROperation.None
  io.csr.bits := DontCare
  io.csr.bits.operation := operations.csrOp
  io.csr.bits.address := operations.rs2Value
  when(io.csr.valid) {
//    printf(p"decoder out ${operations.csrOp}\n")
    io.csr.bits.sourceTag := sourceTag1.bits
    io.csr.bits.value := MuxCase(
      0.U,
      Seq(
        operations.rs1ValueValid -> operations.rs1Value,
        value1.valid -> value1.bits
      )
    )
    io.csr.bits.ready := operations.rs1ValueValid || value1.valid
    io.csr.bits.destinationTag := io.reorderBuffer.destination.destinationTag
  }
}

object Decoder extends App {
  implicit val params = Parameters()
  ChiselStage.emitSystemVerilogFile(new Decoder)
}
