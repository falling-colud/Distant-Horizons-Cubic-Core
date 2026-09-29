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

import com.seibel.distanthorizons.core.logging.DhLoggerBuilder;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.util.LodUtil;
import com.seibel.distanthorizons.core.util.objects.quadTree.iterators.QuadNodeDirectChildIterator;
import com.seibel.distanthorizons.core.util.objects.quadTree.iterators.QuadNodeDirectChildPosIterator;
import com.seibel.distanthorizons.core.util.objects.quadTree.iterators.QuadTreeNodeIterator;
import it.unimi.dsi.fastutil.longs.LongIterator;
import com.seibel.distanthorizons.core.logging.DhLogger;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.function.Consumer;

public class QuadNode<T>
{
	private static final DhLogger LOGGER = new DhLoggerBuilder().build();
	
	
	public final long sectionPos;
	/** 
	 * this is the highest detail level this tree can provide.
	 * IE the detail levels that the root nodes in the tree are.
	 */
	public final byte parentTreeLeafDetailLevel;
	@Nullable
	public T value;
	
	
	/**
	 * The child nodes, indexed as {@link DhSectionPos#getChildByIndex(long, int)} indexes them: <br>
	 * bit 0 = +X, bit 1 = +Z, bit 2 = +Y (cubic positions that split vertically only). <br>
	 * Null until the first child is created.
	 */
	@Nullable
	private QuadNode<T>[] children;
	/** how many children this node's position can have: 4, or 8 for a cubic position that splits vertically */
	private final int childSlotCount;
	
	
	
	public QuadNode(long sectionPos, byte parentTreeLeafDetailLevel)
	{
		this.sectionPos = sectionPos;
		this.parentTreeLeafDetailLevel = parentTreeLeafDetailLevel;
		this.childSlotCount = (DhSectionPos.getDetailLevel(sectionPos) > 0) ? DhSectionPos.getChildCount(sectionPos) : 0;
	}
	
	
	
	/** @return the number of non-null direct child nodes */
	public int getDirectChildCount()
	{
		QuadNode<T>[] children = this.children;
		if (children == null)
		{
			return 0;
		}
		
		int count = 0;
		for (int i = 0; i < children.length; i++)
		{
			if (children[i] != null)
			{
				count++;
			}
		}
		return count;
	}
	
	/** @return how many children this node's position can have: 4, or 8 for a cubic position that splits vertically */
	public int getChildSlotCount() { return this.childSlotCount; }
	
	/**
	 * Use {@link QuadNode#getNonNullChildCount()} if you want the number of non-null child values.
	 *
	 * @return the number of non-null child nodes
	 */
	public int getTotalChildCount()
	{
		int count = 0;
		for (int i = 0; i < this.childSlotCount; i++)
		{
			if (this.getChildByIndex(i) != null)
			{
				count++;
			}
		}
		return count;
	}
	
	/** @return the number of children that have non-null values */
	public int getNonNullChildCount()
	{
		int count = 0;
		for (int i = 0; i < this.childSlotCount; i++)
		{
			QuadNode<T> child = this.getChildByIndex(i);
			if (child != null && (child.value != null || child.getNonNullChildCount() != 0))
			{
				count++;
			}
		}
		return count;
	}
	
	
	
	/**
	 * Returns the child node 1 detail level lower <br><br>
	 *
	 * Relative child positions returned for each index: <br>
	 * 0 = (0,0) - North West <br>
	 * 1 = (1,0) - South West <br>
	 * 2 = (0,1) - North East <br>
	 * 3 = (1,1) - South East <br>
	 * 4-7 = the same for the upper half of a cubic position that splits vertically
	 *
	 * @param childIndex must be an int between 0 and {@link #getChildSlotCount()} - 1
	 */
	public @Nullable QuadNode<T> getChildByIndex(int childIndex) throws IllegalArgumentException
	{
		if (childIndex < 0 || childIndex >= this.childSlotCount)
		{
			throw new IllegalArgumentException("childIndex must be between 0 and " + (this.childSlotCount - 1) + ", given [" + childIndex + "].");
		}
		
		QuadNode<T>[] children = this.children;
		return (children != null) ? children[childIndex] : null;
	}
	
	
	/**
	 * @param sectionPos must be 1 detail level lower than this node's detail level
	 * @return the node at the given position
	 * @throws IllegalArgumentException if childSectionPos has the wrong detail level or is outside the bounds of this node
	 */
	public QuadNode<T> getNode(long sectionPos) throws IllegalArgumentException { return this.getOrSetValue(sectionPos, false, null); }
	
	/**
	 * @param sectionPos must be 1 detail level lower than this node's detail level
	 * @return the value at the given position before the new value was set
	 * @throws IllegalArgumentException if childSectionPos has the wrong detail level or is outside the bounds of this node
	 */
	public T setValue(long sectionPos, T newValue) throws IllegalArgumentException
	{
		QuadNode<T> previousNode = this.getNode(sectionPos);
		if (previousNode != null)
		{
			T previousValue = previousNode.value;
			previousNode.value = newValue;
			return previousValue;
		}
		else
		{
			this.getOrSetValue(sectionPos, true, newValue);
			return null;
		}
	}
	
	/**
	 * @param inputSectionPos must be 1 detail level lower than this node's detail level
	 * @return the node at the given position before the new node was set (if the new node should be set)
	 * @throws IllegalArgumentException if childSectionPos has the wrong detail level or is outside the bounds of this
	 */
	private QuadNode<T> getOrSetValue(long inputSectionPos, boolean replaceValue, T newValue) throws IllegalArgumentException
	{
		// debug validation
		
		if (!DhSectionPos.contains(this.sectionPos, inputSectionPos))
		{
			LOGGER.error((replaceValue ? "set " : "get ") + inputSectionPos + " center block: " + DhSectionPos.getCenterBlockPos(inputSectionPos) + ", this pos: " + this.sectionPos + " this center block: " + DhSectionPos.getCenterBlockPos(this.sectionPos));
			throw new IllegalArgumentException("Input section pos " + inputSectionPos + " outside of this quadNode's pos: " + this.sectionPos + ", this node's blockPos: " + DhSectionPos.convertToDetailLevel(this.sectionPos, LodUtil.BLOCK_DETAIL_LEVEL) + " block width: " + DhSectionPos.getBlockWidth(this.sectionPos) + " input detail level: " + DhSectionPos.convertToDetailLevel(inputSectionPos, LodUtil.BLOCK_DETAIL_LEVEL) + " width: " + DhSectionPos.getBlockWidth(inputSectionPos));
		}
		
		if (DhSectionPos.getDetailLevel(inputSectionPos) > DhSectionPos.getDetailLevel(this.sectionPos))
		{
			throw new IllegalArgumentException("detail level higher than this node. Node Detail level: " + DhSectionPos.getDetailLevel(this.sectionPos) + " input detail level: " + DhSectionPos.getDetailLevel(inputSectionPos));
		}
		
		if (DhSectionPos.getDetailLevel(inputSectionPos) == DhSectionPos.getDetailLevel(this.sectionPos) && inputSectionPos != this.sectionPos)
		{
			throw new IllegalArgumentException("Node and input detail level are equal, however positions are not; this tree doesn't contain the requested position. Node pos: " + this.sectionPos + ", input pos: " + DhSectionPos.toString(inputSectionPos));
		}
		
		if (DhSectionPos.getDetailLevel(inputSectionPos) < this.parentTreeLeafDetailLevel)
		{
			throw new IllegalArgumentException("Input position is requesting a detail level lower than what this node can provide. Tree leaf detail level: " + this.parentTreeLeafDetailLevel + ", input pos: " + DhSectionPos.toString(inputSectionPos));
		}
		
		
		
		// get/set logic
		if (DhSectionPos.getDetailLevel(inputSectionPos) == DhSectionPos.getDetailLevel(this.sectionPos))
		{
			// this node is the requested position
			if (replaceValue)
			{
				this.value = newValue;
			}
			return this;
		}
		else
		{
			// this node is a parent to the position requested,
			// recurse to the next node

//			LOGGER.info((replaceValue ? "set " : "get ")+inputSectionPos+" center block: "+inputSectionPos.getCenter().getCornerBlockPos()+", this pos: "+this.sectionPos+" this center block: "+this.sectionPos.getCenter().getCornerBlockPos());
			
			// find the child that contains the input position
			long containingChildPos = DhSectionPos.convertToDetailLevel(inputSectionPos, (byte) (DhSectionPos.getDetailLevel(this.sectionPos) - 1));
			int childIndex = DhSectionPos.getChildIndexOfParent(containingChildPos);
			long childPos = DhSectionPos.getChildByIndex(this.sectionPos, childIndex);
			if (childPos != containingChildPos)
			{
				throw new IllegalStateException("input position ["+DhSectionPos.toString(inputSectionPos)+"] not contained by any child of ["+DhSectionPos.toString(this.sectionPos)+"]. This should've been caught by the contains check before this point.");
			}
			
			QuadNode<T> childNode = this.getChildByIndex(childIndex);
			if (replaceValue && childNode == null)
			{
				// if no node exists for this position, but we want to insert a new value at this position, create a new node
				childNode = new QuadNode<>(childPos, this.parentTreeLeafDetailLevel);
				this.setChild(childIndex, childNode);
			}
			
			// childNode should only be null when replaceValue = false and the end of a node chain has been reached
			return (childNode != null) ? childNode.getOrSetValue(inputSectionPos, replaceValue, newValue) : null;
		}
	}
	
	@SuppressWarnings("unchecked")
	private void setChild(int childIndex, @Nullable QuadNode<T> childNode)
	{
		if (this.children == null)
		{
			if (childNode == null)
			{
				return;
			}
			this.children = (QuadNode<T>[]) new QuadNode[this.childSlotCount];
		}
		this.children[childIndex] = childNode;
	}
	
	
	
	//===========//
	// iterators //
	//===========//
	
	public Iterator<QuadNode<T>> getNodeIterator() { return new QuadTreeNodeIterator<>(this, false, null); }
	public Iterator<QuadNode<T>> getNodeIterator(@Nullable QuadTree.INodeIteratorStoppingFunc<T> stopIteratingFunc) { return new QuadTreeNodeIterator<>(this, false, stopIteratingFunc); }
	public Iterator<QuadNode<T>> getLeafNodeIterator() { return new QuadTreeNodeIterator<>(this, true, null); }
	
	/** positions can point to null children */
	public LongIterator getChildPosIterator() { return new QuadNodeDirectChildPosIterator<>(this); }
	public Iterator<QuadNode<T>> getChildNodeIterator() { return new QuadNodeDirectChildIterator<>(this); }
	
	
	
	//==========//
	// deletion //
	//==========//
	
	public void deleteAllChildren() { this.deleteAllChildren(null); }
	/** @param removedItemConsumer is only fired for non-null nodes, however the value passed in may be null */
	public void deleteAllChildren(@Nullable Consumer<? super T> removedItemConsumer)
	{
		QuadNode<T>[] children = this.children;
		if (children == null)
		{
			return;
		}
		
		for (int i = 0; i < children.length; i++)
		{
			QuadNode<T> childNode = children[i];
			if (childNode != null)
			{
				childNode.deleteAllChildren(removedItemConsumer);
			}
		}
		
		for (int i = 0; i < children.length; i++)
		{
			QuadNode<T> childNode = children[i];
			if (childNode != null && removedItemConsumer != null)
			{
				removedItemConsumer.accept(childNode.value);
			}
			children[i] = null;
		}
		this.children = null;
	}
	
	
	
	//==============//
	// base methods //
	//==============//
	
	@Override
	public String toString() { return "pos: " + DhSectionPos.toString(this.sectionPos) + ", children #: " + this.getTotalChildCount() + ", value: " + this.value; }
	
}
