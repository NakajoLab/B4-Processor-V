package b4processor.modules.csr

import b4processor.Parameters
import b4processor.connections._
import chisel3._
import chisel3.util._
import _root_.circt.stage.ChiselStage
import b4processor.modules.vector._
import b4processor.riscv.CSRs
import b4processor.utils.FormalTools
import b4processor.utils.operations.CSROperation
import chisel3.experimental.BundleLiterals._

class CSR(implicit params: Parameters) extends Module with FormalTools {
  val io = IO(new Bundle {
    val decoderInput = Flipped(Decoupled(new CSRReservationStation2CSR))
    val CSROutput = Irrevocable(new OutputValue)
    val fetch = Output(new CSR2Fetch)
    val reorderBuffer = Flipped(new ReorderBuffer2CSR)
    val threadId = Input(UInt(log2Up(params.threads).W))
    val vCsrOutput = Output(new VCsrBundle())
  })

  private val operation = io.decoderInput.bits.operation

  io.decoderInput.ready := io.CSROutput.ready
  io.CSROutput.valid := false.B
  io.CSROutput.bits.tag := io.decoderInput.bits.destinationTag
  io.CSROutput.bits.value := 0.U
  io.CSROutput.bits.isError := false.B

  val retireCounter = Module(new RetireCounter)
  retireCounter.io.retireInCycle := io.reorderBuffer.retireCount
  val cycleCounter = Module(new CycleCounter)

  val mtvec = RegInit(0.U(64.W))
  io.fetch.mtvec := mtvec
  val mepc = RegInit(0.U(64.W))
  io.fetch.mepc := mepc
  val mcause = RegInit(0.U(64.W))
  io.fetch.mcause := mcause
  val mstatus = RegInit(0.U(64.W))
  val mie = RegInit(0.U(64.W))
  val vtype = RegInit(new VtypeBundle().Lit(
    _.vill -> true.B,
    _.vma -> false.B,
    _.vta -> false.B,
    _.vsew -> 0.U,
    _.vlmul -> 0.U
  ))
  val vl = RegInit(0.U((log2Up(params.vlenb)+1).W))
  io.vCsrOutput.vtype := vtype
  io.vCsrOutput.vl := vl

  def setCSROutput(reg: UInt): Unit = {
    io.CSROutput.bits.value := reg
    when(io.CSROutput.ready && io.CSROutput.valid) {
      reg := MuxLookup(operation, 0.U)(
        Seq(
          CSROperation.ReadWrite -> io.decoderInput.bits.value,
          CSROperation.ReadSet -> (reg | io.decoderInput.bits.value),
          CSROperation.ReadClear -> (reg & io.decoderInput.bits.value),
        ),
      )
    }
  }

  when(io.decoderInput.valid) {
//    printf(p"csr in ${operation}\n")
    val address = io.decoderInput.bits.address
//    printf(p"csr address ${address}\n")

    io.CSROutput.valid := true.B

    when(address === CSRs.cycle.U || address === CSRs.mcycle.U) {
      io.CSROutput.bits.value := cycleCounter.count
    }.elsewhen(address === CSRs.instret.U || address === CSRs.minstret.U) {
      io.CSROutput.bits.value := retireCounter.io.count
    }.elsewhen(address === CSRs.mhartid.U) {
      io.CSROutput.bits.value := io.threadId
    }.elsewhen(address === CSRs.mtvec.U) {
      setCSROutput(mtvec)
    }.elsewhen(address === CSRs.mepc.U) {
      setCSROutput(mepc)
    }.elsewhen(address === CSRs.mcause.U) {
      setCSROutput(mcause)
    }.elsewhen(address === CSRs.mstatus.U) {
      setCSROutput(mstatus)
    }.elsewhen(address === CSRs.mie.U) {
      setCSROutput(mie)
    }.elsewhen(address === CSRs.vtype.U) {
      io.CSROutput.bits.value := vtype.getBits
    }.elsewhen(address === CSRs.vl.U) {
      io.CSROutput.bits.value := vl
    }.elsewhen(io.decoderInput.bits.operation === CSROperation.SetVl) {
      val avl = io.decoderInput.bits.value
      val vtypei = 0.U(52.W) ## io.decoderInput.bits.address
      val vtypeBits = Wire(new VtypeBundle())
      vtypeBits.setBits(vtypei)
      val maxVl = MuxLookup(vtypeBits.vsew, 0.U)(
        (0 until 4).map(i => i.U(3.W) -> (params.vlen >> (3 + i)).U)
      )
      vtype := vtypeBits
      val nextVl = Mux(avl >= maxVl, maxVl, avl)
      vl := nextVl
      io.CSROutput.bits.value := nextVl

      io.vCsrOutput.vtype := vtypeBits
      io.vCsrOutput.vl := nextVl
    }.otherwise {
      io.CSROutput.bits.isError := true.B
    }
  }

  when(io.reorderBuffer.mcause.valid) {
    mcause := io.reorderBuffer.mcause.bits
  }
  when(io.reorderBuffer.mepc.valid) {
    mepc := io.reorderBuffer.mepc.bits
  }
  io.CSROutput.bits.tag.threadId := io.threadId

  when(io.decoderInput.valid) {
    assume(io.decoderInput.bits.destinationTag.threadId === io.threadId)
  }
  when(io.CSROutput.valid) {
    assert(io.CSROutput.bits.tag.threadId === io.threadId)
  }
  takesEveryValue(io.CSROutput.valid)
}

object CSR extends App {
  implicit val params = Parameters()
  ChiselStage.emitSystemVerilogFile(new CSR())
}
