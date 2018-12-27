package com.github.kilianB.dataStrorage.tree;

import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.PriorityQueue;

import com.github.kilianB.matcher.Hash;

/**
 * A not thread safe binary tree implementation used to quickly compute the
 * <a href="https://en.wikipedia.org/wiki/Hamming_distance">hamming distance</a>
 * of multiple hashes. The tree can be used to keep all hashes in memory, if a
 * persistent storage is required take a look at the database examples.
 * <p>
 * 
 * To keep storage space minimal the tree is lazily populated and creates nodes
 * on the fly only if the node is required to represent a hash.
 * </p>
 * 
 * <b>Example </b>
 * 
 * Hash(1011011)
 * <ul>
 * <li>The left child of a node represents a 1 bit</li>
 * <li>The right child of a node represents a 0 bit</li>
 * <li>Padding bit 1 is ignored as it's the same</li>
 * </ul>
 * 
 * <pre>
 *       root
 *              0
 *           1
 *        1
 *           0
 *        1
 *   leaf
 * </pre>
 * 
 * Using a tree like structure allows to prune searches once the distance of the
 * current branch deviates further away than the threshold would allow.
 * <p>
 * 
 * Currently the tree only allows traversal from the root node allowing to
 * search all hashes which are within a given distance from a needle. A more
 * performant optimization might save the leaves in a hash structure and keep a
 * reference to the parent nodes allowing to start searching from the leaf
 * instead.
 * 
 * @author Kilian
 */
public class BinaryTree<T> {

	/**
	 * The root node of the tree.
	 */
	private Node root;

	/**
	 * Keep track of how many hashes were added to the tree
	 */
	private int hashCount;

	/**
	 * Flag indicating if hashes origin should be checked
	 */
	private boolean ensureHashConsistency;
	/**
	 * The algorithm id all hashes have to match if they want to perform an action
	 */
	private int algoId;

	/**
	 * 
	 * @param ensureHashConsistency If true adding and matching hashes will check
	 *                              weather they are generated by the same
	 *                              algorithms as the first hash added to the tree
	 * 
	 */
	public BinaryTree(boolean ensureHashConsistency) {
		root = new Node();
		this.ensureHashConsistency = ensureHashConsistency;
	}

	/**
	 * Insert a value associated with the supplied hash in the binary tree (similar
	 * to a map). Saved values can be found by invoking
	 * {@link #getElementsWithinHammingDistance}.
	 * <p>
	 * 
	 * Nodes which do not exist will be created. Please be aware that similar to
	 * comparing different hashes for images only hashes produced by the same
	 * algorithms will return useable results.
	 * <p>
	 * 
	 * If the tree is configured to ensureHashConsistency this function will throw
	 * an unchecked IlleglStateException if the added hash does not comply with the
	 * first hash added to the tree.
	 * 
	 * @param hash  The hash used to save the value in the tree
	 * @param value The value which will be returned if the hash . Common values
	 *              point to the image used to create the hash or an id in a SQL
	 *              table
	 * 
	 */
	@SuppressWarnings("unchecked")
	public void addHash(Hash hash, T value) {

		if (ensureHashConsistency) {
			if (algoId == 0) {
				algoId = hash.getAlgorithmId();
			} else {

				if (algoId != hash.getAlgorithmId())
					throw new IllegalStateException("Tried to add an incompatible hash to the binary tree");
			}
		}

		BigInteger hashValue = hash.getHashValue();
		int depth = hash.getBitResolution();
		int ommitedBits = depth - hash.getHashValue().bitLength();

		Node currentNode = root;

		// The hash values does not store preceeding 0 values. Skip those in the tree
		for (int i = 0; i < ommitedBits; i++) {
			Node tempNode = currentNode.getChild(false);
			if (tempNode == null) {
				currentNode = currentNode.createChild(false);
			} else {
				currentNode = tempNode;
			}
		}

		// Compute outstanding bits
		for (int i = depth - 1 - ommitedBits; i > 0; i--) {
			boolean bit = hashValue.testBit(i);
			Node tempNode = currentNode.getChild(bit);
			if (tempNode == null) {
				currentNode = currentNode.createChild(bit);
			} else {
				currentNode = tempNode;
			}
		}

		// We reached the end
		boolean bit = hashValue.testBit(0);
		Node leafNode = currentNode.getChild(bit);
		Leaf<T> leaf;
		if (leafNode != null) {
			leaf = (Leaf<T>) leafNode;
		} else {
			leaf = (Leaf<T>) currentNode.setChild(bit, new Leaf<T>());
		}
		leaf.addData(value);
		hashCount++;
	}

	/**
	 * Return all elements of the tree whose hamming distance is smaller or equal
	 * than the supplied max distance.
	 * 
	 * If the tree is configured to ensureHashConsistency this function will throw
	 * an unchecked IlleglStateException if the checked hash does not comply with
	 * the first hash added to the tree.
	 * 
	 * @param hash        The hash to search for
	 * @param maxDistance The maximal hamming distance deviation all found hashes
	 *                    may possess. A distance of 0 will return all objects added
	 *                    whose hash is exactly the hash supplied as the first
	 *                    argument
	 * 
	 * @return Search results contain objects and distances matching the search
	 *         criteria. The results returned are ordered to return the closest
	 *         match first.
	 */
	public PriorityQueue<Result<T>> getElementsWithinHammingDistance(Hash hash, int maxDistance) {

		if (ensureHashConsistency && algoId != hash.getAlgorithmId()) {
			throw new IllegalStateException("Tried to add an incompatible hash to the binary tree");
		}

		// Iterative implementation. Recursion might get too expensive if the key lenght
		// increases and we need to be aware of the stack depth

		PriorityQueue<Result<T>> result = new PriorityQueue<Result<T>>();

		BigInteger hashValue = hash.getHashValue();
		int treeDepth = hash.getBitResolution();

		ArrayDeque<NodeInfo> queue = new ArrayDeque<>();

		// Breadth first search

		// Begin search at the root
		queue.add(new NodeInfo(root, 0, treeDepth, ""));

		while (!queue.isEmpty()) {

			NodeInfo info = queue.poll();

			// We reached a leaf
			if (info.depth == 0) {
				@SuppressWarnings("unchecked")
				Leaf<T> leaf = (Leaf<T>) info.node;
				for (T o : leaf.getData()) {
					result.add(new Result<T>(o, info.distance,info.distance/(double)treeDepth));
				}
				continue;
			}
			/*
			 * else { System.out.printf("%-8s Depth: %d Distance: %d Next Bit: %s%n",
			 * info.curPath, info.depth, info.distance, hashValue.testBit(info.depth - 1) ?
			 * "1" : "0"); }
			 */

			// Next bit
			boolean bit = hashValue.testBit(info.depth - 1);
			// Are children of the current

			Node correctChild = info.node.getChild(bit);
			if (correctChild != null) {
				queue.add(
						new NodeInfo(correctChild, info.distance, info.depth - 1, (info.curPath + (bit ? "1" : "0"))));
			}

			if (info.distance + 1 <= maxDistance) {
				Node failedChild = info.node.getChild(!bit);
				// Maybe the child does not exist
				if (failedChild != null) {
					queue.add(new NodeInfo(failedChild, info.distance + 1, info.depth - 1,
							(info.curPath + (!bit ? "1" : "0"))));
				}
			}
		}
		return result;
	}

	/**
	 * Helper class to iteratively search the tree {
	 * 
	 * @author Kilian
	 *
	 */
	class NodeInfo {
		protected Node node;
		protected int distance;
		protected int depth;
		
		/** Current path of the node. Used for debugging*/
		String curPath;

		public NodeInfo(Node node, int distance, int depth, String curPath) {
			this.node = node;
			this.distance = distance;
			this.depth = depth;
			this.curPath = curPath;
		}
	}

	/**
	 * @return the root of the binary tree
	 */
	public Node getRoot() {
		return root;
	}

	/**
	 * @return how many hashes were added to the tree
	 */
	public int getHashCount() {
		return hashCount;
	}

	/**
	 * Traverse the tree and output all key = hashes and values found.
	 */
	public void printTree() {
		printTree(root, "");
	}

	/**
	 * Recursively traverse the tree and print all hashes found
	 * 
	 * @param n         The current node whose children will be looked at
	 * @param curString The current hash this node represents
	 */
	@SuppressWarnings("unchecked")
	private void printTree(Node n, String curString) {

		if (n instanceof Leaf) {
			System.out.println("Leaf found: " + curString + " " + ((Leaf<T>) n).getData());
		} else {

			if (n.leftChild != null) {
				printTree(n.leftChild, curString + "1");
			}
			if (n.rightChild != null) {
				printTree(n.rightChild, curString + "0");
			}
		}
	}
}
