package b4processor.modules.executor

import b4processor.Parameters
import b4processor.connections.Executor2Fetch
import b4processor.utils.{ALUValue, FetchValue, LSQValue, ReservationValue}
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec
import chisel3._

class ExecutorWrapper(implicit params: Parameters) extends Module {
  val io = IO(new Bundle {
    val reservationstation = Flipped(new ReservationStation2ExecutorForTest)
    val out = new ExecutionRegisterBypassForTest
    val loadstorequeue = Output(new Executor2LoadStoreQueueForTest)
    val fetch = Output(new Executor2Fetch)
  })

  val executor = Module(new Executor)
  executor.io.reservationstation.bits.destinationTag := io.reservationstation.bits.destinationTag
  executor.io.reservationstation.bits.value1 := io.reservationstation.bits.value1.asUInt
  executor.io.reservationstation.bits.value2 := io.reservationstation.bits.value2.asUInt
  executor.io.reservationstation.bits.function3 := io.reservationstation.bits.function3
  executor.io.reservationstation.bits.immediateOrFunction7 := io.reservationstation.bits.immediateOrFunction7
  executor.io.reservationstation.bits.opcode := io.reservationstation.bits.opcode
  executor.io.reservationstation.bits.programCounter := io.reservationstation.bits.programCounter
  executor.io.reservationstation.valid := io.reservationstation.valid
  io.reservationstation.ready := executor.io.reservationstation.ready

  io.out.value := executor.io.out.value.asSInt
  io.out.valid := executor.io.out.valid
  io.out.destinationTag := executor.io.out.destinationTag

  io.loadstorequeue.value := executor.io.loadstorequeue.value.asSInt
  io.loadstorequeue.valid := executor.io.loadstorequeue.valid
  io.loadstorequeue.programCounter := executor.io.loadstorequeue.programCounter
  io.loadstorequeue.destinationTag := executor.io.loadstorequeue.destinationTag

  executor.io.fetch <> io.fetch

  def setALU(values: ReservationValue): Unit = {
    val reservationstation = this.io.reservationstation
    reservationstation.valid.poke(values.valid)
    reservationstation.bits.destinationTag.poke(values.destinationTag)

    /** マイナスの表現ができていない */

    reservationstation.bits.value1.poke(values.value1)
    reservationstation.bits.value2.poke(values.value2)
    reservationstation.bits.function3.poke(values.function3)
    reservationstation.bits.immediateOrFunction7.poke(values.immediateOrFunction7)
    reservationstation.bits.opcode.poke(values.opcode)
    reservationstation.bits.programCounter.poke(values.programCounter)
  }

  def expectout(values: Option[ALUValue]): Unit = {
    val out = this.io.out
    out.valid.expect(values.isDefined)
    if (values.isDefined) {
      out.destinationTag.expect(values.get.destinationTag)
      out.value.expect(values.get.value)
    }
  }

  def expectLSQ(values: LSQValue): Unit = {
    val loadstorequeue = this.io.loadstorequeue
    loadstorequeue.destinationTag.expect(values.destinationTag)
    loadstorequeue.value.expect(values.value)
    loadstorequeue.valid.expect(values.valid)
    loadstorequeue.programCounter.expect(values.programCounter)
  }

  def expectFetch(values: FetchValue): Unit = {
    val fetch = this.io.fetch
    fetch.valid.expect(values.valid)
    fetch.programCounter.expect(values.programCounter)
  }
}

class ExecutorTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Executor"

  implicit val defaultParams = Parameters(numberOfDecoders = 1)

  it should "lui" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 16
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 16,
        function3 = 0, immediateOrFunction7 = 0, opcode = 55, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 16)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, 16,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "auipc" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 16
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 16,
        function3 = 0, immediateOrFunction7 = 0, opcode = 55, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 16)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 16,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "jal" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 16
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 16,
        function3 = 0, immediateOrFunction7 = 0, opcode = 111, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 104)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 104,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 116))
    }
  }

  it should "jalr" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2(extend_offset) = 16
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 16,
        function3 = 0, immediateOrFunction7 = 0, opcode = 103, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 104)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 104,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 56))
    }
  }

  it should "beq_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 0, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 104))
    }
  }

  it should "beq_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 40, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 40,
        function3 = 0, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 500))
    }
  }

  it should "bne_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 40, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 40,
        function3 = 1, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 104))
    }
  }

  it should "bne_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 1, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 500))
    }
  }

  it should "blt_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 4, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 104))
    }
  }

  it should "blt_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 20, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 20, value2 = 30,
        function3 = 4, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 500))
    }
  }

  it should "bge_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 20, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 20, value2 = 30,
        function3 = 5, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 104))
    }
  }

  it should "bge_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 5, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 500))
    }
  }

  it should "bltu_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 6, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 104))
    }
  }

  it should "bltu_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 20, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 20, value2 = 30,
        function3 = 6, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 500))
    }
  }

  it should "bgeu_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 20, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 20, value2 = 30,
        function3 = 7, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 104))
    }
  }

  it should "bgeu_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200 (jump先： PC + (offset*2))
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 7, immediateOrFunction7 = 200, opcode = 99, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = true, programCounter = 500))
    }
  }

  it should "lb" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 0, immediateOrFunction7 = 0, opcode = 3, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 70)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 70,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "lh" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 1, immediateOrFunction7 = 0, opcode = 3, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 70)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 70,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "lw" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 2, immediateOrFunction7 = 0, opcode = 3, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 70)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 70,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "lbu" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 4, immediateOrFunction7 = 0, opcode = 3, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 70)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 70,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "lhu" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 5, immediateOrFunction7 = 0, opcode = 3, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 70)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 70,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sb" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 0, immediateOrFunction7 = 200, opcode = 35, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 240,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sh" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 1, immediateOrFunction7 = 200, opcode = 35, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 240,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sw" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs = 30, offset = 200
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 2, immediateOrFunction7 = 200, opcode = 35, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 240,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "addi" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 0, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 70)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 70,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "addiw" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 0, immediateOrFunction7 = 0, opcode = 27, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 70)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 70,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "slti_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 2, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "slti_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 20, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 20, value2 = 30,
        function3 = 2, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sltiu_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 3, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sltiu_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 20, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 20, value2 = 30,
        function3 = 3, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "xori" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10(b1010), rs2 = 18(b10010), rd = 24(b11000)
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 18,
        function3 = 4, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 24)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 24,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "ori" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10(b1010), rs2 = 18(b10010), rd = 26(b11010)
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 18,
        function3 = 6, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 26)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 26,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "andi" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10(b1010), rs2 = 18(b10010), rd = 2(b00010)
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 18,
        function3 = 7, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 2)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 2,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "slli" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10, rs2 = 2, rd = 40
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 2,
        function3 = 1, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 40)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 40,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "srli" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 64(b100 0000), rs2 = 3, rd = 8
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 64, value2 = 3,
        function3 = 5, immediateOrFunction7 = 0, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 8)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 8,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "srai" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 7(b0111), rs2 = 2, rd = 1(b0001)
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 7, value2 = 2,
        function3 = 5, immediateOrFunction7 = 32, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "srai nigative" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = -123, rs2 = 2, rd = -31
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = -123, value2 = 2,
        function3 = 5, immediateOrFunction7 = 32, opcode = 19, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = -31)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = -31,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "add" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 0, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 70)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 70,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sub" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 0, immediateOrFunction7 = 32, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 10)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 10,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sll" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10, rs2 = 2, rd = 40
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 2,
        function3 = 1, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 40)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 40,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "slt_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 2, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "slt_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 20, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 20, value2 = 30,
        function3 = 2, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sltu_NG" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 40, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 40, value2 = 30,
        function3 = 3, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 0)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 0,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sltu_OK" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 20, rs2 = 30
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 20, value2 = 30,
        function3 = 3, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 1)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 1,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "xor" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10(b1010), rs2 = 18(b10010), rd = 24(b11000)
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 18,
        function3 = 4, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 24)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 24,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  //
  it should "srl" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 64, rs2 = 3, rd = 8
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 64, value2 = 3,
        function3 = 5, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 8)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 8,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sra" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10, rs2 = 2, rd = 40
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 2,
        function3 = 1, immediateOrFunction7 = 32, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 40)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 40,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "sra negative" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = -100, rs2 = 2, rd = -25
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = -100, value2 = 2,
        function3 = 5, immediateOrFunction7 = 32, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = -25)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = -25,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "or" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10(b1010), rs2 = 18(b10010), rd = 26(b11010)
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 18,
        function3 = 6, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 26)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 26,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }

  it should "and" in {
    test(new ExecutorWrapper) { c =>
      // rs1 = 10(b1010), rs2 = 18(b10010), rd = 2(b00010)
      c.setALU(values = ReservationValue(valid = true, destinationTag = 10, value1 = 10, value2 = 18,
        function3 = 7, immediateOrFunction7 = 0, opcode = 51, programCounter = 100))

      c.expectout(values = Some(ALUValue(destinationTag = 10, value = 2)))

      c.expectLSQ(values = LSQValue(destinationTag = 10, value = 2,
        valid = true, programCounter = 100))

      c.expectFetch(values = FetchValue(valid = false, programCounter = 104))
    }
  }
}

