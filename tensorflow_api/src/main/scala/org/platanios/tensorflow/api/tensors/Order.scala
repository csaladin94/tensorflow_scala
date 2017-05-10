package org.platanios.tensorflow.api.tensors

import scala.annotation.tailrec

/**
  * @author Emmanouil Antonios Platanios
  */
private[api] sealed trait Order {
  def index(dimensions: Array[Int], starts: Array[Int], strides: Array[Int], indices: Array[Int]): Int
  def indexIterator(dimensions: Array[Int], starts: Array[Int], ends: Array[Int], strides: Array[Int]): Iterator[Int]
}

private[api] object RowMajorOrder extends Order {
  override def index(dimensions: Array[Int], starts: Array[Int], strides: Array[Int], indices: Array[Int]): Int = {
    var index: Int = 0
    var dimension: Int = 0
    while (dimension < dimensions.length) {
      var sizesProduct: Int = 1
      var k: Int = dimension + 1
      while (k < dimensions.length) {
        sizesProduct *= dimensions(k)
        k += 1
      }
      index += sizesProduct * (starts(dimension) + indices(dimension) * strides(dimension))
      dimension += 1
    }
    index
  }

  override def indexIterator(
      dimensions: Array[Int], starts: Array[Int], ends: Array[Int], strides: Array[Int]): Iterator[Int] = {
    if (dimensions.length > 0) {
      new Iterator[Int] {
        private val dimCount: Array[Int] = starts.clone()
        private val dimSizes: Array[Int] = dimensions.scanRight(1)(_ * _).takeRight(dimensions.length)
        private var dim     : Int        = dimensions.length - 1
        private var index   : Int        = {
          var i = 0
          var sum = 0
          while (i < dimensions.length) {
            sum += starts(i) * dimSizes(i)
            i += 1
          }
          sum
        }

        override def hasNext: Boolean = dimCount.head < ends.head

        @tailrec
        override def next(): Int = {
          if (dim < dimensions.length - 1 && dimCount(dim) < ends(dim)) {
            dim += 1
            next()
          } else if (dimCount(dim) < ends(dim)) {
            val nextIndex = index
            dimCount(dim) += strides(dim)
            index += strides(dim)
            while (dim > 0 && dimCount(dim) >= ends(dim)) {
              index += dimSizes(dim) * (strides(dim - 1) * dimensions(dim) - dimCount(dim) + starts(dim))
              dimCount(dim) = starts(dim)
              dim -= 1
              dimCount(dim) += strides(dim)
            }
            nextIndex
          } else {
            throw new NoSuchElementException("This flattened index iterator has reached its end.")
          }
        }
      }
    } else {
      Iterator.range(0, 1)
    }
  }
}

private[api] object ColumnMajorOrder extends Order {
  override def index(dimensions: Array[Int], starts: Array[Int], strides: Array[Int], indices: Array[Int]): Int = {
    var index: Int = 0
    var dimension: Int = 0
    while (dimension < dimensions.length) {
      var sizesProduct: Int = 1
      var k: Int = 0
      while (k < dimension) {
        sizesProduct *= dimensions(k)
        k += 1
      }
      index += sizesProduct * (starts(dimension) + indices(dimension) * strides(dimension))
      dimension += 1
    }
    index
  }

  override def indexIterator(
      dimensions: Array[Int], starts: Array[Int], ends: Array[Int], strides: Array[Int]): Iterator[Int] = {
    if (dimensions.length > 0) {
      new Iterator[Int] {
        private val dimCount: Array[Int] = starts.clone()
        private val dimSizes: Array[Int] = dimensions.scanLeft(1)(_ * _).take(dimensions.length)
        private var dim     : Int        = 0
        private var index   : Int        = starts.head * dimSizes.head

        override def hasNext: Boolean = dimCount.head < ends.head

        @tailrec
        override def next(): Int = {
          if (dim > 0 && dimCount(dim) < ends(dim)) {
            dim -= 1
            next()
          } else if (dimCount(dim) < ends(dim)) {
            val nextIndex = index
            dimCount(dim) += strides(dim)
            index += strides(dim)
            while (dim < dimensions.length - 1 && dimCount(dim) >= ends(dim)) {
              index += dimSizes(dim) * (strides(dim + 1) * dimensions(dim) - dimCount(dim) + starts(dim))
              dimCount(dim) = starts(dim)
              dim += 1
              dimCount(dim) += strides(dim)
            }
            nextIndex
          } else {
            throw new NoSuchElementException("This flattened index iterator has reached its end.")
          }
        }
      }
    } else {
      Iterator.range(0, 1)
    }
  }
}
