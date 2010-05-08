/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.mahout.math;

import java.util.Iterator;
import java.util.NoSuchElementException;

import org.apache.mahout.math.function.Functions;

/**
 * <p>
 * Implements vector that only stores non-zero doubles as a pair of parallel arrays (OrderedIntDoubleMapping),
 * one int[], one double[].  If there are <b>k</b> non-zero elements in the vector, this implementation has
 * O(log(k)) random-access read performance, and O(k) random-access write performance, which is far below that
 * of the hashmap based {@link org.apache.mahout.math.RandomAccessSparseVector RandomAccessSparseVector}.  This
 * class is primarily used for operations where the all the elements will be accessed in a read-only fashion
 * sequentially: methods which operate not via get() or set(), but via iterateNonZero(), such as (but not limited
 * to) :</p>
 * <ul>
 *   <li>dot(Vector)</li>
 *   <li>addTo(Vector)</li>
 * </ul>
 * <p>
 * Note that the Vector passed to these above methods may (and currently, are) be used in a random access fashion,
 * so for example, calling SequentialAccessSparseVector.dot(SequentialAccessSparseVector) is slow.
 * TODO: this need not be the case - both are ordered, so this should be very fast if implmented in this class
 * </p>
 *
 * {@see OrderedIntDoubleMapping}
 */
public class SequentialAccessSparseVector extends AbstractVector {

  private OrderedIntDoubleMapping values;

  /** For serialization purposes only. */
  public SequentialAccessSparseVector() {
    super(0);
  }

  public SequentialAccessSparseVector(int cardinality) {
    this(cardinality, cardinality / 8); // arbitrary estimate of 'sparseness'
  }

  public SequentialAccessSparseVector(int cardinality, int size) {
    super(cardinality);
    values = new OrderedIntDoubleMapping(size);
  }

  public SequentialAccessSparseVector(Vector other) {
    this(other.size(), other.getNumNondefaultElements());
    Iterator<Element> it = other.iterateNonZero();
    Element e;
    while(it.hasNext() && (e = it.next()) != null) {
      set(e.index(), e.get());
    }
  }

  public SequentialAccessSparseVector(SequentialAccessSparseVector other, boolean shallowCopy) {
    super(other.size());
    values = shallowCopy ? other.values : other.values.clone();
  }

  public SequentialAccessSparseVector(SequentialAccessSparseVector other) {
    this(other.size(), other.getNumNondefaultElements());
    values = other.values.clone();
  }

  private SequentialAccessSparseVector(int cardinality, OrderedIntDoubleMapping values) {
    super(cardinality);
    this.values = values;
  }

  @Override
  protected Matrix matrixLike(int rows, int columns) {
    int[] cardinality = {rows, columns};
    return new SparseRowMatrix(cardinality);
  }

  @Override
  public SequentialAccessSparseVector clone() {
    return new SequentialAccessSparseVector(size(), values.clone());
  }

  /**
   * @return false
   */
  public boolean isDense() {
    return false;
  }

  /**
   * @return true
   */
  public boolean isSequentialAccess() {
    return true;
  }

  public double getQuick(int index) {
    return values.get(index);
  }

  public void setQuick(int index, double value) {
    lengthSquared = -1;
    values.set(index, value);
  }

  public int getNumNondefaultElements() {
    return values.getNumMappings();
  }

  public SequentialAccessSparseVector like() {
    return new SequentialAccessSparseVector(size(), values.getNumMappings());
  }

  public Iterator<Element> iterateNonZero() {
    return new NonDefaultIterator();
  }

  public Iterator<Element> iterator() {
    return new AllIterator();
  }
    
  @Override
  public double dot(Vector x) {
    if (size() != x.size()) {
      throw new CardinalityException(size(), x.size());
    }
    if (this == x) {
      return dotSelf();
    }
    
    double result = 0;
    if (x instanceof SequentialAccessSparseVector) {
      // For sparse SeqAccVectors. do dot product without lookup in a linear fashion
      Iterator<Element> myIter = iterateNonZero();
      Iterator<Element> otherIter = x.iterateNonZero();
      Element myCurrent = null;
      Element otherCurrent = null;
      while (myIter.hasNext() && otherIter.hasNext()) {
        if (myCurrent == null) {
          myCurrent = myIter.next();
        }
        if (otherCurrent == null) {
          otherCurrent = otherIter.next();
        }

        int myIndex = myCurrent.index();
        int otherIndex = otherCurrent.index();
        
        if (myIndex < otherIndex) {
          // due to the sparseness skipping occurs more hence checked before equality
          myCurrent = null;
        } else if (myIndex > otherIndex){
          otherCurrent = null;
        } else { // both are equal 
          result += myCurrent.get() * otherCurrent.get();
          myCurrent = null;
          otherCurrent = null;
        } 
      }
      return result;
    } else { // seq.rand. seq.dense
      Iterator<Element> iter = iterateNonZero();
      while (iter.hasNext()) {
        Element element = iter.next();
        result += element.get() * x.getQuick(element.index());
      }
      return result;
    }
  }

  @Override
  public Vector minus(Vector that) {
    if (size() != that.size()) {
      throw new CardinalityException(size(), that.size());
    }
    // Here we compute "that - this" since it's not fast to randomly access "this"
    // and then invert at the end
    Vector result = that.clone();
    Iterator<Element> iter = this.iterateNonZero();
    while (iter.hasNext()) {
      Element thisElement = iter.next();
      int index = thisElement.index();
      result.setQuick(index, that.getQuick(index) - thisElement.get());
    }
    result.assign(Functions.negate);
    return result;
  }


  private final class NonDefaultIterator implements Iterator<Element> {

    private final NonDefaultElement element = new NonDefaultElement();

    public boolean hasNext() {
      return element.getNextOffset() < values.getNumMappings();
    }

    public Element next() {
      if (element.getNextOffset() >= values.getNumMappings()) {
        throw new NoSuchElementException();
      } else {
        element.advanceOffset();
        return element;
      }
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  private final class AllIterator implements Iterator<Element> {

    private final AllElement element = new AllElement();

    public boolean hasNext() {
      return element.getNextIndex() < values.getIndices()[values.getNumMappings()-1];
    }

    public Element next() {
      if (element.getNextIndex() >= values.getIndices()[values.getNumMappings()-1]) {
        throw new NoSuchElementException();
      } else {
        element.advanceIndex();
        return element;
      }
    }

    public void remove() {
      throw new UnsupportedOperationException();
    }
  }

  private final class NonDefaultElement implements Element {

    private int offset = -1;

    void advanceOffset() {
      offset++;
    }

    int getNextOffset() {
      return offset + 1;
    }

    public double get() {
      return values.getValues()[offset];
    }

    public int index() {
      return values.getIndices()[offset];
    }

    public void set(double value) {
      lengthSquared = -1;      
      values.getValues()[offset] = value;
    }
  }

  private final class AllElement implements Element {

    private int index = -1;
    private int nextOffset = 0;

    void advanceIndex() {
      index++;
      if (index > values.getIndices()[nextOffset]) {
        nextOffset++;
      }
    }

    int getNextIndex() {
      return index + 1;
    }

    public double get() {
      if (index == values.getIndices()[nextOffset]) {
        return values.getValues()[nextOffset];
      }
      return OrderedIntDoubleMapping.DEFAULT_VALUE;
    }

    public int index() {
      return index;
    }

    public void set(double value) {
      if (index == values.getIndices()[nextOffset]) {
        values.getValues()[nextOffset] = value;
      } else {
        // Yes, this works; the offset into indices of the new value's index will still be nextOffset
        values.set(index, value);
      }
    }
  }
  
}
