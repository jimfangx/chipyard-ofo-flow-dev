package ofo

import chisel3._
import chisel3.util._
import chisel3.experimental.Analog
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._

trait CanHaveOpenframePad { this: BaseSubsystem =>
  val customIO = IO(new Bundle {
    val gpio = Vec(32, Analog(1.W)) // need to fix signal names
    val uart = new UARTPortIO
  })
}
