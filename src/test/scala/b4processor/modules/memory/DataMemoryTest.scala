package b4processor.modules.memory

import b4processor.Parameters
import b4processor.utils.DataMemoryValue
import chisel3._
import chiseltest._
import org.scalatest.flatspec.AnyFlatSpec

class DataMemoryTestWrapper(implicit params: Parameters) extends DataMemory {}

class DataMemoryTest extends AnyFlatSpec with ChiselScalatestTester {
  behavior of "Data Memory"

  implicit val params = Parameters()

  it should "store and load a value" in {
    test(new DataMemoryTestWrapper) { c =>
      // 0アドレスへのストア
      c.io.dataIn.bits.address.poke(0)
      c.io.dataIn.bits.data.poke(123)
      c.io.dataIn.bits.tag.poke(10)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(false)
      c.io.dataIn.valid.poke(true)

      c.clock.step(1)
      // 別アドレスへのストア
      c.io.dataIn.bits.address.poke(40)
      c.io.dataIn.bits.data.poke(1000)
      c.io.dataIn.bits.tag.poke(30)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(false)
      c.io.dataIn.valid.poke(true)

      c.clock.step(1)
      // 0アドレスからのロード
      c.io.dataIn.bits.address.poke(40)
      c.io.dataIn.bits.data.poke(0)
      c.io.dataIn.bits.tag.poke(20)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(true)

      c.clock.step(1)
      // invalid instruction
      c.io.dataIn.bits.address.poke(0)
      c.io.dataIn.bits.data.poke(0)
      c.io.dataIn.bits.tag.poke(0)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(false)
      c.io.dataIn.valid.poke(false)

      // expect load instruction
      c.io.dataOut.validAsResult.expect(true)
      c.io.dataOut.validAsLoadStoreAddress.expect(true)
      c.io.dataOut.value.expect(1000)
      c.io.dataOut.tag.expect(20)

      c.clock.step(5)
    }
  }

  it should "load and next clock store at the same time" in {
    test(new DataMemoryTestWrapper) { c =>
      // STORE
      c.io.dataIn.bits.address.poke(10)
      c.io.dataIn.bits.data.poke(123)
      c.io.dataIn.bits.tag.poke(30)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(false)
      c.io.dataIn.valid.poke(true)
      c.clock.step(1)

      // LOAD
      c.io.dataIn.bits.address.poke(10)
      c.io.dataIn.bits.data.poke(0)
      c.io.dataIn.bits.tag.poke(20)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(true)
      c.clock.step(1)

      // STORE
      c.io.dataIn.bits.address.poke(20)
      c.io.dataIn.bits.data.poke(456)
      c.io.dataIn.bits.tag.poke(15)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(false)
      c.io.dataIn.valid.poke(true)

      // expect load instruction
      c.io.dataOut.validAsResult.expect(true)
      c.io.dataOut.validAsLoadStoreAddress.expect(true)
      c.io.dataOut.value.expect(123)
      c.io.dataOut.tag.expect(20)
      c.clock.step(1)

      // LOAD
      c.io.dataIn.bits.address.poke(20)
      c.io.dataIn.bits.data.poke(0)
      c.io.dataIn.bits.tag.poke(25)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(true)
      c.clock.step(5)
    }
  }

  it should "load a byte value" in {
    test(new DataMemoryTestWrapper).withAnnotations(Seq(WriteVcdAnnotation)) { c =>
      // 0アドレスへのストア
      c.io.dataIn.bits.address.poke(20)
      c.io.dataIn.bits.data.poke("b10000000011".U)
      c.io.dataIn.bits.tag.poke(10)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(false)
      c.io.dataIn.valid.poke(true)

      c.clock.step()

      //書き込みを止める
      c.io.dataIn.bits.address.poke(0)
      c.io.dataIn.bits.data.poke(0)
      c.io.dataIn.bits.tag.poke(0)
      c.io.dataIn.bits.function3.poke(0)
      c.io.dataIn.bits.isLoad.poke(0)
      c.io.dataIn.valid.poke(false)

      c.clock.step(2)
      // 0アドレスからのロード
      c.io.dataIn.bits.address.poke(20)
      c.io.dataIn.bits.data.poke(0)
      c.io.dataIn.bits.tag.poke(20)
      c.io.dataIn.bits.function3.poke("b000".U)
      c.io.dataIn.bits.isLoad.poke(true)
      c.io.dataIn.valid.poke(true)

      c.clock.step(1)
      c.io.dataIn.bits.address.poke(0)
      c.io.dataIn.bits.data.poke(0)
      c.io.dataIn.bits.tag.poke(0)
      c.io.dataIn.bits.function3.poke("b011".U)
      c.io.dataIn.bits.isLoad.poke(false)
      c.io.dataIn.valid.poke(false)

      c.io.dataOut.validAsResult.expect(true)
      c.io.dataOut.validAsLoadStoreAddress.expect(true)
      c.io.dataOut.value.expect(3)
      c.io.dataOut.tag.expect(20)

      c.clock.step(2)
    }
  }
}