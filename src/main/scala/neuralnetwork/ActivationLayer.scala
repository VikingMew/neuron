// Copyright: MIT License 2014 Jianbo Ye (jxy198@psu.edu)
package neuralnetwork
import scala.concurrent.stm._

/** SingleLayerNeuralNetwork is sigmoid functional layer 
 *  that takes in signals and transform them to activations [0,1] **/
class SingleLayerNeuralNetwork (override val dimension: Int, val func: NeuronFunction = SigmoidFunction /** Pointwise Function **/ ) 
	extends SelfTransform (dimension) {
  type InstanceType <: InstanceOfSingleLayerNeuralNetwork
  def create (): InstanceOfSingleLayerNeuralNetwork = new InstanceOfSingleLayerNeuralNetwork(this)
}
class InstanceOfSingleLayerNeuralNetwork (override val NN: SingleLayerNeuralNetwork) 
	extends InstanceOfSelfTransform (NN) { 
  type StructureType <: SingleLayerNeuralNetwork
  
  def apply (x: NeuronVector, mem:SetOfMemorables) = {
    assert (x.length == inputDimension)
    mem(key).mirrorIndex = (mem(key).mirrorIndex - 1 + mem(key).numOfMirrors) % mem(key).numOfMirrors
    val output = NN.func(x)
    mem(key).gradientBuffer(mem(key).mirrorIndex) = NN.func.grad(x, output)    
    output // outputBuffer(cIndex)
  }
  def apply (xs:NeuronMatrix, mem:SetOfMemorables) = {
    assert(xs.rows == inputDimension)
    mem(key).mirrorIndex = (mem(key).mirrorIndex - 1 + mem(key).numOfMirrors) % mem(key).numOfMirrors
    val output = NN.func(xs)
    mem(key).gradientBufferM(mem(key).mirrorIndex) = NN.func.grad(xs, output)
    output
  }
  override def init(seed:String, mem:SetOfMemorables) = {
    if (!mem.isDefinedAt(key) || mem(key).status != seed) {
      mem. += (key -> new Memorable)
      mem(key).status = seed
      mem(key).numOfMirrors = 1 // find a new instance
      mem(key).mirrorIndex = 0
    }
    else {      
      mem(key).numOfMirrors = mem(key).numOfMirrors + 1
    }
    this
  }
  
  override def allocate(seed:String, mem:SetOfMemorables) ={
    if (mem(key).status == seed) {
      mem(key).gradientBuffer= new Array[NeuronVector] (mem(key).numOfMirrors)
      mem(key).gradientBufferM=new Array[NeuronMatrix] (mem(key).numOfMirrors)
      mem(key).status = "" // reset status to make sure *Buffer are allocated only once
    } else {} 
    this
  }
  def backpropagate(eta: NeuronVector, mem:SetOfMemorables) = {
    val cIndex = mem(key).mirrorIndex 
    mem(key).mirrorIndex = (mem(key).mirrorIndex + 1) % mem(key).numOfMirrors
    eta DOT mem(key).gradientBuffer(cIndex) // there is no penalty for sparsity
  }
  def backpropagate(etas:NeuronMatrix, mem: SetOfMemorables) = {
    val cIndex = mem(key).mirrorIndex
    mem(key).mirrorIndex = (mem(key).mirrorIndex + 1) % mem(key).numOfMirrors
    etas DOT mem(key).gradientBufferM(cIndex)
  }
}

/** SparseSingleLayer computes average activation and enforce sparsity penalty **/
class SparseSingleLayerNN (override val dimension: Int, 
						   var beta: Double = 0.0,
                           override val func: NeuronFunction = SigmoidFunction /** Pointwise Activation Function **/,
						   val penalty: NeuronFunction = new KL_divergenceFunction(0.04) /** Sparsity Penalty Function **/)
	extends SingleLayerNeuralNetwork (dimension, func) {
  type InstanceType = InstanceOfSparseSingleLayerNN
  override def create (): InstanceOfSparseSingleLayerNN = new InstanceOfSparseSingleLayerNN(this)
} 

class InstanceOfSparseSingleLayerNN (override val NN: SparseSingleLayerNN) 
	extends InstanceOfSingleLayerNeuralNetwork (NN) {
  private var totalUsage: Int = 0 // reset if weights updated
  private val totalUsageOnUpdate = Ref(0)
  override def setWeights(seed: String, w: WeightVector) : Unit = {
    atomic { implicit txn =>
    if (status != seed) {
      status = seed
      totalUsage = totalUsageOnUpdate()
      totalUsageOnUpdate() = 0
      rho := rhoOnUpdate()
      rhoOnUpdate().set(0.0)
    } else {
    }
    }
 }
  override def getDerativeOfWeights(seed:String, dw:WeightVector, numOfSamples:Int) : Double = {
    if (status != seed) {
      if (totalUsage == 0) 0.0 /* use default value */ else {
        //println("s ", NN.penalty(rho / totalUsage).sum * NN.beta)
        NN.penalty(rho / totalUsage).sum * NN.beta
      }
    }else {
      0.0
    }
  }
  override def apply(x: NeuronVector, mem:SetOfMemorables) = {
    val y = super.apply(x, mem)
    atomic { implicit txn =>
    // This part has parallel side effects
    rhoOnUpdate() = rhoOnUpdate() + y; 
    totalUsageOnUpdate() = totalUsageOnUpdate() + 1 // for computation of average activation
    }
    y
  }
  override def apply(xs:NeuronMatrix, mem:SetOfMemorables) = {
    val ys = super.apply(xs, mem)
    atomic { implicit txn =>
    // This part has parallel side effects
    rhoOnUpdate() = rhoOnUpdate() + ys.sumRow() // it still has problems
    totalUsageOnUpdate() = totalUsageOnUpdate() + ys.cols // for computation of average activation
    }
    ys  
  }
  private val rho : NeuronVector = new NeuronVector(outputDimension)
  private val rhoOnUpdate = Ref(new NeuronVector(outputDimension))
  override def backpropagate(eta: NeuronVector, mem:SetOfMemorables) = {
    val cIndex = mem(key).mirrorIndex 
    mem(key).mirrorIndex = (mem(key).mirrorIndex + 1) % mem(key).numOfMirrors
    (eta + NN.penalty.grad(rho/totalUsage) * NN.beta) DOT mem(key).gradientBuffer(cIndex)
  }
  override def backpropagate(etas: NeuronMatrix, mem:SetOfMemorables) = {
    val cIndex = mem(key).mirrorIndex 
    mem(key).mirrorIndex = (mem(key).mirrorIndex + 1) % mem(key).numOfMirrors
    (etas Add NN.penalty.grad(rho/totalUsage) * NN.beta) DOT mem(key).gradientBufferM(cIndex)    
  }
}