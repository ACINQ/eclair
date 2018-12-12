package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.mutable
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{GraphEdge, DirectedGraph}
import fr.acinq.eclair.wire.ChannelUpdate

object Graph {

  import DirectedGraph._

  implicit object QueueComparator extends Ordering[WeightedNode] {
    override def compare(x: WeightedNode, y: WeightedNode): Int = x.weight.compareTo(y.weight)
  }

  implicit object PathComparator extends Ordering[WeightedPath] {
    override def compare(x: WeightedPath, y: WeightedPath): Int = y.cost.compareTo(x.cost)
  }

  case class WeightedNode(publicKey: PublicKey, weight: Long)
  case class WeightedPath(path: Seq[GraphEdge], cost: Long)

  /**
    * Finds the shortest path in the graph, Dijsktra's algorithm
    * @param g
    * @param sourceNode
    * @param targetNode
    * @param amountMsat
    * @return
    */
  def shortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long): Seq[Hop] = {
    dijkstraShortestPath(g, sourceNode, targetNode, amountMsat).map(graphEdgeToHop)
  }

  /**
    * Yen's algorithm to find the k-shortest (loopless) paths in a graph, uses dijkstra as search algo. Is guaranteed to terminate finding
    * at most @numbersOfPathsToFind paths sorted by cost (the cheapest is in position 0).
    * @param graph
    * @param sourceNode
    * @param targetNode
    * @param amountMsat
    * @param numberOfPathsToFind
    * @return
    */
  def yenKshortestPaths(graph: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long, numberOfPathsToFind: Int): Seq[WeightedPath] = {

    var allSpurPathsFound = false

    //A stores the k shortest path
    val shortestPaths = new mutable.MutableList[WeightedPath]
    shortestPaths += dijkstraShortestPathWithCost(graph, sourceNode, targetNode, amountMsat)

    //stores the candidates for k(K +1) shortest paths, sorted by path cost
    val candidate = new mutable.PriorityQueue[WeightedPath]

    //main loop
    for(k <- 1 until numberOfPathsToFind) {

      if ( !allSpurPathsFound ) {

        //for every edge in the path
        for (i <- 0 to shortestPaths(k - 1).path.size - 1) {

          //select the spur node as the i-th element of the k-th previous shortest path (k -1)
          val spurEdge = shortestPaths(k - 1).path(i)

          // select the subpath from the source to the spur node of the k-th previous shortest path
          val rootPathEdges = subList(shortestPaths(k - 1).path, 0, i).toList

          //subgraph NOT containing the links that are part of the previous shortest path and which share the same root path
          val mutatedGraph = shortestPaths.foldLeft(graph) { (acc, p) =>
            if (subList(p.path, 0, i) == rootPathEdges) {
              acc.removeEdge(p.path(i))
            } else {
              acc
            }
          }

          //find the "spur" path, a subpath going from the spur edge to the target avoiding previously found subpaths
          val spurPath = dijkstraShortestPathWithCost(mutatedGraph, spurEdge.desc.a, targetNode, amountMsat)

          //candidate shortest path is made of the rootPath and the new spurPath
          val totalPath = concat(rootPathEdges, spurPath.path.toList)
          val candidatePath = WeightedPath(totalPath, pathCost(totalPath, amountMsat))

          if (spurPath.path.nonEmpty) {
            candidate.enqueue(candidatePath)
          }
        }
      }

      if(candidate.isEmpty) {
        //handles the case of having exhausted all possible spur paths and it's impossible to reach the target from the source
        allSpurPathsFound = true
      } else {
        //move the best candidate from in the container A
        shortestPaths += candidate.dequeue()
      }
    }

    shortestPaths
  }

  //smart concatenation of paths given the edge lists, if the rootPath begins with the same vertex then discard that
  def concat(rootPath: List[GraphEdge], spurPath: List[GraphEdge]): List[GraphEdge] = (rootPath, spurPath) match {
    case (Nil, _) => spurPath
    case (_, Nil) => rootPath
    case (root :: otherRoot, spurHead :: _ ) => if(root.desc.a == spurHead.desc.a) concat(otherRoot, spurPath) else rootPath ++ spurPath
  }

  def edgeListToVertexList(edges: Seq[GraphEdge]): Seq[PublicKey] = edges.toList match {
    case Nil => Seq.empty
    case last :: Nil => Seq(last.desc.a, last.desc.b)
    case edge :: tail => edge.desc.a +: edgeListToVertexList(tail)
  }

  def pathCost(path: Seq[GraphEdge], amountMsat: Long): Long = {
    path.foldLeft(0L)( (acc, edge) => acc + nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountMsat) )
  }

  //helper function implementing the subList function for "Seq[T]" that will return the list with the
  //first element if indices 0 are used
  def subList[T](list: Seq[T], from: Int, to: Int): Seq[T] = {
    if(from == 0 && to == 0) {
      list.head :: Nil
    } else {
      list.slice(from, to)
    }
  }

  def dijkstraShortestPathWithCost(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long): WeightedPath = {
    val path = dijkstraShortestPath(g, sourceNode, targetNode, amountMsat)
    WeightedPath(path, pathCost(path, amountMsat))
  }

  //TBD the cost for the neighbors of the sourceNode is always 0
  def dijkstraShortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long): Seq[GraphEdge] = {

    val cost = new mutable.HashMap[PublicKey, Long]
    val prev = new mutable.HashMap[GraphEdge, PublicKey]
    val vertexQueue = new mutable.TreeSet[WeightedNode] //a sorted tree is used to have the remove operation

    //initialize the queue with the vertices having max distance
    g.vertexSet().foreach {
      case pk if pk == sourceNode =>
        cost += pk -> 0 // starting node has distance 0
        vertexQueue.add(WeightedNode(pk, 0))
      case pk                     =>
        cost += pk -> Long.MaxValue
        vertexQueue.add(WeightedNode(pk, Long.MaxValue))
    }

    var targetFound = false

    while(vertexQueue.nonEmpty && !targetFound){

      //(next) node with the smallest distance from the source
      val current = vertexQueue.firstKey
      vertexQueue.remove(current)

      if(current.publicKey != targetNode) {

        //for each neighbor
        g.edgesOf(current.publicKey).foreach { edge =>

          val neighbor = edge.desc.b

          val newMinimumKnownCost = cost(current.publicKey) + edgeWeightByAmount(edge, amountMsat)

          //if this neighbor has a shorter distance than previously known
          if (newMinimumKnownCost < cost(neighbor)) {

            //update the visiting tree
            prev.find(_._1.desc.b == neighbor) match {
              // if there was already a pointer to that node we need to remove it before adding the new edge
              case Some( (desc, _) ) =>
                prev.remove(desc)
                prev.put(edge, current.publicKey)
              case None =>
                prev.put(edge, current.publicKey)
            }

            //update the queue, remove and insert
            vertexQueue.remove(WeightedNode(neighbor, cost(neighbor)))
            vertexQueue.add(WeightedNode(neighbor, newMinimumKnownCost))

            //update the minimum known distance array
            cost.update(neighbor, newMinimumKnownCost)
          }
        }
      } else { //we popped the target node from the queue, no need to search any further
        targetFound = true
      }
    }

    //we traverse the list of "previous" backward building the final list of edges that make the shortest path
    val edgePath = new mutable.MutableList[GraphEdge]
    var current = targetNode

    while( prev.exists(_._1.desc.b == current) ) {

      val Some( (temp, _) ) = prev.find(_._1.desc.b == current)
      edgePath += temp
      current = temp.desc.a
    }

    //if there is a path source -> ... -> target then 'current' must be the source node at this point
    if(current != sourceNode)
      Seq.empty  //path not found
    else
      edgePath.reverse
  }

  private def edgeWeightByAmount(edge: GraphEdge, amountMsat: Long): Long = {
    nodeFee(edge.update.feeBaseMsat, edge.update.feeProportionalMillionths, amountMsat)
  }


  /**
    * A graph data structure that uses the adjacency lists
    */
  object GraphStructure {

    /**
      * Representation of an edge of the graph
      * @param desc
      * @param update
      */
    case class GraphEdge(desc: ChannelDesc, update: ChannelUpdate)

    case class DirectedGraph(private val vertices: Map[PublicKey, Seq[GraphEdge]]) {

      def addEdge(d: ChannelDesc, u: ChannelUpdate): DirectedGraph = addEdge(GraphEdge(d, u))

      def addEdges( edges: Seq[(ChannelDesc, ChannelUpdate)]): DirectedGraph = {
        edges.foldLeft(this)( (acc, edge) => acc.addEdge(edge._1, edge._2))
      }

      /**
        * Adds and edge to the graph, if one of the two vertices is not found, it will be created
        *
        * @param d the channel desc
        * @param u the channel update
        * @param weight the weight of this edge
        * @return a new graph containing this edge
        */
      def addEdge(edge: GraphEdge): DirectedGraph = {
        val vertexIn = edge.desc.a
        val vertexOut = edge.desc.b

        //the graph is allowed to have multiple edges between the same vertices but only one per channel
        if(containsEdge(edge.desc)) {
          return removeEdge(edge.desc).addEdge(edge)
        }

        //if vertices does not contain vertexIn/vertexOut, we create it first
        (vertices.contains(vertexIn), vertices.contains(vertexOut)) match {
          case (false, false) =>  //none of the vertices of this edge exists, create them and add the edge
            addVertex(vertexIn).addVertex(vertexOut).addEdge(edge)
          case (false, true) =>
            addVertex(vertexIn).addEdge(edge)
          case (true, false) =>
            addVertex(vertexOut).addEdge(edge)
          case (true, true) =>
            DirectedGraph(vertices.updated(vertexIn, vertices(vertexIn) :+ edge))
        }
      }

      /**
        * Removes the edge corresponding to the given pair channel-desc/channel-update
        * @param d
        * @return
        */
      def removeEdge(desc: ChannelDesc): DirectedGraph = {
        containsEdge(desc) match {
          case true => DirectedGraph(vertices.updated(desc.a, vertices(desc.a).filterNot { neighbor =>
            neighbor.desc == desc && neighbor.desc.shortChannelId == desc.shortChannelId
          }))
          case false => this
        }
      }

      def removeEdge(edge: GraphEdge): DirectedGraph = removeEdge(edge.desc)

      def removeEdgesList(edgeList: Seq[GraphEdge] ): DirectedGraph = removeEdges(edgeList.map(_.desc))

      def removeEdges(descList: Seq[ChannelDesc]): DirectedGraph = {
        descList.foldLeft(this)( (acc, edge ) => acc.removeEdge(edge) )
      }

      /**
        * @param edge
        * @return For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
        */
      def getEdge(edge: GraphEdge): Option[GraphEdge] = getEdge(edge.desc)

      def getEdge(desc: ChannelDesc): Option[GraphEdge] = vertices.get(desc.a).flatMap { adj =>
        adj.find(e => e.desc.b == desc.b && e.desc.shortChannelId == desc.shortChannelId)
      }

      /**
        * @param keyA
        * @param keyB
        * @return all the edges going from keyA --> keyB (there might be more than one if it refers to different shortChannelId)
        */
      def getEdgesBetween(keyA: PublicKey, keyB: PublicKey): Seq[GraphEdge] = {
        vertices.get(keyA) match {
          case None => Seq.empty
          case Some(adj) => adj.filter(e => e.desc.b == keyB)
        }
      }

      /**
        * Removes a vertex and all it's associated edges
        * @param key
        * @return
        */
      def removeVertex( key: PublicKey ): DirectedGraph = {
        vertices.get(key) match {
          case None => this
          case Some(_) => DirectedGraph(vertices - key)
        }
      }

      def removeVertices(keys: Seq[PublicKey]): DirectedGraph = {
        keys.foldLeft(this)( (acc, vertex) => acc.removeVertex(vertex) )
      }

      /**
        * Adds a new vertex to the graph, starting with no edges
        * @param key
        * @return
        */
      def addVertex(key: PublicKey): DirectedGraph = DirectedGraph(vertices + (key -> Seq.empty))

      /**
        * @param key
        * @return a list of the outgoing edges of vertex @param key, if the edge doesn't exists an empty list is returned
        */
      def edgesOf(key: PublicKey): Seq[GraphEdge] = vertices.getOrElse(key, Seq.empty)

      /**
        * @return the set of all the vertices in this graph
        */
      def vertexSet(): Set[PublicKey] = vertices.keySet

      /**
        * @return the set of all the vertices in this graph
        */
      def edgeSet(): Set[GraphEdge] = vertices.values.flatten.toSet

      /**
        * @param key
        * @return true if this graph contain a vertex with this key, false otherwise
        */
      def containsVertex(key: PublicKey): Boolean = vertices.contains(key)

      /**
        * @param edge
        * @return true if this edge is in the graph. For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
        */
      def containsEdge(edge: GraphEdge): Boolean = containsEdge(edge.desc)

      def containsEdge(desc: ChannelDesc): Boolean = vertices.exists { case (key, adj) =>
         key == desc.a && adj.exists(neighbor => neighbor.desc.b == desc.b && neighbor.desc.shortChannelId == desc.shortChannelId)
      }

      /**
        * Checks for the existence of at least one edge between the given two vertices
        * @param vertexA
        * @param vertexB
        * @return
        */
      def containsEdge(vertexA: PublicKey, vertexB: PublicKey): Boolean = {
        vertices.exists { case (key, adj) => key == vertexA && adj.exists(_.desc.b == vertexB) }
      }

      /**
        * @param predicate
        * @return a subset of this graph with only edges satisfying the predicate
        */
      def filterBy( predicate: GraphEdge => Boolean ): DirectedGraph = {
        removeEdgesList(edgeSet().filter(predicate).toSeq)
      }

      def filterByVertex( predicate: PublicKey => Boolean ): DirectedGraph = {
        removeVertices(vertexSet().filter(predicate).toSeq)
      }
    }

    object DirectedGraph {

      //convenience constructors
      def apply(): DirectedGraph = new DirectedGraph( Map( ))
      def apply(key: PublicKey): DirectedGraph = new DirectedGraph( Map((key -> Seq.empty)) )
      def apply(edge: GraphEdge): DirectedGraph = new DirectedGraph( Map() ).addEdge(edge.desc, edge.update)
      def apply(edges: Seq[GraphEdge]): DirectedGraph = {
        edges.foldLeft(new DirectedGraph( Map() ))((acc, edge) => acc.addEdge(edge.desc, edge.update))
      }


      def graphEdgeToHop(graphEdge: GraphEdge): Hop = Hop(graphEdge.desc.a, graphEdge.desc.b, graphEdge.update)
    }
  }
}
