package fr.acinq.eclair.router

import fr.acinq.bitcoin.Crypto.PublicKey
import scala.collection.mutable
import fr.acinq.eclair._
import fr.acinq.eclair.router.Graph.GraphStructure.{DirectedGraph, GraphEdge}
import fr.acinq.eclair.wire.ChannelUpdate

object Graph {

	import DirectedGraph._

	implicit object QueueComparator extends Ordering[WeightedNode] {
		override def compare(x: WeightedNode, y: WeightedNode): Int = x.weight.compareTo(y.weight)
	}

	case class WeightedNode(publicKey: PublicKey, weight: Long)

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

		val cost = new java.util.HashMap[PublicKey, Long]
		val prev = new mutable.HashMap[GraphEdge, PublicKey]
		val vertexQueue = new java.util.PriorityQueue[WeightedNode](QueueComparator)

		//optionally add the extra edges to the graph
		val graphVerticesWithExtra = g.vertexSet() ++ extraEdges.map(_.desc.a).toSet ++ extraEdges.map(_.desc.b).toSet

		//initialize the queue with the vertices having max distance
		graphVerticesWithExtra.foreach {
			case pk if pk == sourceNode =>
				cost.put(pk, 0) // starting node has distance 0
				vertexQueue.add(WeightedNode(pk, 0))
			case pk =>
				cost.put(pk, Long.MaxValue)
				vertexQueue.add(WeightedNode(pk, Long.MaxValue))
		}

		//the graph does not contain source/destination nodes
		if (!graphVerticesWithExtra.contains(sourceNode)) return Seq.empty
		if (!graphVerticesWithExtra.contains(targetNode)) return Seq.empty

		var targetFound = false

		while (!vertexQueue.isEmpty && !targetFound) {

			//(next) node with the smallest distance from the source
			val current = vertexQueue.poll() //O(log(n))

			if (current.publicKey != targetNode) {

				//build the neighbors with optional extra edges
				val currentNeighbors = extraEdges.isEmpty match {
					case true => g.edgesOf(current.publicKey)
					case false => g.edgesOf(current.publicKey) ++ extraEdges.filter(_.desc.a == current.publicKey)
				}

				//for each neighbor
				currentNeighbors.foreach { edge =>

					// test here for ignored edges
					if (!((edge.update.htlcMaximumMsat.isDefined && amountMsat > edge.update.htlcMaximumMsat.get) ||
						amountMsat < edge.update.htlcMinimumMsat ||
						ignoredEdges.contains(edge.desc))
					) {

						val neighbor = edge.desc.b

						val newMinimumKnownCost = cost.get(current.publicKey) + edgeWeightByAmount(edge, amountMsat)

						val neighborCost = cost.get(neighbor)
						//if this neighbor has a shorter distance than previously known
						if (newMinimumKnownCost < neighborCost) {

							//update the visiting tree //FIXME expensive
							prev.find(_._1.desc.b == neighbor) match {
								// if there was already a pointer to that node we need to remove it before adding the new edge
								case Some((desc, _)) =>
									prev.remove(desc)
									prev.put(edge, current.publicKey)
								case None =>
									prev.put(edge, current.publicKey)
							}

							//update the queue, remove and insert
							vertexQueue.remove(WeightedNode(neighbor, neighborCost)) //O(n) //FIXME expensive
							vertexQueue.add(WeightedNode(neighbor, newMinimumKnownCost)) //O(log(n))

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
		val edgePath = new mutable.MutableList[GraphEdge]
		var current = targetNode

		while (prev.exists(_._1.desc.b == current)) {

			val Some((temp, _)) = prev.find(_._1.desc.b == current)
			edgePath += temp
			current = temp.desc.a
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

}
