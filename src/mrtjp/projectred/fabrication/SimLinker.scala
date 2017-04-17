package mrtjp.projectred.fabrication

import mrtjp.core.vec.Point

import scala.collection.mutable.{Map => MMap}

trait ISETile

trait ISEWireTile extends ISETile
{
    def buildWireNet:IWireNet

    def cacheStateRegisters(linker:ISELinker)
}

trait ISEGateTile extends ISETile
{
    def buildImplicitWireNet(r:Int):IWireNet

    def allocateOrFindRegisters(linker:ISELinker)

    def declareOperations(linker:ISELinker)
}

trait IWireNet
{
    val points:scala.collection.Set[Point]

    def allocateRegisters(linker:ISELinker)

    def declareOperations(linker:ISELinker)

    def getInputRegister(p:Point):Int

    def getOutputRegister(p:Point):Int

    def getChannelStateRegisters(p:Point):Set[Int]
}

trait ISETileMap
{
    val tiles:scala.collection.Map[(Int, Int), ISETile]
}

trait ISELinker
{
    /**
      * Allocates space for a register.
      * @return The ID where this register can be added
      */
    def allocateRegisterID():Int

    /**
      * Allocates space for a gate
      * @return The ID where this gate can be added
      */
    def allocateGateID():Int

    /**
      * Sets the ID to the given register
      * @param id A previously allocated register ID
      * @param r The register to put in the slot
      */
    def addRegister(id:Int, r:ISERegister)

    /**
      * Sets the ID to the given gate
      * @param id A previously allocated gate ID
      * @param g The gate
      * @param drivingRegs Registers responsible for driving the gate
      * @param drivenRegs Registers that will be driven by the gate
      */
    def addGate(id:Int, g:ISEGate, drivingRegs:Seq[Int], drivenRegs:Seq[Int])

    /**
      * Callback used by tiles during the linking process. Used to locate a
      * register inputting to point `p` from direction `r`
      *
      * @return The register ID of the located register, or a zero-constant register
      *         if none found.
      */
    def findInputRegister(p:Point, r:Int):Int

    /**
      * Callback used by tiles during the linking process. Used to locate a
      * register outputting from point `p` to direction `r`
      *
      * @return The register ID of the located register, or a zero-constant register
      *         if none found.
      */
    def findOutputRegister(p:Point, r:Int):Int

    /**
      * Callback used by tiles during the linking process. Used to fetch all registers
      * part of a wire net that pass through the given point `p`.
      *
      * @return A set of register IDs within a wirenet that go through the point.
      */
    def getAllWireNetRegisters(p:Point):Set[Int]
}

object ISELinker
{
    def linkFromMap(map:ISETileMap, delegate:Set[Int] => Unit):SEIntegratedCircuit =
        SELinker.linkFromMap(map, delegate)
}

private class SELinker extends ISELinker
{
    import SEIntegratedCircuit._

    private var registers = Array[ISERegister]() //The registers currently in the circuit, indexed by ID
    private var gates = Array[ISEGate]() //The gates in the circuit indexed by ID

    private var regDependents = MMap[Int, Seq[Int]]() //Register deps [regID -> Seq[gateID]]
    private var regDependencies = MMap[Int, Seq[Int]]() //Register depenendices [regID -> Seq[gateID]]
    private var gateDependents = MMap[Int, Seq[Int]]() //[gateID -> Seq[regID]]
    private var gateDependencies = MMap[Int, Seq[Int]]() //[gateID -> Seq[regID]]

    private val wireNetMap = MMap[Point, IWireNet]() //Wire register map [pos -> net]

    /*
     * Stores implicit wires such as between two touching gates. There
     * is no physical wire on the map, but there needs to be one for proper
     * propagation. The map works as:
     *
     * [Set(p1, p2) -> (regID, wireType)]
     *
     * where p1 and p2 are adjacent points on the map
     */
    private val implicitWireNetMap = MMap[Set[Point], IWireNet]()

    private var registerIDPool = 0
    private var gateIDPool = 0

    override def allocateRegisterID() =
    {
        val id = registerIDPool
        registerIDPool += 1
        id
    }

    override def allocateGateID() =
    {
        val id = gateIDPool
        gateIDPool += 1
        id
    }

    override def addRegister(id:Int, r:ISERegister)
    {
        while (registers.length <= id)
            registers :+= null
        registers(id) = r
    }

    override def addGate(id:Int, g:ISEGate, drivingRegs:Seq[Int], drivenRegs:Seq[Int])
    {
        while (gates.length <= id)
            gates :+= null
        gates(id) = g

        for (regID <- drivingRegs) {
            val others = regDependents.getOrElse(regID, Seq.empty)
            regDependents += regID -> (others :+ id)
        }

        for (regID <- drivenRegs) {
            val others = regDependencies.getOrElse(regID, Seq.empty)
            regDependencies += regID -> (others :+ id)
        }

        gateDependents += id -> drivingRegs
        gateDependencies += id -> drivingRegs
    }

    override def findInputRegister(p:Point, r:Int):Int = //register that inputs to p from side r
    {
        val p2 = p.offset(r)
        wireNetMap.get(p2) match {
            case Some(net) => net.getOutputRegister(p2) //Input to p is output from p2
            case _ => implicitWireNetMap.get(Set(p, p2)) match {
                case Some(net) => net.getOutputRegister(p2)
                case _ => REG_ZERO
            }
        }
    }

    override def findOutputRegister(p:Point, r:Int):Int = //register that outputs from p to side r
    {
        val p2 = p.offset(r)
        wireNetMap.get(p2) match {
            case Some(net) => net.getInputRegister(p2) //Output to p is input from p2
            case _ => implicitWireNetMap.get(Set(p, p2)) match {
                case Some(net) => net.getInputRegister(p2)
                case _ => REG_ZERO
            }
        }
    }

    override def getAllWireNetRegisters(p:Point):Set[Int] =
        wireNetMap.get(p) match {
            case Some(net) => net.getChannelStateRegisters(p)
            case _ => Set(REG_ZERO)
    }
}

private object SELinker
{
    def linkFromMap(map:ISETileMap, delegate:(Set[Int]) => Unit):SEIntegratedCircuit =
    {
        val linker = new SELinker
        import linker._

        //Add all SFRs
        import SEIntegratedCircuit._
        for (r <- 0 until 4) for (i <- 0 until 16)
            addRegister(REG_IN(r, i), new StandardRegister[Byte](0))
        for (r <- 0 until 4) for (i <- 0 until 16)
            addRegister(REG_OUT(r, i), new StandardRegister[Byte](0))
        addRegister(REG_SYSTIME, new StandardRegister[Long](0L))
        addRegister(REG_ZERO, new ConstantRegister[Byte](0))

        registerIDPool = REG_ZERO+1

        val allWires = map.tiles.collect {
            case ((x, y), w:ISEWireTile) => (Point(x, y), w)
        }
        val allGates = map.tiles.collect {
            case ((x, y), g:ISEGateTile) => (Point(x, y), g)
        }

        // Register all wire networks
        for ((p, w) <- allWires) { //Start with normal wire nets. Ask each wire to assemble a network.
            if (!wireNetMap.contains(p)) {
                val net = w.buildWireNet
                for (netP <- net.points)
                    wireNetMap += netP -> net
            }
        }

        for ((p, g) <- allGates) { //Then add implicit nets. These are wires between adjacent gates.
            for (r <- 0 until 4) {
                val pSet = Set(p, p.offset(r))
                if (!implicitWireNetMap.contains(pSet)) {
                    val net = g.buildImplicitWireNet(r)
                    if (net != null)
                        implicitWireNetMap += pSet -> net
                }
            }
        }

        // Add required registers from all parts
        for (net <- wireNetMap.values ++ implicitWireNetMap.values)
            net.allocateRegisters(linker) //from wires
        for ((p, w) <- allWires)
            w.cacheStateRegisters(linker) //tell wires about the registers

        for ((_, g) <- allGates)
            g.allocateOrFindRegisters(linker) //from gates

        // Add all gate operations
        for (net <- wireNetMap.values ++ implicitWireNetMap.values)
            net.declareOperations(linker) //from wires
        for ((_, g) <- allGates)
            g.declareOperations(linker) //from gates

        new SEIntegratedCircuit(
            registers.toSeq,
            gates.toSeq,
            regDependents.toMap,
            delegate
        )
    }
}