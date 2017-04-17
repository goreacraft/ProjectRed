/*
 * Copyright (c) 2015.
 * Created by MrTJP.
 * All rights reserved.
 */
package mrtjp.projectred.fabrication

import codechicken.lib.data.{MCDataInput, MCDataOutput}
import codechicken.lib.render.CCRenderState
import codechicken.lib.vec.Transformation
import com.mojang.realmsclient.gui.ChatFormatting
import mrtjp.core.util.Enum
import mrtjp.core.vec.Point
import mrtjp.projectred.integration.GateDefinition.GateDef
import net.minecraft.nbt.NBTTagCompound
import net.minecraftforge.fml.relauncher.{Side, SideOnly}

abstract class GateICTile extends ICTile with TConnectableICTile with TICTileOrient with IGuiICTile with ISEGateTile
{
    private var gateSubID:Byte = 0
    private var gateShape:Byte = 0

    def getLogic[T]:T
    def getLogicPrimitive = getLogic[ICGateLogic[GateICTile]]

    def subID = gateSubID&0xFF

    def shape = gateShape&0xFF
    def setShape(s:Int){ gateShape = s.toByte }

    def preparePlacement(rot:Int, meta:Int)
    {
        gateSubID = meta.toByte
        setRotation(rot)
    }

    override def save(tag:NBTTagCompound)
    {
        tag.setByte("orient", orientation)
        tag.setByte("subID", gateSubID)
        tag.setByte("shape", gateShape)
        tag.setByte("connMap", connMap)
    }

    override def load(tag:NBTTagCompound)
    {
        orientation = tag.getByte("orient")
        gateSubID = tag.getByte("subID")
        gateShape = tag.getByte("shape")
        connMap = tag.getByte("connMap")
    }

    override def writeDesc(out:MCDataOutput)
    {
        out.writeByte(orientation)
        out.writeByte(gateSubID)
        out.writeByte(gateShape)
    }

    override def readDesc(in:MCDataInput)
    {
        orientation = in.readByte()
        gateSubID = in.readByte()
        gateShape = in.readByte()
    }

    override def read(in:MCDataInput, key:Int) = key match
    {
        case 1 => orientation = in.readByte()
        case 2 => gateShape = in.readByte()
        case _ => super.read(in, key)
    }

    override def readClientPacket(in:MCDataInput)
    {
        readClientPacket(in, in.readUByte())
    }

    def readClientPacket(in:MCDataInput, key:Int) = key match
    {
        case 0 => rotate()
        case 1 => configure()
        case 2 => getLogicPrimitive.activate(this)
        case _ =>
    }

    override def canConnectTile(part:ICTile, r:Int) =
        getLogicPrimitive.canConnectTo(this, part, toInternal(r))

    def onSchematicChanged()
    {
        editor.markSchematicChanged()
    }

    override def update()
    {
        getLogicPrimitive.onTick(this)
    }

    override def onNeighborChanged()
    {
        if (!editor.network.isRemote) {
            if (updateConns())
                onSchematicChanged()
        }
    }

    override def onAdded()
    {
        super.onAdded()
        if (!editor.network.isRemote)
            updateConns()
    }

    override def onRemoved()
    {
        super.onRemoved()
        if (!editor.network.isRemote)
            notify(0xF)
    }

    def configure()
    {
        if (getLogicPrimitive.cycleShape(this)) {
            updateConns()
            editor.network.markSave()
            sendShapeUpdate()
            notify(0xF)
            onSchematicChanged()
        }
    }

    def rotate()
    {
        setRotation((rotation+1)%4)
        updateConns()
        editor.network.markSave()
        sendOrientUpdate()
        notify(0xF)
        onSchematicChanged()
    }

    def sendShapeUpdate()
    {
        writeStreamOf(2).writeByte(gateShape)
    }

    def sendOrientUpdate()
    {
        writeStreamOf(1).writeByte(orientation)
    }

    override def buildImplicitWireNet(r:Int) = null //TODO

    override def allocateOrFindRegisters(linker:ISELinker)
    {
        getLogicPrimitive.allocateOrFindRegisters(this, linker)
    }

    def getInputRegister(r:Int, linker:ISELinker):Int =  linker.findInputRegister(Point(x, y), toAbsolute(r))

    def getOutputRegister(r:Int, linker:ISELinker):Int =  linker.findOutputRegister(Point(x, y), toAbsolute(r))

    override def declareOperations(linker:ISELinker)
    {
        getLogicPrimitive.declareOperations(this, linker)
    }

    override def onRegistersChanged(regIDs:Set[Int])
    {
        getLogicPrimitive.onRegistersChanged(this, regIDs)
    }

    @SideOnly(Side.CLIENT)
    override def renderDynamic(ccrs:CCRenderState, t:Transformation, ortho:Boolean, frame:Float)
    {
        RenderICGate.renderDynamic(ccrs, this, t, ortho, frame)
    }

    @SideOnly(Side.CLIENT)
    override def getPartName = ICGateDefinition(subID).name

    @SideOnly(Side.CLIENT)
    override def getRolloverData(detailLevel:Int) =
    {
        val s = Seq.newBuilder[String]
        s ++= getLogicPrimitive.getRolloverData(this, detailLevel)
        super.getRolloverData(detailLevel) ++ s.result().map(ChatFormatting.GRAY + _)
    }

    @SideOnly(Side.CLIENT)
    override def createGui = getLogicPrimitive.createGui(this)

    @SideOnly(Side.CLIENT)
    override def onClicked()
    {
        sendClientPacket(_.writeByte(2))
    }

    @SideOnly(Side.CLIENT)
    override def getPickOp =
        CircuitOpDefs.values(CircuitOpDefs.SimpleIO.ordinal+subID).getOp
}

abstract class ICGateLogic[T <: GateICTile]
{
    def canConnectTo(gate:T, part:ICTile, r:Int):Boolean

    def cycleShape(gate:T) = false

    def onTick(gate:T){}

    def activate(gate:T){}

    def allocateOrFindRegisters(gate:T, linker:ISELinker)

    def declareOperations(gate:T, linker:ISELinker)

    def onRegistersChanged(gate:T, regIDs:Set[Int]){}

    @SideOnly(Side.CLIENT)
    def getRolloverData(gate:T, detailLevel:Int):Seq[String] = Seq.empty

    @SideOnly(Side.CLIENT)
    def createGui(gate:T):CircuitGui = new ICGateGui(gate)
}

trait TComplexGateICTile extends GateICTile
{
    def getLogicComplex = getLogic[TComplexICGateLogic[TComplexGateICTile]]

    def assertLogic()

    abstract override def save(tag:NBTTagCompound)
    {
        super.save(tag)
        getLogicComplex.save(tag)
    }

    abstract override def load(tag:NBTTagCompound)
    {
        super.load(tag)
        assertLogic()
        getLogicComplex.load(tag)
    }

    abstract override def writeDesc(packet:MCDataOutput)
    {
        super.writeDesc(packet)
        getLogicComplex.writeDesc(packet)
    }

    abstract override def readDesc(packet:MCDataInput)
    {
        super.readDesc(packet)
        assertLogic()
        getLogicComplex.readDesc(packet)
    }

    abstract override def read(packet:MCDataInput, key:Int) = key match
    {
        case k if k > 10 =>
            assertLogic() //this may be a net dump part
            getLogicComplex.read(packet, k)
        case _ => super.read(packet, key)
    }

    abstract override def preparePlacement(rot:Int, meta:Int)
    {
        super.preparePlacement(rot, meta)
        assertLogic()
    }
}

trait TComplexICGateLogic[T <: TComplexGateICTile] extends ICGateLogic[T]
{
    def save(tag:NBTTagCompound){}
    def load(tag:NBTTagCompound){}

    def readDesc(packet:MCDataInput){}
    def writeDesc(packet:MCDataOutput){}

    /**
     * Allocated keys > 10
     */
    def read(packet:MCDataInput, key:Int){}
}

object ICGateDefinition extends Enum
{
    type EnumVal = ICGateDef

    import mrtjp.projectred.integration.{GateDefinition => gd}

    val IOSimple = ICGateDef("Simple IO", ICTileDefs.IOGate.id)
    val IOAnalog = ICGateDef("Analog IO", ICTileDefs.IOGate.id)
    val IOBundled = ICGateDef("Bundled IO", ICTileDefs.IOGate.id)

    val OR = ICGateDef("OR gate", ICTileDefs.SimpleGate.id, gd.OR)
    val NOR = ICGateDef("NOR gate", ICTileDefs.SimpleGate.id, gd.NOR)
    val NOT = ICGateDef("NOT gate", ICTileDefs.SimpleGate.id, gd.NOT)
    val AND = ICGateDef("AND gate", ICTileDefs.SimpleGate.id, gd.AND)
    val NAND = ICGateDef("NAND gate", ICTileDefs.SimpleGate.id, gd.NAND)
//    val XOR = ICGateDef("XOR gate", CircuitPartDefs.SimpleGate.id, gd.XOR)
//    val XNOR = ICGateDef("XNOR gate", CircuitPartDefs.SimpleGate.id, gd.XNOR)
//    val Buffer = ICGateDef("Buffer gate", CircuitPartDefs.SimpleGate.id, gd.Buffer)
//    val Multiplexer = ICGateDef("Multiplexer", CircuitPartDefs.SimpleGate.id, gd.Multiplexer)
//    val Pulse = ICGateDef("Pulse Former", CircuitPartDefs.SimpleGate.id, gd.Pulse)
//    val Repeater = ICGateDef("Repeater", CircuitPartDefs.SimpleGate.id, gd.Repeater)
//    val Randomizer = ICGateDef("Randomizer", CircuitPartDefs.SimpleGate.id, gd.Randomizer)
//    val SRLatch = ICGateDef("SR Latch", CircuitPartDefs.ComplexGate.id, gd.SRLatch)
//    val ToggleLatch = ICGateDef("Toggle Latch", CircuitPartDefs.ComplexGate.id, gd.ToggleLatch)
//    val TransparentLatch = ICGateDef("Transparent Latch", CircuitPartDefs.SimpleGate.id, gd.TransparentLatch)
//    val Timer = ICGateDef("Timer", CircuitPartDefs.ComplexGate.id, gd.Timer)
//    val Sequencer = ICGateDef("Sequencer", CircuitPartDefs.ComplexGate.id, gd.Sequencer)
//    val Counter = ICGateDef("Counter", CircuitPartDefs.ComplexGate.id, gd.Counter)
//    val StateCell = ICGateDef("State Cell", CircuitPartDefs.ComplexGate.id, gd.StateCell)
//    val Synchronizer = ICGateDef("Synchronizer", CircuitPartDefs.ComplexGate.id, gd.Synchronizer)
//    val DecRandomizer = ICGateDef("Dec Randomizer", CircuitPartDefs.SimpleGate.id, gd.DecRandomizer)
//    val NullCell = ICGateDef("Null Cell", CircuitPartDefs.ArrayGate.id, gd.NullCell)
//    val InvertCell = ICGateDef("Invert Cell", CircuitPartDefs.ArrayGate.id, gd.InvertCell)
//    val BufferCell = ICGateDef("Buffer Cell", CircuitPartDefs.ArrayGate.id, gd.BufferCell)

    case class ICGateDef(unlocal:String, gateType:Int, intDef:GateDef = null) extends Value
    {
        override def name = unlocal
    }
}