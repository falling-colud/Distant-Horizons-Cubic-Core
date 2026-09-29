/*
 *    This file is part of the Distant Horizons mod
 *    licensed under the GNU LGPL v3 License.
 *
 *    Copyright (C) 2020 James Seibel
 *
 *    This program is free software: you can redistribute it and/or modify
 *    it under the terms of the GNU Lesser General Public License as published by
 *    the Free Software Foundation, version 3.
 *
 *    This program is distributed in the hope that it will be useful,
 *    but WITHOUT ANY WARRANTY; without even the implied warranty of
 *    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *    GNU Lesser General Public License for more details.
 *
 *    You should have received a copy of the GNU Lesser General Public License
 *    along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.seibel.distanthorizons.core.util.objects.quadTree;

import com.seibel.distanthorizons.core.logging.DhLogger;
import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.blockPos.DhBlockPos2D;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.QuadTree.LodQuadTree;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.coreapi.util.BitShiftUtil;
import com.seibel.distanthorizons.coreapi.util.MathUtil;
import com.seibel.distanthorizons.core.util.gridList.MovableGridRingList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongIterator;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.LongConsumer;

/**
 * This class represents a quadTree of T type values. <br><br>
 *
 * A cubic tree (see {@link #isCubic()}) holds cubic {@link DhSectionPos}itions and is an octree:
 * its root nodes are stacked in vertical layers that follow the tree's center up and down,
 * limited to {@link #getVerticalRadiusInBlocks()} above and below it and to the level's height.
 */
public class QuadTree<T>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();



	/**
	 * The largest numerical detail level this tree supports. <br>
	 * IE: the detail level used by the root nodes.
	 */
	public final byte treeRootDetailLevel;
	/**
	 * The smallest numerical detail level this tree supports. <br>
	 * IE: the detail level used by the leaf nodes.
	 */
	public final byte treeLeafDetailLevel;

	private final int diameterInBlocks;

	/** true if this tree holds cubic (3D) positions */
	private final boolean cubic;
	/** how far above and below the center the tree reaches, in blocks. Only used by cubic trees. */
	private final int verticalRadiusInBlocks;
	/** the lowest block Y the tree may reach. Only used by cubic trees. */
	private final int levelMinBlockY;
	/** one past the highest block Y the tree may reach. Only used by cubic trees. */
	private final int levelMaxBlockY;
	/** how tall a root node is in blocks. Only used by cubic trees. */
	private final int rootBlockHeight;

	/**
	 * contain the actual data in the quad tree structure. <br>
	 * One {@link RootLayer} per vertical layer of root nodes, sorted from bottom to top;
	 * 2D trees always have exactly one layer. <br>
	 * Replaced as a whole (never modified in place) so readers can walk a consistent copy.
	 */
	private volatile RootLayer<T>[] rootLayers;
	private final int rootRingHalfWidth;

	private DhBlockPos2D centerBlockPos;
	/** the center's Y. Only used by cubic trees. */
	private int centerBlockY;
	/**
	 * defines how many blocks the center needs to move in blocks
	 * before we check for out-of-bound nodes.
	 */
	private int blockDistanceForNodeClearing;



	//=============//
	// constructor //
	//=============//
	//region

	/**
	 * Constructor of a 2D quadTree
	 *
	 * @param diameterInBlocks equivalent to the distance between the two opposing sides
	 */
	public QuadTree(
		int diameterInBlocks, int blockDistanceForNodeClearing,
		DhBlockPos2D centerBlockPos, byte treeLeafDetailLevel)
	{ this(diameterInBlocks, blockDistanceForNodeClearing, centerBlockPos, treeLeafDetailLevel, false, 0, 0, 0, 0); }

	/**
	 * Constructor of a cubic tree (an octree over cubic {@link DhSectionPos}itions).
	 *
	 * @param diameterInBlocks equivalent to the distance between the two opposing sides
	 * @param verticalRadiusInBlocks how far above and below the center the tree reaches
	 * @param levelMinBlockY the lowest block Y the tree may reach
	 * @param levelMaxBlockY one past the highest block Y the tree may reach
	 */
	public QuadTree(
		int diameterInBlocks, int blockDistanceForNodeClearing,
		DhBlockPos2D centerBlockPos, int centerBlockY, byte treeLeafDetailLevel,
		int verticalRadiusInBlocks, int levelMinBlockY, int levelMaxBlockY)
	{ this(diameterInBlocks, blockDistanceForNodeClearing, centerBlockPos, treeLeafDetailLevel, true, centerBlockY, verticalRadiusInBlocks, levelMinBlockY, levelMaxBlockY); }

	/** 
	 * @param cubic if false every vertical argument is ignored 
	 * @see QuadTree#QuadTree(int, int, DhBlockPos2D, int, byte, int, int, int) 
	 */
	protected QuadTree(
		int diameterInBlocks, int blockDistanceForNodeClearing,
		DhBlockPos2D centerBlockPos, byte treeLeafDetailLevel,
		boolean cubic, int centerBlockY, int verticalRadiusInBlocks, int levelMinBlockY, int levelMaxBlockY)
	{
		this.centerBlockPos = centerBlockPos;
		this.diameterInBlocks = diameterInBlocks;
		this.blockDistanceForNodeClearing = blockDistanceForNodeClearing;

		this.treeLeafDetailLevel = treeLeafDetailLevel;
		// the min detail level must be greater than 0 (to prevent divide by 0 errors) and greater than the maximum detail level
		this.treeRootDetailLevel = (byte) Math.max(Math.max(1, this.treeLeafDetailLevel), MathUtil.log2(diameterInBlocks));

		int halfSizeInRootNodes = Math.floorDiv(this.diameterInBlocks, 2) / BitShiftUtil.powerOfTwo(this.treeRootDetailLevel);
		halfSizeInRootNodes = halfSizeInRootNodes + 1; // always add 1 so nodes will always have a parent, even if the tree's center is offset from the root node grid
		this.rootRingHalfWidth = halfSizeInRootNodes;

		this.cubic = cubic;
		if (cubic)
		{
			if (this.treeLeafDetailLevel < DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL)
			{
				throw new IllegalArgumentException("Cubic trees need a leaf detail level of at least ["+DhSectionPos.SECTION_MINIMUM_DETAIL_LEVEL+"], given ["+this.treeLeafDetailLevel+"].");
			}
			if (levelMaxBlockY <= levelMinBlockY)
			{
				throw new IllegalArgumentException("Cubic trees need a positive height, given min Y ["+levelMinBlockY+"] and max Y ["+levelMaxBlockY+"].");
			}

			this.verticalRadiusInBlocks = Math.max(0, verticalRadiusInBlocks);
			this.levelMinBlockY = levelMinBlockY;
			this.levelMaxBlockY = levelMaxBlockY;
			this.rootBlockHeight = DhSectionPos.getCubicBlockHeight(this.treeRootDetailLevel);
			this.centerBlockY = centerBlockY;
		}
		else
		{
			this.verticalRadiusInBlocks = 0;
			this.levelMinBlockY = 0;
			this.levelMaxBlockY = 0;
			this.rootBlockHeight = 0;
			this.centerBlockY = 0;
		}

		this.rootLayers = this.createLayers(null, null);
	}

	//endregion



	//==================//
	// property getters //
	//==================//
	//region

	/** @return the number of non-null nodes in the tree */
	public int nodeCount()
	{
		int count = 0;
		for (RootLayer<T> layer : this.rootLayers)
		{
			for (QuadNode<T> node : layer.ringList)
			{
				if (node == null)
				{
					continue;
				}

				Iterator<QuadNode<T>> nodeIterator = node.getNodeIterator();
				while (nodeIterator.hasNext())
				{
					if (nodeIterator.next().value != null)
					{
						count++;
					}
				}
			}
		}

		return count;
	}

	/** @return the number of leaf nodes in the tree */
	public int leafNodeCount()
	{
		int count = 0;
		for (RootLayer<T> layer : this.rootLayers)
		{
			for (QuadNode<T> rootNode : layer.ringList)
			{
				if (rootNode == null)
				{
					continue;
				}

				Iterator<QuadNode<T>> leafNodeIterator = rootNode.getLeafNodeIterator();
				while (leafNodeIterator.hasNext())
				{
					QuadNode<T> node = leafNodeIterator.next();
					if (node != null
						&& this.isSectionPosInBounds(node.sectionPos))
					{
						count++;
					}
				}
			}
		}

		return count;
	}


	/**
	 * A tree will always have 9 root nodes per vertical layer,
	 * this is because the tree will grow all the way up to the top where it is a 3x3 grid.
	 * If this is ever changed then these values must also change
	 *
	 */
	public int ringListWidth() { return 3; }
	public int ringListHalfWidth() { return 1; }
	public int diameterInBlocks() { return this.diameterInBlocks; }

	/** @return true if this tree holds cubic (3D) positions */
	public boolean isCubic() { return this.cubic; }
	/** @return how far above and below the center a cubic tree reaches, 0 for 2D trees */
	public int getVerticalRadiusInBlocks() { return this.verticalRadiusInBlocks; }
	/** @return how many vertical layers of root nodes the tree currently has (always 1 for 2D trees) */
	public int rootLayerCount() { return this.rootLayers.length; }

	//endregion



	//==========================//
	// node getters and setters //
	//==========================//
	//region

	/** @return the value at the given section position. Null will be returned if the value is missing or the position is out of bounds. */
	@Nullable
	public final T tryGetValue(long pos)
	{
		QuadNode<T> node = this.tryGetNode(pos);
		if (node != null)
		{
			return node.value;
		}
		return null;
	}

	/** @return the node at the given section position, null if out of bounds */
	@Nullable
	public final QuadNode<T> tryGetNode(long pos) { return this.getOrSetNode(pos, false, null, false); }


	/** @return the node at the given section position */
	@Nullable
	public final QuadNode<T> getNode(long pos) throws IndexOutOfBoundsException { return this.getOrSetNode(pos, false, null, true); }

	/** @return the value at the given section position */
	@Nullable
	public final T getValue(long pos) throws IndexOutOfBoundsException
	{
		QuadNode<T> node = this.getNode(pos);
		if (node != null)
		{
			return node.value;
		}
		return null;
	}

	/** @return the value that was previously in the given position, null if nothing */
	@Nullable
	public final T setValue(long pos, T value) throws IndexOutOfBoundsException
	{
		T previousValue = this.getValue(pos);
		this.getOrSetNode(pos, true, value, true);
		return previousValue;
	}

	/** @param throwIfOutOfBounds if false returns null */
	@Nullable
	protected final QuadNode<T> getOrSetNode(long pos, boolean setNewValue, T newValue, boolean throwIfOutOfBounds) throws IndexOutOfBoundsException
	{
		if (!this.isSectionPosInBounds(pos))
		{
			// how should out-of-bounds positions be handled?
			if (throwIfOutOfBounds)
			{
				int radius = this.diameterInBlocks() / 2;
				DhBlockPos2D minBlockPos = this.getCenterBlockPos().add(new DhBlockPos2D(-radius, -radius));
				DhBlockPos2D maxBlockPos = this.getCenterBlockPos().add(new DhBlockPos2D(radius, radius));
				throw new IndexOutOfBoundsException("QuadTree GetOrSet failed. Position out of bounds, min block pos: [" + minBlockPos + "], max block pos: [" + maxBlockPos + "], "
					+ (this.cubic ? "Y range: [" + this.getWindowMinBlockY() + "," + this.getWindowMaxBlockY() + "), " : "")
					+ "leaf detail level: [" + this.treeLeafDetailLevel + "], root detail level: [" + this.treeRootDetailLevel + "]. Requested section pos: [" + DhSectionPos.toString(pos) + "].");
			}
			else
			{
				return null;
			}
		}



		long rootPos = DhSectionPos.convertToDetailLevel(pos, this.treeRootDetailLevel);
		int ringListPosX = DhSectionPos.getX(rootPos);
		int ringListPosZ = DhSectionPos.getZ(rootPos);

		RootLayer<T> layer = this.getLayer(DhSectionPos.getY(rootPos));
		if (layer == null)
		{
			// can happen if the tree moved vertically between the bounds check and here
			if (throwIfOutOfBounds)
			{
				throw new IndexOutOfBoundsException("QuadTree GetOrSet failed. No root layer for the requested section pos: [" + DhSectionPos.toString(pos) + "].");
			}
			return null;
		}

		QuadNode<T> rootQuadNode = layer.ringList.get(ringListPosX, ringListPosZ);
		if (rootQuadNode == null)
		{
			if (!setNewValue)
			{
				return null;
			}

			rootQuadNode = new QuadNode<T>(rootPos, this.treeLeafDetailLevel);
			boolean successfullyAdded = layer.ringList.set(ringListPosX, ringListPosZ, rootQuadNode);
			if (!successfullyAdded)
			{
				LodUtil.assertNotReach("Failed to add root quadTree node at position: ["+DhSectionPos.toString(rootPos)+"]");
			}
		}

		if (!DhSectionPos.contains(rootQuadNode.sectionPos, pos))
		{
			LodUtil.assertNotReach("failed to get a root node that contains the input position: " + pos + " root node pos: " + rootQuadNode.sectionPos);
		}


		QuadNode<T> returnNode = rootQuadNode.getNode(pos);
		if (setNewValue)
		{
			rootQuadNode.setValue(pos, newValue);
		}
		return returnNode;
	}

	public boolean isSectionPosInBounds(long testPos)
	{
		// check if the testPos is within the detail level limits of the tree
		boolean detailLevelWithinBounds = this.treeLeafDetailLevel <= DhSectionPos.getDetailLevel(testPos) && DhSectionPos.getDetailLevel(testPos) <= this.treeRootDetailLevel;
		if (!detailLevelWithinBounds)
		{
			return false;
		}

		// a cubic tree only holds cubic positions and a 2D tree only 2D ones
		if (DhSectionPos.isCubic(testPos) != this.cubic)
		{
			return false;
		}


		// check if the testPos is within the X,Z boundary of the tree
		DhBlockPos2D treeBlockCorner = this.centerBlockPos.add(new DhBlockPos2D(-this.diameterInBlocks / 2, -this.diameterInBlocks / 2));
		long treeCornerPos = DhSectionPos.encode((byte) 0, treeBlockCorner.x, treeBlockCorner.z);

		long inputSectionCorner = DhSectionPos.convertToDetailLevel(testPos, (byte) 0);
		long inputCornerPos = DhSectionPos.encode((byte) 0, DhSectionPos.getX(inputSectionCorner), DhSectionPos.getZ(inputSectionCorner));
		int inputBlockWidth = BitShiftUtil.powerOfTwo(DhSectionPos.getDetailLevel(testPos));

		if (!DoSquaresOverlap(treeCornerPos, this.diameterInBlocks, inputCornerPos, inputBlockWidth))
		{
			return false;
		}

		if (!this.cubic)
		{
			return true;
		}


		// check if the testPos is within the vertical window of the tree
		int windowMinY = this.getWindowMinBlockY();
		int windowMaxY = this.getWindowMaxBlockY();
		if (windowMinY >= windowMaxY)
		{
			// the center is too far outside the level
			return false;
		}

		int minY = DhSectionPos.getCubicMinBlockY(testPos);
		int maxY = minY + DhSectionPos.getCubicBlockHeight(testPos);
		return minY < windowMaxY
			&& maxY > windowMinY;
	}
	private static boolean DoSquaresOverlap(long square1Min, int square1Width, long square2Min, int square2Width)
	{
		// Determine the coordinates of the squares (the variables say rect[angle] because this logic would also work there and was simplified to work for squares)
		float rect1MinX = DhSectionPos.getX(square1Min);
		float rect1MaxX = DhSectionPos.getX(square1Min) + square1Width;
		float rect1MinZ = DhSectionPos.getZ(square1Min);
		float rect1MaxZ = DhSectionPos.getZ(square1Min) + square1Width;

		float rect2MinX = DhSectionPos.getX(square2Min);
		float rect2MaxX = DhSectionPos.getX(square2Min) + square2Width;
		float rect2MinZ = DhSectionPos.getZ(square2Min);
		float rect2MaxZ = DhSectionPos.getZ(square2Min) + square2Width;

		// Check if the squares overlap
		return
				rect1MinX < rect2MaxX &&
						rect1MaxX > rect2MinX &&
						rect1MinZ < rect2MaxZ &&
						rect1MaxZ > rect2MinZ;
	}


	public int getNonNullChildCountAtPos(long pos) { return this.getChildCountAtPos(pos, false); }
	public int getChildCountAtPos(long pos, boolean includeNullValues)
	{
		int childCount = 0;
		int childSlotCount = DhSectionPos.getChildCount(pos);
		for (int i = 0; i < childSlotCount; i++)
		{
			long childPos = DhSectionPos.getChildByIndex(pos, i);
			if (this.isSectionPosInBounds(childPos))
			{
				T value = this.getValue(childPos);
				if (includeNullValues || value != null)
				{
					childCount++;
				}
			}
		}

		return childCount;
	}

	//endregion



	//===========//
	// iterators //
	//===========//
	//region

	/** can include null nodes */
	public LongIterator rootNodePosIterator() { return new QuadTreeRootPosIterator(true, null); }

	/** @see INodeIteratorStoppingFunc */
	public Iterator<QuadNode<T>> nodeIteratorWithStoppingFilter(INodeIteratorStoppingFunc<T> stoppingFilterFunc) { return new QuadTreeNodeIterator(false, stoppingFilterFunc); }
	public Iterator<QuadNode<T>> nodeIterator() { return new QuadTreeNodeIterator(false, null); }
	public Iterator<QuadNode<T>> leafNodeIterator() { return new QuadTreeNodeIterator(true, null); }

	//endregion



	//================//
	// get/set center //
	//================//
	//region

	public void setCenterBlockPos(DhBlockPos2D newCenterPos) { this.setCenterBlockPos(newCenterPos, null, null); }
	/**
	 * @param removedConsumer fired when a root node is completely removed from the underlying data structure
	 * @param mutateOutOfBoundConsumer fired when a child node is out of bounds, but not removed from the underlying data structure
	 */
	public void setCenterBlockPos(
		DhBlockPos2D newCenterPos,
		@Nullable Consumer<? super T> removedConsumer,
		@Nullable Consumer<? super T> mutateOutOfBoundConsumer)
	{ this.setCenterBlockPos(newCenterPos, this.centerBlockY, removedConsumer, mutateOutOfBoundConsumer); }
	/**
	 * @param newCenterBlockY ignored by 2D trees
	 * @param removedConsumer fired when a root node is completely removed from the underlying data structure
	 * @param mutateOutOfBoundConsumer fired when a child node is out of bounds, but not removed from the underlying data structure
	 */
	public void setCenterBlockPos(
		DhBlockPos2D newCenterPos, int newCenterBlockY,
		@Nullable Consumer<? super T> removedConsumer,
		@Nullable Consumer<? super T> mutateOutOfBoundConsumer)
	{
		if (!this.cubic)
		{
			newCenterBlockY = 0;
		}

		// did we move significantly?
		boolean ringListMoved = false;
		int newCenterPosX = BitShiftUtil.divideByPowerOfTwo(newCenterPos.x, this.treeRootDetailLevel);
		int newCenterPosZ = BitShiftUtil.divideByPowerOfTwo(newCenterPos.z, this.treeRootDetailLevel);
		RootLayer<T>[] oldLayers = this.rootLayers;
		MovableGridRingList.Pos2D oldRingCenter = oldLayers[0].ringList.getCenter();
		if (oldRingCenter.getX() != newCenterPosX
			|| oldRingCenter.getY() != newCenterPosZ)
		{
			ringListMoved = true;
		}

		boolean layersChanged = false;
		if (this.cubic)
		{
			int oldMinLayer = oldLayers[0].layerY;
			int oldMaxLayer = oldLayers[oldLayers.length - 1].layerY;
			layersChanged = oldMinLayer != this.getMinLayerY(newCenterBlockY)
				|| oldMaxLayer != this.getMaxLayerY(newCenterBlockY);
		}

		// did we move a little bit?
		boolean recalculateOutOfBoundNodes = false;
		int centerBlockDistance = this.centerBlockPos.manhattanDist(newCenterPos) + Math.abs(this.centerBlockY - newCenterBlockY);
		if (centerBlockDistance >= this.blockDistanceForNodeClearing)
		{
			recalculateOutOfBoundNodes = true;
		}

		if (!ringListMoved
			&& !layersChanged
			&& !recalculateOutOfBoundNodes)
		{
			// the tree didn't move enough that we need
			// to re-calculate anything
			return;
		}



		this.centerBlockPos = newCenterPos;
		this.centerBlockY = newCenterBlockY;

		// remove out of bound root layers
		if (layersChanged)
		{
			this.rootLayers = this.createLayers(oldLayers, removedConsumer);
		}

		// remove out of bound root nodes
		for (RootLayer<T> layer : this.rootLayers)
		{
			layer.ringList.moveTo(newCenterPosX, newCenterPosZ, (quadNode) ->
			{
				if (quadNode != null)
				{
					quadNode.deleteAllChildren(removedConsumer);

					if (removedConsumer != null)
					{
						removedConsumer.accept(quadNode.value);
					}
				}
			});
		}

		// mutate out of bound child nodes
		for (RootLayer<T> layer : this.rootLayers)
		{
			layer.ringList.forEach((rootNode) ->
			{
				this.mutateOutOfBoundChildNodes(rootNode, mutateOutOfBoundConsumer);
			});
		}
	}
	/**
	 * we don't want to actually remove nodes or node data
	 * since that can cause the {@link LodQuadTree} to
	 * flash low-detail LODs when moving into previously-loaded
	 * LODs, which is really disorienting.
	 */
	private void mutateOutOfBoundChildNodes(@Nullable QuadNode<T> quadNode, @Nullable Consumer<? super T> mutateOutOfBoundConsumer)
	{
		// nodes shouldn't be null, but just in case
		if (quadNode == null)
		{
			return;
		}

		// go over each child node
		for (int i = 0; i < quadNode.getChildSlotCount(); i++)
		{
			QuadNode<T> childNode = quadNode.getChildByIndex(i);
			if (childNode == null
				|| childNode.value == null)
			{
				// no need to go any deeper if this node is already empty
				continue;
			}

			// mutate nodes from the bottom up
			this.mutateOutOfBoundChildNodes(childNode, mutateOutOfBoundConsumer);

			// mutate this node if out of bounds
			if (!this.isSectionPosInBounds(childNode.sectionPos))
			{
				if (mutateOutOfBoundConsumer != null)
				{
					mutateOutOfBoundConsumer.accept(childNode.value);
				}
			}
		}
	}

	public final DhBlockPos2D getCenterBlockPos() { return this.centerBlockPos; }
	/** @return the center's Y, always 0 for 2D trees */
	public final int getCenterBlockY() { return this.centerBlockY; }

	//endregion



	//===============//
	// vertical grid //
	//===============//
	//region

	/** 
	 * @return the lowest block Y inside the tree's vertical window (cubic trees). 
	 * The window is empty (min &gt;= max) when the center is further than the vertical radius outside the level. 
	 */
	private int getWindowMinBlockY() { return Math.max(this.levelMinBlockY, this.centerBlockY - this.verticalRadiusInBlocks); }
	/** @return one past the highest block Y inside the tree's vertical window (cubic trees) */
	private int getWindowMaxBlockY() { return Math.min(this.levelMaxBlockY, this.centerBlockY + this.verticalRadiusInBlocks + 1); }
	
	/** 
	 * The layers cover the vertical window, clamped into the level. 
	 * If the window lies outside the level the tree keeps one layer at the nearest edge,
	 * which holds nothing since no position is in bounds.
	 */
	private int getMinLayerY(int centerBlockY)
	{
		int windowMin = MathUtil.clamp(this.levelMinBlockY, centerBlockY - this.verticalRadiusInBlocks, this.levelMaxBlockY - 1);
		return Math.floorDiv(windowMin, this.rootBlockHeight);
	}
	private int getMaxLayerY(int centerBlockY)
	{
		int windowMaxInclusive = MathUtil.clamp(this.levelMinBlockY, centerBlockY + this.verticalRadiusInBlocks, this.levelMaxBlockY - 1);
		return Math.max(this.getMinLayerY(centerBlockY), Math.floorDiv(windowMaxInclusive, this.rootBlockHeight));
	}
	
	/**
	 * Creates the root layers for the current center. Layers that still exist are reused,
	 * everything in the dropped ones is handed to the removed consumer.
	 */
	@SuppressWarnings("unchecked")
	private RootLayer<T>[] createLayers(@Nullable RootLayer<T>[] oldLayers, @Nullable Consumer<? super T> removedConsumer)
	{
		int ringCenterX = BitShiftUtil.divideByPowerOfTwo(this.centerBlockPos.x, this.treeRootDetailLevel);
		int ringCenterZ = BitShiftUtil.divideByPowerOfTwo(this.centerBlockPos.z, this.treeRootDetailLevel);

		int minLayer = this.cubic ? this.getMinLayerY(this.centerBlockY) : 0;
		int maxLayer = this.cubic ? this.getMaxLayerY(this.centerBlockY) : 0;

		RootLayer<T>[] newLayers = (RootLayer<T>[]) new RootLayer[maxLayer - minLayer + 1];
		for (int layerY = minLayer; layerY <= maxLayer; layerY++)
		{
			RootLayer<T> existing = null;
			if (oldLayers != null)
			{
				for (RootLayer<T> oldLayer : oldLayers)
				{
					if (oldLayer.layerY == layerY)
					{
						existing = oldLayer;
						break;
					}
				}
			}

			newLayers[layerY - minLayer] = (existing != null)
				? existing
				: new RootLayer<T>(layerY, new MovableGridRingList<QuadNode<T>>(this.rootRingHalfWidth, ringCenterX, ringCenterZ));
		}

		// clear the layers that are no longer in range
		if (oldLayers != null)
		{
			for (RootLayer<T> oldLayer : oldLayers)
			{
				if (oldLayer.layerY >= minLayer && oldLayer.layerY <= maxLayer)
				{
					continue;
				}

				oldLayer.ringList.clear((quadNode) ->
				{
					if (quadNode != null)
					{
						quadNode.deleteAllChildren(removedConsumer);

						if (removedConsumer != null)
						{
							removedConsumer.accept(quadNode.value);
						}
					}
				});
			}
		}

		return newLayers;
	}

	@Nullable
	private RootLayer<T> getLayer(int layerY)
	{
		RootLayer<T>[] layers = this.rootLayers;
		int index = layerY - layers[0].layerY;
		if (index < 0 || index >= layers.length)
		{
			return null;
		}
		return layers[index];
	}

	/** One vertical layer of root nodes. */
	private static final class RootLayer<T>
	{
		/** the root nodes' vertical index, 0 for 2D trees */
		final int layerY;
		final MovableGridRingList<QuadNode<T>> ringList;

		RootLayer(int layerY, MovableGridRingList<QuadNode<T>> ringList)
		{
			this.layerY = layerY;
			this.ringList = ringList;
		}
	}

	//endregion



	//==============//
	// base methods //
	//==============//
	//region

	public boolean isEmpty() { return this.nodeCount() == 0; } // this should be rewritten to short-circuit

	@Override
	public String toString()
	{
		return "center block: " + this.centerBlockPos +
				(this.cubic ? ", center Y: " + this.centerBlockY + ", root layers: " + this.rootLayers.length : "") +
				", block width: " + this.diameterInBlocks +
				", detail level range: [" + this.treeLeafDetailLevel + "-" + this.treeRootDetailLevel + "], " +
				"leaf #: " + this.leafNodeCount();
	}

	//endregion



	//==================//
	// iterator classes //
	//==================//
	//region

	/** @see INodeIteratorStoppingFunc#iteratorShouldStop(QuadNode)  */
	@FunctionalInterface
	public interface INodeIteratorStoppingFunc<T>
	{
		/** if this function returns true then the iterator will stop walking down the tree */
		boolean iteratorShouldStop(QuadNode<T> node);
	}

	private class QuadTreeRootPosIterator implements LongIterator
	{
		private final LongArrayFIFOQueue iteratorPosQueue;
		@Nullable
		private final INodeIteratorStoppingFunc<T> stopIteratingFunc;



		public QuadTreeRootPosIterator(boolean includeNullNodes, @Nullable INodeIteratorStoppingFunc<T> stopIteratingFunc)
		{
			this.iteratorPosQueue = new LongArrayFIFOQueue();
			this.stopIteratingFunc = stopIteratingFunc;

			// layers nearest to the center first, the ring list walks each layer center-out
			RootLayer<T>[] layers = QuadTree.this.rootLayers.clone();
			if (layers.length > 1)
			{
				final int centerLayer = QuadTree.this.cubic ? Math.floorDiv(QuadTree.this.centerBlockY, QuadTree.this.rootBlockHeight) : 0;
				Arrays.sort(layers, (a, b) -> Integer.compare(Math.abs(a.layerY - centerLayer), Math.abs(b.layerY - centerLayer)));
			}

			for (RootLayer<T> layer : layers)
			{
				layer.ringList.forEachPosOrdered((node, pos2D) ->
				{
					if (this.stopIteratingFunc != null
						&& this.stopIteratingFunc.iteratorShouldStop(node))
					{
						return;
					}

					if (node != null
						|| includeNullNodes)
					{
						long rootPos = QuadTree.this.cubic
							? DhSectionPos.encodeCubic(QuadTree.this.treeRootDetailLevel, pos2D.getX(), layer.layerY, pos2D.getY())
							: DhSectionPos.encode(QuadTree.this.treeRootDetailLevel, pos2D.getX(), pos2D.getY());
						if (QuadTree.this.isSectionPosInBounds(rootPos))
						{
							this.iteratorPosQueue.enqueue(rootPos);
						}
					}
				});
			}
		}// constructor



		@Override
		public boolean hasNext() { return this.iteratorPosQueue.size() != 0; }

		@Override
		public long nextLong()
		{
			if (this.iteratorPosQueue.size() == 0)
			{
				throw new NoSuchElementException();
			}


			long sectionPos = this.iteratorPosQueue.dequeueLong();
			return sectionPos;
		}


		/** Unimplemented */
		@Override
		public void remove() { throw new UnsupportedOperationException("remove"); }

		@Override
		public void forEachRemaining(LongConsumer action) { LongIterator.super.forEachRemaining(action); }

	}

	private class QuadTreeNodeIterator implements Iterator<QuadNode<T>>
	{
		private final QuadTreeRootPosIterator rootNodePosIterator;
		private Iterator<QuadNode<T>> currentNodeIterator;

		private QuadNode<T> lastNode = null;

		private final boolean onlyReturnLeaves;
		@Nullable
		private final INodeIteratorStoppingFunc<T> stopIteratingFunc;



		public QuadTreeNodeIterator(boolean onlyReturnLeaves, @Nullable INodeIteratorStoppingFunc<T> stopIteratingFunc)
		{
			this.rootNodePosIterator = new QuadTreeRootPosIterator(false, stopIteratingFunc);
			this.onlyReturnLeaves = onlyReturnLeaves;

			this.stopIteratingFunc = stopIteratingFunc;
		}



		@Override
		public boolean hasNext()
		{
			if (!this.rootNodePosIterator.hasNext()
				&& this.currentNodeIterator != null
				&& !this.currentNodeIterator.hasNext())
			{
				return false;
			}


			if (this.currentNodeIterator == null
				|| !this.currentNodeIterator.hasNext())
			{
				this.currentNodeIterator = this.getNextChildNodeIterator();
			}

			return this.currentNodeIterator != null && this.currentNodeIterator.hasNext();
		}

		@Override
		public QuadNode<T> next()
		{
			if (this.currentNodeIterator == null
				|| !this.currentNodeIterator.hasNext())
			{
				this.currentNodeIterator = this.getNextChildNodeIterator();
			}


			this.lastNode = this.currentNodeIterator.next();
			return this.lastNode;
		}

		/** @return null if no new iterator could be found */
		private Iterator<QuadNode<T>> getNextChildNodeIterator()
		{
			Iterator<QuadNode<T>> nodeIterator = null;
			while ((nodeIterator == null || !nodeIterator.hasNext())
					&& this.rootNodePosIterator.hasNext())
			{
				long sectionPos = this.rootNodePosIterator.nextLong();

				// try-get to prevent concurrency errors if the tree is being moved while we walk through it
				QuadNode<T> rootNode = QuadTree.this.tryGetNode(sectionPos);
				if (rootNode != null)
				{
					nodeIterator = this.onlyReturnLeaves ? rootNode.getLeafNodeIterator() : rootNode.getNodeIterator(this.stopIteratingFunc);
				}
			}
			return nodeIterator;
		}


		@Override
		public void remove()
		{
			if (this.lastNode == null)
			{
				throw new NoSuchElementException("No last node found.");
			}


			QuadNode<T> node = QuadTree.this.getOrSetNode(this.lastNode.sectionPos, true, null, false);
			if (node != null)
			{
				node.deleteAllChildren();
			}
		}

		@Override
		public void forEachRemaining(Consumer<? super QuadNode<T>> action) { Iterator.super.forEachRemaining(action); }

	}

	//endregion


}
