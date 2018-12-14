package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.mutable
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.wire.ChannelUpdate
import scala.collection.JavaConverters._

object Graph {

	import DirectedGraph._

	/**
		* Finds the shortest path in the graph, Dijsktra's algorithm
		*
		* @param g
		* @param sourceNode
		* @param targetNode
		* @param amountMsat
		* @return
		*/
	def shortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long, ignoredEdges: Seq[ChannelDesc], extraEdges: Seq[GraphEdge]): Seq[Hop] = {
		dijkstraShortestPath(g, sourceNode, targetNode, amountMsat, ignoredEdges, extraEdges).map(graphEdgeToHop)
	}

	//TBD the cost for the neighbors of the sourceNode is always 0
	def dijkstraShortestPath(g: DirectedGraph, sourceNode: PublicKey, targetNode: PublicKey, amountMsat: Long, ignoredEdges: Seq[ChannelDesc], extraEdges: Seq[GraphEdge]): Seq[GraphEdge] = {

		//optionally add the extra edges to the graph
		val graphVerticesWithExtra = extraEdges.nonEmpty match {
			case true => g.vertexSet() ++ extraEdges.map(_.desc.a).toSet ++ extraEdges.map(_.desc.b).toSet
			case false => g.vertexSet()
		}

		//the graph does not contain source/destination nodes
		if (!graphVerticesWithExtra.contains(sourceNode)) return Seq.empty
		if (!graphVerticesWithExtra.contains(targetNode)) return Seq.empty

		val maxMapSize = graphVerticesWithExtra.size + 1

		val cost = new java.util.HashMap[PublicKey, Long](maxMapSize)
		val prev = new java.util.HashMap[PublicKey, (GraphEdge, PublicKey)]
		val vertexQueue = new PriorityQueue[PublicKey](maxMapSize)

		//initialize the queue with the vertices having max distance
		graphVerticesWithExtra.foreach {
			case pk if pk == sourceNode =>
				cost.put(pk, 0) // starting node has distance 0
				vertexQueue.enqueue(pk, 0)
			case pk =>
				cost.put(pk, Long.MaxValue)
				vertexQueue.enqueue(pk, Long.MaxValue)
		}

		var targetFound = false

		while (!vertexQueue.isEmpty && !targetFound) {

			//(next) node with the smallest distance from the source
			val current = vertexQueue.dequeue() //O(log(n))

			if (current != targetNode) {

				//build the neighbors with optional extra edges
				val currentNeighbors = extraEdges.isEmpty match {
					case true => g.edgesOf(current)
					case false => g.edgesOf(current) ++ extraEdges.filter(_.desc.a == current)
				}

				//for each neighbor
				currentNeighbors.foreach { edge =>

					// test here for ignored edges
					if (!(edge.update.htlcMaximumMsat.exists(_ < amountMsat) ||
						amountMsat < edge.update.htlcMinimumMsat ||
						ignoredEdges.contains(edge.desc))
					) {

						val neighbor = edge.desc.b

						val newMinimumKnownCost = cost.get(current) + edgeWeightByAmount(edge, amountMsat)

						val neighborCost = cost.get(neighbor)
						//if this neighbor has a shorter distance than previously known
						if (newMinimumKnownCost < neighborCost) {

							//update the visiting tree
							prev.put(neighbor, (edge, current))

							//update the queue
							vertexQueue.enqueueOrUpdate(neighbor, newMinimumKnownCost) //O(log(n))

							//update the minimum known distance array
							cost.put(neighbor, newMinimumKnownCost)
						}
					}
				}
			} else { //we popped the target node from the queue, no need to search any further
				targetFound = true
			}
		}

		//we traverse the list of "previous" backward building the final list of edges that make the shortest path
		//FIXME expensive
		val edgePath = new mutable.MutableList[GraphEdge]
		var current = targetNode

		while (prev.containsKey(current)) {

			val temp = prev.get(current)
			edgePath += temp._1
			current = temp._1.desc.a
		}

		//if there is a path source -> ... -> target then 'current' must be the source node at this point
		if (current != sourceNode)
			Seq.empty //path not found
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
			*
			* @param desc
			* @param update
			*/
		case class GraphEdge(desc: ChannelDesc, update: ChannelUpdate)

		case class DirectedGraph(private val vertices: Map[PublicKey, Seq[GraphEdge]]) {

			def addEdge(d: ChannelDesc, u: ChannelUpdate): DirectedGraph = addEdge(GraphEdge(d, u))

			def addEdges(edges: Seq[(ChannelDesc, ChannelUpdate)]): DirectedGraph = {
				edges.foldLeft(this)((acc, edge) => acc.addEdge(edge._1, edge._2))
			}

			/**
				* Adds and edge to the graph, if one of the two vertices is not found, it will be created
				*
				* @param d      the channel desc
				* @param u      the channel update
				* @param weight the weight of this edge
				* @return a new graph containing this edge
				*/
			def addEdge(edge: GraphEdge): DirectedGraph = {

				val vertexIn = edge.desc.a
				val vertexOut = edge.desc.b

				//the graph is allowed to have multiple edges between the same vertices but only one per channel
				if (containsEdge(edge.desc)) {
					removeEdge(edge.desc).addEdge(edge)
				} else {
					val withVertices = addVertex(vertexIn).addVertex(vertexOut)
					DirectedGraph(withVertices.vertices.updated(vertexIn, withVertices.vertices(vertexIn) :+ edge))
				}
			}

			/**
				* Removes the edge corresponding to the given pair channel-desc/channel-update,
				* NB: this operation does NOT remove any vertex
				*
				* @param d
				* @return
				*/
			def removeEdge(desc: ChannelDesc): DirectedGraph = {
				containsEdge(desc) match {
					case true => DirectedGraph(vertices.updated(desc.a, vertices(desc.a).filterNot(_.desc == desc)))
					case false => this
				}
			}

			def removeEdge(edge: GraphEdge): DirectedGraph = removeEdge(edge.desc)

			def removeEdgesList(edgeList: Seq[GraphEdge]): DirectedGraph = removeEdges(edgeList.map(_.desc))

			def removeEdges(descList: Seq[ChannelDesc]): DirectedGraph = {
				descList.foldLeft(this)((acc, edge) => acc.removeEdge(edge))
			}

			/**
				* @param edge
				* @return For edges to be considered equal they must have the same in/out vertices AND same shortChannelId
				*/
			def getEdge(edge: GraphEdge): Option[GraphEdge] = getEdge(edge.desc)

			def getEdge(shortChannelId: Long): Option[GraphEdge] = edgeSet().find(_.desc.shortChannelId.toLong == shortChannelId)

			def getEdge(desc: ChannelDesc): Option[GraphEdge] = vertices.get(desc.a).flatMap { adj =>
				adj.find(e => e.desc.shortChannelId == desc.shortChannelId && e.desc.b == desc.b)
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

			def getIncomingEdgesOf(keyA: PublicKey): Seq[GraphEdge] = {
				edgeSet().filter(_.desc.b == keyA).toSeq
			}

			/**
				* Removes a vertex and all it's associated edges (both incoming and outgoing)
				*
				* @param key
				* @return
				*/
			def removeVertex(key: PublicKey): DirectedGraph = {
				DirectedGraph(removeEdgesList(getIncomingEdgesOf(key)).vertices - key)
			}

			def removeVertices(keys: Seq[PublicKey]): DirectedGraph = {
				keys.foldLeft(this)((acc, vertex) => acc.removeVertex(vertex))
			}

			/**
				* Adds a new vertex to the graph, starting with no edges
				*
				* @param key
				* @return
				*/
			def addVertex(key: PublicKey): DirectedGraph = {
				vertices.get(key) match {
					case None => DirectedGraph(vertices + (key -> Seq.empty))
					case _ => this
				}
			}

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

			def containsEdge(desc: ChannelDesc): Boolean = vertices.get(desc.a) match {
				case None => false
				case Some(adj) => adj.exists(neighbor => neighbor.desc.shortChannelId == desc.shortChannelId && neighbor.desc.b == desc.b)
			}

			/**
				* @param predicate
				* @return a subset of this graph with only edges NOT satisfying the predicate
				*/
			def filterNot(predicate: GraphEdge => Boolean): DirectedGraph = {
				removeEdgesList(edgeSet().filter(predicate).toSeq)
			}

			def prettyPrint(): String = {
				vertices.foldLeft("") { case (acc, (vertex, adj)) =>
					acc + s"[${vertex.toString().take(5)}]: ${adj.map("-> " + _.desc.b.toString().take(5))} \n"
				}
			}
		}

		object DirectedGraph {

			//convenience constructors
			def apply(): DirectedGraph = new DirectedGraph(Map())

			def apply(key: PublicKey): DirectedGraph = new DirectedGraph(Map((key -> Seq.empty)))

			def apply(edge: GraphEdge): DirectedGraph = new DirectedGraph(Map()).addEdge(edge.desc, edge.update)

			def apply(edges: Seq[GraphEdge]): DirectedGraph = {
				makeGraph(edges.map(e => e.desc -> e.update).toMap)
			}

			//optimized constructor
			def makeGraph(descAndUpdates: Map[ChannelDesc, ChannelUpdate]): DirectedGraph = {

				//initialize the map with the appropriate size to avoid resizing during the graph initialization
				val mutableMap = new {} with mutable.HashMap[PublicKey, Seq[GraphEdge]] {
					override def initialSize: Int = descAndUpdates.size + 1
				}

				//add all the vertices and edges in one go
				descAndUpdates.foreach { case (desc, update) =>
					//create or update vertex (desc.a) and update its neighbor
					mutableMap.put(desc.a, mutableMap.getOrElse(desc.a, Seq.empty[GraphEdge]) :+ GraphEdge(desc, update))
					mutableMap.get(desc.b) match {
						case None => mutableMap += desc.b -> Seq.empty[GraphEdge]
						case _ =>
					}
				}

				new DirectedGraph(mutableMap.toMap)
			}

			def graphEdgeToHop(graphEdge: GraphEdge): Hop = Hop(graphEdge.desc.a, graphEdge.desc.b, graphEdge.update)
		}

	}

	case class PriorityElem[T](data: T, weight: Double)
	/**
		* A stateful priority queue using efficient binary heap and providing decrease/increase priority operations (log(n))
		*/
	class PriorityQueue[T](var heap: Array[PriorityElem[T]], var indexes: java.util.HashMap[T, Int], var lastIndex: Int) {
		//Constructor allocating an array of size + 1
		def this(size: Int) = this(new Array[PriorityElem[T]](size + 1), new java.util.HashMap[T, Int](size), 0)

		def this() = this(new Array[PriorityElem[T]](100 + 1), new java.util.HashMap[T, Int](100 + 1), 0)

		def isEmpty(): Boolean = lastIndex == 0

		//Enqueue an element, the new element is added as last leaf of the binary heap and then moved up to the proper level (weight)
		def enqueue(data: T, weight: Double): Unit = {
			//if the heap is full, double the array size
			if (lastIndex == heap.length - 1) {
				val newHeap = new Array[PriorityElem[T]](heap.length * 2)
				System.arraycopy(heap, 0, newHeap, 0, lastIndex)
				heap = newHeap
			}
			//insert the new element as leaf in the heap
			lastIndex += 1
			heap(lastIndex) = PriorityElem(data, weight)
			indexes.put(data, lastIndex)
			moveUp(lastIndex)
		}

		//Extracts the root of the tree (heap), replaces it with the last leaf and then moves it down
		def dequeue(): T = {
			if (isEmpty()) throw new IllegalArgumentException("Empty queue!")
			val ris = heap(1).data
			val lastLeaf = heap(lastIndex)
			heap(lastIndex) = null
			lastIndex -= 1
			if (lastIndex > 0) {
				heap(1) = lastLeaf
				moveDown(1)
			}
			ris
		}

		//Deletes an element from the queue, after removal the tree is rebalanced
		def remove(value: T): Unit = {
			val index = indexes.get(value)
			//edge case: the element is in the last position
			if (index == lastIndex) {
				heap(lastIndex) = null
				lastIndex -= 1
				return
			}
			//overwrite the node containing @data with the last leaf
			heap(index) = heap(lastIndex)
			lastIndex -= 1
			moveDown(index)
			moveUp(index)
		}

		//Updates the weight of an element or enqueues it
		def enqueueOrUpdate(value: T, weight: Double): Unit = {
			indexes.containsKey(value) match {
				case false => enqueue(value, weight)
				case true =>
					val targetIndex = indexes.get(value)
					heap(targetIndex) = PriorityElem(value, weight)
					moveDown(targetIndex)
					moveUp(targetIndex)
			}
		}

		//moves up the element at @index, until the correct level is reached
		private def moveUp(index: Int): Unit = {
			var i = index
			if (i < 1 || i > lastIndex) throw new IllegalArgumentException(s"Index $i not found")
			val tmp = heap(i)
			while (i > 1 && tmp.weight < heap(i / 2).weight) {
				heap(i) = heap(i / 2)
				indexes.put(heap(i).data, i) //update the position
				i = i / 2 //one level up
			}
			indexes.put(tmp.data, i)
			heap(i) = tmp
		}

		//moves an element down to the correct level according to its weight
		private def moveDown(index: Int): Unit = {
			var i = index
			if (i > lastIndex) throw new IllegalArgumentException(s"Index $i not found")
			val tmp = heap(i)
			var j = i * 2
			var found = false
			while (!found && j < lastIndex) {
				if (j + 1 <= lastIndex && heap(j + 1).weight < heap(j).weight) {
					//j is now the smaller of the children
					j += 1
				}
				if (tmp.weight <= heap(j).weight) {
					found = true
				} else {
					heap(i) = heap(j)
					indexes.put(heap(i).data, i)
					i = j
					j = i * 2 //go one level deeper
				}
			}
			indexes.put(tmp.data, i)
			heap(i) = tmp
		}
	}

}
