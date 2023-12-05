package b4processor.modules.memory

import b4processor.Parameters
import b4processor.structures.memoryAccess.MemoryAccessWidth
import b4processor.utils.Tag
import chisel3._
import chisel3.util.{Decoupled, Irrevocable}

class MemoryAccessChannels(implicit params: Parameters) extends Bundle {
  val read = new MemoryReadChannel()
  val write = new MemoryWriteChannel()
}

class MemoryReadChannel(implicit params: Parameters) extends Bundle {
  val request = Decoupled(new MemoryReadRequest())
  val response = Flipped(Irrevocable(new MemoryReadResponse()))
}

class MemoryReadRequest(implicit params: Parameters) extends Bundle {
  val address = UInt(64.W)
  val burstLength = UInt(8.W)
  val size = new MemoryAccessWidth.Type()
  val signed = Bool()
  val outputTag = new Tag()
}
class MemoryReadResponse(implicit params: Parameters) extends Bundle {

  /** 値 */
  val value = Output(UInt(64.W))

  /** バーストの場合のインデックス */
  val burstIndex = Output(UInt(8.W))

  /// エラーだった
  val isError = Bool()

  /** 対応するタグ */
  val tag = Output(new Tag)
}

class MemoryWriteChannel(implicit params: Parameters) extends Bundle {
  val request = Decoupled(new MemoryWriteRequest())
  val requestData = Decoupled(new MemoryWriteRequestData())
  val response = Flipped(Irrevocable(new MemoryWriteResponse()))
}

class MemoryWriteRequest(implicit params: Parameters) extends Bundle {
  val outputTag = new Tag()
  val address = UInt(64.W)
  val burstLen = UInt(8.W)
}

class MemoryWriteRequestData(implicit params: Parameters) extends Bundle {
  val data = UInt(64.W)
  val mask = UInt(8.W)
}

class MemoryWriteResponse(implicit params: Parameters) extends Bundle {

  /** 値 */
  val value = Output(UInt(64.W))

  /** バーストの場合のインデックス */
  val burstIndex = Output(UInt(8.W))

  /// エラーだった
  val isError = Bool()

  /** 対応するタグ */
  val tag = Output(new Tag)
}

object MemoryReadRequest {
  def ReadToTag(
    address: UInt,
    size: MemoryAccessWidth.Type,
    signed: Bool,
    outputTag: Tag,
  )(implicit params: Parameters): MemoryReadRequest = {
    val w = Wire(new MemoryReadRequest)
    w.address := address
    w.burstLength := 0.U
    w.size := size
    w.signed := signed
    w.outputTag := outputTag
    w
  }

  def ReadToAmo(address: UInt, size: MemoryAccessWidth.Type, outputTag: Tag)(
    implicit params: Parameters,
  ): MemoryReadRequest = {
    val w = Wire(new MemoryReadRequest)
    w.address := address
    w.burstLength := 0.U
    w.size := size
    w.signed := true.B
    w.outputTag := outputTag
    w
  }

  def ReadToVector(baseAddress: UInt, size: MemoryAccessWidth.Type, vl: UInt, outputTag: Tag)(implicit params: Parameters): MemoryReadRequest = {
    val w = Wire(new MemoryReadRequest())
    w.address := baseAddress
    w.burstLength := vl
    w.size := MemoryAccessWidth.DoubleWord
    w.signed := true.B
    w.outputTag := outputTag
    w
  }

  def ReadInstruction(address: UInt, length: Int, threadId: UInt)(implicit
    params: Parameters,
  ): MemoryReadRequest = {
    val w = Wire(new MemoryReadRequest)
    w.address := address
    w.burstLength := (length - 1).U
    w.size := MemoryAccessWidth.DoubleWord
    w.signed := false.B
    w.outputTag := Tag(threadId, 0.U)
    w
  }
}
