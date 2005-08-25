package de.lmu.ifi.dbs.index.spatial.rtree;

import de.lmu.ifi.dbs.index.spatial.MBR;
import de.lmu.ifi.dbs.index.spatial.SpatialObject;

import java.util.Arrays;

/**
 * Encapsulates the required parameters for a topological split of a R*Tree.
 * Also static methods for bulk split are provided.
 *
 * @author Elke Achtert (<a href="mailto:achtert@dbs.ifi.lmu.de">achtert@dbs.ifi.lmu.de</a>)
 */
class SplitDescription {
  /**
   * The split axis.
   */
  int splitAxis = 0;

  /**
   * The index of the split point.
   */
  int splitPoint = -1;

  /**
   * Indicates wether the sorting according to maximal or to minmal
   * value is best for the split axis
   */
  int bestSort;

  /**
   * The entries sorted according to their max values of their MBRs.
   */
  Entry[] maxSorting;

  /**
   * The entries sorted according to their min values of their MBRs.
   */
  Entry[] minSorting;

  /**
   * Chooses a split axis.
   *
   * @param entries    the entries to be split
   * @param minEntries number of minimum entries in the node to be split
   */
  public void chooseSplitAxis(Entry[] entries, int minEntries) {
    int dim = entries[0].getMBR().getDimensionality();

    maxSorting = entries.clone();
    minSorting = entries.clone();

    // best value for the surface
    double minSurface = Double.MAX_VALUE;
    // comparator used by sort method
    final SpatialComparator comp = new SpatialComparator();

    for (int i = 1; i <= dim; i++) {
      double currentPerimeter = 0.0;
      // sort the entries according to their minmal and according to their maximal value
      comp.setCompareDimension(i);
      // sort according to minimal value
      comp.setComparisonValue(SpatialComparator.MIN);
      Arrays.sort(minSorting, comp);
      // sort according to maximal value
      comp.setComparisonValue(SpatialComparator.MAX);
      Arrays.sort(maxSorting, comp);

      for (int k = 0; k <= entries.length - 2 * minEntries; k++) {
        MBR mbr1 = mbr(minSorting, 0, minEntries + k);
        MBR mbr2 = mbr(minSorting, minEntries + k, entries.length);
        currentPerimeter += mbr1.perimeter() + mbr2.perimeter();
        mbr1 = mbr(maxSorting, 0, minEntries + k);
        mbr2 = mbr(maxSorting, minEntries + k, entries.length);
        currentPerimeter += mbr1.perimeter() + mbr2.perimeter();
      }

      if (currentPerimeter < minSurface) {
        splitAxis = i;
        minSurface = currentPerimeter;
      }
    }
  }

  /**
   * Chooses a split axis.
   *
   * @param entries    the entries to be split
   * @param minEntries number of minimum entries in the node to be split
   */
  public void chooseSplitPoint(Entry[] entries, int minEntries) {
    // numEntries
    int numEntries = maxSorting.length;
    // sort upper and lower in the right dimesnion
    final SpatialComparator comp = new SpatialComparator();
    comp.setCompareDimension(splitAxis);
    comp.setComparisonValue(SpatialComparator.MIN);
    Arrays.sort(minSorting, comp);
    comp.setComparisonValue(SpatialComparator.MAX);
    Arrays.sort(maxSorting, comp);

    // the split point (first set to minimum entries in the node)
    splitPoint = minEntries;
    // best value for the overlap
    double minOverlap = Double.MAX_VALUE;
    // the volume of mbr1 and mbr2
    double volume = 0.0;
    // indicates wether the sorting according to maximal or to minmal value is best for the split axis
    bestSort = -1;

    for (int i = 0; i <= numEntries - 2 * minEntries; i++) {
      // test the sorting with respect to the minimal values
      MBR mbr1 = mbr(minSorting, 0, minEntries + i);
      MBR mbr2 = mbr(minSorting, minEntries + i, numEntries);
      double currentOverlap = mbr1.overlap(mbr2);
      if (currentOverlap < minOverlap || (currentOverlap == minOverlap && (mbr1.volume() + mbr2.volume()) < volume)) {
        minOverlap = currentOverlap;
        splitPoint = minEntries + i;
        bestSort = SpatialComparator.MIN;
        volume = mbr1.volume() + mbr2.volume();
      }
      // test the sorting with respect to the maximal values
      mbr1 = mbr(maxSorting, 0, minEntries + i);
      mbr2 = mbr(maxSorting, minEntries + i, numEntries);
      currentOverlap = mbr1.overlap(mbr2);
      if (currentOverlap < minOverlap || (currentOverlap == minOverlap && (mbr1.volume() + mbr2.volume()) < volume)) {
        minOverlap = currentOverlap;
        splitPoint = minEntries + i;
        bestSort = SpatialComparator.MAX;
        volume = mbr1.volume() + mbr2.volume();
      }
    }
  }

  /**
   * Computes and returns the mbr of the specified nodes, only the nodes
   * between from and to index are considered.
   *
   * @param nodes the array of nodes
   * @param from  the start index
   * @param to    the end index
   * @return the mbr of the specified nodes
   */
  private MBR mbr(final Entry[] nodes, final int from, final int to) {
    double[] min = new double[nodes[from].getMBR().getDimensionality()];
    double[] max = new double[nodes[from].getMBR().getDimensionality()];

    for (int d = 1; d <= min.length; d++) {
      min[d-1] = nodes[from].getMBR().getMin(d);
      max[d-1] = nodes[from].getMBR().getMax(d);
    }

    for (int i = from; i < to; i++) {
      MBR currMBR = nodes[i].getMBR();
      for (int d = 1; d <= min.length; d++) {
        if (min[d-1] > currMBR.getMin(d)) {
          min[d-1] = currMBR.getMin(d);
        }
        if (max[d-1] < currMBR.getMax(d)) {
          max[d-1] = currMBR.getMax(d);
        }
      }
    }
    return new MBR(min, max);
  }

  /**
   * Computes and returns the best split axis.
   * @param objects the spatial objects to be split
   * @return the best split axis
   */
  public static int chooseBulkSplitAxis(SpatialObject[] objects) {
    int splitAxis = 0;
    int dimension = objects[0].getDimensionality();

    // maximum and minimum value for the extension
    double[] maxExtension = new double[dimension];
    double[] minExtension = new double[dimension];
    Arrays.fill(minExtension, Double.MAX_VALUE);

    // compute min and max value in each dimension
    for (SpatialObject object : objects) {
      MBR mbr = object.mbr();
      for (int d = 1; d <= dimension; d++) {
        double min, max;
        min = mbr.getMin(d);
        max = mbr.getMax(d);

        if (maxExtension[d - 1] < max)
          maxExtension[d - 1] = max;

        if (minExtension[d - 1] > min)
          minExtension[d - 1] = min;
      }
    }

    // set split axis to dim with maximal extension
    double max = 0;
    for (int d = 1; d <= dimension; d++) {
      double currentExtension = maxExtension[d-1] - minExtension[d-1];
      if (max < currentExtension) {
        max = currentExtension;
        splitAxis = d;
      }
    }
    return splitAxis;
  }

  /**
   * Computes and returns the best split point.
   *
   * @param numEntries the number of entries to be split
   * @param minEntries the number of minimum entries in the node to be split
   * @param maxEntries number of maximum entries in the node to be split
   * @return the best split point
   */
  public static int chooseBulkSplitPoint(int numEntries, int minEntries, int maxEntries) {
    int splitPoint;

    if (numEntries < minEntries) {
      throw new IllegalArgumentException("numEntries < minEntries!");
    }

    else if (numEntries <= maxEntries) {
      splitPoint = numEntries;
    }

    else if (numEntries < maxEntries + minEntries) {
      splitPoint = (numEntries - minEntries);
    }

    else {
      splitPoint = maxEntries;
    }

    return splitPoint;
  }
}
