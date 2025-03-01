/*
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
package org.apache.lucene.util.bkd;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;
import org.apache.lucene.codecs.MutablePointTree;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.PointValues;
import org.apache.lucene.index.PointValues.IntersectVisitor;
import org.apache.lucene.index.PointValues.Relation;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.tests.mockfile.ExtrasFS;
import org.apache.lucene.tests.store.CorruptingIndexOutput;
import org.apache.lucene.tests.store.MockDirectoryWrapper;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.IOUtils;
import org.apache.lucene.util.NumericUtils;

public class TestBKD extends LuceneTestCase {

  protected PointValues getPointValues(IndexInput in) throws IOException {
    return new BKDReader(in, in, in);
  }

  public void testBasicInts1D() throws Exception {
    final BKDConfig config = new BKDConfig(1, 1, 4, 2);
    try (Directory dir = getDirectory(100)) {
      BKDWriter w = new BKDWriter(100, dir, "tmp", config, 1.0f, 100);
      byte[] scratch = new byte[4];
      for (int docID = 0; docID < 100; docID++) {
        NumericUtils.intToSortableBytes(docID, scratch, 0);
        w.add(scratch, docID);
      }

      long indexFP;
      try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
        Runnable finalizer = w.finish(out, out, out);
        indexFP = out.getFilePointer();
        finalizer.run();
      }

      try (IndexInput in = dir.openInput("bkd", IOContext.DEFAULT)) {
        in.seek(indexFP);
        PointValues r = getPointValues(in);

        // Simple 1D range query:
        final byte[][] queryMin = new byte[1][4];
        NumericUtils.intToSortableBytes(42, queryMin[0], 0);
        final byte[][] queryMax = new byte[1][4];
        NumericUtils.intToSortableBytes(87, queryMax[0], 0);

        final BitSet hits = new BitSet();
        r.intersect(getIntersectVisitor(hits, queryMin, queryMax, config));

        for (int docID = 0; docID < 100; docID++) {
          boolean expected = docID >= 42 && docID <= 87;
          boolean actual = hits.get(docID);
          assertEquals("docID=" + docID, expected, actual);
        }
      }
    }
  }

  public void testRandomIntsNDims() throws Exception {
    int numDocs = atLeast(1000);
    try (Directory dir = getDirectory(numDocs)) {
      int numDims = TestUtil.nextInt(random(), 1, 5);
      int numIndexDims = TestUtil.nextInt(random(), 1, numDims);
      int maxPointsInLeafNode = TestUtil.nextInt(random(), 50, 100);
      float maxMB = (float) 3.0 + (3 * random().nextFloat());
      BKDConfig config = new BKDConfig(numDims, numIndexDims, 4, maxPointsInLeafNode);
      BKDWriter w = new BKDWriter(numDocs, dir, "tmp", config, maxMB, numDocs);

      if (VERBOSE) {
        System.out.println(
            "TEST: numDims=" + numDims + " numIndexDims=" + numIndexDims + " numDocs=" + numDocs);
      }
      int[][] docs = new int[numDocs][];
      byte[] scratch = new byte[4 * numDims];
      int[] minValue = new int[numDims];
      int[] maxValue = new int[numDims];
      Arrays.fill(minValue, Integer.MAX_VALUE);
      Arrays.fill(maxValue, Integer.MIN_VALUE);
      for (int docID = 0; docID < numDocs; docID++) {
        int[] values = new int[numDims];
        if (VERBOSE) {
          System.out.println("  docID=" + docID);
        }
        for (int dim = 0; dim < numDims; dim++) {
          values[dim] = random().nextInt();
          if (values[dim] < minValue[dim]) {
            minValue[dim] = values[dim];
          }
          if (values[dim] > maxValue[dim]) {
            maxValue[dim] = values[dim];
          }
          NumericUtils.intToSortableBytes(values[dim], scratch, dim * Integer.BYTES);
          if (VERBOSE) {
            System.out.println("    " + dim + " -> " + values[dim]);
          }
        }
        docs[docID] = values;
        w.add(scratch, docID);
      }

      long indexFP;
      try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
        Runnable finalizer = w.finish(out, out, out);
        indexFP = out.getFilePointer();
        finalizer.run();
      }

      try (IndexInput in = dir.openInput("bkd", IOContext.DEFAULT)) {
        in.seek(indexFP);
        PointValues r = getPointValues(in);

        byte[] minPackedValue = r.getMinPackedValue();
        byte[] maxPackedValue = r.getMaxPackedValue();
        for (int dim = 0; dim < numIndexDims; dim++) {
          assertEquals(
              minValue[dim], NumericUtils.sortableBytesToInt(minPackedValue, dim * Integer.BYTES));
          assertEquals(
              maxValue[dim], NumericUtils.sortableBytesToInt(maxPackedValue, dim * Integer.BYTES));
        }

        int iters = atLeast(100);
        for (int iter = 0; iter < iters; iter++) {
          if (VERBOSE) {
            System.out.println("\nTEST: iter=" + iter);
          }

          // Random N dims rect query:
          int[] queryMin = new int[numDims];
          byte[][] queryMinBytes = new byte[numDims][4];
          int[] queryMax = new int[numDims];
          byte[][] queryMaxBytes = new byte[numDims][4];
          for (int dim = 0; dim < numIndexDims; dim++) {
            queryMin[dim] = random().nextInt();
            queryMax[dim] = random().nextInt();
            if (queryMin[dim] > queryMax[dim]) {
              int x = queryMin[dim];
              queryMin[dim] = queryMax[dim];
              queryMax[dim] = x;
            }
            NumericUtils.intToSortableBytes(queryMin[dim], queryMinBytes[dim], 0);
            NumericUtils.intToSortableBytes(queryMax[dim], queryMaxBytes[dim], 0);
          }

          final BitSet hits = new BitSet();
          r.intersect(getIntersectVisitor(hits, queryMinBytes, queryMaxBytes, config));

          for (int docID = 0; docID < numDocs; docID++) {
            int[] docValues = docs[docID];
            boolean expected = true;
            for (int dim = 0; dim < numIndexDims; dim++) {
              int x = docValues[dim];
              if (x < queryMin[dim] || x > queryMax[dim]) {
                expected = false;
                break;
              }
            }
            boolean actual = hits.get(docID);
            assertEquals("docID=" + docID, expected, actual);
          }
        }
      }
    }
  }

  // Tests on N-dimensional points where each dimension is a BigInteger
  public void testBigIntNDims() throws Exception {

    int numDocs = atLeast(1000);
    try (Directory dir = getDirectory(numDocs)) {
      int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
      int numDims = TestUtil.nextInt(random(), 1, 5);
      int maxPointsInLeafNode = TestUtil.nextInt(random(), 50, 100);
      float maxMB = (float) 3.0 + (3 * random().nextFloat());
      BKDConfig config = new BKDConfig(numDims, numDims, numBytesPerDim, maxPointsInLeafNode);
      BKDWriter w = new BKDWriter(numDocs, dir, "tmp", config, maxMB, numDocs);
      BigInteger[][] docs = new BigInteger[numDocs][];

      byte[] scratch = new byte[numBytesPerDim * numDims];
      for (int docID = 0; docID < numDocs; docID++) {
        BigInteger[] values = new BigInteger[numDims];
        if (VERBOSE) {
          System.out.println("  docID=" + docID);
        }
        for (int dim = 0; dim < numDims; dim++) {
          values[dim] = randomBigInt(numBytesPerDim);
          NumericUtils.bigIntToSortableBytes(
              values[dim], numBytesPerDim, scratch, dim * numBytesPerDim);
          if (VERBOSE) {
            System.out.println("    " + dim + " -> " + values[dim]);
          }
        }
        docs[docID] = values;
        w.add(scratch, docID);
      }

      long indexFP;
      try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
        Runnable finalizer = w.finish(out, out, out);
        indexFP = out.getFilePointer();
        finalizer.run();
      }

      try (IndexInput in = dir.openInput("bkd", IOContext.DEFAULT)) {
        in.seek(indexFP);
        PointValues pointValues = getPointValues(in);

        int iters = atLeast(100);
        for (int iter = 0; iter < iters; iter++) {
          if (VERBOSE) {
            System.out.println("\nTEST: iter=" + iter);
          }

          // Random N dims rect query:
          BigInteger[] queryMin = new BigInteger[numDims];
          byte[][] queryMinBytes = new byte[numDims][numBytesPerDim];
          BigInteger[] queryMax = new BigInteger[numDims];
          byte[][] queryMaxBytes = new byte[numDims][numBytesPerDim];
          for (int dim = 0; dim < numDims; dim++) {
            queryMin[dim] = randomBigInt(numBytesPerDim);
            queryMax[dim] = randomBigInt(numBytesPerDim);
            if (queryMin[dim].compareTo(queryMax[dim]) > 0) {
              BigInteger x = queryMin[dim];
              queryMin[dim] = queryMax[dim];
              queryMax[dim] = x;
            }
            NumericUtils.bigIntToSortableBytes(
                queryMin[dim], numBytesPerDim, queryMinBytes[dim], 0);
            NumericUtils.bigIntToSortableBytes(
                queryMax[dim], numBytesPerDim, queryMaxBytes[dim], 0);
          }

          final BitSet hits = new BitSet();
          pointValues.intersect(getIntersectVisitor(hits, queryMinBytes, queryMaxBytes, config));

          for (int docID = 0; docID < numDocs; docID++) {
            BigInteger[] docValues = docs[docID];
            boolean expected = true;
            for (int dim = 0; dim < numDims; dim++) {
              BigInteger x = docValues[dim];
              if (x.compareTo(queryMin[dim]) < 0 || x.compareTo(queryMax[dim]) > 0) {
                expected = false;
                break;
              }
            }
            boolean actual = hits.get(docID);
            assertEquals("docID=" + docID, expected, actual);
          }
        }
      }
    }
  }

  /** Make sure we close open files, delete temp files, etc., on exception */
  public void testWithExceptions() throws Exception {
    int numDocs = atLeast(10000);
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
    int numDataDims = TestUtil.nextInt(random(), 1, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 1, numDataDims), PointValues.MAX_INDEX_DIMENSIONS);

    byte[][][] docValues = new byte[numDocs][][];

    for (int docID = 0; docID < numDocs; docID++) {
      byte[][] values = new byte[numDataDims][];
      for (int dim = 0; dim < numDataDims; dim++) {
        values[dim] = new byte[numBytesPerDim];
        random().nextBytes(values[dim]);
      }
      docValues[docID] = values;
    }

    double maxMBHeap = 0.05;
    // Keep retrying until we 1) we allow a big enough heap, and 2) we hit a random IOExc from MDW:
    boolean done = false;
    while (done == false) {
      MockDirectoryWrapper dir = newMockFSDirectory(createTempDir());
      try {
        dir.setRandomIOExceptionRate(0.05);
        dir.setRandomIOExceptionRateOnOpen(0.05);
        verify(dir, docValues, null, numDataDims, numIndexDims, numBytesPerDim, 50, maxMBHeap);
      } catch (IllegalArgumentException iae) {
        // This just means we got a too-small maxMB for the maxPointsInLeafNode; just retry w/ more
        // heap
        assertTrue(
            iae.getMessage()
                .contains("either increase maxMBSortInHeap or decrease maxPointsInLeafNode"));
        maxMBHeap *= 1.25;
      } catch (IOException ioe) {
        if (ioe.getMessage().contains("a random IOException")) {
          // BKDWriter should fully clean up after itself:
          done = true;
        } else {
          throw ioe;
        }
      }

      String[] files =
          Arrays.stream(dir.listAll())
              .filter(file -> !ExtrasFS.isExtra(file))
              .toArray(String[]::new);
      assertTrue("files=" + Arrays.toString(files), files.length == 0);
      dir.close();
    }
  }

  public void testRandomBinaryTiny() throws Exception {
    doTestRandomBinary(10);
  }

  public void testRandomBinaryMedium() throws Exception {
    doTestRandomBinary(10000);
  }

  @Nightly
  public void testRandomBinaryBig() throws Exception {
    doTestRandomBinary(200000);
  }

  public void testTooLittleHeap() throws Exception {
    try (Directory dir = getDirectory(0)) {
      IllegalArgumentException expected =
          expectThrows(
              IllegalArgumentException.class,
              () -> {
                new BKDWriter(1, dir, "bkd", new BKDConfig(1, 1, 16, 1000000), 0.001, 0);
              });
      assertTrue(
          expected
              .getMessage()
              .contains("either increase maxMBSortInHeap or decrease maxPointsInLeafNode"));
    }
  }

  private void doTestRandomBinary(int count) throws Exception {
    int numDocs = TestUtil.nextInt(random(), count, count * 2);
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);

    int numDataDims = TestUtil.nextInt(random(), 1, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 1, numDataDims), PointValues.MAX_INDEX_DIMENSIONS);

    byte[][][] docValues = new byte[numDocs][][];

    for (int docID = 0; docID < numDocs; docID++) {
      byte[][] values = new byte[numDataDims][];
      for (int dim = 0; dim < numDataDims; dim++) {
        values[dim] = new byte[numBytesPerDim];
        random().nextBytes(values[dim]);
      }
      docValues[docID] = values;
    }

    verify(docValues, null, numDataDims, numIndexDims, numBytesPerDim);
  }

  public void testAllEqual() throws Exception {
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
    int numDataDims = TestUtil.nextInt(random(), 1, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 1, numDataDims), PointValues.MAX_INDEX_DIMENSIONS);

    int numDocs = atLeast(1000);
    byte[][][] docValues = new byte[numDocs][][];

    for (int docID = 0; docID < numDocs; docID++) {
      if (docID == 0) {
        byte[][] values = new byte[numDataDims][];
        for (int dim = 0; dim < numDataDims; dim++) {
          values[dim] = new byte[numBytesPerDim];
          random().nextBytes(values[dim]);
        }
        docValues[docID] = values;
      } else {
        docValues[docID] = docValues[0];
      }
    }

    verify(docValues, null, numDataDims, numIndexDims, numBytesPerDim);
  }

  public void testIndexDimEqualDataDimDifferent() throws Exception {
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
    int numDataDims = TestUtil.nextInt(random(), 2, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 1, numDataDims - 1), PointValues.MAX_INDEX_DIMENSIONS);

    int numDocs = atLeast(1000);
    byte[][][] docValues = new byte[numDocs][][];

    byte[][] indexDimensions = new byte[numDataDims][];
    for (int dim = 0; dim < numIndexDims; dim++) {
      indexDimensions[dim] = new byte[numBytesPerDim];
      random().nextBytes(indexDimensions[dim]);
    }

    for (int docID = 0; docID < numDocs; docID++) {
      byte[][] values = new byte[numDataDims][];
      for (int dim = 0; dim < numIndexDims; dim++) {
        values[dim] = indexDimensions[dim];
      }
      for (int dim = numIndexDims; dim < numDataDims; dim++) {
        values[dim] = new byte[numBytesPerDim];
        random().nextBytes(values[dim]);
      }
      docValues[docID] = values;
    }

    verify(docValues, null, numDataDims, numIndexDims, numBytesPerDim);
  }

  public void testOneDimEqual() throws Exception {
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
    int numDataDims = TestUtil.nextInt(random(), 1, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 1, numDataDims), PointValues.MAX_INDEX_DIMENSIONS);

    int numDocs = atLeast(1000);
    int theEqualDim = random().nextInt(numDataDims);
    byte[][][] docValues = new byte[numDocs][][];

    for (int docID = 0; docID < numDocs; docID++) {
      byte[][] values = new byte[numDataDims][];
      for (int dim = 0; dim < numDataDims; dim++) {
        values[dim] = new byte[numBytesPerDim];
        random().nextBytes(values[dim]);
      }
      docValues[docID] = values;
      if (docID > 0) {
        docValues[docID][theEqualDim] = docValues[0][theEqualDim];
      }
    }

    // Use a small number of points in leaf blocks to trigger a lot of splitting
    verify(
        docValues,
        null,
        numDataDims,
        numIndexDims,
        numBytesPerDim,
        TestUtil.nextInt(random(), 20, 50));
  }

  // This triggers the logic that makes sure all dimensions get indexed
  // by looking at how many times each dim has been split
  public void testOneDimLowCard() throws Exception {
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
    int numDataDims = TestUtil.nextInt(random(), 2, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 2, numDataDims), PointValues.MAX_INDEX_DIMENSIONS);

    int numDocs = atLeast(10000);
    int theLowCardDim = random().nextInt(numDataDims);

    byte[] value1 = new byte[numBytesPerDim];
    random().nextBytes(value1);
    byte[] value2 = value1.clone();
    if (value2[numBytesPerDim - 1] == 0 || random().nextBoolean()) {
      value2[numBytesPerDim - 1]++;
    } else {
      value2[numBytesPerDim - 1]--;
    }

    byte[][][] docValues = new byte[numDocs][][];

    for (int docID = 0; docID < numDocs; docID++) {
      byte[][] values = new byte[numDataDims][];
      for (int dim = 0; dim < numDataDims; dim++) {
        if (dim == theLowCardDim) {
          values[dim] = random().nextBoolean() ? value1 : value2;
        } else {
          values[dim] = new byte[numBytesPerDim];
          random().nextBytes(values[dim]);
        }
      }
      docValues[docID] = values;
    }

    // Use a small number of points in leaf blocks to trigger a lot of splitting
    verify(
        docValues,
        null,
        numDataDims,
        numIndexDims,
        numBytesPerDim,
        TestUtil.nextInt(random(), 20, 50));
  }

  // this should trigger run-length compression with lengths that are greater than 255
  public void testOneDimTwoValues() throws Exception {
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
    int numDataDims = TestUtil.nextInt(random(), 1, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 1, numDataDims), PointValues.MAX_INDEX_DIMENSIONS);

    int numDocs = atLeast(1000);
    int theDim = random().nextInt(numDataDims);
    byte[] value1 = new byte[numBytesPerDim];
    random().nextBytes(value1);
    byte[] value2 = new byte[numBytesPerDim];
    random().nextBytes(value2);
    byte[][][] docValues = new byte[numDocs][][];

    for (int docID = 0; docID < numDocs; docID++) {
      byte[][] values = new byte[numDataDims][];
      for (int dim = 0; dim < numDataDims; dim++) {
        if (dim == theDim) {
          values[dim] = random().nextBoolean() ? value1 : value2;
        } else {
          values[dim] = new byte[numBytesPerDim];
          random().nextBytes(values[dim]);
        }
      }
      docValues[docID] = values;
    }

    verify(docValues, null, numDataDims, numIndexDims, numBytesPerDim);
  }

  // this should trigger low cardinality leaves
  public void testRandomFewDifferentValues() throws Exception {
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
    int numDataDims = TestUtil.nextInt(random(), 1, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 1, numDataDims), PointValues.MAX_INDEX_DIMENSIONS);

    int numDocs = atLeast(10000);
    int cardinality = TestUtil.nextInt(random(), 2, 100);
    byte[][][] values = new byte[cardinality][numDataDims][numBytesPerDim];
    for (int i = 0; i < cardinality; i++) {
      for (int j = 0; j < numDataDims; j++) {
        random().nextBytes(values[i][j]);
      }
    }

    byte[][][] docValues = new byte[numDocs][][];
    for (int docID = 0; docID < numDocs; docID++) {
      docValues[docID] = values[random().nextInt(cardinality)];
    }

    verify(docValues, null, numDataDims, numIndexDims, numBytesPerDim);
  }

  public void testMultiValued() throws Exception {
    int numBytesPerDim = TestUtil.nextInt(random(), 2, 30);
    int numDataDims = TestUtil.nextInt(random(), 1, PointValues.MAX_DIMENSIONS);
    int numIndexDims =
        Math.min(TestUtil.nextInt(random(), 1, numDataDims), PointValues.MAX_INDEX_DIMENSIONS);

    int numDocs = atLeast(1000);
    List<byte[][]> docValues = new ArrayList<>();
    List<Integer> docIDs = new ArrayList<>();

    for (int docID = 0; docID < numDocs; docID++) {
      int numValuesInDoc = TestUtil.nextInt(random(), 1, 5);
      for (int ord = 0; ord < numValuesInDoc; ord++) {
        docIDs.add(docID);
        byte[][] values = new byte[numDataDims][];
        for (int dim = 0; dim < numDataDims; dim++) {
          values[dim] = new byte[numBytesPerDim];
          random().nextBytes(values[dim]);
        }
        docValues.add(values);
      }
    }

    byte[][][] docValuesArray = docValues.toArray(new byte[docValues.size()][][]);
    int[] docIDsArray = new int[docIDs.size()];
    for (int i = 0; i < docIDsArray.length; i++) {
      docIDsArray[i] = docIDs.get(i);
    }

    verify(docValuesArray, docIDsArray, numDataDims, numIndexDims, numBytesPerDim);
  }

  /** docIDs can be null, for the single valued case, else it maps value to docID */
  private void verify(
      byte[][][] docValues, int[] docIDs, int numDataDims, int numIndexDims, int numBytesPerDim)
      throws Exception {
    verify(
        docValues,
        docIDs,
        numDataDims,
        numIndexDims,
        numBytesPerDim,
        TestUtil.nextInt(random(), 50, 1000));
  }

  private void verify(
      byte[][][] docValues,
      int[] docIDs,
      int numDataDims,
      int numIndexDims,
      int numBytesPerDim,
      int maxPointsInLeafNode)
      throws Exception {
    try (Directory dir = getDirectory(docValues.length)) {
      double maxMB = (float) 3.0 + (3 * random().nextDouble());
      verify(
          dir,
          docValues,
          docIDs,
          numDataDims,
          numIndexDims,
          numBytesPerDim,
          maxPointsInLeafNode,
          maxMB);
    }
  }

  private void verify(
      Directory dir,
      byte[][][] docValues,
      int[] docIDs,
      int numDataDims,
      int numIndexDims,
      int numBytesPerDim,
      int maxPointsInLeafNode,
      double maxMB)
      throws Exception {
    int numValues = docValues.length;
    if (VERBOSE) {
      System.out.println(
          "TEST: numValues="
              + numValues
              + " numDataDims="
              + numDataDims
              + " numIndexDims="
              + numIndexDims
              + " numBytesPerDim="
              + numBytesPerDim
              + " maxPointsInLeafNode="
              + maxPointsInLeafNode
              + " maxMB="
              + maxMB);
    }

    List<Long> toMerge = null;
    List<MergeState.DocMap> docMaps = null;
    int seg = 0;
    // we force sometimes to provide a bigger  point count
    long maxDocs = Long.MIN_VALUE;
    if (random().nextBoolean()) {
      maxDocs = docValues.length;
    } else {
      while (maxDocs < docValues.length) {
        maxDocs = random().nextLong();
      }
    }
    BKDWriter w =
        new BKDWriter(
            numValues,
            dir,
            "_" + seg,
            new BKDConfig(numDataDims, numIndexDims, numBytesPerDim, maxPointsInLeafNode),
            maxMB,
            maxDocs);
    IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT);
    IndexInput in = null;

    boolean success = false;

    try {

      byte[] scratch = new byte[numBytesPerDim * numDataDims];
      int lastDocIDBase = 0;
      boolean useMerge = numDataDims == 1 && numValues >= 10 && random().nextBoolean();
      int valuesInThisSeg;
      if (useMerge) {
        // Sometimes we will call merge with a single segment:
        valuesInThisSeg = TestUtil.nextInt(random(), numValues / 10, numValues);
      } else {
        valuesInThisSeg = 0;
      }

      int segCount = 0;

      for (int ord = 0; ord < numValues; ord++) {
        int docID;
        if (docIDs == null) {
          docID = ord;
        } else {
          docID = docIDs[ord];
        }
        if (VERBOSE) {
          System.out.println(
              "  ord=" + ord + " docID=" + docID + " lastDocIDBase=" + lastDocIDBase);
        }
        for (int dim = 0; dim < numDataDims; dim++) {
          if (VERBOSE) {
            System.out.println("    " + dim + " -> " + new BytesRef(docValues[ord][dim]));
          }
          System.arraycopy(docValues[ord][dim], 0, scratch, dim * numBytesPerDim, numBytesPerDim);
        }
        w.add(scratch, docID - lastDocIDBase);

        segCount++;

        if (useMerge && segCount == valuesInThisSeg) {
          if (toMerge == null) {
            toMerge = new ArrayList<>();
            docMaps = new ArrayList<>();
          }
          final int curDocIDBase = lastDocIDBase;
          docMaps.add(
              new MergeState.DocMap() {
                @Override
                public int get(int docID) {
                  return curDocIDBase + docID;
                }
              });
          Runnable finalizer = w.finish(out, out, out);
          toMerge.add(out.getFilePointer());
          finalizer.run();
          valuesInThisSeg = TestUtil.nextInt(random(), numValues / 10, numValues / 2);
          segCount = 0;

          seg++;
          maxPointsInLeafNode = TestUtil.nextInt(random(), 50, 1000);
          maxMB = (float) 3.0 + (3 * random().nextDouble());
          w =
              new BKDWriter(
                  numValues,
                  dir,
                  "_" + seg,
                  new BKDConfig(numDataDims, numIndexDims, numBytesPerDim, maxPointsInLeafNode),
                  maxMB,
                  docValues.length);
          lastDocIDBase = docID;
        }
      }

      long indexFP;

      if (toMerge != null) {
        if (segCount > 0) {
          Runnable finalizer = w.finish(out, out, out);
          toMerge.add(out.getFilePointer());
          finalizer.run();
          final int curDocIDBase = lastDocIDBase;
          docMaps.add(
              new MergeState.DocMap() {
                @Override
                public int get(int docID) {
                  return curDocIDBase + docID;
                }
              });
        }
        out.close();
        in = dir.openInput("bkd", IOContext.DEFAULT);
        seg++;
        w =
            new BKDWriter(
                numValues,
                dir,
                "_" + seg,
                new BKDConfig(numDataDims, numIndexDims, numBytesPerDim, maxPointsInLeafNode),
                maxMB,
                docValues.length);
        List<PointValues> readers = new ArrayList<>();
        for (long fp : toMerge) {
          in.seek(fp);
          readers.add(getPointValues(in));
        }
        out = dir.createOutput("bkd2", IOContext.DEFAULT);
        Runnable finalizer = w.merge(out, out, out, docMaps, readers);
        indexFP = out.getFilePointer();
        finalizer.run();
        out.close();
        in.close();
        in = dir.openInput("bkd2", IOContext.DEFAULT);
      } else {
        Runnable finalizer = w.finish(out, out, out);
        indexFP = out.getFilePointer();
        finalizer.run();
        out.close();
        in = dir.openInput("bkd", IOContext.DEFAULT);
      }

      in.seek(indexFP);
      PointValues pointValues = getPointValues(in);

      assertSize(pointValues.getPointTree());

      int iters = atLeast(100);
      for (int iter = 0; iter < iters; iter++) {
        if (VERBOSE) {
          System.out.println("\nTEST: iter=" + iter);
        }

        // Random N dims rect query:
        byte[][] queryMin = new byte[numDataDims][];
        byte[][] queryMax = new byte[numDataDims][];
        for (int dim = 0; dim < numDataDims; dim++) {
          queryMin[dim] = new byte[numBytesPerDim];
          random().nextBytes(queryMin[dim]);
          queryMax[dim] = new byte[numBytesPerDim];
          random().nextBytes(queryMax[dim]);
          if (Arrays.compareUnsigned(
                  queryMin[dim], 0, numBytesPerDim, queryMax[dim], 0, numBytesPerDim)
              > 0) {
            byte[] x = queryMin[dim];
            queryMin[dim] = queryMax[dim];
            queryMax[dim] = x;
          }
        }

        BitSet expected = new BitSet();
        for (int ord = 0; ord < numValues; ord++) {
          boolean matches = true;
          for (int dim = 0; dim < numIndexDims; dim++) {
            byte[] x = docValues[ord][dim];
            if (Arrays.compareUnsigned(x, 0, numBytesPerDim, queryMin[dim], 0, numBytesPerDim) < 0
                || Arrays.compareUnsigned(x, 0, numBytesPerDim, queryMax[dim], 0, numBytesPerDim)
                    > 0) {
              matches = false;
              break;
            }
          }

          if (matches) {
            int docID;
            if (docIDs == null) {
              docID = ord;
            } else {
              docID = docIDs[ord];
            }
            expected.set(docID);
          }
        }

        BKDConfig config =
            new BKDConfig(numDataDims, numIndexDims, numBytesPerDim, maxPointsInLeafNode);
        final BitSet hits = new BitSet();
        pointValues.intersect(getIntersectVisitor(hits, queryMin, queryMax, config));
        assertHits(hits, expected);

        hits.clear();
        pointValues
            .getPointTree()
            .visitDocValues(getIntersectVisitor(hits, queryMin, queryMax, config));
        assertHits(hits, expected);
      }
      in.close();
      dir.deleteFile("bkd");
      if (toMerge != null) {
        dir.deleteFile("bkd2");
      }
      success = true;
    } finally {
      if (success == false) {
        IOUtils.closeWhileHandlingException(w, in, out);
        IOUtils.deleteFilesIgnoringExceptions(dir, "bkd", "bkd2");
      }
    }
  }

  private void assertSize(PointValues.PointTree tree) throws IOException {
    final PointValues.PointTree clone = tree.clone();
    assertEquals(clone.size(), tree.size());
    // rarely continue with the clone tree
    tree = rarely() ? clone : tree;
    final long[] visitDocIDSize = new long[] {0};
    final long[] visitDocValuesSize = new long[] {0};
    final IntersectVisitor visitor =
        new IntersectVisitor() {
          @Override
          public void visit(int docID) {
            visitDocIDSize[0]++;
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            visitDocValuesSize[0]++;
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return Relation.CELL_CROSSES_QUERY;
          }
        };
    if (random().nextBoolean()) {
      tree.visitDocIDs(visitor);
      tree.visitDocValues(visitor);
    } else {
      tree.visitDocValues(visitor);
      tree.visitDocIDs(visitor);
    }
    assertEquals(visitDocIDSize[0], visitDocValuesSize[0]);
    assertEquals(visitDocIDSize[0], tree.size());
    if (tree.moveToChild()) {
      do {
        randomPointTreeNavigation(tree);
        assertSize(tree);
      } while (tree.moveToSibling());
      tree.moveToParent();
    }
  }

  private void randomPointTreeNavigation(PointValues.PointTree tree) throws IOException {
    byte[] minPackedValue = tree.getMinPackedValue().clone();
    byte[] maxPackedValue = tree.getMaxPackedValue().clone();
    long size = tree.size();
    if (random().nextBoolean() && tree.moveToChild()) {
      randomPointTreeNavigation(tree);
      if (random().nextBoolean() && tree.moveToSibling()) {
        randomPointTreeNavigation(tree);
      }
      tree.moveToParent();
    }
    // we always finish on the same node we started
    assertArrayEquals(minPackedValue, tree.getMinPackedValue());
    assertArrayEquals(maxPackedValue, tree.getMaxPackedValue());
    assertEquals(size, tree.size());
  }

  private void assertHits(BitSet hits, BitSet expected) {
    int limit = Math.max(expected.length(), hits.length());
    for (int docID = 0; docID < limit; docID++) {
      assertEquals("docID=" + docID, expected.get(docID), hits.get(docID));
    }
  }

  private IntersectVisitor getIntersectVisitor(
      BitSet hits, byte[][] queryMin, byte[][] queryMax, BKDConfig config) {
    return new IntersectVisitor() {
      @Override
      public void visit(int docID) {
        hits.set(docID);
        // System.out.println("visit docID=" + docID);
      }

      @Override
      public void visit(int docID, byte[] packedValue) {
        // System.out.println("visit check docID=" + docID);
        for (int dim = 0; dim < config.numIndexDims; dim++) {
          if (Arrays.compareUnsigned(
                      packedValue,
                      dim * config.bytesPerDim,
                      dim * config.bytesPerDim + config.bytesPerDim,
                      queryMin[dim],
                      0,
                      config.bytesPerDim)
                  < 0
              || Arrays.compareUnsigned(
                      packedValue,
                      dim * config.bytesPerDim,
                      dim * config.bytesPerDim + config.bytesPerDim,
                      queryMax[dim],
                      0,
                      config.bytesPerDim)
                  > 0) {
            // System.out.println("  no");
            return;
          }
        }

        // System.out.println("  yes");
        hits.set(docID);
      }

      @Override
      public void visit(DocIdSetIterator iterator, byte[] packedValue) throws IOException {
        if (random().nextBoolean()) {
          // check the default method is correct
          IntersectVisitor.super.visit(iterator, packedValue);
        } else {
          assertEquals(iterator.docID(), -1);
          int cost = Math.toIntExact(iterator.cost());
          int numberOfPoints = 0;
          int docID;
          while ((docID = iterator.nextDoc()) != DocIdSetIterator.NO_MORE_DOCS) {
            assertEquals(iterator.docID(), docID);
            visit(docID, packedValue);
            numberOfPoints++;
          }
          assertEquals(cost, numberOfPoints);
          assertEquals(iterator.docID(), DocIdSetIterator.NO_MORE_DOCS);
          assertEquals(iterator.nextDoc(), DocIdSetIterator.NO_MORE_DOCS);
          assertEquals(iterator.docID(), DocIdSetIterator.NO_MORE_DOCS);
        }
      }

      @Override
      public Relation compare(byte[] minPacked, byte[] maxPacked) {
        boolean crosses = false;
        for (int dim = 0; dim < config.numIndexDims; dim++) {
          if (Arrays.compareUnsigned(
                      maxPacked,
                      dim * config.bytesPerDim,
                      dim * config.bytesPerDim + config.bytesPerDim,
                      queryMin[dim],
                      0,
                      config.bytesPerDim)
                  < 0
              || Arrays.compareUnsigned(
                      minPacked,
                      dim * config.bytesPerDim,
                      dim * config.bytesPerDim + config.bytesPerDim,
                      queryMax[dim],
                      0,
                      config.bytesPerDim)
                  > 0) {
            return Relation.CELL_OUTSIDE_QUERY;
          } else if (Arrays.compareUnsigned(
                      minPacked,
                      dim * config.bytesPerDim,
                      dim * config.bytesPerDim + config.bytesPerDim,
                      queryMin[dim],
                      0,
                      config.bytesPerDim)
                  < 0
              || Arrays.compareUnsigned(
                      maxPacked,
                      dim * config.bytesPerDim,
                      dim * config.bytesPerDim + config.bytesPerDim,
                      queryMax[dim],
                      0,
                      config.bytesPerDim)
                  > 0) {
            crosses = true;
          }
        }

        if (crosses) {
          return Relation.CELL_CROSSES_QUERY;
        } else {
          return Relation.CELL_INSIDE_QUERY;
        }
      }
    };
  }

  private BigInteger randomBigInt(int numBytes) {
    BigInteger x = new BigInteger(numBytes * 8 - 1, random());
    if (random().nextBoolean()) {
      x = x.negate();
    }
    return x;
  }

  private Directory getDirectory(int numPoints) {
    Directory dir;
    if (numPoints > 100000) {
      dir = newFSDirectory(createTempDir("TestBKDTree"));
    } else {
      dir = newDirectory();
    }
    return dir;
  }

  /** Make sure corruption on an input sort file is caught, even if BKDWriter doesn't get angry */
  public void testBitFlippedOnPartition1() throws Exception {

    // Generate fixed data set:
    int numDocs = atLeast(10000);
    int numBytesPerDim = 4;
    int numDims = 3;

    byte[][][] docValues = new byte[numDocs][][];
    byte counter = 0;

    for (int docID = 0; docID < numDocs; docID++) {
      byte[][] values = new byte[numDims][];
      for (int dim = 0; dim < numDims; dim++) {
        values[dim] = new byte[numBytesPerDim];
        for (int i = 0; i < values[dim].length; i++) {
          values[dim][i] = counter;
          counter++;
        }
      }
      docValues[docID] = values;
    }

    try (Directory dir0 = newMockDirectory()) {

      Directory dir =
          new FilterDirectory(dir0) {
            boolean corrupted;

            @Override
            public IndexOutput createTempOutput(String prefix, String suffix, IOContext context)
                throws IOException {
              IndexOutput out = in.createTempOutput(prefix, suffix, context);
              if (corrupted == false && prefix.equals("_0") && suffix.equals("bkd_left0")) {
                corrupted = true;
                return new CorruptingIndexOutput(dir0, 22, out);
              } else {
                return out;
              }
            }
          };

      CorruptIndexException e =
          expectThrows(
              CorruptIndexException.class,
              () -> {
                verify(dir, docValues, null, numDims, numDims, numBytesPerDim, 50, 0.1);
              });
      assertTrue(e.getMessage().contains("checksum failed (hardware problem?)"));
    }
  }

  /** Make sure corruption on a recursed partition is caught, when BKDWriter does get angry */
  public void testBitFlippedOnPartition2() throws Exception {

    // Generate fixed data set:
    int numDocs = atLeast(10000);
    int numBytesPerDim = 4;
    int numDims = 3;

    byte[][][] docValues = new byte[numDocs][][];
    byte counter = 0;

    for (int docID = 0; docID < numDocs; docID++) {
      byte[][] values = new byte[numDims][];
      for (int dim = 0; dim < numDims; dim++) {
        values[dim] = new byte[numBytesPerDim];
        for (int i = 0; i < values[dim].length; i++) {
          values[dim][i] = counter;
          counter++;
        }
      }
      docValues[docID] = values;
    }

    try (Directory dir0 = newMockDirectory()) {

      Directory dir =
          new FilterDirectory(dir0) {
            boolean corrupted;

            @Override
            public IndexOutput createTempOutput(String prefix, String suffix, IOContext context)
                throws IOException {
              IndexOutput out = in.createTempOutput(prefix, suffix, context);
              // System.out.println("prefix=" + prefix + " suffix=" + suffix);
              if (corrupted == false && suffix.equals("bkd_left0")) {
                // System.out.println("now corrupt byte=" + x + " prefix=" + prefix + " suffix=" +
                // suffix);
                corrupted = true;
                return new CorruptingIndexOutput(dir0, 22072, out);
              } else {
                return out;
              }
            }
          };

      Throwable t =
          expectThrows(
              CorruptIndexException.class,
              () -> {
                verify(dir, docValues, null, numDims, numDims, numBytesPerDim, 50, 0.1);
              });
      assertCorruptionDetected(t);
    }
  }

  private void assertCorruptionDetected(Throwable t) {
    if (t instanceof CorruptIndexException) {
      if (t.getMessage().contains("checksum failed (hardware problem?)")) {
        return;
      }
    }

    for (Throwable suppressed : t.getSuppressed()) {
      if (suppressed instanceof CorruptIndexException) {
        if (suppressed.getMessage().contains("checksum failed (hardware problem?)")) {
          return;
        }
      }
    }
    fail("did not see a suppressed CorruptIndexException");
  }

  public void testTieBreakOrder() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 10000;
      BKDWriter w =
          new BKDWriter(
              numDocs + 1, dir, "tmp", new BKDConfig(1, 1, Integer.BYTES, 2), 0.01f, numDocs);
      for (int i = 0; i < numDocs; i++) {
        w.add(new byte[Integer.BYTES], i);
      }

      IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT);
      Runnable finalizer = w.finish(out, out, out);
      long fp = out.getFilePointer();
      finalizer.run();
      out.close();

      IndexInput in = dir.openInput("bkd", IOContext.DEFAULT);
      in.seek(fp);
      PointValues r = getPointValues(in);
      r.intersect(
          new IntersectVisitor() {
            int lastDocID = -1;

            @Override
            public void visit(int docID) {
              assertTrue("lastDocID=" + lastDocID + " docID=" + docID, docID > lastDocID);
              lastDocID = docID;
            }

            @Override
            public void visit(int docID, byte[] packedValue) {
              visit(docID);
            }

            @Override
            public Relation compare(byte[] minPacked, byte[] maxPacked) {
              return Relation.CELL_CROSSES_QUERY;
            }
          });
      in.close();
    }
  }

  public void testCheckDataDimOptimalOrder() throws IOException {
    Directory dir = newDirectory();
    final int numValues = atLeast(5000);
    final int maxPointsInLeafNode = TestUtil.nextInt(random(), 50, 500);
    final int numBytesPerDim = TestUtil.nextInt(random(), 1, 4);
    final double maxMB = (float) 3.0 + (3 * random().nextDouble());

    final int numIndexDims = TestUtil.nextInt(random(), 1, 8);
    final int numDataDims = TestUtil.nextInt(random(), numIndexDims, 8);

    final byte[] pointValue1 = new byte[numDataDims * numBytesPerDim];
    final byte[] pointValue2 = new byte[numDataDims * numBytesPerDim];
    random().nextBytes(pointValue1);
    random().nextBytes(pointValue2);
    // equal index dimensions but different data dimensions
    for (int i = 0; i < numIndexDims; i++) {
      System.arraycopy(
          pointValue1, i * numBytesPerDim, pointValue2, i * numBytesPerDim, numBytesPerDim);
    }

    BKDWriter w =
        new BKDWriter(
            2 * numValues,
            dir,
            "_temp",
            new BKDConfig(numDataDims, numIndexDims, numBytesPerDim, maxPointsInLeafNode),
            maxMB,
            2 * numValues);
    for (int i = 0; i < numValues; ++i) {
      w.add(pointValue1, i);
      w.add(pointValue2, i);
    }
    final long indexFP;
    try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
      Runnable finalizer = w.finish(out, out, out);
      indexFP = out.getFilePointer();
      finalizer.run();
      w.close();
    }

    IndexInput pointsIn = dir.openInput("bkd", IOContext.DEFAULT);
    pointsIn.seek(indexFP);
    PointValues points = getPointValues(pointsIn);

    points.intersect(
        new IntersectVisitor() {

          byte[] previous = null;
          boolean hasChanged = false;

          @Override
          public void visit(int docID) {
            throw new UnsupportedOperationException();
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            if (previous == null) {
              previous = new byte[numDataDims * numBytesPerDim];
              System.arraycopy(packedValue, 0, previous, 0, numDataDims * numBytesPerDim);
            } else {
              int mismatch = Arrays.mismatch(packedValue, previous);
              if (mismatch != -1) {
                if (hasChanged == false) {
                  hasChanged = true;
                  System.arraycopy(packedValue, 0, previous, 0, numDataDims * numBytesPerDim);
                } else {
                  fail("Points are not in optimal order");
                }
              }
            }
          }

          @Override
          public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
            return Relation.CELL_CROSSES_QUERY;
          }
        });

    pointsIn.close();
    dir.close();
  }

  public void test2DLongOrdsOffline() throws Exception {
    try (Directory dir = newDirectory()) {
      int numDocs = 100000;
      BKDWriter w =
          new BKDWriter(
              numDocs + 1, dir, "tmp", new BKDConfig(2, 2, Integer.BYTES, 2), 0.01f, numDocs);
      byte[] buffer = new byte[2 * Integer.BYTES];
      for (int i = 0; i < numDocs; i++) {
        random().nextBytes(buffer);
        w.add(buffer, i);
      }

      IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT);
      Runnable finalizer = w.finish(out, out, out);
      long fp = out.getFilePointer();
      finalizer.run();
      out.close();

      IndexInput in = dir.openInput("bkd", IOContext.DEFAULT);
      in.seek(fp);
      PointValues r = getPointValues(in);
      int[] count = new int[1];
      r.intersect(
          new IntersectVisitor() {

            @Override
            public void visit(int docID) {
              count[0]++;
            }

            @Override
            public void visit(int docID, byte[] packedValue) {
              visit(docID);
            }

            @Override
            public Relation compare(byte[] minPacked, byte[] maxPacked) {
              if (random().nextInt(7) == 1) {
                return Relation.CELL_CROSSES_QUERY;
              } else {
                return Relation.CELL_INSIDE_QUERY;
              }
            }
          });
      assertEquals(numDocs, count[0]);
      in.close();
    }
  }

  // Claims 16 bytes per dim, but only use the bottom N 1-3 bytes; this would happen e.g. if a user
  // indexes what are actually just short
  // values as a LongPoint:
  public void testWastedLeadingBytes() throws Exception {
    Random random = random();
    int numDims = TestUtil.nextInt(random, 1, PointValues.MAX_INDEX_DIMENSIONS);
    int numIndexDims = TestUtil.nextInt(random, 1, numDims);
    int bytesPerDim = PointValues.MAX_NUM_BYTES;
    int bytesUsed = TestUtil.nextInt(random, 1, 3);

    Directory dir = newFSDirectory(createTempDir());
    int numDocs = atLeast(10000);
    BKDWriter w =
        new BKDWriter(
            numDocs + 1,
            dir,
            "tmp",
            new BKDConfig(numDims, numIndexDims, bytesPerDim, 32),
            1f,
            numDocs);
    byte[] tmp = new byte[bytesUsed];
    byte[] buffer = new byte[numDims * bytesPerDim];
    for (int i = 0; i < numDocs; i++) {
      for (int dim = 0; dim < numDims; dim++) {
        random.nextBytes(tmp);
        System.arraycopy(tmp, 0, buffer, dim * bytesPerDim + (bytesPerDim - bytesUsed), tmp.length);
      }
      w.add(buffer, i);
    }

    IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT);
    Runnable finalizer = w.finish(out, out, out);
    long fp = out.getFilePointer();
    finalizer.run();
    out.close();

    IndexInput in = dir.openInput("bkd", IOContext.DEFAULT);
    in.seek(fp);
    PointValues r = getPointValues(in);
    int[] count = new int[1];
    r.intersect(
        new IntersectVisitor() {

          @Override
          public void visit(int docID) {
            count[0]++;
          }

          @Override
          public void visit(int docID, byte[] packedValue) {
            assert packedValue.length == numDims * bytesPerDim;
            visit(docID);
          }

          @Override
          public Relation compare(byte[] minPacked, byte[] maxPacked) {
            assert minPacked.length == numIndexDims * bytesPerDim;
            assert maxPacked.length == numIndexDims * bytesPerDim;
            if (random().nextInt(7) == 1) {
              return Relation.CELL_CROSSES_QUERY;
            } else {
              return Relation.CELL_INSIDE_QUERY;
            }
          }
        });
    assertEquals(numDocs, count[0]);
    in.close();
    dir.close();
  }

  public void testEstimatePointCount() throws IOException {
    Directory dir = newDirectory();
    final int numValues = atLeast(10000); // make sure to have multiple leaves
    final int maxPointsInLeafNode = TestUtil.nextInt(random(), 50, 500);
    final int numBytesPerDim = TestUtil.nextInt(random(), 1, 4);
    final byte[] pointValue = new byte[numBytesPerDim];
    final byte[] uniquePointValue = new byte[numBytesPerDim];
    random().nextBytes(uniquePointValue);

    BKDWriter w =
        new BKDWriter(
            numValues,
            dir,
            "_temp",
            new BKDConfig(1, 1, numBytesPerDim, maxPointsInLeafNode),
            BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP,
            numValues);
    for (int i = 0; i < numValues; ++i) {
      if (i == numValues / 2) {
        w.add(uniquePointValue, i);
      } else {
        do {
          random().nextBytes(pointValue);
        } while (Arrays.equals(pointValue, uniquePointValue));
        w.add(pointValue, i);
      }
    }
    final long indexFP;
    try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
      Runnable finalizer = w.finish(out, out, out);
      indexFP = out.getFilePointer();
      finalizer.run();
      w.close();
    }

    IndexInput pointsIn = dir.openInput("bkd", IOContext.DEFAULT);
    pointsIn.seek(indexFP);
    PointValues points = getPointValues(pointsIn);

    // If all points match, then the point count is numValues
    assertEquals(
        numValues,
        points.estimatePointCount(
            new IntersectVisitor() {
              @Override
              public void visit(int docID, byte[] packedValue) throws IOException {}

              @Override
              public void visit(int docID) throws IOException {}

              @Override
              public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                return Relation.CELL_INSIDE_QUERY;
              }
            }));

    // Return 0 if no points match
    assertEquals(
        0,
        points.estimatePointCount(
            new IntersectVisitor() {
              @Override
              public void visit(int docID, byte[] packedValue) throws IOException {}

              @Override
              public void visit(int docID) throws IOException {}

              @Override
              public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                return Relation.CELL_OUTSIDE_QUERY;
              }
            }));

    // If only one point matches, then the point count is (actualMaxPointsInLeafNode + 1) / 2
    // in general, or maybe 2x that if the point is a split value
    final long pointCount =
        points.estimatePointCount(
            new IntersectVisitor() {
              @Override
              public void visit(int docID, byte[] packedValue) throws IOException {}

              @Override
              public void visit(int docID) throws IOException {}

              @Override
              public Relation compare(byte[] minPackedValue, byte[] maxPackedValue) {
                if (Arrays.compareUnsigned(
                            uniquePointValue, 0, numBytesPerDim, maxPackedValue, 0, numBytesPerDim)
                        > 0
                    || Arrays.compareUnsigned(
                            uniquePointValue, 0, numBytesPerDim, minPackedValue, 0, numBytesPerDim)
                        < 0) {
                  return Relation.CELL_OUTSIDE_QUERY;
                }
                return Relation.CELL_CROSSES_QUERY;
              }
            });
    long lastNodePointCount = numValues % maxPointsInLeafNode;
    assertTrue(
        "" + pointCount,
        pointCount == (maxPointsInLeafNode + 1) / 2 // common case
            || pointCount == (lastNodePointCount + 1) / 2 // not fully populated leaf
            || pointCount == 2 * ((maxPointsInLeafNode + 1) / 2) // if the point is a split value
            || pointCount
                == ((maxPointsInLeafNode + 1) / 2)
                    + ((lastNodePointCount + 1)
                        / 2)); // if the point is a split value and one leaf is not fully populated

    pointsIn.close();
    dir.close();
  }

  public void testTotalPointCountValidation() throws IOException {
    Directory dir = newDirectory();
    final int numValues = 10;
    final int numPointsAdded = 50; // exceeds totalPointCount
    final int numBytesPerDim = TestUtil.nextInt(random(), 1, 4);
    final byte[] pointValue = new byte[numBytesPerDim];
    random().nextBytes(pointValue);

    MutablePointTree reader =
        new MutablePointTree() {

          @Override
          public void swap(int i, int j) {
            // do nothing
          }

          @Override
          public int getDocID(int i) {
            return 0;
          }

          @Override
          public void getValue(int i, BytesRef packedValue) {
            packedValue.bytes = pointValue;
          }

          @Override
          public byte getByteAt(int i, int k) {
            BytesRef b = new BytesRef();
            getValue(i, b);
            return b.bytes[b.offset + k];
          }

          @Override
          public void save(int i, int j) {
            // do nothing
          }

          @Override
          public void restore(int i, int j) {
            // do nothing
          }

          @Override
          public long size() {
            return numPointsAdded;
          }

          @Override
          public void visitDocValues(IntersectVisitor visitor) throws IOException {
            for (int i = 0; i < numPointsAdded; i++) {
              visitor.visit(0, pointValue);
            }
          }
        };

    BKDWriter w =
        new BKDWriter(
            numValues,
            dir,
            "_temp",
            new BKDConfig(1, 1, numBytesPerDim, BKDConfig.DEFAULT_MAX_POINTS_IN_LEAF_NODE),
            BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP,
            numValues);
    expectThrows(
        IllegalStateException.class,
        () -> {
          try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
            w.writeField(out, out, out, "test_field_name", reader);
          } finally {
            w.close();
            dir.close();
          }
        });
  }

  public void testTooManyPoints() throws Exception {
    Directory dir = newDirectory();
    final int numValues = 10;
    final int numBytesPerDim = TestUtil.nextInt(random(), 1, 4);
    final byte[] pointValue = new byte[numBytesPerDim];
    BKDWriter w =
        new BKDWriter(
            numValues,
            dir,
            "_temp",
            new BKDConfig(1, 1, numBytesPerDim, 2),
            BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP,
            numValues);
    for (int i = 0; i < numValues; i++) {
      random().nextBytes(pointValue);
      w.add(pointValue, i);
    }
    random().nextBytes(pointValue);
    IllegalStateException ex =
        expectThrows(
            IllegalStateException.class,
            () -> {
              w.add(pointValue, numValues);
            });
    assertEquals(
        "totalPointCount=10 was passed when we were created, but we just hit 11 values",
        ex.getMessage());
    w.close();
    dir.close();
  }

  public void testTooManyPoints1D() throws Exception {
    Directory dir = newDirectory();
    final int numValues = 10;
    final int numBytesPerDim = TestUtil.nextInt(random(), 1, 4);
    final byte[][] pointValue = new byte[11][numBytesPerDim];
    final int[] docId = new int[11];
    BKDWriter w =
        new BKDWriter(
            numValues + 1,
            dir,
            "_temp",
            new BKDConfig(1, 1, numBytesPerDim, 2),
            BKDWriter.DEFAULT_MAX_MB_SORT_IN_HEAP,
            numValues);
    for (int i = 0; i < numValues + 1; i++) {
      random().nextBytes(pointValue[i]);
      docId[i] = i;
    }
    MutablePointTree val =
        new MutablePointTree() {

          final byte[][] tmpValues = new byte[numValues][];
          final int[] tmpDocs = new int[numValues];

          @Override
          public void getValue(int i, BytesRef packedValue) {
            packedValue.bytes = pointValue[i];
            packedValue.offset = 0;
            packedValue.length = numBytesPerDim;
          }

          @Override
          public byte getByteAt(int i, int k) {
            return pointValue[i][k];
          }

          @Override
          public int getDocID(int i) {
            return docId[i];
          }

          @Override
          public void swap(int i, int j) {
            byte[] temp = pointValue[i];
            pointValue[i] = pointValue[j];
            pointValue[j] = temp;
            int tempDocId = docId[i];
            docId[i] = docId[j];
            docId[j] = tempDocId;
          }

          @Override
          public void save(int i, int j) {
            tmpValues[j] = pointValue[i];
            tmpDocs[j] = docId[i];
          }

          @Override
          public void restore(int i, int j) {
            System.arraycopy(tmpValues, i, pointValue, i, j - i);
            System.arraycopy(tmpDocs, i, docId, i, j - i);
          }

          @Override
          public long size() {
            return 11;
          }

          @Override
          public void visitDocValues(IntersectVisitor visitor) throws IOException {
            for (int i = 0; i < size(); i++) {
              visitor.visit(docId[i], pointValue[i]);
            }
          }
        };
    try (IndexOutput out = dir.createOutput("bkd", IOContext.DEFAULT)) {
      IllegalStateException ex =
          expectThrows(
              IllegalStateException.class,
              () -> {
                w.writeField(out, out, out, "", val);
              });
      assertEquals(
          "totalPointCount=10 was passed when we were created, but we just hit 11 values",
          ex.getMessage());
      w.close();
    }
    dir.close();
  }
}
