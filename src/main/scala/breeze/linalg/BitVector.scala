package breeze.linalg

import java.util
import breeze.macros.expand
import scala.math.BigInt
import breeze.linalg.operators._
import breeze.math.Complex
import breeze.linalg.support.CanAxpy

/**
 * TODO
 * @ dwhl: What does enforceLength exactly do?
 *
 * @author dlwh
 * @author Martin Senne
 **/
class BitVector(val data: java.util.BitSet, val length: Int, val enforceLength: Boolean = true) extends Vector[Boolean] with VectorLike[Boolean, BitVector] {
  def apply(i: Int): Boolean = {
    if(i < 0 || (i >= length))
      throw new IndexOutOfBoundsException(s"$i is not in the range [0, $length)")
    data.get(i)
  }

  def update(i: Int, v: Boolean) {
    if(i < 0 || (i >= length))
      throw new IndexOutOfBoundsException(s"$i is not in the range [0, $length)")
    data.set(i, v)
  }

  def activeSize: Int = data.cardinality()

  def copy = new BitVector(data, length)

  def repr: BitVector = this

  def activeKeysIterator: Iterator[Int] = {
    val firstBit = data.nextSetBit(0)
    if(firstBit < 0) return Iterator.empty

    new Iterator[Int] {
      var nextReady = true
      var _next = firstBit
      def hasNext: Boolean = (_next >= 0) && (nextReady || {
        _next += 1
        _next = data.nextSetBit(_next)
        nextReady = _next >= 0
        nextReady
      })

      def next(): Int = {
        if(!nextReady) {
          hasNext
          if(!nextReady) throw new NoSuchElementException
        }
        nextReady = false
        _next
      }
    }



  }

  /** This will just be a bunch of true values. */
  def activeValuesIterator: Iterator[Boolean] = activeKeysIterator.map(_ => true)

  def activeIterator: Iterator[(Int, Boolean)] = activeKeysIterator.map(_ -> true)

  def lengthsMatch(other: Vector[_]) = {
    if(!enforceLength) true
    else other match {
      case x: BitVector => !x.enforceLength || x.length == length
      case _ => other.length == length
    }
  }

  override def toString = {
    activeKeysIterator.mkString("BitVector(",", ", ")")
  }

}

object BitVector extends BitVectorOps {

  def apply(bools: Boolean*) = {
    val bs = new util.BitSet
    for(i <- 0 until bools.length if bools(i)) {
      bs.set(i)
    }

    new BitVector(bs, bools.length)
  }

  def apply(length: Int, enforceLength: Boolean = true)(trues: Int*) = {
    val bs = new util.BitSet
    for(i <- trues) {
      if(enforceLength && i >= length)
        throw new IndexOutOfBoundsException(s"$i is bigger than $length")
      bs.set(i)
    }
    new BitVector(bs, length, enforceLength && length >= 0)
  }

  def zeros(length: Int, enforceLength: Boolean = true):BitVector = new BitVector(new util.BitSet(), length, enforceLength)

  def ones(length: Int, enforceLength: Boolean = true) = {
    val bs = new java.util.BitSet(length)
    bs.set(0, length)
    new BitVector(bs, length, enforceLength)

    //    commented out by Martin Senne due to issue #92
    //    (BitSet.valueOf not available in JDK 6)
    //
    //    val data = new Array[Long]( (length + 63)/64)
    //    util.Arrays.fill(data, -1L)
    //    val bs = util.BitSet.valueOf(data)
    //    bs.clear(length,data.length * 64)
    //    new BitVector(bs, length, enforceLength)
  }
}

trait BitVectorOps {

  @expand
  @expand.valify
  implicit def bv_bv_UpdateOp[@expand.args(OpAnd, OpOr, OpXor, OpSet) Op <: OpType]
  (implicit @expand.sequence[Op]({_ and _},  {_ or _}, {_ xor _}, { (a,b) => a.clear(); a.or(b)})
  op: Op.InPlaceImpl2[java.util.BitSet, java.util.BitSet]):Op.InPlaceImpl2[BitVector, BitVector] = new Op.InPlaceImpl2[BitVector, BitVector] {
    def apply(a: BitVector, b: BitVector) {
      if(!a.lengthsMatch(b)) throw new IllegalArgumentException(s"Lengths don't match: ${a.length} ${b.length}")
      op(a.data, b.data)
    }
  }

  @expand
  @expand.valify
  implicit def bv_bv_Op[@expand.args(OpAnd, OpOr, OpXor) Op <: OpType]
  (implicit @expand.sequence[Op]({_ and _},  {_ or _}, {_ xor _})
  op: Op.InPlaceImpl2[java.util.BitSet, java.util.BitSet]):Op.Impl2[BitVector, BitVector, BitVector] = new Op.Impl2[BitVector, BitVector, BitVector] {
    def apply(a: BitVector, b: BitVector) = {
      if(!a.lengthsMatch(b)) throw new IllegalArgumentException(s"Lengths don't match: ${a.length} ${b.length}")
      val result = a.data.clone().asInstanceOf[util.BitSet]
      op(result, b.data)
      new BitVector(result, a.length max b.length, a.enforceLength && b.enforceLength)
    }
  }


  implicit val bv_OpNot:OpNot.Impl[BitVector, BitVector] = new OpNot.Impl[BitVector, BitVector] {
    def apply(a: BitVector): BitVector = {
      val ones = BitVector.ones(a.length, a.enforceLength)
      ones.data.andNot(a.data)
      ones
    }
  }


  implicit val bv_bv_OpNe:OpNe.Impl2[BitVector, BitVector, BitVector] = new OpNe.Impl2[BitVector, BitVector, BitVector] {
    def apply(a: BitVector, b: BitVector): BitVector = {
      a ^^ b
    }
  }

  implicit val bv_bv_OpEq:OpEq.Impl2[BitVector, BitVector, BitVector] = new OpEq.Impl2[BitVector, BitVector, BitVector] {
    def apply(a: BitVector, b: BitVector): BitVector = {
      if(!a.lengthsMatch(b)) throw new IllegalArgumentException(s"Lengths don't match: ${a.length} ${b.length}")
      !(a :!= b)
    }
  }


  @expand
  implicit def axpy[@expand.args(Int, Double, Float, Long, BigInt, Complex) V, Vec](implicit ev: Vec <:< Vector[V]): CanAxpy[V, BitVector, Vec] = {
    new CanAxpy[V, BitVector, Vec] {
      def apply(s: V, b: BitVector, a: Vec) {
        require(b.lengthsMatch(a), "Vectors must be the same length!")
        val bd = b.data
        var i= bd.nextSetBit(0)
        while(i >= 0) {
          a(i) += s
          i = bd.nextSetBit(i+1)
        }
      }
    }
  }

  implicit val canDot_BV_BV: OpMulInner.Impl2[BitVector, BitVector, Boolean] = {
    new breeze.linalg.operators.OpMulInner.Impl2[BitVector, BitVector, Boolean] {
      def apply(a: BitVector, b: BitVector): Boolean = {
        require(a.lengthsMatch(b), "Vectors must be the same length!")
        a.data intersects b.data
      }
    }
  }


  @expand
  @expand.valify
  implicit def canDot_BV_DenseVector[@expand.args(Int, Long, BigInt, Complex) T](implicit @expand.sequence[T](0, 0l, BigInt(0), Complex.zero) zero: T): breeze.linalg.operators.OpMulInner.Impl2[BitVector, DenseVector[T], T] = {
    new breeze.linalg.operators.OpMulInner.Impl2[BitVector, DenseVector[T], T] {
      def apply(a: BitVector, b: DenseVector[T]) = {
        val ad = a.data
        val boff = b.offset
        val bd = b.data
        val bstride = b.stride
        var result : T = zero

        var i= ad.nextSetBit(0)
        while(i >= 0) {
          result += bd(boff + bstride * i)
          i = ad.nextSetBit(i+1)
        }
        result

      }
//      implicitly[BinaryRegistry[Vector[T], Vector[T], OpMulInner, T]].register(this)
    }
  }

  @expand
  @expand.valify
  implicit def canDot_BV_SV[@expand.args(Int, Long, BigInt, Complex) T](implicit @expand.sequence[T](0, 0l, BigInt(0), Complex.zero) zero: T): breeze.linalg.operators.OpMulInner.Impl2[BitVector, SparseVector[T], T] = {
    new breeze.linalg.operators.OpMulInner.Impl2[BitVector, SparseVector[T], T] {
      def apply(a: BitVector, b: SparseVector[T]):T = {
        require(a.lengthsMatch(b), "Vectors must be the same length!")
        if(b.activeSize == 0) return zero

        val ad = a.data
        var boff = 0
        val bindex = b.index
        val bd = b.data
        var result : T = zero
        while(boff < b.activeSize) {
          if(ad.get(b.indexAt(boff)))
            result += b.valueAt(boff)
          boff += 1
        }
        result

      }
      //      implicitly[BinaryRegistry[Vector[T], Vector[T], OpMulInner, T]].register(this)
    }
  }

  implicit def canDot_Other_BV[T, Other](implicit op: OpMulInner.Impl2[BitVector, Other, T]):OpMulInner.Impl2[Other, BitVector, T] = {
    new OpMulInner.Impl2[Other, BitVector, T] {
      def apply(a: Other, b: BitVector) = {
        op(b,a)
      }
    }
  }
}