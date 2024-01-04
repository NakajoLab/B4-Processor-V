package b4processor.connections

import b4processor.Parameters
import b4processor.utils.Tag
import b4processor.utils.operations._
import chisel3._

class ReservationStation2VExtExecutor(implicit params: Parameters) extends Bundle {
  val destinationTag = new Tag
  val destVecReg = UInt(5.W)
  val srcVecReg1 = UInt(5.W)
  val srcVecReg2 = UInt(5.W)
  val scalarVal = UInt(params.xprlen.W)
  val vecOperation = VectorOperation()
  val vecOperand = VectorOperands()
}
