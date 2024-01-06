package b4processor.modules.reservationstation

import b4processor.Parameters
import b4processor.connections.{CollectedOutput, Decoder2ReservationStation, LoadStoreQueue2ReorderBuffer, ReservationStation2Executor, ReservationStation2PExtExecutor, ReservationStation2VExtExecutor}
import b4processor.utils.operations.VectorOperands
import b4processor.utils.{FormalTools, MMArbiter}
import chisel3._
import chisel3.experimental.prefix
import circt.stage.ChiselStage
import chisel3.util._

class ReservationStationWithVector(implicit params: Parameters)
  extends Module with FormalTools {
  val io = IO(new Bundle {
    val collectedOutput = Flipped(new CollectedOutput)
    val issue = Irrevocable(new ReservationStation2Executor)
    val pextIssue = Irrevocable(new ReservationStation2PExtExecutor())
    val vExtIssue = Irrevocable(new ReservationStation2VExtExecutor())
    val decoder = Flipped(new Decoder2ReservationStation)
    val threadId = Input(UInt(log2Up(params.threads).W))
    val reorderBuffer = Flipped(
      Vec(
        params.maxRegisterFileCommitCount,
        Valid(new LoadStoreQueue2ReorderBuffer),
      )
    )
  })

  val rsWidth = 3

  val reservation = RegInit(
    VecInit(
      Seq.fill(math.pow(2, rsWidth).toInt)(ReservationStationEntry.default),
    ),
  )

  private val outputArbiter = Module(
    new Arbiter(new ReservationStation2Executor, reservation.length),
  )

  private val pextOutputArbiter = Module(
    new Arbiter(new ReservationStation2PExtExecutor(), reservation.length),
  )

  private val vExtOutputArbiter = Module(
    new Arbiter(new ReservationStation2VExtExecutor(), reservation.length),
  )

  for(rb <- io.reorderBuffer) {
    when(rb.valid) {
      for(rs <- reservation) {
        when(rs.valid && (rb.bits.destinationTag === rs.destinationTag)) {
          rs.readyReorderSign := true.B
        }
      }
    }
  }

  for (i <- 0 until reservation.length) {
    prefix(s"issue${i % params.decoderPerThread}_resv$i") {
      val a = outputArbiter.io.in(i)
      val r = reservation(i)
      a.bits.operation := r.operation
      a.bits.destinationTag := r.destinationTag
      a.bits.value1 := r.sources(0).getValueUnsafe
      a.bits.value2 := r.sources(1).getValueUnsafe
      a.bits.wasCompressed := r.wasCompressed
      a.bits.branchOffset := r.branchOffset
      a.valid := r.valid &&
        r.sources(0).isValue &&
        r.sources(1).isValue &&
        !r.ispext && !r.isVext
      when(a.valid && a.ready) {
        r := ReservationStationEntry.default
      }
    }

  }
  io.issue <> outputArbiter.io.out

  for (i <- 0 until reservation.length) {
    prefix(s"issue${i % params.decoderPerThread}_resv$i") {
      val a = pextOutputArbiter.io.in(i)
      val r = reservation(i)
      a.bits.operation := r.pextOperation
      a.bits.destinationTag := r.destinationTag
      a.bits.value1 := r.sources(0).getValueUnsafe
      a.bits.value2 := r.sources(1).getValueUnsafe
      a.bits.value3 := r.sources(2).getValueUnsafe
      a.valid := r.valid &&
        r.sources(0).isValue &&
        r.sources(1).isValue &&
        r.sources(2).isValue &&
        r.ispext && !r.isVext
      when(a.valid && a.ready) {
        r := ReservationStationEntry.default
      }
    }

  }
  io.pextIssue <> pextOutputArbiter.io.out

  for(i <- reservation.indices) {
    prefix(s"issue${i % params.decoderPerThread}_resv$i") {
      val a = vExtOutputArbiter.io.in(i)
      val r = reservation(i)
      a.bits.destinationTag := r.destinationTag
      a.bits.destVecReg := r.destVecReg
      a.bits.srcVecReg1 := r.srcVecReg1
      a.bits.srcVecReg2 := r.srcVecReg2
      // ベクトルではrs1の部分がスカラ値
      a.bits.scalarVal := r.sources(0).getValueUnsafe
      a.bits.vecOperation := r.vecOperation
      a.bits.vecOperand := r.vecOperand
      a.valid := r.valid && r.readyReorderSign &&
        (!VectorOperands.readIntegerScalar(r.vecOperand) || r.sources(1).isValue) &&
        !r.ispext && r.isVext
      when(a.valid && a.ready) {
        r := ReservationStationEntry.default
      }
    }
  }
  io.vExtIssue <> vExtOutputArbiter.io.out

  // デコーダから
  private val head = RegInit(0.U(rsWidth.W))

  val decoder = io.decoder
  val resNext = reservation(head)
  decoder.ready := false.B
  when(!resNext.valid) {
    decoder.ready := true.B
    when(decoder.entry.valid) {
      resNext := decoder.entry
      when(resNext.sources(0).isTag) {
        assert(resNext.sources(0).getTagUnsafe.threadId === io.threadId)
      }
      when(resNext.sources(1).isTag) {
        assert(resNext.sources(1).getTagUnsafe.threadId === io.threadId)
      }
      when(resNext.sources(2).isTag) {
        assert(resNext.sources(2).getTagUnsafe.threadId === io.threadId)
      }
      head := head + 1.U
    }
  }

  val c = RegInit(0.U(64.W))
  c := c + 1.U

  for ((o, i) <- io.collectedOutput.outputs.zipWithIndex) {
    prefix(s"out$i") {
      when(o.valid) {
        for ((entry, ei) <- reservation.zipWithIndex) {
          when(entry.valid) {
            for (source <- entry.sources) {
              when(source.isTag) {
                assert(
                  source.getTagUnsafe.threadId === io.threadId,
                  p"tid was ${source.getTagUnsafe.threadId} should be ${io.threadId} on $ei on ${c}",
                )
                assert(o.bits.tag.threadId === io.threadId)
              }
              when(source.isTag && source.getTagUnsafe === o.bits.tag) {
                source := source.fromValue(o.bits.value)
              }
            }
          }
        }
      }

    }
  }
}