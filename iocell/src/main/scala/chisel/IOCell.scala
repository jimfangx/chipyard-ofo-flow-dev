// See LICENSE for license details

package barstools.iocell.chisel

import chisel3._
import chisel3.util.{Cat, HasBlackBoxResource}
import chisel3.experimental.{Analog, DataMirror, IO}

class AnalogIOCellBundle extends Bundle {
  val pad = Analog(1.W)
  val core = Analog(1.W)
}

class DigitalGPIOCellBundle extends Bundle {
  val pad = Analog(1.W)
  val i = Output(Bool())
  val ie = Input(Bool())
  val o = Input(Bool())
  val oe = Input(Bool())
}

class DigitalOutIOCellBundle extends Bundle {
  val pad = Output(Bool())
  val o = Input(Bool())
  val oe = Input(Bool())
}

class DigitalInIOCellBundle extends Bundle {
  val pad = Input(Bool())
  val i = Output(Bool())
  val ie = Input(Bool())
}

abstract class IOCell extends BlackBox with HasBlackBoxResource

abstract class AnalogIOCell extends IOCell {
  val io: AnalogIOCellBundle
}

abstract class DigitalGPIOCell extends IOCell {
  val io: DigitalGPIOCellBundle
}

abstract class DigitalInIOCell extends IOCell {
  val io: DigitalInIOCellBundle
}

abstract class DigitalOutIOCell extends IOCell {
  val io: DigitalOutIOCellBundle
}

class ExampleAnalogIOCell extends AnalogIOCell {
  val io = IO(new AnalogIOCellBundle)
  addResource("/barstools/iocell/vsrc/IOCell.v")
}

class ExampleDigitalGPIOCell extends DigitalGPIOCell {
  val io = IO(new DigitalGPIOCellBundle)
  addResource("/barstools/iocell/vsrc/IOCell.v")
}

class ExampleDigitalInIOCell extends DigitalInIOCell {
  val io = IO(new DigitalInIOCellBundle)
  addResource("/barstools/iocell/vsrc/IOCell.v")
}

class ExampleDigitalOutIOCell extends DigitalOutIOCell {
  val io = IO(new DigitalOutIOCellBundle)
  addResource("/barstools/iocell/vsrc/IOCell.v")
}

object IOCell {

  def exampleAnalog() = Module(new ExampleAnalogIOCell)
  def exampleGPIO() = Module(new ExampleDigitalGPIOCell)
  def exampleInput() = Module(new ExampleDigitalInIOCell)
  def exampleOutput() = Module(new ExampleDigitalOutIOCell)

/* This doesn't work because chiselTypeOf doesn't preserve direction info :(
  def generateIOFromSignal[T <: Data](coreSignal: T,
    inFn: () => DigitalInIOCell = IOCell.exampleInput,
    outFn: () => DigitalOutIOCell = IOCell.exampleOutput,
    anaFn: () => AnalogIOCell = IOCell.exampleAnalog): (T, Seq[IOCell]) =
  {
    val padSignal = DataMirror.specifiedDirectionOf(coreSignal) match {
      case SpecifiedDirection.Input => IO(Input(chiselTypeOf(coreSignal)))
      case SpecifiedDirection.Output => IO(Output(chiselTypeOf(coreSignal)))
      case SpecifiedDirection.Flip => IO(Flipped(chiselTypeOf(coreSignal)))
      case _ => IO(chiselTypeOf(coreSignal))
    }

    val iocells = IOCell.generateFromSignal(coreSignal, padSignal, inFn, outFn, anaFn)
    (padSignal, iocells).asInstanceOf[(T, Seq[IOCell])]
  }
*/

  def generateFromSignal[T <: Data](coreSignal: T, padSignal: T,
    inFn: () => DigitalInIOCell = IOCell.exampleInput,
    outFn: () => DigitalOutIOCell = IOCell.exampleOutput,
    anaFn: () => AnalogIOCell = IOCell.exampleAnalog): Seq[IOCell] =
  {
    coreSignal match {
      case coreSignal: Analog => {
        if (coreSignal.getWidth == 0) {
          Seq()
        } else {
          require(coreSignal.getWidth == 1, "Analogs wider than 1 bit are not supported because we can't bit-select Analogs (https://github.com/freechipsproject/chisel3/issues/536)")
          val iocell = anaFn()
          iocell.io.core <> coreSignal
          padSignal <> iocell.io.pad
          Seq(iocell)
        }
      }
      case coreSignal: Clock => {
        DataMirror.directionOf(coreSignal) match {
          case ActualDirection.Input => {
            val iocell = inFn()
            coreSignal := iocell.io.i.asClock
            iocell.io.ie := true.B
            iocell.io.pad := padSignal.asUInt.asBool
            Seq(iocell)
          }
          case ActualDirection.Output => {
            val iocell = outFn()
            iocell.io.o := coreSignal.asUInt.asBool
            iocell.io.oe := true.B
            padSignal := iocell.io.pad.asClock
            Seq(iocell)
          }
          case _ => throw new Exception("Unknown direction")
        }
      }
      case coreSignal: Bits => {
        require(padSignal.getWidth == coreSignal.getWidth, "padSignal and coreSignal must be the same width")
        if (padSignal.getWidth == 0) {
          // This dummy assignment will prevent invalid firrtl from being emitted
          DataMirror.directionOf(coreSignal) match {
            case ActualDirection.Input => coreSignal := 0.U
          }
          Seq()
        } else {
          DataMirror.directionOf(coreSignal) match {
            case ActualDirection.Input => {
              // this type cast is safe because we guarantee that padSignal and coreSignal are the same type (T), but the compiler is not smart enough to know that
              val iocells = padSignal.asInstanceOf[Bits].asBools.map { w =>
                val iocell = inFn()
                iocell.io.pad := w
                iocell.io.ie := true.B
                iocell
              }
              coreSignal := Cat(iocells.map(_.io.i).reverse)
              iocells
            }
            case ActualDirection.Output => {
              val iocells = coreSignal.asBools.map { w =>
                val iocell = outFn()
                iocell.io.o := w
                iocell.io.oe := true.B
                iocell
              }
              padSignal := Cat(iocells.map(_.io.pad).reverse)
              iocells
            }
            case _ => throw new Exception("Unknown direction")
          }
        }
      }
      case coreSignal: Vec[Data] => {
        // this type cast is safe because we guarantee that padSignal and coreSignal are the same type (T), but the compiler is not smart enough to know that
        val padSignal2 = padSignal.asInstanceOf[Vec[Data]]
        require(padSignal2.size == coreSignal.size, "size of Vec for padSignal and coreSignal must be the same")
        coreSignal.zip(padSignal2).foldLeft(Seq.empty[IOCell]) { case (total, (core, pad)) =>
          val ios = IOCell.generateFromSignal(core, pad, inFn, outFn, anaFn)
          total ++ ios
        }
      }
      case coreSignal: Record => {
        // this type cast is safe because we guarantee that padSignal and coreSignal are the same type (T), but the compiler is not smart enough to know that
        val padSignal2 = padSignal.asInstanceOf[Record]
        coreSignal.elements.foldLeft(Seq.empty[IOCell]) { case (total, (name, core)) =>
          val pad = padSignal2.elements(name)
          val ios = IOCell.generateFromSignal(core, pad, inFn, outFn, anaFn)
          total ++ ios
        }
      }
      case _ => { throw new Exception("Oops, I don't know how to handle this signal.") }
    }
  }

}
