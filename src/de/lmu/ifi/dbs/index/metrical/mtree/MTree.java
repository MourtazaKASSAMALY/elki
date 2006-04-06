package de.lmu.ifi.dbs.index.metrical.mtree;

import de.lmu.ifi.dbs.data.DatabaseObject;
import de.lmu.ifi.dbs.distance.Distance;
import de.lmu.ifi.dbs.distance.DistanceFunction;
import de.lmu.ifi.dbs.distance.EuklideanDistanceFunction;
import de.lmu.ifi.dbs.index.BreadthFirstEnumeration;
import de.lmu.ifi.dbs.index.Identifier;
import de.lmu.ifi.dbs.index.TreePath;
import de.lmu.ifi.dbs.index.TreePathComponent;
import de.lmu.ifi.dbs.index.metrical.MetricalIndex;
import de.lmu.ifi.dbs.index.metrical.mtree.util.Assignments;
import de.lmu.ifi.dbs.index.metrical.mtree.util.DistanceEntry;
import de.lmu.ifi.dbs.index.metrical.mtree.util.PQNode;
import de.lmu.ifi.dbs.logging.LoggingConfiguration;
import de.lmu.ifi.dbs.persistent.LRUCache;
import de.lmu.ifi.dbs.persistent.MemoryPageFile;
import de.lmu.ifi.dbs.persistent.PageFile;
import de.lmu.ifi.dbs.persistent.PersistentPageFile;
import de.lmu.ifi.dbs.utilities.KNNList;
import de.lmu.ifi.dbs.utilities.QueryResult;
import de.lmu.ifi.dbs.utilities.UnableToComplyException;
import de.lmu.ifi.dbs.utilities.Util;
import de.lmu.ifi.dbs.utilities.heap.DefaultHeap;
import de.lmu.ifi.dbs.utilities.heap.Heap;
import de.lmu.ifi.dbs.utilities.heap.Identifiable;
import de.lmu.ifi.dbs.utilities.optionhandling.AttributeSettings;
import de.lmu.ifi.dbs.utilities.optionhandling.OptionHandler;
import de.lmu.ifi.dbs.utilities.optionhandling.ParameterException;
import de.lmu.ifi.dbs.utilities.optionhandling.WrongParameterValueException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * MTree is a metrical index structure based on the concepts of the M-Tree.
 * Apart from organizing the objects it also provides several methods to search
 * for certain object in the structure. Persistence is not yet ensured.
 *
 * @author Elke Achtert (<a
 *         href="mailto:achtert@dbs.ifi.lmu.de">achtert@dbs.ifi.lmu.de</a>)
 */
public class MTree<O extends DatabaseObject, D extends Distance<D>> extends MetricalIndex<O, D> {
  /**
   * The default distance function.
   */
  public static final String DEFAULT_DISTANCE_FUNCTION = EuklideanDistanceFunction.class.getName();

  /**
   * Parameter for distance function.
   */
  public static final String DISTANCE_FUNCTION_P = "distancefunction";

  /**
   * Description for parameter distance function.
   */
  public static final String DISTANCE_FUNCTION_D = "<classname>the distance function to determine the distance between database objects - must implement "
                                                   + DistanceFunction.class.getName()
                                                   + ". (Default: "
                                                   + DEFAULT_DISTANCE_FUNCTION + ").";


  /**
   * Holds the class specific debug status.
   */
  protected static boolean DEBUG = LoggingConfiguration.DEBUG;
//  protected static boolean DEBUG = true;

  /**
   * The id of the root node.
   */
  protected static Identifier ROOT_NODE_ID = new Identifier() {
    /**
     * Returns the ROOT ID.
     *
     * @return the ROOT ID
     */
    public Integer value() {
      return 0;
    }

    /**
     * Returns false.
     *
     * @return false
     */
    public boolean isNodeID() {
      return true;
    }
  };

  /**
   * The logger of this class.
   */
  protected Logger logger = Logger.getLogger(this.getClass().getName());

  /**
   * The file storing the entries of this M-Tree.
   */
  protected PageFile<MTreeNode<O, D>> file;

  /**
   * The capacity of a directory node (= 1 + maximum number of entries in a
   * directory node).
   */
  protected int dirCapacity;

  /**
   * The capacity of a leaf node (= 1 + maximum number of entries in a leaf
   * node).
   */
  protected int leafCapacity;

  /**
   * The distance function.
   */
  protected DistanceFunction<O, D> distanceFunction;

  /**
   * True if the RTree is already initialized.
   */
  protected boolean initialized = false;

  /**
   * Empty constructor for subclasses.
   */
  public MTree() {
    super();
    parameterToDescription.put(DISTANCE_FUNCTION_P + OptionHandler.EXPECTS_VALUE, DISTANCE_FUNCTION_D);
    optionHandler = new OptionHandler(parameterToDescription, this.getClass().getName());
  }

  /**
   * Initializes the MTree from an existing persistent file.
   */
  public void initFromFile() {
    // init the file
    MTreeHeader header = new MTreeHeader();
    this.file = new PersistentPageFile<MTreeNode<O, D>>(header, cacheSize, new LRUCache<MTreeNode<O, D>>(), fileName);
    this.dirCapacity = header.getDirCapacity();
    this.leafCapacity = header.getLeafCapacity();

    if (DEBUG) {
      StringBuffer msg = new StringBuffer();
      msg.append(getClass());
      msg.append("\n file = ").append(file.getClass());
      logger.fine(msg.toString());
    }

    initialized = true;
  }

  /**
   * Inserts the specified object into this M-Tree.
   *
   * @param object the object to be inserted
   */
  public void insert(O object) {
    if (DEBUG) {
      logger.fine("insert " + object.getID() + " " + object + "\n");
    }

    if (!initialized) {
      init();
    }

    // find insertion path
    TreePath rootPath = new TreePath(new TreePathComponent(ROOT_NODE_ID, null));
    TreePath path = findInsertionPath(object.getID(), rootPath);
    MTreeNode<O, D> node = getNode(path.getLastPathComponent().getIdentifier());

    // determine parent distance
    D parentDistance = null;
    if (path.getPathCount() > 1) {
      MTreeNode<O, D> parent = getNode(path.getParentPath().getLastPathComponent().getIdentifier());
      Integer index = path.getLastPathComponent().getIndex();
      parentDistance = distanceFunction.distance(object.getID(), parent.entries[index].getObjectID());
    }

    // add object
    node.addLeafEntry(new MTreeLeafEntry<D>(object.getID(), parentDistance));

    // do split if necessary
    while (hasOverflow(path)) {
      path = split(path);
    }

    //test
//    test(new TreePath(new TreePathComponent(ROOT_NODE_ID, null)));
  }

  /**
   * Inserts the specified objects into this index sequentially.
   *
   * @param objects the objects to be inserted
   */
  public void insert(List<O> objects) {
    for (O object : objects) {
      insert(object);
    }
  }

  /**
   * Deletes the specified obect from this index.
   *
   * @param o the object to be deleted
   * @return true if this index did contain the object, false otherwise
   */
  public boolean delete(O o) {
    throw new UnsupportedOperationException("Deletion of objects is not supported by a M-Tree!");
  }

  /**
   * Performs a range query for the given spatial objec with the given epsilon
   * range and the according distance function. The query result is in
   * ascending order to the distance to the query object.
   *
   * @param object  the query object
   * @param epsilon the string representation of the query range
   * @return a List of the query results
   */
  public List<QueryResult<D>> rangeQuery(O object, String epsilon) {

    D range = distanceFunction.valueOf(epsilon);
    final List<QueryResult<D>> result = new ArrayList<QueryResult<D>>();

    doRangeQuery(null, getRoot(), object.getID(), range, result);

    // sort the result according to the distances
    Collections.sort(result);
    return result;
  }

  /**
   * Performs a k-nearest neighbor query for the given NumberVector with the
   * given parameter k and the according distance function. The query result
   * is in ascending order to the distance to the query object.
   *
   * @param object the query object
   * @param k      the number of nearest neighbors to be returned
   * @return a List of the query results
   */
  public List<QueryResult<D>> kNNQuery(O object, int k) {
    if (k < 1) {
      throw new IllegalArgumentException("At least one object has to be requested!");
    }

    final KNNList<D> knnList = new KNNList<D>(k, distanceFunction.infiniteDistance());
    doKNNQuery(object.getID(), knnList);
    return knnList.toList();
  }

  /**
   * Performs a reverse k-nearest neighbor query for the given object ID. The
   * query result is in ascending order to the distance to the query object.
   *
   * @param object the query object
   * @param k      the number of nearest neighbors to be returned
   * @return a List of the query results
   */
  public List<QueryResult<D>> reverseKNNQuery(O object, int k) {
    throw new UnsupportedOperationException("Not yet supported!");
  }

  /**
   * Returns the I/O-access of this M-Tree.
   *
   * @return the I/O-access of this M-Tree
   */
  public long getIOAccess() {
    return file.getIOAccess();
  }

  /**
   * Resets the I/O-access of this M-Tree.
   */
  public void resetIOAccess() {
    file.resetIOAccess();
  }

  /**
   * Returns the node represented by the specified identifier.
   *
   * @param identifier the identifier of the node to be returned
   * @return the node represented by the specified identifier
   */
  protected MTreeNode<O, D> getNode(Identifier identifier) {
    return getNode(identifier.value());
  }

  /**
   * Returns the node represented by the specified identifier.
   *
   * @param nodeID the identifier of the node to be returned
   * @return the node represented by the specified identifier
   */
  protected MTreeNode<O, D> getNode(int nodeID) {
    if (nodeID == ROOT_NODE_ID.value())
      return getRoot();
    else {
      return file.readPage(nodeID);
    }
  }

  /**
   * Returns the distance function.
   *
   * @return the distance function
   */
  public DistanceFunction<O, D> getDistanceFunction() {
    return distanceFunction;
  }

  /**
   * Closes this MTree and the underlying file. If this MTree has a persistent
   * file, all entries are written to disk.
   */
  public void close() {
    file.close();
  }

  /**
   * Returns a string representation of this RTree.
   *
   * @return a string representation of this RTree
   */
  public String toString() {
    StringBuffer result = new StringBuffer();
    int dirNodes = 0;
    int leafNodes = 0;
    int objects = 0;
    int levels = 0;

    MTreeNode<O, D> node = getRoot();

    while (!node.isLeaf()) {
      if (node.getNumEntries() > 0) {
        MTreeDirectoryEntry<D> entry = (MTreeDirectoryEntry<D>) node.entries[0];
        node = getNode(entry);
        levels++;
      }
    }

    TreePath rootPath = new TreePath(new TreePathComponent(ROOT_NODE_ID, null));
    BreadthFirstEnumeration<MTreeNode<O, D>> enumeration = new BreadthFirstEnumeration<MTreeNode<O, D>>(file, rootPath);

    while (enumeration.hasMoreElements()) {
      TreePath path = enumeration.nextElement();
      Identifier id = path.getLastPathComponent().getIdentifier();
      if (!id.isNodeID()) {
        objects++;
//        MTreeLeafEntry e = (MTreeLeafEntry) id;
//        System.out.println("  obj = " + e.getObjectID());
//        System.out.println("  pd  = " + e.getParentDistance());
      }
      else {
        node = file.readPage(id.value());
//        System.out.println(node + ", numEntries = " + node.numEntries);

        if (id instanceof MTreeDirectoryEntry) {
//          MTreeDirectoryEntry e = (MTreeDirectoryEntry) id;
//          System.out.println("  r_obj = " + e.getObjectID());
//          System.out.println("  pd = " + e.getParentDistance());
//          System.out.println("  cr = " + ((MTreeDirectoryEntry<D>) id).getCoveringRadius());
        }

        if (node.isLeaf())
          leafNodes++;
        else {
          dirNodes++;
        }
      }
    }

    result.append(getClass().getName()).append(" hat ").append((levels + 1)).append(" Ebenen \n");
    result.append("DirCapacity = ").append(dirCapacity).append("\n");
    result.append("LeafCapacity = ").append(leafCapacity).append("\n");
    result.append(dirNodes).append(" Directory Knoten \n");
    result.append(leafNodes).append(" Daten Knoten \n");
    result.append(objects).append(" Punkte im Baum \n");
    result.append("IO-Access: ").append(file.getIOAccess()).append("\n");
    result.append("File ").append(file.getClass()).append("\n");

    return result.toString();
  }

  /**
   * @see de.lmu.ifi.dbs.utilities.optionhandling.Parameterizable#setParameters(String[])
   */
  public String[] setParameters(String[] args) throws ParameterException {
    String[] remainingParameters = super.setParameters(args);

    if (optionHandler.isSet(DISTANCE_FUNCTION_P)) {
      String className = optionHandler.getOptionValue(DISTANCE_FUNCTION_P);
      try {
        // noinspection unchecked
        distanceFunction = Util.instantiate(DistanceFunction.class, className);
      }
      catch (UnableToComplyException e) {
        throw new WrongParameterValueException(DISTANCE_FUNCTION_P,
                                               className, DISTANCE_FUNCTION_D, e);
      }
    }
    else {
      try {
        // noinspection unchecked
        distanceFunction = Util.instantiate(DistanceFunction.class,
                                            DEFAULT_DISTANCE_FUNCTION);
      }
      catch (UnableToComplyException e) {
        throw new WrongParameterValueException(DISTANCE_FUNCTION_P,
                                               DEFAULT_DISTANCE_FUNCTION, DISTANCE_FUNCTION_D, e);
      }
    }

    remainingParameters = distanceFunction.setParameters(remainingParameters);
    setParameters(args, remainingParameters);
    return remainingParameters;
  }

  /**
   * Returns the parameter setting of the attributes.
   *
   * @return the parameter setting of the attributes
   */
  public List<AttributeSettings> getAttributeSettings() {
    List<AttributeSettings> attributeSettings = super.getAttributeSettings();

    AttributeSettings mySettings = attributeSettings.get(0);
    mySettings.addSetting(DISTANCE_FUNCTION_P, distanceFunction.getClass().getName());

    attributeSettings.addAll(distanceFunction.getAttributeSettings());

    return attributeSettings;
  }

  /**
   * Initializes this M-Tree.
   */
  protected void init() {
    // determine minimum and maximum entries in an node
    initCapacity();

    // init the file
    if (fileName == null) {
      this.file = new MemoryPageFile<MTreeNode<O, D>>(pageSize, cacheSize, new LRUCache<MTreeNode<O, D>>());
    }
    else {
      MTreeHeader header = createHeader(pageSize);
      this.file = new PersistentPageFile<MTreeNode<O, D>>(header, cacheSize, new LRUCache<MTreeNode<O, D>>(), fileName);
    }

    // create empty root
    MTreeNode<O, D> root = createEmptyRoot();
    file.writePage(root);

    if (DEBUG) {
      StringBuffer msg = new StringBuffer();
      msg.append(getClass());
      msg.append("\n file    = ").append(file.getClass());
      msg.append("\n maximum number of dir entries = ").append((dirCapacity - 1));
      msg.append("\n maximum number of leaf entries = ").append((leafCapacity - 1));
      msg.append("\n root    = ").append(getRoot()).append("\n");
      logger.fine(msg.toString());
    }

    initialized = true;
  }

  /**
   * Creates a new leaf node with the specified capacity.
   *
   * @return a new leaf node
   */
  protected MTreeNode<O, D> createEmptyRoot() {
    return new MTreeNode<O, D>(file, leafCapacity, true);
  }

  /**
   * Creates a header for this M-Tree.
   *
   * @param pageSize the size of a page in Bytes
   */
  protected MTreeHeader createHeader(int pageSize) {
    return new MTreeHeader(pageSize, dirCapacity, leafCapacity);
  }

  /**
   * Performs a range query. It starts from the root node and recursively
   * traverses all paths, which cannot be excluded from leading to
   * qualififying objects.
   *
   * @param o_p    the routing object of the specified node
   * @param node   the root of the subtree to be traversed
   * @param q      the id of the query object
   * @param r_q    the query range
   * @param result the list holding the query results
   */
  private void doRangeQuery(Integer o_p, MTreeNode<O, D> node, Integer q, D r_q, List<QueryResult<D>> result) {

    if (!node.isLeaf) {
      for (int i = 0; i < node.numEntries; i++) {
        MTreeDirectoryEntry<D> entry = (MTreeDirectoryEntry<D>) node.entries[i];
        Integer o_r = entry.getObjectID();

        D r_or = entry.getCoveringRadius();
        D d1 = o_p != null ? distanceFunction.distance(o_p, q) : distanceFunction.nullDistance();
        D d2 = o_p != null ? distanceFunction.distance(o_r, o_p) : distanceFunction.nullDistance();

        D diff = d1.compareTo(d2) > 0 ? d1.minus(d2) : d2.minus(d1);

        D sum = r_q.plus(r_or);

        if (diff.compareTo(sum) <= 0) {
          D d3 = distanceFunction.distance(o_r, q);
          if (d3.compareTo(sum) <= 0) {
            MTreeNode<O, D> child = getNode(entry);
            doRangeQuery(o_r, child, q, r_q, result);
          }
        }

      }
    }

    else {
      for (int i = 0; i < node.numEntries; i++) {
        MTreeEntry<D> entry = node.entries[i];
        Integer o_j = entry.getObjectID();

        D d1 = o_p != null ? distanceFunction.distance(o_p, q) : distanceFunction.nullDistance();
        D d2 = o_p != null ? distanceFunction.distance(o_j, o_p) : distanceFunction.nullDistance();

        D diff = d1.compareTo(d2) > 0 ? d1.minus(d2) : d2.minus(d1);

        if (diff.compareTo(r_q) <= 0) {
          D d3 = distanceFunction.distance(o_j, q);
          if (d3.compareTo(r_q) <= 0) {
            QueryResult<D> queryResult = new QueryResult<D>(o_j, d3);
            result.add(queryResult);
          }
        }
      }
    }
  }

  /**
   * Performs a k-nearest neighbor query for the given NumberVector with the
   * given parameter k and the according distance function. The query result
   * is in ascending order to the distance to the query object.
   *
   * @param q       the id of the query object
   * @param knnList the query result list
   */
  protected void doKNNQuery(Integer q, KNNList<D> knnList) {
    final Heap<D, Identifiable> pq = new DefaultHeap<D, Identifiable>();

    // push root
    pq.addNode(new PQNode<D>(distanceFunction.nullDistance(), ROOT_NODE_ID.value(), null));
    D d_k = knnList.getKNNDistance();

    // search in tree
    while (!pq.isEmpty()) {
      PQNode<D> pqNode = (PQNode<D>) pq.getMinNode();

      if (pqNode.getKey().compareTo(d_k) > 0) {
        return;
      }

      MTreeNode<O, D> node = getNode(pqNode.getValue().getID());
      Integer o_p = pqNode.getRoutingObjectID();

      // directory node
      if (!node.isLeaf) {
        for (int i = 0; i < node.numEntries; i++) {
          MTreeDirectoryEntry<D> entry = (MTreeDirectoryEntry<D>) node.entries[i];
          Integer o_r = entry.getObjectID();
          D r_or = entry.getCoveringRadius();
          D d1 = o_p != null ? distanceFunction.distance(o_p, q) : distanceFunction.nullDistance();
          D d2 = o_p != null ? distanceFunction.distance(o_r, o_p) : distanceFunction.nullDistance();

          D diff = d1.compareTo(d2) > 0 ? d1.minus(d2) : d2.minus(d1);

          D sum = d_k.plus(r_or);

          if (diff.compareTo(sum) <= 0) {
            D d3 = distanceFunction.distance(o_r, q);
            D d_min = Util.max(d3.minus(r_or), distanceFunction.nullDistance());
            if (d_min.compareTo(d_k) <= 0) {
              pq.addNode(new PQNode<D>(d_min, entry.getNodeID(), o_r));
            }
          }
        }

      }

      // data node
      else {
        for (int i = 0; i < node.numEntries; i++) {
          MTreeEntry<D> entry = node.entries[i];
          Integer o_j = entry.getObjectID();

          D d1 = o_p != null ? distanceFunction.distance(o_p, q) : distanceFunction.nullDistance();
          D d2 = o_p != null ? distanceFunction.distance(o_j, o_p) : distanceFunction.nullDistance();

          D diff = d1.compareTo(d2) > 0 ? d1.minus(d2) : d2.minus(d1);

          if (diff.compareTo(d_k) <= 0) {
            D d3 = distanceFunction.distance(o_j, q);
            if (d3.compareTo(d_k) <= 0) {
              QueryResult<D> queryResult = new QueryResult<D>(o_j, d3);
              knnList.add(queryResult);
              d_k = knnList.getKNNDistance();
            }
          }
        }
      }
    }
  }

  /**
   * Returns the root of this index.
   *
   * @return the root of this index
   */
  protected MTreeNode<O, D> getRoot() {
    return file.readPage(ROOT_NODE_ID.value());
  }

  /**
   * Returns true if in the last component of the specified path an overflow has occured, false
   * otherwise.
   *
   * @param path the path to be tested for overflow
   * @return true if in the last component of the specified path an overflow has occured,
   *         false otherwise
   */
  protected boolean hasOverflow(TreePath path) {
    MTreeNode<O, D> node = getNode(path.getLastPathComponent().getIdentifier());
    if (node.isLeaf())
      return node.getNumEntries() == leafCapacity;

    return node.getNumEntries() == dirCapacity;
  }

  /**
   * Determines recursively the path for inserting the specified object.
   *
   * @param objectID the id of the obbject to be inserted
   * @param path     the path of the current subtree
   * @return the path for inserting the specified object
   */
  protected TreePath findInsertionPath(Integer objectID, TreePath path) {
    MTreeNode<O, D> node = getNode(path.getLastPathComponent().getIdentifier());

    // leaf
    if (node.isLeaf()) {
      return path;
    }

    D nullDistance = distanceFunction.nullDistance();
    List<DistanceEntry<D>> candidatesWithoutExtension = new ArrayList<DistanceEntry<D>>();
    List<DistanceEntry<D>> candidatesWithExtension = new ArrayList<DistanceEntry<D>>();

    for (int i = 0; i < node.numEntries; i++) {
      MTreeDirectoryEntry<D> entry = (MTreeDirectoryEntry<D>) node.entries[i];
      D distance = distanceFunction.distance(objectID, entry.getObjectID());
      D enlrg = distance.minus(entry.getCoveringRadius());

      if (enlrg.compareTo(nullDistance) <= 0) {
        candidatesWithoutExtension.add(new DistanceEntry<D>(entry, distance, i));
      }
      else {
        candidatesWithExtension.add(new DistanceEntry<D>(entry, enlrg, i));
      }
    }

    DistanceEntry<D> bestCandidate;
    if (!candidatesWithoutExtension.isEmpty()) {
      bestCandidate = Collections.min(candidatesWithoutExtension);
    }
    else {
      Collections.sort(candidatesWithExtension);
      bestCandidate = Collections.min(candidatesWithExtension);
      MTreeDirectoryEntry<D> entry = (MTreeDirectoryEntry<D>) bestCandidate.getEntry();
      D cr = entry.getCoveringRadius();
      entry.setCoveringRadius((D) cr.plus(bestCandidate.getDistance()));
    }

    return findInsertionPath(objectID, path.pathByAddingChild(new TreePathComponent(bestCandidate.getEntry(),
                                                                                    bestCandidate.getIndex())));
  }

  /**
   * Performs a batch knn query.
   *
   * @param node     the node for which the query should be performed
   * @param ids      the ids of th query objects
   * @param knnLists the knn lists of the query objcets
   */
  protected void batchNN(MTreeNode<O, D> node, List<Integer> ids, Map<Integer, KNNList<D>> knnLists) {
    if (node.isLeaf()) {
      for (int i = 0; i < node.getNumEntries(); i++) {
        MTreeLeafEntry<D> p = (MTreeLeafEntry<D>) node.getEntry(i);
        for (Integer q : ids) {
          KNNList<D> knns_q = knnLists.get(q);
          D knn_q_maxDist = knns_q.getKNNDistance();

          D dist_pq = distanceFunction.distance(p.getObjectID(), q);
          if (dist_pq.compareTo(knn_q_maxDist) <= 0) {
            knns_q.add(new QueryResult<D>(p.getObjectID(), dist_pq));
          }
        }
      }
    }
    else {
      List<DistanceEntry<D>> entries = getSortedEntries(node, ids);
      for (DistanceEntry<D> distEntry : entries) {
        D minDist = distEntry.getDistance();
        for (Integer q : ids) {
          KNNList<D> knns_q = knnLists.get(q);
          D knn_q_maxDist = knns_q.getKNNDistance();

          if (minDist.compareTo(knn_q_maxDist) <= 0) {
            MTreeDirectoryEntry<D> entry = (MTreeDirectoryEntry<D>) distEntry.getEntry();
            MTreeNode<O, D> child = getNode(entry);
            batchNN(child, ids, knnLists);
            break;
          }
        }
      }
    }
  }

  /**
   * Test the covering radius of specified node (for debugging purpose).
   */
  protected void testCoveringRadius(TreePath rootPath) {
    BreadthFirstEnumeration<MTreeNode<O, D>> bfs = new BreadthFirstEnumeration<MTreeNode<O, D>>(file, rootPath);

    MTreeDirectoryEntry<D> rootID = (MTreeDirectoryEntry<D>) rootPath.getLastPathComponent().getIdentifier();
    Integer routingObjectID = rootID.getObjectID();
    D coveringRadius = rootID.getCoveringRadius();

    while (bfs.hasMoreElements()) {
      TreePath path = bfs.nextElement();
      Identifier id = path.getLastPathComponent().getIdentifier();

      if (id.isNodeID()) {
        MTreeNode<O, D> node = getNode(id);
        node.testCoveringRadius(routingObjectID, coveringRadius, distanceFunction);
      }
    }
  }

  /**
   * Test the specified node (for debugging purpose)
   */
  protected void test(TreePath rootPath) {
    BreadthFirstEnumeration<MTreeNode<O, D>> bfs = new BreadthFirstEnumeration<MTreeNode<O, D>>(file, rootPath);

    while (bfs.hasMoreElements()) {
      TreePath path = bfs.nextElement();
      Identifier id = path.getLastPathComponent().getIdentifier();

      if (id.isNodeID()) {
        MTreeNode<O, D> node = getNode(id);
        node.test();

        if (id instanceof MTreeEntry) {
          MTreeDirectoryEntry<D> e = (MTreeDirectoryEntry<D>) id;
          node.testParentDistance(e.getObjectID(), distanceFunction);
          testCoveringRadius(path);
        }
        else {
          node.testParentDistance(null, distanceFunction);
        }
      }
    }
  }

  /**
   * Determines the maximum and minimum number of entries in a node.
   */
  protected void initCapacity() {
    D dummyDistance = distanceFunction.nullDistance();
    int distanceSize = dummyDistance.externalizableSize();

    // overhead = index(4), numEntries(4), id(4), isLeaf(0.125)
    double overhead = 12.125;
    if (pageSize - overhead < 0)
      throw new RuntimeException("Node size of " + pageSize + " Bytes is chosen too small!");

    // dirCapacity = (pageSize - overhead) / (nodeID + objectID +
    // coveringRadius + parentDistance) + 1
    dirCapacity = (int) (pageSize - overhead) / (4 + 4 + distanceSize + distanceSize) + 1;

    if (dirCapacity <= 1)
      throw new RuntimeException("Node size of " + pageSize + " Bytes is chosen too small!");

    if (dirCapacity < 10)
      logger.severe("Page size is choosen too small! Maximum number of entries " + "in a directory node = " + (dirCapacity - 1));

    // leafCapacity = (pageSize - overhead) / (objectID + parentDistance) +
    // 1
    leafCapacity = (int) (pageSize - overhead) / (4 + distanceSize) + 1;

    if (leafCapacity <= 1)
      throw new RuntimeException("Node size of " + pageSize + " Bytes is chosen too small!");

    if (leafCapacity < 10)
      logger.severe("Page size is choosen too small! Maximum number of entries " + "in a leaf node = " + (leafCapacity - 1));
  }

  /**
   * Creates and returns a new root node that points to the two specified
   * child nodes.
   *
   * @param oldRoot              the old root of this MTree
   * @param newNode              the new split node
   * @param firstPromoted        the first promotion object id
   * @param secondPromoted       the second promotion object id
   * @param firstCoveringRadius  the first covering radius
   * @param secondCoveringRadius the second covering radius
   * @return a new root node that points to the two specified child nodes
   */
  private TreePath createNewRoot(final MTreeNode<O, D> oldRoot, final MTreeNode<O, D> newNode,
                                 Integer firstPromoted, Integer secondPromoted,
                                 D firstCoveringRadius, D secondCoveringRadius) {

    StringBuffer msg = new StringBuffer();

    // create new root
    if (DEBUG) {
      msg.append("create new root \n");
    }
    MTreeNode<O, D> root = new MTreeNode<O, D>(file, dirCapacity, false);
    file.writePage(root);

    // change id in old root and set id in new root
    oldRoot.nodeID = root.getID();
    root.nodeID = ROOT_NODE_ID.value();

    // add entries to new root
    root.addDirectoryEntry(new MTreeDirectoryEntry<D>(firstPromoted, null, oldRoot.getNodeID(), firstCoveringRadius));
    root.addDirectoryEntry(new MTreeDirectoryEntry<D>(secondPromoted, null, newNode.getNodeID(), secondCoveringRadius));

    // adjust the parentDistances
    for (int i = 0; i < oldRoot.numEntries; i++) {
      D distance = distanceFunction.distance(firstPromoted, oldRoot.entries[i].getObjectID());
      oldRoot.entries[i].setParentDistance(distance);
    }
    for (int i = 0; i < newNode.numEntries; i++) {
      D distance = distanceFunction.distance(secondPromoted, newNode.entries[i].getObjectID());
      newNode.entries[i].setParentDistance(distance);
    }
    if (DEBUG) {
      msg.append("firstCoveringRadius ").append(firstCoveringRadius).append("\n");
      msg.append("secondCoveringRadius ").append(secondCoveringRadius).append("\n");
    }

    // write the changes
    file.writePage(root);
    file.writePage(oldRoot);
    file.writePage(newNode);

    if (DEBUG) {
      msg.append("New Root-ID ").append(root.nodeID).append("\n");
      logger.fine(msg.toString());
    }

    return new TreePath(new TreePathComponent(ROOT_NODE_ID, null));
  }

  /**
   * Splits the last node in the specified path and returns a path
   * containing at last element the parent of the newly created split node.
   *
   * @param path the path containing at last element the node to be splitted
   * @return a path containing at last element the parent of the newly created split node
   */
  private TreePath split(TreePath path) {
    MTreeNode<O, D> node = getNode(path.getLastPathComponent().getIdentifier());
    Integer nodeIndex = path.getLastPathComponent().getIndex();

    // do split
    Split<O, D> split = new MLBDistSplit<O, D>(node, distanceFunction);
    Assignments<D> assignments = split.getAssignments();
    MTreeNode<O, D> newNode = node.splitEntries(assignments.getFirstAssignments(),
                                                assignments.getSecondAssignments());

    if (DEBUG) {
      String msg = "Split Node " + node.getID() + " (" + this.getClass() + ")\n" +
                   "      newNode " + newNode.getID() + "\n" +
                   "      firstPromoted " + assignments.getFirstRoutingObject() + "\n" +
                   "      firstAssignments(" + node.getID() + ") " + assignments.getFirstAssignments() + "\n" +
                   "      firstCR " + assignments.getFirstCoveringRadius() + "\n" +
                   "      secondPromoted " + assignments.getSecondRoutingObject() + "\n" +
                   "      secondAssignments(" + newNode.getID() + ") " + assignments.getSecondAssignments() + "\n" +
                   "      secondCR " + assignments.getSecondCoveringRadius() + "\n";
      logger.fine(msg);
    }

    // write changes to file
    file.writePage(node);
    file.writePage(newNode);

    // if root was split: create a new root that points the two split nodes
    if (node.getID() == ROOT_NODE_ID.value()) {
      return createNewRoot(node, newNode,
                           assignments.getFirstRoutingObject(), assignments.getSecondRoutingObject(),
                           assignments.getFirstCoveringRadius(), assignments.getSecondCoveringRadius());
    }

    // determine the new parent distances
    MTreeNode<O, D> parent = getNode(path.getParentPath().getLastPathComponent().getIdentifier());
    Integer parentIndex = path.getParentPath().getLastPathComponent().getIndex();
    MTreeNode<O, D> grandParent;
    D parentDistance1 = null, parentDistance2 = null;

    if (parent.getID() != ROOT_NODE_ID.value()) {
      grandParent = getNode(path.getParentPath().getParentPath().getLastPathComponent().getIdentifier());
      Integer parentObject = grandParent.entries[parentIndex].getObjectID();
      parentDistance1 = distanceFunction.distance(assignments.getFirstRoutingObject(), parentObject);
      parentDistance2 = distanceFunction.distance(assignments.getSecondRoutingObject(), parentObject);
    }

    // add the newNode to parent
    parent.addDirectoryEntry(new MTreeDirectoryEntry<D>(assignments.getSecondRoutingObject(), parentDistance2, newNode.getNodeID(), assignments.getSecondCoveringRadius()));

    // set the first promotion object, parentDistance and covering radius
    // for node in parent
    MTreeDirectoryEntry<D> entry1 = (MTreeDirectoryEntry<D>) parent.entries[nodeIndex];
    entry1.setObjectID(assignments.getFirstRoutingObject());
    entry1.setParentDistance(parentDistance1);
    entry1.setCoveringRadius(assignments.getFirstCoveringRadius());

    // adjust the parentDistances in node
    for (int i = 0; i < node.numEntries; i++) {
      D distance = distanceFunction.distance(assignments.getFirstRoutingObject(), node.entries[i].getObjectID());
      node.entries[i].setParentDistance(distance);
    }

    // adjust the parentDistances in newNode
    for (int i = 0; i < newNode.numEntries; i++) {
      D distance = distanceFunction.distance(assignments.getSecondRoutingObject(), newNode.entries[i].getObjectID());
      newNode.entries[i].setParentDistance(distance);
    }

    // write changes in parent to file
    file.writePage(parent);

    return path.getParentPath();
  }

  /**
   * Sorts the entries of the specified node according to their minimum
   * distance to the specified objects.
   *
   * @param node the node
   * @param ids  the ids of the objects
   * @return a list of the sorted entries
   */
  private List<DistanceEntry<D>> getSortedEntries(MTreeNode<O, D> node, List<Integer> ids) {
    List<DistanceEntry<D>> result = new ArrayList<DistanceEntry<D>>();

    for (int i = 0; i < node.getNumEntries(); i++) {
      MTreeDirectoryEntry<D> entry = (MTreeDirectoryEntry<D>) node.getEntry(i);

      D minMinDist = distanceFunction.infiniteDistance();
      for (Integer q : ids) {
        D distance = distanceFunction.distance(entry.getObjectID(), q);
        D minDist = entry.getCoveringRadius().compareTo(distance) > 0 ? distanceFunction.nullDistance() : distance.minus(entry.getCoveringRadius());
        if (minDist.compareTo(minMinDist) < 0) {
          minMinDist = minDist;
        }
      }
      result.add(new DistanceEntry<D>(entry, minMinDist, i));
    }

    Collections.sort(result);
    return result;
  }
}
